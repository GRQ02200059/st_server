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
        val relativeErrors = mutableListOf<Double>()

        OfficialReportFixture.readableReports().forEach { report ->
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
            if (official.rounds !in roundRange || official.outcome !in outcomes) {
                failures += "$report rounds=${official.rounds}/$roundRange " +
                    "outcome=${official.outcome}/$outcomes"
            }
            Side.entries.forEach { side ->
                val officialDamage = official.damageBySide.getValue(side)
                val values = simulated.map { it.damageBySide.getValue(side) }
                val interval = values.min()..values.max()
                if (officialDamage !in interval) {
                    failures += "$report $side damage=$officialDamage/$interval"
                }
                if (officialDamage > 0) {
                    relativeErrors += (
                        kotlin.math.abs(values.median() - officialDamage).toDouble() /
                            officialDamage
                        )
                }
            }
        }

        val meanRelativeError = relativeErrors.average()
        assertTrue(
            failures.isEmpty() && meanRelativeError <= 0.35,
            buildString {
                appendLine("meanDamageRelativeError=$meanRelativeError")
                failures.forEach(::appendLine)
            },
        )
    }

    private fun List<Int>.median(): Int {
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }
}
