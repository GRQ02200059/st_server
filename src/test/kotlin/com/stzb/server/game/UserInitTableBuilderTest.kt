package com.stzb.server.game

import com.stzb.server.protocol.GameServerConfig
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserInitTableBuilderTest {
    @Test
    fun `login snapshot includes exact empty revenue and accumulated money rows`() {
        val root = createTempDirectory("stzb-empty-revenue-row")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                accountKey = "empty-revenue-row",
                cityWid = 10036,
                roleName = "主公",
            )
            state.resources.money = 0
            state.resources.moneyAccumulated = 6500

            val tables = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 10,
                accountKey = state.accountKey,
            ).drop(1).associateBy { it[0].asText() }

            val resourceRow = tables.getValue("Tb_user_res")[1].single()
            assertEquals(6500, resourceRow[1].asInt())
            assertEquals(0, resourceRow[2].asInt())

            val revenueEntry = tables.getValue("Tb_user_revenue")
            assertEquals("Tb_user_revenue", revenueEntry[0].asText())
            assertEquals(1, revenueEntry[1].size())
            val row = revenueEntry[1].single()
            assertEquals(8, row.size())
            assertTrue(row[0].isIntegralNumber)
            assertTrue(row[1].isTextual)
            assertTrue(row[2].isIntegralNumber)
            assertTrue(row[3].isIntegralNumber)
            assertTrue(row[4].isIntegralNumber)
            assertTrue(row[5].isTextual)
            assertTrue(row[6].isTextual)
            assertTrue(row[7].isTextual)
            assertEquals(
                listOf(state.userId, "", 0, 0, 0, "", "", ""),
                row.map { if (it.isTextual) it.asText() else it.asInt() },
            )
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot projects populated structured revenue with trailing semicolons`() {
        val root = createTempDirectory("stzb-populated-revenue-row")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                accountKey = "populated-revenue-row",
                cityWid = 10037,
                roleName = "主公",
            )
            state.revenue.collections += RevenueCollection(10, 6500)
            state.revenue.collections += RevenueCollection(20, 6500)
            state.revenue.gifts += RevenueGift(6500, extra = 0, claimed = false)
            state.revenue.gifts += RevenueGift(6500, extra = 0, claimed = true)
            state.revenue.revenueTime = 20
            state.revenue.nextRefreshTime = 30
            state.revenue.forceCount = 2

            val row = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 10,
                accountKey = state.accountKey,
            ).drop(1)
                .associateBy { it[0].asText() }
                .getValue("Tb_user_revenue")[1]
                .single()

            assertEquals(8, row.size())
            assertEquals(state.userId, row[0].asInt())
            assertEquals("10,6500;20,6500;", row[1].asText())
            assertEquals(20, row[2].asInt())
            assertEquals(30, row[3].asInt())
            assertEquals(2, row[4].asInt())
            assertEquals("", row[5].asText())
            assertEquals("6500,0,0;6500,0,1;", row[6].asText())
            assertEquals("", row[7].asText())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `card packs are new only until their opening screen has been acknowledged`() {
        val root = createTempDirectory("stzb-card-packs-seen")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                accountKey = "card-packs-seen-account",
                cityWid = 10038,
                roleName = "主公",
            )

            val firstLogin = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
                accountKey = state.accountKey,
            )
            assertTrue(cardExtractRows(firstLogin).all { it[7].asInt() == 1 })

            state.markCardPacksSeen()
            PlayerStateRepository.save(state)

            val laterLogin = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_001L,
                accountKey = state.accountKey,
            )
            assertTrue(cardExtractRows(laterLogin).all { it[7].asInt() == 0 })
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `new role snapshot contains starter resources city and summon entries`() {
        val snapshot = UserInitTableBuilder.build(
            userId = 42,
            cityWid = 10001,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        )

        val tables = snapshot.drop(1).associateBy { it[0].asText() }
        val user = tables.getValue("Tb_user")[1][0]
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, user[19].asInt())
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, user[20].asInt())
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, user[22].asInt())

        val userStuff = tables.getValue("Tb_user_stuff")[1][0]
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, userStuff[63].asInt())

        assertTrue(tables.containsKey("Tb_user_inner_city"))
        assertTrue(tables.containsKey("Tb_user_inner_city_building"))
        assertTrue(tables.containsKey("Tb_user_build"))
        assertTrue(tables.containsKey("Tb_build_effect_city"))
        assertTrue(tables.containsKey("Tb_army"))
        assertTrue(tables.containsKey("Tb_user_card_extract"))
        assertTrue(tables.containsKey("Tb_sys_param"))
        assertTrue(tables.containsKey("Tb_hero"))
        assertTrue(tables.containsKey("Tb_hero_temp"))
        assertTrue(tables.containsKey("Tb_hero_identity"))
        assertTrue(tables.containsKey("Tb_user_skill"))
        assertTrue(tables.containsKey("Tb_gear"))
        assertTrue(tables.containsKey("Tb_battle_report_attack"))
        assertTrue(tables.containsKey("Tb_mail_receive"))

        val buildings = tables.getValue("Tb_user_build")[1]
        assertEquals(2, buildings.size())
        val palace = buildings.first { it[2].asInt() == 10 }
        assertEquals(HomeCity.userBuildId(10001, 10), palace[0].asInt())
        assertEquals(10001, palace[1].asInt())
        assertEquals(10, palace[2].asInt())
        assertEquals(42, palace[3].asInt())
        assertEquals(1, palace[4].asInt())
        val barracks = buildings.first { it[2].asInt() == 30 }
        assertEquals(20, barracks[4].asInt())

        val buildEffect = tables.getValue("Tb_build_effect_city")[1][0]
        assertEquals(10001, buildEffect[0].asInt())
        assertEquals(42, buildEffect[1].asInt())
        assertEquals(5_000, buildEffect[4].asInt())
        assertEquals("", buildEffect[6].asText())
        assertEquals(0, buildEffect[7].asInt())
        assertEquals("", buildEffect[16].asText())
        assertEquals(0, buildEffect[17].asInt())
        assertEquals(100, buildEffect[26].asInt())

        assertEquals(
            "1,1,1,1,1,1,1,1,1,",
            userStuff[62].asText(),
            "occupy_land_level must mark levels 1-9 as previously occupied so the client unlocks higher-level expeditions",
        )
        val userStuffTempEx = tables.getValue("Tb_user_stuff_temp_ex")[1][0]
        assertEquals(
            "1,1,1,1,1,1,1,1,1,",
            userStuffTempEx[116].asText(),
            "occupy_land_level_season drives UserData.MaxOccupyLandLevel and must unlock higher-level land",
        )
        val userStuffTempOne = tables.getValue("Tb_user_stuff_temp_one")[1][0]
        assertEquals(
            9,
            userStuffTempOne[196].asInt(),
            "conquered_level drives the first-season S1LandOccupyCheck and must unlock levels above 1",
        )

        val armies = tables.getValue("Tb_army")[1]
        assertEquals(5, armies.size())
        assertEquals((1..5).map { 10001 * 10 + it }, armies.map { it[0].asInt() })
        val army = armies[0]
        assertEquals(10001 * 10 + 1, army[0].asInt())
        assertEquals(42, army[1].asInt())
        assertEquals(10001, army[2].asInt())
        assertEquals(10001, army[14].asInt())

        val worldCity = tables.getValue("Tb_world_city")[1][0]
        assertEquals(
            "10,8,13,20,20,20,21,20,22,20,23,20,24,20,25,1,30,20," +
                "31,10,32,10,33,10,34,10,35,10,36,20,37,10,40,5,42,5," +
                "43,15,44,3,51,10,52,10,53,10,54,10,61,5,62,6,63,5," +
                "64,5,65,5,66,10,67,3,160,10",
            worldCity[4].asText(),
        )
        assertEquals(0, worldCity[11].asInt())

        val activity = tables.getValue("Tb_activity")[1][0]
        assertEquals(875, activity[1].asInt())
        assertEquals("362,0;363,0;364,0;365,0;", activity[3].asText())
        assertEquals("0,0,0,0", activity[4].asText())
        assertEquals("0,0,0,0", activity[5].asText())

        val cardExtracts = tables.getValue("Tb_user_card_extract")[1]
        assertEquals(
            271,
            cardExtracts.size(),
            "all card packs from every client season table must be activated",
        )
        assertEquals(
            271,
            cardExtracts.map { it[2].asInt() }.distinct().size,
            "season variants sharing a card-pack id must be de-duplicated",
        )
        assertTrue(cardExtracts.any { it[2].asInt() == 2004 })

        val sysParams = tables.getValue("Tb_sys_param")[1].associate {
            it[0].asInt() to it[1].asText()
        }
        assertEquals("4", sysParams[12], "CurrentSeason must declare the server as conquest/XP season")
        assertEquals(
            GameServerConfig.CFG_DB_ID.toString(),
            sysParams[26],
            "CurrentSeasonCfgDBId must match the advertised login cfgDataIndex",
        )
    }

    @Test
    fun `login snapshot clears cached npc defender armies`() {
        val snapshot = UserInitTableBuilder.build(
            userId = 42,
            cityWid = 10001,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        )
        val tables = snapshot.drop(1).associateBy { it[0].asText() }

        assertTrue(tables.containsKey("Tb_user_npc_army"))
        assertTrue(tables.getValue("Tb_user_npc_army")[1].isEmpty)
    }

    @Test
    fun `login snapshot clears stale dynamic land level overrides`() {
        val snapshot = UserInitTableBuilder.build(
            userId = 42,
            cityWid = 10001,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        )
        val tables = snapshot.drop(1).associateBy { it[0].asText() }

        listOf("Tb_developed_land", "Tb_land_reclamation", "Tb_store_house").forEach { name ->
            assertTrue(tables.containsKey(name), "$name must be present")
            assertTrue(tables.getValue(name)[1].isEmpty, "$name must be empty")
        }
    }

    @Test
    fun `new role snapshot sets history power high enough for chat channels`() {
        val snapshot = UserInitTableBuilder.build(
            userId = 42,
            cityWid = 10001,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        )
        val userStuff = snapshot.drop(1)
            .associateBy { it[0].asText() }
            .getValue("Tb_user_stuff")[1][0]

        assertEquals(10_000, userStuff[34].asInt())
    }

    @Test
    fun `snapshot restores recruited heroes and saved team from player state`() {
        val root = createTempDirectory("stzb-snapshot-restored-team")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(userId = 43, cityWid = 10043, roleName = "主公")
            val first = state.addHero(100017, nowSec = 1_700_000_001)
            val second = state.addHero(100021, nowSec = 1_700_000_002)
            state.hero(first.heroUid)?.troops = 750
            state.hero(first.heroUid)?.stamina = 80
            state.saveTeam(listOf(first.heroUid, second.heroUid))

            val snapshot = UserInitTableBuilder.build(
                userId = 43,
                cityWid = 10043,
                roleName = "主公",
                serverOpenTime = 1_700_000_000L,
            )

            val tables = snapshot.drop(1).associateBy { it[0].asText() }
            val heroes = tables.getValue("Tb_hero")[1]
            assertEquals(8, heroes.size())
            assertEquals(first.heroUid, heroes[0][0].asInt())
            assertEquals(100017, heroes[0][1].asInt())
            assertEquals(PlayerHero.MAX_STAMINA, heroes[0][7].asInt())
            assertEquals(750, heroes[0][11].asInt())
            assertEquals(second.heroUid, heroes[1][0].asInt())
            assertEquals(100021, heroes[1][1].asInt())

            val army = tables.getValue("Tb_army")[1][0]
            assertEquals(0, army[5].asInt())
            assertEquals(second.heroUid, army[6].asInt())
            assertEquals(first.heroUid, army[7].asInt())
            assertEquals(10043 * 10 + 1, heroes[0][3].asInt())
            assertEquals(10043 * 10 + 1, heroes[1][3].asInt())
            assertEquals(
                HeroCatalog.maxLevelSkillString(first.heroId),
                heroes[0][22].asText(),
                "login snapshot must provide complete skill slots so CanLearnSecondSkill cannot index an empty list",
            )
            assertEquals(1, heroes[0][24].asInt(), "all heroes must be awakened")
            assertEquals(first.skillString(), heroes[0][22].asText())
            assertEquals(
                SkillInventoryCatalog.allSkillIds().size,
                tables.getValue("Tb_user_skill")[1].size(),
                "the login snapshot must expose every skill from the client warfare-skill inventory",
            )
        } finally {
            PlayerStateRepository.reset()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `login snapshot exposes the active hero feature and enabled state`() {
        val state = PlayerStateRepository.getOrCreate(userId = 46, cityWid = 10046, roleName = "主公")
        val hero = state.addHero(100648, nowSec = 1_700_000_001)
        hero.activeFeatureId = 285314

        val heroRow = UserInitTableBuilder.build(
            userId = 46,
            cityWid = 10046,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        ).drop(1)
            .associateBy { it[0].asText() }
            .getValue("Tb_hero")[1]
            .single { it[0].asInt() == hero.heroUid }

        assertEquals("285314,1;", heroRow[37].asText())
    }

    @Test
    fun `login snapshot exposes all five armies with every front position unlocked`() {
        val state = PlayerStateRepository.getOrCreate(userId = 45, cityWid = 10045, roleName = "主公")
        val primary = state.addHero(100017, nowSec = 1_700_000_001)
        val secondary = state.addHero(100021, nowSec = 1_700_000_002)
        state.assignTeamHero(primary.heroUid, pos = 1, armyId = 100451)
        state.assignTeamHero(secondary.heroUid, pos = 1, armyId = 100452)

        val snapshot = UserInitTableBuilder.build(
            userId = 45,
            cityWid = 10045,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        )

        val tables = snapshot.drop(1).associateBy { it[0].asText() }
        val armies = tables.getValue("Tb_army")[1]
        val heroes = tables.getValue("Tb_hero")[1].associateBy { it[0].asInt() }
        val buildEffect = tables.getValue("Tb_build_effect_city")[1][0]

        assertEquals(state.armyIds(), armies.map { it[0].asInt() })
        assertEquals(100451, heroes.getValue(primary.heroUid)[3].asInt())
        assertEquals(100452, heroes.getValue(secondary.heroUid)[3].asInt())
        assertEquals(5, buildEffect[23].asInt())
        assertEquals(5, buildEffect[25].asInt())
    }

    @Test
    fun `snapshot equips yulong and unlocks every supported hero card border`() {
        val root = createTempDirectory("stzb-card-border-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                userId = 44,
                cityWid = 10044,
                roleName = "主公",
            )
            val hero = state.addHero(100017, nowSec = 1_700_000_000)
            PlayerStateRepository.save(state)

            val snapshot = UserInitTableBuilder.build(
                userId = 44,
                cityWid = 10044,
                roleName = "主公",
                serverOpenTime = 1_700_000_000L,
            )
            val tables = snapshot.drop(1).associateBy { it[0].asText() }
            val heroRow = tables.getValue("Tb_hero")[1]
                .single { it[0].asInt() == hero.heroUid }
            val achievements = tables.getValue("Tb_hero_achieve")[1]
            val facades = tables.getValue("Tb_user_facade_card")[1]

            assertEquals(CardBorderCatalog.DEFAULT_ID, heroRow[42].asInt())
            assertTrue(
                achievements.any {
                    it[2].asInt() == hero.heroId && it[5].asInt() == 2
                },
            )
            CardBorderCatalog.normalBorderIds().forEach { borderId ->
                assertTrue(
                    facades.any {
                        it[2].asInt() == hero.heroId && it[3].asInt() == borderId
                    },
                )
            }
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `snapshot unlocks every army and city facade and creates a normal nine tile main city`() {
        val cityWid = 15_061_506
        val userId = 46
        val root = createTempDirectory("stzb-facade-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val snapshot = UserInitTableBuilder.build(
                userId = userId,
                cityWid = cityWid,
                roleName = "主公",
                serverOpenTime = 1_700_000_000L,
            )

            val tables = snapshot.drop(1).associateBy { it[0].asText() }
            val armyFacades = tables.getValue("Tb_user_army_facade_card")[1]
            val cityFacades = tables.getValue("Tb_user_build_facade")[1]
            val specialArmyFacades = tables.getValue("Tb_hero")[1]
                .filter { it[1].asInt() in ArmyFacadeCatalog.specialFacadeIds() }
            assertEquals(60, armyFacades.size())
            assertEquals(157, cityFacades.size())
            assertEquals(5, armyFacades.count { it[2].asInt() == 101138 })
            assertTrue(armyFacades.all { it[0].asInt() > 0 && it[2].asInt() > 0 && it[5].asInt() == 0 })
            assertEquals(4, specialArmyFacades.size)
            val activeFacades = cityFacades.filter { it[5].asInt() == cityWid }
            assertEquals(
                setOf(113305, 129901),
                activeFacades.map { it[1].asInt() }.toSet(),
            )
            val mansion = activeFacades.single { it[1].asInt() == 113305 }
            assertEquals(cityWid, mansion[4].asInt())
            assertEquals(cityWid, mansion[5].asInt())
            assertEquals(50005, mansion[6].asInt())
            assertEquals(1, mansion[8].asInt())
            val wall = activeFacades.single { it[1].asInt() == 129901 }
            assertEquals(cityWid, wall[4].asInt())
            assertEquals(cityWid, wall[5].asInt())
            assertEquals(0, wall[6].asInt())

            val worldCities = tables.getValue("Tb_world_city")[1]
            assertEquals(9, worldCities.size())
            val mainCity = worldCities.single { it[0].asInt() == cityWid }
            assertEquals(1, mainCity[1].asInt())
            assertEquals("\"4P-e0Go[=)')(',0(*',(,-*)", mainCity[3].asText())
            assertEquals(
                "10,8,13,20,20,20,21,20,22,20,23,20,24,20,25,1,30,20," +
                    "31,10,32,10,33,10,34,10,35,10,36,20,37,10,40,5,42,5," +
                    "43,15,44,3,51,10,52,10,53,10,54,10,61,5,62,6,63,5," +
                    "64,5,65,5,66,10,67,3,160,10",
                mainCity[4].asText(),
            )
            assertEquals(userId, mainCity[6].asInt())
            assertEquals(0, mainCity[11].asInt())

            val suburbs = worldCities.filter { it[0].asInt() != cityWid }
            assertTrue(suburbs.all {
                it[1].asInt() == 5 &&
                    it[3].asText().isEmpty() &&
                    it[4].asText().isEmpty() &&
                    it[6].asInt() == userId &&
                    it[21].asInt() == cityWid
            })

            val buildings = tables.getValue("Tb_user_build")[1]
            assertEquals(1_506_150_610, buildings.single { it[2].asInt() == 10 }[0].asInt())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot preserves bound army facade cards in heroes and armies`() {
        val root = createTempDirectory("stzb-army-facade-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                userId = 47,
                cityWid = 10047,
                roleName = "主公",
            )
            val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
            requireNotNull(state.bindArmyFacadeCards(101138, listOf(hero.heroUid)))
            state.assignTeamHero(hero.heroUid, pos = 1)
            PlayerStateRepository.save(state)

            val tables = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
            ).drop(1).associateBy { it[0].asText() }

            val card = tables.getValue("Tb_user_army_facade_card")[1]
                .single { it[0].asInt() == ArmyFacadeCatalog.cardId(101138, 1) }
            val heroRow = tables.getValue("Tb_hero")[1]
                .single { it[0].asInt() == hero.heroUid }
            val armyRow = tables.getValue("Tb_army")[1]
                .single { it[0].asInt() == state.primaryArmyId() }

            assertEquals(hero.heroId, card[5].asInt())
            assertEquals(101138, heroRow[72].asInt())
            assertEquals("101138,0;", armyRow[61].asText())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot equips a default army facade for deployed heroes without one`() {
        val root = createTempDirectory("stzb-default-army-facade-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                userId = 49,
                cityWid = 10049,
                roleName = "主公",
            )
            val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
            state.saveTeam(listOf(hero.heroUid))

            val tables = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
            ).drop(1).associateBy { it[0].asText() }
            val heroRow = tables.getValue("Tb_hero")[1]
                .single { it[0].asInt() == hero.heroUid }
            val armyRow = tables.getValue("Tb_army")[1]
                .single { it[0].asInt() == state.primaryArmyId() }
            val defaultCard = tables.getValue("Tb_user_army_facade_card")[1]
                .single { it[0].asInt() == ArmyFacadeCatalog.cardId(101138, 1) }

            assertEquals(101138, heroRow[72].asInt())
            assertEquals("101138,0;", armyRow[61].asText())
            assertEquals(hero.heroId, defaultCard[5].asInt())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot assigns the next facade after five default cards are used`() {
        val root = createTempDirectory("stzb-default-army-facade-order")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                userId = 50,
                cityWid = 10050,
                roleName = "主公",
            )
            val heroes = HeroCatalog.defaultFiveStarHeroIds().take(6).map(state::addHero)
            heroes.forEachIndexed { index, hero ->
                state.assignTeamHero(
                    heroUid = hero.heroUid,
                    pos = index % 3 + 1,
                    armyId = state.armyIds()[index / 3],
                )
            }

            UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
            )

            assertEquals(
                List(5) { 101138 } + 101156,
                heroes.map { hero -> state.hero(hero.heroUid)?.armyFacadeCardId },
            )
        } finally {
            PlayerStateRepository.reset()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `login snapshot keeps an existing manual army facade binding`() {
        val root = createTempDirectory("stzb-default-army-facade-manual")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                userId = 51,
                cityWid = 10051,
                roleName = "主公",
            )
            val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
            state.saveTeam(listOf(hero.heroUid))
            requireNotNull(state.bindArmyFacadeCards(101682, listOf(hero.heroUid)))

            UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
            )

            assertEquals(101682, state.hero(hero.heroUid)?.armyFacadeCardId)
            assertEquals(
                hero.heroId,
                state.armyFacadeCards()
                    .single { it.facadeId == 101682 && it.cfgHeroId > 0 }
                    .cfgHeroId,
            )
        } finally {
            PlayerStateRepository.reset()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `login snapshot includes a building level saved before repository restart`() {
        val root = createTempDirectory("stzb-build-persistence")
        try {
            val repository = FilePlayerRepository(root)
            PlayerStateRepository.configure(repository)
            val state = PlayerStateRepository.getOrCreate(
                accountKey = "build-persistence",
                cityWid = 10048,
                roleName = "主公",
            )
            val upgradedLevel = state.upgradeBuild(buildId = 42, targetLevel = 4)
            assertEquals(4, upgradedLevel)
            PlayerStateRepository.save(state)

            PlayerStateRepository.configure(repository)
            val snapshot = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
                accountKey = state.accountKey,
            )
            val rows = snapshot.drop(1)
                .associateBy { it[0].asText() }
                .getValue("Tb_user_build")[1]

            val upgraded = rows.single { it[2].asInt() == 42 }
            assertEquals(upgradedLevel, upgraded[4].asInt())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot identifies the union created by this player`() {
        val root = createTempDirectory("stzb-union-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            WorldStateRepository.configure(root)
            val state = PlayerStateRepository.getOrCreate(
                accountKey = "union-snapshot-owner",
                cityWid = 10049,
                roleName = "盟主",
            )
            val unionId = UnionStateRepository.create(state, "洛阳同盟", nowSec = 1_700_000_000)

            val snapshot = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
                accountKey = state.accountKey,
            )
            val user = snapshot.drop(1)
                .associateBy { it[0].asText() }
                .getValue("Tb_user")[1][0]

            assertEquals(unionId, user[10].asInt())
            assertEquals("洛阳同盟", user[11].asText())
        } finally {
            PlayerStateRepository.reset()
            WorldStateRepository.reset()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot gives every playable hero a visible advance count and one material card`() {
        val root = createTempDirectory("stzb-advance-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(userId = 48, cityWid = 10048, roleName = "主公")
            val target = state.addHero(100017, nowSec = 1_700_000_000)

            val snapshot = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
            )
            val heroes = snapshot.drop(1)
                .associateBy { it[0].asText() }
                .getValue("Tb_hero")[1]
                .filter { it[1].asInt() == target.heroId }

            assertEquals(2, heroes.size)
            assertEquals(
                HeroCatalog.heroQuality(target.heroId),
                heroes.single { it[0].asInt() == target.heroUid }[29].asInt(),
            )
            assertEquals(0, heroes.single { it[0].asInt() != target.heroUid }[29].asInt())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot grants configured hongji weapons and five copies of every item`() {
        val root = createTempDirectory("stzb-inventory-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val userId = 77
            val snapshot = UserInitTableBuilder.build(
                userId = userId,
                cityWid = 10077,
                roleName = "主公",
                serverOpenTime = 1_700_000_000L,
            )
            val tables = snapshot.drop(1).associateBy { it[0].asText() }
            val gears = tables.getValue("Tb_gear")[1]
            val items = tables.getValue("Tb_user_item")[1]

            val expectedGearCount =
                InventoryCatalog.normalWeapons().size + InventoryCatalog.hongjiCopies().size
            assertEquals(expectedGearCount, gears.size())
            assertTrue(gears.all { it[2].asInt() == userId && it[5].asInt() == 2 && it[9].asInt() == 0 })
            assertEquals(50, gears.count { it[0].asInt() in 840_100_001..840_100_050 })
            assertTrue(gears.all { it[4].asInt() > 0 })

            assertEquals(111, items.size())
            assertTrue(items.all {
                it[2].asInt() == userId &&
                    it[4].asInt() == 5 &&
                    it[5].asInt() == 0 &&
                    it[6].asInt() == 0
            })
            assertEquals(111, items.map { it[1].asInt() }.distinct().size)
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `login snapshot includes other player city and claimed land`() {
        val world = WorldProjection(
            cities = listOf(
                WorldCity(15_061_506, 10, "Alice"),
                WorldCity(14_961_496, 11, "Bob"),
            ),
            lands = listOf(LandClaim(14_981_496, 11, 14_961_496, 100)),
        )

        val tables = UserInitTableBuilder.build(
            userId = 10,
            cityWid = 15_061_506,
            roleName = "Alice",
            serverOpenTime = 100,
            world = world,
        ).drop(1).associateBy { it[0].asText() }
        val rows = tables.getValue("Tb_world_city")[1]

        assertTrue(rows.any { it[0].asInt() == 14_961_496 && it[6].asInt() == 11 })
        assertTrue(rows.any {
            it[0].asInt() == 14_981_496 &&
                it[1].asInt() == 2 &&
                it[6].asInt() == 11 &&
                it[21].asInt() == 14_961_496
        })
    }

    @Test
    fun `login snapshot restores the bidirectional equipped gear fields`() {
        val root = createTempDirectory("stzb-gear-snapshot")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val state = PlayerStateRepository.getOrCreate(
                accountKey = "gear-snapshot",
                cityWid = 10064,
                roleName = "主公",
            )
            val hero = state.addHero(100017)
            val gearUid = InventoryCatalog.normalWeapons().first().uid
            assertTrue(state.equipGrantedGear(hero.heroUid, gearUid) != null)
            PlayerStateRepository.save(state)

            val tables = UserInitTableBuilder.build(
                userId = state.userId,
                cityWid = state.cityWid,
                roleName = state.roleName,
                serverOpenTime = 1_700_000_000L,
                accountKey = state.accountKey,
            ).drop(1).associateBy { it[0].asText() }

            val heroes = tables.getValue("Tb_hero")[1].associateBy { it[0].asInt() }
            val gear = tables.getValue("Tb_gear")[1].single { it[0].asInt() == gearUid }
            assertEquals(gearUid, heroes.getValue(hero.heroUid)[23].asInt())
            assertEquals(hero.heroUid, gear[9].asInt())
        } finally {
            PlayerStateRepository.reset()
        }
    }

    private fun cardExtractRows(snapshot: com.fasterxml.jackson.databind.node.ArrayNode) =
        snapshot.drop(1)
            .associateBy { it[0].asText() }
            .getValue("Tb_user_card_extract")[1]
}
