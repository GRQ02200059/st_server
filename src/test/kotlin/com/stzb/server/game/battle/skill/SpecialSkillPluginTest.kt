package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecialSkillPluginTest {
    private val config = BattleConfigRepository.loadDefault()

    @Test
    fun `registry rejects duplicate execution ownership`() {
        val first = stubExecutionPlugin("first", setOf(200036))
        val second = stubExecutionPlugin("second", setOf(200036))

        val error = assertFailsWith<IllegalArgumentException> {
            SpecialSkillPluginRegistry(listOf(first, second))
        }

        assertTrue(error.message.orEmpty().contains("200036"))
    }

    @Test
    fun `configured registry owns only genuinely special compatibility skill`() {
        val registry = ConfiguredSpecialSkillPlugins.registry(config)

        assertEquals("skill.200036", registry.pluginFor(200036)?.id)
        assertNull(registry.pluginFor(200001), "200001 is fully declarative and needs no plugin")
    }

    @Test
    fun `coverage reports exact fail closed condition plugin debt without duplicate execution`() {
        val graph = SkillRuleCatalog.build(SkillScopeCatalog.loadDefault(), config)
        val conditions = SkillConditionInterpreter(graph)
        val report = SkillCoverageReport.generate(
            graph = graph,
            conditionInterpreter = conditions,
            executionPlugins = ConfiguredSpecialSkillPlugins.registry(config),
        )

        val resolvedTargetCodes = ScopedConditionCodeCatalog.codes.filterTo(linkedSetOf()) {
            it.field == SkillConditionField.PRECONDITION &&
                it.value in setOf(
                    -80, -70, 70, 80, -14, 14, 16,
                    100003, 100010, 100479, 100661,
                ) ||
                it.field == SkillConditionField.CAST_CONDITION &&
                it.value in setOf(104, 203, 205, 207, 303) ||
                it.field == SkillConditionField.CONDITION &&
                it.value in setOf(1030, 1050, 1060, 1070, 1080, 1090, 2050, 2060)
        }
        assertEquals(
            (ScopedConditionCodeCatalog.codes - resolvedTargetCodes)
                .mapTo(linkedSetOf()) { it.skillId },
            report.unresolvedConditionOwnerSkillIds,
        )
        assertEquals(ScopedConditionCodeCatalog.codes - resolvedTargetCodes, report.pendingConditionCodes)
        assertTrue(report.missingPluginSkillIds.isEmpty())
        assertTrue(report.duplicateExecutionSkillIds.isEmpty())
        assertEquals(setOf(200036), report.executionPluginSkillIds)

        val missing = SkillCoverageReport.generate(
            graph = graph,
            conditionInterpreter = conditions,
            executionPlugins = SpecialSkillPluginRegistry(emptyList()),
        )
        assertEquals(setOf(200036), missing.missingPluginSkillIds)
    }

    @Test
    fun `coverage reports a plugin that does not replace configured execution as missing and duplicate`() {
        val graph = SkillRuleCatalog.build(SkillScopeCatalog.loadDefault(), config)
        val conditions = SkillConditionInterpreter(graph)
        val misconfigured = SpecialSkillPluginRegistry(
            listOf(
                stubExecutionPlugin(
                    pluginId = "misconfigured.200036",
                    ids = setOf(200036),
                    replacesConfiguredExecution = false,
                ),
            ),
        )

        val report = SkillCoverageReport.generate(
            graph = graph,
            conditionInterpreter = conditions,
            executionPlugins = misconfigured,
            ownershipCatalog = SkillExecutionOwnershipCatalog(setOf(200036)),
        )

        assertEquals(setOf(200036), report.missingPluginSkillIds)
        assertEquals(setOf(200036), report.duplicateExecutionSkillIds)
    }

    @Test
    fun `fuwangyikou grants front and middle two active damage reductions`() {
        val fixture = fixture()
        val plugin = ConfiguredSpecialSkillPlugins.registry(config).pluginFor(200036)!!

        val result = plugin.execute(
            SpecialSkillInvocation(
                phase = SpecialSkillPhase.BATTLE_PREPARE,
                owner = fixture.owner,
                actor = fixture.owner,
                context = fixture.context,
            ),
        )

        val reductions = result.stateChanges.filterIsInstance<DamageModifierChange>()
        assertEquals(setOf(fixture.middle, fixture.front), reductions.mapTo(linkedSetOf()) { it.target })
        reductions.forEach {
            assertEquals(DamageModifierChange.Direction.TAKEN, it.direction)
            assertEquals(DamageOrigin.ACTIVE, it.origin)
            assertEquals(-16, it.percent)
            assertEquals(2, it.availableHits)
            assertEquals(200036, it.skillId)
            assertEquals(352, it.effectId)
        }
    }

    @Test
    fun `fuwangyikou damage reduction uses configured strategy scaling`() {
        fun reductionsAtStrategy(strategy: Int): Set<Int> {
            val fixture = fixture(ownerStrategy = strategy)
            return ConfiguredSpecialSkillPlugins.registry(config)
                .pluginFor(200036)!!
                .execute(
                    SpecialSkillInvocation(
                        phase = SpecialSkillPhase.BATTLE_PREPARE,
                        owner = fixture.owner,
                        actor = fixture.owner,
                        context = fixture.context,
                    ),
                )
                .stateChanges
                .filterIsInstance<DamageModifierChange>()
                .mapTo(linkedSetOf()) { it.percent }
        }

        assertEquals(setOf(-16), reductionsAtStrategy(80))
        assertEquals(setOf(-18), reductionsAtStrategy(180))
    }

    @Test
    fun `fuwangyikou active damage reduction expires after two matching hits`() {
        val fixture = fixture()
        val plugin = ConfiguredSpecialSkillPlugins.registry(config).pluginFor(200036)!!
        val changes = plugin.execute(
            SpecialSkillInvocation(
                phase = SpecialSkillPhase.BATTLE_PREPARE,
                owner = fixture.owner,
                actor = fixture.owner,
                context = fixture.context,
            ),
        ).stateChanges
        val battleState = fixture.state
        val applier = BattleStateChangeApplier(battleState)
        applier.apply(changes, 0)
        assertEquals(2, battleState.effectStore.effectsFor(fixture.front).single().remainingHits)

        repeat(2) {
            val troops = battleState.view.state(fixture.front)!!.troops
            applier.apply(
                listOf(
                    TroopDamageChange(
                        source = fixture.enemy,
                        target = fixture.front,
                        amount = 1,
                        troopsAfter = troops - 1,
                        school = DamageSchool.STRATEGY,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 200012,
                        effectId = 302,
                    ),
                ),
                it + 1,
            )
        }

        assertTrue(battleState.effectStore.effectsFor(fixture.front).isEmpty())
    }

    @Test
    fun `fuwangyikou reacts to successful active skills with capped side specific stats`() {
        val fixture = fixture()
        val plugin = ConfiguredSpecialSkillPlugins.registry(config).pluginFor(200036)!!
        fixture.context.runtime.recordSuccessfulExecution(
            fixture.front,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            200012,
        )
        val allied = plugin.execute(
            SpecialSkillInvocation(
                phase = SpecialSkillPhase.AFTER_SUCCESSFUL_SKILL,
                owner = fixture.owner,
                actor = fixture.front,
                successfulSkillId = 200012,
                successfulSkillKind = SkillKind.ACTIVE,
                context = fixture.context,
            ),
        ).stateChanges.filterIsInstance<BattleStatChange>()

        assertEquals(
            setOf(BattleStatChange.Kind.ATTACK to 11, BattleStatChange.Kind.STRATEGY to 13),
            allied.mapTo(linkedSetOf()) { it.kind to it.potency.value },
        )
        assertTrue(allied.all { it.potency.unit == BattleEffectValueUnit.FLAT })
        assertTrue(allied.all { it.target == fixture.front && it.durationRounds == 10 })

        fixture.context.runtime.recordSuccessfulExecution(
            fixture.enemy,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            200012,
        )
        val hostile = plugin.execute(
            SpecialSkillInvocation(
                phase = SpecialSkillPhase.AFTER_SUCCESSFUL_SKILL,
                owner = fixture.owner,
                actor = fixture.enemy,
                successfulSkillId = 200012,
                successfulSkillKind = SkillKind.ACTIVE,
                context = fixture.context,
            ),
        ).stateChanges.filterIsInstance<BattleStatChange>()

        assertEquals(
            setOf(BattleStatChange.Kind.DEFENSE, BattleStatChange.Kind.SPEED),
            hostile.mapTo(linkedSetOf(), BattleStatChange::kind),
        )
        assertTrue(hostile.all { it.target == fixture.enemy && it.potency.value < 0 })
        assertTrue(hostile.all { it.potency.unit == BattleEffectValueUnit.FLAT })

        repeat(5) {
            fixture.context.runtime.recordSuccessfulExecution(
                fixture.front,
                BattleTrigger.ACTIVE_SKILL_ATTEMPT,
                200012,
            )
        }
        val capped = plugin.execute(
            SpecialSkillInvocation(
                phase = SpecialSkillPhase.AFTER_SUCCESSFUL_SKILL,
                owner = fixture.owner,
                actor = fixture.front,
                successfulSkillId = 200012,
                successfulSkillKind = SkillKind.ACTIVE,
                context = fixture.context,
            ),
        )
        assertTrue(capped.stateChanges.isEmpty(), "the sixth successful active skill must not stack")
    }

    private fun fixture(ownerStrategy: Int = 100): Fixture {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100036, 0, listOf(200036), strategy = ownerStrategy),
                    hero(100001, 1),
                    hero(100002, 2),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 0))),
        )
        val owner = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100036))
        val middle = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(100001))
        val front = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(100002))
        val enemy = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(200001))
        val state = SkillBattleState(request, SkillRuntimeState())
        return Fixture(
            owner,
            middle,
            front,
            enemy,
            state,
            SkillBattleContext(
                request = request,
                runtime = state.runtime,
                random = FixedBattleRandom(0),
                round = 0,
                source = owner,
                rootSkillId = 200036,
                currentSkillId = 200036,
                trigger = BattleTrigger.BATTLE_COMMAND,
                battleView = state.view,
            ),
        )
    }

    private fun hero(
        id: Int,
        position: Int,
        skills: List<Int> = emptyList(),
        strategy: Int = 100,
    ) = BattleHero(
        id = BattleHeroId(id),
        position = position,
        stats = BattleStats(100, 100, strategy, 100, 0, 5),
        troops = 1_000,
        skillIds = skills,
    )

    private fun stubExecutionPlugin(
        pluginId: String,
        ids: Set<Int>,
        replacesConfiguredExecution: Boolean = true,
    ) = object : SkillExecutionPlugin {
        override val id: String = pluginId
        override val skillIds: Set<Int> = ids
        override val replacesConfiguredExecution: Boolean = replacesConfiguredExecution
        override fun execute(invocation: SpecialSkillInvocation): SkillExecutionResult =
            SkillExecutionResult.EMPTY
    }

    private data class Fixture(
        val owner: BattleHeroRef,
        val middle: BattleHeroRef,
        val front: BattleHeroRef,
        val enemy: BattleHeroRef,
        val state: SkillBattleState,
        val context: SkillBattleContext,
    )
}
