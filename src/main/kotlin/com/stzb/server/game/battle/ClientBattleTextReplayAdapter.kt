package com.stzb.server.game.battle

import com.stzb.server.game.battle.skill.BattleTrigger
import java.util.logging.Logger

internal class UnsupportedBattleReportProjectionException(message: String) :
    IllegalArgumentException(message)

internal object ClientBattleTextReplayAdapter {
    private val logger = Logger.getLogger(ClientBattleTextReplayAdapter::class.java.name)

    fun adapt(
        result: BattleResult,
        diagnostic: (String) -> Unit = logger::warning,
    ): List<ClientReportAction> = adapt(result, strict = false, diagnostic)

    fun adaptStrict(result: BattleResult): List<ClientReportAction> =
        adapt(result, strict = true) { throw UnsupportedBattleReportProjectionException(it) }

    private fun adapt(
        result: BattleResult,
        strict: Boolean,
        diagnostic: (String) -> Unit,
    ): List<ClientReportAction> {
        val actions = mutableListOf<ClientReportAction>()
        val heroes = (
            result.attacker.heroes.map { Side.ATTACKER to it } +
                result.defender.heroes.map { Side.DEFENDER to it }
            )
            .sortedBy { (side, hero) -> ClientBattleTextReplayProtocol.position(side, hero.position) }
        heroes.forEach { (side, hero) ->
            actions += ClientReportAction(
                ClientBattleTextReplayProtocol.HERO_NAME,
                listOf(ClientBattleTextReplayProtocol.position(side, hero.position), hero.id.value),
            )
        }
        heroes.forEach { (side, hero) ->
            actions += heroInfo(side, hero)
        }
        actions += ClientReportAction(ClientBattleTextReplayProtocol.PREPARE)

        result.events.forEach { event ->
            when (event) {
                is BattleEvent.RoundStart -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.ROUND,
                    listOf(event.round),
                )
                is BattleEvent.HeroActionStart -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.HERO_ACTION_START,
                    listOf(ClientBattleTextReplayProtocol.position(event.source)),
                )
                is BattleEvent.HeroActionEnd -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.HERO_ACTION_END,
                    listOf(ClientBattleTextReplayProtocol.position(event.source)),
                )
                is BattleEvent.SkillPreparationStarted -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.SKILL_PREPARATION_STARTED,
                    listOf(
                        ClientBattleTextReplayProtocol.position(event.source),
                        event.skillId,
                    ),
                )
                is BattleEvent.SkillPreparationCancelled -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.SKILL_PREPARATION_CANCELLED,
                    listOf(
                        ClientBattleTextReplayProtocol.position(event.source),
                        event.skillId,
                    ),
                )
                is BattleEvent.SkillTriggered -> actions += ClientReportAction(
                    event.trigger.clientSkillAction(),
                    listOf(
                        ClientBattleTextReplayProtocol.position(event.source),
                        event.skillId,
                    ),
                )
                is BattleEvent.StatusRemoved -> actions += removedStatus(event)
                is BattleEvent.EffectExpired -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.STATUS_REMOVED,
                    listOf(
                        ClientBattleTextReplayProtocol.position(event.target),
                        ClientBattleTextReplayProtocol.position(event.source),
                        event.skillId,
                        event.effectId,
                    ),
                )
                is BattleEvent.EffectBlocked -> {
                    val action = blockedEffect(event)
                    if (action != null) {
                        actions += action
                    } else {
                        unsupported(
                            "Unsupported EffectBlocked projection: skill=${event.skillId} " +
                                "effect=${event.effectId} blocker=${event.blockingEffectId}",
                            strict,
                            diagnostic,
                        )
                    }
                }
                is BattleEvent.NormalAttack -> actions += listOf(
                    ClientReportAction(
                        ClientBattleTextReplayProtocol.NORMAL_ATTACK,
                        listOf(
                            ClientBattleTextReplayProtocol.position(event.source),
                            ClientBattleTextReplayProtocol.position(event.target),
                        ),
                    ),
                    ClientReportAction(ClientBattleTextReplayProtocol.SKILL_BEGIN),
                    ClientReportAction(
                        ClientBattleTextReplayProtocol.NORMAL_DAMAGE,
                        listOf(
                            ClientBattleTextReplayProtocol.position(event.target),
                            event.damage,
                            event.targetTroopsAfter,
                        ),
                    ),
                    ClientReportAction(ClientBattleTextReplayProtocol.SKILL_END),
                )
                is BattleEvent.SkillDamage -> {
                    actions += skillSegment(
                        event.source,
                        event.skillId,
                        listOf(
                            ClientReportAction(
                                ClientBattleTextReplayProtocol.SKILL_DAMAGE,
                                listOf(
                                    ClientBattleTextReplayProtocol.position(event.source),
                                    event.skillId,
                                    ClientBattleTextReplayProtocol.position(event.target),
                                    event.damage,
                                    event.targetTroopsAfter,
                                ),
                            ),
                        ),
                    )
                }
                is BattleEvent.Recovery -> {
                    if (event.skillId > 0) {
                        actions += skillSegment(
                            event.source,
                            event.skillId,
                            listOf(
                                ClientReportAction(
                                    ClientBattleTextReplayProtocol.RECOVERY,
                                    listOf(
                                        ClientBattleTextReplayProtocol.position(event.source),
                                        event.skillId,
                                        ClientBattleTextReplayProtocol.position(event.target),
                                        event.amount,
                                        event.targetTroopsAfter,
                                    ),
                                ),
                            ),
                        )
                    }
                }
                is BattleEvent.StatusApplied -> {
                    if (event.skillId > 0) {
                        actions += appliedStatusActions(event)
                    }
                }
                is BattleEvent.OngoingDamage -> {
                    if (event.skillId > 0) {
                        actions += ClientReportAction(
                            ClientBattleTextReplayProtocol.ONGOING_DAMAGE,
                            listOf(
                                ClientBattleTextReplayProtocol.position(event.source),
                                event.skillId,
                                ClientBattleTextReplayProtocol.position(event.target),
                                event.damage,
                                event.targetTroopsAfter,
                                ClientBattleTextReplayProtocol.effectId(event.status),
                            ),
                        )
                    }
                }
                is BattleEvent.Evaded -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.DAMAGE_EVADED,
                    listOf(ClientBattleTextReplayProtocol.position(event.target)),
                )
                is BattleEvent.StatChanged -> {
                    val effectId = ClientBattleTextReplayProtocol.effectId(event.stat, event.delta)
                    if (event.skillId > 0 && effectId != 0) {
                        actions += skillCast(event.target, event.skillId)
                    }
                }
                is BattleEvent.UnsupportedSkillEffect -> {
                    unsupported(
                        "Unsupported skill effect projection: skill=${event.skillId} " +
                            "effect=${event.effectId}",
                        strict,
                        diagnostic,
                    )
                }
                is BattleEvent.UnsupportedEquipmentEffect -> unsupported(
                    "Unsupported equipment effect projection: equipment=${event.equipmentId}",
                    strict,
                    diagnostic,
                )
                BattleEvent.BattleStart,
                is BattleEvent.TriggerPoint,
                is BattleEvent.SkillPreparationCompleted,
                is BattleEvent.RoundEnd,
                is BattleEvent.BattleEnd,
                -> Unit
            }
        }
        appendFinalization(actions, result)
        return actions
    }

    private fun BattleTrigger.clientSkillAction(): Int = when (this) {
        BattleTrigger.BATTLE_PASSIVE,
        BattleTrigger.BATTLE_COMMAND,
        -> ClientBattleTextReplayProtocol.SKILL_TRIGGERED_PASSIVE
        BattleTrigger.ACTIVE_SKILL_ATTEMPT ->
            ClientBattleTextReplayProtocol.SKILL_TRIGGERED_ACTIVE
        BattleTrigger.PURSUIT_ATTEMPT ->
            ClientBattleTextReplayProtocol.SKILL_TRIGGERED_PURSUIT
        else -> throw UnsupportedBattleReportProjectionException(
            "SkillTriggered cannot use trigger=$this",
        )
    }

    private fun removedStatus(event: BattleEvent.StatusRemoved) = ClientReportAction(
        ClientBattleTextReplayProtocol.STATUS_REMOVED,
        listOf(
            ClientBattleTextReplayProtocol.position(event.target),
            ClientBattleTextReplayProtocol.position(event.source),
            event.skillId,
            event.effectId,
        ),
    )

    private fun blockedEffect(event: BattleEvent.EffectBlocked): ClientReportAction? {
        val actionId = when {
            event.blockingEffectId == ClientBattleTextReplayProtocol.effectId(BattleStatus.EVADE) ->
                ClientBattleTextReplayProtocol.DAMAGE_EVADED
            event.effectId in setOf(501, 701, 901) -> 337
            event.effectId in setOf(503, 703, 903) -> 338
            event.effectId in setOf(502, 702, 902) -> 339
            event.effectId in setOf(552, 752, 952) -> 340
            else -> return null
        }
        return ClientReportAction(
            actionId,
            if (actionId == ClientBattleTextReplayProtocol.DAMAGE_EVADED) {
                listOf(ClientBattleTextReplayProtocol.position(event.target))
            } else {
                listOf(ClientBattleTextReplayProtocol.position(event.target), event.effectId)
            },
        )
    }

    private fun unsupported(
        message: String,
        strict: Boolean,
        diagnostic: (String) -> Unit,
    ) {
        if (strict) throw UnsupportedBattleReportProjectionException(message)
        diagnostic(message)
    }

    /**
     * Client BattleAnimationData.SetRoundData action 205 layout:
     * position, level, initialTroops, 3 * (skillId, skillLevel),
     * heroTypeFeatureId1, heroTypeFeatureId2.
     *
     * ReportDetailView.GetHeroShareInfo always reads both feature slots, so
     * they must be present even when the server has no feature data.
     */
    private fun heroInfo(side: Side, hero: BattleHero): ClientReportAction {
        val skills = hero.skillIds
            .take(3)
            .flatMap { skillId -> listOf(skillId, DEFAULT_SKILL_LEVEL) }
            .toMutableList()
            .apply {
                while (size < SKILL_SLOT_COUNT * 2) {
                    add(0)
                    add(0)
                }
            }
        return ClientReportAction(
            ClientBattleTextReplayProtocol.HERO_INFO,
            listOf(
                ClientBattleTextReplayProtocol.position(side, hero.position),
                hero.level,
                hero.maxTroops,
            ) + skills + listOf(0, 0),
        )
    }

    private fun skillCast(source: BattleHeroRef, skillId: Int): List<ClientReportAction> =
        if (skillId > 0) {
            listOf(
                ClientReportAction(
                    ClientBattleTextReplayProtocol.SKILL_CAST,
                    listOf(
                        ClientBattleTextReplayProtocol.position(source),
                        ClientBattleTextReplayProtocol.position(source),
                        skillId,
                    ),
                ),
            )
        } else {
            emptyList()
        }

    private fun skillSegment(
        source: BattleHeroRef,
        skillId: Int,
        effects: List<ClientReportAction>,
    ): List<ClientReportAction> =
        if (skillId > 0) {
            listOf(ClientReportAction(ClientBattleTextReplayProtocol.SKILL_BEGIN)) +
                skillCast(source, skillId) +
                effects +
                ClientReportAction(ClientBattleTextReplayProtocol.SKILL_END)
        } else {
            emptyList()
        }

    private fun appliedStatusActions(event: BattleEvent.StatusApplied): List<ClientReportAction> {
        val actionId = ClientBattleTextReplayProtocol.statusAppliedAction(event.status)
        val action = if (actionId == ClientBattleTextReplayProtocol.SKILL_CAST) {
            ClientReportAction(
                actionId,
                listOf(
                    ClientBattleTextReplayProtocol.position(event.target),
                    ClientBattleTextReplayProtocol.position(event.source),
                    event.skillId,
                ),
            )
        } else {
            ClientReportAction(
                actionId,
                listOf(
                    ClientBattleTextReplayProtocol.position(event.source),
                    event.skillId,
                    ClientBattleTextReplayProtocol.position(event.target),
                ),
            )
        }
        return skillSegment(event.source, event.skillId, listOf(action))
    }

    private fun appendFinalization(
        actions: MutableList<ClientReportAction>,
        result: BattleResult,
    ) {
        actions += ClientReportAction(ClientBattleTextReplayProtocol.END)
        actions += when (result.outcome) {
            BattleOutcome.ATTACKER_WIN ->
                ClientReportAction(ClientBattleTextReplayProtocol.ATTACKER_WIN)
            BattleOutcome.DEFENDER_WIN ->
                ClientReportAction(ClientBattleTextReplayProtocol.DEFENDER_WIN)
            BattleOutcome.DRAW ->
                ClientReportAction(ClientBattleTextReplayProtocol.DRAW, listOf(3))
        }
        val heroes = result.attacker.heroes
            .sortedBy(BattleHero::position)
            .map { Side.ATTACKER to it } +
            result.defender.heroes
                .sortedBy(BattleHero::position)
                .map { Side.DEFENDER to it }
        heroes.forEach { (side, hero) ->
            actions += ClientReportAction(
                ClientBattleTextReplayProtocol.FINAL_TROOPS,
                listOf(
                    ClientBattleTextReplayProtocol.position(side, hero.position),
                    hero.troops,
                    (hero.maxTroops - hero.troops).coerceAtLeast(0),
                ),
            )
        }
    }

    private const val SKILL_SLOT_COUNT = 3
    private const val DEFAULT_SKILL_LEVEL = 1
}
