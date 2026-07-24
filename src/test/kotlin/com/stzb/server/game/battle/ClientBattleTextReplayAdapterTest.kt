package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientBattleTextReplayAdapterTest {
    @Test
    fun `creates one preparation stage and one client round per engine round`() {
        val actions = ClientBattleTextReplayAdapter.adapt(twoRoundResult())

        assertEquals(1, actions.count { it.id == ClientBattleTextReplayProtocol.PREPARE })
        assertEquals(
            listOf(listOf<Any>(1), listOf<Any>(2)),
            actions.filter { it.id == ClientBattleTextReplayProtocol.ROUND }.map { it.params },
        )
        assertEquals(
            listOf(1, 2, 4),
            actions.filter { it.id == ClientBattleTextReplayProtocol.HERO_NAME }.map { it.params.first() },
        )
    }

    @Test
    fun `projects normal skill damage and recovery into distinct text actions`() {
        val actions = ClientBattleTextReplayAdapter.adapt(eventResult())

        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.NORMAL_DAMAGE &&
                it.params == listOf<Any>(4, 1, 0, 120, 880)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.SKILL_CAST &&
                it.params == listOf<Any>(1, 1, 200012)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.SKILL_DAMAGE &&
                it.params == listOf<Any>(1, 200012, 4, 180, 700)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.RECOVERY &&
                it.params == listOf<Any>(1, 200001, 1, 70, 950)
        })
    }

    @Test
    fun `projects status dot evade and stat change with their original skill id`() {
        val actions = ClientBattleTextReplayAdapter.adapt(stateResult())

        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.STATUS &&
                it.params == listOf<Any>(1, 4, 200002, 305)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.ONGOING_DAMAGE &&
                it.params == listOf<Any>(1, 200002, 4, 60, 640, 305)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.STATUS &&
                it.params == listOf<Any>(1, 4, 0, 515)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.STATUS &&
                it.params == listOf<Any>(1, 1, 200036, 101)
        })
    }

    @Test
    fun `omits unattributed effect text actions while retaining evade`() {
        val actions = ClientBattleTextReplayAdapter.adapt(unattributedEffectsResult())

        assertTrue(actions.none { it.id == ClientBattleTextReplayProtocol.RECOVERY })
        assertTrue(actions.none { it.id == ClientBattleTextReplayProtocol.ONGOING_DAMAGE })
        assertEquals(
            listOf(listOf<Any>(1, 4, 0, 515)),
            actions.filter { it.id == ClientBattleTextReplayProtocol.STATUS }.map { it.params },
        )
        assertTrue(actions.none { it.id == ClientBattleTextReplayProtocol.SKILL_CAST })
    }

    @Test
    fun `ends the report before writing final troops for both sides`() {
        val actions = ClientBattleTextReplayAdapter.adapt(eventResult())
        val endIndex = actions.indexOfFirst { it.id == ClientBattleTextReplayProtocol.END }
        assertTrue(endIndex >= 0)
        assertTrue(
            actions.take(endIndex).none { it.id == ClientBattleTextReplayProtocol.FINAL_TROOPS },
        )
        val finalTroops = actions.drop(endIndex + 1)
            .filter { it.id == ClientBattleTextReplayProtocol.FINAL_TROOPS }

        assertEquals(3, finalTroops.size)
        assertEquals(listOf<Any>(1, 950, 50), finalTroops[0].params)
        assertEquals(listOf<Any>(2, 1000, 0), finalTroops[1].params)
        assertEquals(listOf<Any>(4, 700, 300), finalTroops[2].params)
    }

    private fun twoRoundResult(): BattleResult =
        BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(listOf(hero(1, 0), hero(2, 1))),
            defender = BattleTeam(listOf(hero(4, 0))),
            events = listOf(
                BattleEvent.BattleStart,
                BattleEvent.RoundStart(1),
                BattleEvent.RoundStart(2),
                BattleEvent.BattleEnd(BattleOutcome.ATTACKER_WIN),
            ),
        )

    private fun eventResult(): BattleResult {
        val attacker = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val defender = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(4))
        return BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(
                listOf(
                    hero(1, 0, troops = 950),
                    hero(2, 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(4, 0, troops = 700))),
            events = listOf(
                BattleEvent.NormalAttack(1, attacker, defender, 120, 880),
                BattleEvent.SkillDamage(1, 200012, 301, attacker, defender, 180, 700),
                BattleEvent.Recovery(1, attacker, attacker, 70, 950, skillId = 200001),
            ),
        )
    }

    private fun unattributedEffectsResult(): BattleResult {
        val attacker = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val defender = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(4))
        return BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(listOf(hero(1, 0))),
            defender = BattleTeam(listOf(hero(4, 0))),
            events = listOf(
                BattleEvent.Recovery(1, attacker, attacker, 70, 950),
                BattleEvent.StatusApplied(1, attacker, defender, BattleStatus.BURN, 2),
                BattleEvent.OngoingDamage(2, attacker, defender, BattleStatus.BURN, 60, 640),
                BattleEvent.StatChanged(2, attacker, attacker, BattleStat.ATTACK, 10, 2),
                BattleEvent.Evaded(2, attacker, defender),
            ),
        )
    }

    private fun stateResult(): BattleResult {
        val attacker = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val defender = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(4))
        return BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(listOf(hero(1, 0))),
            defender = BattleTeam(listOf(hero(4, 0))),
            events = listOf(
                BattleEvent.StatusApplied(1, attacker, defender, BattleStatus.BURN, 2, skillId = 200002),
                BattleEvent.OngoingDamage(2, attacker, defender, BattleStatus.BURN, 60, 640, skillId = 200002),
                BattleEvent.Evaded(2, attacker, defender),
                BattleEvent.StatChanged(2, attacker, attacker, BattleStat.ATTACK, 10, 2, skillId = 200036),
            ),
        )
    }

    private fun hero(id: Int, position: Int, troops: Int = 1_000): BattleHero =
        BattleHero(
            id = BattleHeroId(id),
            position = position,
            stats = BattleStats(1, 1, 1, 1, 0, 1),
            troops = troops,
            maxTroops = 1_000,
        )
}
