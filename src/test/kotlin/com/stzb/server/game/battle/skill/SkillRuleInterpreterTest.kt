package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActiveSkillEffect
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.EffectCategory
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillRuleInterpreterTest {
    @Test
    fun `real child ids inherit root source and preserve execution order`() {
        val graph = graph(
            rule(
                200017,
                effectRule(
                    detailId = 20001706,
                    effectId = 122,
                    childSkillIds = setOf(210017),
                    constantParam = 210017,
                    attackType = 21,
                    attackMax = 2,
                ),
            ),
            rule(210017, effectRule(21001701, 77)),
        )
        val context = context(skillId = 200017)
        val result = interpreter(graph).execute(
            200017,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context,
        )

        assertEquals(listOf(200017, 210017), result.executedSkillIds)
        assertEquals(
            listOf(200017, 210017),
            result.events.filterIsInstance<SkillTriggered>().map { it.skillId },
        )
        assertTrue(result.events.all { it.rootSkillId == 200017 })
        val markers = result.stateChanges.filterIsInstance<MarkerEffectChange>()
        assertEquals(1, markers.size)
        assertTrue(markers.all { it.source == context.source })
        assertEquals(
            setOf(Side.ATTACKER),
            markers.mapTo(linkedSetOf()) { it.target.side },
        )
    }

    @Test
    fun `recursive child call fails with exact dependency path and unwinds stack`() {
        val graph = graph(
            rule(1, effectRule(101, 122, setOf(2), constantParam = 2)),
            rule(2, effectRule(201, 122, setOf(1), constantParam = 1)),
        )
        val context = context(skillId = 1)

        val error = assertFailsWith<SkillRecursionException> {
            interpreter(graph).execute(1, BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)
        }

        assertTrue(error.message!!.contains("1 -> 2 -> 1"))
        assertEquals(emptyList(), context.runtime.currentCallPath())
    }

    @Test
    fun `missing child rule reports the full dependency path`() {
        val graph = graph(
            rule(1, effectRule(101, 122, setOf(2), constantParam = 2)),
        )

        val error = assertFailsWith<MissingSkillRuleException> {
            interpreter(graph).execute(
                1,
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                context(skillId = 1),
            )
        }

        assertTrue(error.message!!.contains("1 -> 2"))
    }

    @Test
    fun `maximum depth failure includes the complete attempted path`() {
        val rules = (1..17).map { skillId ->
            val childId = skillId + 1
            rule(
                skillId,
                effectRule(
                    skillId * 100 + 1,
                    122,
                    setOf(childId),
                    constantParam = childId,
                ),
            )
        } + rule(18, effectRule(1801, 0))
        val context = context(skillId = 1)

        val error = assertFailsWith<SkillRecursionException> {
            interpreter(graph(*rules.toTypedArray())).execute(
                1,
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                context,
            )
        }

        assertTrue(error.message!!.contains("maximum child depth"))
        assertTrue(error.message!!.contains((1..17).joinToString(" -> ")))
        assertEquals(emptyList(), context.runtime.currentCallPath())
    }

    @Test
    fun `handler exception always unwinds runtime stack`() {
        val graph = graph(rule(1, effectRule(101, 999)))
        val registry = BattleEffectRegistry.strict(graph).register(
            EffectHandlerRegistration.implemented(
                999,
                object : ImplementedBattleEffectHandler {
                    override val semanticId: String = "test.throw"

                    override fun execute(invocation: EffectInvocation): EffectExecution =
                        throw IllegalArgumentException("boom")
                },
            ),
        )
        val context = context(skillId = 1)

        assertFailsWith<IllegalArgumentException> {
            SkillRuleInterpreter(graph, registry).execute(
                1,
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                context,
            )
        }
        assertEquals(emptyList(), context.runtime.currentCallPath())
    }

    @Test
    fun `pending condition interpreter rejects every nonzero field explicitly`() {
        val graph = graph(
            rule(
                1,
                effectRule(101, 0, castCondition = 77),
            ),
        )

        val error = assertFailsWith<UnsupportedPendingSkillConditionException> {
            interpreter(graph).execute(
                1,
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                context(skillId = 1),
            )
        }

        assertTrue(error.message!!.contains("cast_condition=77"))
        assertTrue(error.message!!.contains("skill=1 detail=101"))
    }

    @Test
    fun `probability is rolled once with morale and existing modifiers`() {
        val random = CountingRandom(69)
        val graph = graph(rule(1, effectRule(101, 0), probability = 50))
        val context = context(
            skillId = 1,
            random = random,
            sourceModifiers = listOf(
                com.stzb.server.game.battle.BattleModifier.SkillProbabilityPercent(20),
            ),
        )

        val result = interpreter(graph).execute(
            1,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context,
        )

        assertEquals(listOf(1), result.executedSkillIds)
        assertEquals(1, random.calls)
    }

    @Test
    fun `trigger mismatch neither rolls nor executes`() {
        val random = CountingRandom(0)
        val graph = graph(rule(1, effectRule(101, 0)))

        val result = interpreter(graph).execute(
            1,
            BattleTrigger.PURSUIT_ATTEMPT,
            context(skillId = 1, random = random),
        )

        assertEquals(SkillExecutionResult.EMPTY, result)
        assertEquals(0, random.calls)
    }

    @Test
    fun `effect zero is an explicit no behavior implementation with a trigger event`() {
        val graph = graph(rule(1, effectRule(101, 0)))
        val registry = BattleEffectRegistry.strict(graph).registerMetaEffects()

        val result = SkillRuleInterpreter(graph, registry).execute(
            1,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context(skillId = 1),
        )

        assertEquals(setOf(0), registry.implementedEffectIds())
        assertEquals(emptyList(), result.stateChanges)
        assertEquals(1, result.events.filterIsInstance<SkillTriggered>().size)
    }

    @Test
    fun `meta registry owns the exact meaningful non placeholder effect set`() {
        val graph = graph(
            rule(
                1,
                *MetaEffectHandlers.effectIds.sorted()
                    .mapIndexed { index, effectId -> effectRule(100 + index, effectId) }
                    .toTypedArray(),
            ),
        )
        val registry = BattleEffectRegistry.strict(graph).registerMetaEffects()

        assertEquals(EXPECTED_META_EFFECT_IDS, MetaEffectHandlers.effectIds)
        assertEquals(EXPECTED_META_EFFECT_IDS, registry.implementedEffectIds())
        EXPECTED_META_EFFECT_IDS.forEach { effectId ->
            val semantic = registry.implementationSemanticId(effectId)
            assertTrue(!semantic.isNullOrBlank(), "effect=$effectId")
            if (effectId != 0) {
                assertTrue(!semantic.contains("placeholder"), "effect=$effectId semantic=$semantic")
                assertTrue(!semantic.contains("no-op"), "effect=$effectId semantic=$semantic")
            }
        }
    }

    @Test
    fun `trigger effect executes the referenced detail through the registry`() {
        val graph = graph(
            rule(
                1,
                effectRule(
                    detailId = 101,
                    effectId = 151,
                    effectParam = 201,
                    attackType = 21,
                    attackMax = 2,
                ),
            ),
            rule(2, effectRule(201, 77, attackType = 21, attackMax = 2)),
        )

        val result = interpreter(graph).execute(
            1,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context(skillId = 1),
        )

        assertEquals(listOf(1), result.executedSkillIds)
        assertEquals(2, result.stateChanges.filterIsInstance<MarkerEffectChange>().size)
        assertTrue(result.stateChanges.any { it is TriggerReferencedEffectChange })
    }

    @Test
    fun `clear effect change removes only matching referenced detail on selected target`() {
        val store = BattleEffectStore()
        val source = ref(Side.ATTACKER, 0, 1)
        val target = ref(Side.ATTACKER, 1, 2)
        store.apply(activeEffect(source, target, detailId = 201, effectId = 77))
        store.apply(activeEffect(source, target, detailId = 202, effectId = 77))
        val change = ClearReferencedEffectChange(
            source = source,
            target = target,
            rootSkillId = 1,
            skillId = 1,
            detailId = 101,
            referencedDetailId = 201,
        )

        val removed = change.apply(store)

        assertEquals(listOf(201), removed.removed.map { it.detailId })
        assertEquals(listOf(202), store.effectsFor(target).map { it.detailId })
    }

    @Test
    fun `retrigger executes an allied active skill once and records duplicate root attempts`() {
        val graph = graph(
            rule(
                1,
                effectRule(
                    101,
                    129,
                    attackType = 11,
                    availableHit = 1,
                ),
            ),
            rule(2, effectRule(201, 0)),
        )
        val context = context(
            skillId = 1,
            alliedSkillIds = listOf(2),
        )
        val interpreter = interpreter(graph)

        val first = interpreter.execute(1, BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)
        val second = interpreter.execute(1, BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)

        assertEquals(listOf(1, 2), first.executedSkillIds)
        assertEquals(listOf(1), second.executedSkillIds)
        assertEquals(2, context.runtime.count(context.source, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 1))
        assertEquals(
            1,
            context.runtime.count(
                ref(Side.ATTACKER, 1, 2),
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                2,
            ),
        )
    }

    @Test
    fun `result collections are immutable`() {
        val graph = graph(rule(1, effectRule(101, 0)))
        val result = interpreter(graph).execute(
            1,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context(skillId = 1),
        )

        assertFailsWith<UnsupportedOperationException> {
            (result.executedSkillIds as MutableList<Int>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (result.events as MutableList<SkillExecutionEvent>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (result.stateChanges as MutableList<BattleStateChange>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (result.diagnostics as MutableList<SkillExecutionDiagnostic>).clear()
        }
    }

    private fun interpreter(graph: SkillRuleGraph): SkillRuleInterpreter =
        SkillRuleInterpreter(
            graph = graph,
            registry = BattleEffectRegistry.strict(graph).registerMetaEffects(),
        )

    private fun graph(vararg rules: SkillRule): SkillRuleGraph =
        SkillRuleGraph(
            rules = rules.associateBy(SkillRule::skillId),
            effectIds = rules.flatMap { it.details }.mapTo(linkedSetOf()) { it.effectId },
            rootSkillIds = setOf(rules.first().skillId),
        )

    private fun rule(
        skillId: Int,
        vararg details: SkillEffectRule,
        probability: Int = 100,
        kind: SkillKind = SkillKind.ACTIVE,
    ): SkillRule =
        SkillRule(
            skillId = skillId,
            kind = kind,
            rawSkillType = when (kind) {
                SkillKind.PASSIVE -> 1
                SkillKind.COMMAND -> 2
                SkillKind.ACTIVE -> 3
                SkillKind.PURSUIT -> 4
                SkillKind.UNKNOWN -> 99
            },
            probability = probability,
            prepareRounds = 0,
            hitRange = 5,
            details = details.toList(),
        ).let { rule ->
            rule.copy(
                details = rule.details.map {
                    it.copy(skillKind = rule.kind, rawSkillType = rule.rawSkillType)
                },
            )
        }

    private fun effectRule(
        detailId: Int,
        effectId: Int,
        childSkillIds: Set<Int> = emptySet(),
        effectParam: Int = 0,
        constantParam: Int = 0,
        attackType: Int = 0,
        attackMax: Int = 1,
        availableHit: Int = 0,
        castCondition: Int = 0,
    ): SkillEffectRule =
        SkillEffectRule(
            detailId = detailId,
            effectId = effectId,
            childSkillIds = childSkillIds,
            raw = SkillDetailConfig(
                detailId = detailId,
                effectId = effectId,
                effectParam = effectParam,
                attackType = attackType,
                targetType = 0,
                selectType = 0,
                availableHit = availableHit,
                intelParam = 0,
                constantParam = constantParam,
                probabilityInit = 100,
                probabilityMax = 100,
                castCondition = castCondition,
                attackMax = attackMax,
                availableRounds = 2,
                effectName = "fixture-$effectId",
            ),
            effectBuffType = when (effectId) {
                123, 114, 152, 181, 231, 261 -> 1
                else -> 2
            },
            effectReplaceType = 0,
        )

    private fun context(
        skillId: Int,
        random: BattleRandom = FixedBattleRandom(0),
        sourceModifiers: List<com.stzb.server.game.battle.BattleModifier> = emptyList(),
        alliedSkillIds: List<Int> = emptyList(),
    ): SkillBattleContext {
        val source = hero(1, 0, modifiers = sourceModifiers)
        val ally = hero(2, 1, skillIds = alliedSkillIds)
        val enemy = hero(3, 0)
        return SkillBattleContext(
            request = BattleRequest(
                attacker = BattleTeam(listOf(source, ally)),
                defender = BattleTeam(listOf(enemy)),
            ),
            runtime = SkillRuntimeState(),
            random = random,
            round = 3,
            source = ref(Side.ATTACKER, source.position, source.id.value),
            rootSkillId = skillId,
            currentSkillId = skillId,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
        )
    }

    private fun hero(
        id: Int,
        position: Int,
        skillIds: List<Int> = emptyList(),
        modifiers: List<com.stzb.server.game.battle.BattleModifier> = emptyList(),
    ): BattleHero =
        BattleHero(
            id = BattleHeroId(id),
            position = position,
            stats = BattleStats(100, 100, 100, 100, 100, 5),
            troops = 1_000,
            maxTroops = 1_000,
            skillIds = skillIds,
            modifiers = modifiers,
            morale = 100,
        )

    private fun activeEffect(
        source: BattleHeroRef,
        target: BattleHeroRef,
        detailId: Int,
        effectId: Int,
    ): ActiveSkillEffect =
        ActiveSkillEffect(
            source = source,
            target = target,
            rootSkillId = 1,
            skillId = 1,
            skillKind = SkillKind.ACTIVE,
            sourceSkillType = 3,
            detailId = detailId,
            effectId = effectId,
            category = EffectCategory.BENEFICIAL,
            conflict = 0,
            strength = 1,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            stacks = 1,
            remainingRounds = 2,
            remainingHits = null,
            clearPerHit = false,
        )

    private fun ref(side: Side, position: Int, heroId: Int): BattleHeroRef =
        BattleHeroRef(side, position, BattleHeroId(heroId))

    private class CountingRandom(
        private val value: Int,
    ) : BattleRandom {
        var calls: Int = 0

        override fun nextInt(bound: Int): Int {
            calls += 1
            return value.coerceIn(0, bound - 1)
        }
    }

    private companion object {
        val EXPECTED_META_EFFECT_IDS = setOf(
            0,
            77,
            81, 82, 83,
            88,
            111, 112, 113, 114,
            118,
            121, 122, 123,
            125, 127, 129, 130, 131,
            141, 149,
            151, 152, 153,
            161, 171, 181, 199, 200, 210, 231, 261, 281, 313,
            404, 407, 408, 409,
        )
    }
}
