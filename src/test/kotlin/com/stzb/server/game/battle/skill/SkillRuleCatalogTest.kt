package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillRuleCatalogTest {
    @Test
    fun `target skills expand to complete validated rule graph`() {
        val graph = SkillRuleCatalog.build(
            SkillScopeCatalog.loadDefault(),
            com.stzb.server.game.battle.BattleConfigRepository.loadDefault(),
        )

        assertEquals(668, graph.executionNodeIds.size)
        assertEquals(
            112,
            graph.effectIds.size,
            "unresolved=${graph.details.map { it.effectId }.toSet() - graph.effectIds}",
        )
        assertEquals(1935, graph.details.size)
        assertTrue(graph.validate().isEmpty(), graph.validate().joinToString())
    }

    @Test
    fun `recursive child skill path reports exact cycle`() {
        val graph = fakeGraph(1 to listOf(2), 2 to listOf(1))

        assertEquals("1 -> 2 -> 1", graph.validate().single().dependencyPath)
    }

    @Test
    fun `self skill parameter for probability adjustment is not an execution dependency`() {
        val graph = SkillRuleCatalog.build(
            SkillScopeCatalog.loadDefault(),
            com.stzb.server.game.battle.BattleConfigRepository.loadDefault(),
        )

        val detail = graph.rule(200947)?.details?.single { it.detailId == 20094723 }
        assertEquals(200947, detail?.raw?.effectParam)
        assertTrue(detail?.childSkillIds.orEmpty().isEmpty())
    }

    private fun fakeGraph(vararg dependencies: Pair<Int, List<Int>>): SkillRuleGraph {
        val rules = dependencies.associate { (skillId, childSkillIds) ->
            skillId to SkillRule(
                skillId = skillId,
                kind = SkillKind.ACTIVE,
                probability = 100,
                prepareRounds = 0,
                hitRange = null,
                details = listOf(
                    SkillEffectRule(
                        detailId = skillId * 100,
                        effectId = 1,
                        childSkillIds = childSkillIds.toSet(),
                        raw = detail(skillId * 100),
                    ),
                ),
            )
        }
        return SkillRuleGraph(rules, effectIds = setOf(1))
    }

    private fun detail(detailId: Int) = SkillDetailConfig(
        detailId = detailId,
        effectId = 1,
        effectParam = 0,
        calcPos = 0,
        calcParam = 0,
        attackType = 0,
        selectSkillParam = 0,
        targetType = 0,
        selectType = 0,
        availableHit = 0,
        intelParam = 0,
        constantParam = 0,
        probabilityInit = 0,
        probabilityMax = 0,
        bindFlag = 0,
        castCondition = 0,
        precondition = 0,
        condition = 0,
        addCountMax = 0,
        buffType = 0,
        attackMax = 0,
        delayRound = 0,
        delayHit = 0,
        availableRounds = 0,
        clearPerHit = false,
        selectFlag = 0,
        inherent = 0,
        moraleAffected = false,
        calculationType = 0,
        effectName = "",
    )
}
