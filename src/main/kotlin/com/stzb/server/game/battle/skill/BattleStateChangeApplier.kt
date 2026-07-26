package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActionPermission
import com.stzb.server.game.battle.ActiveSkillEffect
import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.DamageTag
import com.stzb.server.game.battle.EffectCategory
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillKind
import kotlin.math.roundToInt

class UnsupportedBattleStateChangeException(
    change: BattleStateChange,
) : IllegalArgumentException(
    "Unsupported battle-state change: ${change::class.qualifiedName ?: change::class.simpleName}",
)

sealed interface BattleStateOutput {
    data class DamageDealt(
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val amount: Int,
        val school: DamageSchool,
        val origin: DamageOrigin,
        val tags: Set<DamageTag>,
        val skillId: Int,
        val effectId: Int,
    ) : BattleStateOutput

    data class HurtReceived(
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val amount: Int,
        val school: DamageSchool,
        val origin: DamageOrigin,
        val tags: Set<DamageTag>,
        val skillId: Int,
        val effectId: Int,
    ) : BattleStateOutput

    data class TroopsRecovered(
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val amount: Int,
        val skillId: Int,
        val effectId: Int,
    ) : BattleStateOutput
}

data class BattleStateApplyResult(
    val outputs: List<BattleStateOutput> = emptyList(),
)

data class BattleStatePermission(
    val canAct: Boolean = true,
    val canCastActive: Boolean = true,
    val canNormalAttack: Boolean = true,
    val normalAttackCount: Int = 1,
    val pursuitOpportunityCount: Int = 1,
    val splitAttack: Boolean = false,
    val counterattack: Boolean = false,
    val canEvade: Boolean = false,
    val ignoresEvade: Boolean = false,
    val firstAction: Boolean = false,
    val damageRedirectTarget: BattleHeroRef? = null,
)

class SkillBattleState(
    val request: BattleRequest,
    val runtime: SkillRuntimeState,
    initialWoundedTroops: Map<BattleHeroRef, Int> = emptyMap(),
    val effectStore: BattleEffectStore = BattleEffectStore(),
) {
    internal data class MutableHeroState(
        val entry: SkillBattleHeroState,
        var stats: BattleStats,
        var troops: Int,
        var woundedTroops: Int,
    )

    private val states = mutableMapOf<BattleHeroRef, MutableHeroState>()
    private val damageDealt = mutableMapOf<BattleHeroRef, Int>()

    init {
        request.attacker.heroes.forEach { add(Side.ATTACKER, it, initialWoundedTroops) }
        request.defender.heroes.forEach { add(Side.DEFENDER, it, initialWoundedTroops) }
    }

    val view: SkillBattleView = object : SkillBattleView {
        override val capabilities: Set<SkillBattleViewCapability> =
            SkillBattleViewCapability.entries.toSet()

        override fun heroes(): List<BattleHeroRef> = states.keys.toList()

        override fun entryState(ref: BattleHeroRef): SkillBattleHeroState? =
            states[ref]?.entry?.snapshot()

        override fun state(ref: BattleHeroRef): SkillBattleHeroState? =
            states[ref]?.snapshot(ref)

        override fun metadata(ref: BattleHeroRef): SkillBattleHeroMetadata? = null

        override fun accumulatedDamageDealt(ref: BattleHeroRef): Int = damageDealt[ref] ?: 0

        override fun currentMorale(ref: BattleHeroRef): Int? = states[ref]?.entry?.morale

        override fun currentAttackRange(ref: BattleHeroRef): Int? = states[ref]?.stats?.hitRange

        override fun linkedTarget(source: BattleHeroRef): BattleHeroRef? = null

        override fun currentTarget(source: BattleHeroRef): BattleHeroRef? = null

        override fun previousTarget(source: BattleHeroRef): BattleHeroRef? = null

        override fun matchesStateFilter(
            filter: SkillTargetStateFilter,
            source: BattleHeroRef,
            target: BattleHeroRef,
        ): Boolean = false

        override fun activeEffectIds(ref: BattleHeroRef): Set<Int> =
            effectStore.effectsFor(ref).mapTo(mutableSetOf()) { it.effectId }
    }

    internal fun mutable(ref: BattleHeroRef): MutableHeroState =
        requireNotNull(states[ref]) { "Unknown battle hero: $ref" }

    internal fun recordDamage(source: BattleHeroRef, amount: Int) {
        damageDealt[source] = (damageDealt[source] ?: 0) + amount
    }

    internal fun liveHero(ref: BattleHeroRef): BattleHero {
        val state = mutable(ref)
        val entryHero = teamFor(ref.side).heroes.single {
            it.position == ref.position && it.id == ref.heroId
        }
        return entryHero.copy(
            stats = state.stats,
            troops = state.troops,
            activeStatuses = state.snapshot(ref).statuses,
        )
    }

    private fun add(
        side: Side,
        hero: BattleHero,
        woundedTroops: Map<BattleHeroRef, Int>,
    ) {
        val ref = BattleHeroRef(side, hero.position, hero.id)
        val entry = SkillBattleHeroState(
            stats = hero.stats.copy(),
            troops = hero.troops,
            maxTroops = hero.maxTroops,
            statuses = hero.activeStatuses.toSet(),
            morale = hero.morale,
            attackRange = hero.stats.hitRange,
            woundedTroops = woundedTroops[ref]?.coerceAtLeast(0) ?: 0,
        )
        states[ref] = MutableHeroState(entry, entry.stats, entry.troops, entry.woundedTroops)
    }

    private fun teamFor(side: Side) =
        if (side == Side.ATTACKER) request.attacker else request.defender

    private fun MutableHeroState.snapshot(ref: BattleHeroRef) = SkillBattleHeroState(
        stats = stats.copy(),
        troops = troops,
        maxTroops = entry.maxTroops,
        statuses = entry.statuses + effectStore.effectsFor(ref).mapNotNull { statusFor(it.effectId) },
        morale = entry.morale,
        attackRange = stats.hitRange,
        canReceiveEffectsWhenDefeated = entry.canReceiveEffectsWhenDefeated,
        woundedTroops = woundedTroops,
    )

    private fun SkillBattleHeroState.snapshot() = copy(
        stats = stats.copy(),
        statuses = statuses.toSet(),
    )

    private fun statusFor(effectId: Int): BattleStatus? = when (effectId) {
        501, 701, 901 -> BattleStatus.CONFUSION
        502, 702, 902 -> BattleStatus.HESITATION
        514, 714 -> BattleStatus.EVADE
        515 -> BattleStatus.IGNORE_EVADE
        544, 744 -> BattleStatus.DOUBLE_ATTACK
        552, 752, 952 -> BattleStatus.DISARM
        761 -> BattleStatus.FIRST_ACTION
        else -> null
    }
}

class BattleStateChangeApplier(
    private val state: SkillBattleState,
) {
    private data class StatModifier(
        val target: BattleHeroRef,
        val kind: BattleStatChange.Kind,
        val potency: TypedBattlePotency.Resolved,
        val expiresAfterRound: Int,
    )

    private data class OngoingDamage(
        val change: ScheduledDamageEffectChange,
        val expiresAfterRound: Int,
    )

    private data class OngoingRecovery(
        val change: ScheduledRecoveryEffectChange,
        val expiresAfterRound: Int,
    )

    private val statModifiers = mutableListOf<StatModifier>()
    private val ongoingDamage = mutableListOf<OngoingDamage>()
    private val ongoingRecovery = mutableListOf<OngoingRecovery>()
    private val redirections = mutableMapOf<BattleHeroRef, BattleHeroRef>()

    fun apply(
        changes: List<BattleStateChange>,
        round: Int,
    ): BattleStateApplyResult {
        changes.forEach(::preflight)
        val outputs = mutableListOf<BattleStateOutput>()
        changes.forEach { applyOne(it, round, outputs) }
        return BattleStateApplyResult(outputs.toList())
    }

    fun onRoundStart(round: Int): BattleStateApplyResult {
        val changes = buildList {
            ongoingDamage
                .filter { round <= it.expiresAfterRound }
                .forEach {
                    add(
                        it.change.tick(
                            liveSource = state.liveHero(it.change.source),
                            liveTarget = state.liveHero(it.change.target),
                        ),
                    )
                }
            ongoingRecovery
                .filter { round <= it.expiresAfterRound }
                .forEach {
                    addAll(
                        it.change.tick(
                            liveState = requireNotNull(state.view.state(it.change.target)),
                            effectStore = state.effectStore,
                        ),
                    )
                }
        }
        return apply(changes, round)
    }

    fun onRoundEnd(round: Int): BattleStateApplyResult {
        state.effectStore.tick(EffectTickBoundary.ROUND_END)
        statModifiers.removeAll { it.expiresAfterRound <= round }
        ongoingDamage.removeAll { it.expiresAfterRound <= round }
        ongoingRecovery.removeAll { it.expiresAfterRound <= round }
        recalculateStats()
        return BattleStateApplyResult()
    }

    fun permissionFor(actor: BattleHeroRef): BattleStatePermission {
        val effects = state.effectStore.effectsFor(actor)
        val base: ActionPermission = CompleteSkillEngine(state.effectStore).permissionFor(actor)
        val secondaryAttack = effects.any { it.effectId == 545 }
        return BattleStatePermission(
            canAct = base.canAct,
            canCastActive = base.canCastActive,
            canNormalAttack = base.canNormalAttack,
            normalAttackCount = base.normalAttackCount,
            pursuitOpportunityCount = if (base.canNormalAttack) base.normalAttackCount else 0,
            splitAttack = secondaryAttack,
            counterattack = base.counterattack,
            canEvade = CompleteSkillEngine(state.effectStore).canEvade(actor),
            ignoresEvade = effects.any { it.effectId == 515 },
            firstAction = base.firstAction,
            damageRedirectTarget = redirections[actor],
        )
    }

    private fun preflight(change: BattleStateChange) {
        when (change) {
            is TroopDamageChange,
            is RecoverTroopsChange,
            is TroopRecoveryChange,
            is ConsumeWoundedTroopsChange,
            is WoundedPoolChange,
            is BattleStatChange,
            is ApplyBattleEffectChange,
            is ScheduledDamageEffectChange,
            is ScheduledRecoveryEffectChange,
            is ActionEffectChange,
            is DamageRedirectionEffectChange,
            is CancelPreparedSkillsChange,
            is CleanseEffectsChange,
            is EffectBlockedChange,
            -> Unit
            else -> throw UnsupportedBattleStateChangeException(change)
        }
    }

    private fun applyOne(
        change: BattleStateChange,
        round: Int,
        outputs: MutableList<BattleStateOutput>,
    ) {
        when (change) {
            is TroopDamageChange -> applyDamage(change, outputs)
            is RecoverTroopsChange -> applyRecovery(
                change.source,
                change.target,
                change.amount,
                change.skillId,
                change.effectId,
                true,
                outputs,
            )
            is TroopRecoveryChange -> applyRecovery(
                change.source,
                change.target,
                change.amount,
                change.skillId,
                change.effectId,
                false,
                outputs,
            )
            is ConsumeWoundedTroopsChange -> {
                val target = state.mutable(change.target)
                target.woundedTroops =
                    (target.woundedTroops - change.amount.coerceAtLeast(0)).coerceAtLeast(0)
            }
            is WoundedPoolChange -> {
                val target = state.mutable(change.target)
                target.woundedTroops = (target.woundedTroops + change.delta).coerceAtLeast(0)
            }
            is BattleStatChange -> {
                statModifiers += StatModifier(
                    change.target,
                    change.kind,
                    change.potency,
                    round + change.durationRounds,
                )
                state.effectStore.apply(statEffect(change))
                recalculateStats(change.target)
            }
            is ApplyBattleEffectChange -> applyEffect(change.spec)
            is ScheduledDamageEffectChange -> {
                applyEffect(change.spec)
                ongoingDamage += OngoingDamage(change, round + change.durationRounds)
            }
            is ScheduledRecoveryEffectChange -> {
                applyEffect(change.spec)
                ongoingRecovery += OngoingRecovery(change, round + change.durationRounds)
            }
            is ActionEffectChange -> applyEffect(change.spec)
            is DamageRedirectionEffectChange -> {
                applyEffect(change.spec)
                change.protectedTargets.forEach { redirections[it] = change.damageBearer }
            }
            is CancelPreparedSkillsChange -> change.apply(state.runtime)
            is CleanseEffectsChange -> change.apply(state.effectStore)
            is EffectBlockedChange -> Unit
            else -> throw UnsupportedBattleStateChangeException(change)
        }
    }

    private fun applyDamage(
        change: TroopDamageChange,
        outputs: MutableList<BattleStateOutput>,
    ) {
        val target = state.mutable(change.target)
        val amount = change.amount.coerceAtLeast(0).coerceAtMost(target.troops)
        target.troops -= amount
        state.recordDamage(change.source, amount)
        outputs += BattleStateOutput.DamageDealt(
            change.source,
            change.target,
            amount,
            change.school,
            change.origin,
            change.tags.toSet(),
            change.skillId,
            change.effectId,
        )
        outputs += BattleStateOutput.HurtReceived(
            change.source,
            change.target,
            amount,
            change.school,
            change.origin,
            change.tags.toSet(),
            change.skillId,
            change.effectId,
        )
    }

    private fun applyRecovery(
        source: BattleHeroRef,
        targetRef: BattleHeroRef,
        requestedAmount: Int,
        skillId: Int,
        effectId: Int,
        limitedByWounded: Boolean,
        outputs: MutableList<BattleStateOutput>,
    ) {
        val target = state.mutable(targetRef)
        val room = (target.entry.maxTroops - target.troops).coerceAtLeast(0)
        val limit = if (limitedByWounded) target.woundedTroops else Int.MAX_VALUE
        val amount = minOf(requestedAmount.coerceAtLeast(0), room, limit)
        target.troops += amount
        outputs += BattleStateOutput.TroopsRecovered(source, targetRef, amount, skillId, effectId)
    }

    private fun applyEffect(spec: PersistentEffectSpec) {
        spec.toActiveSkillEffectOrNull()?.let(state.effectStore::apply)
    }

    private fun statEffect(change: BattleStatChange): ActiveSkillEffect =
        PersistentEffectSpec(
            source = change.source,
            target = change.target,
            rootSkillId = change.skillId,
            skillId = change.skillId,
            skillKind = SkillKind.COMMAND,
            rawSkillType = 2,
            detailId = change.skillId * 10_000 + change.effectId,
            effectId = change.effectId,
            category = EffectCategory.BENEFICIAL,
            conflict = 0,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = change.durationRounds,
            availableHit = 0,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = change.potency,
        ).toActiveSkillEffect()

    private fun recalculateStats(target: BattleHeroRef? = null) {
        val targets = target?.let(::listOf) ?: state.view.heroes()
        targets.forEach { ref ->
            val mutable = state.mutable(ref)
            val entry = mutable.entry.stats
            val values = BattleStatChange.Kind.entries.associateWith { kind ->
                val base = entry.value(kind)
                val modifiers = statModifiers.filter { it.target == ref && it.kind == kind }
                val flat = modifiers
                    .filter { it.potency.unit == BattleEffectValueUnit.FLAT }
                    .sumOf { it.potency.value }
                val percent = modifiers
                    .filter { it.potency.unit == BattleEffectValueUnit.PERCENT }
                    .sumOf { it.potency.value }
                (base + base * percent / 100.0 + flat).roundToInt()
            }
            mutable.stats = BattleStats(
                attack = values.getValue(BattleStatChange.Kind.ATTACK),
                defense = values.getValue(BattleStatChange.Kind.DEFENSE),
                strategy = values.getValue(BattleStatChange.Kind.STRATEGY),
                speed = values.getValue(BattleStatChange.Kind.SPEED),
                siege = values.getValue(BattleStatChange.Kind.SIEGE),
                hitRange = values.getValue(BattleStatChange.Kind.ATTACK_RANGE),
            )
        }
    }

    private fun BattleStats.value(kind: BattleStatChange.Kind): Int = when (kind) {
        BattleStatChange.Kind.ATTACK -> attack
        BattleStatChange.Kind.DEFENSE -> defense
        BattleStatChange.Kind.STRATEGY -> strategy
        BattleStatChange.Kind.SPEED -> speed
        BattleStatChange.Kind.SIEGE -> siege
        BattleStatChange.Kind.ATTACK_RANGE -> hitRange
    }
}
