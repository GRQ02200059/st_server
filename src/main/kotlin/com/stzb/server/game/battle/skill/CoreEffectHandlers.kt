package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleDamageCalculator
import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.BattleStat
import com.stzb.server.game.battle.ConfiguredBattleEffectValue
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.DamageTag
import com.stzb.server.game.battle.EffectCategory
import com.stzb.server.game.battle.ActiveSkillEffect
import com.stzb.server.game.battle.SkillKind
import kotlin.math.roundToInt

sealed interface TypedBattlePotency {
    val unit: BattleEffectValueUnit

    data class Resolved(
        override val unit: BattleEffectValueUnit,
        val value: Int,
        val exactValue: Double = value.toDouble(),
    ) : TypedBattlePotency

    data class Deferred(
        override val unit: BattleEffectValueUnit,
        val configuredValue: ConfiguredBattleEffectValue,
        val diagnostic: String,
    ) : TypedBattlePotency

    companion object {
        fun flat(value: Int, exactValue: Double = value.toDouble()): Resolved =
            Resolved(BattleEffectValueUnit.FLAT, value, exactValue)
        fun percent(value: Int): Resolved = Resolved(BattleEffectValueUnit.PERCENT, value)
        fun rate(value: Int): Resolved = Resolved(BattleEffectValueUnit.RATE, value)
    }
}

data class BattleStatChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val kind: Kind,
    val potency: TypedBattlePotency.Resolved,
    val durationRounds: Int,
    val skillId: Int,
    val effectId: Int,
    val detailId: Int = skillId * 10_000 + effectId,
    val maxStacks: Int = 1,
) : BattleStateChange {
    enum class Kind {
        ATTACK,
        DEFENSE,
        STRATEGY,
        SPEED,
        SIEGE,
        ATTACK_RANGE,
    }
}

data class RecoverTroopsChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val amount: Int,
    val troopsAfter: Int,
    val skillId: Int,
    val effectId: Int,
) : BattleStateChange

data class ConsumeWoundedTroopsChange(
    val target: BattleHeroRef,
    val amount: Int,
    val woundedAfter: Int,
    val skillId: Int,
    val effectId: Int,
) : BattleStateChange

data class TroopDamageChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val amount: Int,
    val troopsAfter: Int,
    val school: DamageSchool,
    val origin: DamageOrigin,
    val tags: Set<DamageTag>,
    val skillId: Int,
    val effectId: Int,
) : BattleStateChange

data class TroopRecoveryChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val amount: Int,
    val troopsAfter: Int,
    val skillId: Int,
    val effectId: Int,
) : BattleStateChange

data class WoundedPoolChange(
    val target: BattleHeroRef,
    val delta: Int,
    val woundedAfter: Int,
) : BattleStateChange

enum class EffectStartBoundary {
    IMMEDIATE,
    AFTER_DELAY,
}

data class PersistentEffectSpec(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val skillKind: SkillKind,
    val rawSkillType: Int,
    val detailId: Int,
    val effectId: Int,
    val category: EffectCategory,
    val conflict: Int,
    val replaceType: Int,
    val bindFlag: Int,
    val maxStacks: Int,
    val delayRound: Int,
    val delayHit: Int,
    val availableRounds: Int,
    val availableHit: Int,
    val clearPerHit: Boolean,
    val startBoundary: EffectStartBoundary,
    val potency: TypedBattlePotency.Resolved,
) {
    fun toActiveSkillEffectOrNull(): ActiveSkillEffect? {
        if (availableRounds == 0 && availableHit == 0 && !clearPerHit) return null
        return ActiveSkillEffect(
            source = source,
            target = target,
            rootSkillId = rootSkillId,
            skillId = skillId,
            skillKind = skillKind,
            sourceSkillType = rawSkillType,
            detailId = detailId,
            effectId = effectId,
            category = category,
            conflict = conflict,
            strength = kotlin.math.abs(potency.value),
            strengthExact = kotlin.math.abs(potency.exactValue),
            replaceType = replaceType,
            bindFlag = bindFlag,
            maxStacks = maxStacks,
            stacks = 1,
            remainingRounds = availableRounds.takeIf { it > 0 },
            remainingHits = availableHit.takeIf { it > 0 },
            clearPerHit = clearPerHit,
        )
    }

    fun toActiveSkillEffect(): ActiveSkillEffect =
        requireNotNull(toActiveSkillEffectOrNull()) {
            "Effect detail=$detailId has explicit zero duration and no hit lifecycle"
        }
}

data class ApplyBattleEffectChange(
    val spec: PersistentEffectSpec,
) : BattleStateChange {
    fun toActiveSkillEffectOrNull(): ActiveSkillEffect? = spec.toActiveSkillEffectOrNull()
    fun toActiveSkillEffect(): ActiveSkillEffect = spec.toActiveSkillEffect()
}

data class ScheduledDamageEffectChange(
    val spec: PersistentEffectSpec,
    val school: DamageSchool,
    val origin: DamageOrigin,
    val tags: Set<DamageTag>,
    val status: BattleStatus,
    val coefficientSource: BattleCoefficientSource,
    val rawCoefficient: Int,
    val calculationTypes: List<Int>,
) : BattleStateChange {
    val source: BattleHeroRef
        get() = spec.source
    val target: BattleHeroRef
        get() = spec.target
    val potency: TypedBattlePotency.Resolved
        get() = spec.potency
    val durationRounds: Int
        get() = spec.availableRounds
    val skillId: Int
        get() = spec.skillId
    val effectId: Int
        get() = spec.effectId

    fun tick(
        liveSource: BattleHero,
        liveTarget: BattleHero,
        attributeRandomTenths: Int = 35,
    ): TroopDamageChange {
        val ratePercent = liveRatePercent(liveSource)
        val amount = when (school) {
            DamageSchool.PHYSICAL -> BattleDamageCalculator.physical(
                source = liveSource,
                target = liveTarget,
                ratePercent = ratePercent,
                attributeRandomTenths = attributeRandomTenths,
                origin = origin,
                tags = tags,
            )
            DamageSchool.STRATEGY -> BattleDamageCalculator.strategy(
                source = liveSource,
                target = liveTarget,
                ratePercent = ratePercent,
                ongoing = true,
                origin = origin,
                tags = tags,
            )
        }.coerceAtMost(liveTarget.troops)
        return TroopDamageChange(
            source = source,
            target = target,
            amount = amount,
            troopsAfter = (liveTarget.troops - amount).coerceAtLeast(0),
            school = school,
            origin = origin,
            tags = tags,
            skillId = skillId,
            effectId = effectId,
        )
    }

    private fun liveRatePercent(source: BattleHero): Int {
        val attribute = when (coefficientSource) {
            BattleCoefficientSource.ATTACK -> source.stats.attack
            BattleCoefficientSource.DEFENSE -> source.stats.defense
            BattleCoefficientSource.STRATEGY -> source.stats.strategy
            BattleCoefficientSource.SPEED -> source.stats.speed
            BattleCoefficientSource.NONE -> BASE_STRATEGY.toInt()
        }
        val base = potency.value.toDouble()
        val rate = if (rawCoefficient == 0) {
            base
        } else if (attribute < BASE_STRATEGY) {
            base * 0.4 + base * 0.6 * attribute / BASE_STRATEGY
        } else {
            base + rawCoefficient / 1_000.0 * (attribute - BASE_STRATEGY)
        }
        val multiplier = if (calculationTypes.isEmpty()) {
            1
        } else {
            calculationTypes[source.advanceLevel.coerceIn(0, calculationTypes.lastIndex)]
        }
        return (rate * multiplier).roundToInt().coerceAtLeast(1)
    }

    private companion object {
        const val BASE_STRATEGY = 80.0
    }
}

val ScheduledRecoveryEffectChange.source: BattleHeroRef
    get() = spec.source
val ScheduledRecoveryEffectChange.target: BattleHeroRef
    get() = spec.target
val ScheduledRecoveryEffectChange.durationRounds: Int
    get() = spec.availableRounds
val ScheduledRecoveryEffectChange.skillId: Int
    get() = spec.skillId
val ScheduledRecoveryEffectChange.effectId: Int
    get() = spec.effectId

data class ScheduledRecoveryEffectChange(
    val spec: PersistentEffectSpec,
    val potency: TypedBattlePotency.Resolved,
) : BattleStateChange {
    fun tick(
        liveState: SkillBattleHeroState,
        effectStore: BattleEffectStore,
    ): List<BattleStateChange> {
        if (effectStore.effectsFor(spec.target).any { it.effectId == UNRECOVERABLE_EFFECT_ID }) {
            return listOf(
                EffectBlockedChange(
                    source = spec.source,
                    target = spec.target,
                    skillId = spec.skillId,
                    effectId = spec.effectId,
                    blockingEffectId = UNRECOVERABLE_EFFECT_ID,
                ),
            )
        }
        val amount = minOf(
            potency.value.coerceAtLeast(0),
            liveState.woundedTroops,
            (liveState.maxTroops - liveState.troops).coerceAtLeast(0),
        )
        if (amount == 0) return emptyList()
        return listOf(
            RecoverTroopsChange(
                source = spec.source,
                target = spec.target,
                amount = amount,
                troopsAfter = liveState.troops + amount,
                skillId = spec.skillId,
                effectId = spec.effectId,
            ),
            ConsumeWoundedTroopsChange(
                target = spec.target,
                amount = amount,
                woundedAfter = liveState.woundedTroops - amount,
                skillId = spec.skillId,
                effectId = spec.effectId,
            ),
        )
    }

    companion object {
        private const val UNRECOVERABLE_EFFECT_ID = 207
    }
}

data class DamageModifierChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val direction: Direction,
    val school: DamageSchool?,
    val origin: DamageOrigin?,
    val tag: DamageTag?,
    val percent: Int,
    val durationRounds: Int,
    val skillId: Int,
    val effectId: Int,
    val detailId: Int = skillId * 10_000 + effectId,
    val availableHits: Int = 0,
    val extraParameters: Map<Int, Int> = emptyMap(),
) : BattleStateChange {
    enum class Direction {
        DEALT,
        TAKEN,
    }
}

data class EffectBlockedChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val skillId: Int,
    val effectId: Int,
    val blockingEffectId: Int,
) : BattleStateChange

interface BattleValueCalculator {
    fun effectValue(
        rule: SkillEffectRule,
        source: BattleHero,
        skillLevel: Int = 1,
    ): TypedBattlePotency
    fun physicalDamage(invocation: EffectInvocation): Int
    fun strategyDamage(invocation: EffectInvocation, ongoing: Boolean): Int
    fun recovery(invocation: EffectInvocation): Int

    fun physicalDamage(invocation: EffectInvocation, target: BattleHeroRef): Int =
        physicalDamage(invocation)

    fun strategyDamage(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        ongoing: Boolean,
    ): Int = strategyDamage(invocation, ongoing)
}

class DefaultBattleValueCalculator(
    private val targetSelector: SkillTargetSelector = SkillTargetSelector(),
) : BattleValueCalculator {
    override fun effectValue(
        rule: SkillEffectRule,
        source: BattleHero,
        skillLevel: Int,
    ): TypedBattlePotency {
        val configured = rule.configuredValue ?: ConfiguredBattleEffectValue(
            unit = BattleEffectValueUnit.FLAT,
            rawValueType = BattleEffectValueUnit.FLAT.rawValueType,
            rawConstant = rule.raw.constantParam,
            rawCoefficient = rule.raw.intelParam,
            rawAttributeType = rule.raw.attributeType,
            rawCalcPosition = rule.raw.calcPos,
            rawCalcParameter = rule.raw.calcParam,
        )
        if (rule.effectId in 521..534 && configured.unit == BattleEffectValueUnit.RATE) {
            val level = skillLevel.coerceIn(1, 10)
            val ratio = rule.raw.initEffectRatio +
                (level - 1) * (100 - rule.raw.initEffectRatio) / 9.0
            val raw = configured.rawConstant +
                configured.rawCoefficient * source.stats.precise(BattleStat.STRATEGY) / 200.0
            return TypedBattlePotency.rate((ratio * raw / 100.0).roundToInt())
        }
        if (rule.detailId in 20002301..20002304 &&
            configured.unit == BattleEffectValueUnit.PERCENT
        ) {
            val level = skillLevel.coerceIn(1, 10)
            val ratio = rule.raw.initEffectRatio +
                (level - 1) * (100 - rule.raw.initEffectRatio) / 9.0
            val raw = configured.rawConstant / 1_000_000.0 +
                configured.rawCoefficient / 1_000_000.0 *
                source.stats.precise(BattleStat.STRATEGY) / 200.0
            return TypedBattlePotency.percent((ratio * raw / 100.0).roundToInt())
        }
        if (rule.effectId in FLAT_ATTRIBUTE_EFFECT_IDS &&
            configured.unit == BattleEffectValueUnit.PERCENT &&
            configured.rawCalcPosition == 0 &&
            configured.rawCoefficient == 0 &&
            configured.rawAttributeType == 0 &&
            kotlin.math.abs(configured.rawConstant) < PERCENT_ATTRIBUTE_SCALE_THRESHOLD
        ) {
            val level = skillLevel.coerceIn(1, 10)
            val ratio = rule.raw.initEffectRatio +
                (level - 1) * (100 - rule.raw.initEffectRatio) / 9.0
            return TypedBattlePotency.flat(
                (configured.rawConstant / FLAT_ATTRIBUTE_SCALE * ratio / 100.0).roundToInt(),
                configured.rawConstant / FLAT_ATTRIBUTE_SCALE * ratio / 100.0,
            )
        }
        val scale = when (configured.unit) {
            BattleEffectValueUnit.FLAT -> 1.0
            BattleEffectValueUnit.RATE -> 1.0
            BattleEffectValueUnit.PERCENT ->
                when (configured.rawCalcPosition) {
                    31, 311, 31111, 31112 -> 100.0
                    else -> when (rule.detailId) {
                        20000101 -> 100.0
                        20003625, 20003636 -> 1.0
                        in 20002301..20002304 -> 1_000_000.0
                        in 21227003..21227006 -> 100.0
                        else -> return deferred(rule, configured)
                    }
                }
        }
        val constant = configured.rawConstant / scale
        val coefficient = configured.rawCoefficient / scale
        val attribute = when (rule.coefficientSource) {
            BattleCoefficientSource.ATTACK -> source.stats.attack
            BattleCoefficientSource.DEFENSE -> source.stats.defense
            BattleCoefficientSource.STRATEGY -> source.stats.strategy
            BattleCoefficientSource.SPEED -> source.stats.speed
            BattleCoefficientSource.NONE -> BASE_STRATEGY.toInt()
        }
        val intelligenceScaled =
            constant + (attribute - BASE_STRATEGY) * coefficient / 1_000.0
        val calculationMultiplier = if (rule.raw.calculationTypes.isEmpty()) {
            1
        } else {
            rule.raw.calculationTypes[
                source.advanceLevel.coerceIn(0, rule.raw.calculationTypes.lastIndex)
            ]
        }
        val value = (intelligenceScaled * calculationMultiplier).roundToInt()
        return TypedBattlePotency.Resolved(configured.unit, value)
    }

    private fun deferred(
        rule: SkillEffectRule,
        configured: ConfiguredBattleEffectValue,
    ): TypedBattlePotency.Deferred =
        TypedBattlePotency.Deferred(
            unit = configured.unit,
            configuredValue = configured,
            diagnostic = "Unsupported configured battle value: detail=${rule.detailId} " +
                "effect=${rule.effectId} unit=${configured.unit} " +
                "rawValueType=${configured.rawValueType} rawConstant=${configured.rawConstant} " +
                "rawCoefficient=${configured.rawCoefficient} " +
                "rawAttributeType=${configured.rawAttributeType} " +
                "rawCalcPosition=${configured.rawCalcPosition} " +
                "rawCalcParameter=${configured.rawCalcParameter}",
        )

    override fun physicalDamage(invocation: EffectInvocation): Int =
        physicalDamage(invocation, selectedTarget(invocation))

    override fun physicalDamage(invocation: EffectInvocation, target: BattleHeroRef): Int {
        val source = invocation.liveHero(invocation.context.source)
        val targetHero = invocation.liveHero(target)
        return BattleDamageCalculator.physical(
            source = source,
            target = targetHero,
            ratePercent = damageRate(invocation, source),
            attributeRandomTenths = 30 + invocation.context.random.nextInt(10),
            origin = invocation.damageOrigin(),
        )
    }

    override fun strategyDamage(invocation: EffectInvocation, ongoing: Boolean): Int =
        strategyDamage(invocation, selectedTarget(invocation), ongoing)

    override fun strategyDamage(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        ongoing: Boolean,
    ): Int {
        val source = invocation.liveHero(invocation.context.source)
        val targetHero = invocation.liveHero(target)
        val tags = buildSet {
            if (ongoing) add(DamageTag.ONGOING)
            if (invocation.rule.effectId == FIRE_ATTACK_EFFECT_ID) add(DamageTag.FIRE)
        }
        return BattleDamageCalculator.strategy(
            source = source,
            target = targetHero,
            ratePercent = damageRate(invocation, source),
            ongoing = ongoing,
            origin = invocation.damageOrigin(),
            tags = tags,
        )
    }

    override fun recovery(invocation: EffectInvocation): Int {
        val source = invocation.liveHero(invocation.context.source)
        val base = (source.troops * 300.0 / (3_500 + source.troops)).roundToInt()
        val rate = damageRate(invocation, source) / 100.0
        return (base * rate).toInt().coerceAtLeast(0)
    }

    private fun selectedTarget(invocation: EffectInvocation): BattleHeroRef =
        invocation.selectTargets(targetSelector).firstOrNull()
            ?: error("No live target for detail=${invocation.rule.detailId}")

    private fun damageRate(invocation: EffectInvocation, source: BattleHero): Int {
        invocation.valueOverride?.let {
            return invocation.withValueDelta(it).value.coerceAtLeast(1)
        }
        val rule = invocation.rule
        val raw = rule.raw
        val base = raw.constantParam.toDouble()
        val rate = if (raw.intelParam == 0) {
            base
        } else if (source.stats.strategy < BASE_STRATEGY) {
            base * 0.4 + base * 0.6 * source.stats.strategy / BASE_STRATEGY
        } else {
            base + raw.intelParam / 1_000.0 * (source.stats.strategy - BASE_STRATEGY)
        }
        val multiplier = if (raw.calculationTypes.isEmpty()) {
            1
        } else {
            raw.calculationTypes[source.advanceLevel.coerceIn(0, raw.calculationTypes.lastIndex)]
        }
        val configuredRate = invocation.withValueDelta(
            TypedBattlePotency.rate((rate * multiplier).roundToInt()),
        ).value.coerceAtLeast(1)
        if (invocation.rule.effectId !in STRATEGY_DAMAGE_EFFECT_IDS) {
            return scaleEffectValue(configuredRate, invocation)
        }
        val minimum = source.modifiers
            .filterIsInstance<BattleModifier.DamageRateMinimumPercent>()
            .lastOrNull()
            ?.percent
        val maximum = source.modifiers
            .filterIsInstance<BattleModifier.DamageRateMaximumPercent>()
            .lastOrNull()
            ?.percent
        val modifierAdjusted = if (minimum == null && maximum == null) {
            configuredRate
        } else {
            val low = (minimum ?: maximum ?: 100).coerceAtLeast(0)
            val high = (maximum ?: minimum ?: 100).coerceAtLeast(low)
            val factor = if (low == high) {
                low
            } else {
                low + invocation.context.random.nextInt(high - low + 1)
            }
            (configuredRate * factor / 100.0).roundToInt().coerceAtLeast(1)
        }
        return scaleEffectValue(modifierAdjusted, invocation)
    }

    private fun scaleEffectValue(value: Int, invocation: EffectInvocation): Int =
        (
            value * invocation.context.effectValueScalePercent / 100.0
            ).roundToInt().coerceAtLeast(1)

    private companion object {
        const val BASE_STRATEGY = 80.0
        const val FLAT_ATTRIBUTE_SCALE = 100.0
        const val PERCENT_ATTRIBUTE_SCALE_THRESHOLD = 100_000
        const val FIRE_ATTACK_EFFECT_ID = 307
        val FLAT_ATTRIBUTE_EFFECT_IDS = (101..105) + (201..205)
        val STRATEGY_DAMAGE_EFFECT_IDS = setOf(302, 304, 305, 306, 307)
    }
}

object CoreEffectHandlers {
    val effectIds: Set<Int> =
        ((101..106) + (201..207) + (301..307) +
            listOf(321, 322, 325, 331, 332, 335, 342, 351, 352, 355, 401, 402) +
            (521..524) + (531..534)).toSet()

    fun registrations(
        effectStore: BattleEffectStore,
        calculator: BattleValueCalculator = DefaultBattleValueCalculator(),
        targetSelector: SkillTargetSelector = SkillTargetSelector(),
    ): Array<EffectHandlerRegistration> =
        effectIds.sorted().map { effectId ->
            EffectHandlerRegistration.implemented(
                effectId,
                CoreEffectHandler(effectId, effectStore, calculator, targetSelector),
            )
        }.toTypedArray()
}

private class CoreEffectHandler(
    private val ownedEffectId: Int,
    private val effectStore: BattleEffectStore,
    private val calculator: BattleValueCalculator,
    private val targetSelector: SkillTargetSelector,
) : ImplementedBattleEffectHandler {
    override val semanticId: String = "core.effect.$ownedEffectId"

    override fun execute(invocation: EffectInvocation): EffectExecution {
        check(invocation.rule.effectId == ownedEffectId) {
            "Handler $ownedEffectId cannot execute effect=${invocation.rule.effectId}"
        }
        val targets = invocation.selectTargets(targetSelector)
        val changes = targets.flatMap { target ->
            blockedChange(invocation, target)?.let(::listOf)
                ?: changesForTarget(invocation, target)
        }
        return EffectExecution(changes, emptyList())
    }

    private fun changesForTarget(
        invocation: EffectInvocation,
        target: BattleHeroRef,
    ): List<BattleStateChange> {
        val effectId = invocation.rule.effectId
        val sourceHero = invocation.liveHero(invocation.context.source)
        val potency = invocation.withValueDelta(
            invocation.valueOverride
                ?: calculator.effectValue(
                invocation.rule,
                sourceHero,
                invocation.rootSkillLevel(sourceHero),
            ).requireResolved(invocation),
        )
        return when (effectId) {
            in 101..106 -> listOf(statChange(invocation, target, potency, increase = true))
            in 201..206 -> listOf(statChange(invocation, target, potency, increase = false))
            207 -> listOf(appliedEffect(invocation, target, potency))
            301 -> listOf(directDamage(invocation, target, DamageSchool.PHYSICAL))
            302 -> listOf(directDamage(invocation, target, DamageSchool.STRATEGY))
            303 -> listOf(ongoingDamage(invocation, target, BattleStatus.SHAKE, DamageSchool.PHYSICAL))
            304 -> listOf(ongoingDamage(invocation, target, BattleStatus.PANIC, DamageSchool.STRATEGY))
            305 -> listOf(ongoingDamage(invocation, target, BattleStatus.BURN, DamageSchool.STRATEGY))
            306 -> listOf(ongoingDamage(invocation, target, BattleStatus.HEX, DamageSchool.STRATEGY))
            307 -> listOf(directDamage(invocation, target, DamageSchool.STRATEGY, setOf(DamageTag.FIRE)))
            401 -> if (
                invocation.rule.raw.calcPos == EMERGENCY_RECOVERY_CALC_POSITION &&
                invocation.context.trigger == BattleTrigger.BATTLE_COMMAND
            ) {
                listOf(appliedEffect(invocation, target, potency))
            } else {
                recoveryChanges(invocation, target)
            }
            402 -> scheduledRecoveryChanges(invocation, target)
            in DAMAGE_MODIFIER_EFFECT_IDS -> listOf(damageModifier(invocation, target, potency))
            else -> error("Core handler missing effect=$effectId")
        }
    }

    private fun statChange(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        potency: TypedBattlePotency.Resolved,
        increase: Boolean,
    ): BattleStatChange {
        val normalizedId = if (increase) invocation.rule.effectId else invocation.rule.effectId - 100
        val kind = when (normalizedId) {
            101 -> BattleStatChange.Kind.ATTACK
            102 -> BattleStatChange.Kind.DEFENSE
            103 -> BattleStatChange.Kind.STRATEGY
            104 -> BattleStatChange.Kind.SPEED
            105 -> BattleStatChange.Kind.SIEGE
            106 -> BattleStatChange.Kind.ATTACK_RANGE
            else -> error("Unsupported stat effect=${invocation.rule.effectId}")
        }
        return BattleStatChange(
            source = invocation.context.source,
            target = target,
            kind = kind,
            potency = potency.copy(
                value = if (increase) potency.value else -potency.value,
                exactValue = if (increase) potency.exactValue else -potency.exactValue,
            ),
            durationRounds = invocation.lifecycle().availableRounds,
            skillId = invocation.context.currentSkillId,
            effectId = invocation.rule.effectId,
            detailId = invocation.rule.detailId,
        )
    }

    private fun directDamage(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        school: DamageSchool,
        tags: Set<DamageTag> = emptySet(),
    ): BattleStateChange {
        val targetState = invocation.targetState(target)
        val damage = when (school) {
            DamageSchool.PHYSICAL -> calculator.physicalDamage(invocation, target)
            DamageSchool.STRATEGY -> calculator.strategyDamage(invocation, target, ongoing = false)
        }.coerceAtMost(targetState.troops)
        val damageChange = TroopDamageChange(
            source = invocation.context.source,
            target = target,
            amount = damage,
            troopsAfter = (targetState.troops - damage).coerceAtLeast(0),
            school = school,
            origin = invocation.damageOrigin(),
            tags = tags,
            skillId = invocation.context.currentSkillId,
            effectId = invocation.rule.effectId,
        )
        val lifecycle = invocation.lifecycle()
        if (lifecycle.delayRound <= 0 && lifecycle.delayHit <= 0) return damageChange
        return ScheduledTimingChange(
            snapshot = DelayedEffect(
                source = invocation.context.source,
                rootSkillId = invocation.context.rootSkillId,
                skillId = invocation.context.currentSkillId,
                detailId = invocation.rule.detailId,
                dueRound = 0,
            ),
            delayRound = lifecycle.delayRound,
            delayHit = lifecycle.delayHit,
            change = damageChange,
        )
    }

    private fun ongoingDamage(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        status: BattleStatus,
        school: DamageSchool,
    ): ScheduledDamageEffectChange {
        val raw = invocation.rule.raw
        val potency = invocation.withValueDelta(
            TypedBattlePotency.Resolved(
                unit = invocation.rule.configuredValue?.unit ?: BattleEffectValueUnit.RATE,
                value = invocation.rule.configuredValue?.rawConstant ?: raw.constantParam,
            ),
        )
        return ScheduledDamageEffectChange(
            spec = persistentSpec(invocation, target, potency),
            school = school,
            origin = invocation.damageOrigin(),
            tags = buildSet {
                add(DamageTag.ONGOING)
                when (status) {
                    BattleStatus.SHAKE -> add(DamageTag.SHAKE)
                    BattleStatus.PANIC -> add(DamageTag.PANIC)
                    BattleStatus.BURN -> add(DamageTag.FIRE)
                    BattleStatus.HEX -> add(DamageTag.HEX)
                    else -> Unit
                }
            },
            status = status,
            coefficientSource = invocation.rule.coefficientSource,
            rawCoefficient = invocation.rule.configuredValue?.rawCoefficient ?: raw.intelParam,
            calculationTypes = raw.calculationTypes.toList(),
        )
    }

    private fun recoveryChanges(
        invocation: EffectInvocation,
        target: BattleHeroRef,
    ): List<BattleStateChange> {
        if (isRecoveryBlocked(target)) return emptyList()
        val state = invocation.targetState(target)
        val amount = minOf(
            calculator.recovery(invocation),
            state.woundedTroops,
            (state.maxTroops - state.troops).coerceAtLeast(0),
        )
        if (amount <= 0) return emptyList()
        return listOf(
            RecoverTroopsChange(
                source = invocation.context.source,
                target = target,
                amount = amount,
                troopsAfter = state.troops + amount,
                skillId = invocation.context.currentSkillId,
                effectId = invocation.rule.effectId,
            ),
            ConsumeWoundedTroopsChange(
                target = target,
                amount = amount,
                woundedAfter = state.woundedTroops - amount,
                skillId = invocation.context.currentSkillId,
                effectId = invocation.rule.effectId,
            ),
        )
    }

    private fun scheduledRecoveryChanges(
        invocation: EffectInvocation,
        target: BattleHeroRef,
    ): List<BattleStateChange> {
        if (isRecoveryBlocked(target)) return emptyList()
        val amount = calculator.recovery(invocation)
        if (amount <= 0) return emptyList()
        return listOf(
            ScheduledRecoveryEffectChange(
                spec = persistentSpec(invocation, target, TypedBattlePotency.flat(amount)),
                potency = TypedBattlePotency.flat(amount),
            ),
        )
    }

    private fun damageModifier(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        potency: TypedBattlePotency.Resolved,
    ): DamageModifierChange {
        val effectId = invocation.rule.effectId
        val direction = if (effectId in TAKEN_EFFECT_IDS) {
            DamageModifierChange.Direction.TAKEN
        } else {
            DamageModifierChange.Direction.DEALT
        }
        val sign = if (effectId in REDUCTION_EFFECT_IDS) -1 else 1
        val origin = when (effectId) {
            321, 331, 351 -> DamageOrigin.NORMAL
            322, 332, 342, 352 -> DamageOrigin.ACTIVE
            325, 335, 355 -> DamageOrigin.PURSUIT
            else -> null
        }
        val school = when (effectId) {
            521, 522, 531, 532 -> DamageSchool.PHYSICAL
            523, 524, 533, 534 -> DamageSchool.STRATEGY
            else -> null
        }
        return DamageModifierChange(
            source = invocation.context.source,
            target = target,
            direction = direction,
            school = school,
            origin = origin,
            tag = null,
            percent = sign * potency.value,
            durationRounds = invocation.lifecycle().availableRounds,
            skillId = invocation.context.currentSkillId,
            effectId = effectId,
            detailId = invocation.rule.detailId,
            extraParameters = invocation.executionOverride?.extraParameters.orEmpty(),
        )
    }

    private fun appliedEffect(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        potency: TypedBattlePotency.Resolved,
    ) = ApplyBattleEffectChange(
        spec = persistentSpec(invocation, target, potency),
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
            category = EffectCategory.fromClientBuffType(invocation.rule.effectBuffType),
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

    private fun blockedChange(
        invocation: EffectInvocation,
        target: BattleHeroRef,
    ): EffectBlockedChange? {
        if (invocation.rule.effectId !in RECOVERY_EFFECT_IDS || !isRecoveryBlocked(target)) return null
        return EffectBlockedChange(
            skillId = invocation.context.currentSkillId,
            effectId = invocation.rule.effectId,
            source = invocation.context.source,
            target = target,
            blockingEffectId = UNRECOVERABLE_EFFECT_ID,
        )
    }

    private fun isRecoveryBlocked(target: BattleHeroRef): Boolean =
        effectStore.effectsFor(target).any { it.effectId == UNRECOVERABLE_EFFECT_ID }

    private companion object {
        const val EMERGENCY_RECOVERY_CALC_POSITION = 995
        const val UNRECOVERABLE_EFFECT_ID = 207
        val RECOVERY_EFFECT_IDS = setOf(401, 402)
        val DAMAGE_MODIFIER_EFFECT_IDS =
            setOf(321, 322, 325, 331, 332, 335, 342, 351, 352, 355) +
                (521..524) + (531..534)
        val TAKEN_EFFECT_IDS = setOf(342, 351, 352, 355) + (521..524)
        val REDUCTION_EFFECT_IDS =
            setOf(331, 332, 335, 351, 352, 355, 522, 524, 532, 534)
    }
}

private fun TypedBattlePotency.requireResolved(
    invocation: EffectInvocation,
): TypedBattlePotency.Resolved =
    when (this) {
        is TypedBattlePotency.Resolved -> this
        is TypedBattlePotency.Deferred -> throw UnsupportedConfiguredBattleValueException(
            BattleEffectDiagnostic(
                code = EffectFailureCode.UNSUPPORTED_CONFIGURED_VALUE,
                skillId = invocation.context.currentSkillId,
                detailId = invocation.rule.detailId,
                effectId = invocation.rule.effectId,
                trigger = invocation.context.trigger,
                callPath = invocation.callPath,
                reason = diagnostic,
            ),
        )
    }

private fun EffectInvocation.damageOrigin(): DamageOrigin =
    when (rule.skillKind to rule.rawSkillType) {
        SkillKind.ACTIVE to 3 -> DamageOrigin.ACTIVE
        SkillKind.PURSUIT to 4 -> DamageOrigin.PURSUIT
        SkillKind.COMMAND to 2 -> DamageOrigin.COMMAND
        SkillKind.PASSIVE to 1 -> DamageOrigin.PASSIVE
        else -> throw UnsupportedConfiguredBattleValueException(
            BattleEffectDiagnostic(
                code = EffectFailureCode.UNSUPPORTED_CONFIGURED_VALUE,
                skillId = context.currentSkillId,
                detailId = rule.detailId,
                effectId = rule.effectId,
                trigger = context.trigger,
                callPath = callPath,
                reason = "Unsupported damage origin: skillKind=${rule.skillKind} " +
                    "rawSkillType=${rule.rawSkillType}",
            ),
        )
    }

private fun EffectInvocation.targetState(target: BattleHeroRef): SkillBattleHeroState =
    requireNotNull(context.battleView.state(target)) { "Missing live target state for $target" }

private fun EffectInvocation.liveHero(ref: BattleHeroRef): BattleHero {
    val state = requireNotNull(context.battleView.state(ref)) { "Missing live hero state for $ref" }
    val entry = (if (ref.side == com.stzb.server.game.battle.Side.ATTACKER) {
        context.request.attacker
    } else {
        context.request.defender
    }).heroes.single { it.position == ref.position && it.id == ref.heroId }
    return entry.copy(
        stats = state.stats,
        troops = state.troops,
        maxTroops = state.maxTroops,
        activeStatuses = state.statuses,
        morale = state.morale,
        modifiers = state.modifiers ?: entry.modifiers,
    )
}
