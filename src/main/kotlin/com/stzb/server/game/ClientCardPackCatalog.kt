package com.stzb.server.game

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class ClientCardPack(
    val packId: Int,
    val parentPackId: Int,
    val containerPackId: Int,
    val priority: Int,
    val heroIds: List<Int>,
)

/**
 * All card packs contained in the client, including every season variant.
 *
 * The client only uses Tb_user_card_extract to decide which configured packs
 * are active. Pack layout and labels remain client-owned; the server merely
 * activates every unique id and uses tb_cfg_card_prob_* as the draw source.
 */
object ClientCardPackCatalog {
    private val packs: List<ClientCardPack> by lazy { load() }
    private val packsById: Map<Int, ClientCardPack> by lazy { packs.associateBy(ClientCardPack::packId) }
    private val packIndexById: Map<Int, Int> by lazy {
        packs.mapIndexed { index, pack -> pack.packId to index }.toMap()
    }

    fun allPacks(): List<ClientCardPack> = packs

    fun heroIdsForPack(packId: Int): List<Int> = packsById[packId]?.heroIds.orEmpty()

    fun summonUid(userId: Int, packId: Int): Int {
        val index = packIndexById[packId] ?: error("unknown client card pack: $packId")
        return userId * SUMMON_UID_STRIDE + index + 1
    }

    fun packIdForSummonUid(userId: Int, summonUid: Int): Int? {
        val index = summonUid - userId * SUMMON_UID_STRIDE - 1
        return packs.getOrNull(index)?.packId
    }

    private fun load(): List<ClientCardPack> {
        val roots = cardExtractResources()
        val heroIdsByPack = linkedMapOf<Int, LinkedHashSet<Int>>()
        val extractRows = linkedMapOf<Int, CardExtractRow>()

        roots.forEach { resource ->
            val suffix = resource.fileName.removePrefix("tb_cfg_card_extract").removeSuffix(".bin")
            val probFileName = "tb_cfg_card_prob$suffix.bin"
            parseCardExtract(resource.bytes, resource.fileName).forEach { row ->
                extractRows.putIfAbsent(row.packId, row)
            }
            resource.readSibling(probFileName)?.let { bytes ->
                parseCardProb(bytes, probFileName).forEach { (packId, heroIds) ->
                    heroIdsByPack.getOrPut(packId, ::linkedSetOf).addAll(heroIds)
                }
            }
        }

        return extractRows.values
            .sortedWith(compareBy<CardExtractRow> { it.priority }.thenBy { it.packId })
            .mapNotNull { row ->
                val heroIds = resolveHeroIds(row.packId, extractRows.values, heroIdsByPack)
                if (heroIds.isEmpty()) return@mapNotNull null
                ClientCardPack(
                    packId = row.packId,
                    parentPackId = row.parentPackId,
                    containerPackId = row.containerPackId,
                    priority = row.priority,
                    heroIds = heroIds,
                )
            }
    }

    private fun resolveHeroIds(
        packId: Int,
        rows: Collection<CardExtractRow>,
        directPools: Map<Int, Set<Int>>,
        visited: Set<Int> = emptySet(),
    ): List<Int> {
        directPools[packId]?.takeIf(Set<Int>::isNotEmpty)?.let { return it.toList() }
        if (packId in visited) return emptyList()

        val childIds = rows.asSequence()
            .filter { it.parentPackId == packId || it.containerPackId == packId }
            .map(CardExtractRow::packId)
            .distinct()
            .toList()
        return childIds
            .flatMap { childId -> resolveHeroIds(childId, rows, directPools, visited + packId) }
            .distinct()
    }

    private fun cardExtractResources(): List<ClientResource> {
        val classLoader = ClientCardPackCatalog::class.java.classLoader
        val index = classLoader.getResourceAsStream("client-config/card-pack-files.txt")
            ?.bufferedReader()
            ?.useLines { lines -> lines.filter(String::isNotBlank).toList() }
            .orEmpty()
        if (index.isNotEmpty()) {
            return index.sortedWith(cardPackFileComparator()).map { fileName ->
                val bytes = classLoader.getResourceAsStream("client-config/$fileName")
                    ?.use { it.readBytes() }
                    ?: error("missing bundled client card-pack table: $fileName")
                ClientResource(fileName, bytes) { sibling ->
                    classLoader.getResourceAsStream("client-config/$sibling")?.use { it.readBytes() }
                }
            }
        }

        val defaultRoot = resolveClientTableRoot().resolve("default")
        return Files.list(defaultRoot).use { files ->
            files
                .filter { it.fileName.toString().matches(CARD_EXTRACT_FILE) }
                .sorted(cardPackPathComparator())
                .map { path ->
                    ClientResource(path.fileName.toString(), Files.readAllBytes(path)) { sibling ->
                        path.parent.resolve(sibling).takeIf(Path::exists)?.let(Files::readAllBytes)
                            ?: path.parent.parent.resolve(sibling).takeIf(Path::exists)?.let(Files::readAllBytes)
                    }
                }
                .toList()
        }
    }

    private fun resolveClientTableRoot(): Path {
        val cwd = Path.of("").toAbsolutePath().normalize()
        generateSequence(cwd) { it.parent }.take(6).forEach { root ->
            if (!root.isDirectory()) return@forEach
            Files.list(root).use { children ->
                val clientRoot = children
                    .filter { it.isDirectory() && it.name.startsWith(CLIENT_DIR_PREFIX) }
                    .sorted()
                    .findFirst()
                    .orElse(null)
                val tableRoot = clientRoot?.resolve(TABLE_RELATIVE_PATH)
                if (tableRoot?.resolve("default/tb_cfg_card_extract.bin")?.exists() == true) {
                    return tableRoot
                }
            }
        }
        error("无法定位客户端全赛季卡包配置表: $cwd")
    }

    private fun parseCardExtract(bytes: ByteArray, source: String): List<CardExtractRow> {
        val table = CardMemoryPackTable.open(bytes, source)
        return table.keys.map {
            require(table.reader.byte().toInt() and 0xff == 62) { "invalid Tcfg_card_extract row in $source" }
            val packId = table.reader.int()
            val parentPackId = table.reader.int()
            val containerPackId = table.reader.int()
            repeat(13) { table.reader.int() }
            val priority = table.reader.int()
            repeat(10) { table.reader.int() }
            repeat(17) { table.reader.byte() }
            table.reader.intArray()
            repeat(3) { table.reader.nestedIntArray() }
            repeat(14) { table.reader.int() }
            CardExtractRow(packId, parentPackId, containerPackId, priority)
        }
    }

    private fun parseCardProb(bytes: ByteArray, source: String): Map<Int, List<Int>> {
        val table = CardMemoryPackTable.open(bytes, source)
        return buildMap<Int, MutableList<Int>> {
            table.keys.forEach {
                require(table.reader.byte().toInt() and 0xff == 1) { "invalid Tcfg_card_prob row in $source" }
                val refreshWayHeroId = table.reader.int()
                val packId = refreshWayHeroId / HERO_ID_FACTOR
                val heroId = refreshWayHeroId % HERO_ID_FACTOR
                getOrPut(packId, ::mutableListOf).add(heroId)
            }
        }
    }

    private fun cardPackFileComparator(): Comparator<String> =
        compareBy<String> { it != "tb_cfg_card_extract.bin" }.thenBy(::seasonSuffix)

    private fun cardPackPathComparator(): Comparator<Path> =
        compareBy<Path> { it.fileName.toString() != "tb_cfg_card_extract.bin" }
            .thenBy { seasonSuffix(it.fileName.toString()) }

    private fun seasonSuffix(fileName: String): Int =
        fileName.removePrefix("tb_cfg_card_extract")
            .removeSuffix(".bin")
            .removePrefix("_")
            .toIntOrNull() ?: 0

    private const val CLIENT_DIR_PREFIX = "stzb_9.2.2_out_branch_"
    private const val HERO_ID_FACTOR = 1_000_000
    private const val SUMMON_UID_STRIDE = 1_000
    private val CARD_EXTRACT_FILE = Regex("""tb_cfg_card_extract(?:_\d+)?\.bin""")
    private val TABLE_RELATIVE_PATH = Path.of(
        "assets", "npk_extracted_all", "others", "res", "csharp", "data", "tcfg",
    )
}

private data class CardExtractRow(
    val packId: Int,
    val parentPackId: Int,
    val containerPackId: Int,
    val priority: Int,
)

private data class ClientResource(
    val fileName: String,
    val bytes: ByteArray,
    val readSibling: (String) -> ByteArray?,
)

private class CardMemoryPackTable private constructor(
    val keys: List<Int>,
    val reader: CardLittleEndianReader,
) {
    companion object {
        fun open(bytes: ByteArray, source: String): CardMemoryPackTable {
            val reader = CardLittleEndianReader(bytes)
            val stringTableLength = reader.int()
            val stringTableEnd = reader.position + stringTableLength
            val stringCount = reader.int()
            repeat(stringCount.coerceAtLeast(0)) { reader.memoryPackString() }
            require(reader.position == stringTableEnd) { "invalid string table in $source" }
            require(reader.byte().toInt() and 0xff == 2) { "invalid table header in $source" }
            val keyCount = reader.int()
            val keys = List(keyCount) { reader.int() }
            require(reader.int() == keyCount) { "key/value count mismatch in $source" }
            return CardMemoryPackTable(keys, reader)
        }
    }
}

private class CardLittleEndianReader(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val position: Int
        get() = buffer.position()

    fun byte(): Byte = buffer.get()

    fun int(): Int = buffer.int

    fun intArray() {
        val length = int()
        if (length > 0) buffer.position(buffer.position() + length * Int.SIZE_BYTES)
    }

    fun nestedIntArray() {
        val length = int()
        repeat(length.coerceAtLeast(0)) { intArray() }
    }

    fun memoryPackString() {
        val length = int()
        when {
            length == -1 -> Unit
            length >= 0 -> buffer.position(buffer.position() + length * 2)
            else -> {
                val byteCount = length.inv()
                int()
                buffer.position(buffer.position() + byteCount)
            }
        }
    }
}
