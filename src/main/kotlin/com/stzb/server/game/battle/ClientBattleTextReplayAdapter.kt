package com.stzb.server.game.battle

internal object ClientBattleTextReplayAdapter {
    fun adapt(result: BattleResult): List<ClientReportAction> {
        val actions = mutableListOf<ClientReportAction>()
        (
            result.attacker.heroes.map { Side.ATTACKER to it } +
                result.defender.heroes.map { Side.DEFENDER to it }
            )
            .sortedBy { (side, hero) -> ClientBattleTextReplayProtocol.position(side, hero.position) }
            .forEach { (side, hero) ->
                actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.HERO_NAME,
                    listOf(ClientBattleTextReplayProtocol.position(side, hero.position), hero.id.value),
                )
            }
        actions += ClientReportAction(ClientBattleTextReplayProtocol.PREPARE)

        result.events.forEach { event ->
            when (event) {
                is BattleEvent.RoundStart -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.ROUND,
                    listOf(event.round),
                )
                is BattleEvent.NormalAttack -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.NORMAL_DAMAGE,
                    listOf(
                        ClientBattleTextReplayProtocol.position(event.target),
                        ClientBattleTextReplayProtocol.position(event.source),
                        0,
                        event.damage,
                        event.targetTroopsAfter,
                    ),
                )
                is BattleEvent.SkillDamage -> {
                    actions += skillCast(event.source, event.skillId)
                    actions += ClientReportAction(
                        ClientBattleTextReplayProtocol.SKILL_DAMAGE,
                        listOf(
                            ClientBattleTextReplayProtocol.position(event.source),
                            event.skillId,
                            ClientBattleTextReplayProtocol.position(event.target),
                            event.damage,
                            event.targetTroopsAfter,
                        ),
                    )
                }
                is BattleEvent.Recovery -> {
                    actions += skillCast(event.source, event.skillId)
                    actions += ClientReportAction(
                        ClientBattleTextReplayProtocol.RECOVERY,
                        listOf(
                            ClientBattleTextReplayProtocol.position(event.source),
                            event.skillId,
                            ClientBattleTextReplayProtocol.position(event.target),
                            event.amount,
                            event.targetTroopsAfter,
                        ),
                    )
                }
                is BattleEvent.StatusApplied -> actions += statusActions(
                    source = event.source,
                    target = event.target,
                    skillId = event.skillId,
                    effectId = ClientBattleTextReplayProtocol.effectId(event.status),
                )
                is BattleEvent.OngoingDamage -> actions += ClientReportAction(
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
                is BattleEvent.Evaded -> actions += statusActions(
                    source = event.source,
                    target = event.target,
                    skillId = 0,
                    effectId = ClientBattleTextReplayProtocol.effectId(BattleStatus.EVADE),
                )
                is BattleEvent.StatChanged -> {
                    val effectId = ClientBattleTextReplayProtocol.effectId(event.stat, event.delta)
                    if (effectId != 0) {
                        actions += statusActions(event.source, event.target, event.skillId, effectId)
                    }
                }
                is BattleEvent.UnsupportedSkillEffect,
                is BattleEvent.UnsupportedEquipmentEffect -> Unit
                else -> Unit
            }
        }
        appendFinalization(actions, result)
        return actions
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

    private fun statusActions(
        source: BattleHeroRef,
        target: BattleHeroRef,
        skillId: Int,
        effectId: Int,
    ): List<ClientReportAction> =
        buildList {
            if (skillId > 0) {
                add(
                    ClientReportAction(
                        ClientBattleTextReplayProtocol.SKILL_CAST,
                        listOf(
                            ClientBattleTextReplayProtocol.position(source),
                            ClientBattleTextReplayProtocol.position(source),
                            skillId,
                        ),
                    ),
                )
            }
            add(
                ClientReportAction(
                    ClientBattleTextReplayProtocol.STATUS,
                    listOf(
                        ClientBattleTextReplayProtocol.position(source),
                        ClientBattleTextReplayProtocol.position(target),
                        skillId,
                        effectId,
                    ),
                ),
            )
        }

    private fun appendFinalization(
        actions: MutableList<ClientReportAction>,
        result: BattleResult,
    ) {
        actions += ClientReportAction(ClientBattleTextReplayProtocol.END)
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
}
