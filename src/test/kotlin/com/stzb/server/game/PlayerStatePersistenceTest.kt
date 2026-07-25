package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStatePersistenceTest {
    @Test
    fun `selected hero facade survives snapshot restore`() {
        val state = PlayerState(
            userId = 42,
            cityWid = 10001,
            roleName = "主公",
            accountKey = "facade-test",
        )
        val hero = state.addHero(100067)

        assertTrue(state.selectHeroFacade(hero.heroUid, 100534))
        assertFalse(state.selectHeroFacade(hero.heroUid, 101300))

        val restored = PlayerState.fromSnapshot(state.toSnapshot())
        assertEquals(100534, restored.hero(hero.heroUid)?.dynamicIcon)
        assertTrue(restored.selectHeroFacade(hero.heroUid, 0))
        assertEquals(0, restored.hero(hero.heroUid)?.dynamicIcon)
    }

    @Test
    fun `snapshot restores account heroes resources buildings team and march`() {
        val state = PlayerState(
            userId = 101,
            accountKey = "acct-a",
            cityWid = 100101,
            roleName = "测试主公",
        )
        val hero = state.addHero(100017, nowSec = 1_700_000_000)
        hero.troops = 777
        hero.stamina = 555_000
        hero.level = 8
        state.saveTeam(listOf(hero.heroUid))
        state.upgradeBuild(10, 4)
        state.resources.food = 123456
        state.occupyLand(100103)
        state.startMarch(targetWid = 100102, nowSec = 1_700_000_010)

        val restored = PlayerState.fromSnapshot(state.toSnapshot())

        assertEquals("acct-a", restored.accountKey)
        assertEquals(101, restored.userId)
        assertEquals(777, restored.hero(hero.heroUid)?.troops)
        assertEquals(555_000, restored.hero(hero.heroUid)?.stamina)
        assertEquals(8, restored.hero(hero.heroUid)?.level)
        assertEquals(listOf(hero.heroUid, 0, 0), restored.teamHeroes())
        assertEquals(4, restored.buildLevel(10))
        assertEquals(123456, restored.resources.food)
        assertEquals(setOf(100103), restored.occupiedLands())
        assertEquals(100102, restored.activeMarch()?.targetWid)
    }
}
