package com.stzb.server.game.battle.skill

import kotlin.test.Test
import kotlin.test.assertEquals

class CompleteSkillCoverageTest {
    @Test
    fun `all scoped skills have complete executable coverage`() {
        val report = SkillCoverageReport.generateDefault()

        assertEquals(308, report.mainSkills)
        assertEquals(668, report.executionNodes)
        assertEquals(112, report.effectIds)
        assertEquals(1935, report.detailRules)
        assertEquals(emptySet(), report.unsupportedEffects)
        assertEquals(emptySet(), report.unknownSelectors)
        assertEquals(emptySet(), report.brokenDependencies)
        assertEquals(emptySet(), report.noBehaviorSkills)
        assertEquals(emptySet(), report.missingPluginSkillIds)
        assertEquals(emptySet(), report.duplicateExecutionSkillIds)
        assertEquals(emptySet(), report.unknownConditions)
    }
}
