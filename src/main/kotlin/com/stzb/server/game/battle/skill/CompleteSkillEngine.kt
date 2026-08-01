package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActionPermission
import com.stzb.server.game.battle.BattleActionResolver
import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleDamageCalculator
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleStat
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.DamageTag
import com.stzb.server.game.battle.SkillKind
import com.stzb.server.game.battle.opposite
import kotlin.math.roundToInt

private const val ZHENGSHI_SIGNAL = "skill.200244.next-action"
private const val BAIZHAN_STACKS = "skill.200252.stacks"
private const val BAIZHAN_MAX_STACKS = 3

private data class QiqinqizongGuardResult(
    val guarded: Boolean,
    val completion: SkillExecutionResult,
)

private data class PibingjuyiDamageBeforeResult(
    val change: TroopDamageChange,
    val owner: BattleHeroRef?,
)

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
    private val pendingExtraNormalAttacks = mutableMapOf<BattleHeroRef, Int>()

    override fun prepareBattle(context: SkillBattleContext): List<BattleEvent> {
        if (prepared) return emptyList()
        prepared = true
        return buildList {
            val sources = livingHeroesInSpeedOrder()
            sources.forEach { source ->
                addAll(trigger(BattleTrigger.BATTLE_PASSIVE, context.copy(source = source)))
                if (isBaizhanOwner(source)) {
                    state.runtime.addCounter(
                        owner = source,
                        namespace = BAIZHAN_STACKS,
                        delta = BAIZHAN_MAX_STACKS,
                        maximum = BAIZHAN_MAX_STACKS,
                    )
                }
                if (201006 in state.liveHero(source).skillIds) {
                    state.view.heroes()
                        .filter { candidate ->
                            candidate.side == source.side &&
                                (state.view.state(candidate)?.troops ?: 0) > 0
                        }
                        .sortedBy(BattleHeroRef::position)
                        .forEach { target ->
                            add(
                                BattleEvent.SkillTriggered(
                                    round = context.round,
                                    source = target,
                                    rootSkillId = 201006,
                                    skillId = 221006,
                                    trigger = BattleTrigger.BATTLE_PASSIVE,
                                ),
                            )
                        }
                }
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
            skillProbabilityUses = SkillProbabilityUseSink { source, skillId, skillKind ->
                applier.consumeSkillProbabilityUses(source, skillId, skillKind)
                context.skillProbabilityUses.consume(source, skillId, skillKind)
            },
            forcedTargets = BattleForcedTargetSource { request ->
                if (request.rule.skillKind == SkillKind.ACTIVE &&
                    request.rule.effectId in 301..307
                ) {
                    applier.tryConsumeForcedTarget(
                        actor = request.context.source,
                        eligibleTargets = request.candidates,
                        random = request.context.random,
                    )?.let(::listOf)
                        ?: context.forcedTargets.select(request)
                } else {
                    context.forcedTargets.select(request)
                }
            },
        )
        val events = mutableListOf<BattleEvent>()
        if (trigger.emitsPoint()) {
            events += BattleEvent.TriggerPoint(scoped.round, scoped.source, trigger)
        }
        val configuredResult = when (trigger) {
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
                    val boundaryOutputs = applier.beginRound(scoped.round)
                    events += processDamageOutputs(boundaryOutputs, scoped)
                    val timingResult = timing.onRoundStart(scoped)
                    events += apply(timingResult, scoped)
                    val roundOutputs = applier.onRoundStart(scoped.round)
                    events += processDamageOutputs(roundOutputs, scoped)
                    shoujingRoundResult(scoped) + pibingjuyiRoundStartResult(scoped)
                } else {
                    SkillExecutionResult.EMPTY
                } + baizhanSpendResult(scoped.source, scoped)
            }
            BattleTrigger.ROUND_END -> {
                tianziRoundEndResult(scoped)
            }
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            BattleTrigger.PURSUIT_ATTEMPT,
            -> attemptSkills(trigger, scoped)
            BattleTrigger.ACTION_BEFORE -> {
                val actionResult = timing.onAction(scoped) +
                    zhengshiActionResult(scoped) +
                    dingjunActionResult(scoped)
                events += apply(actionResult, scoped)
                val fenjiResult = executeFenjiAction(scoped) { event ->
                    events += event
                }
                val pluginResponseSeed = SkillExecutionResult.immutable(
                    stateChanges = emptyList(),
                    events = emptyList(),
                    executedSkillIds =
                        actionResult.executedSkillIds + fenjiResult.executedSkillIds,
                    diagnostics = emptyList(),
                )
                events += apply(
                    withSuccessfulSkillPluginResponses(pluginResponseSeed, scoped),
                    scoped,
                )
                SkillExecutionResult.EMPTY
            }
            BattleTrigger.BATTLE_PASSIVE,
            BattleTrigger.BATTLE_COMMAND,
            -> {
                skillsFor(scoped.source, trigger).forEach { skillId ->
                    val skillResult = executeBattleSkill(trigger, scoped, skillId)
                    events += apply(
                        withSuccessfulSkillPluginResponses(skillResult, scoped),
                        scoped,
                    )
                }
                SkillExecutionResult.EMPTY
            }
            else -> SkillExecutionResult.EMPTY
        } + fuboyangshaNormalAttackResult(scoped) + qibuActionResult(scoped)
        val result = configuredResult + sanjunduoshuaiResult(scoped, configuredResult)
        events += apply(withSuccessfulSkillPluginResponses(result, scoped), scoped)
        return events
    }

    override fun permissionFor(
        actor: BattleHeroRef,
        context: SkillBattleContext,
    ): ActionPermission {
        val scopedContext = context.copy(runtime = state.runtime, battleView = state.view)
        val permission = applier.permissionFor(actor, scopedContext)
        val base = ActionPermissionResolver(state.effectStore).permissionFor(actor, scopedContext)
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

    fun resolveNormalAttack(
        round: Int,
        source: BattleHeroRef,
        target: BattleHeroRef,
        random: BattleRandom,
        context: SkillBattleContext,
    ): List<BattleEvent> {
        val prospective = TroopDamageChange(
            source = source,
            target = target,
            amount = 0,
            troopsAfter = requireNotNull(state.view.state(target)).troops,
            school = DamageSchool.PHYSICAL,
            origin = DamageOrigin.NORMAL,
            tags = emptySet(),
            skillId = 0,
            effectId = 0,
        )
        val events = mutableListOf<BattleEvent>()
        events += apply(
            chijieDamageBeforeResult(
                prospective,
                context.copy(
                    round = round,
                    source = source,
                    trigger = BattleTrigger.DAMAGE_BEFORE,
                ),
            ),
            context,
        )
        val liveSource = liveHero(source)
        val liveTarget = liveHero(target)
        val amount = actionResolver.normalAttackDamage(liveSource, liveTarget, random)
        events += applyNormalDamage(round, source, target, amount, context)
        return events
    }

    private fun executeSimulatedNormalAttack(
        change: SimulatedNormalAttackChange,
        context: SkillBattleContext,
    ): List<BattleEvent> {
        if ((state.view.state(change.source)?.troops ?: 0) <= 0) return emptyList()
        val permission = permissionFor(change.source, context)
        val targetPool = permission.resolvedTargetPool.ifEmpty {
            state.view.heroes().filter { ref ->
                ref.side == (permission.resolvedAllegiance ?: change.source.side).opposite()
            }
        }
        val currentSource = state.liveHero(change.source)
        val eligible = actionResolver.normalAttackTargetsInRange(
            source = currentSource,
            enemies = targetPool.map(state::liveHero),
        )
        val selected = when (change.mode) {
            SimulatedNormalAttackMode.SINGLE -> {
                val target = actionResolver.selectNormalAttackTarget(
                    source = currentSource,
                    enemies = eligible.map { it.first },
                    random = context.random,
                ) ?: return emptyList()
                listOf(target)
            }
            SimulatedNormalAttackMode.ALL_IN_RANGE -> eligible.map { it.first }
        }
        return buildList {
            selected.forEach targetLoop@{ targetHero ->
                if (baseDefeated() || (state.view.state(change.source)?.troops ?: 0) <= 0) {
                    return@targetLoop
                }
                var target = targetPool.single {
                    it.position == targetHero.position && it.heroId == targetHero.id
                }
                if (change.mode == SimulatedNormalAttackMode.SINGLE) {
                    target = permission.redirectTarget
                        ?: forcedNormalAttackTarget(change.source, target, context.random)
                }
                if ((state.view.state(target)?.troops ?: 0) <= 0) return@targetLoop
                recordTarget(change.source, target)
                addAll(
                    trigger(
                        BattleTrigger.NORMAL_ATTACK_BEFORE,
                        context.copy(
                            source = change.source,
                            trigger = BattleTrigger.NORMAL_ATTACK_BEFORE,
                        ),
                    ),
                )
                val evaded = tryEvade(context.round, change.source, target)
                if (evaded != null) {
                    add(evaded)
                } else {
                    addAll(
                        resolveNormalAttack(
                            round = context.round,
                            source = change.source,
                            target = target,
                            random = context.random,
                            context = context,
                        ),
                    )
                    if (!baseDefeated()) {
                        val targetContext = context.copy(
                            source = target,
                            trigger = BattleTrigger.DAMAGE_AFTER,
                        )
                        if (
                            BattleModifier.CounterattackImmunity !in
                            state.liveHero(change.source).modifiers &&
                            permissionFor(target, targetContext).counterattack
                        ) {
                            addAll(
                                reactiveAttack(
                                    context.round,
                                    target,
                                    change.source,
                                    551,
                                    targetContext,
                                ),
                            )
                        }
                    }
                }
                state.runtime.recordBattleTriggerOccurrence(
                    change.source,
                    BattleTrigger.NORMAL_ATTACK_AFTER,
                )
                addAll(
                    trigger(
                        BattleTrigger.NORMAL_ATTACK_AFTER,
                        context.copy(
                            source = change.source,
                            trigger = BattleTrigger.NORMAL_ATTACK_AFTER,
                        ),
                    ),
                )
            }
        }
    }

    internal fun schedule(
        change: BattleStateChange,
        round: Int,
    ) {
        timing.enqueue(change, round, timing.position().hit)
    }

    internal fun timingPosition(): TimingPosition = timing.position()

    internal fun consumePendingExtraNormalAttacks(actor: BattleHeroRef): Int =
        pendingExtraNormalAttacks.remove(actor) ?: 0

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

    fun forcedNormalAttackTarget(
        actor: BattleHeroRef,
        normalTarget: BattleHeroRef,
        random: BattleRandom,
    ): BattleHeroRef {
        val eligibleTargets = state.view.heroes().filter { candidate ->
            candidate.side != actor.side &&
                (state.view.state(candidate)?.troops ?: 0) > 0
        }
        return applier.tryConsumeForcedTarget(actor, eligibleTargets, random)
            ?: normalTarget
    }

    internal fun recordDamageThresholds(
        damageSource: BattleHeroRef,
        context: SkillBattleContext,
    ) {
        state.runtime.recordBattleTriggerOccurrence(damageSource, BattleTrigger.DAMAGE_AFTER)
        val damageCount = state.runtime.sideCount(damageSource.side, BattleTrigger.DAMAGE_AFTER)
        state.view.heroes()
            .filter { owner ->
                owner.side != damageSource.side &&
                    200244 in state.liveHero(owner).skillIds
            }
            .forEach { owner ->
                if (state.runtime.consumeThreshold(
                        owner = owner,
                        namespace = "skill.200244.enemy-damage",
                        count = damageCount,
                        threshold = 15,
                    )
                ) {
                    state.runtime.scheduleSignal(
                        owner,
                        ZHENGSHI_SIGNAL,
                        readyRound = context.round + 1,
                    )
                }
            }
    }

    private fun xinzhanDamageResult(
        damageSource: BattleHeroRef,
        damageTarget: BattleHeroRef,
        damageCount: Int,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (damageCount !in 1..9) return SkillExecutionResult.EMPTY
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == damageSource.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200275 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        val listenerContext = context.copy(
            source = owner,
            rootSkillId = 200275,
            currentSkillId = 214275,
            trigger = BattleTrigger.DAMAGE_AFTER,
        )
        val morale = interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 21427501 },
            listenerContext,
            preselectedTargets = listOf(damageTarget),
        )
        if (damageCount < 9) return morale
        return morale + interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 20027523 },
            listenerContext.copy(currentSkillId = 200275),
            preselectedTargets = listOf(owner),
        )
    }

    private fun shoujingRoundResult(context: SkillBattleContext): SkillExecutionResult {
        val detailId = when (context.round) {
            6 -> 20027704
            8 -> 20027705
            else -> return SkillExecutionResult.EMPTY
        }
        return state.view.heroes()
            .filter { owner ->
                state.view.state(owner)?.troops?.let { it > 0 } == true &&
                    200277 in state.liveHero(owner).skillIds &&
                    state.runtime.consumeThreshold(
                        owner = owner,
                        namespace = "skill.200277.round.${context.round}",
                        count = 1,
                        threshold = 1,
                    )
            }
            .fold(SkillExecutionResult.EMPTY) { result, owner ->
                result + interpreter.executeDetailForEngine(
                    graph.details.single { it.detailId == detailId },
                    context.copy(
                        source = owner,
                        rootSkillId = 200277,
                        currentSkillId = 200277,
                        trigger = BattleTrigger.ROUND_START,
                    ),
                )
            }
    }

    private fun huiyanDamageResult(
        damageSource: BattleHeroRef,
        damageCount: Int,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (damageCount != 6) return SkillExecutionResult.EMPTY
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == damageSource.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200294 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 20029402 },
            context.copy(
                source = owner,
                rootSkillId = 200294,
                currentSkillId = 200294,
                trigger = BattleTrigger.DAMAGE_AFTER,
            ),
        )
    }

    private fun dingjunActionResult(context: SkillBattleContext): SkillExecutionResult {
        if (context.round != 4 || 200293 !in state.liveHero(context.source).skillIds) {
            return SkillExecutionResult.EMPTY
        }
        val actionContext = context.copy(
            rootSkillId = 200293,
            currentSkillId = 200293,
            trigger = BattleTrigger.ACTION_BEFORE,
        )
        return listOf(20029307, 20029311).fold(SkillExecutionResult.EMPTY) { result, detailId ->
            result + interpreter.executeDetailForEngine(
                graph.details.single { it.detailId == detailId },
                actionContext,
            )
        }
    }

    private fun executeFenjiAction(
        context: SkillBattleContext,
        onEvent: (BattleEvent) -> Unit,
    ): SkillExecutionResult {
        if (200961 !in state.liveHero(context.source).skillIds) {
            return SkillExecutionResult.EMPTY
        }
        val listenerContext = context.copy(
            rootSkillId = 200961,
            currentSkillId = 200961,
            trigger = BattleTrigger.ACTION_BEFORE,
        )
        return interpreter.executeDetailStreamingForEngine(
            graph.details.single { it.detailId == 20096103 },
            listenerContext,
        ) { step ->
            apply(step, listenerContext).forEach(onEvent)
        }
    }

    private fun isBaizhanOwner(source: BattleHeroRef): Boolean =
        source.position != 0 &&
            200252 in state.liveHero(source).skillIds

    private fun baizhanSpendResult(
        owner: BattleHeroRef,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (!isBaizhanOwner(owner) ||
            state.runtime.counter(owner, BAIZHAN_STACKS) <= 0
        ) {
            return SkillExecutionResult.EMPTY
        }
        state.runtime.addCounter(
            owner = owner,
            namespace = BAIZHAN_STACKS,
            delta = -1,
            maximum = BAIZHAN_MAX_STACKS,
        )
        state.runtime.recordMarker(
            target = owner,
            detailId = 21325201,
            value = 1,
            appliedRound = context.round,
            durationRounds = 1,
            rootSkillId = 200252,
            source = owner,
        )
        val recoveryContext = context.copy(
            source = owner,
            rootSkillId = 200252,
            currentSkillId = 214252,
        )
        return try {
            interpreter.executeDetailForEngine(
                detail = graph.details.single { it.detailId == 21425203 },
                context = recoveryContext,
                preselectedTargets = listOf(owner),
                probabilityAlreadyAccepted = true,
            )
        } finally {
            state.runtime.removeMarker(owner, 21325201)
        }
    }

    private fun manwangHurtResult(
        target: BattleHeroRef,
        hurtCount: Int,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (200297 !in state.liveHero(target).skillIds ||
            !state.runtime.consumeThreshold(
                owner = target,
                namespace = "skill.200297.actual-hurt",
                count = hurtCount,
                threshold = 5,
            )
        ) {
            return SkillExecutionResult.EMPTY
        }
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 20029725 },
            context.copy(
                source = target,
                rootSkillId = 200297,
                currentSkillId = 200297,
                trigger = BattleTrigger.HURT_AFTER,
            ),
        )
    }

    private fun sanjunduoshuaiResult(
        context: SkillBattleContext,
        configuredResult: SkillExecutionResult,
    ): SkillExecutionResult {
        val hero = state.liveHero(context.source)
        if (200987 !in hero.skillIds) return SkillExecutionResult.EMPTY
        val successfulResponses = when (context.trigger) {
            BattleTrigger.NORMAL_ATTACK_AFTER -> 1
            BattleTrigger.ACTIVE_SKILL_ATTEMPT -> configuredResult.executedSkillIds.count { skillId ->
                skillId in hero.skillIds && graph.rule(skillId)?.kind == SkillKind.ACTIVE
            }
            BattleTrigger.PURSUIT_ATTEMPT -> configuredResult.executedSkillIds.count { skillId ->
                skillId in hero.skillIds && graph.rule(skillId)?.kind == SkillKind.PURSUIT
            }
            else -> 0
        }
        return (0 until successfulResponses).fold(SkillExecutionResult.EMPTY) { result, _ ->
            result + sanjunduoshuaiBranch(context)
        }
    }

    private fun sanjunduoshuaiBranch(
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val responseContext = context.copy(
            rootSkillId = 200987,
            currentSkillId = 211987,
        )
        val physicalBranch = context.random.nextInt(100) < 50
        val damageDetailId = if (physicalBranch) 21198701 else 21198723
        val modifierDetailId = if (physicalBranch) 21198712 else 21198724
        val damage = interpreter.executeDetailForEngine(
            detail = graph.details.single { it.detailId == damageDetailId },
            context = responseContext,
            probabilityAlreadyAccepted = true,
        )
        val modifierTargets = if (physicalBranch) {
            listOf(context.source)
        } else {
            damage.stateChanges.filterIsInstance<TroopDamageChange>()
                .map(TroopDamageChange::target)
                .distinct()
        }
        val modifier = interpreter.executeDetailForEngine(
            detail = graph.details.single { it.detailId == modifierDetailId },
            context = responseContext,
            preselectedTargets = modifierTargets,
            probabilityAlreadyAccepted = true,
        )
        return SkillExecutionResult.immutable(
            stateChanges = emptyList(),
            events = listOf(
                SkillTriggered(
                    round = context.round,
                    source = context.source,
                    rootSkillId = 200987,
                    skillId = 211987,
                    trigger = context.trigger,
                ),
            ),
            executedSkillIds = listOf(211987),
            diagnostics = emptyList(),
        ) + damage + modifier
    }

    private fun qibuActionResult(context: SkillBattleContext): SkillExecutionResult {
        if (context.trigger !in setOf(
                BattleTrigger.NORMAL_ATTACK_AFTER,
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                BattleTrigger.PURSUIT_ATTEMPT,
            )
        ) {
            return SkillExecutionResult.EMPTY
        }
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == context.source.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200950 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        val count = state.runtime.sideCount(
            context.source.side,
            BattleTrigger.NORMAL_ATTACK_AFTER,
        ) + state.runtime.sideAttemptCount(
            context.source.side,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
        ) + state.runtime.sideAttemptCount(
            context.source.side,
            BattleTrigger.PURSUIT_ATTEMPT,
        )
        if (!state.runtime.consumeThreshold(
                owner = owner,
                namespace = "skill.200950.team-actions",
                count = count,
                threshold = 7,
            )
        ) {
            return SkillExecutionResult.EMPTY
        }
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 20095002 },
            context.copy(
                source = owner,
                rootSkillId = 200950,
                currentSkillId = 200950,
            ),
        )
    }

    private fun fuboyangshaNormalAttackResult(
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (context.trigger != BattleTrigger.NORMAL_ATTACK_AFTER) {
            return SkillExecutionResult.EMPTY
        }
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == context.source.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200255 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        val bonus = state.view.activeEffectStrength(context.source, 20025525)
        if (bonus <= 0) return SkillExecutionResult.EMPTY

        val progress = state.runtime.addCounter(
            owner,
            FUBO_UPLIFT_COUNTER,
            delta = bonus,
        )
        val layers = state.runtime.counter(owner, FUBO_LAYER_COUNTER)
        val generatedLayers = minOf(progress / FUBO_LAYER_THRESHOLD, FUBO_MAX_LAYERS - layers)
        if (generatedLayers > 0) {
            state.runtime.addCounter(
                owner,
                FUBO_UPLIFT_COUNTER,
                delta = -generatedLayers * FUBO_LAYER_THRESHOLD,
            )
            state.runtime.addCounter(
                owner,
                FUBO_LAYER_COUNTER,
                delta = generatedLayers,
                maximum = FUBO_MAX_LAYERS,
            )
        }
        if (context.source == owner) {
            val availableLayers = state.runtime.counter(owner, FUBO_LAYER_COUNTER)
            val extraAttacks = availableLayers / FUBO_LAYERS_PER_EXTRA_ATTACK
            if (extraAttacks > 0) {
                state.runtime.addCounter(
                    owner,
                    FUBO_LAYER_COUNTER,
                    delta = -extraAttacks * FUBO_LAYERS_PER_EXTRA_ATTACK,
                )
                pendingExtraNormalAttacks[owner] =
                    (pendingExtraNormalAttacks[owner] ?: 0) + extraAttacks
            }
        }
        return SkillExecutionResult.EMPTY
    }

    private fun pibingjuyiRoundStartResult(
        context: SkillBattleContext,
    ): SkillExecutionResult {
        state.view.heroes()
            .filter { candidate ->
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                    200264 in state.liveHero(candidate).skillIds
            }
            .forEach { owner ->
                state.view.heroes()
                    .filter { candidate ->
                        candidate.side == owner.side &&
                            state.view.state(candidate)?.troops?.let { it > 0 } == true
                    }
                    .forEach { target ->
                        state.runtime.addCounter(
                            target,
                            PIBING_BIRUI_LAYER_COUNTER,
                            delta = PIBING_LAYERS_PER_ROUND,
                            maximum = PIBING_MAX_LAYERS,
                        )
                        state.runtime.recordMarker(
                            target = target,
                            detailId = 20026412,
                            value = 1,
                            appliedRound = context.round,
                            durationRounds = 8,
                            rootSkillId = 200264,
                            source = owner,
                        )
                    }
            }
        return SkillExecutionResult.EMPTY
    }

    private fun pibingjuyiDamageBeforeResult(
        change: TroopDamageChange,
        context: SkillBattleContext,
    ): PibingjuyiDamageBeforeResult {
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == change.target.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200264 in state.liveHero(candidate).skillIds
        } ?: return PibingjuyiDamageBeforeResult(change, null)
        if (state.runtime.counter(change.target, PIBING_BIRUI_LAYER_COUNTER) <= 0) {
            return PibingjuyiDamageBeforeResult(change, null)
        }
        state.runtime.addCounter(
            change.target,
            PIBING_BIRUI_LAYER_COUNTER,
            delta = -1,
            maximum = PIBING_MAX_LAYERS,
        )
        val detailId = when (change.school) {
            DamageSchool.PHYSICAL -> 21726401
            DamageSchool.STRATEGY -> 21726402
        }
        val modifier = interpreter.executeDetailForEngine(
            detail = graph.details.single { it.detailId == detailId },
            context = context.copy(
                source = owner,
                rootSkillId = 200264,
                currentSkillId = 217264,
                trigger = BattleTrigger.DAMAGE_BEFORE,
            ),
            preselectedTargets = listOf(change.target),
            probabilityAlreadyAccepted = true,
        ).stateChanges.filterIsInstance<DamageModifierChange>().single()
        val retainedPercent = (100 - kotlin.math.abs(modifier.percent)).coerceAtLeast(0)
        val reducedAmount = change.amount.toLong()
            .times(retainedPercent)
            .div(100)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val targetTroops = requireNotNull(state.view.state(change.target)).troops
        return PibingjuyiDamageBeforeResult(
            change = change.copy(
                amount = reducedAmount,
                troopsAfter = (targetTroops - reducedAmount).coerceAtLeast(0),
            ),
            owner = owner,
        )
    }

    private fun pibingjuyiBurnResult(
        owner: BattleHeroRef?,
        target: BattleHeroRef,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        owner ?: return SkillExecutionResult.EMPTY
        if (state.view.state(target)?.troops?.let { it > 0 } != true) {
            return SkillExecutionResult.EMPTY
        }
        val burnContext = context.copy(
            source = owner,
            rootSkillId = 200264,
            currentSkillId = 211264,
            trigger = BattleTrigger.EFFECT_APPLYING,
        )
        val probabilityDetail = graph.details.single { it.detailId == 21126422 }
        if (!interpreter.detailProbabilitySucceedsForEngine(probabilityDetail, burnContext)) {
            return SkillExecutionResult.EMPTY
        }
        val base = interpreter.executeDetailForEngine(
            detail = graph.details.single { it.detailId == 21626411 },
            context = burnContext.copy(currentSkillId = 216264),
            preselectedTargets = listOf(target),
            probabilityAlreadyAccepted = true,
        )
        val growth = state.runtime.counter(target, PIBING_BURN_GROWTH_COUNTER)
        val boostedChanges = base.stateChanges.map { change ->
            if (change !is ScheduledDamageEffectChange) {
                change
            } else {
                val boostedPotency = change.potency.copy(
                    value = change.potency.value + growth,
                    exactValue = change.potency.exactValue + growth,
                )
                change.copy(spec = change.spec.copy(potency = boostedPotency))
            }
        }
        state.runtime.recordMarker(
            target = target,
            detailId = 20026421,
            value = 1,
            appliedRound = context.round,
            durationRounds = 8,
            rootSkillId = 200264,
            source = owner,
        )
        val growthDelta = interpreter.executeDetailForEngine(
            detail = graph.details.single { it.detailId == 21426401 },
            context = burnContext.copy(currentSkillId = 214264),
            preselectedTargets = listOf(target),
            probabilityAlreadyAccepted = true,
        ).stateChanges.filterIsInstance<ReferencedValueChange>().single().delta
        state.runtime.addCounter(
            target,
            PIBING_BURN_GROWTH_COUNTER,
            delta = growthDelta,
        )
        return SkillExecutionResult.immutable(
            stateChanges = boostedChanges,
            events = base.events,
            executedSkillIds = base.executedSkillIds,
            diagnostics = base.diagnostics,
            timingDues = base.timingDues,
        )
    }

    private fun huangtianDamageResult(
        output: BattleStateOutput.DamageDealt,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (output.amount <= 0 ||
            output.skillId != 200008 ||
            output.effectId != 306 ||
            DamageTag.ONGOING !in output.tags ||
            200008 !in state.liveHero(output.source).skillIds
        ) {
            return SkillExecutionResult.EMPTY
        }
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 20000802 },
            context.copy(
                source = output.source,
                rootSkillId = 200008,
                currentSkillId = 200008,
                trigger = BattleTrigger.DAMAGE_AFTER,
            ),
            preselectedTargets = listOf(output.source),
        )
    }

    private fun xianmingOngoingDamageResult(
        output: BattleStateOutput.DamageDealt,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (output.amount <= 0 || DamageTag.ONGOING !in output.tags) {
            return SkillExecutionResult.EMPTY
        }
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side != output.target.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200254 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        if (state.runtime.hasMarker(output.target, 21125401, context.round)) {
            return SkillExecutionResult.EMPTY
        }
        state.runtime.recordMarker(
            target = output.target,
            detailId = 21125401,
            value = 1,
            appliedRound = context.round,
            durationRounds = 1,
        )
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 21225401 },
            context.copy(
                source = owner,
                rootSkillId = 200254,
                currentSkillId = 212254,
                trigger = BattleTrigger.DAMAGE_AFTER,
            ),
            preselectedTargets = listOf(output.target),
        )
    }

    private fun qixurulinStrategySplashResult(
        output: BattleStateOutput.DamageDealt,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (context.round <= 0 ||
            output.amount <= 0 ||
            output.school != DamageSchool.STRATEGY ||
            DamageTag.IMPERIAL_SEAL_RELEASE in output.tags ||
            output.skillId == 210282
        ) {
            return SkillExecutionResult.EMPTY
        }
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == output.source.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200282 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        val adjacent = state.view.heroes().filter { candidate ->
            candidate.side == output.target.side &&
                candidate != output.target &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                kotlin.math.abs(candidate.position - output.target.position) == 1
        }
        val ownerHero = state.liveHero(owner)
        val skillIndex = ownerHero.skillIds.indexOf(200282)
        val skillLevel = ownerHero.skillLevels.getOrElse(skillIndex) { 1 }.coerceIn(1, 10)
        val configured = DefaultBattleValueCalculator().effectValue(
            graph.details.single { it.detailId == 20028212 },
            ownerHero,
            skillLevel,
        )
        val basePercent = when (configured) {
            is TypedBattlePotency.Resolved -> configured.value
            is TypedBattlePotency.Deferred ->
                error("Unsupported qixurulin value: ${configured.diagnostic}")
        }
        val percent = basePercent + state.runtime.referencedValueDelta(
            owner,
            200282,
            20028212,
        )
        val amount = (output.amount * percent / 100).coerceAtLeast(1)
        return SkillExecutionResult.immutable(
            stateChanges = adjacent.map { target ->
                TroopDamageChange(
                    source = output.source,
                    target = target,
                    amount = amount,
                    troopsAfter = (
                        requireNotNull(state.view.state(target)).troops - amount
                        ).coerceAtLeast(0),
                    school = DamageSchool.STRATEGY,
                    origin = output.origin,
                    tags = output.tags,
                    skillId = 210282,
                    effectId = 302,
                )
            },
            events = listOf(
                SkillTriggered(
                    round = context.round,
                    source = owner,
                    rootSkillId = 200282,
                    skillId = 210282,
                    trigger = BattleTrigger.DAMAGE_AFTER,
                ),
            ),
            executedSkillIds = listOf(210282),
            diagnostics = emptyList(),
        )
    }

    private fun juxianStatApplyingResult(
        change: BattleStatChange,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (context.round <= 0 || !applier.willApply(change)) return SkillExecutionResult.EMPTY
        val owner = state.view.heroes().firstOrNull { candidate ->
            state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200269 in state.liveHero(candidate).skillIds &&
                (
                    change.potency.value > 0 && candidate.side == change.target.side ||
                        change.potency.value < 0 && candidate.side != change.target.side
                    )
        } ?: return SkillExecutionResult.EMPTY
        val detailId = if (change.potency.value > 0) 21326901 else 21426901
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == detailId },
            context.copy(
                source = owner,
                rootSkillId = 200269,
                currentSkillId = detailId / 100,
                trigger = BattleTrigger.EFFECT_APPLYING,
            ),
            preselectedTargets = listOf(change.target),
        )
    }

    private fun shenshidingjiEffectApplyingResult(
        change: BattleStatChange,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (context.round <= 0 || change.potency.value >= 0) {
            return SkillExecutionResult.EMPTY
        }
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == change.source.side &&
                candidate.side != change.target.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200257 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 21025701 },
            context.copy(
                source = owner,
                rootSkillId = 200257,
                currentSkillId = 210257,
                trigger = BattleTrigger.EFFECT_APPLYING,
            ),
            preselectedTargets = listOf(change.target),
        )
    }

    private fun qiqinqizongGuardResult(
        target: BattleHeroRef,
        context: SkillBattleContext,
    ): QiqinqizongGuardResult? {
        if (context.round <= 0) return null
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == target.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200298 in state.liveHero(candidate).skillIds
        } ?: return null
        if (!state.runtime.consumeLimitedOccurrence(
                owner = owner,
                namespace = QIQIN_PROTECTED_EVENTS,
                limit = 7,
            )
        ) {
            return null
        }
        val completion =
            if (state.runtime.limitedOccurrenceCount(owner, QIQIN_PROTECTED_EVENTS) == 7) {
                qiqinqizongFinalResult(owner, context)
            } else {
                SkillExecutionResult.EMPTY
            }
        val ownerHero = state.liveHero(owner)
        val skillIndex = ownerHero.skillIds.indexOf(200298)
        val skillLevel = ownerHero.skillLevels.getOrElse(skillIndex) { 1 }.coerceIn(1, 10)
        val detail = graph.details.single { it.detailId == 21029812 }
        val probability = (
            detail.raw.probabilityInit +
                (skillLevel - 1) *
                (detail.raw.probabilityMax - detail.raw.probabilityInit) / 9.0
            ).toInt().coerceIn(0, 100)
        return QiqinqizongGuardResult(
            guarded = context.random.nextInt(100) < probability,
            completion = completion,
        )
    }

    private fun qiqinqizongFinalResult(
        owner: BattleHeroRef,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val target = state.view.heroes()
            .filter { candidate ->
                candidate.side != owner.side &&
                    state.view.state(candidate)?.troops?.let { it > 0 } == true
            }
            .maxByOrNull(state.view::accumulatedDamageDealt)
            ?: return SkillExecutionResult.EMPTY
        val finalContext = context.copy(
            source = owner,
            rootSkillId = 200298,
            currentSkillId = 214298,
            trigger = BattleTrigger.EFFECT_APPLYING,
        )
        val details = if (state.view.metadata(owner)?.country == 3) {
            listOf(graph.details.single { it.detailId == 21429803 })
        } else {
            val probabilityDetail = graph.details.single { it.detailId == 21129813 }
            if (!interpreter.detailProbabilitySucceedsForEngine(
                    probabilityDetail,
                    finalContext.copy(currentSkillId = 211298),
                )
            ) {
                return SkillExecutionResult.EMPTY
            }
            listOf(21429801, 21429802).map { detailId ->
                graph.details.single { it.detailId == detailId }
            }
        }
        return details.fold(SkillExecutionResult.EMPTY) { aggregate, detail ->
            val immediate = interpreter.executeDetailForEngine(
                detail = detail,
                context = finalContext,
                preselectedTargets = listOf(target),
                probabilityAlreadyAccepted = true,
            )
            aggregate + SkillExecutionResult.immutable(
                stateChanges = immediate.stateChanges.map { change ->
                    ScheduledTimingChange(
                        snapshot = DelayedEffect(
                            source = owner,
                            rootSkillId = 200298,
                            skillId = 214298,
                            detailId = detail.detailId,
                            dueRound = 0,
                        ),
                        delayRound = 1,
                        delayHit = 0,
                        change = change,
                    )
                },
                events = emptyList(),
                executedSkillIds = emptyList(),
                diagnostics = immediate.diagnostics,
            )
        }
    }

    private fun chijieDamageBeforeResult(
        change: TroopDamageChange,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        chijieSourceDamageBeforeResult(change, context) +
            chijieTargetDamageBeforeResult(change, context)

    private fun chijieSourceDamageBeforeResult(
        change: TroopDamageChange,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val sourceOwner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == change.source.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200989 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        val detailId = when (change.school) {
            DamageSchool.PHYSICAL -> 21398901
            DamageSchool.STRATEGY -> 21498901
        }
        val detail = graph.details.single { it.detailId == detailId }
        return interpreter.executeDetailForEngine(
            detail,
            context.copy(
                source = sourceOwner,
                rootSkillId = 200989,
                currentSkillId = detailId / 100,
                trigger = BattleTrigger.DAMAGE_BEFORE,
            ),
            preselectedTargets = listOf(change.source),
            valueOverride = chijiePotency(sourceOwner, detail),
        )
    }

    private fun chijieTargetDamageBeforeResult(
        change: TroopDamageChange,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        val targetOwner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == change.target.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200989 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        val detail = graph.details.single { it.detailId == 21598901 }
        return interpreter.executeDetailForEngine(
            detail,
            context.copy(
                source = targetOwner,
                rootSkillId = 200989,
                currentSkillId = 215989,
                trigger = BattleTrigger.DAMAGE_BEFORE,
            ),
            preselectedTargets = listOf(change.target),
            valueOverride = chijiePotency(targetOwner, detail),
        )
    }

    private fun chijiePotency(
        owner: BattleHeroRef,
        detail: SkillEffectRule,
    ): TypedBattlePotency.Resolved {
        val hero = state.liveHero(owner)
        val attribute = when (detail.coefficientSource) {
            BattleCoefficientSource.ATTACK -> hero.stats.precise(BattleStat.ATTACK)
            BattleCoefficientSource.DEFENSE -> hero.stats.precise(BattleStat.DEFENSE)
            BattleCoefficientSource.STRATEGY -> hero.stats.precise(BattleStat.STRATEGY)
            BattleCoefficientSource.SPEED -> hero.stats.precise(BattleStat.SPEED)
            BattleCoefficientSource.NONE -> 0.0
        }
        val skillIndex = hero.skillIds.indexOf(200989)
        val skillLevel = hero.skillLevels.getOrElse(skillIndex) { 1 }.coerceIn(1, 10)
        val ratio = detail.raw.initEffectRatio +
            (skillLevel - 1) * (100 - detail.raw.initEffectRatio) / 9.0
        val exactValue = (
            detail.raw.constantParam +
                detail.raw.intelParam * attribute / 200.0
            ) / 100.0 * ratio / 100.0
        return TypedBattlePotency.flat(exactValue.roundToInt(), exactValue)
    }

    private fun recalculateDirectDamage(
        change: TroopDamageChange,
    ): TroopDamageChange {
        val calculation = change.calculation ?: return change
        val targetTroops = requireNotNull(state.view.state(change.target)).troops
        val amount = calculation.calculate(
            source = change.sourceSnapshot ?: state.liveHero(change.source),
            target = state.liveHero(change.target),
            school = change.school,
            origin = change.origin,
            tags = change.tags,
        ).coerceAtMost(targetTroops)
        return change.copy(
            amount = amount,
            troopsAfter = (targetTroops - amount).coerceAtLeast(0),
        )
    }

    private fun zhongkeDamageResult(
        output: BattleStateOutput.DamageDealt,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (output.amount <= 0 ||
            output.school != DamageSchool.PHYSICAL ||
            output.skillId == 212268 ||
            !state.runtime.hasMarker(output.target, 20026811, context.round)
        ) {
            return SkillExecutionResult.EMPTY
        }
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side != output.target.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                200268 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        if (!state.runtime.consumeLimitedOccurrence(
                owner = owner,
                namespace = "skill.200268.marked-attack",
                limit = 2,
            )
        ) {
            return SkillExecutionResult.EMPTY
        }
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 21226811 },
            context.copy(
                source = owner,
                rootSkillId = 200268,
                currentSkillId = 212268,
                trigger = BattleTrigger.DAMAGE_AFTER,
            ),
            preselectedTargets = listOf(output.target),
        )
    }

    private fun tianziRoundEndResult(context: SkillBattleContext): SkillExecutionResult {
        if (200270 !in state.liveHero(context.source).skillIds) return SkillExecutionResult.EMPTY
        val target = state.view.heroes().firstOrNull { candidate ->
            candidate.side != context.source.side &&
                state.runtime.hasMarker(candidate, 21027012, context.round) &&
                state.runtime.roundHurtCount(candidate, context.round) >= 2
        } ?: return SkillExecutionResult.EMPTY
        val listenerContext = context.copy(
                rootSkillId = 200270,
                currentSkillId = 212270,
                trigger = BattleTrigger.ROUND_END,
        )
        return graph.rule(212270)!!.details.fold(SkillExecutionResult.EMPTY) { result, detail ->
            result + interpreter.executeDetailForEngine(
                detail,
                listenerContext,
                preselectedTargets = listOf(target),
            )
        }
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
        var sourceHero = liveHero(source)
        var targetHero = liveHero(target)
        if (sourceHero.troops <= 0 || targetHero.troops <= 0) return emptyList()
        if (actionResolver.selectNormalAttackTarget(sourceHero, listOf(targetHero), random = null) == null) {
            return emptyList()
        }
        val damageContext = context.copy(
            round = round,
            source = source,
            trigger = BattleTrigger.DAMAGE_BEFORE,
        )
        val events = mutableListOf<BattleEvent>()
        events += apply(
            chijieDamageBeforeResult(
                TroopDamageChange(
                    source = source,
                    target = target,
                    amount = 0,
                    troopsAfter = targetHero.troops,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.NORMAL,
                    tags = emptySet(),
                    skillId = effect.skillId,
                    effectId = effectId,
                ),
                damageContext,
            ),
            damageContext,
        )
        sourceHero = liveHero(source)
        targetHero = liveHero(target)
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
        events += processDamageOutputs(result, context.copy(round = round, source = source))
        events += processDamageOutputs(
            applier.consumeEffectHit(
                target = source,
                effectId = effectId,
                source = effect.source,
                detailId = effect.detailId,
            ),
            context.copy(round = round, source = source),
        )
        return events
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

    fun finishRound(round: Int): List<BattleEvent> {
        state.runtime.advanceReferencedValueChanges(round)
        return applier.onRoundEnd(round).toEvents(round)
    }

    private fun executeBattleSkill(
        trigger: BattleTrigger,
        context: SkillBattleContext,
        skillId: Int,
    ): SkillExecutionResult {
        if (trigger == BattleTrigger.BATTLE_PASSIVE && skillId == 200987) {
            return SkillExecutionResult.immutable(
                stateChanges = emptyList(),
                events = listOf(
                    SkillTriggered(
                        round = context.round,
                        source = context.source,
                        rootSkillId = skillId,
                        skillId = skillId,
                        trigger = trigger,
                    ),
                ),
                executedSkillIds = listOf(skillId),
                diagnostics = emptyList(),
            )
        }
        if (trigger == BattleTrigger.BATTLE_COMMAND && skillId == 200961) {
            return SkillExecutionResult.immutable(
                stateChanges = emptyList(),
                events = listOf(
                    SkillTriggered(
                        round = context.round,
                        source = context.source,
                        rootSkillId = skillId,
                        skillId = skillId,
                        trigger = trigger,
                    ),
                ),
                executedSkillIds = listOf(skillId),
                diagnostics = emptyList(),
            )
        }
        val skillContext = context.copy(
            rootSkillId = skillId,
            currentSkillId = skillId,
        )
        specialPlugins.pluginFor(skillId)?.takeIf {
            trigger == BattleTrigger.BATTLE_COMMAND
        }?.let { plugin ->
            val pluginResult = pluginTriggeredResult(skillId, trigger, skillContext, plugin)
            if (plugin.replacesConfiguredExecution) {
                return pluginResult
            }
            return interpreter.execute(skillId, trigger, skillContext) + pluginResult
        }
        return interpreter.execute(skillId, trigger, skillContext)
    }

    private fun attemptSkills(
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): SkillExecutionResult =
        skillsFor(context.source, trigger).fold(SkillExecutionResult.EMPTY) { result, skillId ->
            val attemptsBefore = activePursuitAttemptCount(context.source)
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
                result + attempt + attemptThresholdResult(
                    context,
                    attemptsBefore,
                    activePursuitAttemptCount(context.source),
                )
            }
        }

    private fun activePursuitAttemptCount(source: BattleHeroRef): Int =
        state.runtime.attemptCount(source, BattleTrigger.ACTIVE_SKILL_ATTEMPT) +
            state.runtime.attemptCount(source, BattleTrigger.PURSUIT_ATTEMPT)

    private fun attemptThresholdResult(
        context: SkillBattleContext,
        before: Int,
        after: Int,
    ): SkillExecutionResult {
        if (after <= before || 200253 !in state.liveHero(context.source).skillIds) {
            return SkillExecutionResult.EMPTY
        }
        if (!state.runtime.consumeThreshold(
                owner = context.source,
                namespace = "skill.200253.active-pursuit-attempt",
                count = after,
                threshold = 3,
            )
        ) {
            return SkillExecutionResult.EMPTY
        }
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 20025301 },
            context.copy(rootSkillId = 200253, currentSkillId = 200253),
        )
    }

    private fun zhengshiActionResult(context: SkillBattleContext): SkillExecutionResult {
        if (200244 !in state.liveHero(context.source).skillIds ||
            !state.runtime.consumeSignal(context.source, ZHENGSHI_SIGNAL, context.round)
        ) {
            return SkillExecutionResult.EMPTY
        }
        return interpreter.executeDetailForEngine(
            graph.details.single { it.detailId == 20024406 },
            context.copy(rootSkillId = 200244, currentSkillId = 200244),
        )
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
                            if (detail.effectId == 305) add(DamageTag.BURN)
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
        changeLoop@ for ((changeIndex, change) in result.stateChanges.withIndex()) {
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
                    val scheduled = if (change.change is TroopDamageChange) {
                        events += apply(
                            chijieSourceDamageBeforeResult(change.change, context),
                            context,
                        )
                        change.copy(
                            change = change.change.copy(
                                sourceSnapshot = state.liveHero(change.change.source),
                            ),
                        )
                    } else {
                        change
                    }
                    val position = timing.position()
                    timing.enqueue(scheduled, context.round.coerceAtLeast(1), position.hit)
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
                            effectId = change.effectId,
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
                is TransformAndCastRandomActiveSkillChange,
                -> Unit
                is TriggerLastAppliedEffectChange -> {
                    change.appliedSpec?.let { appliedSpec ->
                        events += processDamageOutputs(
                            applier.triggerAppliedOngoingDamage(appliedSpec, context.round),
                            context,
                        )
                    }
                }
                is TriggerSpecifiedEffectChange -> {
                    events += processDamageOutputs(
                        applier.triggerSpecifiedOngoingDamage(
                            target = change.target,
                            effectId = change.triggeredEffectId,
                            round = context.round,
                        ),
                        context,
                    )
                }
                is MetaEffectChange -> {
                    when (change.operation) {
                        MetaEffectOperation.SKILL_RANGE_INCREASE -> {
                            val kinds = when (change.parameters.selectSkillParameter) {
                                3 -> listOf(SkillKind.ACTIVE)
                                4 -> listOf(SkillKind.PURSUIT)
                                else -> listOf(SkillKind.ACTIVE, SkillKind.PURSUIT)
                            }
                            kinds.forEach { kind ->
                                events += state.applySkillRangeChange(
                                    change,
                                    kind,
                                    change.parameters.constant,
                                    context.round,
                                )
                            }
                        }
                        MetaEffectOperation.SKILL_RANGE_DECREASE -> {
                            listOf(SkillKind.ACTIVE, SkillKind.PURSUIT).forEach { kind ->
                                events += state.applySkillRangeChange(
                                    change,
                                    kind,
                                    -change.parameters.constant,
                                    context.round,
                                )
                            }
                        }
                        else -> throw UnsupportedBattleStateChangeException(change)
                    }
                }
                is MoraleEffectChange ->
                    events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                is ModifierEffectChange ->
                    events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                is NamedFlagCounterChange ->
                    state.runtime.addCounter(
                        owner = change.target,
                        namespace = "skill.named-flag.${change.flagId}",
                        delta = change.delta,
                        maximum = change.maximum,
                    )
                is SimulatedNormalAttackChange ->
                    events += executeSimulatedNormalAttack(change, context)
                is MarkerEffectChange,
                is ReferencedExtraParameterChange,
                is ReferencedValueChange,
                -> Unit
                is DamageModifierChange ->
                    events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                is ApplyBattleEffectChange -> {
                    val durationExtension = if (statusForControl(change.spec.effectId) != null) {
                        applier.matchingControlDurationExtensions(
                            actor = change.spec.source,
                            rootSkillId = change.spec.rootSkillId,
                            skillKind = change.spec.skillKind,
                        )
                    } else {
                        ControlDurationExtensionMatch(0, emptyList())
                    }
                    val extendedChange = if (durationExtension.rounds > 0) {
                        change.copy(
                            spec = change.spec.copy(
                                availableRounds =
                                    change.spec.availableRounds + durationExtension.rounds,
                            ),
                        )
                    } else {
                        change
                    }
                    val eligibleForGuard = change.spec.category ==
                        com.stzb.server.game.battle.EffectCategory.HARMFUL &&
                        change.spec.effectId in QIQIN_CONTROL_EFFECT_IDS
                    val guard = if (eligibleForGuard) {
                        qiqinqizongGuardResult(change.spec.target, context)
                    } else {
                        null
                    }
                    events += apply(guard?.completion ?: SkillExecutionResult.EMPTY, context)
                    val blocked = guard?.guarded == true
                    val applied = if (blocked) {
                        EffectBlockedChange(
                            source = change.spec.source,
                            target = change.spec.target,
                            skillId = change.spec.skillId,
                            effectId = change.spec.effectId,
                            blockingEffectId = 118,
                        )
                    } else {
                        extendedChange
                    }
                    val appliedResult = applier.apply(listOf(applied), context.round)
                    events += processDamageOutputs(appliedResult, context)
                    val successfullyExtended = durationExtension.rounds > 0 &&
                        appliedResult.outputs.any { output ->
                            output is BattleStateOutput.EffectApplied &&
                                output.spec.detailId == change.spec.detailId
                        }
                    if (successfullyExtended) {
                        events += processDamageOutputs(
                            applier.consumeControlDurationExtensions(durationExtension),
                            context,
                        )
                    }
                }
                is BattleStatChange -> {
                    events += apply(
                        shenshidingjiEffectApplyingResult(change, context),
                        context,
                    )
                    events += apply(juxianStatApplyingResult(change, context), context)
                    events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                }
                is TroopDamageChange -> {
                    val guard = qiqinqizongGuardResult(change.target, context)
                    events += apply(guard?.completion ?: SkillExecutionResult.EMPTY, context)
                    if (guard?.guarded == true) {
                        events += BattleEvent.Evaded(context.round, change.source, change.target)
                        continue@changeLoop
                    }
                    val chijie = if (change.sourceSnapshot == null) {
                        chijieDamageBeforeResult(change, context)
                    } else {
                        chijieTargetDamageBeforeResult(change, context)
                    }
                    events += apply(chijie, context)
                    val recalculated = recalculateDirectDamage(change)
                    val pibing = pibingjuyiDamageBeforeResult(recalculated, context)
                    events += processDamageOutputs(
                        applier.apply(listOf(pibing.change), context.round),
                        context,
                    )
                    events += apply(
                        pibingjuyiBurnResult(pibing.owner, pibing.change.source, context),
                        context,
                    )
                }
                is ClearReferencedEffectChange -> {
                    events += processDamageOutputs(applier.apply(listOf(change), context.round), context)
                }
                is ReduceReferencedEffectUseChange,
                is ConsumeEffectUseChange,
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
            if (output.amount > 0) {
                state.runtime.recordRoundHurt(output.target, context.round)
                recordDamageThresholds(output.source, context)
                if (isBaizhanOwner(output.source)) {
                    state.runtime.addCounter(
                        owner = output.source,
                        namespace = BAIZHAN_STACKS,
                        delta = 1,
                        maximum = BAIZHAN_MAX_STACKS,
                    )
                }
                events += apply(
                    huangtianDamageResult(output, damageContext),
                    damageContext,
                )
                events += apply(
                    xianmingOngoingDamageResult(output, damageContext),
                    damageContext,
                )
                events += apply(
                    qixurulinStrategySplashResult(output, damageContext),
                    damageContext,
                )
                events += apply(
                    zhongkeDamageResult(output, damageContext),
                    damageContext,
                )
                events += apply(
                    xinzhanDamageResult(
                        output.source,
                        output.target,
                        state.runtime.sideCount(output.source.side, BattleTrigger.DAMAGE_AFTER),
                        damageContext,
                    ),
                    damageContext,
                )
                events += apply(
                    huiyanDamageResult(
                        output.source,
                        state.runtime.sideCount(output.source.side, BattleTrigger.DAMAGE_AFTER),
                        damageContext,
                    ),
                    damageContext,
                )
            }
            events += trigger(BattleTrigger.DAMAGE_AFTER, damageContext)
            val hurtContext = context.copy(source = output.target, trigger = BattleTrigger.HURT_AFTER)
            val hurtCount = state.runtime.recordBattleTriggerOccurrence(
                output.target,
                BattleTrigger.HURT_AFTER,
            )
            if (output.amount > 0) {
                events += apply(
                    manwangHurtResult(output.target, hurtCount, hurtContext),
                    hurtContext,
                )
            }
            events += trigger(BattleTrigger.HURT_AFTER, hurtContext)
            if (output.amount > 0) {
                events += apply(
                    baizhanSpendResult(output.target, hurtContext),
                    hurtContext,
                )
                events += emergencyRecoveryEvents(output.target, hurtContext)
            }
            events += apply(timing.onHit(damageContext), damageContext)
            if (output.amount > 0) {
                events += apply(
                    tongchouHurtResult(output.target, hurtContext),
                    hurtContext,
                )
            }
        }
        result.outputs.filterIsInstance<BattleStateOutput.TroopsRecovered>()
            .filter { it.amount > 0 }
            .forEach { output ->
                val registrationOwner = state.effectStore.effectsFor(output.target)
                    .firstOrNull { effect ->
                        effect.effectId == output.effectId &&
                            effect.skillId == output.skillId &&
                            effect.source == output.source
                    }
                    ?.source
                val recoveryOwner = registrationOwner ?: output.source
                val recoveryCount = state.runtime.recordBattleTriggerOccurrence(
                    recoveryOwner,
                    BattleTrigger.RECOVERY_AFTER,
                )
                if (
                    output.skillId == 200016 &&
                    200016 in state.liveHero(recoveryOwner).skillIds &&
                    state.runtime.consumeThreshold(
                        owner = recoveryOwner,
                        namespace = "skill.200016.actual-recovery",
                        count = recoveryCount,
                        threshold = 3,
                    )
                ) {
                    val listenerContext = context.copy(
                        source = recoveryOwner,
                        rootSkillId = 200016,
                        currentSkillId = 200016,
                        trigger = BattleTrigger.RECOVERY_AFTER,
                    )
                    val listenerDetail = graph.details.single { it.detailId == 20001602 }
                    events += apply(
                        interpreter.executeDetailForEngine(listenerDetail, listenerContext),
                        listenerContext,
                    )
                }
            }
        result.outputs.filterIsInstance<BattleStateOutput.EffectApplied>()
            .filter { context.round >= 3 }
            .forEach { output ->
                val owner = state.view.heroes().firstOrNull { candidate ->
                    candidate.side != output.spec.target.side &&
                        state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                        200254 in state.liveHero(candidate).skillIds
                }
                if (owner != null) {
                    val appliedContext = context.copy(
                        source = owner,
                        rootSkillId = 200254,
                        currentSkillId = 214254,
                        trigger = BattleTrigger.EFFECT_APPLIED,
                    )
                    val triggerResult = interpreter.executeDetailForEngine(
                            graph.details.single { it.detailId == 21425401 },
                            appliedContext,
                            preselectedTargets = listOf(output.spec.target),
                        )
                    events += apply(
                        SkillExecutionResult.immutable(
                            triggerResult.stateChanges.map { change ->
                                if (change is TriggerLastAppliedEffectChange) {
                                    change.copy(appliedSpec = output.spec)
                                } else {
                                    change
                                }
                            },
                            triggerResult.events,
                            triggerResult.executedSkillIds,
                            triggerResult.diagnostics,
                            triggerResult.timingDues,
                        ),
                        appliedContext,
                    )
                }
            }
        events += result.outputs
            .filterNot { it is BattleStateOutput.DamageDealt || it is BattleStateOutput.HurtReceived }
            .let(::BattleStateApplyResult)
            .toEvents(context.round)
        return events
    }

    private fun emergencyRecoveryEvents(
        target: BattleHeroRef,
        context: SkillBattleContext,
    ): List<BattleEvent> =
        state.effectStore.effectsFor(target)
            .filter { effect -> effect.effectId == 401 && effect.detailId > 0 }
            .flatMap { effect ->
                val detail = graph.details.singleOrNull { it.detailId == effect.detailId }
                    ?: return@flatMap emptyList()
                val increment = graph.details
                    .singleOrNull { it.detailId == 21101601 && it.raw.effectParam == detail.detailId }
                    ?.raw
                    ?.constantParam
                    ?: 0
                val actualRecoveries = state.runtime.count(
                    effect.source,
                    BattleTrigger.RECOVERY_AFTER,
                )
                val probability = (
                    detail.raw.probabilityInit + actualRecoveries / 3 * increment
                    ).coerceAtMost(100)
                if (context.random.nextInt(100) >= probability) {
                    emptyList()
                } else {
                    val recoveryContext = context.copy(
                        source = effect.source,
                        rootSkillId = effect.rootSkillId,
                        currentSkillId = effect.skillId,
                        trigger = BattleTrigger.HURT_AFTER,
                    )
                    apply(
                        interpreter.executeDetailForEngine(
                            detail = detail,
                            context = recoveryContext,
                            preselectedTargets = listOf(target),
                            probabilityAlreadyAccepted = true,
                        ),
                        recoveryContext,
                    )
                }
            }

    private fun tongchouHurtResult(
        hurtTarget: BattleHeroRef,
        context: SkillBattleContext,
    ): SkillExecutionResult {
        if (context.round <= 0) return SkillExecutionResult.EMPTY
        val owner = state.view.heroes().firstOrNull { candidate ->
            candidate.side == hurtTarget.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                201006 in state.liveHero(candidate).skillIds
        } ?: return SkillExecutionResult.EMPTY
        val targets = state.view.heroes().filter { candidate ->
            candidate.side == hurtTarget.side &&
                state.view.state(candidate)?.troops?.let { it > 0 } == true &&
                kotlin.math.abs(candidate.position - hurtTarget.position) <= 1
        }
        val listenerContext = context.copy(
            source = owner,
            rootSkillId = 201006,
            currentSkillId = 201006,
            trigger = BattleTrigger.HURT_AFTER,
        )
        return listOf(20100601, 20100602).fold(SkillExecutionResult.EMPTY) { result, detailId ->
            result + interpreter.executeDetailForEngine(
                graph.details.single { it.detailId == detailId },
                listenerContext,
                preselectedTargets = targets,
            )
        }
    }

    private fun BattleStateApplyResult.toEvents(round: Int): List<BattleEvent> =
        outputs.flatMap { output ->
            when (output) {
                is BattleStateOutput.EffectApplied -> emptyList()
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
                    add(output.toEvent(round))
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
                                effectId = change.effectId,
                            ),
                        )
                    }
                }
                is BattleStateOutput.ModifierApplied -> listOf(
                    output.change.let { change ->
                        BattleEvent.ModifierApplied(
                            round = round,
                            source = change.source,
                            target = change.target,
                            skillId = change.skillId,
                            effectId = change.effectId,
                            amount = change.percent,
                            durationRounds = change.durationRounds,
                        )
                    },
                )
                is BattleStateOutput.DamageAbsorbed -> emptyList()
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

    private fun BattleStateOutput.StatChanged.toEvent(round: Int): BattleEvent.StatChanged {
        val change = change
        return BattleEvent.StatChanged(
            round,
            change.source,
            change.target,
            when (change.kind) {
                BattleStatChange.Kind.ATTACK -> BattleStat.ATTACK
                BattleStatChange.Kind.DEFENSE -> BattleStat.DEFENSE
                BattleStatChange.Kind.STRATEGY -> BattleStat.STRATEGY
                BattleStatChange.Kind.SPEED -> BattleStat.SPEED
                BattleStatChange.Kind.SIEGE -> BattleStat.SIEGE
                BattleStatChange.Kind.ATTACK_RANGE -> BattleStat.HIT_RANGE
            },
            delta,
            change.durationRounds,
            change.skillId,
            change.effectId,
            strength = strength,
            valueAfter = valueAfter,
            deltaExact = deltaExact,
            valueAfterExact = valueAfterExact,
            unit = change.potency.unit,
        )
    }

    companion object {
        private const val DISORDER_SKILL_ID = 200002
        private const val QIQIN_PROTECTED_EVENTS = "skill.200298.protected-events"
        private const val FUBO_UPLIFT_COUNTER = "skill.200255.normal-damage-uplift"
        private const val FUBO_LAYER_COUNTER = "skill.200255.yangsha-layers"
        private const val FUBO_LAYER_THRESHOLD = 40
        private const val FUBO_LAYERS_PER_EXTRA_ATTACK = 4
        private const val FUBO_MAX_LAYERS = 20
        private const val PIBING_BIRUI_LAYER_COUNTER = "skill.200264.birui-layers"
        private const val PIBING_BURN_GROWTH_COUNTER = "skill.200264.burn-growth"
        private const val PIBING_LAYERS_PER_ROUND = 2
        private const val PIBING_MAX_LAYERS = 99
        private val QIQIN_CONTROL_EFFECT_IDS =
            setOf(501, 502, 503, 505, 552, 701, 702, 703, 752, 901, 902, 903, 952)

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
    501, 701, 901 -> com.stzb.server.game.battle.BattleStatus.CONFUSION
    502, 702, 902 -> com.stzb.server.game.battle.BattleStatus.HESITATION
    503, 703, 903 -> com.stzb.server.game.battle.BattleStatus.BERSERK
    552, 752, 952 -> com.stzb.server.game.battle.BattleStatus.DISARM
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
