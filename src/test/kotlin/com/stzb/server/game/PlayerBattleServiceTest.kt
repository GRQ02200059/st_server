package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.ClientBattleReportStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerBattleServiceTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `starting pve expedition consumes stamina and defers battle resolution`() {
        val state = PlayerStateRepository.getOrCreate(userId = 901, cityWid = 1901, roleName = "主公")
        val first = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        val second = state.addHero(heroId = 100021, nowSec = 1_700_000_002)
        state.saveTeam(listOf(first.heroUid, second.heroUid))
        val store = ClientBattleReportStore.createEmpty()

        val result = PlayerBattleService(reportStore = store).launchPveBattle(
            state = state,
            targetWid = 1902,
            nowSec = 1_700_000_010,
        ) ?: error("battle should launch")

        assertEquals(0, result.battleId)
        assertEquals(PlayerHero.MAX_STAMINA - 200_000, state.hero(first.heroUid)?.stamina)
        assertEquals(PlayerHero.MAX_STAMINA - 200_000, state.hero(second.heroUid)?.stamina)
        assertEquals(1_000, state.hero(first.heroUid)?.troops)
        assertEquals(1_000, state.hero(second.heroUid)?.troops)
        val march = state.activeMarch() ?: error("battle launch should create a visible march")
        assertEquals(state.primaryArmyId(), march.armyId)
        assertEquals(1901, march.fromWid)
        assertEquals(1902, march.targetWid)
        assertTrue(march.endSec > march.beginSec)
    }

    @Test
    fun `arriving pve expedition resolves battle and creates report`() {
        val state = PlayerStateRepository.getOrCreate(userId = 903, cityWid = 1903, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.saveTeam(listOf(hero.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(reportStore = store)

        service.launchPveBattle(state = state, targetWid = 1904, nowSec = 1_700_000_010)
            ?: error("expedition should start")

        assertEquals(null, service.settlePveBattle(state, nowSec = 1_700_000_012))

        val result = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertTrue(result.battleId > 0)
        assertEquals(1904, result.targetWid)
        assertEquals(null, state.activeMarch())
        assertTrue((state.hero(hero.heroUid)?.troops ?: 0) in 1..1_000)

        val profile = mapper.readTree(store.profileResponse(listOf(result.battleId), serverId = 0))[1][0]
        assertEquals(result.battleId, profile["battle_id"].asInt())
        assertEquals(1904, profile["wid"].asInt())
    }

    @Test
    fun `launching pve battle is rejected when no team hero has stamina`() {
        val state = PlayerStateRepository.getOrCreate(userId = 902, cityWid = 1902, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.hero(hero.heroUid)?.stamina = 0
        state.saveTeam(listOf(hero.heroUid))

        val result = PlayerBattleService(reportStore = ClientBattleReportStore.createEmpty()).launchPveBattle(
            state = state,
            targetWid = 1903,
            nowSec = 1_700_000_010,
        )

        assertEquals(null, result)
        assertEquals(0, state.hero(hero.heroUid)?.stamina)
        assertEquals(1_000, state.hero(hero.heroUid)?.troops)
    }
}
