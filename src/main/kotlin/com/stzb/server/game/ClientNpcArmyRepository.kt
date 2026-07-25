package com.stzb.server.game

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class ClientNpcHero(
    val heroUid: Int,
    val heroId: Int,
    val level: Int,
    val troops: Int,
    val skillIds: List<Int>,
)

data class ClientNpcArmy(
    val armyId: Int,
    val pool: Int,
    val heroes: List<ClientNpcHero>,
)

/**
 * Reads the client's MemoryPack configuration tables used by resource-land NPCs.
 *
 * Tcfg_army selects the hero instances for an army, while Tcfg_hero_u owns the
 * actual hero id, level, troops and skill loadout. Keeping that relationship
 * intact avoids manufacturing defender values on the server.
 */
class ClientNpcArmyRepository private constructor(
    private val armiesByPool: Map<Int, List<ClientNpcArmy>>,
    private val teamCounts: Map<Int, Int>,
) {
    fun armiesForPool(pool: Int): List<ClientNpcArmy> = armiesByPool[pool].orEmpty()

    fun teamCount(pool: Int): Int = teamCounts[pool] ?: 1

    companion object {
        private const val CLIENT_DIR_PREFIX = "stzb_9.2.2_out_branch_"
        private val TABLE_RELATIVE_PATH = Path.of(
            "assets",
            "npk_extracted_all",
            "others",
            "res",
            "csharp",
            "data",
            "tcfg",
        )

        fun loadDefault(): ClientNpcArmyRepository =
            load(
                armyBytes = readClientResource("tb_cfg_army.bin"),
                heroBytes = readClientResource("tb_cfg_hero_u.bin"),
                armyCountBytes = readClientResource("tb_cfg_army_count.bin"),
            )

        fun load(tableRoot: Path): ClientNpcArmyRepository {
            return load(
                armyBytes = Files.readAllBytes(tableRoot.resolve("tb_cfg_army.bin")),
                heroBytes = Files.readAllBytes(tableRoot.resolve("tb_cfg_hero_u.bin")),
                armyCountBytes = Files.readAllBytes(tableRoot.resolve("tb_cfg_army_count.bin")),
            )
        }

        private fun load(
            armyBytes: ByteArray,
            heroBytes: ByteArray,
            armyCountBytes: ByteArray,
        ): ClientNpcArmyRepository {
            val heroes = parseHeroes(heroBytes)
            val armies = parseArmies(armyBytes)
                .map { row ->
                    ClientNpcArmy(
                        armyId = row.armyId,
                        pool = row.pool,
                        heroes = row.heroUids.mapNotNull(heroes::get),
                    )
                }
                .filter { army ->
                    army.pool in 1..9 &&
                        army.armyId / 100 == army.pool &&
                        army.heroes.isNotEmpty()
                }
                .groupBy(ClientNpcArmy::pool)
                .mapValues { (_, rows) -> rows.sortedBy(ClientNpcArmy::armyId) }
            require((1..9).all { armies[it].orEmpty().isNotEmpty() }) {
                "client resource-land defender pools 1..9 are incomplete"
            }
            return ClientNpcArmyRepository(
                armiesByPool = armies,
                teamCounts = parseArmyCounts(armyCountBytes),
            )
        }

        private fun readClientResource(fileName: String): ByteArray {
            val resourcePath = "/client-config/$fileName"
            ClientNpcArmyRepository::class.java.getResourceAsStream(resourcePath)?.use {
                return it.readBytes()
            }
            return Files.readAllBytes(resolveClientTableRoot().resolve(fileName))
        }

        private fun resolveClientTableRoot(): Path {
            val cwd = Path.of("").toAbsolutePath().normalize()
            val projectRoots = generateSequence(cwd) { it.parent }.take(6)
            projectRoots.forEach { root ->
                if (!root.isDirectory()) return@forEach
                Files.list(root).use { children ->
                    val clientRoot = children
                        .filter { it.isDirectory() && it.name.startsWith(CLIENT_DIR_PREFIX) }
                        .sorted()
                        .findFirst()
                        .orElse(null)
                    if (clientRoot != null) {
                        val tableRoot = clientRoot.resolve(TABLE_RELATIVE_PATH)
                        if (tableRoot.resolve("tb_cfg_army.bin").exists() &&
                            tableRoot.resolve("tb_cfg_hero_u.bin").exists()
                        ) {
                            return tableRoot
                        }
                    }
                }
            }
            error("无法定位客户端守军配置表，请从项目目录启动服务: $cwd")
        }

        private fun parseArmies(bytes: ByteArray): List<ArmyRow> {
            val table = MemoryPackTable.open(bytes, "tb_cfg_army.bin")
            return table.keys.map {
                require(table.reader.byte().toInt() and 0xff == 7) { "invalid Tcfg_army row" }
                val armyId = table.reader.int()
                val base = table.reader.int()
                val middle = table.reader.int()
                val front = table.reader.int()
                val counsellor = table.reader.int()
                table.reader.int() // exercise_record
                val pool = table.reader.int()
                ArmyRow(
                    armyId = armyId,
                    pool = pool,
                    heroUids = listOf(base, middle, front, counsellor).filter { uid -> uid > 0 },
                )
            }
        }

        private fun parseHeroes(bytes: ByteArray): Map<Int, ClientNpcHero> {
            val table = MemoryPackTable.open(bytes, "tb_cfg_hero_u.bin")
            return buildMap {
                table.keys.forEach {
                    require(table.reader.byte().toInt() and 0xff == 9) { "invalid Tcfg_hero_u row" }
                    val heroUid = table.reader.int()
                    val heroId = table.reader.int()
                    val level = table.reader.int()
                    val troops = table.reader.int()
                    table.reader.int() // hero_type
                    table.reader.int() // hero_feature
                    table.reader.int() // gearid_u
                    val skill = table.string(table.reader.int()).orEmpty()
                    table.reader.int() // hero_type_feature string-table index
                    put(
                        heroUid,
                        ClientNpcHero(
                            heroUid = heroUid,
                            heroId = heroId,
                            level = level,
                            troops = troops,
                            skillIds = parseSkillIds(skill),
                        ),
                    )
                }
            }
        }

        private fun parseArmyCounts(bytes: ByteArray): Map<Int, Int> {
            val table = MemoryPackTable.open(bytes, "tb_cfg_army_count.bin")
            return buildMap {
                table.keys.forEach {
                    require(table.reader.byte().toInt() and 0xff == 3) { "invalid Tcfg_army_count row" }
                    val type = table.reader.int()
                    val count = table.reader.int()
                    table.reader.int() // recover_interval
                    if (type in 1..9) put(type, count)
                }
            }
        }

        private fun parseSkillIds(value: String): List<Int> =
            value.split(';')
                .mapNotNull { item -> item.substringBefore(',').toIntOrNull() }
                .filter { it > 0 }
    }
}

private data class ArmyRow(
    val armyId: Int,
    val pool: Int,
    val heroUids: List<Int>,
)

private class MemoryPackTable private constructor(
    val strings: List<String?>,
    val keys: List<Int>,
    val reader: LittleEndianReader,
) {
    fun string(index: Int): String? =
        if (index == -1) null else strings.getOrNull(index)

    companion object {
        fun open(bytes: ByteArray, source: String): MemoryPackTable {
            val reader = LittleEndianReader(bytes)
            val stringTableLength = reader.int()
            val stringTableEnd = reader.position + stringTableLength
            val stringCount = reader.int()
            val strings = if (stringCount < 0) {
                emptyList()
            } else {
                List(stringCount) { reader.memoryPackString() }
            }
            require(reader.position == stringTableEnd) { "invalid string table in $source" }
            require(reader.byte().toInt() and 0xff == 2) { "invalid table header in $source" }
            val keyCount = reader.int()
            val keys = List(keyCount) { reader.int() }
            require(reader.int() == keyCount) { "key/value count mismatch in $source" }
            return MemoryPackTable(strings, keys, reader)
        }
    }
}

private class LittleEndianReader(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val position: Int
        get() = buffer.position()

    fun byte(): Byte = buffer.get()

    fun int(): Int = buffer.int

    fun memoryPackString(): String? {
        val length = int()
        return when {
            length == -1 -> null
            length == 0 -> ""
            length > 0 -> {
                val bytes = ByteArray(length * 2)
                buffer.get(bytes)
                bytes.toString(Charsets.UTF_16LE)
            }
            else -> {
                val byteCount = length.inv()
                int() // UTF-16 character count
                val bytes = ByteArray(byteCount)
                buffer.get(bytes)
                bytes.toString(Charsets.UTF_8)
            }
        }
    }
}
