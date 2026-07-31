package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActionPermission
import com.stzb.server.game.battle.ActiveSkillEffect
import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStat
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
    data class EffectApplied(val spec: PersistentEffectSpec) : BattleStateOutput
    data class EffectRemoved(val effect: ActiveSkillEffect) : BattleStateOutput
    data class EffectExpired(val effect: ActiveSkillEffect) : BattleStateOutput
    data class EffectBlocked(val change: EffectBlockedChange) : BattleStateOutput

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

    data class StatChanged(
        val change: BattleStatChange,
        val strength: Int,
        val delta: Int,
        val valueAfter: Int,
        val deltaExact: Double = delta.toDouble(),
        val valueAfterExact: Double = valueAfter.toDouble(),
    ) : BattleStateOutput

    data class ModifierApplied(
        val change: DamageModifierChange,
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

internal data class EffectKey(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val skillKind: SkillKind,
    val sourceSkillType: Int,
    val detailId: Int,
    val effectId: Int,
    val category: EffectCategory,
    val conflict: Int,
    val replaceType: Int,
    val bindFlag: Int,
    val maxStacks: Int,
    val clearPerHit: Boolean,
    val clearable: Boolean,
)

interface SkillBattleHistoryAdapter {
    fun linkedTarget(source: BattleHeroRef): BattleHeroRef?
    fun currentTarget(source: BattleHeroRef): BattleHeroRef?
    fun previousTarget(source: BattleHeroRef): BattleHeroRef?
}

class SkillBattleState(
    val request: BattleRequest,
    val runtime: SkillRuntimeState,
    initialWoundedTroops: Map<BattleHeroRef, Int> = emptyMap(),
    val effectStore: BattleEffectStore = BattleEffectStore(),
    private val metadataProvider: ((BattleHeroRef) -> SkillBattleHeroMetadata?)? = null,
    private val historyAdapter: SkillBattleHistoryAdapter? = null,
    private val stateFilterMatcher:
        ((SkillTargetStateFilter, BattleHeroRef, BattleHeroRef) -> Boolean)? = null,
) {
    internal data class MutableHeroState(
        val entry: SkillBattleHeroState,
        val inherentStats: BattleStats,
        var stats: BattleStats,
        var troops: Int,
        var woundedTroops: Int,
        var morale: Int,
    )

    private val states = mutableMapOf<BattleHeroRef, MutableHeroState>()
    private val damageDealt = mutableMapOf<BattleHeroRef, Int>()
    internal val effectStatuses = mutableMapOf<EffectKey, BattleStatus>()
    internal val effectModifiers = mutableMapOf<EffectKey, BattleModifier>()

    init {
        request.attacker.heroes.forEach { add(Side.ATTACKER, it, initialWoundedTroops) }
        request.defender.heroes.forEach { add(Side.DEFENDER, it, initialWoundedTroops) }
    }

    val view: SkillBattleView = object : SkillBattleView {
        override val capabilities: Set<SkillBattleViewCapability> = buildSet {
            add(SkillBattleViewCapability.HERO_ROSTER)
            add(SkillBattleViewCapability.ENTRY_STATE)
            add(SkillBattleViewCapability.LIVE_STATE)
            add(SkillBattleViewCapability.DAMAGE_HISTORY)
            add(SkillBattleViewCapability.LIVE_MORALE)
            add(SkillBattleViewCapability.NORMAL_ATTACK_RANGE)
            add(SkillBattleViewCapability.ACTIVE_EFFECTS)
            if (metadataProvider != null) add(SkillBattleViewCapability.HERO_METADATA)
            if (historyAdapter != null) add(SkillBattleViewCapability.TARGET_HISTORY)
            if (stateFilterMatcher != null) add(SkillBattleViewCapability.STATE_FILTERS)
        }

        override fun heroes(): List<BattleHeroRef> = states.keys.toList()

        override fun entryState(ref: BattleHeroRef): SkillBattleHeroState? =
            states[ref]?.entry?.snapshot()

        override fun state(ref: BattleHeroRef): SkillBattleHeroState? =
            states[ref]?.snapshot(ref)

        override fun metadata(ref: BattleHeroRef): SkillBattleHeroMetadata? =
            metadataProvider?.invoke(ref)
                ?: if (metadataProvider == null) {
                    missing(SkillBattleViewCapability.HERO_METADATA, "metadata")
                } else {
                    null
                }

        override fun accumulatedDamageDealt(ref: BattleHeroRef): Int = damageDealt[ref] ?: 0

        override fun currentMorale(ref: BattleHeroRef): Int? = states[ref]?.morale

        override fun currentAttackRange(ref: BattleHeroRef): Int? = states[ref]?.stats?.hitRange

        override fun linkedTarget(source: BattleHeroRef): BattleHeroRef? =
            historyAdapter?.linkedTarget(source)
                ?: if (historyAdapter == null) {
                    missing(SkillBattleViewCapability.TARGET_HISTORY, "linkedTarget")
                } else {
                    null
                }

        override fun currentTarget(source: BattleHeroRef): BattleHeroRef? =
            historyAdapter?.currentTarget(source)
                ?: if (historyAdapter == null) {
                    missing(SkillBattleViewCapability.TARGET_HISTORY, "currentTarget")
                } else {
                    null
                }

        override fun previousTarget(source: BattleHeroRef): BattleHeroRef? =
            historyAdapter?.previousTarget(source)
                ?: if (historyAdapter == null) {
                    missing(SkillBattleViewCapability.TARGET_HISTORY, "previousTarget")
                } else {
                    null
                }

        override fun matchesStateFilter(
            filter: SkillTargetStateFilter,
            source: BattleHeroRef,
            target: BattleHeroRef,
        ): Boolean =
            stateFilterMatcher?.invoke(filter, source, target)
                ?: missing(SkillBattleViewCapability.STATE_FILTERS, "matchesStateFilter")

        override fun activeEffectIds(ref: BattleHeroRef): Set<Int> =
            effectStore.effectsFor(ref).mapTo(mutableSetOf()) { it.effectId }

        private fun <T> missing(
            capability: SkillBattleViewCapability,
            operation: String,
        ): T = throw MissingLiveBattleViewData(capability, operation)
    }

    internal fun contains(ref: BattleHeroRef): Boolean = ref in states

    internal fun seedInitialEffects() {
        states.forEach { (ref, mutable) ->
            mutable.entry.statuses.forEach { status ->
                val effectId = status.initialEffectId() ?: return@forEach
                effectStore.apply(
                    ActiveSkillEffect(
                        source = ref,
                        target = ref,
                        rootSkillId = 1,
                        skillId = 1,
                        skillKind = SkillKind.PASSIVE,
                        sourceSkillType = 1,
                        detailId = -effectId,
                        effectId = effectId,
                        category = EffectCategory.BENEFICIAL,
                        conflict = effectId,
                        strength = 1,
                        replaceType = 0,
                        bindFlag = 0,
                        maxStacks = 1,
                        stacks = 1,
                        remainingRounds = 99,
                        remainingHits = if (status == BattleStatus.EVADE) 1 else null,
                        clearPerHit = status == BattleStatus.EVADE,
                        clearable = false,
                    ),
                )
            }
        }
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
            modifiers = state.snapshot(ref).modifiers ?: entryHero.modifiers,
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
            modifiers = hero.modifiers.toList(),
        )
        states[ref] = MutableHeroState(
            entry,
            hero.inherentStats.copy(),
            entry.stats,
            entry.troops,
            entry.woundedTroops,
            entry.morale,
        )
    }

    private fun teamFor(side: Side) =
        if (side == Side.ATTACKER) request.attacker else request.defender

    private fun MutableHeroState.snapshot(ref: BattleHeroRef) = SkillBattleHeroState(
        stats = stats.copy(),
        troops = troops,
        maxTroops = entry.maxTroops,
        statuses = entry.statuses + effectStore.effectsFor(ref).mapNotNull {
            effectStatuses[it.key()]
        },
        morale = morale,
        attackRange = stats.hitRange,
        canReceiveEffectsWhenDefeated = entry.canReceiveEffectsWhenDefeated,
        woundedTroops = woundedTroops,
        modifiers = entry.modifiers.orEmpty() + effectStore.effectsFor(ref).mapNotNull {
            effectModifiers[it.key()]
        },
    )

    private fun SkillBattleHeroState.snapshot() = copy(
        stats = stats.copy(),
        statuses = statuses.toSet(),
    )

}

private fun BattleStatus.initialEffectId(): Int? = when (this) {
    BattleStatus.CONFUSION -> 501
    BattleStatus.HESITATION -> 502
    BattleStatus.DISARM -> 552
    BattleStatus.INSIGHT -> 511
    BattleStatus.EVADE -> 514
    BattleStatus.IGNORE_EVADE -> 515
    BattleStatus.DOUBLE_ATTACK -> 544
    BattleStatus.FIRST_ACTION -> 761
    else -> null
}

class BattleStateChangeApplier(
    private val state: SkillBattleState,
) {
    private data class StatModifier(
        val kind: BattleStatChange.Kind,
        val unit: BattleEffectValueUnit,
        val sign: Int,
    )

    private data class DamageModifier(
        val direction: DamageModifierChange.Direction,
        val school: DamageSchool?,
        val origin: DamageOrigin?,
        val tag: DamageTag?,
        val sign: Int,
    ) {
        fun matches(change: TroopDamageChange): Boolean =
            direction == DamageModifierChange.Direction.TAKEN &&
                (school == null || school == change.school) &&
                (origin == null || origin == change.origin) &&
                (tag == null || tag in change.tags)
    }

    private data class Redirection(
        val protectedTargets: List<BattleHeroRef>,
        val damageBearer: BattleHeroRef,
    )

    private val statModifiers = mutableMapOf<EffectKey, StatModifier>()
    private val damageModifiers = mutableMapOf<EffectKey, DamageModifier>()
    private data class OngoingDamageBehavior(
        val change: ScheduledDamageEffectChange,
        val sourceSnapshot: BattleHero,
    )

    private val ongoingDamage = mutableMapOf<EffectKey, OngoingDamageBehavior>()
    private val ongoingRecovery = mutableMapOf<EffectKey, ScheduledRecoveryEffectChange>()
    private val redirections = mutableMapOf<EffectKey, Redirection>()
    private var lastStartedRound = 0
    private var lastEndedRound = 0

    fun apply(
        changes: List<BattleStateChange>,
        round: Int,
    ): BattleStateApplyResult = applyValidated(changes, round, delayedActivation = false)

    fun willApply(change: BattleStatChange): Boolean =
        state.effectStore.canApply(statEffect(change))

    fun applyActivated(
        change: ScheduledEffectActivationChange,
        due: SkillTimingDue,
        round: Int,
        hit: Int = 0,
    ): BattleStateApplyResult {
        require(due.change == change) { "Timing due token does not match scheduled activation" }
        require(round > due.dueRound || round == due.dueRound && hit >= due.dueHit) {
            "Activation is early: current=($round,$hit) due=(${due.dueRound},${due.dueHit})"
        }
        val changes = change.activationChanges()
        changes.forEach { preflight(it, delayedActivation = true) }
        due.consume()
        return applyValidated(changes, round, delayedActivation = true)
    }

    private fun applyValidated(
        changes: List<BattleStateChange>,
        round: Int,
        delayedActivation: Boolean,
    ): BattleStateApplyResult {
        require(round >= 0) { "round must not be negative: $round" }
        changes.forEach { preflight(it, delayedActivation) }
        val outputs = mutableListOf<BattleStateOutput>()
        val recovered = mutableMapOf<RecoveryKey, Int>()
        changes.forEach { applyOne(it, outputs, recovered) }
        return BattleStateApplyResult(outputs.toList())
    }

    fun onRoundStart(round: Int): BattleStateApplyResult {
        require(round > 0) { "round must be positive: $round" }
        require(round >= maxOf(lastStartedRound, lastEndedRound)) {
            "round moved backward: current=${maxOf(lastStartedRound, lastEndedRound)} requested=$round"
        }
        if (round == lastStartedRound || round <= lastEndedRound) return BattleStateApplyResult()
        lastStartedRound = round
        pruneInactiveBehaviors()
        val changes = buildList {
            activeEntries(ongoingDamage).forEach { (_, behavior) ->
                    add(
                        behavior.change.tick(
                            liveSource = behavior.sourceSnapshot,
                            liveTarget = state.liveHero(behavior.change.target),
                        ),
                    )
                }
            activeEntries(ongoingRecovery).forEach { (_, change) ->
                    addAll(
                        change.tick(
                            liveState = requireNotNull(state.view.state(change.target)),
                            effectStore = state.effectStore,
                        ),
                    )
                }
        }
        return apply(changes, round)
    }

    fun triggerAppliedOngoingDamage(
        spec: PersistentEffectSpec,
        round: Int,
    ): BattleStateApplyResult {
        val key = spec.toActiveSkillEffectOrNull()?.key() ?: return BattleStateApplyResult()
        val behavior = ongoingDamage[key] ?: return BattleStateApplyResult()
        return apply(
            listOf(
                behavior.change.tick(
                    liveSource = behavior.sourceSnapshot,
                    liveTarget = state.liveHero(behavior.change.target),
                ),
            ),
            round,
        )
    }

    fun onRoundEnd(round: Int): BattleStateApplyResult {
        require(round > 0) { "round must be positive: $round" }
        require(round >= maxOf(lastStartedRound, lastEndedRound)) {
            "round moved backward: current=${maxOf(lastStartedRound, lastEndedRound)} requested=$round"
        }
        if (round == lastEndedRound) return BattleStateApplyResult()
        lastEndedRound = round
        val lifecycle = synchronize(state.effectStore.tick(EffectTickBoundary.ROUND_END))
        recalculateStats()
        return BattleStateApplyResult(lifecycle)
    }

    fun permissionFor(actor: BattleHeroRef): BattleStatePermission {
        val effects = state.effectStore.effectsFor(actor)
        val base: ActionPermission = ActionPermissionResolver(state.effectStore).permissionFor(actor)
        val secondaryAttack = effects.any { it.effectId == 545 }
        val redirect = activeEntries(redirections)
            .asSequence()
            .filter { (_, behavior) -> actor in behavior.protectedTargets }
            .lastOrNull()
            ?.second
            ?.damageBearer
        return BattleStatePermission(
            canAct = base.canAct,
            canCastActive = base.canCastActive,
            canNormalAttack = base.canNormalAttack,
            normalAttackCount = base.normalAttackCount,
            pursuitOpportunityCount = if (base.canNormalAttack) base.normalAttackCount else 0,
            splitAttack = secondaryAttack,
            counterattack = base.counterattack,
            canEvade = ActionPermissionResolver(state.effectStore).canEvade(actor),
            ignoresEvade = effects.any { it.effectId == 515 },
            firstAction = base.firstAction,
            damageRedirectTarget = redirect,
        )
    }

    private fun preflight(
        change: BattleStateChange,
        delayedActivation: Boolean,
    ) {
        when (change) {
            is TroopDamageChange -> {
                requireHero(change.source)
                requireHero(change.target)
                require(change.amount >= 0) { "damage amount must not be negative" }
            }
            is RecoverTroopsChange -> {
                requireHero(change.source)
                requireHero(change.target)
                require(change.amount >= 0) { "recovery amount must not be negative" }
            }
            is TroopRecoveryChange -> {
                requireHero(change.source)
                requireHero(change.target)
                require(change.amount >= 0) { "recovery amount must not be negative" }
            }
            is ConsumeWoundedTroopsChange -> {
                requireHero(change.target)
                require(change.amount >= 0) { "wounded consumption must not be negative" }
            }
            is WoundedPoolChange -> requireHero(change.target)
            is BattleStatChange -> {
                requireHero(change.source)
                requireHero(change.target)
                require(change.durationRounds > 0) { "stat duration must be positive" }
                require(
                    change.potency.unit == BattleEffectValueUnit.FLAT ||
                        change.potency.unit == BattleEffectValueUnit.PERCENT,
                ) { "stat potency must be flat or percent" }
                require(change.potency.value != 0) { "stat potency must not be zero" }
                statEffect(change)
            }
            is DamageModifierChange -> {
                requireHero(change.source)
                requireHero(change.target)
                require(change.durationRounds > 0) { "damage modifier duration must be positive" }
                require(change.percent != 0) { "damage modifier percent must not be zero" }
                modifierEffect(change)
            }
            is ApplyBattleEffectChange -> validateSpec(change.spec, delayedActivation)
            is ScheduledDamageEffectChange -> validateSpec(change.spec, delayedActivation)
            is ScheduledRecoveryEffectChange -> validateSpec(change.spec, delayedActivation)
            is ActionEffectChange -> validateSpec(change.spec, delayedActivation)
            is DamageRedirectionEffectChange -> {
                validateSpec(change.spec, delayedActivation)
                change.protectedTargets.forEach(::requireHero)
                requireHero(change.damageBearer)
            }
            is CancelPreparedSkillsChange -> validateSpec(change.spec, delayedActivation)
            is CleanseEffectsChange -> validateSpec(change.spec, delayedActivation)
            is ScheduledEffectActivationChange ->
                throw IllegalArgumentException(
                    "ScheduledEffectActivationChange must be expanded through applyActivated at its due boundary",
                )
            is EffectBlockedChange -> {
                requireHero(change.source)
                requireHero(change.target)
            }
            is ClearReferencedEffectChange -> {
                requireHero(change.source)
                requireHero(change.target)
            }
            is ReduceReferencedEffectUseChange -> {
                requireHero(change.source)
                requireHero(change.target)
                require(change.amount >= 0) { "referenced effect use reduction must not be negative" }
            }
            is MoraleEffectChange -> {
                requireHero(change.source)
                requireHero(change.target)
            }
            else -> throw UnsupportedBattleStateChangeException(change)
        }
    }

    private fun applyOne(
        change: BattleStateChange,
        outputs: MutableList<BattleStateOutput>,
        recovered: MutableMap<RecoveryKey, Int>,
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
                recovered,
            )
            is TroopRecoveryChange -> applyRecovery(
                change.source,
                change.target,
                change.amount,
                change.skillId,
                change.effectId,
                false,
                outputs,
                recovered,
            )
            is ConsumeWoundedTroopsChange -> {
                val target = state.mutable(change.target)
                val key = RecoveryKey(change.target, change.skillId, change.effectId)
                val pairedRecovery = recovered[key]
                val amount = minOf(
                    change.amount,
                    target.woundedTroops,
                    pairedRecovery ?: change.amount,
                )
                target.woundedTroops -= amount
                if (pairedRecovery != null) recovered[key] = (pairedRecovery - amount).coerceAtLeast(0)
            }
            is WoundedPoolChange -> {
                val target = state.mutable(change.target)
                target.woundedTroops = (target.woundedTroops + change.delta).coerceAtLeast(0)
            }
            is BattleStatChange -> {
                val valueBefore = state.mutable(change.target).stats.preciseValue(change.kind)
                val effect = statEffect(change)
                val accepted = applyBehavior(effect, outputs) { key, _ ->
                    statModifiers[key] = StatModifier(
                        change.kind,
                        change.potency.unit,
                        if (change.potency.value < 0) -1 else 1,
                    )
                }
                if (accepted) {
                    recalculateStats(change.target)
                    val valueAfter = state.mutable(change.target).stats.preciseValue(change.kind)
                    outputs += BattleStateOutput.StatChanged(
                        change = change,
                        strength = kotlin.math.abs(change.potency.value),
                        delta = (valueAfter - valueBefore).toInt(),
                        valueAfter = valueAfter.toInt(),
                        deltaExact = valueAfter - valueBefore,
                        valueAfterExact = valueAfter,
                    )
                }
            }
            is DamageModifierChange -> {
                val effect = modifierEffect(change)
                val accepted = applyBehavior(effect, outputs) { key, _ ->
                    damageModifiers[key] = DamageModifier(
                        change.direction,
                        change.school,
                        change.origin,
                        change.tag,
                        if (change.percent < 0) -1 else 1,
                    )
                    state.effectModifiers[key] = change.toBattleModifier()
                }
                if (accepted) outputs += BattleStateOutput.ModifierApplied(change)
            }
            is ApplyBattleEffectChange -> applyEffect(change.spec, outputs) { key, _ ->
                statusFor(change.spec.effectId)?.let { state.effectStatuses[key] = it }
            }
            is ScheduledDamageEffectChange -> {
                applyEffect(change.spec, outputs) { key, _ ->
                    ongoingDamage[key] = OngoingDamageBehavior(
                        change,
                        state.liveHero(change.source),
                    )
                    state.effectStatuses[key] = change.status
                }
            }
            is ScheduledRecoveryEffectChange -> {
                applyEffect(change.spec, outputs) { key, _ ->
                    ongoingRecovery[key] = change
                }
            }
            is ActionEffectChange -> applyEffect(change.spec, outputs) { key, _ ->
                statusFor(change.spec.effectId)?.let { state.effectStatuses[key] = it }
            }
            is DamageRedirectionEffectChange -> {
                applyEffect(change.spec, outputs) { key, _ ->
                    redirections[key] = Redirection(
                        change.protectedTargets.toList(),
                        change.damageBearer,
                    )
                }
            }
            is CancelPreparedSkillsChange -> change.apply(state.runtime)
            is CleanseEffectsChange -> {
                outputs += synchronize(change.apply(state.effectStore))
                recalculateStats()
            }
            is EffectBlockedChange -> outputs += BattleStateOutput.EffectBlocked(change)
            is ClearReferencedEffectChange -> {
                outputs += synchronize(change.apply(state.effectStore))
                recalculateStats(change.target)
            }
            is ReduceReferencedEffectUseChange -> {
                outputs += synchronize(change.apply(state.effectStore))
                recalculateStats(change.target)
            }
            is MoraleEffectChange -> {
                val target = state.mutable(change.target)
                target.morale = (target.morale + change.delta).coerceAtLeast(0)
            }
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
        target.woundedTroops += amount
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
        activeEntries(damageModifiers)
            .filter { (key, modifier) ->
                key.target == change.target &&
                    modifier.matches(change) &&
                    state.effectStore.effectsFor(key.target)
                        .singleOrNull { it.key() == key }
                        ?.remainingHits != null
            }
            .map { it.first }
            .forEach { key ->
                outputs += synchronize(
                    state.effectStore.consumeHit(
                        target = key.target,
                        effectId = key.effectId,
                        source = key.source,
                        detailId = key.detailId,
                    ),
                )
            }
    }

    private fun applyRecovery(
        source: BattleHeroRef,
        targetRef: BattleHeroRef,
        requestedAmount: Int,
        skillId: Int,
        effectId: Int,
        limitedByWounded: Boolean,
        outputs: MutableList<BattleStateOutput>,
        recovered: MutableMap<RecoveryKey, Int>,
    ) {
        val target = state.mutable(targetRef)
        val room = (target.entry.maxTroops - target.troops).coerceAtLeast(0)
        val limit = if (limitedByWounded) target.woundedTroops else Int.MAX_VALUE
        val amount = minOf(requestedAmount.coerceAtLeast(0), room, limit)
        target.troops += amount
        recovered[RecoveryKey(targetRef, skillId, effectId)] =
            (recovered[RecoveryKey(targetRef, skillId, effectId)] ?: 0) + amount
        outputs += BattleStateOutput.TroopsRecovered(source, targetRef, amount, skillId, effectId)
    }

    private fun applyEffect(
        spec: PersistentEffectSpec,
        outputs: MutableList<BattleStateOutput>,
        onAccepted: (EffectKey, ActiveSkillEffect) -> Unit = { _, _ -> },
    ) {
        spec.toActiveSkillEffectOrNull()?.let { effect ->
            if (applyBehavior(effect, outputs, onAccepted)) {
                outputs += BattleStateOutput.EffectApplied(spec)
            }
        }
    }

    private fun applyBehavior(
        effect: ActiveSkillEffect,
        outputs: MutableList<BattleStateOutput>,
        onAccepted: (EffectKey, ActiveSkillEffect) -> Unit,
    ): Boolean {
        val result = state.effectStore.apply(effect)
        synchronizeRemoved(result.removed)
        outputs += result.removed.map(BattleStateOutput::EffectRemoved)
        if (result.outcome == EffectApplyOutcome.REJECTED) return false
        val accepted = requireNotNull(result.effect)
        val key = accepted.key()
        if (result.outcome == EffectApplyOutcome.STACKED ||
            result.outcome == EffectApplyOutcome.REFRESHED
        ) {
            removeBehavior(key)
        }
        onAccepted(key, accepted)
        return true
    }

    private fun statEffect(change: BattleStatChange): ActiveSkillEffect =
        PersistentEffectSpec(
            source = change.source,
            target = change.target,
            rootSkillId = change.skillId,
            skillId = change.skillId,
            skillKind = SkillKind.COMMAND,
            rawSkillType = 2,
            detailId = change.detailId,
            effectId = change.effectId,
            category =
                if (change.potency.value > 0) EffectCategory.BENEFICIAL else EffectCategory.HARMFUL,
            conflict = 0,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = change.maxStacks,
            delayRound = 0,
            delayHit = 0,
            availableRounds = change.durationRounds,
            availableHit = 0,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = change.potency,
        ).toActiveSkillEffect()

    private fun recalculateStats(target: BattleHeroRef? = null) {
        pruneInactiveBehaviors()
        val targets = target?.let(::listOf) ?: state.view.heroes()
        targets.forEach { ref ->
            val mutable = state.mutable(ref)
            val entry = mutable.entry.stats
            val inherent = mutable.inherentStats
            val values = BattleStatChange.Kind.entries.associateWith { kind ->
                val base = entry.preciseValue(kind)
                val percentBase = inherent.preciseValue(kind)
                val modifiers = activeEntries(statModifiers)
                    .filter { (key, modifier) -> key.target == ref && modifier.kind == kind }
                val flat = modifiers
                    .filter { (_, modifier) -> modifier.unit == BattleEffectValueUnit.FLAT }
                    .sumOf { (key, modifier) -> key.strength() * modifier.sign }
                val percent = modifiers
                    .filter { (_, modifier) -> modifier.unit == BattleEffectValueUnit.PERCENT }
                    .sumOf { (key, modifier) -> key.strength() * modifier.sign }
                base + percentBase * percent / 100.0 + flat
            }
            mutable.stats = BattleStats.fromHundredths(
                attack = (values.getValue(BattleStatChange.Kind.ATTACK) * 100).roundToInt(),
                defense = (values.getValue(BattleStatChange.Kind.DEFENSE) * 100).roundToInt(),
                strategy = (values.getValue(BattleStatChange.Kind.STRATEGY) * 100).roundToInt(),
                speed = (values.getValue(BattleStatChange.Kind.SPEED) * 100).roundToInt(),
                siege = (values.getValue(BattleStatChange.Kind.SIEGE) * 100).roundToInt(),
                hitRange = values.getValue(BattleStatChange.Kind.ATTACK_RANGE).roundToInt(),
            )
        }
    }

    private data class RecoveryKey(
        val target: BattleHeroRef,
        val skillId: Int,
        val effectId: Int,
    )

    private fun requireHero(ref: BattleHeroRef) {
        require(state.contains(ref)) { "Unknown battle hero: $ref" }
    }

    private fun validateSpec(
        spec: PersistentEffectSpec,
        delayedActivation: Boolean,
    ) {
        requireHero(spec.source)
        requireHero(spec.target)
        if (spec.startBoundary == EffectStartBoundary.AFTER_DELAY && !delayedActivation) {
            throw IllegalArgumentException(
                "Effect detail=${spec.detailId} is AFTER_DELAY and cannot apply before activation",
            )
        }
        spec.toActiveSkillEffectOrNull()
    }

    private fun modifierEffect(change: DamageModifierChange): ActiveSkillEffect =
        PersistentEffectSpec(
            source = change.source,
            target = change.target,
            rootSkillId = change.skillId,
            skillId = change.skillId,
            skillKind = SkillKind.COMMAND,
            rawSkillType = 2,
            detailId = change.detailId,
            effectId = change.effectId,
            category =
                when (change.direction) {
                    DamageModifierChange.Direction.DEALT ->
                        if (change.percent > 0) EffectCategory.BENEFICIAL else EffectCategory.HARMFUL
                    DamageModifierChange.Direction.TAKEN ->
                        if (change.percent > 0) EffectCategory.HARMFUL else EffectCategory.BENEFICIAL
                },
            conflict = 0,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = change.durationRounds,
            availableHit = change.availableHits,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = TypedBattlePotency.percent(change.percent),
        ).toActiveSkillEffect()

    private fun DamageModifierChange.toBattleModifier(): BattleModifier =
        when (direction) {
            DamageModifierChange.Direction.DEALT -> BattleModifier.DamageDealtPercent(
                school = school,
                origin = origin,
                tag = tag,
                percent = percent,
            )
            DamageModifierChange.Direction.TAKEN -> BattleModifier.DamageTakenPercent(
                school = school,
                origin = origin,
                tag = tag,
                percent = percent,
            )
        }

    private fun synchronize(result: EffectLifecycleResult): List<BattleStateOutput> {
        synchronizeRemoved(result.expired + result.removed)
        pruneInactiveBehaviors()
        return result.expired.map(BattleStateOutput::EffectExpired) +
            result.removed.map(BattleStateOutput::EffectRemoved)
    }

    private fun synchronizeRemoved(removed: List<ActiveSkillEffect>) {
        removed.forEach { removeBehavior(it.key()) }
    }

    private fun removeBehavior(key: EffectKey) {
        statModifiers.remove(key)
        damageModifiers.remove(key)
        ongoingDamage.remove(key)
        ongoingRecovery.remove(key)
        redirections.remove(key)
        state.effectStatuses.remove(key)
        state.effectModifiers.remove(key)
    }

    private fun pruneInactiveBehaviors() {
        val activeKeys = state.view.heroes()
            .flatMap(state.effectStore::effectsFor)
            .mapTo(mutableSetOf()) { it.key() }
        (
            statModifiers.keys + damageModifiers.keys + ongoingDamage.keys +
                ongoingRecovery.keys + redirections.keys + state.effectStatuses.keys +
                state.effectModifiers.keys
            )
            .filterNot { it in activeKeys }
            .forEach(::removeBehavior)
    }

    private fun <T> activeEntries(
        behaviors: Map<EffectKey, T>,
    ): List<Pair<EffectKey, T>> {
        val effects = state.view.heroes()
            .flatMap(state.effectStore::effectsFor)
            .associateBy { it.key() }
        return behaviors.mapNotNull { (key, behavior) ->
            effects[key]?.let { key to behavior }
        }
    }

    private fun EffectKey.strength(): Int =
        state.effectStore.effectsFor(target)
            .singleOrNull { it.key() == this }
            ?.effectiveStrength
            ?: 0

    private fun statusFor(effectId: Int): BattleStatus? = when (effectId) {
        501, 701, 901 -> BattleStatus.CONFUSION
        502, 702, 902 -> BattleStatus.HESITATION
        511, 711 -> BattleStatus.INSIGHT
        514, 714 -> BattleStatus.EVADE
        515 -> BattleStatus.IGNORE_EVADE
        544, 744 -> BattleStatus.DOUBLE_ATTACK
        552, 752, 952 -> BattleStatus.DISARM
        761 -> BattleStatus.FIRST_ACTION
        else -> null
    }

    private fun BattleStats.value(kind: BattleStatChange.Kind): Int = when (kind) {
        BattleStatChange.Kind.ATTACK -> attack
        BattleStatChange.Kind.DEFENSE -> defense
        BattleStatChange.Kind.STRATEGY -> strategy
        BattleStatChange.Kind.SPEED -> speed
        BattleStatChange.Kind.SIEGE -> siege
        BattleStatChange.Kind.ATTACK_RANGE -> hitRange
    }

    private fun BattleStats.preciseValue(kind: BattleStatChange.Kind): Double = when (kind) {
        BattleStatChange.Kind.ATTACK -> precise(BattleStat.ATTACK)
        BattleStatChange.Kind.DEFENSE -> precise(BattleStat.DEFENSE)
        BattleStatChange.Kind.STRATEGY -> precise(BattleStat.STRATEGY)
        BattleStatChange.Kind.SPEED -> precise(BattleStat.SPEED)
        BattleStatChange.Kind.SIEGE -> precise(BattleStat.SIEGE)
        BattleStatChange.Kind.ATTACK_RANGE -> hitRange.toDouble()
    }
}

private fun ActiveSkillEffect.key(): EffectKey =
    EffectKey(
        source = source,
        target = target,
        rootSkillId = rootSkillId,
        skillId = skillId,
        skillKind = skillKind,
        sourceSkillType = sourceSkillType,
        detailId = detailId,
        effectId = effectId,
        category = category,
        conflict = conflict,
        replaceType = replaceType,
        bindFlag = bindFlag,
        maxStacks = maxStacks,
        clearPerHit = clearPerHit,
        clearable = clearable,
    )
