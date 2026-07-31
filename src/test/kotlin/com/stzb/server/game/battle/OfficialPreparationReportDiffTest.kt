package com.stzb.server.game.battle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialPreparationReportDiffTest {
    private val officialReport =
        Path.of("assent/cfg/paper/11/cap_20260312014510506_0000000b_zlib.json")

    @Test
    fun `representative official preparation report remains structurally compatible`() {
        val config = BattleConfigRepository.loadDefault()
        val officialActions = OfficialReportFixture.read(officialReport)
        val request = OfficialReportFixture.reconstructBattleRequest(officialActions, config)

        val firstResult = BattleEngine.resolve(request, config, FixedBattleRandom(0))
        val firstText = ClientReportTextEncoder.encode(firstResult)
        val secondText = ClientReportTextEncoder.encode(
            BattleEngine.resolve(request, config, FixedBattleRandom(0)),
        )
        assertEquals(firstText, secondText, "fixed-random report projection must be deterministic")
        val appliedEffectIds = firstResult.events
            .filterIsInstance<BattleEvent.StatusApplied>()
            .filter { it.round == 0 }
            .mapNotNull(BattleEvent.StatusApplied::effectId)
            .toSet()
        assertTrue(702 in appliedEffectIds, "hesitation must retain configured effect 702")
        assertTrue(752 in appliedEffectIds, "disarm must retain configured effect 752")

        val officialPreparation = OfficialReportFixture.preparation(officialActions)
        val generatedPreparation =
            OfficialReportFixture.preparation(OfficialReportFixture.parseText(firstText))
        val officialJa = OfficialReportFixture.jaTuples(officialPreparation)
        val generatedJa = OfficialReportFixture.jaTuples(generatedPreparation)
        val repeatedGeneratedJa = OfficialReportFixture.jaTuples(
            OfficialReportFixture.preparation(OfficialReportFixture.parseText(secondText)),
        )
        val battleOnlyWrappers = setOf(
            ClientBattleTextReplayProtocol.SKILL_BEGIN,
            ClientBattleTextReplayProtocol.SKILL_END,
            ClientBattleTextReplayProtocol.SKILL_CAST,
            ClientBattleTextReplayProtocol.SKILL_DAMAGE,
        )

        assertTrue(
            generatedPreparation.none { it.id in battleOnlyWrappers },
            "round-zero projection leaked battle-only wrappers",
        )
        assertTrue(
            generatedPreparation.any { it.id == ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BEGIN },
            "generated preparation is missing the 66 effect envelope",
        )
        assertTrue(
            generatedPreparation.any { it.id == ClientBattleTextReplayProtocol.PREPARATION_EFFECT_END },
            "generated preparation is missing the 67 effect envelope",
        )
        assertTrue(
            generatedPreparation.any { it.id == ClientBattleTextReplayProtocol.PREPARATION_EFFECT_BOUNDARY },
            "generated preparation is missing the 61 effect boundary",
        )
        assertTrue(
            generatedPreparation.any { it.id == "8x".toInt(36) },
            "equipment feature projection is missing the official 8x action",
        )
        assertTrue(
            generatedPreparation.none { it.id == "0t".toInt(36) },
            "successful applied statuses must not be projected as 0t",
        )
        assertTrue(
            generatedPreparation.any { it.params.lastOrNull() == "702" },
            "hesitation must retain configured effect 702",
        )
        assertTrue(
            generatedPreparation.any { it.params.lastOrNull() == "752" },
            "disarm must retain configured effect 752",
        )
        assertEquals(
            emptySet(),
            generatedPreparation.map { it.id }.toSet() -
                officialPreparation.map { it.id }.toSet(),
            "generated preparation must not invent action families absent from the official report",
        )
        assertEquals(
            emptyMap(),
            OfficialReportFixture.commonWidthMismatches(
                official = officialPreparation,
                generated = generatedPreparation,
            ),
            "generated common action families use parameter widths absent from the official report",
        )
        assertEquals(25, officialJa.size, "reviewed paper fixture ja count changed")
        assertEquals(23, generatedJa.size, "preparation ja parity milestone regressed")
        assertEquals(generatedJa, repeatedGeneratedJa, "generated ja tuples must be deterministic")
        assertTrue(generatedJa.none { it.sourceId == 223006 })
        assertTrue(
            generatedJa.containsAll(
                listOf(
                    OfficialReportFixture.JaTuple(1, 296132, 1, 531, 8),
                    OfficialReportFixture.JaTuple(1, 296132, 1, 533, 8),
                    OfficialReportFixture.JaTuple(6, 296232, 6, 531, 8),
                    OfficialReportFixture.JaTuple(6, 296232, 6, 533, 8),
                ),
            ),
            "generated report is missing verified troop-feature ja tuples",
        )
    }
}
