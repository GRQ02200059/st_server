package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialFullBattleReportDiffTest {
    @Test
    fun `all readable paper reports expose complete battle summaries`() {
        val reports = OfficialReportFixture.readableReports()

        assertEquals(28, reports.size)
        reports.forEach { report ->
            val summary = OfficialReportFixture.fullBattleSummary(
                OfficialReportFixture.read(report),
            )

            assertTrue(summary.rounds in 0..8, report.toString())
            if (summary.rounds == 0) {
                assertTrue(summary.actionRoundsByPosition.isEmpty(), report.toString())
            }
            assertTrue(
                summary.finalTroopsByPosition.keys.all { it in 1..6 },
                report.toString(),
            )
            assertTrue(
                summary.actionRoundsByPosition.values.flatten().all { it in 1..8 },
                report.toString(),
            )
        }
    }

    @Test
    fun `official full battles stay inside deterministic simulation envelopes`() {
        val config = BattleConfigRepository.loadDefault()
        val failures = mutableListOf<String>()
        var reportCount = 0
        var outcomeCoverageCount = 0
        var roundCoverageCount = 0
        var finalTroopCoverageCount = 0
        var finalTroopComparisonCount = 0
        var actionRoundMismatchCount = 0
        var skillTriggerMismatchCount = 0
        val damageRelativeErrors = Side.entries.associateWith {
            mutableListOf<Double>()
        }
        val recoveryRelativeErrors = mutableListOf<Double>()

        OfficialReportFixture.readableReports().forEach { report ->
            reportCount += 1
            val actions = OfficialReportFixture.read(report)
            val official = OfficialReportFixture.fullBattleSummary(actions)
            val request = OfficialReportFixture.reconstructBattleRequest(actions, config)
            val simulated = (0 until 32).map { seed ->
                OfficialReportFixture.fullBattleSummary(
                    BattleEngine.resolve(
                        request,
                        config,
                        SeededBattleRandom(seed),
                    ),
                )
            }

            val roundRange = simulated.minOf { it.rounds }..simulated.maxOf { it.rounds }
            val outcomes = simulated.mapTo(linkedSetOf()) { it.outcome }
            val roundCovered = official.rounds in roundRange
            val outcomeCovered = official.outcome in outcomes
            if (roundCovered) {
                roundCoverageCount += 1
            }
            if (outcomeCovered) {
                outcomeCoverageCount += 1
            }
            if (!roundCovered || !outcomeCovered) {
                failures += "$report rounds=${official.rounds}/$roundRange " +
                    "outcome=${official.outcome}/$outcomes"
            }
            val actionRoundMismatches = actionRoundMismatches(official, simulated)
            actionRoundMismatchCount += actionRoundMismatches.size
            if (actionRoundMismatches.isNotEmpty()) {
                failures += "$report actionRounds=${actionRoundMismatches.joinToString()}"
            }
            val skillTriggerMismatches = skillTriggerMismatches(official, simulated)
            skillTriggerMismatchCount += skillTriggerMismatches.size
            if (skillTriggerMismatches.isNotEmpty()) {
                failures += "$report skillTriggers=${skillTriggerMismatches.joinToString()}"
            }
            Side.entries.forEach { side ->
                val officialDamage = official.damageBySide.getValue(side)
                val values = simulated.map { it.damageBySide.getValue(side) }
                val interval = values.min()..values.max()
                if (officialDamage !in interval) {
                    failures += "$report $side damage=$officialDamage/$interval"
                }
                if (officialDamage > 0) {
                    damageRelativeErrors.getValue(side) += (
                        kotlin.math.abs(values.median() - officialDamage).toDouble() /
                            officialDamage
                        )
                }

                val officialRecovery = official.recoveryBySide.getValue(side)
                val recoveryValues = simulated.map { it.recoveryBySide.getValue(side) }
                val recoveryInterval = recoveryValues.min()..recoveryValues.max()
                if (officialRecovery !in recoveryInterval) {
                    failures += "$report $side recovery=$officialRecovery/$recoveryInterval"
                }
                if (officialRecovery > 0) {
                    recoveryRelativeErrors += (
                        kotlin.math.abs(recoveryValues.median() - officialRecovery).toDouble() /
                            officialRecovery
                        )
                }
            }
            official.finalTroopsByPosition.forEach { (position, officialTroops) ->
                finalTroopComparisonCount += 1
                val values = simulated.mapNotNull {
                    it.finalTroopsByPosition[position]
                }
                val interval = values.min()..values.max()
                if (officialTroops in interval) {
                    finalTroopCoverageCount += 1
                } else {
                    failures += "$report position=$position finalTroops=$officialTroops/$interval"
                }
            }
        }

        val attackerDamageError =
            damageRelativeErrors.getValue(Side.ATTACKER).average()
        val defenderDamageError =
            damageRelativeErrors.getValue(Side.DEFENDER).average()
        val meanDamageRelativeError =
            listOf(attackerDamageError, defenderDamageError).average()
        val recoveryRelativeError = recoveryRelativeErrors.averageOrZero()
        assertTrue(
            failures.isEmpty() && meanDamageRelativeError <= 0.35,
            buildString {
                appendLine("outcome_coverage=$outcomeCoverageCount/$reportCount")
                appendLine("round_coverage=$roundCoverageCount/$reportCount")
                appendLine("action_round_mismatch_count=$actionRoundMismatchCount")
                appendLine("skill_trigger_mismatch_count=$skillTriggerMismatchCount")
                appendLine(
                    "final_troop_coverage=" +
                        "$finalTroopCoverageCount/$finalTroopComparisonCount",
                )
                appendLine(
                    "attacker_damage_median_relative_error=$attackerDamageError",
                )
                appendLine(
                    "defender_damage_median_relative_error=$defenderDamageError",
                )
                appendLine(
                    "recovery_median_relative_error=$recoveryRelativeError",
                )
                appendLine("meanDamageRelativeError=$meanDamageRelativeError")
                failures.forEach(::appendLine)
            },
        )
    }

    @Test
    fun `first round defender victory can reproduce the attacker base defeat`() {
        val report = java.nio.file.Path.of(
            "assent/cfg/paper/6231/cap_20260311223648438_00001857_zlib.json",
        )
        val config = BattleConfigRepository.loadDefault()
        val actions = OfficialReportFixture.read(report)
        val official = OfficialReportFixture.fullBattleSummary(actions)
        val request = OfficialReportFixture.reconstructBattleRequest(actions, config)
        val attackerBase = request.attacker.heroes.single { hero ->
            ClientBattleTextReplayProtocol.position(Side.ATTACKER, hero.position) == 1
        }
        val simulations = (0 until 32).map { seed ->
            seed to BattleEngine.resolve(request, config, SeededBattleRandom(seed))
        }
        val simulatedBaseTroops = simulations.map { (_, result) ->
            result.troopsAfterFirstRound(1, attackerBase.troops)
        }
        val closest = simulations.minBy { (_, result) ->
            result.troopsAfterFirstRound(1, attackerBase.troops)
        }
        val source = request.defender.heroes.single { hero ->
            ClientBattleTextReplayProtocol.position(Side.DEFENDER, hero.position) == 6
        }
        val seedSummaries = simulations.joinToString(separator = "\n") { (seed, result) ->
            val pursuits = result.events.filterIsInstance<BattleEvent.SkillTriggered>()
                .count {
                    it.round == 1 &&
                        ClientBattleTextReplayProtocol.position(it.source) == 6 &&
                        it.skillId == 200722
                }
            val sanjun = result.events.filterIsInstance<BattleEvent.SkillDamage>()
                .filter {
                    it.round == 1 &&
                        ClientBattleTextReplayProtocol.position(it.source) == 6 &&
                        it.skillId == 211987
                }
                .joinToString(separator = ",") {
                    "${it.effectId}->${ClientBattleTextReplayProtocol.position(it.target)}"
                }
            "seed=$seed base=${result.troopsAfterFirstRound(1, attackerBase.troops)} " +
                "pursuits=$pursuits sanjun=[$sanjun]"
        }

        assertTrue(
            official.finalTroopsByPosition.getValue(1) in
                simulatedBaseTroops.min()..simulatedBaseTroops.max(),
            "officialBase=${official.finalTroopsByPosition.getValue(1)} " +
                "simulated=${simulatedBaseTroops.min()}..${simulatedBaseTroops.max()} " +
                "closestSeed=${closest.first} source6Initial=${source.stats}\n" +
                closest.second.firstRoundDamageTrace() + "\n" + seedSummaries,
        )
    }

    @Test
    fun `huangyi paper recovery stays inside deterministic simulation envelope`() {
        val report = java.nio.file.Path.of(
            "assent/cfg/paper/11/cap_20260311222842345_0000000b_zlib.json",
        )
        val config = BattleConfigRepository.loadDefault()
        val actions = OfficialReportFixture.read(report)
        val official = OfficialReportFixture.fullBattleSummary(actions)
        val request = OfficialReportFixture.reconstructBattleRequest(actions, config)
        val source = request.defender.heroes.single { hero ->
            ClientBattleTextReplayProtocol.position(Side.DEFENDER, hero.position) == 6
        }
        val simulations = (0 until 32).map { seed ->
            seed to BattleEngine.resolve(request, config, SeededBattleRandom(seed))
        }
        val recoveryTotals = simulations.map { (_, result) ->
            result.events.filterIsInstance<BattleEvent.Recovery>()
                .filter { it.source.side == Side.DEFENDER && it.skillId == 200016 }
                .sumOf(BattleEvent.Recovery::amount)
        }
        val seedSummaries = simulations.joinToString(separator = "\n") { (seed, result) ->
            val recoveries = result.events.filterIsInstance<BattleEvent.Recovery>()
                .filter { it.source.side == Side.DEFENDER && it.skillId == 200016 }
            val hurtEvents = result.events.count { event ->
                when (event) {
                    is BattleEvent.NormalAttack ->
                        event.target.side == Side.DEFENDER && event.damage > 0
                    is BattleEvent.SkillDamage ->
                        event.target.side == Side.DEFENDER && event.damage > 0
                    is BattleEvent.OngoingDamage ->
                        event.target.side == Side.DEFENDER && event.damage > 0
                    else -> false
                }
            }
            val increments = result.events.filterIsInstance<BattleEvent.SkillTriggered>()
                .count { it.source.side == Side.DEFENDER && it.skillId == 211016 }
            val byRound = recoveries.groupBy(BattleEvent.Recovery::round)
                .mapValues { (_, events) -> events.sumOf(BattleEvent.Recovery::amount) }
            "seed=$seed hurtEvents=$hurtEvents recoveries=${recoveries.size} " +
                "total=${recoveries.sumOf(BattleEvent.Recovery::amount)} " +
                "increments=$increments byRound=$byRound"
        }

        assertTrue(
            official.recoveryBySide.getValue(Side.DEFENDER) in
                recoveryTotals.min()..recoveryTotals.max(),
            "official=${official.recoveryBySide.getValue(Side.DEFENDER)} " +
                "simulated=${recoveryTotals.min()}..${recoveryTotals.max()} " +
                "sourceInitial=${source.stats}\n" +
                seedSummaries,
        )
    }

    private fun BattleResult.troopsAfterFirstRound(
        clientPosition: Int,
        initialTroops: Int,
    ): Int =
        events.fold(initialTroops) { troops, event ->
            when (event) {
                is BattleEvent.NormalAttack -> event.targetTroopsAfter.takeIf {
                    event.round == 1 &&
                        ClientBattleTextReplayProtocol.position(event.target) == clientPosition
                }
                is BattleEvent.SkillDamage -> event.targetTroopsAfter.takeIf {
                    event.round == 1 &&
                        ClientBattleTextReplayProtocol.position(event.target) == clientPosition
                }
                is BattleEvent.OngoingDamage -> event.targetTroopsAfter.takeIf {
                    event.round == 1 &&
                        ClientBattleTextReplayProtocol.position(event.target) == clientPosition
                }
                is BattleEvent.Recovery -> event.targetTroopsAfter.takeIf {
                    event.round == 1 &&
                        ClientBattleTextReplayProtocol.position(event.target) == clientPosition
                }
                else -> null
            } ?: troops
        }

    private fun BattleResult.firstRoundDamageTrace(): String =
        events.mapNotNull { event ->
            when (event) {
                is BattleEvent.NormalAttack -> if (event.round == 1) {
                    "normal ${ClientBattleTextReplayProtocol.position(event.source)}->" +
                        "${ClientBattleTextReplayProtocol.position(event.target)} " +
                        "damage=${event.damage} after=${event.targetTroopsAfter}"
                } else {
                    null
                }
                is BattleEvent.SkillDamage -> if (event.round == 1) {
                    "skill=${event.skillId} effect=${event.effectId} " +
                        "${ClientBattleTextReplayProtocol.position(event.source)}->" +
                        "${ClientBattleTextReplayProtocol.position(event.target)} " +
                        "damage=${event.damage} after=${event.targetTroopsAfter}"
                } else {
                    null
                }
                is BattleEvent.OngoingDamage -> if (event.round == 1) {
                    "ongoing skill=${event.skillId} " +
                        "${ClientBattleTextReplayProtocol.position(event.source)}->" +
                        "${ClientBattleTextReplayProtocol.position(event.target)} " +
                        "damage=${event.damage} after=${event.targetTroopsAfter}"
                } else {
                    null
                }
                is BattleEvent.SkillTriggered -> if (event.round == 1) {
                    "trigger source=${ClientBattleTextReplayProtocol.position(event.source)} " +
                        "root=${event.rootSkillId} skill=${event.skillId} " +
                        "trigger=${event.trigger}"
                } else {
                    null
                }
                is BattleEvent.StatChanged -> if (
                    event.round <= 1 &&
                    ClientBattleTextReplayProtocol.position(event.target) == 6
                ) {
                    "stat round=${event.round} " +
                        "${ClientBattleTextReplayProtocol.position(event.source)}->6 " +
                        "skill=${event.skillId} effect=${event.effectId} " +
                        "${event.stat} delta=${event.deltaExact} after=${event.valueAfterExact}"
                } else {
                    null
                }
                is BattleEvent.ModifierApplied -> if (
                    event.round == 0 &&
                    event.skillId in setOf(200198, 200204, 200773, 296106) &&
                    ClientBattleTextReplayProtocol.position(event.target) in setOf(1, 6)
                ) {
                    "modifier ${ClientBattleTextReplayProtocol.position(event.source)}->" +
                        "${ClientBattleTextReplayProtocol.position(event.target)} " +
                        "skill=${event.skillId} effect=${event.effectId} amount=${event.amount}"
                } else {
                    null
                }
                is BattleEvent.Recovery -> if (event.round == 1) {
                    "recovery skill=${event.skillId} " +
                        "${ClientBattleTextReplayProtocol.position(event.source)}->" +
                        "${ClientBattleTextReplayProtocol.position(event.target)} " +
                        "amount=${event.amount} after=${event.targetTroopsAfter}"
                } else {
                    null
                }
                else -> null
            }
        }.joinToString("\n")

    private fun List<Int>.median(): Int {
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }

    private fun actionRoundMismatches(
        official: OfficialReportFixture.FullBattleSummary,
        simulated: List<OfficialReportFixture.FullBattleSummary>,
    ): List<String> =
        (1..6).flatMap { position ->
            (1..8).mapNotNull { round ->
                val officialCount =
                    official.actionRoundsByPosition[position].orEmpty().count { it == round }
                val simulatedCounts = simulated.map { summary ->
                    summary.actionRoundsByPosition[position].orEmpty().count { it == round }
                }
                val interval = simulatedCounts.min()..simulatedCounts.max()
                if (officialCount !in interval) {
                    "p$position:r$round=$officialCount/$interval"
                } else {
                    null
                }
            }
        }

    private fun List<Double>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average()

    private fun skillTriggerMismatches(
        official: OfficialReportFixture.FullBattleSummary,
        simulated: List<OfficialReportFixture.FullBattleSummary>,
    ): List<String> =
        official.skillTriggers.keys.sorted().mapNotNull { skillId ->
            val officialCount = official.skillTriggers[skillId] ?: 0
            val simulatedCounts = simulated.map { it.skillTriggers[skillId] ?: 0 }
            val interval = simulatedCounts.min()..simulatedCounts.max()
            if (officialCount !in interval) {
                "$skillId=$officialCount/$interval"
            } else {
                null
            }
        }
}
