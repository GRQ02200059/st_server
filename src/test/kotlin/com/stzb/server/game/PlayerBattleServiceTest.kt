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

    // PvE battle assertions below depend on specific resource-land levels
    // (e.g. "level nine land", wid 10_603). Pin defenders to the 2001 map so
    // they stay valid regardless of the season the server currently advertises
    // (LandDefenderFactory defaults to GameServerConfig.CFG_DB_ID).
    private fun defendersOn2001() = LandDefenderFactory(LandMapRepository.load(2001))

    @Test
    fun `starting pve expedition keeps stamina available and defers battle resolution`() {
        val state = PlayerStateRepository.getOrCreate(userId = 901, cityWid = 1901, roleName = "主公")
        val first = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        val second = state.addHero(heroId = 100021, nowSec = 1_700_000_002)
        state.saveTeam(listOf(first.heroUid, second.heroUid))
        val store = ClientBattleReportStore.createEmpty()

        val result = PlayerBattleService(reportStore = store, defenderFactory = defendersOn2001()).launchPveBattle(
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
    fun `starting pve expedition freezes selected army facades`() {
        val state = PlayerState(userId = 914, cityWid = 1914, roleName = "主公")
        val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
        requireNotNull(state.bindArmyFacadeCards(101138, listOf(hero.heroUid)))
        val xiyuan = state.specialArmyFacadeCards().single { it.facadeId == 101515 }
        requireNotNull(state.setSpecialArmyFacadeState(xiyuan.heroUid, 2))
        state.saveTeam(listOf(hero.heroUid))

        PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            defenderFactory = defendersOn2001(),
        ).launchPveBattle(
            state = state,
            targetWid = 10_011,
            nowSec = 1_700_000_010,
        ) ?: error("expedition should start")

        val march = state.activeMarch() ?: error("battle launch should create a visible march")
        assertEquals(101515, march.specialArmyFacadeId)
        assertEquals(101138, march.participants.single().armyFacadeCardId)
        assertEquals("101515,0;", march.facadeIds())
    }

    @Test
    fun `expired persisted march does not permanently block another expedition`() {
        val state = PlayerState(userId = 909, cityWid = 1909, roleName = "主公")
        val hero = state.addHero(heroId = 100017, nowSec = 1_700_000_001)
        state.saveTeam(listOf(hero.heroUid))
        state.startMarch(targetWid = 10_011, nowSec = 1_700_000_000)
        val service = PlayerBattleService(ClientBattleReportStore.createEmpty(), defenderFactory = defendersOn2001())

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
        val service = PlayerBattleService(reportStore = store, defenderFactory = defendersOn2001())

        service.launchPveBattle(state = state, targetWid = 10_011, nowSec = 1_700_000_010)
            ?: error("expedition should start")

        assertEquals(null, service.settlePveBattle(state, nowSec = 1_700_000_012))

        val result = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertTrue(result.battleId > 0)
        assertEquals(10_011, result.targetWid)
        assertEquals(null, state.activeMarch())
        assertTrue((state.hero(hero.heroUid)?.troops ?: 0) in 0..1_000)

        val profile = mapper.readTree(
            store.profileResponse(state.userId, listOf(result.battleId), serverId = 0),
        )[1][0]
        assertEquals(result.battleId, profile["battle_id"].asInt())
        assertEquals(10_011, profile["wid"].asInt())
    }

    @Test
    fun `settled pve report keeps the card border and dynamic icon from departure`() {
        val state = PlayerState(userId = 913, cityWid = 1913, roleName = "主公")
        val hero = state.addHero(100017).apply {
            dynamicIcon = 100534
            cardBorder = CardBorderCatalog.DEFAULT_ID
        }
        state.saveTeam(listOf(hero.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(store, defenderFactory = defendersOn2001())

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
            ?: error("expedition should start")
        hero.cardBorder = 110997
        hero.dynamicIcon = 0

        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val profile = mapper.readTree(
            store.profileResponse(state.userId, listOf(settlement.battleId), serverId = 0),
        )[1][0]

        assertEquals("100017,100534;0,0;0,0", profile["attack_all_surface"].asText())
        assertEquals(
            "0,0,0;101260,100534,0;0,0,0;0,0,0",
            profile["attacker_surface"].asText(),
        )
        assertEquals("0,0;0,0;0,0", profile["defend_all_surface"].asText())
        assertEquals("0,0,0;0,0,0;0,0,0;0,0,0", profile["defender_surface"].asText())
    }

    @Test
    fun `settled pve report keeps the departed hero advance number`() {
        val state = PlayerState(userId = 915, cityWid = 1915, roleName = "主公")
        val hero = state.addHero(100017).apply {
            advanceNum = 5
        }
        state.saveTeam(listOf(hero.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(store, defenderFactory = defendersOn2001())

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
            ?: error("expedition should start")
        hero.advanceNum = 0

        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val profile = mapper.readTree(
            store.profileResponse(state.userId, listOf(settlement.battleId), serverId = 0),
        )[1][0]

        assertEquals(
            "0,0,0,0,0,0;5,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0",
            profile["attack_advance"].asText(),
        )
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
        val service = PlayerBattleService(ClientBattleReportStore.createEmpty(), defenderFactory = defendersOn2001())

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

        val result = PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            defenderFactory = defendersOn2001(),
        ).launchPveBattle(
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
            defenderFactory = defendersOn2001(),
        )

        service.launchPveBattle(state = state, targetWid = 10_011, nowSec = 1_700_000_010)
            ?: error("expedition should start")
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val report = store.findOrDefault(state.userId, settlement.battleId)

        assertTrue(report.result.events.none {
            it is BattleEvent.SkillPreparationStarted && it.skillId == 200031
        })
    }

    @Test
    fun `winning pve battle only marks the target land as eligible for a world claim`() {
        val state = PlayerState(userId = 905, cityWid = 1905, roleName = "主公")
        val hero = state.addHero(heroId = 100021, nowSec = 1_700_000_001).apply {
            troops = 10_000
            level = 50
        }
        state.saveTeam(listOf(hero.heroUid))
        val service = PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { FixedBattleRandom(0) },
            defenderFactory = defendersOn2001(),
        )

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
        val result = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, result.outcome)
        assertTrue(result.mayClaimLand)
        assertFalse(state.ownsLand(10_011))
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
            defenderFactory = defendersOn2001(),
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
        // wid 10_603 is a level-9 (strongest) resource land on the 2001 map, so a
        // 1-troop level-1 hero is guaranteed to lose. Pin the defenders to 2001
        // so this holds regardless of the season the server advertises.
        val service = PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { FixedBattleRandom(0) },
            defenderFactory = LandDefenderFactory(LandMapRepository.load(2001)),
        )

        service.launchPveBattle(state, targetWid = 10_603, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")

        assertNotEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, settlement.outcome)
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
        // wid 10_146 is a two-team level-6 resource land only on the 2001 map;
        // pin the defender factory to 2001 so this scenario stays valid
        // regardless of the season the server advertises.
        val defenderFactory = LandDefenderFactory(LandMapRepository.load(2001))
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(0) },
            defenderFactory = defenderFactory,
        )

        service.launchPveBattle(state, targetWid = 10_146, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val report = store.findOrDefault(state.userId, settlement.battleId)
        val expectedLastTeam = defenderFactory.teamsForWid(10_146).last().map { it.heroId }

        assertEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, settlement.outcome)
        assertEquals(expectedLastTeam, report.result.defender.heroes.map { it.id.value })
        assertTrue(settlement.mayClaimLand)
        assertFalse(state.ownsLand(10_146))
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
            defenderFactory = defendersOn2001(),
        )

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve battle")
        val report = store.findOrDefault(state.userId, settlement.battleId)

        assertTrue(report.result.attacker.heroes.single().skillIds.contains(200021))
        assertEquals(
            List(report.result.attacker.heroes.single().skillIds.size) { PlayerHero.MAX_SKILL_LEVEL },
            report.result.entryAttacker?.heroes?.single()?.skillLevels,
        )
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
        val skills = store.findOrDefault(state.userId, settlement.battleId).result.attacker.heroes.single().skillIds

        assertEquals(listOf(200021, 200012), skills)
        assertFalse(200031 in skills)
    }

    @Test
    fun `expedition settles with the heroes locked at departure after the army is changed`() {
        val state = PlayerState(userId = 913, cityWid = 1913, roleName = "主公")
        val departed = state.addHero(heroId = 100021).apply {
            troops = 10_000
            level = 50
        }
        val replacement = state.addHero(heroId = 100017).apply {
            troops = 777
            level = 1
        }
        state.saveTeam(listOf(departed.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(0) },
            defenderFactory = defendersOn2001(),
        )

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
            ?: error("expedition should start")
        state.saveTeam(listOf(replacement.heroUid))
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("departed army should still settle")
        val report = store.findOrDefault(state.userId, settlement.battleId)

        assertEquals(listOf(100021), report.result.attacker.heroes.map { it.id.value })
        assertEquals(777, replacement.troops)
        assertEquals(report.result.attacker.heroes.single().troops, departed.troops)
    }

    @Test
    fun `clearing the current army does not consume its departed expedition without a battle`() {
        val state = PlayerState(userId = 914, cityWid = 1914, roleName = "主公")
        val departed = state.addHero(heroId = 100021).apply {
            troops = 10_000
            level = 50
        }
        state.saveTeam(listOf(departed.heroUid))
        val store = ClientBattleReportStore.createEmpty()
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(0) },
            defenderFactory = defendersOn2001(),
        )

        service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
            ?: error("expedition should start")
        state.saveTeam(emptyList())
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("departed army should still settle")
        val report = store.findOrDefault(state.userId, settlement.battleId)

        assertEquals(com.stzb.server.game.battle.BattleOutcome.ATTACKER_WIN, settlement.outcome)
        assertEquals(null, state.activeMarch())
        assertEquals(report.result.attacker.heroes.single().troops, departed.troops)
    }

    @Test
    fun `the same persisted expedition uses the same random seed regardless of settlement time`() {
        fun launchedState(): PlayerState =
            PlayerState(userId = 915, cityWid = 1915, roleName = "主公").also { state ->
                val hero = state.addHero(heroId = 100021).apply {
                    troops = 10_000
                    level = 50
                }
                state.saveTeam(listOf(hero.heroUid))
                state.startMarch(targetWid = 10_011, nowSec = 1_700_000_010)
            }

        val firstSeeds = mutableListOf<Int>()
        val secondSeeds = mutableListOf<Int>()
        val first = launchedState()
        val second = PlayerState.fromSnapshot(launchedState().toSnapshot(), nowSec = 1_700_000_011)
        PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { seed -> firstSeeds += seed; FixedBattleRandom(0) },
            defenderFactory = defendersOn2001(),
        ).settlePveBattle(first, nowSec = 1_700_000_013)
        PlayerBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { seed -> secondSeeds += seed; FixedBattleRandom(0) },
            defenderFactory = defendersOn2001(),
        ).settlePveBattle(second, nowSec = 1_700_000_099)

        assertEquals(firstSeeds, secondSeeds)
        assertTrue(firstSeeds.isNotEmpty())
    }

    @Test
    fun `each defender wave has an independently replayable report`() {
        val state = PlayerState(userId = 916, cityWid = 1916, roleName = "主公")
        val heroes = listOf(100010, 100479, 100022).map { heroId ->
            state.addHero(heroId).apply {
                troops = 10_000
                level = 1_000
            }
        }
        state.saveTeam(heroes.map { it.heroUid })
        val store = ClientBattleReportStore.createEmpty()
        val defenderFactory = defendersOn2001()
        val service = PlayerBattleService(
            reportStore = store,
            battleRandomFactory = { FixedBattleRandom(0) },
            defenderFactory = defenderFactory,
        )

        service.launchPveBattle(state, targetWid = 10_146, nowSec = 1_700_000_010)
        val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
            ?: error("arrival should resolve all defender waves")
        val firstWave = store.findOrDefault(state.userId, settlement.battleId - 1)
        val finalWave = store.findOrDefault(state.userId, settlement.battleId)

        assertEquals(10_146, firstWave.wid)
        assertEquals(
            defenderFactory.teamsForWid(10_146).first().map { it.heroId },
            firstWave.result.defender.heroes.map { it.id.value },
        )
        assertEquals(
            defenderFactory.teamsForWid(10_146).last().map { it.heroId },
            finalWave.result.defender.heroes.map { it.id.value },
        )
        assertEquals(
            firstWave.result.attacker.heroes.map { it.stats },
            finalWave.result.attacker.heroes.map { it.stats },
        )
    }

    @Test
    fun `captured map five expedition settles instead of remaining in march state`() {
        val state = PlayerState(userId = 10003, cityWid = 15061506, roleName = "主公")
        val heroes = listOf(
            Triple(100027, 9_899, listOf(200027, 200755, 200031)),
            Triple(100498, 6_615, listOf(200739, 200755, 200980)),
            Triple(100023, 4_797, listOf(200023, 200220, 200228)),
        ).map { (heroId, troops, skillIds) ->
            state.addHero(heroId).apply {
                this.troops = troops
                level = 50
                this.skillIds = skillIds.toMutableList()
            }
        }
        state.saveTeam(heroes.map(PlayerHero::heroUid))
        val service = PlayerBattleService(ClientBattleReportStore.createEmpty())

        service.launchPveBattle(
            state = state,
            targetWid = 15051508,
            nowSec = 1785573011,
        ) ?: error("captured expedition should start")

        val settlement = service.settlePveBattle(
            state = state,
            nowSec = 1785573014,
        ) ?: error("captured expedition should settle on arrival")

        assertTrue(settlement.battleId > 0)
        assertEquals(null, state.activeMarch())
    }

    @Test
    fun `captured army with skill 200255 settles instead of disappearing without a report`() {
        val state = PlayerState(userId = 10003, cityWid = 15061506, roleName = "主公")
        val heroes = listOf(
            Triple(100683, 10_000, listOf(200957, 200647, 200863)),
            Triple(100677, 8_913, listOf(200955, 200980, 200220)),
            Triple(100785, 4_549, listOf(200255, 200980, 200647)),
        ).map { (heroId, troops, skillIds) ->
            state.addHero(heroId).apply {
                this.troops = troops
                level = 50
                this.skillIds = skillIds.toMutableList()
            }
        }
        val armyId = state.armyIds()[1]
        state.saveTeam(heroes.map(PlayerHero::heroUid), armyId)
        val service = PlayerBattleService(ClientBattleReportStore.createEmpty())

        service.launchPveBattle(
            state = state,
            targetWid = 15051508,
            armyId = armyId,
            nowSec = 1785577168,
        ) ?: error("captured expedition should start")

        val settlement = service.settlePveBattle(
            state = state,
            armyId = armyId,
            nowSec = 1785577171,
        ) ?: error("captured expedition should settle on arrival")

        assertTrue(settlement.battleId > 0)
        assertEquals(null, state.activeMarch(armyId))
    }
}
