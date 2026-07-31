package com.stzb.server.game

import com.stzb.server.protocol.GameServerConfig
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserInitTableBuilderTest {
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
        assertEquals(10001 * 1000 + 10, palace[0].asInt())
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
        assertEquals(1, armies.size())
        val army = armies[0]
        assertEquals(10001 * 10 + 1, army[0].asInt())
        assertEquals(42, army[1].asInt())
        assertEquals(10001, army[2].asInt())
        assertEquals(10001, army[14].asInt())

        val worldCity = tables.getValue("Tb_world_city")[1][0]
        assertEquals("", worldCity[4].asText())
        assertEquals(1, worldCity[11].asInt())

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
    fun `snapshot restores recruited heroes and saved team from player state`() {
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
        assertEquals(2, heroes.size())
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
    }

    @Test
    fun `login snapshot only exposes the original primary army`() {
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

        assertEquals(1, armies.size())
        assertEquals(100451, armies[0][0].asInt())
        assertEquals(100451, heroes.getValue(primary.heroUid)[3].asInt())
        assertEquals(0, heroes.getValue(secondary.heroUid)[3].asInt())
    }

    @Test
    fun `snapshot owns every facade without fabricating hero achievements`() {
        val snapshot = UserInitTableBuilder.build(
            userId = 44,
            cityWid = 10044,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        )

        val tables = snapshot.drop(1).associateBy { it[0].asText() }
        val facades = tables.getValue("Tb_user_facade_card")[1]
        val normal = facades.first { it[3].asInt() == 100534 }
        val achievement = facades.first { it[3].asInt() == 101300 }

        assertEquals(HeroFacadeCatalog.all().size, facades.size())
        assertEquals(100067, normal[2].asInt())
        assertEquals(0, normal[6].asInt())
        assertEquals(0, normal[7].asInt())
        assertEquals(1, normal[13].asInt())
        assertEquals(0, achievement[6].asInt())
        assertTrue("Tb_hero_achieve" !in tables)
    }

    private fun cardExtractRows(snapshot: com.fasterxml.jackson.databind.node.ArrayNode) =
        snapshot.drop(1)
            .associateBy { it[0].asText() }
            .getValue("Tb_user_card_extract")[1]
}
