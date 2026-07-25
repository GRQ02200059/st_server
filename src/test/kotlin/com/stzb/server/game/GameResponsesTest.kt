package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.BattleOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `card recruit response matches client result parser shape`() {
        val json = GameResponses.cardRecruit(
            userId = 42,
            summonUid = 1000501,
            summonCfgId = 281,
            childCfgId = 0,
            summonOpType = 0,
        )
        val response = mapper.readTree(json)

        assertTrue(response.isArray)
        assertEquals(5, response.size())
        assertEquals(1000501, response[0].asInt())
        assertEquals(0, response[3].asInt())
        assertEquals(0, response[4].asInt())
        assertEquals(1, response[1].size())

        val firstCard = response[1][0]
        assertTrue(firstCard.isArray)
        assertTrue(firstCard.size() >= 4)
        assertEquals(0, firstCard[0].asInt())
        assertTrue(firstCard[1].asInt() in HeroCatalog.fiveStarHeroIdsForCardPack(281))
    }

    @Test
    fun `card recruit returns five-star heroes from requested child pack`() {
        val json = GameResponses.cardRecruit(
            userId = 42,
            summonUid = 1000501,
            summonCfgId = 801,
            childCfgId = 901,
            summonOpType = 1,
        )
        val response = mapper.readTree(json)

        assertEquals(5, response[1].size())
        val heroIds = response[1].map { it[1].asInt() }
        assertTrue(response[1].all { it[0].asInt() == 0 })
        assertEquals(5, heroIds.distinct().size)
        assertTrue(heroIds.all { it in HeroCatalog.fiveStarHeroIdsForCardPack(901) })
        assertTrue(heroIds.all { HeroCatalog.heroQuality(it) == 4 })
    }

    @Test
    fun `quick card recruit response matches client parser shape`() {
        val response = mapper.readTree(
            GameResponses.quickCardRecruit(summonUid = 1000501, packId = 281, quickCount = 99),
        )

        assertTrue(response.isArray)
        assertEquals(8, response.size())
        assertEquals(1000501, response[0].asInt())
        assertEquals(0, response[4].asInt())
        assertTrue(response[5].isArray)
        assertEquals(10, response[5].sumOf { it.asInt() })
        assertTrue(response[7].isArray)
        assertEquals(10, response[7].size())
        assertTrue(response[7].all { it[0].asInt() == 0 })
        assertTrue(
            response[7].all {
                it[1].asInt() in HeroCatalog.fiveStarHeroIdsForCardPack(281) &&
                    HeroCatalog.heroQuality(it[1].asInt()) == 4
            },
        )
    }

    @Test
    fun `hero insert notify uses db notify insert shape`() {
        val json = GameResponses.heroInsertNotify(
            userId = 42,
            heroUid = 4_200_001,
            heroId = 100017,
            nowSec = 1_700_000_000,
        )
        val response = mapper.readTree(json)

        assertTrue(response.isArray)
        assertEquals(1, response.size())
        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_hero", response[0][1].asText())
        val row = response[0][2]
        assertEquals(4_200_001, row[0].asInt())
        assertEquals(100017, row[1].asInt())
        assertEquals(42, row[2].asInt())
        assertEquals(1, row[6].asInt())
        assertEquals("", row[22].asText())
        assertEquals(2, row[32].asInt())
    }

    @Test
    fun `hero upsert notify includes current stamina and troops`() {
        val hero = PlayerHero(
            heroUid = 4_200_002,
            heroId = 100021,
            createdAtSec = 1_700_000_000,
            troops = 640,
            stamina = 60,
            level = 3,
        )

        val response = mapper.readTree(GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)))

        assertEquals(1, response.size())
        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_hero", response[0][1].asText())
        val row = response[0][2]
        assertEquals(4_200_002, row[0].asInt())
        assertEquals(100021, row[1].asInt())
        assertEquals(42, row[2].asInt())
        assertEquals(3, row[6].asInt())
        assertEquals(60, row[7].asInt())
        assertEquals(640, row[11].asInt())
        assertEquals(2, row[32].asInt())
    }

    @Test
    fun `user resource upsert notify includes current food and money`() {
        val response = mapper.readTree(
            GameResponses.userResourceUpsertNotify(
                userId = 42,
                resources = PlayerResources(money = 900, food = 800),
            ),
        )

        assertEquals(1, response.size())
        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_user_res", response[0][1].asText())
        val row = response[0][2]
        assertEquals(42, row[0].asInt())
        assertEquals(900, row[2].asInt())
        assertEquals(800, row[6].asInt())
    }

    @Test
    fun `army upsert notify includes current team slots`() {
        val state = PlayerStateRepository.getOrCreate(userId = 44, cityWid = 10044, roleName = "主公")
        val first = state.addHero(100017)
        val second = state.addHero(100021)
        state.saveTeam(listOf(first.heroUid, second.heroUid))

        val response = mapper.readTree(GameResponses.armyUpsertNotify(state))

        assertEquals(1, response.size())
        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_army", response[0][1].asText())
        val row = response[0][2]
        assertEquals(10044 * 10 + 1, row[0].asInt())
        assertEquals(44, row[1].asInt())
        assertEquals(10044, row[2].asInt())
        assertEquals(0, row[5].asInt())
        assertEquals(second.heroUid, row[6].asInt())
        assertEquals(first.heroUid, row[7].asInt())
    }

    @Test
    fun `army hero upsert is atomic and writes heroes before army`() {
        val state = PlayerStateRepository.getOrCreate(userId = 46, cityWid = 10046, roleName = "主公")
        val hero = state.addHero(100017)
        state.assignTeamHero(hero.heroUid, pos = 1)

        val response = mapper.readTree(
            GameResponses.armyAndHeroesUpsertNotify(state, listOf(hero)),
        )

        assertEquals(2, response.size())
        assertEquals("Tb_hero", response[0][1].asText())
        assertEquals(hero.heroUid, response[0][2][0].asInt())
        assertEquals("Tb_army", response[1][1].asText())
        assertEquals(hero.heroUid, response[1][2][7].asInt())
    }

    @Test
    fun `army upsert notify marks active expedition fields`() {
        val state = PlayerStateRepository.getOrCreate(userId = 45, cityWid = 10045, roleName = "主公")
        state.startMarch(targetWid = 110045, nowSec = 1_700_000_000)

        val response = mapper.readTree(GameResponses.armyUpsertNotify(state))
        val row = response[0][2]

        assertEquals(1, row[11].asInt())
        assertEquals(110045, row[13].asInt())
        assertEquals(1_700_000_000, row[16].asInt())
        assertEquals(1_700_000_003, row[17].asInt())
    }

    @Test
    fun `battle report insert notifies map battle with client result enum`() {
        val response = mapper.readTree(
            GameResponses.battleReportAttackInsertNotify(
                userId = 42,
                battleId = 1_000_002,
                armyId = 100011,
                targetWid = 10002,
                outcome = BattleOutcome.DEFENDER_WIN,
                heroIds = listOf(100017),
            ),
        )

        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_battle_report_attack", response[0][1].asText())
        val row = response[0][2]
        assertEquals(14, row.size())
        assertEquals(1_000_002, row[0].asInt())
        assertEquals(42, row[1].asInt())
        assertEquals(100011, row[3].asInt())
        assertEquals(2, row[6].asInt())
        assertEquals(10002, row[7].asInt())
        assertEquals("100017", row[10].asText())
    }

    @Test
    fun `building upsert notify updates user build level`() {
        val json = GameResponses.userBuildUpsertNotify(
            userId = 42,
            cityWid = 10001,
            buildId = 10,
            level = 2,
            resources = PlayerResources(money = 999, wood = 998, stone = 997, iron = 996, food = 995),
        )
        val response = mapper.readTree(json)

        assertEquals(3, response.size())
        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_user_build", response[0][1].asText())
        val row = response[0][2]
        assertEquals(10001 * 1000 + 10, row[0].asInt())
        assertEquals(10001, row[1].asInt())
        assertEquals(10, row[2].asInt())
        assertEquals(42, row[3].asInt())
        assertEquals(2, row[4].asInt())
        assertEquals("", row[12].asText())
        assertEquals(1, response[1][0].asInt())
        assertEquals("Tb_build_effect_city", response[1][1].asText())
        assertEquals(10001, response[1][2][0].asInt())
        assertEquals(100, response[1][2][26].asInt())
        assertEquals(1, response[2][0].asInt())
        assertEquals("Tb_user_res", response[2][1].asText())
        assertEquals(42, response[2][2][0].asInt())
        assertEquals(999, response[2][2][2].asInt())
        assertEquals(998, response[2][2][3].asInt())
        assertEquals(997, response[2][2][4].asInt())
        assertEquals(996, response[2][2][5].asInt())
        assertEquals(995, response[2][2][6].asInt())
    }

    @Test
    fun `hero team library response matches HeroUI parser`() {
        val response = mapper.readTree(GameResponses.heroTeamLibrary(listOf(100003, 100017)))

        assertTrue(response["res"].isArray)
        assertTrue(response["res"][0]["rec"].isArray)
        assertEquals(100003, response["res"][1]["rec"][0].asInt())
        assertEquals(100017, response["res"][1]["rec"][1].asInt())
    }

    @Test
    fun `normal team composition response is accepted by callback guard`() {
        val response = mapper.readTree(GameResponses.normalTeamComposition(heroId = 100017))

        assertTrue(response.isArray)
        assertEquals(3, response.size())
        assertEquals(100017, response[0].asInt())
        assertEquals("", response[1].asText())
        assertTrue(response[2].asText().isNotEmpty())
    }

    @Test
    fun `empty ui response is a valid json array`() {
        val response = mapper.readTree(GameResponses.emptyArray())

        assertTrue(response.isArray)
        assertEquals(0, response.size())
    }

    @Test
    fun `server time response contains one epoch-second value`() {
        val response = mapper.readTree(GameResponses.serverTime(1_700_000_000L))

        assertTrue(response.isArray)
        assertEquals(1, response.size())
        assertEquals(1_700_000_000L, response[0].asLong())
    }

    @Test
    fun `server time millis response contains one epoch-millisecond value`() {
        val response = mapper.readTree(GameResponses.serverTimeMillis(1_700_000_000_123L))

        assertTrue(response.isArray)
        assertEquals(1, response.size())
        assertEquals(1_700_000_000_123L, response[0].asLong())
    }

    @Test
    fun `army related fort response contains camp bucket`() {
        val response = mapper.readTree(GameResponses.armyRelatedFort())

        assertTrue(response.isObject)
        assertTrue(response["4"].isArray)
        assertEquals(0, response["4"].size())
    }

    @Test
    fun `land info response provides client indexed fields`() {
        val response = mapper.readTree(GameResponses.landInfo(100001))

        assertTrue(response.isArray)
        assertEquals(54, response.size())
        assertEquals(100001, response[0].asInt())
        assertEquals("", response[1].asText())
        assertEquals("", response[2].asText())
        assertEquals("", response[3].asText())
        assertEquals(100, response[4].asInt())
        assertEquals(100, response[5].asInt())
        assertTrue(response[8].isNull)
        assertEquals("", response[50].asText())
        assertEquals("", response[53].asText())
    }

    @Test
    fun `pre server token response allows login flow to continue`() {
        val response = mapper.readTree(GameResponses.preServerTokenCheck())

        assertTrue(response.isArray)
        assertEquals(1, response.size())
        assertEquals(0, response[0].asInt())
    }

    @Test
    fun `device ping response contains one server monotonic timestamp`() {
        val response = mapper.readTree(GameResponses.devicePing(1_234_567_890L))

        assertTrue(response.isArray)
        assertEquals(1, response.size())
        assertEquals(1_234_567_890L, response[0].asLong())
    }

    @Test
    fun `world scene full info provides all client indexed slots`() {
        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 42,
                cityWid = 10001,
                roleName = "主公",
            ),
        )

        assertTrue(response.isArray)
        assertEquals(30, response.size())
        assertTrue(response[0].isObject)
        assertTrue(response[1].isObject)
        assertTrue(response[14].isObject)
        assertEquals(1, response[18].asInt())
        assertTrue(response[29].isObject)
        assertEquals("主公", response[1]["42"][0].asText())
        assertEquals(10001, response[1]["42"][1].asInt())
        assertEquals(1, response[14]["10001"]["0"][0].asInt())
        assertEquals(42, response[14]["10001"]["0"][2].asInt())
        assertEquals("主公", response[14]["10001"]["0"][6].asText())
    }

    @Test
    fun `world scene includes active army march`() {
        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 42,
                cityWid = 10001,
                roleName = "主公",
                march = PlayerMarch(
                    armyId = 100011,
                    fromWid = 10001,
                    targetWid = 10002,
                    beginSec = 1_700_000_000,
                    endSec = 1_700_000_060,
                ),
            ),
        )

        val army = response[6]["100011"]
        assertEquals(1, army[0].asInt())
        assertEquals(42, army[1].asInt())
        assertEquals(10001, army[2].asInt())
        assertEquals(10002, army[3].asInt())
        assertEquals(1_700_000_000, army[4].asInt())
        assertEquals(1_700_000_060, army[5].asInt())
        assertTrue(army[19].isNull)
    }

    @Test
    fun `world scene explicitly removes completed army`() {
        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 42,
                cityWid = 10001,
                roleName = "主公",
                removedArmyId = 100011,
            ),
        )

        assertEquals(0, response[6]["100011"][0].asInt())
    }

    @Test
    fun `world scene exposes occupied player lands`() {
        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 42,
                cityWid = 10001,
                roleName = "主公",
                occupiedLands = setOf(10002),
            ),
        )

        val land = response[14]["10002"]["0"]
        assertEquals(2, land[0].asInt())
        assertEquals(42, land[2].asInt())
        assertEquals(10001, land[7].asInt())
    }
}
