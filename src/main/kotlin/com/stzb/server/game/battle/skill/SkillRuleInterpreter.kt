package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.SkillKind
import java.util.Collections

data class SkillTriggered(
    val round: Int,
    val source: BattleHeroRef,
    override val rootSkillId: Int,
    val skillId: Int,
    val trigger: BattleTrigger,
) : SkillExecutionEvent

sealed interface SkillExecutionEvent {
    val rootSkillId: Int
}

data class BattleOutputEvent(
    override val rootSkillId: Int,
    val event: BattleEvent,
) : SkillExecutionEvent

data class SkillExecutionDiagnostic(
    val code: String,
    val skillId: Int,
    val detailId: Int?,
    val effectId: Int?,
    val trigger: BattleTrigger,
    val fullPath: List<SkillExecutionFrame>,
    private val skillDependencyPath: List<Int> = fullPath.map(SkillExecutionFrame::skillId),
    val reason: String,
) {
    val dependencyPath: List<Int>
        get() = skillDependencyPath
}

data class SkillExecutionResult(
    val stateChanges: List<BattleStateChange>,
    val events: List<SkillExecutionEvent>,
    val executedSkillIds: List<Int>,
    val diagnostics: List<SkillExecutionDiagnostic>,
    val timingDues: List<SkillTimingDue>,
) {
    operator fun plus(other: SkillExecutionResult): SkillExecutionResult {
        if (this === EMPTY) return other
        if (other === EMPTY) return this
        return immutable(
            stateChanges + other.stateChanges,
            events + other.events,
            executedSkillIds + other.executedSkillIds,
            diagnostics + other.diagnostics,
            timingDues + other.timingDues,
        )
    }

    companion object {
        val EMPTY: SkillExecutionResult =
            immutable(emptyList(), emptyList(), emptyList(), emptyList())

        internal fun immutable(
            stateChanges: Collection<BattleStateChange>,
            events: Collection<SkillExecutionEvent>,
            executedSkillIds: Collection<Int>,
            diagnostics: Collection<SkillExecutionDiagnostic>,
            timingDues: Collection<SkillTimingDue> = emptyList(),
        ): SkillExecutionResult =
            SkillExecutionResult(
                Collections.unmodifiableList(ArrayList(stateChanges)),
                Collections.unmodifiableList(ArrayList(events)),
                Collections.unmodifiableList(ArrayList(executedSkillIds)),
                Collections.unmodifiableList(ArrayList(diagnostics)),
                Collections.unmodifiableList(ArrayList(timingDues)),
            )
    }
}

fun interface PendingSkillConditionInterpreter {
    fun matches(
        rule: SkillEffectRule,
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): Boolean
}

class StrictPendingConditionInterpreter : PendingSkillConditionInterpreter {
    override fun matches(
        rule: SkillEffectRule,
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): Boolean {
        val raw = rule.raw
        val pending = listOf(
            "cast_condition" to raw.castCondition,
            "precondition" to raw.precondition,
            "condition" to raw.condition,
        ).filter { it.second != 0 }
        if (pending.isNotEmpty()) {
            throw UnsupportedPendingSkillConditionException(
                "Pending condition semantics: skill=${context.currentSkillId} " +
                    "detail=${rule.detailId} trigger=$trigger " +
                    pending.joinToString { "${it.first}=${it.second}" },
            )
        }
        return true
    }
}

class UnsupportedPendingSkillConditionException(message: String) :
    IllegalStateException(message)

class MissingSkillRuleException(
    val dependencyPath: List<Int>,
) : IllegalArgumentException(
    "Missing skill rule: ${dependencyPath.joinToString(" -> ")}",
)

class MissingSkillDetailException(
    val dependencyPath: List<Int>,
    val detailId: Int,
) : IllegalArgumentException(
    "Missing referenced detail=$detailId: ${dependencyPath.joinToString(" -> ")}",
)

class SkillRecursionException(
    val dependencyPath: List<Int>,
    reason: String,
) : IllegalStateException(
    "$reason: ${dependencyPath.joinToString(" -> ")}",
)

class SkillDetailRecursionException(
    val fullPath: List<SkillExecutionFrame>,
    reason: String,
) : IllegalStateException(
    "$reason: ${fullPath.joinToString(" -> ")}",
)

private enum class InterpreterFailureMode {
    STRICT,
    SAFE,
}

class SkillRuleInterpreter private constructor(
    private val graph: SkillRuleGraph,
    private val registry: BattleEffectRegistry,
    private val conditionInterpreter: PendingSkillConditionInterpreter,
    private val failureMode: InterpreterFailureMode,
    private val diagnosticSink: (SkillExecutionDiagnostic) -> Unit,
) {
    constructor(
        graph: SkillRuleGraph,
        registry: BattleEffectRegistry,
        conditionInterpreter: PendingSkillConditionInterpreter = SkillConditionInterpreter(graph),
    ) : this(
        graph,
        registry,
        conditionInterpreter,
        InterpreterFailureMode.STRICT,
        {},
    )

    fun execute(
        skillId: Int,
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        executeSkill(skillId, trigger, context, ChildProbabilityOwnership.CONFIGURED_CHILD)

    internal fun probabilitySucceeds(
        skillId: Int,
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): Boolean {
        val rule = graph.rule(skillId) ?: throw MissingSkillRuleException(
            context.runtime.currentCallPath() + skillId,
        )
        if (!triggerMatches(rule.kind, trigger)) return false
        return rollProbability(rule, context.copy(currentSkillId = skillId, trigger = trigger))
    }

    internal fun executeAccepted(
        snapshot: SkillExecutionSnapshot,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        executeSkill(
            skillId = snapshot.skillId,
            trigger = snapshot.trigger,
            parentContext = context.copy(
                source = snapshot.source,
                rootSkillId = snapshot.rootSkillId,
                currentSkillId = snapshot.skillId,
                trigger = snapshot.trigger,
            ),
            probabilityOwnership = ChildProbabilityOwnership.FORCED_SUCCESS,
            recordSuccessfulExecution = false,
            rootPreselectedTargets = snapshot.lockedTargets,
        )

    internal fun executeDetailForEngine(
        detail: SkillEffectRule,
        context: SkillBattleContext,
        preselectedTargets: List<BattleHeroRef>? = null,
        valueOverride: TypedBattlePotency.Resolved? = null,
    ): SkillExecutionResult =
        executeDetail(detail, context, preselectedTargets, valueOverride)

    private fun executeSkill(
        skillId: Int,
        trigger: BattleTrigger,
        parentContext: SkillBattleContext,
        probabilityOwnership: ChildProbabilityOwnership,
        recordSuccessfulExecution: Boolean = true,
        rootPreselectedTargets: List<BattleHeroRef>? = null,
    ): SkillExecutionResult {
        val attemptedPath = parentContext.runtime.currentCallPath() + skillId
        val rule = graph.rule(skillId) ?: throw MissingSkillRuleException(attemptedPath)
        if (!triggerMatches(rule.kind, trigger)) return SkillExecutionResult.EMPTY
        try {
            parentContext.runtime.enter(skillId)
        } catch (error: IllegalStateException) {
            throw SkillRecursionException(attemptedPath, error.message ?: "Skill recursion failure")
        }
        try {
            val context = parentContext.copy(
                rootSkillId = if (parentContext.runtime.currentCallPath().size == 1) {
                    parentContext.rootSkillId.takeIf { it != 0 } ?: skillId
                } else {
                    parentContext.rootSkillId
                },
                currentSkillId = skillId,
                trigger = trigger,
            )
            if (probabilityOwnership == ChildProbabilityOwnership.CONFIGURED_CHILD &&
                !rollProbability(rule, context)
            ) {
                return SkillExecutionResult.EMPTY
            }
            if (recordSuccessfulExecution) {
                parentContext.runtime.recordSuccessfulExecution(context.source, trigger, skillId)
            }

            var result = SkillExecutionResult.immutable(
                stateChanges = emptyList(),
                events = listOf(
                    SkillTriggered(
                        round = context.round,
                        source = context.source,
                        rootSkillId = context.rootSkillId,
                        skillId = skillId,
                        trigger = trigger,
                    ),
                ),
                executedSkillIds = listOf(skillId),
                diagnostics = emptyList(),
            )
            rule.details.forEach { detail ->
                result += executeBranch(detail, context, rootPreselectedTargets)
            }
            return result
        } finally {
            parentContext.runtime.exit(skillId)
        }
    }

    private fun executeBranch(
        detail: SkillEffectRule,
        context: SkillBattleContext,
        preselectedTargets: List<BattleHeroRef>? = null,
    ): SkillExecutionResult =
        try {
            if (!conditionInterpreter.matches(detail, context.trigger, context)) {
                SkillExecutionResult.EMPTY
            } else {
                executeDetail(detail, context, preselectedTargets)
            }
        } catch (error: Exception) {
            if (failureMode == InterpreterFailureMode.STRICT || !isRecoverable(error)) throw error
            diagnosticResult(detail, context, error)
        }

    private fun executeDetail(
        detail: SkillEffectRule,
        context: SkillBattleContext,
        preselectedTargets: List<BattleHeroRef>? = null,
        valueOverride: TypedBattlePotency.Resolved? = null,
    ): SkillExecutionResult {
        val ownerSkillId = detail.detailId / 100
        val frame = SkillExecutionFrame(ownerSkillId, detail.detailId)
        val attempted = context.runtime.currentDetailPath() + frame
        try {
            context.runtime.enterDetail(frame)
        } catch (error: IllegalStateException) {
            throw SkillDetailRecursionException(
                attempted,
                error.message ?: "Skill detail recursion failure",
            )
        }
        try {
            val executionContext = context.copy(currentSkillId = ownerSkillId)
            val execution = registry.execute(
                rule = detail,
                context = executionContext,
                preselectedTargets = preselectedTargets,
                valueOverride = valueOverride,
            )
            var result = SkillExecutionResult.immutable(
                stateChanges = execution.stateChanges,
                events = execution.events.map { BattleOutputEvent(context.rootSkillId, it) },
                executedSkillIds = emptyList(),
                diagnostics = emptyList(),
            )
            execution.stateChanges.forEach { change ->
                result += when (change) {
                    is ExecuteChildSkillChange -> executeChildren(change, executionContext)
                    is RetriggerSkillChange -> retrigger(change, executionContext)
                    is TriggerReferencedEffectChange -> triggerReferencedEffect(change, executionContext)
                    else -> SkillExecutionResult.EMPTY
                }
            }
            return result
        } finally {
            context.runtime.exitDetail(frame)
        }
    }

    private fun executeChildren(
        change: ExecuteChildSkillChange,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        change.childSkillIds.fold(SkillExecutionResult.EMPTY) { aggregate, childSkillId ->
            val child = graph.rule(childSkillId) ?: throw MissingSkillRuleException(
                context.runtime.currentCallPath() + childSkillId,
            )
            aggregate + if (change.valueOverride == null && change.inheritedPreselectedTargets == null) {
                executeSkill(
                    skillId = childSkillId,
                    trigger = triggerFor(child.kind),
                    parentContext = context,
                    probabilityOwnership = change.probabilityOwnership,
                )
            } else {
                executeChildWithOverrides(child, change, context)
            }
        }

    private fun executeChildWithOverrides(
        child: SkillRule,
        change: ExecuteChildSkillChange,
        parentContext: SkillBattleContext,
    ): SkillExecutionResult {
        val trigger = triggerFor(child.kind)
        val attemptedPath = parentContext.runtime.currentCallPath() + child.skillId
        try {
            parentContext.runtime.enter(child.skillId)
        } catch (error: IllegalStateException) {
            throw SkillRecursionException(attemptedPath, error.message ?: "Skill recursion failure")
        }
        try {
            val context = parentContext.copy(
                currentSkillId = child.skillId,
                trigger = trigger,
            )
            if (change.probabilityOwnership == ChildProbabilityOwnership.CONFIGURED_CHILD &&
                !rollProbability(child, context)
            ) {
                return SkillExecutionResult.EMPTY
            }
            parentContext.runtime.recordSuccessfulExecution(context.source, trigger, child.skillId)
            var result = SkillExecutionResult.immutable(
                stateChanges = emptyList(),
                events = listOf(
                    SkillTriggered(
                        round = context.round,
                        source = context.source,
                        rootSkillId = context.rootSkillId,
                        skillId = child.skillId,
                        trigger = trigger,
                    ),
                ),
                executedSkillIds = listOf(child.skillId),
                diagnostics = emptyList(),
            )
            child.details.forEach { detail ->
                result += try {
                    if (conditionInterpreter.matches(detail, trigger, context)) {
                        executeDetail(
                            detail = detail,
                            context = context,
                            preselectedTargets = change.inheritedPreselectedTargets,
                            valueOverride = change.valueOverride,
                        )
                    } else {
                        SkillExecutionResult.EMPTY
                    }
                } catch (error: Exception) {
                    if (failureMode == InterpreterFailureMode.STRICT || !isRecoverable(error)) throw error
                    diagnosticResult(detail, context, error)
                }
            }
            return result
        } finally {
            parentContext.runtime.exit(child.skillId)
        }
    }

    private fun retrigger(
        change: RetriggerSkillChange,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val maximum = change.maximumExecutions
        var result = SkillExecutionResult.EMPTY
        change.selectedTargets.forEach { target ->
            val targetHero = requestHero(context, target)
            targetHero.skillIds
                .asSequence()
                .filter { graph.rule(it)?.kind == change.skillKind }
                .forEach { skillId ->
                    val trigger = triggerFor(change.skillKind)
                    if (maximum == null || context.runtime.count(target, trigger, skillId) < maximum) {
                        result += executeSkill(
                            skillId = skillId,
                            trigger = trigger,
                            parentContext = context.copy(source = target),
                            probabilityOwnership = change.probabilityOwnership,
                        )
                    }
                }
        }
        return result
    }

    private fun triggerReferencedEffect(
        change: TriggerReferencedEffectChange,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val detail = graph.details.singleOrNull { it.detailId == change.referencedDetailId }
            ?: throw MissingSkillDetailException(
                context.runtime.currentCallPath(),
                change.referencedDetailId,
            )
        return executeDetail(
            detail = detail,
            context = context,
            preselectedTargets = change.selectedTargets,
            valueOverride = change.valueOverride,
        )
    }

    private fun diagnosticResult(
        detail: SkillEffectRule,
        context: SkillBattleContext,
        error: Exception,
    ): SkillExecutionResult {
        val fullPath = when (error) {
            is SkillDetailRecursionException -> error.fullPath
            else -> if (context.runtime.currentDetailPath().isEmpty()) {
                listOf(SkillExecutionFrame(context.currentSkillId, detail.detailId))
            } else {
                context.runtime.currentDetailPath()
            }
        }
        val diagnostic = SkillExecutionDiagnostic(
            code = when (error) {
                is MissingSkillDetailException -> "MISSING_REFERENCED_DETAIL"
                is MissingSkillRuleException -> "MISSING_CHILD_SKILL"
                is SkillRecursionException -> "SKILL_RECURSION"
                is SkillDetailRecursionException -> "DETAIL_RECURSION"
                is UnsupportedPendingSkillConditionException -> "UNSUPPORTED_CONDITION"
                is UnsupportedSkillRuleException -> error.diagnostic.code.name
                is UnsupportedConfiguredBattleValueException -> error.diagnostic.code.name
                else -> "INVALID_RULE"
            },
            skillId = context.currentSkillId,
            detailId = detail.detailId,
            effectId = detail.effectId,
            trigger = context.trigger,
            fullPath = Collections.unmodifiableList(ArrayList(fullPath)),
            skillDependencyPath = Collections.unmodifiableList(
                ArrayList(
                    when (error) {
                        is MissingSkillRuleException -> error.dependencyPath
                        is SkillRecursionException -> error.dependencyPath
                        else -> fullPath.map(SkillExecutionFrame::skillId)
                    },
                ),
            ),
            reason = error.message.orEmpty(),
        )
        logSafely(diagnostic)
        return SkillExecutionResult.immutable(
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(diagnostic),
        )
    }

    private fun isRecoverable(error: Exception): Boolean =
        error is MissingSkillDetailException ||
            error is MissingSkillRuleException ||
            error is SkillRecursionException ||
            error is SkillDetailRecursionException ||
            error is UnsupportedPendingSkillConditionException ||
            error is UnsupportedSkillRuleException ||
            error is UnsupportedConfiguredBattleValueException ||
            error is IllegalArgumentException ||
            error is IllegalStateException

    private fun logSafely(diagnostic: SkillExecutionDiagnostic) {
        try {
            diagnosticSink(diagnostic)
        } catch (_: Exception) {
            // Safe-mode diagnostics never replace branch execution.
        }
    }

    private fun rollProbability(
        rule: SkillRule,
        context: SkillBattleContext,
    ): Boolean {
        val source = requestHero(context, context.source)
        val morale =
            if (SkillBattleViewCapability.LIVE_MORALE in context.battleView.capabilities) {
                context.battleView.currentMorale(context.source) ?: source.morale
            } else {
                source.morale
            }
        val moraleAddition = (morale - 100).toDouble() / (100 + 0.5 * morale)
        val moraleAdjusted = (rule.probability * (1 + moraleAddition)).toInt()
        val modifier = source.modifiers
            .filterIsInstance<BattleModifier.SkillProbabilityPercent>()
            .sumOf { it.percent }
        val probability = (moraleAdjusted + modifier).coerceIn(0, 100)
        return context.random.nextInt(100) < probability
    }

    private fun requestHero(
        context: SkillBattleContext,
        ref: BattleHeroRef,
    ): BattleHero {
        val team = if (ref.side == com.stzb.server.game.battle.Side.ATTACKER) {
            context.request.attacker
        } else {
            context.request.defender
        }
        return team.heroes.single { it.id == ref.heroId && it.position == ref.position }
    }

    private fun triggerMatches(kind: SkillKind, trigger: BattleTrigger): Boolean =
        triggerFor(kind) == trigger

    private fun triggerFor(kind: SkillKind): BattleTrigger =
        when (kind) {
            SkillKind.PASSIVE -> BattleTrigger.BATTLE_PASSIVE
            SkillKind.COMMAND -> BattleTrigger.BATTLE_COMMAND
            SkillKind.ACTIVE -> BattleTrigger.ACTIVE_SKILL_ATTEMPT
            SkillKind.PURSUIT -> BattleTrigger.PURSUIT_ATTEMPT
            SkillKind.UNKNOWN -> throw IllegalArgumentException("Unsupported skill kind=$kind")
        }

    companion object {
        fun safe(
            graph: SkillRuleGraph,
            registry: BattleEffectRegistry,
            conditionInterpreter: PendingSkillConditionInterpreter = SkillConditionInterpreter(graph),
            diagnosticSink: (SkillExecutionDiagnostic) -> Unit,
        ): SkillRuleInterpreter =
            SkillRuleInterpreter(
                graph,
                registry,
                conditionInterpreter,
                InterpreterFailureMode.SAFE,
                diagnosticSink,
            )
    }
}
