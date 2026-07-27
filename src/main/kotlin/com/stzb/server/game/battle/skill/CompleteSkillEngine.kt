package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActionPermission
import com.stzb.server.game.battle.BattleActionResolver
import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleDamageCalculator
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleStat
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.DamageTag
import com.stzb.server.game.battle.SkillKind

interface CompleteSkillEngine {
    fun prepareBattle(context: SkillBattleContext): List<BattleEvent>
    fun trigger(trigger: BattleTrigger, context: SkillBattleContext): List<BattleEvent>
    fun permissionFor(actor: BattleHeroRef, context: SkillBattleContext): ActionPermission
}

class DefaultCompleteSkillEngine private constructor(
    val state: SkillBattleState,
    private val graph: SkillRuleGraph,
    private val interpreter: SkillRuleInterpreter,
    private val timing: CompleteTimingCoordinator,
    private val applier: BattleStateChangeApplier,
    private val specialPlugins: SpecialSkillPluginRegistry,
) : CompleteSkillEngine {
    private var prepared = false
    private val actionResolver = BattleActionResolver()
    private val cooldownUntilRound = mutableMapOf<Pair<BattleHeroRef, Int>, Int>()

    override fun prepareBattle(context: SkillBattleContext): List<BattleEvent> {
        if (prepared) return emptyList()
        prepared = true
        return buildList {
            val sources = livingHeroesInSpeedOrder()
            sources.forEach { source ->
                addAll(trigger(BattleTrigger.BATTLE_PASSIVE, context.copy(source = source)))
            }
            sources.forEach { source ->
                addAll(trigger(BattleTrigger.BATTLE_COMMAND, context.copy(source = source)))
            }
        }
    }

    override fun trigger(
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): List<BattleEvent> {
        val scoped = context.copy(
            runtime = state.runtime,
            trigger = trigger,
            battleView = state.view,
        )
        val events = mutableListOf<BattleEvent>()
        if (trigger.emitsPoint()) {
            events += BattleEvent.TriggerPoint(scoped.round, scoped.source, trigger)
        }
        val result = when (trigger) {
            BattleTrigger.ROUND_START -> {
                val first = state.view.heroes()
                    .filter { requireNotNull(state.view.state(it)).troops > 0 }
                    .sortedWith(
                        compareByDescending<BattleHeroRef> {
                            requireNotNull(state.view.state(it)).stats.speed
                        }.thenBy { it.side.ordinal }.thenBy { it.position },
                    )
                    .firstOrNull()
                if (scoped.source == first) {
                    val timingResult = timing.onRoundStart(scoped)
                    events += apply(timingResult, scoped)
                    val roundOutputs = applier.onRoundStart(scoped.round)
                    events += processDamageOutputs(roundOutputs, scoped)
                    SkillExecutionResult.EMPTY
                } else {
                    SkillExecutionResult.EMPTY
                }
            }
            BattleTrigger.ROUND_END -> {
                SkillExecutionResult.EMPTY
            }
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            BattleTrigger.PURSUIT_ATTEMPT,
            -> attemptSkills(trigger, scoped)
            BattleTrigger.ACTION_BEFORE -> timing.onAction(scoped)
            BattleTrigger.BATTLE_PASSIVE,
            BattleTrigger.BATTLE_COMMAND,
            -> executeBattleSkills(trigger, scoped)
            else -> SkillExecutionResult.EMPTY
        }
        events += apply(withSuccessfulSkillPluginResponses(result, scoped), scoped)
        return events
    }

    override fun permissionFor(
        actor: BattleHeroRef,
        context: SkillBattleContext,
    ): ActionPermission {
        val permission = applier.permissionFor(actor)
        val base = ActionPermissionResolver(state.effectStore).permissionFor(
            actor,
            context.copy(runtime = state.runtime, battleView = state.view),
        )
        return base.copy(
            canAct = permission.canAct,
            canCastActive = permission.canCastActive,
            canNormalAttack = permission.canNormalAttack,
            redirectTarget = base.redirectTarget ?: permission.damageRedirectTarget,
            normalAttackCount = permission.normalAttackCount,
            grantsPursuitOpportunityPerNormal = permission.pursuitOpportunityCount > 0,
            counterattack = permission.counterattack,
            secondaryAttack = permission.splitAttack,
            firstAction = permission.firstAction,
        )
    }

    fun applyNormalDamage(
        round: Int,
        source: BattleHeroRef,
        target: BattleHeroRef,
        amount: Int,
        context: SkillBattleContext,
    ): List<BattleEvent> {
        val redirected = applier.permissionFor(target).damageRedirectTarget ?: target
        val result = applier.apply(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = redirected,
                    amount = amount,
                    troopsAfter = (
                        requireNotNull(state.view.state(redirected)).troops - amount
                        ).coerceAtLeast(0),
                    school = com.stzb.server.game.battle.DamageSchool.PHYSICAL,
                    origin = com.stzb.server.game.battle.DamageOrigin.NORMAL,
                    tags = emptySet(),
                    skillId = 0,
                    effectId = 0,
                ),
            ),
            round,
        )
        return processDamageOutputs(result, context.copy(round = round, source = source))
    }

    internal fun schedule(
        change: BattleStateChange,
        round: Int,
    ) {
        timing.enqueue(change, round, timing.position().hit)
    }

    internal fun timingPosition(): TimingPosition = timing.position()

    internal fun applyChanges(
        changes: List<BattleStateChange>,
        context: SkillBattleContext,
    ): List<BattleEvent> = apply(
        SkillExecutionResult.immutable(changes, emptyList(), emptyList(), emptyList()),
        context,
    )

    fun liveHero(ref: BattleHeroRef) = state.liveHero(ref)

    fun livingHeroesInSpeedOrder(): List<BattleHeroRef> =
        state.view.heroes()
            .filter { requireNotNull(state.view.state(it)).troops > 0 }
            .sortedWith(
                compareByDescending<BattleHeroRef> {
                    val permission = applier.permissionFor(it)
                    if (permission.firstAction) Int.MAX_VALUE
                    else requireNotNull(state.view.state(it)).stats.speed
                }.thenBy { it.side.ordinal }.thenBy { it.position },
            )

    fun recordTarget(source: BattleHeroRef, target: BattleHeroRef) {
        history.record(source, target)
    }

    fun secondaryTarget(
        source: BattleHeroRef,
        primary: BattleHeroRef,
    ): BattleHeroRef? {
        val sourceHero = liveHero(source)
        return state.view.heroes()
            .asSequence()
            .filter {
                it.side == primary.side && it != primary &&
                    (state.view.state(it)?.troops ?: 0) > 0
            }
            .map(::liveHero)
            .filter {
                actionResolver.selectNormalAttackTarget(sourceHero, listOf(it), random = null) != null
            }
            .minWithOrNull(
                compareBy<BattleHero> { kotlin.math.abs(it.position - primary.position) }
                    .thenBy { it.position },
            )
            ?.let { BattleHeroRef(primary.side, it.position, it.id) }
    }

    fun reactiveAttack(
        round: Int,
        source: BattleHeroRef,
        target: BattleHeroRef,
        effectId: Int,
        context: SkillBattleContext,
    ): List<BattleEvent> {
        if (baseDefeated()) return emptyList()
        val effect = state.effectStore.effectsFor(source).lastOrNull { it.effectId == effectId }
            ?: return emptyList()
        val sourceHero = liveHero(source)
        val targetHero = liveHero(target)
        if (sourceHero.troops <= 0 || targetHero.troops <= 0) return emptyList()
        if (actionResolver.selectNormalAttackTarget(sourceHero, listOf(targetHero), random = null) == null) {
            return emptyList()
        }
        val damage = BattleDamageCalculator.physical(
            source = sourceHero,
            target = targetHero,
            ratePercent = effect.effectiveStrength.coerceAtLeast(1),
            origin = DamageOrigin.NORMAL,
        )
        val result = applier.apply(
            listOf(
                TroopDamageChange(
                    source,
                    target,
                    damage,
                    (targetHero.troops - damage).coerceAtLeast(0),
                    DamageSchool.PHYSICAL,
                    DamageOrigin.NORMAL,
                    emptySet(),
                    effect.skillId,
                    effectId,
                ),
            ),
            round,
        )
        return processDamageOutputs(result, context.copy(round = round, source = source))
    }

    fun tryEvade(
        round: Int,
        source: BattleHeroRef,
        target: BattleHeroRef,
    ): BattleEvent.Evaded? {
        val targetPermission = applier.permissionFor(target)
        val sourcePermission = applier.permissionFor(source)
        val entryEvade = requireNotNull(state.view.state(target)).statuses
            .contains(com.stzb.server.game.battle.BattleStatus.EVADE)
        if ((!targetPermission.canEvade && !entryEvade) || sourcePermission.ignoresEvade) return null
        state.effectStore.consumeHit(target, 514)
        state.effectStore.consumeHit(target, 714)
        return BattleEvent.Evaded(round, source, target)
    }

    fun baseDefeated(): Boolean =
        com.stzb.server.game.battle.Side.entries.any { side ->
            val base = state.view.heroes()
                .filter { it.side == side }
                .minByOrNull { it.position }
            base == null || requireNotNull(state.view.state(base)).troops <= 0
        }

    fun finishRound(round: Int): List<BattleEvent> =
        applier.onRoundEnd(round).toEvents(round)

    private fun executeBattleSkills(
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        skillsFor(context.source, trigger).fold(SkillExecutionResult.EMPTY) { result, skillId ->
            val skillContext = context.copy(
                    rootSkillId = skillId,
                    currentSkillId = skillId,
                )
            specialPlugins.pluginFor(skillId)?.takeIf {
                trigger == BattleTrigger.BATTLE_COMMAND
            }?.let { plugin ->
                val pluginResult = pluginTriggeredResult(skillId, trigger, skillContext, plugin)
                if (plugin.replacesConfiguredExecution) {
                    return@fold result + pluginResult
                }
                return@fold result +
                    interpreter.execute(skillId, trigger, skillContext) +
                    pluginResult
            }
            val skillResult = interpreter.execute(skillId, trigger, skillContext)
            result + skillResult
        }

    private fun attemptSkills(
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        skillsFor(context.source, trigger).fold(SkillExecutionResult.EMPTY) { result, skillId ->
            val key = context.source to skillId
            if ((cooldownUntilRound[key] ?: 0) >= context.round) {
                result
            } else {
                val attempt = if (skillId == DISORDER_SKILL_ID) {
                    executeDisorder(context)
                } else {
                    timing.attempt(
                    skillId,
                    context.copy(rootSkillId = skillId, currentSkillId = skillId),
                    TimingAttemptOptions(oncePerRound = trigger != BattleTrigger.PURSUIT_ATTEMPT),
                    )
                }
                if (skillId == DISORDER_SKILL_ID && attempt.executedSkillIds.isNotEmpty()) {
                    cooldownUntilRound[key] = context.round + 3
                }
                result + attempt
            }
        }

    private fun withSuccessfulSkillPluginResponses(
        result: SkillExecutionResult,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        result.executedSkillIds
            .filter { graph.rule(it)?.kind == SkillKind.ACTIVE }
            .fold(result) { aggregate, successfulSkillId ->
                aggregate + successfulSkillPluginResponses(
                    actor = context.source,
                    successfulSkillId = successfulSkillId,
                    successfulSkillKind = SkillKind.ACTIVE,
                    context = context,
                )
            }

    private fun pluginTriggeredResult(
        skillId: Int,
        trigger: BattleTrigger,
        context: SkillBattleContext,
        plugin: SkillExecutionPlugin,
    ): SkillExecutionResult {
        val result = plugin.execute(
            SpecialSkillInvocation(
                phase = SpecialSkillPhase.BATTLE_PREPARE,
                owner = context.source,
                actor = context.source,
                context = context,
            ),
        )
        return SkillExecutionResult.immutable(
            stateChanges = result.stateChanges,
            events = listOf(
                SkillTriggered(
                    context.round,
                    context.source,
                    skillId,
                    skillId,
                    trigger,
                ),
            ) + result.events,
            executedSkillIds = listOf(skillId),
            diagnostics = result.diagnostics,
            timingDues = result.timingDues,
        )
    }

    private fun successfulSkillPluginResponses(
        actor: BattleHeroRef,
        successfulSkillId: Int,
        successfulSkillKind: SkillKind?,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        state.view.heroes()
            .filter { (state.view.state(it)?.troops ?: 0) > 0 }
            .fold(SkillExecutionResult.EMPTY) ownerFold@{ aggregate, owner ->
                state.liveHero(owner).skillIds.fold(aggregate) pluginFold@{ inner, ownerSkillId ->
                    val plugin = specialPlugins.pluginFor(ownerSkillId)
                        ?: return@pluginFold inner
                    inner + plugin.execute(
                        SpecialSkillInvocation(
                            phase = SpecialSkillPhase.AFTER_SUCCESSFUL_SKILL,
                            owner = owner,
                            actor = actor,
                            successfulSkillId = successfulSkillId,
                            successfulSkillKind = successfulSkillKind,
                            context = context.copy(
                                source = owner,
                                rootSkillId = ownerSkillId,
                                currentSkillId = ownerSkillId,
                            ),
                        ),
                    )
                }
            }

    private fun executeDisorder(context: SkillBattleContext): SkillExecutionResult {
        if (!context.runtime.recordAttempt(
                context.source,
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                DISORDER_SKILL_ID,
                context.round,
            )
        ) return SkillExecutionResult.EMPTY
        val rule = requireNotNull(graph.rule(DISORDER_SKILL_ID))
        if (context.random.nextInt(100) >= rule.probability) return SkillExecutionResult.EMPTY
        context.runtime.recordSuccessfulExecution(
            context.source,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            DISORDER_SKILL_ID,
        )
        val candidates = rule.details.filter {
            it.effectId in setOf(303, 304, 305, 306, 501, 502, 503, 552, 505)
        }.distinctBy { it.effectId }
        val selected = List(3) { candidates[context.random.nextInt(candidates.size)] }
        val target = state.view.heroes()
            .filter { it.side != context.source.side && requireNotNull(state.view.state(it)).troops > 0 }
            .sortedBy { it.position }
            .firstOrNull()
        var result = SkillExecutionResult.immutable(
            emptyList(),
            listOf(
                SkillTriggered(
                    context.round,
                    context.source,
                    DISORDER_SKILL_ID,
                    DISORDER_SKILL_ID,
                    BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                ),
            ),
            listOf(DISORDER_SKILL_ID),
            emptyList(),
        )
        selected.forEach { detail ->
            if (target != null) {
                val spec = PersistentEffectSpec(
                    source = context.source,
                    target = target,
                    rootSkillId = DISORDER_SKILL_ID,
                    skillId = DISORDER_SKILL_ID,
                    skillKind = SkillKind.ACTIVE,
                    rawSkillType = 3,
                    detailId = detail.detailId,
                    effectId = detail.effectId,
                    category = com.stzb.server.game.battle.EffectCategory.HARMFUL,
                    conflict = detail.raw.hideConflict,
                    replaceType = detail.effectReplaceType,
                    bindFlag = detail.raw.bindFlag,
                    maxStacks = detail.raw.addCountMax + 1,
                    delayRound = 0,
                    delayHit = 0,
                    availableRounds = detail.raw.availableRounds.coerceAtLeast(1) + 1,
                    availableHit = detail.raw.availableHit,
                    clearPerHit = detail.raw.clearPerHit,
                    startBoundary = EffectStartBoundary.IMMEDIATE,
                    potency = TypedBattlePotency.rate(detail.raw.constantParam.coerceAtLeast(1)),
                )
                val change: BattleStateChange = when (detail.effectId) {
                    303, 304, 305, 306 -> ScheduledDamageEffectChange(
                        spec,
                        if (detail.effectId == 303) {
                            com.stzb.server.game.battle.DamageSchool.PHYSICAL
                        } else {
                            com.stzb.server.game.battle.DamageSchool.STRATEGY
                        },
                        com.stzb.server.game.battle.DamageOrigin.ACTIVE,
                        buildSet {
                            add(DamageTag.ONGOING)
                            if (detail.effectId == 305) add(DamageTag.FIRE)
                        },
                        requireNotNull(statusFor(detail.effectId)),
                        detail.coefficientSource,
                        detail.raw.intelParam,
                        detail.raw.calculationTypes,
                    )
                    else -> ScheduledEffectActivationChange(
                        spec = spec,
                        status = statusForControl(detail.effectId),
                    )
                }
                result += SkillExecutionResult.immutable(
                    listOf(change),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                )
            }
        }
        return result
    }

    private fun skillsFor(
        source: BattleHeroRef,
        trigger: BattleTrigger,
    ): List<Int> {
        val kind = when (trigger) {
            BattleTrigger.BATTLE_PASSIVE -> SkillKind.PASSIVE
            BattleTrigger.BATTLE_COMMAND -> SkillKind.COMMAND
            BattleTrigger.ACTIVE_SKILL_ATTEMPT -> SkillKind.ACTIVE
            BattleTrigger.PURSUIT_ATTEMPT -> SkillKind.PURSUIT
            else -> return emptyList()
        }
        return state.liveHero(source).skillIds.filter { graph.rule(it)?.kind == kind }
    }

    private fun apply(
        result: SkillExecutionResult,
        context: SkillBattleContext,
    ): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        result.events.forEach { event ->
            when (event) {
                is SkillTriggered -> events += BattleEvent.SkillTriggered(
                    event.round,
                    event.source,
                    event.rootSkillId,
                    event.skillId,
                    event.trigger,
                )
                is BattleOutputEvent -> events += event.event
                is SkillTimingEvent.PreparationCompleted ->
                    events += BattleEvent.SkillPreparationCompleted(
                        event.completedRound,
                        event.source,
                        event.rootSkillId,
                        event.currentSkillId,
                        event.startedRound,
                        event.readyRound,
                        event.trigger,
                    )
                is SkillPreparationCancelledEvent ->
                    events += BattleEvent.SkillPreparationCancelled(
                        event.round,
                        event.snapshot.source,
                        event.snapshot.rootSkillId,
                        event.snapshot.skillId,
                        event.reason.name,
                    )
                is SkillPreparationStartedEvent -> Unit
            }
        }
        val dueChangeIndices = result.dueChangeIndexMask()
        for ((changeIndex, change) in result.stateChanges.withIndex()) {
            if (dueChangeIndices[changeIndex]) continue
            when (change) {
                is ScheduledEffectActivationChange -> {
                    if (change.spec.startBoundary == EffectStartBoundary.IMMEDIATE) {
                        events += apply(timing.activate(change, context.round), context)
                    } else {
                        val position = timing.position()
                        timing.enqueue(change, context.round.coerceAtLeast(1), position.hit)
                    }
                }
                is ScheduledTimingChange -> {
                    val position = timing.position()
                    timing.enqueue(change, context.round.coerceAtLeast(1), position.hit)
                }
                is ScheduledDamageEffectChange ->
                    if (change.spec.startBoundary == EffectStartBoundary.AFTER_DELAY) {
                        val position = timing.position()
                        timing.enqueue(change, context.round.coerceAtLeast(1), position.hit)
                    } else {
                        events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                        events += BattleEvent.StatusApplied(
                            context.round,
                            change.source,
                            change.target,
                            change.status,
                            change.durationRounds,
                            change.potency.value,
                            skillId = change.skillId,
                        )
                    }
                is ScheduledRecoveryEffectChange ->
                    if (change.spec.startBoundary == EffectStartBoundary.AFTER_DELAY) {
                        val position = timing.position()
                        timing.enqueue(change, context.round.coerceAtLeast(1), position.hit)
                    } else {
                        events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                    }
                is SkillAttemptRejectedChange,
                is SkillPreparationRejectedChange,
                is SkillPreparationCancelledChange,
                is ExecuteChildSkillChange,
                is RetriggerSkillChange,
                is TriggerReferencedEffectChange,
                is MetaEffectChange,
                is MoraleEffectChange,
                -> Unit
                is MarkerEffectChange -> Unit
                is DamageModifierChange ->
                    if (change.durationRounds > 0) {
                        events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                    }
                is BattleStatChange -> {
                    events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                }
                is ClearReferencedEffectChange -> {
                    events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                }
                is ReduceReferencedEffectUseChange,
                -> events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                else -> events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
            }
            if (baseDefeated()) break
        }
        result.timingDues.forEach { due ->
            events += processDamageOutputs(applier.applyActivated(
                due.change,
                due,
                context.round,
                timing.position().hit,
            ), context)
        }
        return events
    }

    private fun processDamageOutputs(
        result: BattleStateApplyResult,
        context: SkillBattleContext,
    ): List<BattleEvent> {
        val events = mutableListOf<BattleEvent>()
        result.outputs.filterIsInstance<BattleStateOutput.DamageDealt>().forEach { output ->
            events += BattleStateApplyResult(listOf(output)).toEvents(context.round)
            val damageContext = context.copy(source = output.source, trigger = BattleTrigger.DAMAGE_AFTER)
            state.runtime.recordBattleTriggerOccurrence(output.source, BattleTrigger.DAMAGE_AFTER)
            events += trigger(BattleTrigger.DAMAGE_AFTER, damageContext)
            val hurtContext = context.copy(source = output.target, trigger = BattleTrigger.HURT_AFTER)
            state.runtime.recordBattleTriggerOccurrence(output.target, BattleTrigger.HURT_AFTER)
            events += trigger(BattleTrigger.HURT_AFTER, hurtContext)
            events += apply(timing.onHit(damageContext), damageContext)
        }
        result.outputs.filterIsInstance<BattleStateOutput.TroopsRecovered>()
            .filter { it.amount > 0 }
            .forEach { output ->
                state.runtime.recordBattleTriggerOccurrence(
                    output.source,
                    BattleTrigger.RECOVERY_AFTER,
                )
            }
        events += result.outputs
            .filterNot { it is BattleStateOutput.DamageDealt || it is BattleStateOutput.HurtReceived }
            .let(::BattleStateApplyResult)
            .toEvents(context.round)
        return events
    }

    private fun BattleStateApplyResult.toEvents(round: Int): List<BattleEvent> =
        outputs.flatMap { output ->
            when (output) {
                is BattleStateOutput.DamageDealt -> {
                    val damageEvent: BattleEvent =
                        if (output.skillId == 0) {
                            BattleEvent.NormalAttack(
                            round,
                            output.source,
                            output.target,
                            output.amount,
                            requireNotNull(state.view.state(output.target)).troops,
                        )
                        } else if (DamageTag.ONGOING in output.tags) {
                            BattleEvent.OngoingDamage(
                            round,
                            output.source,
                            output.target,
                            statusFor(output.effectId)
                                ?: com.stzb.server.game.battle.BattleStatus.PANIC,
                            output.amount,
                            requireNotNull(state.view.state(output.target)).troops,
                            output.skillId,
                        )
                        } else {
                            BattleEvent.SkillDamage(
                            round,
                            output.skillId,
                            output.effectId,
                            output.source,
                            output.target,
                            output.amount,
                            requireNotNull(state.view.state(output.target)).troops,
                        )
                        }
                    listOf(
                        BattleEvent.TriggerPoint(round, output.source, BattleTrigger.DAMAGE_BEFORE),
                        damageEvent,
                    )
                }
                is BattleStateOutput.HurtReceived -> emptyList()
                is BattleStateOutput.TroopsRecovered -> listOf(
                    BattleEvent.Recovery(
                        round,
                        output.source,
                        output.target,
                        output.amount,
                        requireNotNull(state.view.state(output.target)).troops,
                        output.skillId,
                    ),
                )
                is BattleStateOutput.StatChanged -> buildList {
                    val change = output.change
                    add(change.toEvent(round))
                    statusForStat(change.effectId)?.let { status ->
                        add(
                            BattleEvent.StatusApplied(
                                round,
                                change.source,
                                change.target,
                                status,
                                change.durationRounds,
                                change.potency.value,
                                skillId = change.skillId,
                            ),
                        )
                    }
                }
                is BattleStateOutput.EffectRemoved -> listOf(
                    BattleEvent.StatusRemoved(
                        round,
                        output.effect.source,
                        output.effect.target,
                        output.effect.skillId,
                        output.effect.effectId,
                    ),
                )
                is BattleStateOutput.EffectExpired -> listOf(
                    BattleEvent.EffectExpired(
                        round,
                        output.effect.source,
                        output.effect.target,
                        output.effect.skillId,
                        output.effect.effectId,
                    ),
                )
                is BattleStateOutput.EffectBlocked -> listOf(
                    output.change.let { change ->
                        BattleEvent.EffectBlocked(
                            round,
                            change.source,
                            change.target,
                            change.skillId,
                            change.effectId,
                            change.blockingEffectId,
                        )
                    },
                )
            }
        }

    private fun BattleStatChange.toEvent(round: Int): BattleEvent.StatChanged =
        BattleEvent.StatChanged(
            round,
            source,
            target,
            when (kind) {
                BattleStatChange.Kind.ATTACK -> BattleStat.ATTACK
                BattleStatChange.Kind.DEFENSE -> BattleStat.DEFENSE
                BattleStatChange.Kind.STRATEGY -> BattleStat.STRATEGY
                BattleStatChange.Kind.SPEED -> BattleStat.SPEED
                BattleStatChange.Kind.SIEGE -> BattleStat.SIEGE
                BattleStatChange.Kind.ATTACK_RANGE -> BattleStat.HIT_RANGE
            },
            potency.value,
            durationRounds,
            skillId,
        )

    companion object {
        private const val DISORDER_SKILL_ID = 200002
        fun create(
            request: com.stzb.server.game.battle.BattleRequest,
            config: BattleConfigRepository,
            strict: Boolean = false,
        ): DefaultCompleteSkillEngine {
            val runtime = SkillRuntimeState()
            val history = MutableBattleHistory()
            val state = SkillBattleState(
                request,
                runtime,
                metadataProvider = { ref ->
                    config.hero(ref.heroId.value)?.let { hero ->
                        SkillBattleHeroMetadata(
                            gender = when (hero.sex) {
                                0 -> SkillHeroGender.MALE
                                1 -> SkillHeroGender.FEMALE
                                else -> SkillHeroGender.UNKNOWN
                            },
                            troopType = when (hero.heroType) {
                                1 -> SkillTroopType.CAVALRY
                                2 -> SkillTroopType.ARCHER
                                3 -> SkillTroopType.INFANTRY
                                else -> SkillTroopType.UNKNOWN
                            },
                            country = hero.country,
                        )
                    }
                },
                historyAdapter = history,
                stateFilterMatcher = { _, _, _ -> true },
            )
            state.seedInitialEffects()
            val graph = SkillRuleCatalog.build(
                SkillScope(
                    fiveStarInitialSkillIds = request.attacker.heroes.flatMap { it.skillIds }.toSet(),
                    learnableSaSkillIds = request.defender.heroes.flatMap { it.skillIds }.toSet(),
                ),
                config,
            )
            val diagnostics = mutableListOf<SkillExecutionDiagnostic>()
            val registry = (
                if (strict) BattleEffectRegistry.strict(graph)
                else BattleEffectRegistry.safe(graph) {}
                )
                .registerCoreEffects(state.effectStore)
                .registerControlEffects(state.effectStore)
                .registerMetaEffects()
            val interpreter = if (strict) {
                SkillRuleInterpreter(graph, registry)
            } else {
                SkillRuleInterpreter.safe(
                    graph,
                    registry,
                    conditionInterpreter = SkillConditionInterpreter(graph),
                    diagnosticSink = diagnostics::add,
                )
            }
            val timing = if (strict) {
                CompleteTimingCoordinator(graph, interpreter, runtime)
            } else {
                CompleteTimingCoordinator.safe(graph, interpreter, runtime)
            }
            val specialPlugins = ConfiguredSpecialSkillPlugins.registry(config)
            return DefaultCompleteSkillEngine(
                state,
                graph,
                interpreter,
                timing,
                BattleStateChangeApplier(state),
                specialPlugins,
            ).also { it.history = history }
        }
    }

    private lateinit var history: MutableBattleHistory
}

internal fun SkillExecutionResult.dueChangeIndexMask(): BooleanArray {
    val dueChangeIndices = BooleanArray(stateChanges.size)
    timingDues
        .flatMap { it.activatedChanges }
        .asReversed()
        .forEach { dueChange ->
            val index = stateChanges.indices.reversed().firstOrNull {
                !dueChangeIndices[it] && stateChanges[it] == dueChange
            }
            check(index != null) { "Timing due change is missing from execution result: $dueChange" }
            dueChangeIndices[index] = true
        }
    return dueChangeIndices
}

private class MutableBattleHistory : SkillBattleHistoryAdapter {
    private val current = mutableMapOf<BattleHeroRef, BattleHeroRef>()
    private val previous = mutableMapOf<BattleHeroRef, BattleHeroRef>()

    fun record(source: BattleHeroRef, target: BattleHeroRef) {
        current[source]?.let { previous[source] = it }
        current[source] = target
    }

    override fun linkedTarget(source: BattleHeroRef): BattleHeroRef? = current[source]
    override fun currentTarget(source: BattleHeroRef): BattleHeroRef? = current[source]
    override fun previousTarget(source: BattleHeroRef): BattleHeroRef? = previous[source]
}

private fun BattleTrigger.emitsPoint(): Boolean =
    this !in setOf(
        BattleTrigger.BATTLE_PASSIVE,
        BattleTrigger.BATTLE_COMMAND,
        BattleTrigger.ACTIVE_SKILL_ATTEMPT,
        BattleTrigger.PURSUIT_ATTEMPT,
    )

private fun statusFor(effectId: Int) = when (effectId) {
    303 -> com.stzb.server.game.battle.BattleStatus.SHAKE
    304 -> com.stzb.server.game.battle.BattleStatus.PANIC
    305 -> com.stzb.server.game.battle.BattleStatus.BURN
    306 -> com.stzb.server.game.battle.BattleStatus.HEX
    else -> null
}

private fun statusForControl(effectId: Int) = when (effectId) {
    501 -> com.stzb.server.game.battle.BattleStatus.CONFUSION
    502 -> com.stzb.server.game.battle.BattleStatus.HESITATION
    552 -> com.stzb.server.game.battle.BattleStatus.DISARM
    else -> null
}

private fun statusForStat(effectId: Int) = when (effectId) {
    101 -> com.stzb.server.game.battle.BattleStatus.ATTACK_BUFF
    102 -> com.stzb.server.game.battle.BattleStatus.DEFENSE_BUFF
    103 -> com.stzb.server.game.battle.BattleStatus.STRATEGY_BUFF
    104 -> com.stzb.server.game.battle.BattleStatus.SPEED_BUFF
    201 -> com.stzb.server.game.battle.BattleStatus.ATTACK_DEBUFF
    202 -> com.stzb.server.game.battle.BattleStatus.DEFENSE_DEBUFF
    203 -> com.stzb.server.game.battle.BattleStatus.STRATEGY_DEBUFF
    204 -> com.stzb.server.game.battle.BattleStatus.SPEED_DEBUFF
    else -> null
}
