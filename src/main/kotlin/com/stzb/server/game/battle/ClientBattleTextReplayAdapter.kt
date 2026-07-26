package com.stzb.server.game.battle

internal object ClientBattleTextReplayAdapter {
    fun adapt(result: BattleResult): List<ClientReportAction> {
        val actions = mutableListOf<ClientReportAction>()
        val projectedUnsupportedSkills = mutableSetOf<Triple<Int, BattleHeroRef, Int>>()
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
                        actions += statusActions(
                            source = event.source,
                            target = event.target,
                            skillId = event.skillId,
                            effectId = ClientBattleTextReplayProtocol.effectId(event.status),
                        )
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
                is BattleEvent.Evaded -> actions += statusActions(
                    source = event.source,
                    target = event.target,
                    skillId = 0,
                    effectId = ClientBattleTextReplayProtocol.effectId(BattleStatus.EVADE),
                )
                is BattleEvent.StatChanged -> {
                    val effectId = ClientBattleTextReplayProtocol.effectId(event.stat, event.delta)
                    if (event.skillId > 0 && effectId != 0) {
                        actions += statusActions(event.source, event.target, event.skillId, effectId)
                    }
                }
                is BattleEvent.UnsupportedSkillEffect -> {
                    val key = Triple(event.round, event.source, event.skillId)
                    if (event.skillId > 0 && projectedUnsupportedSkills.add(key)) {
                        actions += skillSegment(event.source, event.skillId, emptyList())
                    }
                }
                is BattleEvent.UnsupportedEquipmentEffect -> Unit
                is BattleEvent.SkillTriggered,
                is BattleEvent.TriggerPoint,
                is BattleEvent.SkillPreparationCompleted,
                is BattleEvent.SkillPreparationCancelled,
                -> Unit
                else -> Unit
            }
        }
        appendFinalization(actions, result)
        return actions
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

    private fun statusActions(
        source: BattleHeroRef,
        target: BattleHeroRef,
        skillId: Int,
        effectId: Int,
    ): List<ClientReportAction> {
        val status = ClientReportAction(
            ClientBattleTextReplayProtocol.STATUS,
            listOf(
                ClientBattleTextReplayProtocol.position(source),
                ClientBattleTextReplayProtocol.position(target),
                skillId,
                effectId,
            ),
        )
        return if (skillId > 0) {
            skillSegment(
                source = source,
                skillId = skillId,
                effects = listOf(status),
            )
        } else {
            listOf(status)
        }
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
