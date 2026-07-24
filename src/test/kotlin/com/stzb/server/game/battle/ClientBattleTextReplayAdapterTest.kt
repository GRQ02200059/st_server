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
            attacker = BattleTeam(listOf(hero(1, 0))),
            defender = BattleTeam(listOf(hero(4, 0))),
            events = listOf(
                BattleEvent.NormalAttack(1, attacker, defender, 120, 880),
                BattleEvent.SkillDamage(1, 200012, 301, attacker, defender, 180, 700),
                BattleEvent.Recovery(1, attacker, attacker, 70, 950, skillId = 200001),
            ),
        )
    }

    private fun hero(id: Int, position: Int): BattleHero =
        BattleHero(
            id = BattleHeroId(id),
            position = position,
            stats = BattleStats(1, 1, 1, 1, 0, 1),
            troops = 1_000,
        )
}
