package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleDamageCalculator
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.DamageKind
import kotlin.math.roundToInt

enum class DamageSchool {
    PHYSICAL,
    STRATEGY,
}

enum class DamageCategory {
    NORMAL,
    ACTIVE,
    PURSUIT,
    ONGOING,
    FIRE,
}

data class BattleStatChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val kind: Kind,
    val amount: Int,
    val durationRounds: Int,
    val skillId: Int,
    val effectId: Int,
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

data class TroopDamageChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val amount: Int,
    val troopsAfter: Int,
    val school: DamageSchool,
    val category: DamageCategory,
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

data class ApplyBattleEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val skillId: Int,
    val detailId: Int,
    val effectId: Int,
    val strength: Int,
    val durationRounds: Int,
) : BattleStateChange

data class ScheduledDamageEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val damagePerTick: Int,
    val school: DamageSchool,
    val category: DamageCategory,
    val status: BattleStatus,
    val durationRounds: Int,
    val skillId: Int,
    val effectId: Int,
) : BattleStateChange

data class ScheduledRecoveryEffectChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val amountPerTick: Int,
    val durationRounds: Int,
    val skillId: Int,
    val effectId: Int,
) : BattleStateChange

data class DamageModifierChange(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val direction: Direction,
    val school: DamageSchool?,
    val category: DamageCategory?,
    val percent: Int,
    val durationRounds: Int,
    val skillId: Int,
    val effectId: Int,
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
    fun effectValue(rule: SkillEffectRule, source: BattleHero): Int
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
    override fun effectValue(rule: SkillEffectRule, source: BattleHero): Int {
        val raw = rule.raw
        val encodedScale = when (rule.effectId) {
            in 101..105, in 201..205 ->
                if (raw.constantParam.absoluteValueLong() >= 1_000_000L) 1_000_000.0 else 100.0
            else -> 1.0
        }
        val constant = raw.constantParam / encodedScale
        val intelligence = raw.intelParam / encodedScale
        val intelligenceScaled =
            constant + (source.stats.strategy - BASE_STRATEGY) * intelligence / 1_000.0
        val calculationMultiplier = if (raw.calculationTypes.isEmpty()) {
            1
        } else {
            raw.calculationTypes[source.advanceLevel.coerceIn(0, raw.calculationTypes.lastIndex)]
        }
        return (intelligenceScaled * calculationMultiplier).roundToInt()
    }

    override fun physicalDamage(invocation: EffectInvocation): Int =
        physicalDamage(invocation, selectedTarget(invocation))

    override fun physicalDamage(invocation: EffectInvocation, target: BattleHeroRef): Int {
        val source = invocation.liveHero(invocation.context.source)
        val targetHero = invocation.liveHero(target)
        return BattleDamageCalculator.physical(
            source = source,
            target = targetHero,
            ratePercent = damageRate(invocation.rule, source),
            attributeRandomTenths = 30 + invocation.context.random.nextInt(10),
            category = DamageKind.ACTIVE_SKILL,
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
        val category = when {
            ongoing -> DamageKind.ONGOING
            invocation.rule.effectId == FIRE_ATTACK_EFFECT_ID -> DamageKind.FIRE
            else -> DamageKind.ACTIVE_SKILL
        }
        return BattleDamageCalculator.strategy(
            source = source,
            target = targetHero,
            ratePercent = damageRate(invocation.rule, source),
            ongoing = ongoing,
            category = category,
        )
    }

    override fun recovery(invocation: EffectInvocation): Int {
        val source = invocation.liveHero(invocation.context.source)
        val base = (source.troops * 300.0 / (3_500 + source.troops)).roundToInt()
        val rate = damageRate(invocation.rule, source) / 100.0
        return (base * rate).toInt().coerceAtLeast(0)
    }

    private fun selectedTarget(invocation: EffectInvocation): BattleHeroRef =
        targetSelector.compile(invocation.rule).select(invocation.context).firstOrNull()
            ?: error("No live target for detail=${invocation.rule.detailId}")

    private fun damageRate(rule: SkillEffectRule, source: BattleHero): Int {
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
        return (rate * multiplier).roundToInt().coerceAtLeast(1)
    }

    private fun Int.absoluteValueLong(): Long =
        if (this == Int.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(toLong())

    private companion object {
        const val BASE_STRATEGY = 80.0
        const val FIRE_ATTACK_EFFECT_ID = 307
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
        val targets = targetSelector.compile(invocation.rule).select(invocation.context)
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
        val value = calculator.effectValue(invocation.rule, sourceHero)
        return when (effectId) {
            in 101..106 -> listOf(statChange(invocation, target, value, increase = true))
            in 201..206 -> listOf(statChange(invocation, target, value, increase = false))
            207 -> listOf(appliedEffect(invocation, target, value))
            301 -> listOf(directDamage(invocation, target, DamageSchool.PHYSICAL, DamageCategory.ACTIVE))
            302 -> listOf(directDamage(invocation, target, DamageSchool.STRATEGY, DamageCategory.ACTIVE))
            303 -> listOf(ongoingDamage(invocation, target, BattleStatus.SHAKE, DamageSchool.PHYSICAL))
            304 -> listOf(ongoingDamage(invocation, target, BattleStatus.PANIC, DamageSchool.STRATEGY))
            305 -> listOf(ongoingDamage(invocation, target, BattleStatus.BURN, DamageSchool.STRATEGY))
            306 -> listOf(ongoingDamage(invocation, target, BattleStatus.HEX, DamageSchool.STRATEGY))
            307 -> listOf(directDamage(invocation, target, DamageSchool.STRATEGY, DamageCategory.FIRE))
            401 -> recoveryChanges(invocation, target)
            402 -> scheduledRecoveryChanges(invocation, target)
            in DAMAGE_MODIFIER_EFFECT_IDS -> listOf(damageModifier(invocation, target, value))
            else -> error("Core handler missing effect=$effectId")
        }
    }

    private fun statChange(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        value: Int,
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
            amount = if (increase) value else -value,
            durationRounds = invocation.durationRounds(),
            skillId = invocation.context.currentSkillId,
            effectId = invocation.rule.effectId,
        )
    }

    private fun directDamage(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        school: DamageSchool,
        category: DamageCategory,
    ): TroopDamageChange {
        val targetState = invocation.targetState(target)
        val damage = when (school) {
            DamageSchool.PHYSICAL -> calculator.physicalDamage(invocation, target)
            DamageSchool.STRATEGY -> calculator.strategyDamage(invocation, target, ongoing = false)
        }.coerceAtMost(targetState.troops)
        return TroopDamageChange(
            source = invocation.context.source,
            target = target,
            amount = damage,
            troopsAfter = (targetState.troops - damage).coerceAtLeast(0),
            school = school,
            category = category,
            skillId = invocation.context.currentSkillId,
            effectId = invocation.rule.effectId,
        )
    }

    private fun ongoingDamage(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        status: BattleStatus,
        school: DamageSchool,
    ): ScheduledDamageEffectChange {
        val damage = when (school) {
            DamageSchool.PHYSICAL -> calculator.physicalDamage(invocation, target)
            DamageSchool.STRATEGY -> calculator.strategyDamage(invocation, target, ongoing = true)
        }
        return ScheduledDamageEffectChange(
            source = invocation.context.source,
            target = target,
            damagePerTick = damage,
            school = school,
            category = DamageCategory.ONGOING,
            status = status,
            durationRounds = invocation.durationRounds(),
            skillId = invocation.context.currentSkillId,
            effectId = invocation.rule.effectId,
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
            TroopRecoveryChange(
                source = invocation.context.source,
                target = target,
                amount = amount,
                troopsAfter = state.troops + amount,
                skillId = invocation.context.currentSkillId,
                effectId = invocation.rule.effectId,
            ),
            WoundedPoolChange(
                target = target,
                delta = -amount,
                woundedAfter = state.woundedTroops - amount,
            ),
        )
    }

    private fun scheduledRecoveryChanges(
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
            ScheduledRecoveryEffectChange(
                source = invocation.context.source,
                target = target,
                amountPerTick = amount,
                durationRounds = invocation.durationRounds(),
                skillId = invocation.context.currentSkillId,
                effectId = invocation.rule.effectId,
            ),
        )
    }

    private fun damageModifier(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        value: Int,
    ): DamageModifierChange {
        val effectId = invocation.rule.effectId
        val direction = if (effectId in TAKEN_EFFECT_IDS) {
            DamageModifierChange.Direction.TAKEN
        } else {
            DamageModifierChange.Direction.DEALT
        }
        val sign = if (effectId in REDUCTION_EFFECT_IDS) -1 else 1
        val category = when (effectId) {
            321, 331, 351 -> DamageCategory.NORMAL
            322, 332, 342, 352 -> DamageCategory.ACTIVE
            325, 335, 355 -> DamageCategory.PURSUIT
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
            category = category,
            percent = sign * value,
            durationRounds = invocation.durationRounds(),
            skillId = invocation.context.currentSkillId,
            effectId = effectId,
        )
    }

    private fun appliedEffect(
        invocation: EffectInvocation,
        target: BattleHeroRef,
        strength: Int,
    ) = ApplyBattleEffectChange(
        source = invocation.context.source,
        target = target,
        skillId = invocation.context.currentSkillId,
        detailId = invocation.rule.detailId,
        effectId = invocation.rule.effectId,
        strength = strength,
        durationRounds = invocation.durationRounds(),
    )

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

private fun EffectInvocation.durationRounds(): Int =
    rule.raw.availableRounds.takeIf { it > 0 } ?: 1

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
    )
}
