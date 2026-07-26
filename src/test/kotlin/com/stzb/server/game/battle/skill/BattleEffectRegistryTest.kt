package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BattleEffectRegistryTest {
    private val defaultGraph by lazy {
        SkillRuleCatalog.build(
            SkillScopeCatalog.loadDefault(),
            BattleConfigRepository.loadDefault(),
        )
    }

    @Test
    fun `strict registry declares exactly every scoped effect without claiming implementations`() {
        val registry = BattleEffectRegistry.strict()

        assertEquals(112, registry.declaredEffectIds().size)
        assertEquals(defaultGraph.effectIds, registry.declaredEffectIds())
        assertEquals(emptySet(), registry.implementedEffectIds())
        assertFailsWith<UnsupportedOperationException> {
            (registry.declaredEffectIds() as MutableSet<Int>).add(999)
        }
        assertFailsWith<UnsupportedOperationException> {
            (registry.implementedEffectIds() as MutableSet<Int>).add(1)
        }
    }

    @Test
    fun `effect zero is declared as meta no-op but remains unimplemented`() {
        val registry = BattleEffectRegistry.strict()

        assertEquals(
            EffectDeclaration(0, EffectDeclarationKind.META_NO_OP),
            registry.declaration(0),
        )
        assertTrue(0 !in registry.implementedEffectIds())
        val error = assertFailsWith<UnsupportedSkillRuleException> {
            registry.execute(effectRule(detailId = 100, effectId = 0), context(skillId = 1))
        }
        assertEquals(EffectFailureCode.UNIMPLEMENTED_EFFECT, error.diagnostic.code)
    }

    @Test
    fun `strict mode distinguishes unknown from declared unimplemented effects with full context`() {
        val runtime = SkillRuntimeState().apply {
            enter(1)
            enter(2)
        }
        val context = context(
            skillId = 2,
            rootSkillId = 1,
            trigger = BattleTrigger.DAMAGE_AFTER,
            runtime = runtime,
        )
        val registry = BattleEffectRegistry.strict(graph(effectIds = setOf(7)))

        val unknown = assertFailsWith<UnsupportedSkillRuleException> {
            registry.execute(effectRule(detailId = 201, effectId = 999), context)
        }
        assertEquals(EffectFailureCode.UNKNOWN_EFFECT, unknown.diagnostic.code)
        assertEquals(
            BattleEffectDiagnostic(
                code = EffectFailureCode.UNKNOWN_EFFECT,
                skillId = 2,
                detailId = 201,
                effectId = 999,
                trigger = BattleTrigger.DAMAGE_AFTER,
                callPath = listOf(1, 2),
            ),
            unknown.diagnostic,
        )
        assertTrue(
            unknown.message!!.contains(
                "skill=2 detail=201 effect=999 trigger=DAMAGE_AFTER callPath=1 -> 2",
            ),
        )

        val unimplemented = assertFailsWith<UnsupportedSkillRuleException> {
            registry.execute(effectRule(detailId = 202, effectId = 7), context)
        }
        assertEquals(EffectFailureCode.UNIMPLEMENTED_EFFECT, unimplemented.diagnostic.code)
        assertTrue(unimplemented.message!!.contains("skill=2 detail=202 effect=7"))
    }

    @Test
    fun `safe mode logs one structured diagnostic and returns empty without fabricated output`() {
        val diagnostics = mutableListOf<BattleEffectDiagnostic>()
        val registry = BattleEffectRegistry.safe(
            graph = graph(effectIds = setOf(7)),
            logger = diagnostics::add,
        )

        val execution = registry.execute(
            effectRule(detailId = 101, effectId = 7),
            context(skillId = 1, trigger = BattleTrigger.ROUND_START),
        )

        assertSame(EffectExecution.EMPTY, execution)
        assertEquals(emptyList(), execution.stateChanges)
        assertEquals(emptyList(), execution.events)
        assertEquals(1, diagnostics.size)
        assertEquals(EffectFailureCode.UNIMPLEMENTED_EFFECT, diagnostics.single().code)
        assertEquals(1, diagnostics.single().skillId)
        assertEquals(101, diagnostics.single().detailId)
        assertEquals(7, diagnostics.single().effectId)
        assertEquals(BattleTrigger.ROUND_START, diagnostics.single().trigger)
        assertEquals(listOf(1), diagnostics.single().callPath)
    }

    @Test
    fun `safe mode contains a throwing logger after invoking it exactly once`() {
        var loggerCalls = 0
        val registry = BattleEffectRegistry.safe(graph(effectIds = setOf(7))) {
            loggerCalls += 1
            throw IllegalStateException("diagnostic sink unavailable")
        }

        val execution = registry.execute(
            effectRule(detailId = 101, effectId = 7),
            context(skillId = 1),
        )

        assertSame(EffectExecution.EMPTY, execution)
        assertEquals(1, loggerCalls)
    }

    @Test
    fun `placeholder registration remains absent from implemented ids and fails in strict mode`() {
        val registry = BattleEffectRegistry.strict(graph(effectIds = setOf(7))).register(
            EffectHandlerRegistration.placeholder(7, "damage semantics not implemented"),
        )

        assertEquals(emptySet(), registry.implementedEffectIds())
        val error = assertFailsWith<UnsupportedSkillRuleException> {
            registry.execute(effectRule(detailId = 101, effectId = 7), context(skillId = 1))
        }
        assertEquals(EffectFailureCode.UNIMPLEMENTED_EFFECT, error.diagnostic.code)
    }

    @Test
    fun `registration rejects undeclared duplicate and empty implemented handlers`() {
        val handler = MeaningfulTestHandler()
        val registry = BattleEffectRegistry.strict(graph(effectIds = setOf(7)))

        val undeclared = assertFailsWith<IllegalArgumentException> {
            registry.register(EffectHandlerRegistration.implemented(999, handler))
        }
        assertTrue(undeclared.message!!.contains("Undeclared effect=999"))

        val registered = registry.register(EffectHandlerRegistration.implemented(7, handler))
        val duplicate = assertFailsWith<IllegalArgumentException> {
            registered.register(EffectHandlerRegistration.implemented(7, handler))
        }
        assertTrue(duplicate.message!!.contains("Duplicate handler for effect=7"))
        val empty = assertFailsWith<IllegalArgumentException> {
            EffectHandlerRegistration.implemented(7, EmptyTestHandler())
        }
        assertTrue(empty.message!!.contains("must declare non-empty semantics"))
        assertEquals(emptySet(), registry.implementedEffectIds())
        assertEquals(setOf(7), registered.implementedEffectIds())
    }

    @Test
    fun `batch registration rejects repeated effect ids before entries can be erased`() {
        val registry = BattleEffectRegistry.strict(graph(effectIds = setOf(7)))

        val error = assertFailsWith<IllegalArgumentException> {
            registry.register(
                EffectHandlerRegistration.placeholder(7, "first owner"),
                EffectHandlerRegistration.implemented(7, MeaningfulTestHandler()),
            )
        }

        assertTrue(error.message!!.contains("Duplicate handlers for effects=[7]"))
    }

    @Test
    fun `registered handler receives invocation and preserves state changes and events`() {
        val stateChange = object : BattleStateChange {}
        val event = BattleEvent.RoundStart(3)
        val stateChanges = mutableListOf<BattleStateChange>(stateChange)
        val events = mutableListOf<BattleEvent>(event)
        val handler = RecordingTestHandler(stateChanges, events)
        val registry = BattleEffectRegistry.strict(graph(effectIds = setOf(7))).register(
            EffectHandlerRegistration.implemented(7, handler),
        )
        val rule = effectRule(detailId = 101, effectId = 7)
        val context = context(skillId = 1, trigger = BattleTrigger.ACTION_BEFORE)

        val execution = registry.execute(rule, context)
        stateChanges.clear()
        events.clear()

        assertSame(rule, handler.received!!.rule)
        assertSame(context, handler.received!!.context)
        assertEquals(listOf(1), handler.received!!.callPath)
        assertEquals(listOf(stateChange), execution.stateChanges)
        assertEquals(listOf(event), execution.events)
        assertFailsWith<UnsupportedOperationException> {
            (execution.stateChanges as MutableList<BattleStateChange>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (execution.events as MutableList<BattleEvent>).clear()
        }
    }

    private class MeaningfulTestHandler : ImplementedBattleEffectHandler {
        override val semanticId: String = "test.state-change"

        override fun execute(invocation: EffectInvocation): EffectExecution =
            EffectExecution(
                stateChanges = listOf(object : BattleStateChange {}),
                events = emptyList(),
            )
    }

    private class EmptyTestHandler : ImplementedBattleEffectHandler {
        override val semanticId: String = ""

        override fun execute(invocation: EffectInvocation): EffectExecution = EffectExecution.EMPTY
    }

    private class RecordingTestHandler(
        private val stateChanges: List<BattleStateChange>,
        private val events: List<BattleEvent>,
    ) : ImplementedBattleEffectHandler {
        override val semanticId: String = "test.recording"
        var received: EffectInvocation? = null

        override fun execute(invocation: EffectInvocation): EffectExecution {
            received = invocation
            return EffectExecution(stateChanges, events)
        }
    }

    private fun graph(effectIds: Set<Int>): SkillRuleGraph =
        SkillRuleGraph(
            rules = mapOf(
                1 to SkillRule(
                    skillId = 1,
                    kind = SkillKind.ACTIVE,
                    rawSkillType = 3,
                    probability = 100,
                    prepareRounds = 0,
                    hitRange = 3,
                    details = effectIds.mapIndexed { index, effectId ->
                        effectRule(detailId = 100 + index, effectId = effectId)
                    },
                ),
            ),
            effectIds = effectIds,
        )

    private fun effectRule(detailId: Int, effectId: Int): SkillEffectRule =
        SkillEffectRule(
            detailId = detailId,
            effectId = effectId,
            childSkillIds = emptySet(),
            raw = SkillDetailConfig(
                detailId = detailId,
                effectId = effectId,
                attackType = 1,
                targetType = 0,
                selectType = 0,
                intelParam = 0,
                constantParam = 0,
                probabilityInit = 100,
                probabilityMax = 100,
                attackMax = 1,
                availableRounds = 0,
                effectName = "fixture",
            ),
        )

    private fun context(
        skillId: Int,
        rootSkillId: Int = skillId,
        trigger: BattleTrigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
        runtime: SkillRuntimeState = SkillRuntimeState(),
    ): SkillBattleContext {
        val source = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        return SkillBattleContext(
            request = BattleRequest(
                attacker = BattleTeam(listOf(hero(1, 0))),
                defender = BattleTeam(listOf(hero(2, 0))),
            ),
            runtime = runtime,
            random = FixedBattleRandom(0),
            round = 3,
            source = source,
            rootSkillId = rootSkillId,
            currentSkillId = skillId,
            trigger = trigger,
        )
    }

    private fun hero(id: Int, position: Int): BattleHero =
        BattleHero(
            id = BattleHeroId(id),
            position = position,
            stats = BattleStats(100, 100, 100, 100, 100, 3),
            troops = 1000,
        )
}
