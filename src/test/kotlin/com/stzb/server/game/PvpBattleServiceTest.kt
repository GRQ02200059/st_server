package com.stzb.server.game

import com.stzb.server.game.battle.BattleHeroSpec
import com.stzb.server.game.battle.BattleOutcome
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.FixedBattleRandom
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PvpBattleServiceTest {
    @BeforeTest
    fun setUp() {
        WorldStateRepository.configure(Files.createTempDirectory("stzb-pvp-battle-"))
    }

    @AfterTest
    fun tearDown() {
        WorldStateRepository.reset()
    }

    private fun weakGarrison(wid: Int, owner: Int) = GarrisonSnapshot(
        wid = wid,
        ownerUserId = owner,
        armyId = 999,
        specs = listOf(BattleHeroSpec(heroId = 100017, position = 0, troops = 1, level = 1)),
        residedAtSec = 1_700_000_000,
    )

    @Test
    fun `attacker win clears garrison and transfers ownership`() {
        val defender = PlayerState(userId = 720, cityWid = 15061520, roleName = "守方")
        val attacker = PlayerState(userId = 710, cityWid = 15061510, roleName = "攻方")
        val hero = attacker.addHero(heroId = 100021).apply { troops = 100_000; level = 1000 }
        attacker.saveTeam(listOf(hero.heroUid))
        val targetWid = 15051530
        WorldStateRepository.putGarrison(weakGarrison(targetWid, owner = 720))

        // Mirror production: cmd 6 launches the attack march with participants.
        val participants = listOf(
            PlayerMarchHero(
                heroUid = hero.heroUid,
                position = 0,
                heroId = hero.heroId,
                troops = hero.troops,
                level = hero.level,
                skillIds = hero.normalizedSkillIds(),
            ),
        )
        attacker.startMarch(targetWid = targetWid, nowSec = 1_700_000_000, participants = participants)
        val march = attacker.completeMarchIfDue(1_700_000_600)!!
        val service = PvpBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { FixedBattleRandom(0) },
        )

        val result = service.settle(
            attacker = attacker,
            march = march,
            garrison = WorldStateRepository.garrisonAt(targetWid)!!,
            nowSec = 1_700_000_600,
            loadDefenderState = { if (it == 720) defender else null },
        )

        assertEquals(BattleOutcome.ATTACKER_WIN, result.outcome)
        assertEquals(720, result.defenderUserId)
        assertTrue(result.ownershipTransferred)
        assertNull(WorldStateRepository.garrisonAt(targetWid))
        assertTrue(attacker.ownsLand(targetWid))
        assertTrue(result.battleId > 0)
    }
}
