package com.stzb.server.game.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.skill.BattleTargetDecisionSource
import java.nio.file.Path
import java.util.ArrayDeque
import kotlin.math.roundToInt

internal object OfficialReportFixture {
    private data class PrecisePaperStats(
        val entry: Map<Int, Map<BattleStat, Int>>,
        val inherent: Map<Int, Map<BattleStat, Int>>,
    )

    private data class DecisionKey(
        val sourcePosition: Int,
        val skillId: Int,
        val effectId: Int,
    )

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
        val preciseStats = preciseStatsBeforeBattle(actions)
        fun withPreciseStats(team: BattleTeam, side: Side): BattleTeam = team.copy(
            heroes = team.heroes.map { hero ->
                val clientPosition = ClientBattleTextReplayProtocol.position(side, hero.position)
                val recorded = preciseStats.entry[clientPosition]
                    ?: return@map hero
                val inherent = preciseStats.inherent[clientPosition].orEmpty()
                val stats = hero.stats
                hero.copy(
                    stats = BattleStats.fromHundredths(
                        attack = recorded[BattleStat.ATTACK]
                            ?: (stats.precise(BattleStat.ATTACK) * 100).roundToInt(),
                        defense = recorded[BattleStat.DEFENSE]
                            ?: (stats.precise(BattleStat.DEFENSE) * 100).roundToInt(),
                        strategy = recorded[BattleStat.STRATEGY]
                            ?: (stats.precise(BattleStat.STRATEGY) * 100).roundToInt(),
                        speed = recorded[BattleStat.SPEED]
                            ?: (stats.precise(BattleStat.SPEED) * 100).roundToInt(),
                        siege = (stats.precise(BattleStat.SIEGE) * 100).roundToInt(),
                        hitRange = stats.hitRange,
                    ),
                    inherentStats = BattleStats.fromHundredths(
                        attack = inherent[BattleStat.ATTACK]
                            ?: (hero.inherentStats.precise(BattleStat.ATTACK) * 100).roundToInt(),
                        defense = inherent[BattleStat.DEFENSE]
                            ?: (hero.inherentStats.precise(BattleStat.DEFENSE) * 100).roundToInt(),
                        strategy = inherent[BattleStat.STRATEGY]
                            ?: (hero.inherentStats.precise(BattleStat.STRATEGY) * 100).roundToInt(),
                        speed = inherent[BattleStat.SPEED]
                            ?: (hero.inherentStats.precise(BattleStat.SPEED) * 100).roundToInt(),
                        siege = (hero.inherentStats.precise(BattleStat.SIEGE) * 100).roundToInt(),
                        hitRange = hero.inherentStats.hitRange,
                    ),
                )
            },
        )
        return BattleRequest(
            attacker = withPreciseStats(
                teamBuilder.build((1..3).mapNotNull(specsByClientPosition::get)),
                Side.ATTACKER,
            ),
            defender = withPreciseStats(
                teamBuilder.build((4..6).mapNotNull(specsByClientPosition::get)),
                Side.DEFENDER,
            ),
        )
    }

    fun targetDecisions(actions: List<Action>): BattleTargetDecisionSource {
        val queues = linkedMapOf<DecisionKey, ArrayDeque<List<Int>>>()
        var currentKey: DecisionKey? = null
        var currentTargets = mutableListOf<Int>()
        fun flush() {
            val key = currentKey ?: return
            queues.getOrPut(key, ::ArrayDeque).addLast(currentTargets.toList())
            currentKey = null
            currentTargets = mutableListOf()
        }
        actions.forEach { action ->
            if (action.id != "ja".toInt(36)) {
                flush()
                return@forEach
            }
            val tuple = jaTuples(listOf(action)).single()
            val key = DecisionKey(tuple.sourcePosition, tuple.sourceId, tuple.effectId)
            if (currentKey != null && currentKey != key) flush()
            currentKey = key
            currentTargets += tuple.targetPosition
        }
        flush()

        return BattleTargetDecisionSource { request ->
            val key = DecisionKey(
                ClientBattleTextReplayProtocol.position(request.context.source.side, request.context.source.position),
                request.context.rootSkillId,
                request.rule.effectId,
            )
            val queue = queues[key] ?: return@BattleTargetDecisionSource null
            require(queue.isNotEmpty()) { "Paper target decisions exhausted for $key" }
            val targetPositions = queue.removeFirst()
            targetPositions.map { position ->
                requireNotNull(
                    request.candidates.find { candidate ->
                        ClientBattleTextReplayProtocol.position(candidate.side, candidate.position) == position
                    },
                ) {
                    "Paper target position $position is absent from candidates ${request.candidates} for $key"
                }
            }
        }
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

    private fun preciseStatsBeforeBattle(actions: List<Action>): PrecisePaperStats {
        val formats = mapOf(
            "19".toInt(36) to (BattleStat.ATTACK to 5),
            "1a".toInt(36) to (BattleStat.DEFENSE to 5),
            "1b".toInt(36) to (BattleStat.STRATEGY to 5),
            "1c".toInt(36) to (BattleStat.SPEED to 5),
            "0v".toInt(36) to (BattleStat.ATTACK to 4),
            "0w".toInt(36) to (BattleStat.DEFENSE to 4),
            "0x".toInt(36) to (BattleStat.STRATEGY to 4),
            "0y".toInt(36) to (BattleStat.SPEED to 4),
        )
        val result = linkedMapOf<Int, MutableMap<BattleStat, Int>>()
        val inherent = linkedMapOf<Int, MutableMap<BattleStat, Int>>()
        actions.takeWhile { it.id != "hr".toInt(36) }.forEach { action ->
            val (stat, valueIndex) = formats[action.id] ?: return@forEach
            require(action.params.size > valueIndex) { "invalid precise stat action: ${action.raw}" }
            val targetPosition = action.intParam(2)
            val hundredths = action.params[valueIndex].toBigDecimal().movePointRight(2).intValueExact()
            result.getOrPut(targetPosition, ::linkedMapOf)[stat] = hundredths
            if (valueIndex == 5 && action.intParam(3) != 0) {
                val inferredHundredths = action.params[4].toBigDecimal()
                    .movePointRight(4)
                    .divide(action.params[3].toBigDecimal(), 0, java.math.RoundingMode.HALF_UP)
                    .intValueExact()
                inherent.getOrPut(targetPosition, ::linkedMapOf).putIfAbsent(stat, inferredHundredths)
            }
        }
        return PrecisePaperStats(result, inherent)
    }

    private fun formationPosition(clientPosition: Int): Int =
        if (clientPosition <= 3) clientPosition - 1 else 6 - clientPosition
}
