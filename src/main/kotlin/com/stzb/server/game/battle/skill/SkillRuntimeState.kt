package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.Side

data class SkillExecutionFrame(
    val skillId: Int,
    val detailId: Int,
) {
    override fun toString(): String = "$skillId/$detailId"
}

class SkillRuntimeState {
    private val counts = mutableMapOf<RuntimeKey, Int>()
    private val attemptCounts = mutableMapOf<RuntimeKey, Int>()
    private val lastAttemptRounds = mutableMapOf<RuntimeKey, Int>()
    private val triggerCounts = mutableMapOf<TriggerKey, Int>()
    private val sideTriggerCounts = mutableMapOf<SideTriggerKey, Int>()
    private val consumedThresholdGenerations = mutableMapOf<ThresholdKey, Int>()
    private val markers = mutableMapOf<MarkerKey, MarkerValue>()
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

    fun attemptCount(source: BattleHeroRef, trigger: BattleTrigger, skillId: Int): Int =
        attemptCounts[RuntimeKey(source, trigger, skillId)] ?: 0

    fun recordAttempt(
        source: BattleHeroRef,
        trigger: BattleTrigger,
        skillId: Int,
        round: Int,
        oncePerRound: Boolean = true,
    ): Boolean {
        val key = RuntimeKey(source, trigger, skillId)
        if (oncePerRound && lastAttemptRounds[key] == round) return false
        lastAttemptRounds[key] = round
        attemptCounts[key] = attemptCount(source, trigger, skillId) + 1
        return true
    }

    internal fun suppressAttemptForRound(
        source: BattleHeroRef,
        trigger: BattleTrigger,
        skillId: Int,
        round: Int,
    ) {
        lastAttemptRounds[RuntimeKey(source, trigger, skillId)] = round
    }

    fun count(source: BattleHeroRef, trigger: BattleTrigger): Int =
        triggerCounts[TriggerKey(source, trigger)] ?: 0

    fun recordBattleTriggerOccurrence(source: BattleHeroRef, trigger: BattleTrigger): Int {
        val key = TriggerKey(source, trigger)
        val updated = count(source, trigger) + 1
        triggerCounts[key] = updated
        val sideKey = SideTriggerKey(source.side, trigger)
        sideTriggerCounts[sideKey] = sideCount(source.side, trigger) + 1
        return updated
    }

    fun sideCount(side: Side, trigger: BattleTrigger): Int =
        sideTriggerCounts[SideTriggerKey(side, trigger)] ?: 0

    fun consumeThreshold(
        owner: BattleHeroRef,
        namespace: String,
        count: Int,
        threshold: Int,
    ): Boolean {
        require(namespace.isNotBlank()) { "Threshold namespace must not be blank" }
        require(count >= 0) { "Threshold count must be non-negative: $count" }
        require(threshold > 0) { "Threshold must be positive: $threshold" }
        val generation = count / threshold
        if (generation <= 0) return false
        val key = ThresholdKey(owner, namespace, threshold)
        val consumed = consumedThresholdGenerations[key] ?: 0
        if (generation <= consumed) return false
        consumedThresholdGenerations[key] = generation
        return true
    }

    fun recordMarker(
        target: BattleHeroRef,
        detailId: Int,
        value: Int,
        appliedRound: Int,
        durationRounds: Int,
    ) {
        require(detailId > 0) { "Marker detail ID must be positive: $detailId" }
        require(appliedRound >= 0) { "Marker round must be non-negative: $appliedRound" }
        require(durationRounds >= 0) { "Marker duration must be non-negative: $durationRounds" }
        markers[MarkerKey(target, detailId)] = MarkerValue(
            value = value,
            expiresAtRound = appliedRound + durationRounds,
        )
    }

    fun hasMarker(target: BattleHeroRef, detailId: Int, round: Int): Boolean =
        markerValue(target, detailId, round) != null

    fun markerValue(target: BattleHeroRef, detailId: Int, round: Int): Int? {
        val key = MarkerKey(target, detailId)
        val marker = markers[key] ?: return null
        if (round >= marker.expiresAtRound) {
            markers.remove(key)
            return null
        }
        return marker.value
    }

    @Deprecated(
        message = "Use recordBattleTriggerOccurrence to make explicit that callers record battle events",
        replaceWith = ReplaceWith("recordBattleTriggerOccurrence(source, trigger)"),
    )
    fun recordTrigger(source: BattleHeroRef, trigger: BattleTrigger): Int =
        recordBattleTriggerOccurrence(source, trigger)

    fun recordSuccessfulExecution(
        source: BattleHeroRef,
        trigger: BattleTrigger,
        skillId: Int,
    ): Int {
        recordBattleTriggerOccurrence(source, trigger)
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

    fun isPreparing(source: BattleHeroRef, skillId: Int): Boolean =
        preparations.any { it.source == source && it.skillId == skillId }

    fun interruptPreparations(source: BattleHeroRef): List<PreparedSkill> {
        val removed = preparations.filter { it.source == source }
        preparations.removeAll(removed.toSet())
        return removed
    }

    fun duePreparations(
        round: Int,
        source: BattleHeroRef? = null,
    ): List<PreparedSkill> {
        val due = preparations.filter {
            it.readyRound <= round && (source == null || it.source == source)
        }
        preparations.removeAll(due.toSet())
        return due
    }

    fun schedule(effect: DelayedEffect): DelayedEffect {
        val scheduled = effect.copy(sequence = nextSequence++)
        delayedEffects += scheduled
        return scheduled
    }

    fun delayedCount(): Int = delayedEffects.size

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

    private data class SideTriggerKey(
        val side: Side,
        val trigger: BattleTrigger,
    )

    private data class ThresholdKey(
        val owner: BattleHeroRef,
        val namespace: String,
        val threshold: Int,
    )

    private data class MarkerKey(
        val target: BattleHeroRef,
        val detailId: Int,
    )

    private data class MarkerValue(
        val value: Int,
        val expiresAtRound: Int,
    )

    companion object {
        const val MAX_CHILD_DEPTH = 16
        const val MAX_REFERENCE_DEPTH = 16
    }
}
