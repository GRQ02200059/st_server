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
                else -> Unit
            }
        }
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
}
