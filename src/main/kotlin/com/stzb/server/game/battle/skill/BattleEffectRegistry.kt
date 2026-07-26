package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEvent
import java.util.Collections

fun interface BattleEffectHandler {
    fun execute(invocation: EffectInvocation): EffectExecution
}

interface BattleStateChange

data class EffectInvocation(
    val rule: SkillEffectRule,
    val context: SkillBattleContext,
    val callPath: List<Int>,
)

data class EffectExecution(
    val stateChanges: List<BattleStateChange>,
    val events: List<BattleEvent>,
) {
    internal fun immutableCopy(): EffectExecution =
        EffectExecution(
            stateChanges = immutableList(stateChanges),
            events = immutableList(events),
        )

    companion object {
        val EMPTY = EffectExecution(
            stateChanges = immutableList(emptyList()),
            events = immutableList(emptyList()),
        )
    }
}

enum class EffectDeclarationKind {
    STANDARD,
    META_NO_OP,
}

data class EffectDeclaration(
    val effectId: Int,
    val kind: EffectDeclarationKind,
)

enum class EffectFailureCode {
    UNKNOWN_EFFECT,
    UNIMPLEMENTED_EFFECT,
}

data class BattleEffectDiagnostic(
    val code: EffectFailureCode,
    val skillId: Int,
    val detailId: Int,
    val effectId: Int,
    val trigger: BattleTrigger,
    val callPath: List<Int>,
) {
    fun message(): String =
        "$code: skill=$skillId detail=$detailId effect=$effectId " +
            "trigger=$trigger callPath=${callPath.joinToString(" -> ")}"
}

class UnsupportedSkillRuleException(
    val diagnostic: BattleEffectDiagnostic,
) : IllegalStateException(diagnostic.message())

class BattleEffectRegistry private constructor(
    declarations: Map<Int, EffectDeclaration>,
    handlers: Map<Int, BattleEffectHandler>,
    private val failureMode: FailureMode,
    private val logger: (BattleEffectDiagnostic) -> Unit,
) {
    private val declarations: Map<Int, EffectDeclaration> = immutableMap(declarations)
    private val handlers: Map<Int, BattleEffectHandler> = immutableMap(handlers)
    private val declaredIds: Set<Int> = immutableSet(this.declarations.keys)
    private val implementedIds: Set<Int> = immutableSet(this.handlers.keys)

    fun declaredEffectIds(): Set<Int> = declaredIds

    fun implementedEffectIds(): Set<Int> = implementedIds

    fun declaration(effectId: Int): EffectDeclaration? = declarations[effectId]

    fun register(
        effectId: Int,
        handler: BattleEffectHandler,
    ): BattleEffectRegistry {
        require(effectId in declarations) { "Undeclared effect=$effectId" }
        require(effectId !in handlers) { "Duplicate handler for effect=$effectId" }
        return BattleEffectRegistry(
            declarations = declarations,
            handlers = handlers + (effectId to handler),
            failureMode = failureMode,
            logger = logger,
        )
    }

    fun register(handlers: Map<Int, BattleEffectHandler>): BattleEffectRegistry {
        val duplicateIds = handlers.keys intersect this.handlers.keys
        require(duplicateIds.isEmpty()) {
            "Duplicate handlers for effects=${duplicateIds.sorted()}"
        }
        val undeclaredIds = handlers.keys - declarations.keys
        require(undeclaredIds.isEmpty()) {
            "Undeclared effects=${undeclaredIds.sorted()}"
        }
        return BattleEffectRegistry(
            declarations = declarations,
            handlers = this.handlers + handlers,
            failureMode = failureMode,
            logger = logger,
        )
    }

    fun execute(
        rule: SkillEffectRule,
        context: SkillBattleContext,
    ): EffectExecution {
        val callPath = invocationCallPath(context)
        val invocation = EffectInvocation(
            rule = rule,
            context = context,
            callPath = immutableList(callPath),
        )
        val handler = handlers[rule.effectId]
        if (handler != null) {
            return handler.execute(invocation).immutableCopy()
        }

        val diagnostic = BattleEffectDiagnostic(
            code = if (rule.effectId in declarations) {
                EffectFailureCode.UNIMPLEMENTED_EFFECT
            } else {
                EffectFailureCode.UNKNOWN_EFFECT
            },
            skillId = context.currentSkillId,
            detailId = rule.detailId,
            effectId = rule.effectId,
            trigger = context.trigger,
            callPath = immutableList(callPath),
        )
        return when (failureMode) {
            FailureMode.STRICT -> throw UnsupportedSkillRuleException(diagnostic)
            FailureMode.SAFE -> {
                logger(diagnostic)
                EffectExecution.EMPTY
            }
        }
    }

    private fun invocationCallPath(context: SkillBattleContext): List<Int> {
        val runtimePath = context.runtime.currentCallPath()
        if (runtimePath.isNotEmpty()) {
            return if (runtimePath.last() == context.currentSkillId) {
                runtimePath
            } else {
                runtimePath + context.currentSkillId
            }
        }
        return if (context.rootSkillId == context.currentSkillId) {
            listOf(context.currentSkillId)
        } else {
            listOf(context.rootSkillId, context.currentSkillId)
        }
    }

    private enum class FailureMode {
        STRICT,
        SAFE,
    }

    companion object {
        private val defaultGraph: SkillRuleGraph by lazy {
            SkillRuleCatalog.build(
                SkillScopeCatalog.loadDefault(),
                BattleConfigRepository.loadDefault(),
            )
        }

        fun strict(graph: SkillRuleGraph = defaultGraph): BattleEffectRegistry =
            create(graph, FailureMode.STRICT) {}

        fun safe(
            logger: (BattleEffectDiagnostic) -> Unit,
        ): BattleEffectRegistry =
            safe(defaultGraph, logger)

        fun safe(
            graph: SkillRuleGraph,
            logger: (BattleEffectDiagnostic) -> Unit,
        ): BattleEffectRegistry =
            create(graph, FailureMode.SAFE, logger)

        private fun create(
            graph: SkillRuleGraph,
            failureMode: FailureMode,
            logger: (BattleEffectDiagnostic) -> Unit,
        ): BattleEffectRegistry {
            val declarations = graph.effectIds.associateWith { effectId ->
                EffectDeclaration(
                    effectId = effectId,
                    kind = if (effectId == META_NO_OP_EFFECT_ID) {
                        EffectDeclarationKind.META_NO_OP
                    } else {
                        EffectDeclarationKind.STANDARD
                    },
                )
            }
            return BattleEffectRegistry(
                declarations = declarations,
                handlers = emptyMap(),
                failureMode = failureMode,
                logger = logger,
            )
        }

        private const val META_NO_OP_EFFECT_ID = 0
    }
}

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
