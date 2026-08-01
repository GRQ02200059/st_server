package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleDamageCalculator
import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind
import com.stzb.server.game.battle.EffectCategory
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.DamageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            assertEquals(
                if (effectId % 100 == 6) {
                    TypedBattlePotency.flat(expectedChange.second)
                } else {
                    TypedBattlePotency.percent(expectedChange.second)
                },
                change.potency,
            )
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
    fun `damage origin comes from skill kind and selects only its modifier axis`() {
        val config = BattleConfigRepository.loadDefault()
        val pursuitGraph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200026),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val pursuitRule = pursuitGraph.detail(20002612)
        val pursuitRegistry = registry(BattleEffectStore(), pursuitRule)
        val pursuitContext = context(
            sourceModifiers = listOf(
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.ACTIVE, percent = 500),
            ),
        ).copy(rootSkillId = 200026, currentSkillId = 200026)
        val pursuit = pursuitRegistry.execute(pursuitRule, pursuitContext).stateChanges.single()

        assertIs<TroopDamageChange>(pursuit)
        assertEquals(DamageOrigin.PURSUIT, pursuit.origin)
        assertEquals(
            BattleDamageCalculator.physical(
                source = pursuitContext.request.attacker.heroes.single(),
                target = pursuitContext.request.defender.heroes.single(),
                ratePercent = 200,
                attributeRandomTenths = 30,
                origin = DamageOrigin.PURSUIT,
            ),
            pursuit.amount,
        )

        listOf(
            SkillKind.ACTIVE to (3 to DamageOrigin.ACTIVE),
            SkillKind.PURSUIT to (4 to DamageOrigin.PURSUIT),
            SkillKind.COMMAND to (2 to DamageOrigin.COMMAND),
            SkillKind.PASSIVE to (1 to DamageOrigin.PASSIVE),
        ).forEach { (kind, rawAndOrigin) ->
            val (rawSkillType, expectedOrigin) = rawAndOrigin
            val synthetic = rule(
                effectId = 302,
                constant = 180,
                skillKind = kind,
                rawSkillType = rawSkillType,
            )
            val syntheticContext = context(
                sourceModifiers = listOf(
                    BattleModifier.DamageDealtPercent(
                        origin = expectedOrigin,
                        percent = 40,
                    ),
                    BattleModifier.DamageDealtPercent(
                        origin = if (expectedOrigin == DamageOrigin.ACTIVE) {
                            DamageOrigin.PURSUIT
                        } else {
                            DamageOrigin.ACTIVE
                        },
                        percent = 500,
                    ),
                ),
            )
            val change = registry(BattleEffectStore(), synthetic)
                .execute(synthetic, syntheticContext)
                .stateChanges.single()
            assertIs<TroopDamageChange>(change)
            assertEquals(expectedOrigin, change.origin)
            assertEquals(
                BattleDamageCalculator.strategy(
                    source = syntheticContext.request.attacker.heroes.single(),
                    target = syntheticContext.request.defender.heroes.single(),
                    ratePercent = 180,
                    origin = expectedOrigin,
                ),
                change.amount,
            )
        }
    }

    @Test
    fun `unknown skill kind and raw type never fall back to active damage`() {
        val unsupported = rule(
            effectId = 302,
            constant = 180,
            skillKind = SkillKind.UNKNOWN,
            rawSkillType = 14,
        )

        val strict = assertFailsWith<UnsupportedConfiguredBattleValueException> {
            registry(BattleEffectStore(), unsupported).execute(unsupported, context())
        }
        assertTrue(strict.diagnostic.reason.orEmpty().contains("rawSkillType=14"))
        assertTrue(strict.diagnostic.reason.orEmpty().contains("skillKind=UNKNOWN"))

        val diagnostics = mutableListOf<BattleEffectDiagnostic>()
        val safe = BattleEffectRegistry.safe(graph(listOf(unsupported)), diagnostics::add)
            .registerCoreEffects(BattleEffectStore())
            .execute(unsupported, context())
        assertEquals(EffectExecution.EMPTY, safe)
        assertTrue(diagnostics.single().reason.orEmpty().contains("rawSkillType=14"))
    }

    @Test
    fun `four ongoing damage effects schedule complete typed effects instead of cast damage`() {
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
            assertEquals(DamageOrigin.ACTIVE, scheduled.origin)
            assertEquals(
                setOf(
                    DamageTag.ONGOING,
                    when (scheduled.status) {
                        BattleStatus.SHAKE -> DamageTag.SHAKE
                        BattleStatus.PANIC -> DamageTag.PANIC
                        BattleStatus.BURN -> DamageTag.FIRE
                        BattleStatus.HEX -> DamageTag.HEX
                        else -> error("unexpected ongoing status=${scheduled.status}")
                    },
                ),
                scheduled.tags,
            )
            assertEquals(TypedBattlePotency.rate(120), scheduled.potency)
            assertEquals(2, scheduled.durationRounds)
            assertTrue(result.stateChanges.none { it is TroopDamageChange })
        }
    }

    @Test
    fun `real delayed burn preserves full identity and recalculates every tick from live combatants`() {
        val config = BattleConfigRepository.loadDefault()
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200020),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val realRule = graph.detail(20002012)
        val scheduled = registry(BattleEffectStore(), realRule)
            .execute(
                realRule,
                context().copy(rootSkillId = 200020, currentSkillId = 200020),
            )
            .stateChanges.single()

        assertIs<ScheduledDamageEffectChange>(scheduled)
        assertEquals(
            PersistentEffectSpec(
                source = sourceRef,
                target = targetRef,
                rootSkillId = 200020,
                skillId = 200020,
                skillKind = SkillKind.COMMAND,
                rawSkillType = 2,
                detailId = 20002012,
                effectId = 305,
                category = EffectCategory.HARMFUL,
                conflict = 0,
                replaceType = 0,
                bindFlag = 0,
                maxStacks = 1,
                delayRound = 2,
                delayHit = 0,
                availableRounds = 8,
                availableHit = 0,
                clearPerHit = false,
                startBoundary = EffectStartBoundary.AFTER_DELAY,
                potency = TypedBattlePotency.rate(31),
            ),
            scheduled.spec,
        )
        assertEquals(DamageSchool.STRATEGY, scheduled.school)
        assertEquals(DamageOrigin.COMMAND, scheduled.origin)
        assertEquals(setOf(DamageTag.ONGOING, DamageTag.FIRE), scheduled.tags)
        assertEquals(BattleStatus.BURN, scheduled.status)

        val firstSource = hero(id = 1, position = 2, troops = 1_000, strategy = 120)
        val firstTarget = hero(id = 2, position = 2, troops = 900, strategy = 80)
        val first = scheduled.tick(firstSource, firstTarget)
        assertEquals(
            BattleDamageCalculator.strategy(
                source = firstSource,
                target = firstTarget,
                ratePercent = 32,
                ongoing = true,
                origin = DamageOrigin.COMMAND,
                tags = setOf(DamageTag.ONGOING, DamageTag.FIRE),
            ),
            first.amount,
        )

        val secondSource = hero(id = 1, position = 2, troops = 1_500, strategy = 200).copy(
            modifiers = listOf(
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.COMMAND, percent = 35),
                BattleModifier.DamageDealtPercent(tag = DamageTag.ONGOING, percent = 25),
            ),
        )
        val secondTarget = hero(id = 2, position = 2, troops = 600, strategy = 260).copy(
            modifiers = listOf(
                BattleModifier.DamageTakenPercent(origin = DamageOrigin.COMMAND, percent = -20),
            ),
        )
        val second = scheduled.tick(secondSource, secondTarget)
        assertEquals(
            BattleDamageCalculator.strategy(
                source = secondSource,
                target = secondTarget,
                ratePercent = 34,
                ongoing = true,
                origin = DamageOrigin.COMMAND,
                tags = setOf(DamageTag.ONGOING, DamageTag.FIRE),
            ),
            second.amount,
        )
        assertTrue(first.amount != second.amount)

        val nearlyDefeated = secondTarget.copy(troops = 3)
        assertEquals(3, scheduled.tick(secondSource, nearlyDefeated).amount)
        assertEquals(0, scheduled.tick(secondSource, nearlyDefeated).troopsAfter)
    }

    @Test
    fun `pursuit ongoing damage keeps origin tag and hit lifecycle`() {
        val pursuitBurn = rule(
            effectId = 305,
            constant = 120,
            availableRounds = 0,
            availableHit = 4,
            delayHit = 2,
            clearPerHit = true,
            bindFlag = 7,
            addCountMax = 2,
            hideConflict = 51,
            effectReplaceType = 2,
            skillKind = SkillKind.PURSUIT,
            rawSkillType = 4,
        )
        val scheduled = registry(BattleEffectStore(), pursuitBurn)
            .execute(pursuitBurn, context())
            .stateChanges.single()

        assertIs<ScheduledDamageEffectChange>(scheduled)
        assertEquals(DamageOrigin.PURSUIT, scheduled.origin)
        assertEquals(setOf(DamageTag.ONGOING, DamageTag.FIRE), scheduled.tags)
        assertEquals(0, scheduled.spec.availableRounds)
        assertEquals(4, scheduled.spec.availableHit)
        assertEquals(2, scheduled.spec.delayHit)
        assertEquals(true, scheduled.spec.clearPerHit)
        assertEquals(7, scheduled.spec.bindFlag)
        assertEquals(3, scheduled.spec.maxStacks)
        assertEquals(51, scheduled.spec.conflict)
        assertEquals(2, scheduled.spec.replaceType)
        assertEquals(EffectStartBoundary.AFTER_DELAY, scheduled.spec.startBoundary)
    }

    @Test
    fun `recovery consumes only wounded troops and obeys troop cap`() {
        val registry = registry()
        val result = registry.execute(
            rule(401, constant = 300),
            context(targetTroops = 950, targetMaxTroops = 1_000, woundedTroops = 80),
        )

        val recovery = result.stateChanges.filterIsInstance<RecoverTroopsChange>().single()
        val wounded = result.stateChanges.filterIsInstance<ConsumeWoundedTroopsChange>().single()
        assertEquals(50, recovery.amount)
        assertEquals(50, wounded.amount)
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
    fun `strategy rate range modifiers scale the configured damage rate`() {
        val rule = rule(302, constant = 100)
        val baseline = registry(BattleEffectStore(), rule).execute(rule, context())
            .stateChanges.filterIsInstance<TroopDamageChange>().single().amount
        val ranged = registry(BattleEffectStore(), rule).execute(
            rule,
            context(
                sourceModifiers = listOf(
                    BattleModifier.DamageRateMinimumPercent(50),
                    BattleModifier.DamageRateMaximumPercent(50),
                ),
            ),
        ).stateChanges.filterIsInstance<TroopDamageChange>().single().amount

        assertTrue(ranged < baseline)
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

        assertEquals(TypedBattlePotency.percent(11), calculator.effectValue(fixed, source))
        assertEquals(TypedBattlePotency.percent(22), calculator.effectValue(calculated, source))
    }

    @Test
    fun `damage modifier value uses skill level and precise live strategy`() {
        val config = BattleConfigRepository.loadDefault()
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200023, 200198),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val calculator = DefaultBattleValueCalculator()
        fun source(strategyHundredths: Int) = hero(id = 1, position = 2).copy(
            stats = BattleStats.fromHundredths(
                attack = 14_000,
                defense = 10_000,
                strategy = strategyHundredths,
                speed = 8_000,
                siege = 2_000,
                hitRange = 5,
            ),
        )

        assertEquals(
            TypedBattlePotency.rate(53),
            calculator.effectValue(
                graph.detail(20019801),
                source(23_640),
                skillLevel = 10,
            ),
        )
        assertEquals(
            TypedBattlePotency.rate(56),
            calculator.effectValue(
                graph.detail(20019801),
                source(33_000),
                skillLevel = 7,
            ),
        )
        assertEquals(
            TypedBattlePotency.percent(22),
            calculator.effectValue(
                graph.detail(20002303),
                source(23_640),
                skillLevel = 10,
            ),
        )
    }

    @Test
    fun `official flat attribute details decode hundredth encoded constants`() {
        val config = BattleConfigRepository.loadDefault()
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200233, 200689),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val calculator = DefaultBattleValueCalculator()
        val source = hero(id = 1, position = 2)

        assertEquals(
            TypedBattlePotency.flat(30),
            calculator.effectValue(graph.detail(20023302), source, skillLevel = 10),
        )
        assertEquals(
            TypedBattlePotency.flat(100),
            calculator.effectValue(graph.detail(20068902), source, skillLevel = 10),
        )
        assertEquals(
            TypedBattlePotency.flat(25),
            calculator.effectValue(graph.detail(20068903), source, skillLevel = 10),
        )
        assertEquals(
            TypedBattlePotency.flat(67, 200.0 / 3),
            calculator.effectValue(graph.detail(20068902), source, skillLevel = 4),
        )
        assertEquals(
            TypedBattlePotency.flat(17, 50.0 / 3),
            calculator.effectValue(graph.detail(20068903), source, skillLevel = 4),
        )
    }

    @Test
    fun `real configured values resolve official rates and preserve unsupported raw encodings`() {
        val config = BattleConfigRepository.loadDefault()
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200957, 200023, 295001, 200007),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val calculator = DefaultBattleValueCalculator()
        val source = hero(id = 1, position = 2, defense = 100, strategy = 80)

        assertEquals(
            TypedBattlePotency.rate(300),
            calculator.effectValue(graph.detail(20095701), source),
        )
        assertEquals(
            TypedBattlePotency.rate(25),
            calculator.effectValue(graph.detail(20000712), source),
        )
        assertEquals(
            TypedBattlePotency.percent(8),
            calculator.effectValue(graph.detail(20002301), source),
        )
        listOf(29500101 to 500_000).forEach { (detailId, raw) ->
            val deferred = calculator.effectValue(graph.detail(detailId), source)
            assertIs<TypedBattlePotency.Deferred>(deferred)
            assertEquals(BattleEffectValueUnit.PERCENT, deferred.unit)
            assertEquals(raw, deferred.configuredValue.rawConstant)
            assertTrue(deferred.diagnostic.contains("detail=$detailId"))
            assertTrue(deferred.diagnostic.contains("rawConstant=$raw"))
            assertTrue(deferred.diagnostic.contains("unit=PERCENT"))
            assertTrue(deferred.diagnostic.contains("rawCalcPosition=0"))
        }
    }

    @Test
    fun `strict execution fails deferred configured values while safe execution logs and skips`() {
        val config = BattleConfigRepository.loadDefault()
        val sourceGraph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(295001),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val realRule = sourceGraph.detail(29500101)
        val rule = rule(effectId = 101, constant = 500_000, rawCalcPosition = 0).copy(
            configuredValue = realRule.configuredValue,
        )
        val graph = SkillRuleGraph(
            rules = mapOf(
                295001 to SkillRule(
                    skillId = 295001,
                    kind = SkillKind.ACTIVE,
                    rawSkillType = 3,
                    probability = 100,
                    prepareRounds = 0,
                    hitRange = 5,
                    details = listOf(rule),
                ),
            ),
            effectIds = coreEffectIds,
        )
        val context = context().copy(rootSkillId = 295001, currentSkillId = 295001)

        val strictError = assertFailsWith<UnsupportedConfiguredBattleValueException> {
            BattleEffectRegistry.strict(graph)
                .registerCoreEffects(BattleEffectStore())
                .execute(rule, context)
        }
        assertEquals(EffectFailureCode.UNSUPPORTED_CONFIGURED_VALUE, strictError.diagnostic.code)
        assertTrue(strictError.diagnostic.message().contains("rawConstant=500000"))

        val diagnostics = mutableListOf<BattleEffectDiagnostic>()
        val safe = BattleEffectRegistry.safe(graph, diagnostics::add)
            .registerCoreEffects(BattleEffectStore())
            .execute(rule, context)
        assertEquals(EffectExecution.EMPTY, safe)
        assertEquals(EffectFailureCode.UNSUPPORTED_CONFIGURED_VALUE, diagnostics.single().code)
    }

    @Test
    fun `stat changes retain percent potency instead of flattening it into an amount`() {
        val rule = rule(effectId = 201, constant = 1_500)

        val change = registry(BattleEffectStore(), rule)
            .execute(rule, context())
            .stateChanges.single()

        assertIs<BattleStatChange>(change)
        assertEquals(TypedBattlePotency.percent(-15), change.potency)
    }

    @Test
    fun `tianzi strategy scaled stat percent uses its configured direct scale`() {
        val config = BattleConfigRepository.loadDefault()
        val graph = SkillRuleCatalog.build(
            SkillScope(
                fiveStarInitialSkillIds = setOf(200270),
                learnableSaSkillIds = emptySet(),
            ),
            config,
        )
        val rule = graph.detail(21227003)

        val change = BattleEffectRegistry.strict(graph)
            .registerCoreEffects(BattleEffectStore())
            .execute(
                rule,
                context().copy(rootSkillId = 200270, currentSkillId = 212270),
                preselectedTargets = listOf(targetRef),
            )
            .stateChanges.single()

        assertIs<BattleStatChange>(change)
        assertEquals(TypedBattlePotency.percent(-8), change.potency)
    }

    @Test
    fun `persistent spec snapshots exact identity lifecycle and converts without repository access`() {
        val rule = rule(
            effectId = 207,
            constant = 100,
            availableRounds = 3,
            availableHit = 2,
            delayRound = 1,
            delayHit = 4,
            clearPerHit = true,
            bindFlag = 7,
            addCountMax = 2,
            hideConflict = 51,
            effectBuffType = 1,
            effectReplaceType = 2,
        )
        val context = context().copy(rootSkillId = 99, currentSkillId = 1)
        val change = registry(BattleEffectStore(), rule).execute(rule, context).stateChanges.single()

        assertIs<ApplyBattleEffectChange>(change)
        assertEquals(
            PersistentEffectSpec(
                source = sourceRef,
                target = targetRef,
                rootSkillId = 99,
                skillId = 1,
                skillKind = SkillKind.ACTIVE,
                rawSkillType = 3,
                detailId = 10_207,
                effectId = 207,
                category = EffectCategory.HARMFUL,
                conflict = 51,
                replaceType = 2,
                bindFlag = 7,
                maxStacks = 3,
                delayRound = 1,
                delayHit = 4,
                availableRounds = 3,
                availableHit = 2,
                clearPerHit = true,
                startBoundary = EffectStartBoundary.AFTER_DELAY,
                potency = TypedBattlePotency.flat(100),
            ),
            change.spec,
        )

        val active = change.toActiveSkillEffect()
        assertEquals(sourceRef, active.source)
        assertEquals(targetRef, active.target)
        assertEquals(99, active.rootSkillId)
        assertEquals(1, active.skillId)
        assertEquals(SkillKind.ACTIVE, active.skillKind)
        assertEquals(3, active.sourceSkillType)
        assertEquals(10_207, active.detailId)
        assertEquals(207, active.effectId)
        assertEquals(EffectCategory.HARMFUL, active.category)
        assertEquals(51, active.conflict)
        assertEquals(100, active.strength)
        assertEquals(2, active.replaceType)
        assertEquals(7, active.bindFlag)
        assertEquals(3, active.maxStacks)
        assertEquals(3, active.remainingRounds)
        assertEquals(2, active.remainingHits)
        assertEquals(true, active.clearPerHit)
    }

    @Test
    fun `zero configured duration remains an explicit no duration persistent spec`() {
        val rule = rule(effectId = 207, constant = 100, availableRounds = 0)

        val change = registry(BattleEffectStore(), rule).execute(rule, context()).stateChanges.single()

        assertIs<ApplyBattleEffectChange>(change)
        assertEquals(0, change.spec.availableRounds)
        assertEquals(null, change.toActiveSkillEffectOrNull())
    }

    @Test
    fun `scheduled recovery caps uncapped potency against live state on every tick without double consumption`() {
        val effectStore = BattleEffectStore()
        val rule = rule(effectId = 402, constant = 100, availableRounds = 2)
        val scheduled = registry(effectStore, rule).execute(
            rule,
            context(targetTroops = 950, targetMaxTroops = 1_000, woundedTroops = 80),
        ).stateChanges.single()

        assertIs<ScheduledRecoveryEffectChange>(scheduled)
        assertEquals(TypedBattlePotency.flat(67), scheduled.potency)

        val first = scheduled.tick(
            liveState = SkillBattleHeroState(
                stats = BattleStats.ZERO,
                troops = 950,
                maxTroops = 1_000,
                statuses = emptySet(),
                morale = 100,
                attackRange = 1,
                woundedTroops = 80,
            ),
            effectStore = effectStore,
        )
        assertEquals(
            listOf(
                RecoverTroopsChange(
                    source = sourceRef,
                    target = targetRef,
                    amount = 50,
                    troopsAfter = 1_000,
                    skillId = 1,
                    effectId = 402,
                ),
                ConsumeWoundedTroopsChange(
                    target = targetRef,
                    amount = 50,
                    woundedAfter = 30,
                    skillId = 1,
                    effectId = 402,
                ),
            ),
            first,
        )
        val second = scheduled.tick(
            liveState = SkillBattleHeroState(
                stats = BattleStats.ZERO,
                troops = 900,
                maxTroops = 1_000,
                statuses = emptySet(),
                morale = 100,
                attackRange = 1,
                woundedTroops = 30,
            ),
            effectStore = effectStore,
        )
        assertEquals(30, second.filterIsInstance<RecoverTroopsChange>().single().amount)
        assertEquals(30, second.filterIsInstance<ConsumeWoundedTroopsChange>().single().amount)

        effectStore.apply(activeEffect(effectId = 207))
        assertEquals(
            listOf(
                EffectBlockedChange(
                    source = sourceRef,
                    target = targetRef,
                    skillId = 1,
                    effectId = 402,
                    blockingEffectId = 207,
                ),
            ),
            scheduled.tick(
                liveState = SkillBattleHeroState(
                    stats = BattleStats.ZERO,
                    troops = 900,
                    maxTroops = 1_000,
                    statuses = emptySet(),
                    morale = 100,
                    attackRange = 1,
                    woundedTroops = 30,
                ),
                effectStore = effectStore,
            ),
        )
    }

    private fun registry(
        effectStore: BattleEffectStore = BattleEffectStore(),
        vararg rules: SkillEffectRule,
    ): BattleEffectRegistry =
        BattleEffectRegistry.strict(
            graph(if (rules.isEmpty()) coreEffectIds.map(::rule) else rules.toList()),
        ).registerCoreEffects(effectStore)

    private fun graph(rules: List<SkillEffectRule> = coreEffectIds.map(::rule)): SkillRuleGraph =
        SkillRuleGraph(
            rules = mapOf(
                1 to SkillRule(
                    skillId = 1,
                    kind = SkillKind.ACTIVE,
                    rawSkillType = 3,
                    probability = 100,
                    prepareRounds = 0,
                    hitRange = 5,
                    details = rules,
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
        availableRounds: Int = 2,
        availableHit: Int = 0,
        delayRound: Int = 0,
        delayHit: Int = 0,
        clearPerHit: Boolean = false,
        bindFlag: Int = 0,
        addCountMax: Int = 0,
        hideConflict: Int = 0,
        effectBuffType: Int = if (effectId in 101..106) 2 else 1,
        effectReplaceType: Int = 0,
        rawCalcPosition: Int = if (effectId in 101..105 || effectId in 201..205) 311 else 0,
        skillKind: SkillKind = SkillKind.ACTIVE,
        rawSkillType: Int = 3,
    ): SkillEffectRule =
        SkillEffectRule(
            detailId = 10_000 + effectId,
            effectId = effectId,
            childSkillIds = emptySet(),
            raw = SkillDetailConfig(
                detailId = 10_000 + effectId,
                effectId = effectId,
                calcPos = rawCalcPosition,
                attackType = 41,
                targetType = 0,
                selectType = 0,
                availableHit = availableHit,
                intelParam = intel,
                constantParam = constant,
                probabilityInit = 100,
                probabilityMax = 100,
                attackMax = 1,
                bindFlag = bindFlag,
                addCountMax = addCountMax,
                delayRound = delayRound,
                delayHit = delayHit,
                availableRounds = availableRounds,
                clearPerHit = clearPerHit,
                hideConflict = hideConflict,
                calculationTypes = calculationTypes,
                effectName = "ignored fixture description",
            ),
            skillHitRange = 5,
            configuredValue = com.stzb.server.game.battle.ConfiguredBattleEffectValue(
                unit = when (effectId) {
                    in 101..105, in 201..205 -> BattleEffectValueUnit.PERCENT
                    in 301..307, in 321..355, 401, 402, in 521..534 -> BattleEffectValueUnit.RATE
                    else -> BattleEffectValueUnit.FLAT
                },
                rawValueType = when (effectId) {
                    in 101..105, in 201..205 -> 2
                    in 301..307, in 321..355, 401, 402, in 521..534 -> 1
                    else -> 0
                },
                rawConstant = constant,
                rawCoefficient = intel,
                rawAttributeType = 0,
                rawCalcPosition = rawCalcPosition,
                rawCalcParameter = 0,
            ),
            effectBuffType = effectBuffType,
            effectReplaceType = effectReplaceType,
            skillKind = skillKind,
            rawSkillType = rawSkillType,
        )

    private fun SkillRuleGraph.detail(detailId: Int): SkillEffectRule =
        details.single { it.detailId == detailId }

    private fun context(
        targetTroops: Int = 700,
        targetMaxTroops: Int = 1_000,
        woundedTroops: Int = 300,
        targetDefense: Int = 100,
        targetStrategy: Int = 100,
        sourceModifiers: List<BattleModifier> = emptyList(),
    ): SkillBattleContext {
        val source = hero(id = 1, position = 2, strategy = 120).copy(modifiers = sourceModifiers)
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

    private inner class TestBattleView(
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
        override fun currentTarget(source: BattleHeroRef): BattleHeroRef? =
            targetRef.takeIf { source == sourceRef }
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
