package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserInitTableBuilderTest {
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
        assertTrue(tables.containsKey("Tb_hero"))
        assertTrue(tables.containsKey("Tb_hero_temp"))
        assertTrue(tables.containsKey("Tb_hero_identity"))
        assertTrue(tables.containsKey("Tb_user_skill"))
        assertTrue(tables.containsKey("Tb_gear"))
        assertTrue(tables.containsKey("Tb_battle_report_attack"))
        assertTrue(tables.containsKey("Tb_mail_receive"))

        val palace = tables.getValue("Tb_user_build")[1][0]
        assertEquals(10001 * 1000 + 10, palace[0].asInt())
        assertEquals(10001, palace[1].asInt())
        assertEquals(10, palace[2].asInt())
        assertEquals(42, palace[3].asInt())
        assertEquals(1, palace[4].asInt())

        val buildEffect = tables.getValue("Tb_build_effect_city")[1][0]
        assertEquals(10001, buildEffect[0].asInt())
        assertEquals(42, buildEffect[1].asInt())
        assertEquals(100, buildEffect[26].asInt())

        val army = tables.getValue("Tb_army")[1][0]
        assertEquals(10001 * 10 + 1, army[0].asInt())
        assertEquals(42, army[1].asInt())
        assertEquals(10001, army[2].asInt())
        assertEquals(10001, army[14].asInt())

        val activity = tables.getValue("Tb_activity")[1][0]
        assertEquals(875, activity[1].asInt())
        assertEquals("362,0;363,0;364,0;365,0;", activity[3].asText())
        assertEquals("0,0,0,0", activity[4].asText())
        assertEquals("0,0,0,0", activity[5].asText())
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
        assertEquals(80, heroes[0][7].asInt())
        assertEquals(750, heroes[0][11].asInt())
        assertEquals(second.heroUid, heroes[1][0].asInt())
        assertEquals(100021, heroes[1][1].asInt())

        val army = tables.getValue("Tb_army")[1][0]
        assertEquals(0, army[5].asInt())
        assertEquals(second.heroUid, army[6].asInt())
        assertEquals(first.heroUid, army[7].asInt())
        assertEquals(10043 * 10 + 1, heroes[0][3].asInt())
        assertEquals(10043 * 10 + 1, heroes[1][3].asInt())
    }
}
