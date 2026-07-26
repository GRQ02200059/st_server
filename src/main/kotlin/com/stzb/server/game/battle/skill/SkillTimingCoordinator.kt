package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef
import java.util.Collections

enum class PreparationCancelReason {
    CONFUSION,
    HESITATION,
    CLEANSE,
}

data class TimingAttemptOptions(
    val oncePerRound: Boolean = true,
    val preparationReductionRounds: Int = 0,
    val lockedTargets: List<BattleHeroRef>? = null,
)

data class SkillPreparationStartedEvent(
    val snapshot: SkillExecutionSnapshot,
) : SkillExecutionEvent {
    override val rootSkillId: Int
        get() = snapshot.rootSkillId
}

data class SkillPreparationCancelledEvent(
    val round: Int,
    val snapshot: SkillExecutionSnapshot,
    val reason: PreparationCancelReason,
) : SkillExecutionEvent {
    override val rootSkillId: Int
        get() = snapshot.rootSkillId
}

data class SkillAttemptRejectedChange(
    val source: BattleHeroRef,
    val skillId: Int,
    val trigger: BattleTrigger,
    val round: Int,
) : BattleStateChange

data class SkillPreparationRejectedChange(
    val snapshot: SkillExecutionSnapshot,
) : BattleStateChange

data class SkillPreparationCancelledChange(
    val round: Int,
    val snapshot: SkillExecutionSnapshot,
    val reason: PreparationCancelReason,
) : BattleStateChange

data class ScheduledTimingChange(
    val snapshot: DelayedEffect,
    val delayRound: Int,
    val delayHit: Int,
    val change: BattleStateChange,
) : BattleStateChange

data class TimingPosition(
    val round: Int,
    val hit: Int,
)

class CompleteTimingCoordinator(
    private val graph: SkillRuleGraph,
    private val interpreter: SkillRuleInterpreter,
    private val runtime: SkillRuntimeState,
    private val diagnosticSink: (SkillExecutionDiagnostic) -> Unit = {},
) {
    private val scheduledChanges = mutableMapOf<Long, BattleStateChange>()
    private var currentRound: Int = 0
    private var currentHit: Int = 0

    fun position(): TimingPosition = TimingPosition(currentRound, currentHit)

    fun attempt(
        skillId: Int,
        context: SkillBattleContext,
        options: TimingAttemptOptions = TimingAttemptOptions(),
    ): SkillExecutionResult {
        val timingContext = context.copy(runtime = runtime)
        val rule = graph.rule(skillId) ?: return diagnostic(
            context = timingContext,
            skillId = skillId,
            reason = "Missing timing rule for skill=$skillId",
        )
        if (options.preparationReductionRounds < 0) {
            return diagnostic(
                context = timingContext,
                skillId = skillId,
                reason = "preparationReductionRounds must not be negative: " +
                    options.preparationReductionRounds,
            )
        }
        if (runtime.isPreparing(timingContext.source, skillId)) {
            return result(
                changes = listOf(
                    SkillPreparationRejectedChange(
                        PreparedSkill(
                            source = timingContext.source,
                            rootSkillId = timingContext.rootSkillId.takeIf { it != 0 } ?: skillId,
                            skillId = skillId,
                            trigger = timingContext.trigger,
                            startedRound = timingContext.round,
                            readyRound = timingContext.round +
                                (rule.prepareRounds - options.preparationReductionRounds).coerceAtLeast(0),
                            lockedTargets = options.lockedTargets,
                        ),
                    ),
                ),
            )
        }
        if (!runtime.recordAttempt(
                source = timingContext.source,
                trigger = timingContext.trigger,
                skillId = skillId,
                round = timingContext.round,
                oncePerRound = options.oncePerRound,
            )
        ) {
            return result(
                changes = listOf(
                    SkillAttemptRejectedChange(
                        timingContext.source,
                        skillId,
                        timingContext.trigger,
                        timingContext.round,
                    ),
                ),
            )
        }
        if (!interpreter.probabilitySucceeds(skillId, timingContext.trigger, timingContext)) {
            return SkillExecutionResult.EMPTY
        }
        runtime.recordSuccessfulExecution(timingContext.source, timingContext.trigger, skillId)
        val effectivePreparation = (rule.prepareRounds - options.preparationReductionRounds)
            .coerceAtLeast(0)
        val snapshot = PreparedSkill(
            source = timingContext.source,
            rootSkillId = timingContext.rootSkillId.takeIf { it != 0 } ?: skillId,
            skillId = skillId,
            trigger = timingContext.trigger,
            startedRound = timingContext.round,
            readyRound = timingContext.round + effectivePreparation,
            lockedTargets = options.lockedTargets?.let { Collections.unmodifiableList(it.toList()) },
        )
        if (effectivePreparation == 0) {
            return interpreter.executeAccepted(snapshot, timingContext)
        }
        if (!runtime.prepare(snapshot)) {
            return result(changes = listOf(SkillPreparationRejectedChange(snapshot)))
        }
        return result(
            events = listOf(
                SkillPreparationStartedEvent(snapshot),
                BattleOutputEvent(
                    snapshot.rootSkillId,
                    com.stzb.server.game.battle.BattleEvent.SkillPreparationStarted(
                        round = snapshot.startedRound,
                        source = snapshot.source,
                        skillId = snapshot.skillId,
                        readyRound = snapshot.readyRound,
                    ),
                ),
            ),
        )
    }

    fun onRound(context: SkillBattleContext): SkillExecutionResult {
        if (context.round <= 0) {
            return diagnostic(context.copy(runtime = runtime), context.currentSkillId, "round must be positive")
        }
        currentRound = context.round
        currentHit = 0
        var aggregate = drain(context, currentRound, currentHit)
        runtime.duePreparations(currentRound).forEach { snapshot ->
            aggregate += interpreter.executeAccepted(
                snapshot,
                context.copy(
                    runtime = runtime,
                    round = currentRound,
                    source = snapshot.source,
                    rootSkillId = snapshot.rootSkillId,
                    currentSkillId = snapshot.skillId,
                    trigger = snapshot.trigger,
                ),
            )
        }
        return aggregate
    }

    fun onHit(context: SkillBattleContext): SkillExecutionResult {
        if (context.round <= 0) {
            return diagnostic(context.copy(runtime = runtime), context.currentSkillId, "round must be positive")
        }
        if (currentRound != context.round) {
            currentRound = context.round
            currentHit = 0
        }
        currentHit += 1
        return drain(context, currentRound, currentHit)
    }

    fun enqueue(
        change: BattleStateChange,
        currentRound: Int,
        currentHit: Int,
    ): SkillExecutionResult {
        val timing = timingOf(change) ?: return diagnostic(
            trigger = BattleTrigger.ACTION_AFTER,
            skillId = skillIdOf(change),
            detailId = detailIdOf(change),
            reason = "Unsupported scheduled change=${change::class.simpleName}",
        )
        val (delayRound, delayHit, snapshot) = timing
        if (currentRound <= 0 || currentHit < 0 || delayRound < 0 || delayHit < 0 ||
            delayRound == 0 && delayHit == 0
        ) {
            return diagnostic(
                trigger = BattleTrigger.ACTION_AFTER,
                skillId = snapshot.skillId,
                detailId = snapshot.detailId,
                reason = "Invalid timing: currentRound=$currentRound currentHit=$currentHit " +
                    "delayRound=$delayRound delayHit=$delayHit",
            )
        }
        val dueRound = currentRound + delayRound
        val dueHit = if (delayRound == 0) currentHit + delayHit else delayHit
        val scheduled = runtime.schedule(
            snapshot.copy(dueRound = dueRound, dueHit = dueHit),
        )
        scheduledChanges[scheduled.sequence] = change
        return SkillExecutionResult.EMPTY
    }

    fun activate(change: BattleStateChange, round: Int): SkillExecutionResult =
        when (change) {
            is ScheduledEffectActivationChange -> {
                val activationChanges = change.activationChanges()
                var aggregate = result(
                    changes = activationChanges.filterNot { it is CancelPreparedSkillsChange },
                    events = change.activationEvent(round)?.let {
                        listOf(BattleOutputEvent(change.spec.rootSkillId, it))
                    }.orEmpty(),
                )
                if (activationChanges.any { it is CancelPreparedSkillsChange }) {
                    aggregate += cancelPreparations(
                        change.spec.target,
                        round,
                        cancelReason(change.spec.effectId),
                    )
                }
                aggregate
            }
            is CancelPreparedSkillsChange ->
                cancelPreparations(change.spec.target, round, cancelReason(change.spec.effectId))
            is ScheduledTimingChange -> result(changes = listOf(change.change))
            is ScheduledDamageEffectChange,
            is ScheduledRecoveryEffectChange,
            -> result(changes = listOf(change))
            else -> result(changes = listOf(change))
        }

    fun cancelPreparations(
        source: BattleHeroRef,
        round: Int,
        reason: PreparationCancelReason,
    ): SkillExecutionResult {
        val removed = runtime.interruptPreparations(source)
        return result(
            changes = removed.map { SkillPreparationCancelledChange(round, it, reason) },
            events = removed.map { SkillPreparationCancelledEvent(round, it, reason) },
        )
    }

    private fun drain(
        context: SkillBattleContext,
        round: Int,
        hit: Int,
    ): SkillExecutionResult =
        runtime.dueEffects(round, hit).fold(SkillExecutionResult.EMPTY) { aggregate, delayed ->
            val change = scheduledChanges.remove(delayed.sequence)
            if (change == null) {
                aggregate + diagnostic(
                    trigger = context.trigger,
                    skillId = delayed.skillId,
                    detailId = delayed.detailId,
                    reason = "Missing scheduled payload for sequence=${delayed.sequence}",
                )
            } else {
                aggregate + activate(change, round)
            }
        }

    private fun timingOf(change: BattleStateChange): Triple<Int, Int, DelayedEffect>? =
        when (change) {
            is ScheduledTimingChange -> Triple(change.delayRound, change.delayHit, change.snapshot)
            is ScheduledEffectActivationChange -> change.spec.timing()
            is ScheduledDamageEffectChange -> change.spec.timing()
            is ScheduledRecoveryEffectChange -> change.spec.timing()
            else -> null
        }

    private fun PersistentEffectSpec.timing() =
        Triple(
            delayRound,
            delayHit,
            DelayedEffect(source, rootSkillId, skillId, detailId, dueRound = 0),
        )

    private fun skillIdOf(change: BattleStateChange): Int =
        (change as? ScheduledTimingChange)?.snapshot?.skillId ?: 0

    private fun detailIdOf(change: BattleStateChange): Int? =
        (change as? ScheduledTimingChange)?.snapshot?.detailId

    private fun diagnostic(
        context: SkillBattleContext,
        skillId: Int,
        reason: String,
    ): SkillExecutionResult =
        diagnostic(context.trigger, skillId, null, reason)

    private fun diagnostic(
        trigger: BattleTrigger,
        skillId: Int,
        detailId: Int?,
        reason: String,
    ): SkillExecutionResult {
        val diagnostic = SkillExecutionDiagnostic(
            code = "INVALID_TIMING",
            skillId = skillId,
            detailId = detailId,
            effectId = null,
            trigger = trigger,
            fullPath = detailId?.let { listOf(SkillExecutionFrame(skillId, it)) }.orEmpty(),
            reason = reason,
        )
        try {
            diagnosticSink(diagnostic)
        } catch (_: Exception) {
            // Timing diagnostics remain safe even when a reporting sink fails.
        }
        return result(diagnostics = listOf(diagnostic))
    }

    private fun cancelReason(effectId: Int): PreparationCancelReason =
        if (effectId in HESITATION_IDS) PreparationCancelReason.HESITATION
        else PreparationCancelReason.CONFUSION

    private fun result(
        changes: List<BattleStateChange> = emptyList(),
        events: List<SkillExecutionEvent> = emptyList(),
        diagnostics: List<SkillExecutionDiagnostic> = emptyList(),
    ): SkillExecutionResult =
        SkillExecutionResult.immutable(changes, events, emptyList(), diagnostics)

    private companion object {
        val HESITATION_IDS = setOf(502, 702, 902)
    }
}
