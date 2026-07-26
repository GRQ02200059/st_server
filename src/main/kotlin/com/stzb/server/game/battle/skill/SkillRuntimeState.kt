package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef

data class SkillExecutionFrame(
    val skillId: Int,
    val detailId: Int,
) {
    override fun toString(): String = "$skillId/$detailId"
}

class SkillRuntimeState {
    private val counts = mutableMapOf<RuntimeKey, Int>()
    private val triggerCounts = mutableMapOf<TriggerKey, Int>()
    private val preparations = mutableListOf<PreparedSkill>()
    private val delayedEffects = mutableListOf<DelayedEffect>()
    private val callStack = ArrayDeque<Int>()
    private val detailCallStack = ArrayDeque<SkillExecutionFrame>()
    private var nextSequence = 0L

    fun count(source: BattleHeroRef, trigger: BattleTrigger, skillId: Int): Int =
        counts[RuntimeKey(source, trigger, skillId)] ?: 0

    fun increment(source: BattleHeroRef, trigger: BattleTrigger, skillId: Int): Int {
        val key = RuntimeKey(source, trigger, skillId)
        val updated = count(source, trigger, skillId) + 1
        counts[key] = updated
        return updated
    }

    fun count(source: BattleHeroRef, trigger: BattleTrigger): Int =
        triggerCounts[TriggerKey(source, trigger)] ?: 0

    fun recordTrigger(source: BattleHeroRef, trigger: BattleTrigger): Int {
        val key = TriggerKey(source, trigger)
        val updated = count(source, trigger) + 1
        triggerCounts[key] = updated
        return updated
    }

    fun recordSuccessfulExecution(
        source: BattleHeroRef,
        trigger: BattleTrigger,
        skillId: Int,
    ): Int {
        recordTrigger(source, trigger)
        return increment(source, trigger, skillId)
    }

    fun prepare(skill: PreparedSkill): Boolean {
        if (preparations.any { it.source == skill.source && it.skillId == skill.skillId }) {
            return false
        }
        preparations += skill
        return true
    }

    fun preparedSkills(): List<PreparedSkill> = preparations.toList()

    fun interruptPreparations(source: BattleHeroRef) {
        preparations.removeAll { it.source == source }
    }

    fun schedule(effect: DelayedEffect) {
        delayedEffects += effect.copy(sequence = nextSequence++)
    }

    fun dueEffects(round: Int, hit: Int = 0): List<DelayedEffect> {
        val due = delayedEffects
            .filter { it.dueRound < round || it.dueRound == round && it.dueHit <= hit }
            .sortedWith(compareBy(DelayedEffect::dueRound, DelayedEffect::dueHit, DelayedEffect::sequence))
        delayedEffects.removeAll(due.toSet())
        return due
    }

    fun enter(skillId: Int) {
        val path = callStack.toList()
        check(skillId !in path) {
            "Skill call cycle: ${(path + skillId).joinToString(" -> ")}"
        }
        check(callStack.size < MAX_CHILD_DEPTH) {
            "Skill call path exceeds maximum child depth $MAX_CHILD_DEPTH: " +
                (path + skillId).joinToString(" -> ")
        }
        callStack.addLast(skillId)
    }

    fun exit(skillId: Int) {
        check(callStack.lastOrNull() == skillId) {
            "Cannot exit skill $skillId from call path ${callStack.joinToString(" -> ")}"
        }
        callStack.removeLast()
    }

    fun currentCallPath(): List<Int> = callStack.toList()

    fun enterDetail(frame: SkillExecutionFrame) {
        val path = detailCallStack.toList()
        check(frame !in path) {
            "Skill detail call cycle: ${(path + frame).joinToString(" -> ")}"
        }
        check(detailCallStack.size < MAX_REFERENCE_DEPTH) {
            "Skill detail path exceeds maximum reference depth $MAX_REFERENCE_DEPTH: " +
                (path + frame).joinToString(" -> ")
        }
        detailCallStack.addLast(frame)
    }

    fun exitDetail(frame: SkillExecutionFrame) {
        check(detailCallStack.lastOrNull() == frame) {
            "Cannot exit detail $frame from call path ${detailCallStack.joinToString(" -> ")}"
        }
        detailCallStack.removeLast()
    }

    fun currentDetailPath(): List<SkillExecutionFrame> = detailCallStack.toList()

    private data class RuntimeKey(
        val source: BattleHeroRef,
        val trigger: BattleTrigger,
        val skillId: Int,
    )

    private data class TriggerKey(
        val source: BattleHeroRef,
        val trigger: BattleTrigger,
    )

    companion object {
        const val MAX_CHILD_DEPTH = 16
        const val MAX_REFERENCE_DEPTH = 16
    }
}
