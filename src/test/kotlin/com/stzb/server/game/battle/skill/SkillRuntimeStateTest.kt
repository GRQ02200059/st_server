package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillRuntimeStateTest {
    @Test
    fun `limited occurrences stop at their configured cap per owner and namespace`() {
        val runtime = SkillRuntimeState()
        val owner = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(100268))

        assertTrue(runtime.consumeLimitedOccurrence(owner, "skill.200268.marked-attack", 2))
        assertTrue(runtime.consumeLimitedOccurrence(owner, "skill.200268.marked-attack", 2))
        assertFalse(runtime.consumeLimitedOccurrence(owner, "skill.200268.marked-attack", 2))
        assertTrue(runtime.consumeLimitedOccurrence(owner, "other", 2))
    }

    @Test
    fun `round hurt counts isolate target and round`() {
        val runtime = SkillRuntimeState()
        val first = BattleHeroRef(Side.DEFENDER, 2, BattleHeroId(1))
        val second = BattleHeroRef(Side.DEFENDER, 1, BattleHeroId(2))

        runtime.recordRoundHurt(first, 1)
        runtime.recordRoundHurt(first, 1)
        runtime.recordRoundHurt(second, 1)

        assertEquals(2, runtime.roundHurtCount(first, 1))
        assertEquals(1, runtime.roundHurtCount(second, 1))
        assertEquals(0, runtime.roundHurtCount(first, 2))
    }

    private val refA = ref(Side.ATTACKER, 0, 100017)
    private val refB = ref(Side.ATTACKER, 1, 100018)

    @Test
    fun `battle triggers retain the required values and order`() {
        assertEquals(
            listOf(
                "BATTLE_PASSIVE",
                "BATTLE_COMMAND",
                "ROUND_START",
                "ACTION_BEFORE",
                "ACTIVE_SKILL_ATTEMPT",
                "NORMAL_ATTACK_BEFORE",
                "NORMAL_ATTACK_AFTER",
                "DAMAGE_BEFORE",
                "DAMAGE_AFTER",
                "EFFECT_APPLYING",
                "EFFECT_APPLIED",
                "HURT_AFTER",
                "RECOVERY_AFTER",
                "PURSUIT_ATTEMPT",
                "ACTION_AFTER",
                "ROUND_END",
                "BASE_HERO_DEFEATED",
            ),
            BattleTrigger.entries.map(BattleTrigger::name),
        )
    }

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
    fun `attempt totals aggregate skills but retain hero and trigger isolation`() {
        val state = SkillRuntimeState()

        state.recordAttempt(refA, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 1, round = 1)
        state.recordAttempt(refA, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 2, round = 1)
        state.recordAttempt(refA, BattleTrigger.PURSUIT_ATTEMPT, 3, round = 1)
        state.recordAttempt(refB, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 1, round = 1)

        assertEquals(2, state.attemptCount(refA, BattleTrigger.ACTIVE_SKILL_ATTEMPT))
        assertEquals(1, state.attemptCount(refA, BattleTrigger.PURSUIT_ATTEMPT))
        assertEquals(1, state.attemptCount(refB, BattleTrigger.ACTIVE_SKILL_ATTEMPT))
    }

    @Test
    fun `attempt totals aggregate by side across heroes and skills`() {
        val defender = ref(Side.DEFENDER, 0, 100019)
        val state = SkillRuntimeState()

        state.recordAttempt(refA, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 1, round = 1)
        state.recordAttempt(refB, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 2, round = 1)
        state.recordAttempt(refB, BattleTrigger.PURSUIT_ATTEMPT, 3, round = 1)
        state.recordAttempt(defender, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 1, round = 1)

        assertEquals(2, state.sideAttemptCount(Side.ATTACKER, BattleTrigger.ACTIVE_SKILL_ATTEMPT))
        assertEquals(1, state.sideAttemptCount(Side.ATTACKER, BattleTrigger.PURSUIT_ATTEMPT))
        assertEquals(1, state.sideAttemptCount(Side.DEFENDER, BattleTrigger.ACTIVE_SKILL_ATTEMPT))
    }

    @Test
    fun `Task 12 battle event counter interface records every required trigger independently`() {
        val state = SkillRuntimeState()
        val requiredTask12Events = listOf(
            BattleTrigger.NORMAL_ATTACK_AFTER,
            BattleTrigger.DAMAGE_AFTER,
            BattleTrigger.HURT_AFTER,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            BattleTrigger.PURSUIT_ATTEMPT,
        )

        requiredTask12Events.forEach { trigger ->
            assertEquals(1, state.recordBattleTriggerOccurrence(refA, trigger))
        }

        requiredTask12Events.forEach { trigger ->
            assertEquals(1, state.count(refA, trigger), "trigger=$trigger")
            assertEquals(0, state.count(refB, trigger), "trigger=$trigger")
        }
    }

    @Test
    fun `battle event counters aggregate by side without crossing formations`() {
        val defender = ref(Side.DEFENDER, 0, 100019)
        val state = SkillRuntimeState()

        state.recordBattleTriggerOccurrence(refA, BattleTrigger.DAMAGE_AFTER)
        state.recordBattleTriggerOccurrence(refB, BattleTrigger.DAMAGE_AFTER)
        state.recordBattleTriggerOccurrence(defender, BattleTrigger.DAMAGE_AFTER)

        assertEquals(2, state.sideCount(Side.ATTACKER, BattleTrigger.DAMAGE_AFTER))
        assertEquals(1, state.sideCount(Side.DEFENDER, BattleTrigger.DAMAGE_AFTER))
    }

    @Test
    fun `detail markers are target scoped and expire after configured rounds`() {
        val state = SkillRuntimeState()

        state.recordMarker(
            target = refA,
            detailId = 21001701,
            value = 7,
            appliedRound = 2,
            durationRounds = 2,
        )

        assertTrue(state.hasMarker(refA, 21001701, round = 2))
        assertTrue(state.hasMarker(refA, 21001701, round = 3))
        assertEquals(7, state.markerValue(refA, 21001701, round = 3))
        assertEquals(false, state.hasMarker(refB, 21001701, round = 3))
        assertEquals(false, state.hasMarker(refA, 21001701, round = 4))
        assertEquals(null, state.markerValue(refA, 21001701, round = 4))
    }

    @Test
    fun `detail marker can be consumed without affecting another target`() {
        val state = SkillRuntimeState()
        state.recordMarker(refA, 21002401, 0, appliedRound = 1, durationRounds = 2)
        state.recordMarker(refB, 21002401, 0, appliedRound = 1, durationRounds = 2)

        assertTrue(state.removeMarker(refA, 21002401))

        assertEquals(false, state.hasMarker(refA, 21002401, round = 1))
        assertTrue(state.hasMarker(refB, 21002401, round = 1))
    }

    @Test
    fun `zero round marker remains visible for the current cast only`() {
        val state = SkillRuntimeState()
        state.recordMarker(refA, 21098402, 0, appliedRound = 3, durationRounds = 0)

        assertTrue(state.hasMarker(refA, 21098402, round = 3))
        assertEquals(false, state.hasMarker(refA, 21098402, round = 4))
    }

    @Test
    fun `threshold generations are consumed once per owner and threshold`() {
        val state = SkillRuntimeState()

        assertEquals(false, state.consumeThreshold(refA, "damage", count = 2, threshold = 3))
        assertEquals(true, state.consumeThreshold(refA, "damage", count = 3, threshold = 3))
        assertEquals(false, state.consumeThreshold(refA, "damage", count = 4, threshold = 3))
        assertEquals(true, state.consumeThreshold(refA, "damage", count = 6, threshold = 3))
        assertEquals(false, state.consumeThreshold(refA, "damage", count = 6, threshold = 3))
        assertEquals(true, state.consumeThreshold(refB, "damage", count = 3, threshold = 3))
    }

    @Test
    fun `pending signals wait until their ready round and consume once`() {
        val state = SkillRuntimeState()

        state.scheduleSignal(refA, "zhengshi", readyRound = 3)

        assertEquals(false, state.consumeSignal(refA, "zhengshi", round = 2))
        assertEquals(true, state.consumeSignal(refA, "zhengshi", round = 3))
        assertEquals(false, state.consumeSignal(refA, "zhengshi", round = 3))
        assertEquals(false, state.consumeSignal(refB, "zhengshi", round = 3))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `recordTrigger remains a compatibility alias for the explicit battle occurrence contract`() {
        val state = SkillRuntimeState()

        assertEquals(1, state.recordTrigger(refA, BattleTrigger.DAMAGE_AFTER))
        assertEquals(1, state.count(refA, BattleTrigger.DAMAGE_AFTER))
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
    fun `prepare suppresses same source and current skill without replacing snapshot`() {
        val state = SkillRuntimeState()
        val original = PreparedSkill(
            source = refA,
            skillId = 200031,
            rootSkillId = 200001,
            startedRound = 1,
            readyRound = 2,
        )
        val duplicate = original.copy(rootSkillId = 200002, startedRound = 3, readyRound = 4)
        val otherSource = original.copy(source = ref(Side.DEFENDER, 0, 100017))
        val otherSkill = original.copy(skillId = 200032)

        assertEquals(true, state.prepare(original))
        assertEquals(false, state.prepare(duplicate))
        assertEquals(true, state.prepare(otherSource))
        assertEquals(true, state.prepare(otherSkill))

        assertEquals(listOf(original, otherSource, otherSkill), state.preparedSkills())
    }

    @Test
    fun `prepared and delayed records retain source root and current skill ids`() {
        val prepared = PreparedSkill(
            source = refA,
            skillId = 200031,
            rootSkillId = 200001,
            readyRound = 2,
        )
        val delayed = DelayedEffect(
            source = refB,
            rootSkillId = 200002,
            skillId = 200032,
            detailId = 1,
            dueRound = 2,
        )

        assertEquals(refA, prepared.source)
        assertEquals(200001, prepared.rootSkillId)
        assertEquals(200031, prepared.skillId)
        assertEquals(refB, delayed.source)
        assertEquals(200002, delayed.rootSkillId)
        assertEquals(200032, delayed.skillId)
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
    fun `schedule assigns runtime sequence and ignores caller sequence`() {
        val state = SkillRuntimeState()
        state.schedule(delayed(200001, dueRound = 2, dueHit = 0).copy(sequence = 99))
        state.schedule(delayed(200002, dueRound = 2, dueHit = 0).copy(sequence = 7))

        val due = state.dueEffects(round = 2)

        assertEquals(listOf(200001, 200002), due.map { it.skillId })
        assertEquals(listOf(0L, 1L), due.map { it.sequence })
    }

    @Test
    fun `call stack reports full root cycle path and remains unchanged`() {
        val state = SkillRuntimeState()
        state.enter(100)
        state.enter(1)
        state.enter(2)

        val failure = assertFailsWith<IllegalStateException> {
            state.enter(1)
        }

        assertEquals("Skill call cycle: 100 -> 1 -> 2 -> 1", failure.message)
        assertEquals(listOf(100, 1, 2), state.currentCallPath())
    }

    @Test
    fun `mismatched exit preserves call stack`() {
        val state = SkillRuntimeState()
        state.enter(100)
        state.enter(1)

        assertFailsWith<IllegalStateException> {
            state.exit(100)
        }

        assertEquals(listOf(100, 1), state.currentCallPath())
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
