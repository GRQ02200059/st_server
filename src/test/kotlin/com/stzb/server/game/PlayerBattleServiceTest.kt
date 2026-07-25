package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.BattleEvent
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

    @Test
    fun `settlement uses its configured random source instead of forcing every skill roll to zero`() {
        val state = PlayerStateRepository.getOrCreate(userId = 904, cityWid = 1904, roleName = "主公")
        val hero = state.addHero(heroId = 130031, nowSec = 1_700_000_001)
        state.saveTeam(listOf(hero.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(99) },
        )

        service.launchPveBattle(state = state, targetWid = 1905, nowSec = 1_700_000_010)
            ?: error("expedition should start")
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val report = store.findOrDefault(settlement.battleId)

        assertTrue(report.result.events.none {
            it is BattleEvent.SkillPreparationStarted && it.skillId == 200031
        })
    }

    @Test
    fun `winning pve battle occupies the target land`() {
        val state = PlayerState(userId = 905, cityWid = 1905, roleName = "主公")
        val hero = state.addHero(heroId = 100021, nowSec = 1_700_000_001).apply {
            troops = 10_000
            level = 50
        }
        state.saveTeam(listOf(hero.heroUid))
        val service = PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { FixedBattleRandom(0) },
        )

        service.launchPveBattle(state, targetWid = 1906, nowSec = 1_700_000_010)
        val result = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, result.outcome)
        assertTrue(state.ownsLand(1906))
    }

    @Test
    fun `settled report retains equipped hero skills and skill actions`() {
        val state = PlayerState(userId = 906, cityWid = 1906, roleName = "主公")
        val hero = state.addHero(heroId = 100021, nowSec = 1_700_000_001).apply {
            troops = 10_000
            level = 50
        }
        state.saveTeam(listOf(hero.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(0) },
        )

        service.launchPveBattle(state, targetWid = 1907, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val report = store.findOrDefault(settlement.battleId)

        assertTrue(report.result.attacker.heroes.single().skillIds.contains(200021))
        assertTrue(report.result.events.any {
            it is BattleEvent.SkillDamage && it.skillId == 200021
        })
    }
}
