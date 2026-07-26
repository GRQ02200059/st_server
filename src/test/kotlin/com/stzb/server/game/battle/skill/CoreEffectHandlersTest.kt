package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleDamageCalculator
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.DamageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CoreEffectHandlersTest {
    private val sourceRef = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(1))
    private val targetRef = BattleHeroRef(Side.DEFENDER, 2, BattleHeroId(2))

    @Test
    fun `core effect registry owns the exact forty effect contract`() {
        assertEquals(40, coreEffectIds.size)
        assertEquals(coreEffectIds, CoreEffectHandlers.effectIds)
        assertEquals(coreEffectIds, registry().implementedEffectIds())
    }

    @Test
    fun `attribute effects preserve all six stat identities and signs`() {
        val registry = registry()
        val expected = listOf(
            101 to (BattleStatChange.Kind.ATTACK to 10),
            102 to (BattleStatChange.Kind.DEFENSE to 10),
            103 to (BattleStatChange.Kind.STRATEGY to 10),
            104 to (BattleStatChange.Kind.SPEED to 10),
            105 to (BattleStatChange.Kind.SIEGE to 10),
            106 to (BattleStatChange.Kind.ATTACK_RANGE to 1),
            201 to (BattleStatChange.Kind.ATTACK to -10),
            202 to (BattleStatChange.Kind.DEFENSE to -10),
            203 to (BattleStatChange.Kind.STRATEGY to -10),
            204 to (BattleStatChange.Kind.SPEED to -10),
            205 to (BattleStatChange.Kind.SIEGE to -10),
            206 to (BattleStatChange.Kind.ATTACK_RANGE to -1),
        )

        expected.forEach { (effectId, expectedChange) ->
            val constant = if (effectId % 100 == 6) 1 else 1_000
            val change = registry.execute(rule(effectId, constant = constant), context())
                .stateChanges.single()
            assertIs<BattleStatChange>(change)
            assertEquals(targetRef, change.target)
            assertEquals(expectedChange.first, change.kind)
            assertEquals(expectedChange.second, change.amount)
            assertEquals(2, change.durationRounds)
        }
    }

    @Test
    fun `damage effects use live source and target stats and retain classification`() {
        val registry = registry()
        val physical = registry.execute(rule(301, constant = 180), context()).stateChanges.single()
        val strategy = registry.execute(rule(302, constant = 180), context()).stateChanges.single()
        val fire = registry.execute(rule(307, constant = 180), context()).stateChanges.single()

        assertIs<TroopDamageChange>(physical)
        assertEquals(DamageSchool.PHYSICAL, physical.school)
        assertEquals(DamageOrigin.ACTIVE, physical.origin)
        assertEquals(emptySet(), physical.tags)
        assertEquals(targetRef, physical.target)
        assertTrue(physical.amount > 0)
        assertIs<TroopDamageChange>(strategy)
        assertEquals(DamageSchool.STRATEGY, strategy.school)
        assertEquals(DamageOrigin.ACTIVE, strategy.origin)
        assertEquals(emptySet(), strategy.tags)
        assertIs<TroopDamageChange>(fire)
        assertEquals(DamageSchool.STRATEGY, fire.school)
        assertEquals(DamageOrigin.ACTIVE, fire.origin)
        assertEquals(setOf(DamageTag.FIRE), fire.tags)

        val lowDefense = context(targetDefense = 20)
        val highDefense = context(targetDefense = 300)
        val lowStrategy = context(targetStrategy = 20)
        val highStrategy = context(targetStrategy = 300)
        val attackLowDefense = registry.execute(rule(301, constant = 180), lowDefense)
            .stateChanges.single() as TroopDamageChange
        val attackHighDefense = registry.execute(rule(301, constant = 180), highDefense)
            .stateChanges.single() as TroopDamageChange
        val strategyLowDefense = registry.execute(rule(302, constant = 180), lowStrategy)
            .stateChanges.single() as TroopDamageChange
        val strategyHighDefense = registry.execute(rule(302, constant = 180), highStrategy)
            .stateChanges.single() as TroopDamageChange
        assertTrue(attackLowDefense.amount > attackHighDefense.amount)
        assertTrue(strategyLowDefense.amount > strategyHighDefense.amount)
    }

    @Test
    fun `four ongoing damage effects schedule typed effects instead of dealing immediately`() {
        val registry = registry()
        val expected = mapOf(
            303 to (BattleStatus.SHAKE to DamageSchool.PHYSICAL),
            304 to (BattleStatus.PANIC to DamageSchool.STRATEGY),
            305 to (BattleStatus.BURN to DamageSchool.STRATEGY),
            306 to (BattleStatus.HEX to DamageSchool.STRATEGY),
        )

        expected.forEach { (effectId, type) ->
            val result = registry.execute(rule(effectId, constant = 120), context())
            val scheduled = result.stateChanges.single()
            assertIs<ScheduledDamageEffectChange>(scheduled)
            assertEquals(type.first, scheduled.status)
            assertEquals(type.second, scheduled.school)
            assertEquals(DamageOrigin.ONGOING, scheduled.origin)
            assertEquals(
                if (scheduled.status == BattleStatus.BURN) setOf(DamageTag.ONGOING, DamageTag.FIRE)
                else setOf(DamageTag.ONGOING),
                scheduled.tags,
            )
            assertTrue(scheduled.damagePerTick > 0)
            assertEquals(2, scheduled.durationRounds)
            assertTrue(result.stateChanges.none { it is TroopDamageChange })
        }
    }

    @Test
    fun `recovery consumes only wounded troops and obeys troop cap`() {
        val registry = registry()
        val result = registry.execute(
            rule(401, constant = 300),
            context(targetTroops = 950, targetMaxTroops = 1_000, woundedTroops = 80),
        )

        val recovery = result.stateChanges.filterIsInstance<TroopRecoveryChange>().single()
        val wounded = result.stateChanges.filterIsInstance<WoundedPoolChange>().single()
        assertEquals(50, recovery.amount)
        assertEquals(-50, wounded.delta)
        assertEquals(1_000, recovery.troopsAfter)
    }

    @Test
    fun `rest schedules recovery while unrecoverable effect blocks both recovery families`() {
        val effectStore = BattleEffectStore()
        val registry = registry(effectStore)
        val scheduled = registry.execute(rule(402, constant = 100), context())
            .stateChanges.single()
        assertIs<ScheduledRecoveryEffectChange>(scheduled)
        assertEquals(2, scheduled.durationRounds)

        effectStore.apply(activeEffect(effectId = 207))
        listOf(401, 402).forEach { effectId ->
            val result = registry.execute(rule(effectId, constant = 100), context())
            assertTrue(result.events.isEmpty())
            val blocked = result.stateChanges.single()
            assertIs<EffectBlockedChange>(blocked)
            assertEquals(207, blocked.blockingEffectId)
            assertEquals(effectId, blocked.effectId)
        }
    }

    @Test
    fun `damage modifiers keep normal active pursuit physical and strategy categories separate`() {
        val registry = registry()
        val expected = mapOf(
            321 to Triple(DamageModifierChange.Direction.DEALT, DamageOrigin.NORMAL, 20),
            322 to Triple(DamageModifierChange.Direction.DEALT, DamageOrigin.ACTIVE, 20),
            325 to Triple(DamageModifierChange.Direction.DEALT, DamageOrigin.PURSUIT, 20),
            331 to Triple(DamageModifierChange.Direction.DEALT, DamageOrigin.NORMAL, -20),
            332 to Triple(DamageModifierChange.Direction.DEALT, DamageOrigin.ACTIVE, -20),
            335 to Triple(DamageModifierChange.Direction.DEALT, DamageOrigin.PURSUIT, -20),
            342 to Triple(DamageModifierChange.Direction.TAKEN, DamageOrigin.ACTIVE, 20),
            351 to Triple(DamageModifierChange.Direction.TAKEN, DamageOrigin.NORMAL, -20),
            352 to Triple(DamageModifierChange.Direction.TAKEN, DamageOrigin.ACTIVE, -20),
            355 to Triple(DamageModifierChange.Direction.TAKEN, DamageOrigin.PURSUIT, -20),
        )
        expected.forEach { (effectId, values) ->
            val change = registry.execute(rule(effectId, constant = 20), context())
                .stateChanges.single()
            assertIs<DamageModifierChange>(change)
            assertEquals(values.first, change.direction)
            assertEquals(values.second, change.origin)
            assertEquals(null, change.school)
            assertEquals(null, change.tag)
            assertEquals(values.third, change.percent)
        }

        val schools = mapOf(
            521 to Triple(DamageModifierChange.Direction.TAKEN, DamageSchool.PHYSICAL, 20),
            522 to Triple(DamageModifierChange.Direction.TAKEN, DamageSchool.PHYSICAL, -20),
            523 to Triple(DamageModifierChange.Direction.TAKEN, DamageSchool.STRATEGY, 20),
            524 to Triple(DamageModifierChange.Direction.TAKEN, DamageSchool.STRATEGY, -20),
            531 to Triple(DamageModifierChange.Direction.DEALT, DamageSchool.PHYSICAL, 20),
            532 to Triple(DamageModifierChange.Direction.DEALT, DamageSchool.PHYSICAL, -20),
            533 to Triple(DamageModifierChange.Direction.DEALT, DamageSchool.STRATEGY, 20),
            534 to Triple(DamageModifierChange.Direction.DEALT, DamageSchool.STRATEGY, -20),
        )
        schools.forEach { (effectId, values) ->
            val change = registry.execute(rule(effectId, constant = 20), context())
                .stateChanges.single() as DamageModifierChange
            assertEquals(values.first, change.direction)
            assertEquals(values.second, change.school)
            assertEquals(null, change.origin)
            assertEquals(null, change.tag)
            assertEquals(values.third, change.percent)
        }
    }

    @Test
    fun `pursuit physical damage applies pursuit and physical modifiers only`() {
        val target = hero(id = 2, position = 2, troops = 10_000, maxTroops = 10_000)
        val base = hero(id = 1, position = 2, troops = 10_000, maxTroops = 10_000)
        val source = base.copy(
            modifiers = listOf(
                BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 10),
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.PURSUIT, percent = 20),
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.ACTIVE, percent = 80),
                BattleModifier.DamageDealtPercent(school = DamageSchool.STRATEGY, percent = 90),
                BattleModifier.DamageDealtPercent(tag = DamageTag.FIRE, percent = 70),
            ),
        )

        assertEquals(
            BattleDamageCalculator.physical(
                source = base.copy(
                    modifiers = listOf(
                        BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 10),
                        BattleModifier.DamageDealtPercent(origin = DamageOrigin.PURSUIT, percent = 20),
                    ),
                ),
                target = target,
                origin = DamageOrigin.PURSUIT,
            ),
            BattleDamageCalculator.physical(
                source = source,
                target = target,
                origin = DamageOrigin.PURSUIT,
            ),
        )
    }

    @Test
    fun `active fire damage applies active strategy and fire modifiers only`() {
        val target = hero(id = 2, position = 2, troops = 10_000, maxTroops = 10_000)
        val base = hero(id = 1, position = 2, troops = 10_000, maxTroops = 10_000)
        val source = base.copy(
            modifiers = listOf(
                BattleModifier.DamageDealtPercent(school = DamageSchool.STRATEGY, percent = 10),
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.ACTIVE, percent = 20),
                BattleModifier.DamageDealtPercent(tag = DamageTag.FIRE, percent = 30),
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.PURSUIT, percent = 80),
                BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 90),
                BattleModifier.DamageDealtPercent(tag = DamageTag.ONGOING, percent = 70),
            ),
        )

        assertEquals(
            BattleDamageCalculator.strategy(
                source = base.copy(
                    modifiers = listOf(
                        BattleModifier.DamageDealtPercent(school = DamageSchool.STRATEGY, percent = 10),
                        BattleModifier.DamageDealtPercent(origin = DamageOrigin.ACTIVE, percent = 20),
                        BattleModifier.DamageDealtPercent(tag = DamageTag.FIRE, percent = 30),
                    ),
                ),
                target = target,
                ratePercent = 100,
                origin = DamageOrigin.ACTIVE,
                tags = setOf(DamageTag.FIRE),
            ),
            BattleDamageCalculator.strategy(
                source = source,
                target = target,
                ratePercent = 100,
                origin = DamageOrigin.ACTIVE,
                tags = setOf(DamageTag.FIRE),
            ),
        )
    }

    @Test
    fun `physical curve leaves troop base unmodified and applies modifier once to attack terms`() {
        val target = hero(id = 2, position = 2, troops = 10_000, maxTroops = 10_000)
        val source = hero(id = 1, position = 2, troops = 10_000, maxTroops = 10_000).copy(
            modifiers = listOf(
                BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 30),
            ),
        )

        assertEquals(
            643,
            BattleDamageCalculator.physical(
                source = source,
                target = target,
                ratePercent = 100,
                attributeRandomTenths = 35,
            ),
        )
    }

    @Test
    fun `value calculation is deterministic and uses constant intelligence and calculation types`() {
        val calculator = DefaultBattleValueCalculator()
        val source = hero(
            id = 1,
            position = 2,
            strategy = 120,
            advanceLevel = 2,
        )
        val fixed = rule(101, constant = 1_000, intel = 2_200)
        val calculated = rule(
            101,
            constant = 1_000,
            intel = 2_200,
            calculationTypes = listOf(1, 1, 2, 3),
        )

        assertEquals(11, calculator.effectValue(fixed, source))
        assertEquals(22, calculator.effectValue(calculated, source))
        assertEquals(
            calculator.effectValue(calculated, source),
            calculator.effectValue(calculated, source),
        )
    }

    private fun registry(effectStore: BattleEffectStore = BattleEffectStore()): BattleEffectRegistry =
        BattleEffectRegistry.strict(graph()).registerCoreEffects(effectStore)

    private fun graph(): SkillRuleGraph =
        SkillRuleGraph(
            rules = mapOf(
                1 to SkillRule(
                    skillId = 1,
                    kind = SkillKind.ACTIVE,
                    rawSkillType = 3,
                    probability = 100,
                    prepareRounds = 0,
                    hitRange = 5,
                    details = coreEffectIds.map(::rule),
                ),
            ),
            effectIds = coreEffectIds,
        )

    private fun rule(
        effectId: Int,
        constant: Int = when (effectId) {
            106, 206 -> 1
            101, 102, 103, 104, 105, 201, 202, 203, 204, 205 -> 1_000
            else -> 100
        },
        intel: Int = 0,
        calculationTypes: List<Int> = emptyList(),
    ): SkillEffectRule =
        SkillEffectRule(
            detailId = 10_000 + effectId,
            effectId = effectId,
            childSkillIds = emptySet(),
            raw = SkillDetailConfig(
                detailId = 10_000 + effectId,
                effectId = effectId,
                attackType = 41,
                targetType = 0,
                selectType = 0,
                intelParam = intel,
                constantParam = constant,
                probabilityInit = 100,
                probabilityMax = 100,
                attackMax = 1,
                availableRounds = 2,
                calculationTypes = calculationTypes,
                effectName = "ignored fixture description",
            ),
            skillHitRange = 5,
        )

    private fun context(
        targetTroops: Int = 700,
        targetMaxTroops: Int = 1_000,
        woundedTroops: Int = 300,
        targetDefense: Int = 100,
        targetStrategy: Int = 100,
    ): SkillBattleContext {
        val source = hero(id = 1, position = 2, strategy = 120)
        val target = hero(
            id = 2,
            position = 2,
            troops = targetTroops,
            maxTroops = targetMaxTroops,
            defense = targetDefense,
            strategy = targetStrategy,
        )
        val request = BattleRequest(BattleTeam(listOf(source)), BattleTeam(listOf(target)))
        return SkillBattleContext(
            request = request,
            runtime = SkillRuntimeState(),
            random = FixedBattleRandom(0),
            round = 3,
            source = sourceRef,
            rootSkillId = 1,
            currentSkillId = 1,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = TestBattleView(
                mapOf(
                    sourceRef to source.toState(woundedTroops = 0),
                    targetRef to target.toState(woundedTroops = woundedTroops),
                ),
            ),
        )
    }

    private fun activeEffect(effectId: Int) =
        com.stzb.server.game.battle.ActiveSkillEffect(
            source = sourceRef,
            target = targetRef,
            rootSkillId = 1,
            skillId = 1,
            skillKind = SkillKind.ACTIVE,
            sourceSkillType = 3,
            detailId = 10_000 + effectId,
            effectId = effectId,
            category = com.stzb.server.game.battle.EffectCategory.HARMFUL,
            conflict = 0,
            strength = 100,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            stacks = 1,
            remainingRounds = 2,
            remainingHits = null,
            clearPerHit = false,
        )

    private fun hero(
        id: Int,
        position: Int,
        troops: Int = 1_000,
        maxTroops: Int = troops,
        defense: Int = 100,
        strategy: Int = 100,
        advanceLevel: Int = 0,
    ) = BattleHero(
        id = BattleHeroId(id),
        position = position,
        stats = BattleStats(attack = 140, defense, strategy, speed = 80, siege = 20, hitRange = 5),
        troops = troops,
        maxTroops = maxTroops,
        level = 50,
        advanceLevel = advanceLevel,
    )

    private fun BattleHero.toState(woundedTroops: Int) =
        SkillBattleHeroState(
            stats = stats,
            troops = troops,
            maxTroops = maxTroops,
            statuses = activeStatuses,
            morale = morale,
            attackRange = stats.hitRange,
            woundedTroops = woundedTroops,
        )

    private class TestBattleView(
        private val states: Map<BattleHeroRef, SkillBattleHeroState>,
    ) : SkillBattleView {
        override val capabilities = setOf(
            SkillBattleViewCapability.HERO_ROSTER,
            SkillBattleViewCapability.ENTRY_STATE,
            SkillBattleViewCapability.LIVE_STATE,
        )

        override fun heroes() = states.keys.toList()
        override fun entryState(ref: BattleHeroRef) = states[ref]
        override fun state(ref: BattleHeroRef) = states[ref]
        override fun metadata(ref: BattleHeroRef): SkillBattleHeroMetadata? = null
        override fun accumulatedDamageDealt(ref: BattleHeroRef) = 0
        override fun currentMorale(ref: BattleHeroRef) = states[ref]?.morale
        override fun currentAttackRange(ref: BattleHeroRef) = states[ref]?.attackRange
        override fun linkedTarget(source: BattleHeroRef): BattleHeroRef? = null
        override fun currentTarget(source: BattleHeroRef): BattleHeroRef? = null
        override fun previousTarget(source: BattleHeroRef): BattleHeroRef? = null
        override fun matchesStateFilter(
            filter: SkillTargetStateFilter,
            source: BattleHeroRef,
            target: BattleHeroRef,
        ) = true
    }

    private companion object {
        val coreEffectIds = setOf(
            101, 102, 103, 104, 105, 106,
            201, 202, 203, 204, 205, 206, 207,
            301, 302, 303, 304, 305, 306, 307,
            321, 322, 325, 331, 332, 335, 342, 351, 352, 355,
            401, 402,
            521, 522, 523, 524,
            531, 532, 533, 534,
        )
    }
}
