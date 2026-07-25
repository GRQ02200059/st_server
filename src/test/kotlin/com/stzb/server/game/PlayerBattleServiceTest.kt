package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.BattleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlayerBattleServiceTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `starting pve expedition keeps stamina available and defers battle resolution`() {
        val state = PlayerStateRepository.getOrCreate(userId = 901, cityWid = 1901, roleName = "主公")
        val first = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        val second = state.addHero(heroId = 100021, nowSec = 1_700_000_002)
        state.saveTeam(listOf(first.heroUid, second.heroUid))
        val store = ClientBattleReportStore.createEmpty()

        val result = PlayerBattleService(reportStore = store).launchPveBattle(
            state = state,
            targetWid = 10_011,
            nowSec = 1_700_000_010,
        ) ?: error("battle should launch")

        assertEquals(0, result.battleId)
        assertEquals(PlayerHero.MAX_STAMINA, state.hero(first.heroUid)?.stamina)
        assertEquals(PlayerHero.MAX_STAMINA, state.hero(second.heroUid)?.stamina)
        assertEquals(1_000, state.hero(first.heroUid)?.troops)
        assertEquals(1_000, state.hero(second.heroUid)?.troops)
        val march = state.activeMarch() ?: error("battle launch should create a visible march")
        assertEquals(state.primaryArmyId(), march.armyId)
        assertEquals(1901, march.fromWid)
        assertEquals(10_011, march.targetWid)
        assertTrue(march.endSec > march.beginSec)
    }

    @Test
    fun `expired persisted march does not permanently block another expedition`() {
        val state = PlayerState(userId = 909, cityWid = 1909, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.saveTeam(listOf(hero.heroUid))
        state.startMarch(targetWid = 10_011, nowSec = 1_700_000_000)
        val service = PlayerBattleService(ClientBattleReportStore.createEmpty())

        val result = service.launchPveBattle(
            state = state,
            targetWid = 10_012,
            nowSec = 1_700_000_010,
        )

        assertEquals(10_012, result?.targetWid)
        assertEquals(10_012, state.activeMarch()?.targetWid)
    }

    @Test
    fun `arriving pve expedition resolves battle and creates report`() {
        val state = PlayerStateRepository.getOrCreate(userId = 903, cityWid = 1903, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.saveTeam(listOf(hero.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(reportStore = store)

        service.launchPveBattle(state = state, targetWid = 10_011, nowSec = 1_700_000_010)
            ?: error("expedition should start")

        assertEquals(null, service.settlePveBattle(state, nowSec = 1_700_000_012))

        val result = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertTrue(result.battleId > 0)
        assertEquals(10_011, result.targetWid)
        assertEquals(null, state.activeMarch())
        assertTrue((state.hero(hero.heroUid)?.troops ?: 0) in 0..1_000)

        val profile = mapper.readTree(store.profileResponse(listOf(result.battleId), serverId = 0))[1][0]
        assertEquals(result.battleId, profile["battle_id"].asInt())
        assertEquals(10_011, profile["wid"].asInt())
    }

    @Test
    fun `second army expedition keeps its own army id and team`() {
        val state = PlayerState(userId = 907, cityWid = 1907, roleName = "主公")
        val first = state.addHero(100017)
        val second = state.addHero(100021)
        val firstArmyId = state.armyIds()[0]
        val secondArmyId = state.armyIds()[1]
        state.assignTeamHero(first.heroUid, 1, firstArmyId)
        state.assignTeamHero(second.heroUid, 1, secondArmyId)
        val service = PlayerBattleService(ClientBattleReportStore.createEmpty())

        service.launchPveBattle(state, targetWid = 10_011, armyId = secondArmyId, nowSec = 1_700_000_010)
            ?: error("second army should launch")

        assertEquals(secondArmyId, state.activeMarch(secondArmyId)?.armyId)
        assertEquals(PlayerHero.MAX_STAMINA, first.stamina)
        assertEquals(PlayerHero.MAX_STAMINA, second.stamina)
    }

    @Test
    fun `legacy zero stamina does not permanently block expedition`() {
        val state = PlayerStateRepository.getOrCreate(userId = 902, cityWid = 1902, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.hero(hero.heroUid)?.stamina = 0
        state.saveTeam(listOf(hero.heroUid))

        val result = PlayerBattleService(reportStore = ClientBattleReportStore.createEmpty()).launchPveBattle(
            state = state,
            targetWid = 10_011,
            nowSec = 1_700_000_010,
        )

        assertEquals(10_011, result?.targetWid)
        assertEquals(PlayerHero.MAX_STAMINA, state.hero(hero.heroUid)?.stamina)
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

        service.launchPveBattle(state = state, targetWid = 10_011, nowSec = 1_700_000_010)
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

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
        val result = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, result.outcome)
        assertTrue(state.ownsLand(10_011))
    }

    @Test
    fun `understrength default army does not win or occupy a level nine land`() {
        val state = PlayerState(userId = 908, cityWid = 1908, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001).apply {
            troops = 1_000
            level = 50
        }
        state.saveTeam(listOf(hero.heroUid))
        val service = PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { FixedBattleRandom(99) },
        )

        service.launchPveBattle(state, targetWid = 10_603, nowSec = 1_700_000_010)
        val result = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertNotEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, result.outcome)
        assertFalse(state.ownsLand(10_603))
    }

    @Test
    fun `a defeated base hero persists zero troops after settlement`() {
        val state = PlayerState(userId = 912, cityWid = 1912, roleName = "主公")
        val hero = state.addHero(heroId = 100017).apply {
            troops = 1
            level = 1
        }
        state.saveTeam(listOf(hero.heroUid))
        val service = PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { FixedBattleRandom(99) },
        )

        service.launchPveBattle(state, targetWid = 10_603, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertEquals(com.stzb.server.game.battle.BattleOutcome.DEFENDER_WIN, settlement.outcome)
        assertEquals(0, state.hero(hero.heroUid)?.troops)
    }

    @Test
    fun `level six land must defeat both client configured defender teams`() {
        val state = PlayerState(userId = 911, cityWid = 1911, roleName = "主公")
        val heroes = listOf(100010, 100479, 100022).map { heroId ->
            state.addHero(heroId).apply {
                troops = 100_000
                level = 1_000
            }
        }
        state.saveTeam(heroes.map { it.heroUid })
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(0) },
        )

        service.launchPveBattle(state, targetWid = 10_146, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val report = store.findOrDefault(settlement.battleId)
        val expectedLastTeam = LandDefenderFactory().teamsForWid(10_146).last().map { it.heroId }

        assertEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, settlement.outcome)
        assertEquals(expectedLastTeam, report.result.defender.heroes.map { it.id.value })
        assertTrue(state.ownsLand(10_146))
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

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val report = store.findOrDefault(settlement.battleId)

        assertTrue(report.result.attacker.heroes.single().skillIds.contains(200021))
        assertTrue(report.result.events.any {
            it is BattleEvent.SkillDamage && it.skillId == 200021
        })
    }

    @Test
    fun `battle uses persistent hero slots and excludes a forgotten skill`() {
        val state = PlayerState(userId = 910, cityWid = 1910, roleName = "主公")
        val hero = state.addHero(heroId = 100021, nowSec = 1_700_000_001).apply {
            troops = 10_000
            skillIds = mutableListOf(200021, 200012, 0)
        }
        state.saveTeam(listOf(hero.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(0) },
        )

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val skills = store.findOrDefault(settlement.battleId).result.attacker.heroes.single().skillIds

        assertEquals(listOf(200021, 200012), skills)
        assertFalse(200031 in skills)
    }
}
