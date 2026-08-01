package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleStat
import com.stzb.server.game.battle.ConfiguredBattleEffectValue
import com.stzb.server.game.battle.SkillKind
import com.stzb.server.game.battle.opposite

enum class MetaEffectOperation {
    MARKER,
    JOINT_ATTACK,
    DAMAGE_RATE_MAXIMUM,
    DAMAGE_RATE_MINIMUM,
    SHARED_EFFECT_USES,
    REFERENCED_EXTRA_PARAMETER,
    REFERENCED_VALUE_CHANGE,
    MORALE_INCREASE,
    MORALE_DECREASE,
    RESISTANCE,
    COMMAND_EFFECT_IMMUNITY,
    EXECUTE_BENEFICIAL_CHILD,
    EXECUTE_HARMFUL_CHILD,
    BENEFICIAL_PUPPET,
    DAMAGE_SHARING,
    RETRIGGER_ACTIVE_SKILL,
    RETRIGGER_PURSUIT_SKILL,
    SKILL_PROBABILITY_INCREASE,
    EFFECT_PROBABILITY_INCREASE,
    TRIGGER_LAST_APPLIED_EFFECT,
    TRIGGER_REFERENCED_EFFECT,
    CLEAR_REFERENCED_EFFECT,
    TRIGGER_ATTRIBUTE_SCALED_EFFECT,
    IGNORE_ENEMY_ATTRIBUTE,
    SKILL_RANGE_INCREASE,
    SKILL_RANGE_DECREASE,
    TRANSFORMATION,
    COMBO,
    EXECUTE_NAMED_CHILD,
    SKILL_PROBABILITY_DECREASE,
    SPECIAL_DAMAGE_TAKEN_INCREASE,
    RECOVERY_TAKEN_INCREASE,
    REDUCE_REFERENCED_EFFECT_USES,
    EXTRA_CONTROL_TARGET,
    DAMAGE_ABSORPTION,
    RELEASE_DAMAGE,
    LINKED_HEARTS,
}

/**
 * Repository-free, lossless execution metadata for meta effects. Consumers can
 * apply these intents later without reopening the CSV repository.
 */
data class MetaEffectParameters(
    val detailId: Int,
    val effectId: Int,
    val effectParam: Int,
    val calcPosition: Int,
    val calcParameter: Int,
    val attackType: Int,
    val selectSkillParameter: Int,
    val targetType: Int,
    val selectType: Int,
    val targetCountry: Int,
    val selectAttribute: Int,
    val customSelectFlag: Int,
    val availableHit: Int,
    val intelligenceCoefficient: Int,
    val constant: Int,
    val probabilityInitial: Int,
    val probabilityMaximum: Int,
    val bindFlag: Int,
    val castCondition: Int,
    val precondition: Int,
    val condition: Int,
    val addCountMaximum: Int,
    val rawBuffType: Int,
    val targetLimit: Int,
    val delayRound: Int,
    val delayHit: Int,
    val availableRounds: Int,
    val clearPerHit: Boolean,
    val selectFlag: Int,
    val inherent: Int,
    val moraleAffected: Boolean,
    val attributeType: Int,
    val valueAddMaximum: Int,
    val hideConflict: Int,
    val probabilitySeries: List<Int>,
    val calculationType: Int,
    val calculationTypes: List<Int>,
    val effectName: String,
    val childSkillIds: Set<Int>,
    val skillHitRange: Int?,
    val configuredValue: ConfiguredBattleEffectValue?,
    val resolvedBuffType: Int,
    val replaceType: Int,
    val skillKind: SkillKind,
    val rawSkillType: Int,
) {
    companion object {
        fun from(rule: SkillEffectRule): MetaEffectParameters {
            val raw = rule.raw
            return MetaEffectParameters(
                detailId = rule.detailId,
                effectId = rule.effectId,
                effectParam = raw.effectParam,
                calcPosition = raw.calcPos,
                calcParameter = raw.calcParam,
                attackType = raw.attackType,
                selectSkillParameter = raw.selectSkillParam,
                targetType = raw.targetType,
                selectType = raw.selectType,
                targetCountry = raw.targetCountry,
                selectAttribute = raw.selectAttri,
                customSelectFlag = raw.customSelectFlag,
                availableHit = raw.availableHit,
                intelligenceCoefficient = raw.intelParam,
                constant = raw.constantParam,
                probabilityInitial = raw.probabilityInit,
                probabilityMaximum = raw.probabilityMax,
                bindFlag = raw.bindFlag,
                castCondition = raw.castCondition,
                precondition = raw.precondition,
                condition = raw.condition,
                addCountMaximum = raw.addCountMax,
                rawBuffType = raw.buffType,
                targetLimit = raw.attackMax,
                delayRound = raw.delayRound,
                delayHit = raw.delayHit,
                availableRounds = raw.availableRounds,
                clearPerHit = raw.clearPerHit,
                selectFlag = raw.selectFlag,
                inherent = raw.inherent,
                moraleAffected = raw.moraleAffected,
                attributeType = raw.attributeType,
                valueAddMaximum = raw.valueAddMax,
                hideConflict = raw.hideConflict,
                probabilitySeries = raw.probabilitySeries.toList(),
                calculationType = raw.calculationType,
                calculationTypes = raw.calculationTypes.toList(),
                effectName = raw.effectName,
                childSkillIds = rule.childSkillIds.toSet(),
                skillHitRange = rule.skillHitRange,
                configuredValue = rule.configuredValue,
                resolvedBuffType = rule.effectBuffType,
                replaceType = rule.effectReplaceType,
                skillKind = rule.skillKind,
                rawSkillType = rule.rawSkillType,
            )
        }
    }
}

data class MarkerEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val marker: Int,
    val parameters: MetaEffectParameters,
) : BattleStateChange

data class MetaEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val effectId: Int,
    val operation: MetaEffectOperation,
    val parameters: MetaEffectParameters,
) : BattleStateChange

data class TriggerLastAppliedEffectChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val targets: List<BattleHeroRef>,
    val parameters: MetaEffectParameters,
    val appliedSpec: PersistentEffectSpec? = null,
) : BattleStateChange

data class TransformAndCastRandomActiveSkillChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val parameters: MetaEffectParameters,
) : BattleStateChange

data class ForcedTargetEffectChange(
    val spec: PersistentEffectSpec,
    val forcedTarget: BattleHeroRef,
) : BattleStateChange

data class SharedEffectUseGroupChange(
    val spec: PersistentEffectSpec,
    val memberDetailId: Int,
) : BattleStateChange

data class ModifierEffectChange(
    val spec: PersistentEffectSpec,
    val modifier: com.stzb.server.game.battle.BattleModifier,
) : BattleStateChange

data class MoraleEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val operation: MetaEffectOperation,
    val potency: TypedBattlePotency.Resolved,
    val parameters: MetaEffectParameters,
) : BattleStateChange {
    val delta: Int
        get() = potency.value
}

data class ExecuteChildSkillChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val operation: MetaEffectOperation,
    val childSkillIds: List<Int>,
    val selectedTargets: List<BattleHeroRef>,
    val inheritedPreselectedTargets: List<BattleHeroRef>?,
    val valueOverride: TypedBattlePotency.Resolved?,
    val probabilityOwnership: ChildProbabilityOwnership,
    val parameters: MetaEffectParameters,
) : BattleStateChange

enum class ChildProbabilityOwnership {
    CONFIGURED_CHILD,
    FORCED_SUCCESS,
}

data class RetriggerSkillChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val operation: MetaEffectOperation,
    val skillKind: SkillKind,
    val selectedTargets: List<BattleHeroRef>,
    val maximumExecutions: Int?,
    val probabilityOwnership: ChildProbabilityOwnership,
    val parameters: MetaEffectParameters,
) : BattleStateChange

enum class ReferenceEffectMode {
    NORMAL,
    ATTRIBUTE_SCALED,
    DAMAGE_RELEASE,
}

data class TriggerReferencedEffectChange(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val referencedDetailId: Int,
    val referencedEffectId: Int,
    val selectedTargets: List<BattleHeroRef>,
    val mode: ReferenceEffectMode,
    val valueOverride: TypedBattlePotency.Resolved?,
    val parameters: MetaEffectParameters,
    val executionOverride: ReferencedDetailExecutionOverride? = null,
    val probabilityAlreadyAccepted: Boolean = false,
) : BattleStateChange {
    val attributeScaled: Boolean
        get() = mode == ReferenceEffectMode.ATTRIBUTE_SCALED
}

data class ClearReferencedEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val referencedDetailId: Int,
    val referencedEffectId: Int,
    val parameters: MetaEffectParameters,
) : BattleStateChange {
    fun apply(store: BattleEffectStore): EffectLifecycleResult =
        store.clearMatching(target) {
            it.detailId == referencedDetailId && it.effectId == referencedEffectId
        }
}

data class ReduceReferencedEffectUseChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val referencedDetailId: Int,
    val referencedEffectId: Int,
    val amount: Int,
    val parameters: MetaEffectParameters,
) : BattleStateChange {
    fun apply(store: BattleEffectStore): EffectLifecycleResult {
        var result = EffectLifecycleResult()
        repeat(amount.coerceAtLeast(0)) {
            val consumed = store.consumeHit(
                target = target,
                effectId = referencedEffectId,
                source = source,
                detailId = referencedDetailId,
            )
            if (consumed.updated.isEmpty() && consumed.expired.isEmpty()) return result
            result = consumed
        }
        return result
    }
}

data class ConsumeEffectUseChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val effectId: Int,
) : BattleStateChange {
    fun apply(store: BattleEffectStore): EffectLifecycleResult =
        store.consumeHit(target = target, effectId = effectId, source = source)
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
        calculator: BattleValueCalculator = DefaultBattleValueCalculator(),
        detailResolver: (Int) -> SkillEffectRule? = { null },
    ): Array<EffectHandlerRegistration> =
        effectIds.sorted().map { effectId ->
            EffectHandlerRegistration.implemented(
                effectId,
                MetaEffectHandler(effectId, targetSelector, calculator, detailResolver),
            )
        }.toTypedArray()
}

private class MetaEffectHandler(
    private val ownedEffectId: Int,
    private val targetSelector: SkillTargetSelector,
    private val calculator: BattleValueCalculator,
    private val detailResolver: (Int) -> SkillEffectRule?,
) : ImplementedBattleEffectHandler {
    override val semanticId: String = "meta.${OPERATIONS[ownedEffectId]?.name?.lowercase() ?: "no-op"}"

    override fun execute(invocation: EffectInvocation): EffectExecution {
        check(invocation.rule.effectId == ownedEffectId) {
            "Handler $ownedEffectId cannot execute effect=${invocation.rule.effectId}"
        }
        if (ownedEffectId == 0) return EffectExecution.EMPTY

        val targets = invocation.selectTargets(targetSelector)
        val context = invocation.context
        val raw = invocation.rule.raw
        val parameters = MetaEffectParameters.from(invocation.rule)
        val operation = OPERATIONS.getValue(ownedEffectId)
        val changes: List<BattleStateChange> = when (ownedEffectId) {
            77 -> targets.map { target ->
                MarkerEffectChange(
                    source = context.source,
                    target = target,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    marker = raw.constantParam,
                    parameters = parameters,
                )
            }
            113, 114 -> {
                val value = invocation.valueOverride ?: configuredPotency(invocation)
                val signed = value.copy(value = if (ownedEffectId == 113) value.value else -value.value)
                targets.map { target ->
                    MoraleEffectChange(
                        source = context.source,
                        target = target,
                        rootSkillId = context.rootSkillId,
                        skillId = context.currentSkillId,
                        detailId = invocation.rule.detailId,
                        operation = operation,
                        potency = signed,
                        parameters = parameters,
                    )
                }
            }
            88 -> {
                val referenced = referencedDetail(invocation)
                targets.map { target ->
                    SharedEffectUseGroupChange(
                        spec = persistentSpec(
                            invocation,
                            target,
                            TypedBattlePotency.flat(1),
                        ),
                        memberDetailId = referenced.detailId,
                    )
                }
            }
            122, 123, 210 -> listOf(
                ExecuteChildSkillChange(
                    source = context.source,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    operation = operation,
                    childSkillIds = invocation.rule.childSkillIds.toList(),
                    selectedTargets = targets,
                    inheritedPreselectedTargets = invocation.preselectedTargets,
                    valueOverride = invocation.valueOverride,
                    probabilityOwnership = ChildProbabilityOwnership.CONFIGURED_CHILD,
                    parameters = parameters,
                ),
            )
            125 -> {
                val referenced = referencedDetail(invocation)
                val inherited = invocation.executionOverride
                val override = ReferencedDetailExecutionOverride(
                    referencedDetailId = referenced.detailId,
                    valueDelta = inherited?.valueDelta,
                    valueReplacement = invocation.valueOverride ?: configuredPotency(invocation),
                    extraParameters = inherited?.extraParameters.orEmpty(),
                    targetOverride = targets,
                    lifecycleOverride = inherited?.lifecycleOverride ?: EffectLifecycleOverride(
                        delayRound = raw.delayRound,
                        delayHit = raw.delayHit,
                        availableRounds = raw.availableRounds,
                        availableHit = raw.availableHit,
                        clearPerHit = raw.clearPerHit,
                    ),
                )
                listOf(
                    TriggerReferencedEffectChange(
                        source = context.source,
                        rootSkillId = context.rootSkillId,
                        skillId = context.currentSkillId,
                        detailId = invocation.rule.detailId,
                        referencedDetailId = referenced.detailId,
                        referencedEffectId = referenced.effectId,
                        selectedTargets = targets,
                        mode = ReferenceEffectMode.NORMAL,
                        valueOverride = override.valueReplacement,
                        parameters = parameters,
                        executionOverride = override,
                        probabilityAlreadyAccepted = true,
                    ),
                )
            }
            129, 130 -> listOf(
                RetriggerSkillChange(
                    source = context.source,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    operation = operation,
                    skillKind = if (ownedEffectId == 129) SkillKind.ACTIVE else SkillKind.PURSUIT,
                    selectedTargets = targets,
                    maximumExecutions = raw.availableHit.takeIf { it > 0 },
                    probabilityOwnership = ChildProbabilityOwnership.CONFIGURED_CHILD,
                    parameters = parameters,
                ),
            )
            131, 231 -> targets.map { target ->
                val rawSkillId = raw.effectParam
                val skillId = rawSkillId.takeIf { it > 0 }
                val skillKind = when (rawSkillId) {
                    -11, -1_000_003 -> SkillKind.ACTIVE
                    -14, -1_000_004 -> SkillKind.PURSUIT
                    -21 -> SkillKind.COMMAND
                    else -> null
                }
                val sign = if (ownedEffectId == 231) -1 else 1
                ModifierEffectChange(
                    spec = persistentSpec(
                        invocation,
                        target,
                        TypedBattlePotency.percent(sign * raw.constantParam),
                    ),
                    modifier = BattleModifier.SkillProbabilityPercent(
                        percent = sign * raw.constantParam,
                        skillId = skillId,
                        skillKind = skillKind,
                    ),
                )
            }
            141 -> targets.map { target ->
                val referencedDetailId = raw.effectParam
                requireNotNull(detailResolver(referencedDetailId)) {
                    "Missing referenced probability detail=$referencedDetailId"
                }
                val potency = TypedBattlePotency.percent(raw.constantParam)
                ModifierEffectChange(
                    spec = persistentSpec(invocation, target, potency),
                    modifier = BattleModifier.EffectProbabilityPercent(
                        detailId = referencedDetailId,
                        percent = potency.value,
                    ),
                )
            }
            281 -> {
                val potency = invocation.valueOverride ?: configuredPotency(invocation)
                targets.map { target ->
                    ModifierEffectChange(
                        spec = persistentSpec(invocation, target, potency),
                        modifier = BattleModifier.RecoveryTakenPercent(potency.value),
                    )
                }
            }
            261 -> {
                val potency = invocation.valueOverride ?: configuredPotency(invocation)
                val tag = when (raw.effectParam) {
                    303 -> com.stzb.server.game.battle.DamageTag.SHAKE
                    304 -> com.stzb.server.game.battle.DamageTag.PANIC
                    305 -> com.stzb.server.game.battle.DamageTag.FIRE
                    306 -> com.stzb.server.game.battle.DamageTag.HEX
                    307 -> com.stzb.server.game.battle.DamageTag.FIRE
                    else -> null
                }
                targets.map { target ->
                    ModifierEffectChange(
                        spec = persistentSpec(invocation, target, potency),
                        modifier = BattleModifier.DamageTakenPercent(
                            tag = tag,
                            percent = potency.value,
                        ),
                    )
                }
            }
            161 -> {
                val potency = defenseIgnorePotency(invocation)
                val stat = when (raw.effectParam) {
                    2 -> BattleStat.DEFENSE
                    3 -> BattleStat.STRATEGY
                    else -> throw UnsupportedConfiguredBattleValueException(
                        BattleEffectDiagnostic(
                            code = EffectFailureCode.UNSUPPORTED_CONFIGURED_VALUE,
                            skillId = context.currentSkillId,
                            detailId = invocation.rule.detailId,
                            effectId = invocation.rule.effectId,
                            trigger = context.trigger,
                            callPath = invocation.callPath,
                            reason = "Unsupported ignored attribute effectParam=${raw.effectParam}",
                        ),
                    )
                }
                targets.map { target ->
                    ModifierEffectChange(
                        spec = persistentSpec(invocation, target, potency),
                        modifier = BattleModifier.DefenseIgnorePercent(potency.value, stat),
                    )
                }
            }
            121 -> targets.map { target ->
                ApplyBattleEffectChange(
                    persistentSpec(
                        invocation,
                        target,
                        TypedBattlePotency.flat(1),
                    ),
                )
            }
            81 -> {
                val forcedTarget = if (raw.customSelectFlag != 0) {
                    context.battleView.heroes()
                        .filter { candidate ->
                            candidate.side != context.source.side &&
                                (
                                    if (SkillBattleViewCapability.LIVE_STATE in
                                        context.battleView.capabilities
                                    ) {
                                        context.battleView.state(candidate)
                                    } else {
                                        context.battleView.entryState(candidate)
                                    }
                                    )?.troops?.let { it > 0 } == true
                        }
                        .minByOrNull(BattleHeroRef::position)
                } else {
                    context.runtime.latestMarkedTarget(
                        rootSkillId = context.rootSkillId,
                        targetSide = context.source.side.opposite(),
                        round = context.round,
                    )
                }
                if (forcedTarget == null) {
                    emptyList()
                } else {
                    targets.map { target ->
                        ForcedTargetEffectChange(
                            spec = persistentSpec(
                                invocation,
                                target,
                                TypedBattlePotency.percent(raw.constantParam.coerceIn(0, 100)),
                            ),
                            forcedTarget = forcedTarget,
                        )
                    }
                }
            }
            118 -> {
                val source = sourceHero(invocation)
                val level = invocation.rootSkillLevel(source)
                val percent = (
                    raw.probabilityInit +
                        (level - 1) * (raw.probabilityMax - raw.probabilityInit) / 9.0
                    ).toInt().coerceIn(0, 100)
                targets.map { target ->
                    ApplyBattleEffectChange(
                        persistentSpec(
                            invocation,
                            target,
                            TypedBattlePotency.percent(percent),
                        ),
                    )
                }
            }
            127 -> targets.mapNotNull { target ->
                val bearer = when {
                    context.source != target -> context.source
                    else -> targets.firstOrNull { it != target }
                } ?: return@mapNotNull null
                val percent = if (raw.calcPos == 992) {
                    raw.constantParam / 5
                } else {
                    raw.constantParam
                }.coerceIn(1, 100)
                DamageRedirectionEffectChange(
                    spec = persistentSpec(
                        invocation,
                        target,
                        TypedBattlePotency.percent(percent),
                    ),
                    protectedTargets = listOf(target),
                    damageBearer = bearer,
                    sharePercent = percent,
                    school = when (raw.effectParam) {
                        0 -> com.stzb.server.game.battle.DamageSchool.PHYSICAL
                        1 -> com.stzb.server.game.battle.DamageSchool.STRATEGY
                        else -> null
                    },
                )
            }
            409 -> {
                if (targets.isEmpty()) emptyList() else listOf(
                    LinkedDamageSharingEffectChange(
                        spec = persistentSpec(
                            invocation,
                            targets.first(),
                            TypedBattlePotency.percent(raw.constantParam),
                        ),
                        members = targets,
                        sharePercentPerAlly = raw.constantParam.coerceIn(1, 100),
                    ),
                )
            }
            149 -> listOf(
                TriggerLastAppliedEffectChange(
                    source = context.source,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    targets = targets,
                    parameters = parameters,
                ),
            )
            200 -> targets.map { target ->
                ActionEffectChange(
                    spec = persistentSpec(
                        invocation,
                        target,
                        TypedBattlePotency.flat(1),
                    ),
                    kind = ActionEffectKind.DOUBLE_ATTACK,
                )
            }
            199 -> listOf(
                TransformAndCastRandomActiveSkillChange(
                    source = context.source,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    parameters = parameters,
                ),
            )
            82, 83 -> targets.map { target ->
                val potency = TypedBattlePotency.percent(raw.constantParam)
                ModifierEffectChange(
                    spec = persistentSpec(invocation, target, potency),
                    modifier = if (ownedEffectId == 82) {
                        BattleModifier.DamageRateMaximumPercent(potency.value)
                    } else {
                        BattleModifier.DamageRateMinimumPercent(potency.value)
                    },
                )
            }
            404 -> targets.map { target ->
                ApplyBattleEffectChange(
                    persistentSpec(
                        invocation,
                        target,
                        TypedBattlePotency.flat(1),
                    ).copy(availableHit = raw.availableHit.coerceAtLeast(1)),
                )
            }
            151, 153, 408 -> {
                val referenced = referencedDetail(invocation)
                listOf(
                    TriggerReferencedEffectChange(
                        source = context.source,
                        rootSkillId = context.rootSkillId,
                        skillId = context.currentSkillId,
                        detailId = invocation.rule.detailId,
                        referencedDetailId = referenced.detailId,
                        referencedEffectId = referenced.effectId,
                        selectedTargets = targets,
                        mode = when (ownedEffectId) {
                            153 -> ReferenceEffectMode.ATTRIBUTE_SCALED
                            408 -> ReferenceEffectMode.DAMAGE_RELEASE
                            else -> ReferenceEffectMode.NORMAL
                        },
                        valueOverride = if (ownedEffectId == 153) configuredPotency(invocation) else null,
                        parameters = parameters,
                    ),
                )
            }
            152 -> {
                val referenced = referencedDetail(invocation)
                targets.map { target ->
                    ClearReferencedEffectChange(
                        source = context.source,
                        target = target,
                        rootSkillId = context.rootSkillId,
                        skillId = context.currentSkillId,
                        detailId = invocation.rule.detailId,
                        referencedDetailId = referenced.detailId,
                        referencedEffectId = referenced.effectId,
                        parameters = parameters,
                    )
                }
            }
            313 -> {
                val referenced = referencedDetail(invocation)
                targets.map { target ->
                    ReduceReferencedEffectUseChange(
                        source = context.source,
                        target = target,
                        rootSkillId = context.rootSkillId,
                        skillId = context.currentSkillId,
                        detailId = invocation.rule.detailId,
                        referencedDetailId = referenced.detailId,
                        referencedEffectId = referenced.effectId,
                        amount = raw.constantParam.coerceAtLeast(1),
                        parameters = parameters,
                    )
                }
            }
            else -> targets.map { target ->
                MetaEffectChange(
                    source = context.source,
                    target = target,
                    rootSkillId = context.rootSkillId,
                    skillId = context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    effectId = ownedEffectId,
                    operation = operation,
                    parameters = parameters,
                )
            }
        }
        return EffectExecution(changes, emptyList())
    }

    private fun referencedDetail(invocation: EffectInvocation): SkillEffectRule =
        detailResolver(invocation.rule.raw.effectParam)
            ?: throw MissingSkillDetailException(
                invocation.callPath,
                invocation.rule.raw.effectParam,
            )

    private fun persistentSpec(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        potency: TypedBattlePotency.Resolved,
    ): PersistentEffectSpec {
        val raw = invocation.rule.raw
        val lifecycle = invocation.lifecycle()
        return PersistentEffectSpec(
            source = invocation.context.source,
            target = target,
            rootSkillId = invocation.context.rootSkillId,
            skillId = invocation.context.currentSkillId,
            skillKind = invocation.rule.skillKind,
            rawSkillType = invocation.rule.rawSkillType,
            detailId = invocation.rule.detailId,
            effectId = invocation.rule.effectId,
            category = com.stzb.server.game.battle.EffectCategory.fromClientBuffType(
                invocation.rule.effectBuffType,
            ),
            conflict = raw.hideConflict,
            replaceType = invocation.rule.effectReplaceType,
            bindFlag = raw.bindFlag,
            maxStacks = raw.addCountMax + 1,
            delayRound = lifecycle.delayRound,
            delayHit = lifecycle.delayHit,
            availableRounds = lifecycle.availableRounds,
            availableHit = lifecycle.availableHit,
            clearPerHit = lifecycle.clearPerHit,
            startBoundary = if (lifecycle.delayRound > 0 || lifecycle.delayHit > 0) {
                EffectStartBoundary.AFTER_DELAY
            } else {
                EffectStartBoundary.IMMEDIATE
            },
            potency = potency,
        )
    }

    private fun configuredPotency(invocation: EffectInvocation): TypedBattlePotency.Resolved {
        val source = sourceHero(invocation)
        val calculated = calculator.effectValue(
            invocation.rule,
            source,
            invocation.rootSkillLevel(source),
        )
        return when (calculated) {
            is TypedBattlePotency.Resolved -> calculated
            is TypedBattlePotency.Deferred -> throw UnsupportedConfiguredBattleValueException(
                BattleEffectDiagnostic(
                    code = EffectFailureCode.UNSUPPORTED_CONFIGURED_VALUE,
                    skillId = invocation.context.currentSkillId,
                    detailId = invocation.rule.detailId,
                    effectId = invocation.rule.effectId,
                    trigger = invocation.context.trigger,
                    callPath = invocation.callPath,
                    reason = calculated.diagnostic,
                ),
            )
        }
    }

    private fun defenseIgnorePotency(invocation: EffectInvocation): TypedBattlePotency.Resolved {
        val source = sourceHero(invocation)
        val level = invocation.rootSkillLevel(source)
        val ratio = invocation.rule.raw.initEffectRatio +
            (level - 1) * (100 - invocation.rule.raw.initEffectRatio) / 9.0
        val percent = invocation.rule.raw.constantParam / 1_000.0 * ratio / 100.0
        return TypedBattlePotency.percent(percent.toInt())
    }

    private fun sourceHero(invocation: EffectInvocation): BattleHero {
        val ref = invocation.context.source
        val entry = (if (ref.side == com.stzb.server.game.battle.Side.ATTACKER) {
            invocation.context.request.attacker
        } else {
            invocation.context.request.defender
        }).heroes.single { it.id == ref.heroId && it.position == ref.position }
        if (SkillBattleViewCapability.LIVE_STATE !in invocation.context.battleView.capabilities) {
            return entry
        }
        val state = requireNotNull(invocation.context.battleView.state(ref)) {
            "Missing live source state for $ref"
        }
        return entry.copy(
            stats = state.stats,
            troops = state.troops,
            maxTroops = state.maxTroops,
            activeStatuses = state.statuses,
            morale = state.morale,
        )
    }

    private companion object {
        val OPERATIONS = mapOf(
            77 to MetaEffectOperation.MARKER,
            81 to MetaEffectOperation.JOINT_ATTACK,
            82 to MetaEffectOperation.DAMAGE_RATE_MAXIMUM,
            83 to MetaEffectOperation.DAMAGE_RATE_MINIMUM,
            88 to MetaEffectOperation.SHARED_EFFECT_USES,
            111 to MetaEffectOperation.REFERENCED_EXTRA_PARAMETER,
            112 to MetaEffectOperation.REFERENCED_VALUE_CHANGE,
            113 to MetaEffectOperation.MORALE_INCREASE,
            114 to MetaEffectOperation.MORALE_DECREASE,
            118 to MetaEffectOperation.RESISTANCE,
            121 to MetaEffectOperation.COMMAND_EFFECT_IMMUNITY,
            122 to MetaEffectOperation.EXECUTE_BENEFICIAL_CHILD,
            123 to MetaEffectOperation.EXECUTE_HARMFUL_CHILD,
            125 to MetaEffectOperation.BENEFICIAL_PUPPET,
            127 to MetaEffectOperation.DAMAGE_SHARING,
            129 to MetaEffectOperation.RETRIGGER_ACTIVE_SKILL,
            130 to MetaEffectOperation.RETRIGGER_PURSUIT_SKILL,
            131 to MetaEffectOperation.SKILL_PROBABILITY_INCREASE,
            141 to MetaEffectOperation.EFFECT_PROBABILITY_INCREASE,
            149 to MetaEffectOperation.TRIGGER_LAST_APPLIED_EFFECT,
            151 to MetaEffectOperation.TRIGGER_REFERENCED_EFFECT,
            152 to MetaEffectOperation.CLEAR_REFERENCED_EFFECT,
            153 to MetaEffectOperation.TRIGGER_ATTRIBUTE_SCALED_EFFECT,
            161 to MetaEffectOperation.IGNORE_ENEMY_ATTRIBUTE,
            171 to MetaEffectOperation.SKILL_RANGE_INCREASE,
            181 to MetaEffectOperation.SKILL_RANGE_DECREASE,
            199 to MetaEffectOperation.TRANSFORMATION,
            200 to MetaEffectOperation.COMBO,
            210 to MetaEffectOperation.EXECUTE_NAMED_CHILD,
            231 to MetaEffectOperation.SKILL_PROBABILITY_DECREASE,
            261 to MetaEffectOperation.SPECIAL_DAMAGE_TAKEN_INCREASE,
            281 to MetaEffectOperation.RECOVERY_TAKEN_INCREASE,
            313 to MetaEffectOperation.REDUCE_REFERENCED_EFFECT_USES,
            404 to MetaEffectOperation.EXTRA_CONTROL_TARGET,
            407 to MetaEffectOperation.DAMAGE_ABSORPTION,
            408 to MetaEffectOperation.RELEASE_DAMAGE,
            409 to MetaEffectOperation.LINKED_HEARTS,
        )
    }
}
