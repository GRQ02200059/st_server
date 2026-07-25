package com.stzb.server.game

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the client hero table once so recruiting and Tb_hero serialization use
 * the same full set of valid hero ids and troop types.
 */
object HeroCatalog {
    private data class HeroInfo(
        val troopType: Int,
        val quality: Int,
        val initialSkillId: Int,
    )

    private val heroes: LinkedHashMap<Int, HeroInfo> by lazy { loadHeroes() }
    private val fiveStarHeroIdsByPack: Map<Int, List<Int>> by lazy {
        ClientCardPackCatalog.allPacks().associate { pack ->
            pack.packId to pack.heroIds.filter { heroQuality(it) == FIVE_STAR_QUALITY }
        }
    }
    private val defaultFiveStarHeroIds: List<Int> by lazy {
        ClientCardPackCatalog.allPacks()
            .flatMap { fiveStarHeroIdsByPack[it.packId].orEmpty() }
            .distinct()
    }

    fun recruitableHeroIds(): List<Int> = heroes.keys.toList()

    fun heroType(heroId: Int): Int = heroes[heroId]?.troopType ?: 1

    fun heroQuality(heroId: Int): Int = heroes[heroId]?.quality ?: 0

    fun initialSkillId(heroId: Int): Int =
        heroes[heroId]?.initialSkillId ?: heroId + 100_000

    fun defaultSkillIds(heroId: Int): List<Int> =
        listOf(initialSkillId(heroId), DEFAULT_SECOND_SKILL_ID, DEFAULT_THIRD_SKILL_ID)

    /**
     * Tb_hero.skill uses the client format "skillId,level;".
     * Keep three usable slots on test accounts; the latter two are real
     * client skills already supported by the battle runtime.
     */
    fun maxLevelSkillString(heroId: Int): String =
        maxLevelSkillIds(heroId)
            .joinToString(separator = "", postfix = "") { "$it,$MAX_SKILL_LEVEL;" }

    fun maxLevelSkillIds(heroId: Int): List<Int> =
        defaultSkillIds(heroId)

    fun fiveStarHeroIdsForCardPack(packId: Int): List<Int> =
        fiveStarHeroIdsByPack[packId].orEmpty()

    fun defaultFiveStarHeroIds(): List<Int> = defaultFiveStarHeroIds

    private fun loadHeroes(): LinkedHashMap<Int, HeroInfo> {
        HeroCatalog::class.java.classLoader
            ?.getResourceAsStream("hero_table.csv")
            ?.use { input ->
                return input.bufferedReader().useLines(::parseHeroes)
            }

        val heroTable = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("hero_table.csv") }
            .firstOrNull(Files::exists)
            ?: error("无法定位 hero_table.csv")

        return Files.newBufferedReader(heroTable).useLines(::parseHeroes)
    }

    private fun parseHeroes(lines: Sequence<String>): LinkedHashMap<Int, HeroInfo> {
        return lines.drop(1)
            .mapNotNull(::parseHero)
            .toMap(LinkedHashMap())
    }

    private fun parseHero(line: String): Pair<Int, HeroInfo>? {
        val columns = line.split(',')
        val heroId = columns.getOrNull(1)?.toIntOrNull() ?: return null
        val troopType = columns.getOrNull(3)?.toIntOrNull()?.rem(10) ?: return null
        val quality = columns.getOrNull(35)?.toIntOrNull() ?: return null
        val initialSkillId = columns.getOrNull(15)?.toIntOrNull() ?: return null
        return heroId.takeIf { it > 0 }
            ?.takeIf { troopType in 1..3 }
            ?.let {
                it to HeroInfo(
                    troopType = troopType,
                    quality = quality,
                    initialSkillId = initialSkillId,
                )
            }
    }

    private const val FIVE_STAR_QUALITY = 4
    private const val MAX_SKILL_LEVEL = 10
    private const val DEFAULT_SECOND_SKILL_ID = 200223
    private const val DEFAULT_THIRD_SKILL_ID = 200031

    @Suppress("unused")
    private val legacyClientCardPackHeroIds: Map<Int, List<Int>> = mapOf(
        281 to listOf(
            100006, 100007, 100009, 100017, 100020, 100024, 100025, 100031, 100033, 100039,
            100040, 100042, 100043, 100044, 100049, 100050, 100052, 100053, 100054, 100062,
            100065, 100067, 100068, 100069, 100078, 100079, 100081, 100085, 100086, 100089,
            100091, 100092, 100101, 100102, 100104, 100110, 100111, 100116, 100126, 100129,
            100137, 100140, 100145, 100147, 100149, 100150, 100157, 100162, 100163, 100228,
            100241, 100268, 100271, 100285, 100329, 100398, 100414, 100421, 100439, 100694,
            100695, 100696, 100697, 100698, 100699, 100700, 100707, 100711, 100713, 100714,
            100715, 100716, 100717, 100718, 100719, 100721, 100722, 100723, 100724, 100744,
            100745, 100746, 100747, 100748, 100749, 100750, 100751, 100752, 100753, 100754,
            100756, 100758, 100759, 100760, 100763, 100764, 100765, 100766, 100768,
        ),
        901 to listOf(
            100008, 100039, 100040, 100045, 100051, 100052, 100054, 100062, 100111, 100126,
            100285, 100340, 100430, 100474, 100487, 100553, 100586, 100677, 100785, 100796,
            100805,
        ),
        902 to listOf(
            100001, 100005, 100041, 100088, 100102, 100110, 100116, 100354, 100355, 100378,
            100414, 100443, 100450, 100526, 100582, 100655, 100693, 100746, 100747, 100769,
            100804,
        ),
        903 to listOf(
            100021, 100025, 100027, 100047, 100058, 100059, 100064, 100066, 100069, 100077,
            100081, 100092, 100147, 100471, 100475, 100619, 100671, 100741, 100748, 100752,
            100754, 100757, 100765, 100766, 100790,
        ),
        904 to listOf(
            100013, 100019, 100032, 100046, 100048, 100063, 100072, 100079, 100137, 100145,
            100146, 100243, 100265, 100269, 100331, 100398, 100421, 100426, 100449, 100472,
            100616, 100771, 100803,
        ),
        905 to listOf(
            100020, 100035, 100036, 100047, 100048, 100049, 100057, 100058, 100059, 100110,
            100113, 100116, 100119, 100271, 100351, 100424, 100574, 100641, 100642, 100643,
            100644, 100672, 100707, 100755, 100759, 100811,
        ),
        906 to listOf(
            100076, 100084, 100087, 100091, 100134, 100136, 100139, 100149, 100159, 100326,
            100337, 100338, 100379, 100451, 100585, 100615, 100620, 100635, 100636, 100637,
            100670, 100702, 100706, 100761, 100793,
        ),
        907 to listOf(
            100017, 100086, 100162, 100329, 100334, 100398, 100452, 100489, 100490, 100491,
            100492, 100493, 100494, 100524, 100638, 100639, 100640, 100689, 100704, 100705,
            100751, 100753, 100766, 100768, 100783,
        ),
    )
}
