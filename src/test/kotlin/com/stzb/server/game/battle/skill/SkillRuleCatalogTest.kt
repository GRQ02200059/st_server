package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val diagnostics = graph.validate()
        assertTrue(diagnostics.isEmpty(), diagnostics.joinToString())
    }

    @Test
    fun `rule identity preserves raw skill type without collapsing unknown values`() {
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200012, 270012),
                learnableSaSkillIds = emptySet(),
            ),
            com.stzb.server.game.battle.BattleConfigRepository.loadDefault(),
        )

        assertEquals(3, graph.rule(200012)?.rawSkillType)
        assertEquals(14, graph.rule(270012)?.rawSkillType)
    }

    @Test
    fun `recursive child skill path reports exact cycle`() {
        val graph = fakeGraph(1 to listOf(2), 2 to listOf(1))

        assertEquals("1 -> 2 -> 1", graph.validate().single().dependencyPath)
    }

    @Test
    fun `only verified field and effect pairs produce execution dependencies`() {
        val graph = SkillRuleCatalog.build(
            SkillScopeCatalog.loadDefault(),
            com.stzb.server.game.battle.BattleConfigRepository.loadDefault(),
        )

        assertEquals(setOf(210006), graph.detail(20000601).childSkillIds)
        assertEquals(setOf(210024), graph.detail(20002402).childSkillIds)
        assertEquals(setOf(210255), graph.detail(20025501).childSkillIds)
        assertEquals(setOf(213828), graph.detail(21082802).childSkillIds)
    }

    @Test
    fun `metadata skill targets are not execution dependencies`() {
        val config = com.stzb.server.game.battle.BattleConfigRepository.loadDefault()
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200806, 200947, 200948, 200284, 200871, 200875),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )

        assertEquals(setOf(200217, 220217), setOf(
            graph.detail(20087501).raw.effectParam,
            graph.detail(20087501).raw.constantParam,
        ))
        assertTrue(graph.detail(20087501).childSkillIds.isEmpty())
        assertEquals(200212, graph.detail(20080602).raw.effectParam)
        assertTrue(graph.detail(20080602).childSkillIds.isEmpty())
        assertEquals(200806, graph.detail(20080601).raw.effectParam)
        assertTrue(graph.detail(20080601).childSkillIds.isEmpty())
        assertEquals(200690, graph.detail(20087102).raw.effectParam)
        assertTrue(graph.detail(20087102).childSkillIds.isEmpty())
        assertEquals(200947, graph.detail(20094723).raw.effectParam)
        assertTrue(graph.detail(20094723).childSkillIds.isEmpty())
        assertEquals(200948, graph.detail(20094811).raw.effectParam)
        assertTrue(graph.detail(20094811).childSkillIds.isEmpty())
        assertEquals(200284, graph.detail(20028424).raw.effectParam)
        assertTrue(graph.detail(20028424).childSkillIds.isEmpty())
    }

    @Test
    fun `genuine self child reports cycle`() {
        val graph = fakeGraph(1 to listOf(1))

        assertEquals("1 -> 1", graph.validate().single().dependencyPath)
    }

    @Test
    fun `strict diagnostics include complete root paths and context`() {
        val missingRoot = SkillRuleGraph(
            rules = emptyMap(),
            effectIds = emptySet(),
            rootSkillIds = setOf(9),
        )
        assertEquals(
            SkillDiagnostic(9, null, null, "MISSING_SKILL", "9"),
            missingRoot.validate().single(),
        )

        val zeroDetails = SkillRuleGraph(
            rules = mapOf(1 to rule(1, emptyList())),
            effectIds = emptySet(),
            rootSkillIds = setOf(1),
        )
        assertEquals(
            SkillDiagnostic(1, null, null, "MISSING_DETAILS", "1"),
            zeroDetails.validate().single(),
        )

        val missingDependency = fakeGraph(
            1 to listOf(2),
            2 to listOf(3),
            rootSkillIds = setOf(1),
        )
        assertEquals(
            SkillDiagnostic(2, 200, 1, "MISSING_SKILL", "1 -> 2 -> 3"),
            missingDependency.validate().single(),
        )

        val missingEffect = SkillRuleGraph(
            rules = mapOf(
                1 to rule(1, listOf(effectRule(100, 1, setOf(2)))),
                2 to rule(2, listOf(effectRule(200, 99, emptySet()))),
            ),
            effectIds = setOf(1),
            rootSkillIds = setOf(1),
        )
        assertEquals(
            SkillDiagnostic(2, 200, 99, "MISSING_EFFECT", "1 -> 2"),
            missingEffect.validate().single(),
        )
    }

    @Test
    fun `graph collections are deeply immutable to Java callers`() {
        val mutableChildren = linkedSetOf(2)
        val mutableDetails = mutableListOf(effectRule(100, 1, mutableChildren))
        val mutableRules = linkedMapOf(1 to rule(1, mutableDetails))
        val mutableEffects = linkedSetOf(1)
        val graph = SkillRuleGraph(mutableRules, mutableEffects, rootSkillIds = setOf(1))

        mutableChildren += 3
        mutableDetails.clear()
        mutableRules.clear()
        mutableEffects += 2

        assertEquals(setOf(1), graph.executionNodeIds)
        assertEquals(setOf(1), graph.effectIds)
        assertEquals(1, graph.details.size)
        assertEquals(setOf(2), graph.rule(1)?.details?.single()?.childSkillIds)
        assertFailsWith<UnsupportedOperationException> {
            (graph.executionNodeIds as MutableSet).add(3)
        }
        assertFailsWith<UnsupportedOperationException> {
            (graph.effectIds as MutableSet).add(2)
        }
        assertFailsWith<UnsupportedOperationException> {
            (graph.details as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (graph.rule(1)!!.details as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (graph.rule(1)!!.details.single().childSkillIds as MutableSet).add(3)
        }
    }

    private fun SkillRuleGraph.detail(detailId: Int): SkillEffectRule =
        details.single { it.detailId == detailId }

    private fun fakeGraph(
        vararg dependencies: Pair<Int, List<Int>>,
        rootSkillIds: Set<Int> = dependencies.mapTo(linkedSetOf()) { it.first },
    ): SkillRuleGraph {
        val rules = dependencies.associate { (skillId, childSkillIds) ->
            skillId to rule(
                skillId,
                listOf(effectRule(skillId * 100, 1, childSkillIds.toSet())),
            )
        }
        return SkillRuleGraph(rules, effectIds = setOf(1), rootSkillIds = rootSkillIds)
    }

    private fun rule(skillId: Int, details: List<SkillEffectRule>) = SkillRule(
        skillId = skillId,
        kind = SkillKind.ACTIVE,
        rawSkillType = 3,
        probability = 100,
        prepareRounds = 0,
        hitRange = null,
        details = details,
    )

    private fun effectRule(
        detailId: Int,
        effectId: Int,
        childSkillIds: Set<Int>,
    ) = SkillEffectRule(
        detailId = detailId,
        effectId = effectId,
        childSkillIds = childSkillIds,
        raw = detail(detailId, effectId),
    )

    private fun detail(detailId: Int, effectId: Int = 1) = SkillDetailConfig(
        detailId = detailId,
        effectId = effectId,
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
