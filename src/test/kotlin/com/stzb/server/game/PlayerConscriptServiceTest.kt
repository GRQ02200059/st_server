package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerConscriptServiceTest {
    @Test
    fun `conscript fills hero troops without spending unlimited resources`() {
        val state = PlayerStateRepository.getOrCreate(userId = 911, cityWid = 1911, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.hero(hero.heroUid)?.troops = 600

        val result = PlayerConscriptService().conscript(
            state = state,
            request = ConscriptRequest(type = 0, allocations = listOf(ConscriptAllocation(hero.heroUid, 250))),
        )

        assertEquals(1911 * 10 + 1, result.armyId)
        assertEquals(listOf(hero), result.updatedHeroes)
        assertEquals(850, state.hero(hero.heroUid)?.troops)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.food)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.money)
    }

    @Test
    fun `conscript does not exceed max troops`() {
        val state = PlayerStateRepository.getOrCreate(userId = 912, cityWid = 1912, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.hero(hero.heroUid)?.troops = 900

        PlayerConscriptService().conscript(
            state = state,
            request = ConscriptRequest(type = 0, allocations = listOf(ConscriptAllocation(hero.heroUid, 250))),
        )

        assertEquals(1_000, state.hero(hero.heroUid)?.troops)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.food)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.money)
    }

    @Test
    fun `conscript ignores resource balance in unlimited mode`() {
        val state = PlayerStateRepository.getOrCreate(userId = 913, cityWid = 1913, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.hero(hero.heroUid)?.troops = 500
        state.resources.food = 30
        state.resources.money = 80

        PlayerConscriptService().conscript(
            state = state,
            request = ConscriptRequest(type = 0, allocations = listOf(ConscriptAllocation(hero.heroUid, 100))),
        )

        assertEquals(600, state.hero(hero.heroUid)?.troops)
        assertEquals(30, state.resources.food)
        assertEquals(80, state.resources.money)
    }
}
