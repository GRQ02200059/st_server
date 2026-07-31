package com.stzb.server.game.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

internal object OfficialReportFixture {
    data class Action(
        val id: Int,
        val raw: String,
        val params: List<String>,
    )

    data class JaTuple(
        val sourcePosition: Int,
        val sourceId: Int,
        val targetPosition: Int,
        val effectId: Int,
        val amount: Int,
    )

    private val mapper = jacksonObjectMapper()

    fun read(path: Path): List<Action> =
        parseText(mapper.readTree(path.toFile())[1]["report"].asText())

    fun parseText(text: String): List<Action> =
        text.split('#').map { raw ->
            require(raw.length >= 2) { "invalid report action: $raw" }
            Action(
                id = raw.take(2).toInt(36),
                raw = raw,
                params = raw.drop(2).takeIf(String::isNotEmpty)?.split(',') ?: emptyList(),
            )
        }

    fun preparation(actions: List<Action>): List<Action> =
        actions.takeWhile { it.id != ClientBattleTextReplayProtocol.ROUND }

    fun jaTuples(actions: List<Action>): List<JaTuple> =
        actions
            .filter { it.id == "ja".toInt(36) }
            .map { action ->
                require(action.params.size == 5) {
                    "invalid ja action width ${action.params.size}: ${action.raw}"
                }
                JaTuple(
                    sourcePosition = action.intParam(0),
                    sourceId = action.intParam(1),
                    targetPosition = action.intParam(2),
                    effectId = action.intParam(3),
                    amount = action.intParam(4),
                )
            }

    fun reconstructBattleRequest(
        actions: List<Action>,
        config: BattleConfigRepository,
    ): BattleRequest {
        val preparation = preparation(actions)
        val heroIdsByClientPosition = preparation
            .filter { it.id == ClientBattleTextReplayProtocol.HERO_NAME }
            .associate { it.intParam(0) to it.intParam(1) }
        val equipmentIdsByClientPosition = preparation
            .filter { it.id == ClientBattleTextReplayProtocol.EQUIPMENT_EFFECT_SOURCE }
            .groupBy { it.intParam(0) }
            .mapValues { (_, sources) -> sources.map { it.intParam(1) }.distinct() }
        val equipmentFeaturesByClientPosition = preparation
            .filter {
                it.id == "8x".toInt(36) &&
                    it.params.size >= 5 &&
                    it.intParam(1) in 450_000..459_999
            }
            .groupBy { it.intParam(0) }

        val specsByClientPosition = preparation
            .filter { it.id == ClientBattleTextReplayProtocol.HERO_INFO }
            .associate { heroInfo ->
                val clientPosition = heroInfo.intParam(0)
                val skillIds = listOf(
                    heroInfo.intParam(3),
                    heroInfo.intParam(5),
                    heroInfo.intParam(7),
                )
                clientPosition to BattleHeroSpec(
                    heroId = requireNotNull(heroIdsByClientPosition[clientPosition]) {
                        "missing 0e hero identity for client position $clientPosition"
                    },
                    position = formationPosition(clientPosition),
                    troops = heroInfo.intParam(2),
                    extraSkillIds = skillIds.drop(1),
                    skillLevels = listOf(
                        heroInfo.intParam(4),
                        heroInfo.intParam(6),
                        heroInfo.intParam(8),
                    ),
                    troopFeatureIds = listOf(heroInfo.intParam(9), heroInfo.intParam(10)),
                    equipmentIds = equipmentIdsByClientPosition[clientPosition].orEmpty(),
                    equipmentSkillIds = listOf(
                        heroInfo.intParam(12),
                        heroInfo.intParam(14),
                        heroInfo.intParam(16),
                    ),
                    equipmentSkillLevels = listOf(
                        heroInfo.intParam(13),
                        heroInfo.intParam(15),
                        heroInfo.intParam(17),
                    ),
                    equipmentFeatureSkillIds =
                        equipmentFeaturesByClientPosition[clientPosition]
                            .orEmpty()
                            .map { it.intParam(1) },
                    equipmentFeatureSkillLevels =
                        equipmentFeaturesByClientPosition[clientPosition]
                            .orEmpty()
                            .map { it.intParam(4) },
                    level = heroInfo.intParam(1),
                )
            }

        val teamBuilder = BattleTeamBuilder(
            config = config,
            equipmentRepository = BattleEquipmentRepository.loadDefault(),
        )
        return BattleRequest(
            attacker = teamBuilder.build((1..3).mapNotNull(specsByClientPosition::get)),
            defender = teamBuilder.build((4..6).mapNotNull(specsByClientPosition::get)),
        )
    }

    fun commonWidthMismatches(
        official: List<Action>,
        generated: List<Action>,
    ): Map<Int, Pair<Set<Int>, Set<Int>>> {
        val officialWidths = official.groupBy(Action::id)
            .mapValues { (_, values) -> values.map { it.params.size }.toSet() }
        val generatedWidths = generated.groupBy(Action::id)
            .mapValues { (_, values) -> values.map { it.params.size }.toSet() }
        return generatedWidths.keys.intersect(officialWidths.keys)
            .mapNotNull { id ->
                val unexpected = generatedWidths.getValue(id) - officialWidths.getValue(id)
                if (unexpected.isEmpty()) {
                    null
                } else {
                    id to (officialWidths.getValue(id) to generatedWidths.getValue(id))
                }
            }
            .toMap()
    }

    private fun Action.intParam(index: Int): Int =
        params[index].toInt()

    private fun formationPosition(clientPosition: Int): Int =
        if (clientPosition <= 3) clientPosition - 1 else 6 - clientPosition
}
