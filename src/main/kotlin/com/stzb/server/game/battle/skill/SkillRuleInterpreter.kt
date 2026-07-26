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
    val dependencyPath: List<Int>,
    val reason: String,
)

data class SkillExecutionResult(
    val stateChanges: List<BattleStateChange>,
    val events: List<SkillExecutionEvent>,
    val executedSkillIds: List<Int>,
    val diagnostics: List<SkillExecutionDiagnostic>,
) {
    operator fun plus(other: SkillExecutionResult): SkillExecutionResult {
        if (this === EMPTY) return other
        if (other === EMPTY) return this
        return immutable(
            stateChanges + other.stateChanges,
            events + other.events,
            executedSkillIds + other.executedSkillIds,
            diagnostics + other.diagnostics,
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
        ): SkillExecutionResult =
            SkillExecutionResult(
                Collections.unmodifiableList(ArrayList(stateChanges)),
                Collections.unmodifiableList(ArrayList(events)),
                Collections.unmodifiableList(ArrayList(executedSkillIds)),
                Collections.unmodifiableList(ArrayList(diagnostics)),
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

class SkillRuleInterpreter(
    private val graph: SkillRuleGraph,
    private val registry: BattleEffectRegistry,
    private val conditionInterpreter: PendingSkillConditionInterpreter =
        StrictPendingConditionInterpreter(),
) {
    fun execute(
        skillId: Int,
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        executeSkill(skillId, trigger, context, probabilityResolved = false)

    private fun executeSkill(
        skillId: Int,
        trigger: BattleTrigger,
        parentContext: SkillBattleContext,
        probabilityResolved: Boolean,
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
            parentContext.runtime.increment(context.source, trigger, skillId)
            if (!probabilityResolved && !rollProbability(rule, context)) {
                return SkillExecutionResult.EMPTY
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
                if (conditionInterpreter.matches(detail, trigger, context)) {
                    result += executeDetail(detail, context)
                }
            }
            return result
        } finally {
            parentContext.runtime.exit(skillId)
        }
    }

    private fun executeDetail(
        detail: SkillEffectRule,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val execution = registry.execute(detail, context)
        var result = SkillExecutionResult.immutable(
            stateChanges = execution.stateChanges,
            events = execution.events.map { BattleOutputEvent(context.rootSkillId, it) },
            executedSkillIds = emptyList(),
            diagnostics = emptyList(),
        )
        execution.stateChanges.forEach { change ->
            result += when (change) {
                is ExecuteChildSkillChange -> executeChildren(change, context)
                is RetriggerSkillChange -> retrigger(change, context)
                is TriggerReferencedEffectChange -> triggerReferencedEffect(change, context)
                else -> SkillExecutionResult.EMPTY
            }
        }
        return result
    }

    private fun executeChildren(
        change: ExecuteChildSkillChange,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        change.childSkillIds.fold(SkillExecutionResult.EMPTY) { aggregate, childSkillId ->
            val child = graph.rule(childSkillId) ?: throw MissingSkillRuleException(
                context.runtime.currentCallPath() + childSkillId,
            )
            aggregate + executeSkill(
                skillId = childSkillId,
                trigger = triggerFor(child.kind),
                parentContext = context,
                probabilityResolved = false,
            )
        }

    private fun retrigger(
        change: RetriggerSkillChange,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val maximum = change.maximumExecutions
        var result = SkillExecutionResult.EMPTY
        change.selectedTargets.forEach targetLoop@{ target ->
            val targetHero = requestHero(context, target)
            targetHero.skillIds
                .asSequence()
                .filter { graph.rule(it)?.kind == change.skillKind }
                .forEach { skillId ->
                    if (maximum != null &&
                        context.runtime.count(target, triggerFor(change.skillKind), skillId) >= maximum
                    ) {
                        return@targetLoop
                    }
                    result += executeSkill(
                        skillId = skillId,
                        trigger = triggerFor(change.skillKind),
                        parentContext = context.copy(source = target),
                        probabilityResolved = true,
                    )
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
        val execution = registry.execute(detail, context)
        var result = SkillExecutionResult.immutable(
            stateChanges = execution.stateChanges,
            events = execution.events.map { BattleOutputEvent(context.rootSkillId, it) },
            executedSkillIds = emptyList(),
            diagnostics = emptyList(),
        )
        execution.stateChanges.forEach { nested ->
            result += when (nested) {
                is ExecuteChildSkillChange -> executeChildren(nested, context)
                is RetriggerSkillChange -> retrigger(nested, context)
                is TriggerReferencedEffectChange -> triggerReferencedEffect(nested, context)
                else -> SkillExecutionResult.EMPTY
            }
        }
        return result
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
}
