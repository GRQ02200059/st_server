package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.BattleOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `prebook server info exposes all mandatory empty lists`() {
        val json = GameResponses.prebookServerInfo()
        val response = mapper.readTree(json)

        assertEquals(
            """{"prebook_info":[],"del_prebook":[],"prebook_list":[]}""",
            json,
        )
        assertTrue(response.isObject)
        assertEquals(
            setOf("prebook_info", "del_prebook", "prebook_list"),
            response.fieldNames().asSequence().toSet(),
        )
        listOf("prebook_info", "del_prebook", "prebook_list").forEach { key ->
            assertTrue(response.has(key), "missing key=$key")
            assertTrue(response[key].isArray, "key=$key must be an array")
            assertEquals(0, response[key].size(), "key=$key must be empty")
        }
    }

    @Test
    fun `community user token rejection keeps the three slot client contract`() {
        val json = GameResponses.communityUserTokenRejection()
        val response = mapper.readTree(json)

        assertEquals("""[0,"",""]""", json)
        assertTrue(response.isArray)
        assertEquals(3, response.size())
        assertTrue(response[0].isIntegralNumber)
        assertEquals(0, response[0].asInt())
        assertTrue(response[1].isTextual)
        assertEquals("", response[1].asText())
        assertTrue(response[2].isTextual)
        assertEquals("", response[2].asText())
    }

    @Test
    fun `army related fort response keeps all recorded dictionary buckets`() {
        val response = mapper.readTree(GameResponses.armyRelatedFort())

        assertTrue(response.isObject)
        assertTrue(response["33"].isArray)
        assertTrue(response["4"].isArray)
        assertEquals(0, response["33"].size())
        assertEquals(0, response["4"].size())
    }

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
        assertTrue(firstCard.size() >= 5)
        assertEquals(0, firstCard[0].asInt())
        assertTrue(firstCard[1].asInt() in HeroCatalog.fiveStarHeroIdsForCardPack(281))
        assertEquals(0, firstCard[4].asInt(), "HasAdvanced must be explicit; missing defaults to 1 in client")
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
        assertTrue(response[7].all { it.size() >= 5 && it[4].asInt() == 0 })
        assertTrue(
            response[7].all {
                it[1].asInt() in HeroCatalog.fiveStarHeroIdsForCardPack(281) &&
                    HeroCatalog.heroQuality(it[1].asInt()) == 4
            },
        )
    }

    @Test
    fun `recruit from a cross-season pack stays inside that client pool`() {
        val packId = 2004
        val expectedPool = ClientCardPackCatalog.heroIdsForPack(packId)
            .filter { HeroCatalog.heroQuality(it) == 4 }
        assertTrue(expectedPool.isNotEmpty())

        val response = mapper.readTree(
            GameResponses.cardRecruit(
                userId = 42,
                summonUid = ClientCardPackCatalog.summonUid(42, packId),
                summonCfgId = packId,
                childCfgId = 0,
                summonOpType = 1,
            ),
        )

        assertTrue(response[1].all { it[1].asInt() in expectedPool })
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
        assertEquals(PlayerHero.DEFAULT_LEVEL, row[6].asInt())
        assertEquals("200017,10;200223,10;200031,10;", row[22].asText())
        assertEquals(1, row[24].asInt())
        assertEquals(2, row[32].asInt())
    }

    @Test
    fun `hero upsert notify always includes infinite stamina and current troops`() {
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
        assertEquals(PlayerHero.MAX_STAMINA, row[7].asInt())
        assertEquals(640, row[11].asInt())
        assertEquals("200021,10;200223,10;200031,10;", row[22].asText())
        assertEquals(1, row[24].asInt())
        assertEquals(2, row[32].asInt())
    }

    @Test
    fun `hero upsert exposes the active hero feature in the client field format`() {
        val hero = PlayerHero(
            heroUid = 4_200_003,
            heroId = 100648,
            createdAtSec = 1_700_000_000,
            activeFeatureId = 285314,
        )

        val row = mapper.readTree(
            GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)),
        )[0][2]

        assertEquals("285314,1;", row[37].asText())
    }

    @Test
    fun `hero upsert serializes persistent empty and replaced skill slots`() {
        val hero = PlayerHero(
            heroUid = 4_200_004,
            heroId = 100021,
            createdAtSec = 1_700_000_000,
            skillIds = mutableListOf(200021, 0, 200070),
        )

        val row = mapper.readTree(
            GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)),
        )[0][2]

        assertEquals("200021,10;0,0;200070,10;", row[22].asText())
        assertEquals(1, row[24].asInt())
    }

    @Test
    fun `hero skill update uses the real sparse db notification shape`() {
        val hero = PlayerHero(
            heroUid = 4_200_005,
            heroId = 100021,
            createdAtSec = 1_700_000_000,
            skillIds = mutableListOf(200021, 0, 200070),
        )

        val update = mapper.readTree(GameResponses.heroSkillUpdateNotify(hero))

        assertEquals(1, update.size())
        assertEquals(2, update[0][0].asInt())
        assertEquals("Tb_hero", update[0][1].asText())
        assertEquals(
            listOf(0, 4_200_005, 22, "200021,10;0,0;200070,10;", 24, 1),
            update[0][2].map { if (it.isTextual) it.asText() else it.asInt() },
        )
    }

    @Test
    fun `hero advance notification updates advance count and removes consumed material`() {
        val update = mapper.readTree(
            GameResponses.heroAdvanceNotify(
                heroUid = 4_200_006,
                advanceNum = 5,
                consumedMaterialUids = listOf(4_200_007),
            ),
        )

        assertEquals(2, update.size())
        assertEquals(2, update[0][0].asInt())
        assertEquals("Tb_hero", update[0][1].asText())
        assertEquals(
            listOf(0, 4_200_006, 29, 5),
            update[0][2].map { it.asInt() },
        )
        assertEquals(3, update[1][0].asInt())
        assertEquals("Tb_hero", update[1][1].asText())
        assertEquals(4_200_007, update[1][2].asInt())
    }

    @Test
    fun `card pack seen notify clears is new for every active extract row`() {
        val summonUids = listOf(7469, 7470, 7471)

        val update = mapper.readTree(GameResponses.cardPacksSeenNotify(summonUids))

        assertEquals(3, update.size())
        update.forEachIndexed { index, item ->
            assertEquals(2, item[0].asInt())
            assertEquals("Tb_user_card_extract", item[1].asText())
            assertEquals(
                listOf(0, summonUids[index], 7, 0),
                item[2].map { it.asInt() },
            )
        }
    }

    @Test
    fun `hero upsert notify includes selected facade as dynamic icon`() {
        val hero = PlayerHero(
            heroUid = 4_200_003,
            heroId = 100067,
            createdAtSec = 1_700_000_000,
            dynamicIcon = 100534,
        )

        val row = mapper.readTree(
            GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)),
        )[0][2]

        assertEquals(100534, row[43].asInt())
    }

    @Test
    fun `hero upsert contains selected card border and dynamic icon`() {
        val hero = PlayerHero(
            heroUid = 4_200_004,
            heroId = 100067,
            createdAtSec = 1_700_000_000,
            cardBorder = 110997,
            dynamicIcon = 100534,
        )

        val row = mapper.readTree(
            GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)),
        )[0][2]

        assertEquals(110997, row[42].asInt())
        assertEquals(100534, row[43].asInt())
    }

    @Test
    fun `card border update uses sparse hero field forty two`() {
        val update = mapper.readTree(
            GameResponses.heroCardBorderUpdateNotify(
                heroUid = 4_200_004,
                cardBorder = 110997,
            ),
        )

        assertEquals(1, update.size())
        assertEquals(2, update[0][0].asInt())
        assertEquals("Tb_hero", update[0][1].asText())
        assertEquals(
            listOf(0, 4_200_004, 42, 110997),
            update[0][2].map { it.asInt() },
        )
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
    fun `army upsert notify targets requested second army`() {
        val state = PlayerStateRepository.getOrCreate(userId = 47, cityWid = 10047, roleName = "主公")
        val hero = state.addHero(100017)
        val secondArmyId = state.armyIds()[1]
        state.assignTeamHero(hero.heroUid, pos = 1, armyId = secondArmyId)

        val row = mapper.readTree(GameResponses.armyUpsertNotify(state, secondArmyId))[0][2]

        assertEquals(secondArmyId, row[0].asInt())
        assertEquals(hero.heroUid, row[7].asInt())
    }

    @Test
    fun `second army upsert exposes its own active march`() {
        val state = PlayerState(userId = 49, cityWid = 10049, roleName = "主公")
        val secondArmyId = state.armyIds()[1]
        state.startMarch(targetWid = 10060, nowSec = 1_700_000_000, armyId = secondArmyId)

        val row = mapper.readTree(GameResponses.armyUpsertNotify(state, secondArmyId))[0][2]

        assertEquals(secondArmyId, row[0].asInt())
        assertEquals(1, row[11].asInt())
        assertEquals(10060, row[13].asInt())
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
    fun `army hero upsert includes every affected army`() {
        val state = PlayerStateRepository.getOrCreate(userId = 48, cityWid = 10048, roleName = "主公")
        val first = state.addHero(100017)
        val second = state.addHero(100021)
        val firstArmyId = state.armyIds()[0]
        val secondArmyId = state.armyIds()[1]
        state.assignTeamHero(first.heroUid, 1, firstArmyId)
        state.assignTeamHero(second.heroUid, 1, secondArmyId)

        val response = mapper.readTree(
            GameResponses.armyAndHeroesUpsertNotify(
                state,
                listOf(first, second),
                listOf(firstArmyId, secondArmyId),
            ),
        )

        assertEquals(
            listOf(firstArmyId, secondArmyId),
            response.toList().takeLast(2).map { it[2][0].asInt() },
        )
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
        assertEquals(0, row[6].asInt())
        assertEquals(10002, row[7].asInt())
        assertEquals("100017", row[10].asText())
    }

    @Test
    fun `battle report insert uses the same result enum as report profile`() {
        val attackerWin = mapper.readTree(
            GameResponses.battleReportAttackInsertNotify(
                userId = 42,
                battleId = 1,
                armyId = 2,
                targetWid = 3,
                outcome = BattleOutcome.ATTACKER_WIN,
                heroIds = emptyList(),
            ),
        )
        val draw = mapper.readTree(
            GameResponses.battleReportAttackInsertNotify(
                userId = 42,
                battleId = 1,
                armyId = 2,
                targetWid = 3,
                outcome = BattleOutcome.DRAW,
                heroIds = emptyList(),
            ),
        )

        assertEquals(1, attackerWin[0][2][6].asInt())
        assertEquals(6, draw[0][2][6].asInt())
    }

    @Test
    fun `occupied land upsert assigns world city ownership to the player`() {
        val response = mapper.readTree(
            GameResponses.occupiedLandUpsertNotify(
                userId = 42,
                cityWid = 10001,
                landWid = 10002,
            ),
        )

        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_world_city", response[0][1].asText())
        val row = response[0][2]
        assertEquals(10002, row[0].asInt())
        assertEquals(2, row[1].asInt())
        assertEquals(42, row[6].asInt())
        assertEquals(1, row[11].asInt())
        assertEquals(10001, row[21].asInt())
        assertEquals(0, row[22].asInt())
    }

    @Test
    fun `building upsert notify refreshes every front slot and user build level`() {
        val state = PlayerState(userId = 42, cityWid = 10001, roleName = "主公")
        val json = GameResponses.userBuildUpsertNotify(
            state = state,
            buildId = 10,
            level = 2,
            resources = PlayerResources(money = 999, wood = 998, stone = 997, iron = 996, food = 995),
        )
        val response = mapper.readTree(json)

        assertEquals(8, response.size())
        assertEquals(1, response[0][0].asInt())
        assertEquals("Tb_user_build", response[0][1].asText())
        val row = response[0][2]
        assertEquals(HomeCity.userBuildId(10001, 10), row[0].asInt())
        assertEquals(10001, row[1].asInt())
        assertEquals(10, row[2].asInt())
        assertEquals(42, row[3].asInt())
        assertEquals(2, row[4].asInt())
        assertEquals("", row[12].asText())
        assertEquals(1, response[1][0].asInt())
        assertEquals("Tb_build_effect_city", response[1][1].asText())
        assertEquals(10001, response[1][2][0].asInt())
        assertEquals(5_000, response[1][2][4].asInt())
        assertEquals("295010", response[1][2][6].asText())
        assertEquals(PlayerState.MAX_COUNTRY_BUILD_LEVEL, response[1][2][7].asInt())
        assertEquals("295140", response[1][2][16].asText())
        assertEquals(PlayerState.MAX_COUNTRY_BUILD_LEVEL, response[1][2][17].asInt())
        assertEquals(5, response[1][2][23].asInt())
        assertEquals(5, response[1][2][25].asInt())
        val armies = (2..6).map { response[it] }
        assertEquals(
            state.armyIds(),
            armies.map { it[2][0].asInt() },
        )
        assertTrue(armies.all { it[1].asText() == "Tb_army" })
        assertEquals(100, response[1][2][26].asInt())
        assertEquals(1, response[7][0].asInt())
        assertEquals("Tb_user_res", response[7][1].asText())
        assertEquals(42, response[7][2][0].asInt())
        assertEquals(999, response[7][2][2].asInt())
        assertEquals(998, response[7][2][3].asInt())
        assertEquals(997, response[7][2][4].asInt())
        assertEquals(996, response[7][2][5].asInt())
        assertEquals(995, response[7][2][6].asInt())
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
    fun `land info defender counters match the configured resource land waves`() {
        val wid = 15_011_503
        val defenders = LandDefenderFactory()
        val expectedTeamCount = defenders.teamCountForLevel(defenders.levelForWid(wid))

        val response = mapper.readTree(GameResponses.landInfo(wid))

        assertEquals(expectedTeamCount, response[10].asInt(), "npc_army_left")
        assertEquals(expectedTeamCount, response[11].asInt(), "npc_army_total")
    }

    @Test
    fun `land npc army response matches defender recovery callback`() {
        val response = mapper.readTree(GameResponses.landNpcArmy(120002))

        assertEquals(2, response.size())
        assertEquals(120002, response[0].asInt())
        assertEquals(0L, response[1].asLong())
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
        assertEquals(31, response.size())
        assertTrue(response[0].isObject)
        assertTrue(response[1].isObject)
        assertTrue(response[2].isObject)
        assertTrue(response[14].isObject)
        assertTrue(response[17].isNull)
        assertEquals(1, response[18].asInt())
        assertTrue(response[29].isObject)
        assertTrue(response[30].isNull)
        assertEquals("主公", response[1]["42"][0].asText())
        assertEquals(10001, response[1]["42"][1].asInt())
        assertEquals(1, response[14]["10001"]["0"][0].asInt())
        assertEquals(42, response[14]["10001"]["0"][2].asInt())
        assertEquals("主公", response[14]["10001"]["0"][6].asText())
        assertEquals(0, response[14]["10001"]["0"][12].asInt())
    }

    @Test
    fun `world scene sends city facade and build data only for the main city`() {
        val cityWid = 15_061_506
        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 42,
                cityWid = cityWid,
                roleName = "主公",
            ),
        )

        val cities = response[14]
        val mainCityChunk = cities[cityWid.toString()]
        val mainCity = mainCityChunk["0"]
        assertEquals(1, mainCity[0].asInt())
        assertEquals("\"4P-e0Go[=)')(',0(*',(,-*)", mainCity[5].asText())
        assertEquals(
            "10,8,13,20,20,20,21,20,22,20,23,20,24,20,25,1,30,20," +
                "31,10,32,10,33,10,34,10,35,10,36,20,37,10,40,5,42,5," +
                "43,15,44,3,51,10,52,10,53,10,54,10,61,5,62,6,63,5," +
                "64,5,65,5,66,10,67,3,160,10",
            mainCity[13].asText(),
        )
        assertTrue(mainCityChunk.has("4"))
        assertEquals(
            "1112130,20004,110005;1121120,120004,120003;1122050,100010;" +
                "1122070,100003;1122090,100008;1122140,100009;1133050,50005;" +
                "1212010,10008;1222040,50011;1222060,10002;1233080,50003;" +
                "1299010,0;1322030,30004;1322110,90011;1322130,120005;" +
                "1333070,40008;1333090,80002;1333150,20011;",
            mainCityChunk["4"][0].asText(),
        )
        assertEquals("", mainCityChunk["4"][1].asText())

        HomeCity.suburbWids(cityWid).forEach { suburbWid ->
            val suburbChunk = cities[suburbWid.toString()]
            val suburb = suburbChunk["0"]
            assertEquals(5, suburb[0].asInt())
            assertEquals("", suburb[5].asText())
            assertEquals("", suburb[13].asText())
            assertTrue(!suburbChunk.has("4"))
        }
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
    fun `world scene includes simultaneous marches from different armies`() {
        val marches = listOf(
            PlayerMarch(100011, 10001, 10002, 10, 13),
            PlayerMarch(100012, 10001, 10003, 11, 14),
        )

        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(42, 10001, "主公", marches = marches),
        )

        assertEquals(10002, response[6]["100011"][3].asInt())
        assertEquals(10003, response[6]["100012"][3].asInt())
    }

    @Test
    fun `world scene exposes occupied player lands`() {
        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 42,
                cityWid = 10001,
                roleName = "主公",
                occupiedLands = setOf(10004),
            ),
        )

        val land = response[14]["10004"]["0"]
        assertEquals(2, land[0].asInt())
        assertEquals(42, land[2].asInt())
        assertEquals(10001, land[7].asInt())
    }

    @Test
    fun `world scene exposes all eight owned main city suburbs`() {
        val cityWid = 15_061_506
        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 42,
                cityWid = cityWid,
                roleName = "主公",
            ),
        )

        val chunks = response[14]
        val suburbs = chunks.fields().asSequence()
            .filter { (wid, _) -> wid.toInt() != cityWid }
            .map { (_, value) -> value["0"] }
            .toList()

        assertEquals(8, suburbs.size)
        assertTrue(suburbs.all {
            it[0].asInt() == 5 &&
                it[2].asInt() == 42 &&
                it[7].asInt() == cityWid &&
                it[12].asInt() == 0
        })
    }

    @Test
    fun `world scene uses each projected owners user id`() {
        val world = WorldProjection(
            cities = listOf(
                WorldCity(15_061_506, 10, "Alice"),
                WorldCity(14_961_496, 11, "Bob"),
            ),
            lands = listOf(LandClaim(14_981_496, 11, 14_961_496, 100)),
        )

        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 10,
                cityWid = 15_061_506,
                roleName = "Alice",
                world = world,
            ),
        )

        assertEquals("Bob", response[1]["11"][0].asText())
        assertEquals(14_961_496, response[1]["11"][1].asInt())
        assertEquals(11, response[14]["14981496"]["0"][2].asInt())
        assertEquals(14_961_496, response[14]["14981496"]["0"][7].asInt())
    }

    @Test
    fun `gear notification updates affected heroes before affected gears`() {
        val update = mapper.readTree(
            GameResponses.gearEquipNotify(
                GearEquipResult(
                    heroGearUids = mapOf(10_002 to 800_001_042, 10_001 to 0),
                    gearHeroUids = mapOf(800_001_041 to 0, 800_001_042 to 10_002),
                ),
            ),
        )

        assertEquals(4, update.size())
        assertEquals("Tb_hero", update[0][1].asText())
        assertEquals(listOf(0, 10_001, 23, 0), update[0][2].map { it.asInt() })
        assertEquals("Tb_hero", update[1][1].asText())
        assertEquals(listOf(0, 10_002, 23, 800_001_042), update[1][2].map { it.asInt() })
        assertEquals("Tb_gear", update[2][1].asText())
        assertEquals(listOf(0, 800_001_041, 9, 0), update[2][2].map { it.asInt() })
        assertEquals("Tb_gear", update[3][1].asText())
        assertEquals(listOf(0, 800_001_042, 9, 10_002), update[3][2].map { it.asInt() })
    }

    @Test
    fun `army facade notification updates cards before heroes with sparse fields`() {
        val state = PlayerState(userId = 84, cityWid = 10084, roleName = "主公")
        val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
        val mutation = requireNotNull(state.bindArmyFacadeCards(101138, listOf(hero.heroUid)))

        val update = mapper.readTree(GameResponses.armyFacadeNotify(state, mutation))

        assertEquals(2, update.size())
        assertEquals("Tb_user_army_facade_card", update[0][1].asText())
        assertEquals(
            listOf(0, ArmyFacadeCatalog.cardId(101138, 1), 5, hero.heroId),
            update[0][2].map { it.asInt() },
        )
        assertEquals("Tb_hero", update[1][1].asText())
        assertEquals(listOf(0, hero.heroUid, 72, 101138), update[1][2].map { it.asInt() })
    }

    @Test
    fun `world scene uses captured army facade ids at tuple index fifteen`() {
        val march = PlayerMarch(
            armyId = 100_841,
            fromWid = 10084,
            targetWid = 10085,
            beginSec = 1,
            endSec = 4,
            participants = listOf(
                PlayerMarchHero(
                    heroUid = 1,
                    position = 0,
                    heroId = 100017,
                    troops = 1_000,
                    level = 50,
                    skillIds = listOf(200017),
                    armyFacadeCardId = 101138,
                ),
            ),
        )

        val response = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 84,
                cityWid = 10084,
                roleName = "主公",
                marches = listOf(march),
            ),
        )

        assertEquals("101138,0;", response[6][march.armyId.toString()][15].asText())
    }

    @Test
    fun `hero upsert retains the equipped gear uid`() {
        val hero = PlayerHero(
            heroUid = 4_200_008,
            heroId = 100017,
            createdAtSec = 1_700_000_000,
            gearUid = 800_001_042,
        )

        val row = mapper.readTree(GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)))[0][2]

        assertEquals(800_001_042, row[23].asInt())
    }

}
