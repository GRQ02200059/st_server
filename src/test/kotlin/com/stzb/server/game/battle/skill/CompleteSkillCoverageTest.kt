package com.stzb.server.game.battle.skill

import kotlin.test.Test
import kotlin.test.assertEquals

class CompleteSkillCoverageTest {
    @Test
    fun `all scoped skills have complete executable coverage`() {
        val report = SkillCoverageReport.generateDefault()

        assertEquals(308, report.mainSkills)
        assertEquals(673, report.executionNodes)
        assertEquals(111, report.effectIds)
        assertEquals(1942, report.detailRules)
        assertEquals(emptySet(), report.unsupportedEffects)
        assertEquals(emptySet(), report.unconsumedMetaEffects)
        assertEquals(emptySet(), report.unknownSelectors)
        assertEquals(emptySet(), report.brokenDependencies)
        assertEquals(emptySet(), report.noBehaviorSkills)
        assertEquals(emptySet(), report.missingPluginSkillIds)
        assertEquals(emptySet(), report.duplicateExecutionSkillIds)
        assertEquals(emptySet(), report.unknownConditions)
    }

    @Test
    fun `all configured battle effects have an executable mapping`() {
        val config = com.stzb.server.game.battle.BattleConfigRepository.loadDefault()
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = config.allSkillIds(),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val implemented = BattleEffectRegistry.strict(graph)
            .registerCoreEffects(BattleEffectStore())
            .registerControlEffects(BattleEffectStore())
            .registerMetaEffects()
            .implementedEffectIds()

        assertEquals(
            emptySet(),
            graph.effectIds - implemented - NON_BATTLE_EFFECT_IDS,
        )
    }

    private companion object {
        val NON_BATTLE_EFFECT_IDS = setOf(601, 605, 607)
    }
}
