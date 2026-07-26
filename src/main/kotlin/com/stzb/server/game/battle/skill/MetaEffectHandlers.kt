package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef

enum class MetaEffectSemantic {
    JOINT_ATTACK,
    DAMAGE_RATE_MAXIMUM,
    DAMAGE_RATE_MINIMUM,
    SHARED_EFFECT_USES,
    REFERENCED_EXTRA_PARAMETER,
    REFERENCED_VALUE_CHANGE,
    RESISTANCE,
    COMMAND_EFFECT_IMMUNITY,
    BENEFICIAL_PUPPET,
    DAMAGE_SHARING,
    SKILL_PROBABILITY_INCREASE,
    EFFECT_PROBABILITY_INCREASE,
    TRIGGER_LAST_APPLIED_EFFECT,
    IGNORE_ENEMY_ATTRIBUTE,
    SKILL_RANGE_INCREASE,
    SKILL_RANGE_DECREASE,
    TRANSFORMATION,
    COMBO,
    SKILL_PROBABILITY_DECREASE,
    SPECIAL_DAMAGE_TAKEN_INCREASE,
    RECOVERY_TAKEN_INCREASE,
    EXTRA_CONTROL_TARGET,
    DAMAGE_ABSORPTION,
    LINKED_HEARTS,
}

data class MarkerEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val marker: Int,
) : BattleStateChange

data class MetaEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val effectId: Int,
    val semantic: MetaEffectSemantic,
    val referencedId: Int,
    val value: Int,
    val durationRounds: Int,
) : BattleStateChange

data class MoraleEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val delta: Int,
) : BattleStateChange

data class ExecuteChildSkillChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val effectId: Int,
    val childSkillIds: List<Int>,
    val selectedTargets: List<BattleHeroRef>,
) : BattleStateChange

data class RetriggerSkillChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val skillKind: com.stzb.server.game.battle.SkillKind,
    val selectedTargets: List<BattleHeroRef>,
    val maximumExecutions: Int?,
) : BattleStateChange

data class TriggerReferencedEffectChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val referencedDetailId: Int,
    val selectedTargets: List<BattleHeroRef>,
    val attributeScaled: Boolean,
) : BattleStateChange

data class ClearReferencedEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val referencedDetailId: Int,
) : BattleStateChange {
    fun apply(store: BattleEffectStore): EffectLifecycleResult =
        store.clearMatching(target) { it.detailId == referencedDetailId }
}

data class ReduceReferencedEffectUseChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val referencedDetailId: Int,
    val amount: Int,
) : BattleStateChange {
    fun apply(store: BattleEffectStore): EffectLifecycleResult {
        var result = EffectLifecycleResult()
        repeat(amount.coerceAtLeast(0)) {
            val referenced = store.effectsFor(target).firstOrNull {
                it.detailId == referencedDetailId
            } ?: return result
            result = store.consumeHit(target, referenced.effectId, referenced.source)
        }
        return result
    }
}

object MetaEffectHandlers {
    val effectIds: Set<Int> = setOf(
        0,
        77,
        81, 82, 83,
        88,
        111, 112, 113, 114,
        118,
        121, 122, 123,
        125, 127, 129, 130, 131,
        141, 149,
        151, 152, 153,
        161, 171, 181, 199, 200, 210, 231, 261, 281, 313,
        404, 407, 408, 409,
    )

    fun registrations(
        targetSelector: SkillTargetSelector = SkillTargetSelector(),
    ): Array<EffectHandlerRegistration> =
        effectIds.sorted().map { effectId ->
            EffectHandlerRegistration.implemented(
                effectId,
                MetaEffectHandler(effectId, targetSelector),
            )
        }.toTypedArray()
}

private class MetaEffectHandler(
    private val ownedEffectId: Int,
    private val targetSelector: SkillTargetSelector,
) : ImplementedBattleEffectHandler {
    override val semanticId: String = SEMANTIC_IDS.getValue(ownedEffectId)

    override fun execute(invocation: EffectInvocation): EffectExecution {
        check(invocation.rule.effectId == ownedEffectId) {
            "Handler $ownedEffectId cannot execute effect=${invocation.rule.effectId}"
        }
        if (ownedEffectId == 0) return EffectExecution.EMPTY

        val targets = targetSelector.compile(invocation.rule).select(invocation.context)
        val context = invocation.context
        val raw = invocation.rule.raw
        val commonChanges = {
            targets.map { target ->
                MetaEffectChange(
                    source = context.source,
                    target = target,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    effectId = ownedEffectId,
                    semantic = SEMANTICS.getValue(ownedEffectId),
                    referencedId = raw.effectParam,
                    value = raw.constantParam,
                    durationRounds = raw.availableRounds,
                )
            }
        }
        val changes: List<BattleStateChange> = when (ownedEffectId) {
            77 -> targets.map { target ->
                MarkerEffectChange(
                    source = context.source,
                    target = target,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    marker = raw.constantParam,
                )
            }
            113, 114 -> targets.map { target ->
                MoraleEffectChange(
                    source = context.source,
                    target = target,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    delta = if (ownedEffectId == 113) raw.constantParam else -raw.constantParam,
                )
            }
            122, 123, 210 -> listOf(
                ExecuteChildSkillChange(
                    source = context.source,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    effectId = ownedEffectId,
                    childSkillIds = invocation.rule.childSkillIds.toList(),
                    selectedTargets = targets,
                ),
            )
            129, 130 -> listOf(
                RetriggerSkillChange(
                    source = context.source,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    skillKind = if (ownedEffectId == 129) {
                        com.stzb.server.game.battle.SkillKind.ACTIVE
                    } else {
                        com.stzb.server.game.battle.SkillKind.PURSUIT
                    },
                    selectedTargets = targets,
                    maximumExecutions = raw.availableHit.takeIf { it > 0 },
                ),
            )
            151, 153, 408 -> listOf(
                TriggerReferencedEffectChange(
                    source = context.source,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    referencedDetailId = raw.effectParam,
                    selectedTargets = targets,
                    attributeScaled = ownedEffectId == 153,
                ),
            )
            152 -> targets.map { target ->
                ClearReferencedEffectChange(
                    source = context.source,
                    target = target,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    referencedDetailId = raw.effectParam,
                )
            }
            313 -> targets.map { target ->
                ReduceReferencedEffectUseChange(
                    source = context.source,
                    target = target,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    referencedDetailId = raw.effectParam,
                    amount = raw.constantParam.coerceAtLeast(1),
                )
            }
            else -> commonChanges()
        }
        return EffectExecution(changes, emptyList())
    }

    private companion object {
        val SEMANTIC_IDS: Map<Int, String> = mapOf(
            0 to "meta.no-op.explicit",
            77 to "meta.marker",
            81 to "meta.joint-attack",
            82 to "meta.damage-rate.maximum",
            83 to "meta.damage-rate.minimum",
            88 to "meta.effect-uses.shared",
            111 to "meta.reference.extra-parameter",
            112 to "meta.reference.value-change",
            113 to "meta.morale.increase",
            114 to "meta.morale.decrease",
            118 to "meta.resistance",
            121 to "meta.command-effect.immunity",
            122 to "meta.child.beneficial",
            123 to "meta.child.harmful",
            125 to "meta.puppet.beneficial",
            127 to "meta.damage.sharing",
            129 to "meta.retrigger.active",
            130 to "meta.retrigger.pursuit",
            131 to "meta.skill-probability.increase",
            141 to "meta.effect-probability.increase",
            149 to "meta.last-applied-effect.trigger",
            151 to "meta.referenced-effect.trigger",
            152 to "meta.referenced-effect.clear",
            153 to "meta.referenced-effect.attribute-trigger",
            161 to "meta.enemy-attribute.ignore",
            171 to "meta.skill-range.increase",
            181 to "meta.skill-range.decrease",
            199 to "meta.transformation",
            200 to "meta.combo",
            210 to "meta.named-flag.child",
            231 to "meta.skill-probability.decrease",
            261 to "meta.special-damage-taken.increase",
            281 to "meta.recovery-taken.increase",
            313 to "meta.referenced-effect.use-reduction",
            404 to "meta.control-target.extra",
            407 to "meta.damage.absorption",
            408 to "meta.damage.release",
            409 to "meta.linked-hearts",
        )

        val SEMANTICS: Map<Int, MetaEffectSemantic> = mapOf(
            81 to MetaEffectSemantic.JOINT_ATTACK,
            82 to MetaEffectSemantic.DAMAGE_RATE_MAXIMUM,
            83 to MetaEffectSemantic.DAMAGE_RATE_MINIMUM,
            88 to MetaEffectSemantic.SHARED_EFFECT_USES,
            111 to MetaEffectSemantic.REFERENCED_EXTRA_PARAMETER,
            112 to MetaEffectSemantic.REFERENCED_VALUE_CHANGE,
            118 to MetaEffectSemantic.RESISTANCE,
            121 to MetaEffectSemantic.COMMAND_EFFECT_IMMUNITY,
            125 to MetaEffectSemantic.BENEFICIAL_PUPPET,
            127 to MetaEffectSemantic.DAMAGE_SHARING,
            131 to MetaEffectSemantic.SKILL_PROBABILITY_INCREASE,
            141 to MetaEffectSemantic.EFFECT_PROBABILITY_INCREASE,
            149 to MetaEffectSemantic.TRIGGER_LAST_APPLIED_EFFECT,
            161 to MetaEffectSemantic.IGNORE_ENEMY_ATTRIBUTE,
            171 to MetaEffectSemantic.SKILL_RANGE_INCREASE,
            181 to MetaEffectSemantic.SKILL_RANGE_DECREASE,
            199 to MetaEffectSemantic.TRANSFORMATION,
            200 to MetaEffectSemantic.COMBO,
            231 to MetaEffectSemantic.SKILL_PROBABILITY_DECREASE,
            261 to MetaEffectSemantic.SPECIAL_DAMAGE_TAKEN_INCREASE,
            281 to MetaEffectSemantic.RECOVERY_TAKEN_INCREASE,
            404 to MetaEffectSemantic.EXTRA_CONTROL_TARGET,
            407 to MetaEffectSemantic.DAMAGE_ABSORPTION,
            409 to MetaEffectSemantic.LINKED_HEARTS,
        )
    }
}
