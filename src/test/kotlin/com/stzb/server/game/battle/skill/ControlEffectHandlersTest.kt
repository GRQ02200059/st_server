package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActiveSkillEffect
import com.stzb.server.game.battle.ActionPermission
import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.ConfiguredBattleEffectValue
import com.stzb.server.game.battle.EffectCategory
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlEffectHandlersTest {
    private val source = ref(Side.ATTACKER, 0, 1)
    private val ally = ref(Side.ATTACKER, 1, 2)
    private val target = ref(Side.DEFENDER, 0, 3)

    @Test
    fun `registry implements the exact configured control and action id set`() {
        assertEquals(controlIds, ControlEffectHandlers.effectIds)
        assertEquals(
            controlIds,
            registry(controlIds.map(::rule)).implementedEffectIds(),
        )
    }

    @Test
    fun `every configured id emits a meaningful typed change`() {
        controlIds.forEach { effectId ->
            val execution = execute(effectId, BattleEffectStore())
            assertTrue(execution.stateChanges.isNotEmpty(), "effect=$effectId")
            val spec = when (val first = execution.stateChanges.first()) {
                is ApplyBattleEffectChange -> first.spec
                is ActionEffectChange -> first.spec
                is CleanseEffectsChange -> first.spec
                else -> null
            }
            spec?.let {
                assertEquals(effectId, it.effectId)
                assertEquals(source, it.source)
                assertEquals(target, it.target)
                assertEquals(10_000 + effectId, it.detailId)
                assertEquals(3, it.rawSkillType)
            }
        }
    }

    @Test
    fun `unknown raw skill kind fails instead of fabricating action semantics`() {
        val malformed = rule(544).copy(
            skillKind = SkillKind.UNKNOWN,
            rawSkillType = 14,
        )
        val error = assertFailsWith<UnsupportedConfiguredBattleValueException> {
            BattleEffectRegistry.strict(graph(controlIds.map(::rule)))
                .registerControlEffects(BattleEffectStore())
                .execute(malformed, context(target))
        }
        assertTrue(error.diagnostic.reason.orEmpty().contains("rawSkillType=14"))
    }

    @Test
    fun `control immunity matrix follows configured effects`() {
        val store = BattleEffectStore()
        store.apply(active(511, target = target, category = EffectCategory.BENEFICIAL))
        listOf(501, 502, 503, 505, 701, 702, 703, 901, 902, 903).forEach { effectId ->
            val blocked = execute(effectId, store)
            assertIs<EffectBlockedChange>(blocked.stateChanges.single(), "effect=$effectId")
            assertEquals(emptyList(), blocked.events)
        }
        store.apply(active(594, target = target, category = EffectCategory.BENEFICIAL))
        listOf(552, 752, 952).forEach { effectId ->
            val blocked = execute(effectId, store)
            assertIs<EffectBlockedChange>(blocked.stateChanges.single(), "effect=$effectId")
        }
        assertIs<ApplyBattleEffectChange>(execute(514, store).stateChanges.single())
        assertTrue(execute(515, store).events.single() is BattleEvent.StatusApplied)

        val disarmOnly = BattleEffectStore()
        disarmOnly.apply(active(594, target = target, category = EffectCategory.BENEFICIAL))
        assertIs<ApplyBattleEffectChange>(execute(501, disarmOnly).stateChanges.first())
    }

    @Test
    fun `control application retains lifecycle and requests preparation cancellation`() {
        val execution = execute(501, BattleEffectStore())
        val applied = assertIs<ApplyBattleEffectChange>(execution.stateChanges[0])
        assertEquals(501, applied.spec.effectId)
        assertEquals(2, applied.spec.availableRounds)
        assertEquals(EffectStartBoundary.IMMEDIATE, applied.spec.startBoundary)
        assertEquals(
            CancelPreparedSkillsChange(applied.spec),
            execution.stateChanges[1],
        )
        assertTrue(execution.events.single() is BattleEvent.StatusApplied)

        val prepared = execute(702, BattleEffectStore()).stateChanges
        val preparedEffect = assertIs<ApplyBattleEffectChange>(prepared[0])
        assertEquals(EffectStartBoundary.AFTER_DELAY, preparedEffect.spec.startBoundary)
        assertIs<CancelPreparedSkillsChange>(prepared[1])

        val runtime = SkillRuntimeState()
        runtime.prepare(PreparedSkill(target, 99, readyRound = 3))
        val cancellation = assertIs<CancelPreparedSkillsChange>(prepared[1])
        assertEquals(preparedEffect.spec, cancellation.spec)
        assertEquals(1, runtime.preparedSkills().size)
        cancellation.apply(runtime)
        assertEquals(emptyList(), runtime.preparedSkills())

        val immediatePreparedName = rule(702).copy(
            raw = rule(702).raw.copy(delayRound = 0),
        )
        val immediate = BattleEffectRegistry.strict(graph(controlIds.map(::rule)))
            .registerControlEffects(BattleEffectStore())
            .execute(immediatePreparedName, context(target))
            .stateChanges
            .first()
        assertIs<ApplyBattleEffectChange>(immediate)
        assertEquals(EffectStartBoundary.IMMEDIATE, immediate.spec.startBoundary)
    }

    @Test
    fun `action permission aggregates controls and action modifiers without leaking targets`() {
        val store = BattleEffectStore()
        store.apply(active(502, target = source))
        store.apply(active(752, target = source))
        store.apply(active(544, target = source, category = EffectCategory.BENEFICIAL))
        store.apply(active(551, target = source, category = EffectCategory.BENEFICIAL))
        store.apply(active(545, target = source, category = EffectCategory.BENEFICIAL))
        store.apply(active(761, target = source, category = EffectCategory.BENEFICIAL))

        assertEquals(
            ActionPermission(
                canAct = true,
                canCastActive = false,
                canNormalAttack = false,
                normalAttackCount = 0,
                grantsPursuitOpportunityPerNormal = false,
                counterattack = true,
                secondaryAttack = true,
                firstAction = true,
            ),
            CompleteSkillEngine(store).permissionFor(source),
        )
        assertEquals(ActionPermission(), CompleteSkillEngine(store).permissionFor(target))
    }

    @Test
    fun `confusion dominates other permissions and berserk exposes random allegiance`() {
        val store = BattleEffectStore()
        store.apply(active(501, target = source))
        store.apply(active(503, target = source))
        store.apply(active(544, target = source, category = EffectCategory.BENEFICIAL))

        assertEquals(
            ActionPermission(
                canAct = false,
                canCastActive = false,
                canNormalAttack = false,
                normalAttackCount = 0,
                grantsPursuitOpportunityPerNormal = false,
                randomAllegiance = true,
            ),
            CompleteSkillEngine(store).permissionFor(source),
        )
    }

    @Test
    fun `taunt and guard redirect using effect origin identities`() {
        val store = BattleEffectStore()
        store.apply(active(505, source = ally, target = target))
        assertEquals(ally, CompleteSkillEngine(store).permissionFor(target).redirectTarget)

        val guardStore = BattleEffectStore()
        guardStore.apply(active(504, source = source, target = ally, category = EffectCategory.BENEFICIAL))
        assertEquals(
            source,
            CompleteSkillEngine(guardStore).permissionFor(target, intendedTarget = ally).redirectTarget,
        )
        assertNull(CompleteSkillEngine(guardStore).permissionFor(target, intendedTarget = target).redirectTarget)
    }

    @Test
    fun `cleanse removes only harmful effects on the selected target`() {
        val store = BattleEffectStore()
        store.apply(active(501, target = target))
        store.apply(active(552, target = target))
        store.apply(active(514, target = target, category = EffectCategory.BENEFICIAL))
        store.apply(active(501, target = ally))

        val execution = execute(513, store)
        val cleanse = assertIs<CleanseEffectsChange>(execution.stateChanges.single())
        assertEquals(513, cleanse.spec.effectId)
        assertEquals(EffectStartBoundary.IMMEDIATE, cleanse.spec.startBoundary)
        val removed = cleanse.apply(store)
        assertEquals(setOf(501, 552), removed.removed.mapTo(mutableSetOf()) { it.effectId })
        assertEquals(listOf(514), store.effectsFor(target).map { it.effectId })
        assertEquals(listOf(501), store.effectsFor(ally).map { it.effectId })

        val preparedCleanse = assertIs<CleanseEffectsChange>(
            execute(713, BattleEffectStore()).stateChanges.single(),
        )
        assertEquals(EffectStartBoundary.AFTER_DELAY, preparedCleanse.spec.startBoundary)
    }

    @Test
    fun `double attack is exactly two normals and each normal opens pursuit`() {
        val store = BattleEffectStore()
        store.apply(active(544, target = source, category = EffectCategory.BENEFICIAL))
        val permission = CompleteSkillEngine(store).permissionFor(source)

        assertEquals(2, permission.normalAttackCount)
        assertTrue(permission.grantsPursuitOpportunityPerNormal)
    }

    @Test
    fun `evade ignore evade and typed action intents remain distinct`() {
        val store = BattleEffectStore()
        store.apply(active(514, target = target, category = EffectCategory.BENEFICIAL))
        assertTrue(CompleteSkillEngine(store).canEvade(target))
        store.apply(active(515, target = source, category = EffectCategory.BENEFICIAL))
        assertFalse(CompleteSkillEngine(store).canEvade(target, attacker = source))

        val expectedKinds = mapOf(
            542 to ActionEffectKind.STRATEGY_LIFE_STEAL,
            545 to ActionEffectKind.SECONDARY_ATTACK,
            546 to ActionEffectKind.EXECUTION_ATTACK,
            551 to ActionEffectKind.COUNTERATTACK,
            571 to ActionEffectKind.IGNORE_TROOP_COUNTER,
            581 to ActionEffectKind.REDUCE_INHERENT_PREPARATION,
            771 to ActionEffectKind.IGNORE_TROOP_COUNTER,
        )
        expectedKinds.forEach { (effectId, kind) ->
            val execution = execute(effectId, BattleEffectStore())
            val intent = assertIs<ActionEffectChange>(execution.stateChanges.single())
            assertEquals(kind, intent.kind)
        }
    }

    private fun execute(
        effectId: Int,
        store: BattleEffectStore,
        selectedTarget: BattleHeroRef = target,
    ): EffectExecution {
        val effectRule = rule(effectId)
        return registry(listOf(effectRule), store).execute(
            effectRule,
            context(selectedTarget),
        )
    }

    private fun registry(
        @Suppress("UNUSED_PARAMETER") rules: List<SkillEffectRule>,
        store: BattleEffectStore = BattleEffectStore(),
    ): BattleEffectRegistry =
        BattleEffectRegistry.strict(graph(controlIds.map(::rule))).registerControlEffects(store)

    private fun graph(rules: List<SkillEffectRule>) = SkillRuleGraph(
        rules = mapOf(
            1 to SkillRule(1, SkillKind.ACTIVE, 3, 100, 0, 5, rules),
        ),
        effectIds = rules.mapTo(mutableSetOf()) { it.effectId },
    )

    private fun rule(effectId: Int): SkillEffectRule {
        val prepared = effectId in setOf(701, 702, 703, 711, 712, 713, 714, 744, 752, 761, 771)
        val beneficial = effectId in setOf(
            504, 506, 511, 513, 514, 515, 542, 544, 545, 546, 551, 571, 581, 594,
            711, 713, 714, 744, 761, 771,
        )
        return SkillEffectRule(
            detailId = 10_000 + effectId,
            effectId = effectId,
            childSkillIds = emptySet(),
            raw = SkillDetailConfig(
                detailId = 10_000 + effectId,
                effectId = effectId,
                attackType = 41,
                targetType = 0,
                selectType = 0,
                intelParam = 0,
                constantParam = 100,
                probabilityInit = 100,
                probabilityMax = 100,
                attackMax = 1,
                availableRounds = 2,
                delayRound = if (prepared) 1 else 0,
                buffType = if (beneficial) 2 else 1,
                effectName = "ignored",
            ),
            configuredValue = ConfiguredBattleEffectValue(
                BattleEffectValueUnit.RATE, 1, 100, 0, 0, 0, 0,
            ),
            effectBuffType = if (beneficial) 2 else 1,
            effectReplaceType = 3,
            skillKind = SkillKind.ACTIVE,
            rawSkillType = 3,
        )
    }

    private fun context(selectedTarget: BattleHeroRef): SkillBattleContext {
        val request = BattleRequest(
            BattleTeam(listOf(hero(1, 0), hero(2, 1))),
            BattleTeam(listOf(hero(3, 0))),
        )
        return SkillBattleContext(
            request = request,
            runtime = SkillRuntimeState(),
            random = FixedBattleRandom(0),
            round = 2,
            source = source,
            rootSkillId = 1,
            currentSkillId = 1,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = SelectedTargetView(request, selectedTarget),
        )
    }

    private fun active(
        effectId: Int,
        source: BattleHeroRef = this.source,
        target: BattleHeroRef,
        category: EffectCategory = EffectCategory.HARMFUL,
    ) = ActiveSkillEffect(
        source, target, 1, 1, SkillKind.ACTIVE, 3, 10_000 + effectId, effectId,
        category, 0, 100, 3, 0, 1, 1, 2, null, false,
    )

    private fun hero(id: Int, position: Int) = BattleHero(
        BattleHeroId(id), position, BattleStats(100, 100, 100, 100, 20, 5), 1_000,
    )

    private fun ref(side: Side, position: Int, id: Int) =
        BattleHeroRef(side, position, BattleHeroId(id))

    private class SelectedTargetView(
        request: BattleRequest,
        private val selected: BattleHeroRef,
    ) : SkillBattleView by SkillBattleView.entrySnapshot(request) {
        override fun heroes(): List<BattleHeroRef> = listOf(selected)
    }

    private companion object {
        val controlIds = (
            (501..506) + (511..515) + listOf(542) + (544..546) +
                listOf(551, 552, 571, 581, 594) + (701..703) + (711..714) +
                listOf(744, 752, 761, 771) + (901..903) + listOf(952)
            ).toSet()
    }
}
