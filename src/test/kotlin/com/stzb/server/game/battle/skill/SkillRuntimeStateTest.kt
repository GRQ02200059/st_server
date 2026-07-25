package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillRuntimeStateTest {
    private val refA = ref(Side.ATTACKER, 0, 100017)
    private val refB = ref(Side.ATTACKER, 1, 100018)

    @Test
    fun `runtime keys include side position hero trigger and skill`() {
        val state = SkillRuntimeState()
        val attack = ref(Side.ATTACKER, 0, 100017)
        val defend = ref(Side.DEFENDER, 0, 100017)
        val otherPosition = ref(Side.ATTACKER, 1, 100017)
        val otherHero = ref(Side.ATTACKER, 0, 100018)

        state.increment(attack, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017)

        assertEquals(1, state.count(attack, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017))
        assertEquals(0, state.count(defend, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017))
        assertEquals(0, state.count(otherPosition, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017))
        assertEquals(0, state.count(otherHero, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017))
        assertEquals(0, state.count(attack, BattleTrigger.PURSUIT_ATTEMPT, 200017))
        assertEquals(0, state.count(attack, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200018))
    }

    @Test
    fun `interrupt removes only matching hero preparations`() {
        val state = SkillRuntimeState()
        state.prepare(PreparedSkill(refA, 200031, readyRound = 2))
        state.prepare(PreparedSkill(refB, 200235, readyRound = 2))

        state.interruptPreparations(refA)

        assertEquals(listOf(200235), state.preparedSkills().map { it.skillId })
    }

    @Test
    fun `due effects use round hit and stable insertion order`() {
        val state = SkillRuntimeState()
        state.schedule(delayed(200003, dueRound = 3, dueHit = 0))
        state.schedule(delayed(200001, dueRound = 2, dueHit = 1))
        state.schedule(delayed(200002, dueRound = 2, dueHit = 1))
        state.schedule(delayed(200004, dueRound = 2, dueHit = 2))

        assertEquals(emptyList(), state.dueEffects(round = 2, hit = 0))
        assertEquals(
            listOf(200001, 200002),
            state.dueEffects(round = 2, hit = 1).map { it.skillId },
        )
        assertEquals(
            listOf(200004, 200003),
            state.dueEffects(round = 3, hit = 0).map { it.skillId },
        )
        assertEquals(emptyList(), state.dueEffects(round = 3, hit = 0))
    }

    @Test
    fun `call stack reports exact cycle path and remains unchanged`() {
        val state = SkillRuntimeState()
        state.enter(1)
        state.enter(2)

        val failure = assertFailsWith<IllegalStateException> {
            state.enter(1)
        }

        assertTrue(failure.message.orEmpty().contains("1 -> 2 -> 1"))
        assertEquals(listOf(1, 2), state.currentCallPath())
    }

    @Test
    fun `call stack permits sixteen levels and rejects the seventeenth`() {
        val state = SkillRuntimeState()
        (1..SkillRuntimeState.MAX_CHILD_DEPTH).forEach(state::enter)

        val failure = assertFailsWith<IllegalStateException> {
            state.enter(SkillRuntimeState.MAX_CHILD_DEPTH + 1)
        }

        assertTrue(failure.message.orEmpty().contains("maximum child depth 16"))
        assertEquals((1..16).toList(), state.currentCallPath())
        (16 downTo 1).forEach(state::exit)
        assertEquals(emptyList(), state.currentCallPath())
    }

    private fun delayed(skillId: Int, dueRound: Int, dueHit: Int) =
        DelayedEffect(
            source = refA,
            rootSkillId = 200000,
            skillId = skillId,
            detailId = skillId * 100,
            dueRound = dueRound,
            dueHit = dueHit,
        )

    private fun ref(side: Side, position: Int, heroId: Int) =
        BattleHeroRef(side, position, BattleHeroId(heroId))
}
