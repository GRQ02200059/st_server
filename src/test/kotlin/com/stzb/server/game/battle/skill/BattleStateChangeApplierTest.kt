package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleStat
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.DamageTag
import com.stzb.server.game.battle.EffectCategory
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BattleStateChangeApplierTest {
    @Test
    fun `effect store preflight matches accepted and rejected apply outcomes`() {
        val store = BattleEffectStore()
        val accepted = spec(304).copy(conflict = 304).toActiveSkillEffect()
        val rejected = spec(304).copy(
            source = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(100018)),
            conflict = 304,
        ).toActiveSkillEffect()

        assertTrue(store.canApply(accepted))
        assertTrue(store.apply(accepted).outcome != EffectApplyOutcome.REJECTED)
        assertFalse(store.canApply(rejected))
        assertEquals(EffectApplyOutcome.REJECTED, store.apply(rejected).outcome)
    }

    private val source = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(1))
    private val target = BattleHeroRef(Side.DEFENDER, 2, BattleHeroId(2))

    @Test
    fun `damage recovery and wounded changes use live caps and emit counter hooks`() {
        val fixture = fixture(targetTroops = 30, targetWounded = 20)

        val damage = fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = target,
                    amount = 50,
                    troopsAfter = 0,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.COMMAND,
                    tags = emptySet(),
                    skillId = 10,
                    effectId = 301,
                ),
            ),
            round = 0,
        )
        assertEquals(0, fixture.state.view.state(target)?.troops)
        assertEquals(30, damage.outputs.filterIsInstance<BattleStateOutput.DamageDealt>().single().amount)
        assertEquals(30, damage.outputs.filterIsInstance<BattleStateOutput.HurtReceived>().single().amount)

        fixture.applier.apply(
            listOf(
                RecoverTroopsChange(source, target, 99, 99, 10, 401),
                ConsumeWoundedTroopsChange(target, 99, 0, 10, 401),
            ),
            round = 0,
        )
        assertEquals(48, fixture.state.view.state(target)?.troops)
        assertEquals(0, fixture.state.view.state(target)?.woundedTroops)
    }

    @Test
    fun `typed stat modifiers recalculate from entry stats and expire after exact rounds`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.ATTACK,
                    TypedBattlePotency.Resolved(BattleEffectValueUnit.PERCENT, 10),
                    durationRounds = 2,
                    skillId = 20,
                    effectId = 101,
                ),
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.ATTACK,
                    TypedBattlePotency.flat(5),
                    durationRounds = 2,
                    skillId = 21,
                    effectId = 101,
                ),
            ),
            round = 0,
        )

        assertEquals(115, fixture.state.view.state(target)?.stats?.attack)
        fixture.applier.onRoundEnd(1)
        assertEquals(115, fixture.state.view.state(target)?.stats?.attack)
        fixture.applier.onRoundEnd(2)
        assertEquals(100, fixture.state.view.state(target)?.stats?.attack)
    }

    @Test
    fun `stat output separates configured strength from actual target delta`() {
        val fixture = fixture()

        val result = fixture.applier.apply(
            listOf(
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.ATTACK,
                    TypedBattlePotency.Resolved(BattleEffectValueUnit.PERCENT, -22),
                    durationRounds = 8,
                    skillId = 200023,
                    effectId = 201,
                ),
            ),
            round = 0,
        )

        assertEquals(
            BattleStateOutput.StatChanged(
                change = BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.ATTACK,
                    TypedBattlePotency.Resolved(BattleEffectValueUnit.PERCENT, -22),
                    durationRounds = 8,
                    skillId = 200023,
                    effectId = 201,
                ),
                strength = 22,
                delta = -22,
                valueAfter = 78,
            ),
            result.outputs.last(),
        )
    }

    @Test
    fun `percent stat output retains exact decimal delta and resulting value`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    BattleHero(
                        id = source.heroId,
                        position = source.position,
                        stats = BattleStats(100, 100, 100, 100, 10, 3),
                        troops = 1_000,
                    ),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    BattleHero(
                        id = target.heroId,
                        position = target.position,
                        stats = BattleStats.fromHundredths(13150, 10000, 10000, 10000, 1000, 3),
                        troops = 1_000,
                    ),
                ),
            ),
        )
        val state = SkillBattleState(request, SkillRuntimeState())
        val result = BattleStateChangeApplier(state).apply(
            listOf(
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.ATTACK,
                    TypedBattlePotency.Resolved(BattleEffectValueUnit.PERCENT, 10),
                    durationRounds = 8,
                    skillId = 200023,
                    effectId = 201,
                ),
            ),
            round = 0,
        )

        val output = result.outputs.filterIsInstance<BattleStateOutput.StatChanged>().single()
        assertEquals(13.15, output.deltaExact, 0.001)
        assertEquals(144.65, output.valueAfterExact, 0.001)
        assertEquals(144.65, state.view.state(target)?.stats?.precise(com.stzb.server.game.battle.BattleStat.ATTACK))
    }

    @Test
    fun `flat stat output retains exact skill level interpolation`() {
        val fixture = fixture()

        val result = fixture.applier.apply(
            listOf(
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.DEFENSE,
                    TypedBattlePotency.flat(67, 200.0 / 3),
                    durationRounds = 10,
                    skillId = 200689,
                    effectId = 102,
                ),
            ),
            round = 0,
        )

        val output = result.outputs.filterIsInstance<BattleStateOutput.StatChanged>().single()
        assertEquals(66.67, output.deltaExact, 0.001)
        assertEquals(166.67, output.valueAfterExact, 0.001)
        assertEquals(
            166.67,
            requireNotNull(fixture.state.view.state(target)).stats
                .precise(com.stzb.server.game.battle.BattleStat.DEFENSE),
            0.001,
        )
    }

    @Test
    fun `percent stat modifiers use inherent stats while preserving entry bonuses`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(BattleHero(source.heroId, source.position, BattleStats(100, 100, 100, 100, 10, 3), 1_000)),
            ),
            defender = BattleTeam(
                listOf(
                    BattleHero(
                        id = target.heroId,
                        position = target.position,
                        stats = BattleStats.fromHundredths(13_150, 10_000, 10_000, 10_000, 1_000, 3),
                        troops = 1_000,
                        inherentStats = BattleStats(100, 100, 100, 100, 10, 3),
                    ),
                ),
            ),
        )
        val state = SkillBattleState(request, SkillRuntimeState())
        val result = BattleStateChangeApplier(state).apply(
            listOf(
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.ATTACK,
                    TypedBattlePotency.percent(-22),
                    durationRounds = 8,
                    skillId = 200023,
                    effectId = 201,
                ),
            ),
            round = 0,
        )

        val output = result.outputs.filterIsInstance<BattleStateOutput.StatChanged>().single()
        assertEquals(-22.0, output.deltaExact, 0.001)
        assertEquals(109.5, output.valueAfterExact, 0.001)
    }

    @Test
    fun `recovery is capped by live maximum troops`() {
        val fixture = fixture(targetTroops = 990)

        fixture.applier.apply(
            listOf(TroopRecoveryChange(source, target, 99, 1_089, 10, 401)),
            round = 0,
        )

        assertEquals(1_000, fixture.state.view.state(target)?.troops)
    }

    @Test
    fun `recovery taken modifier scales actual recovery and expires on its round boundary`() {
        val fixture = fixture(targetTroops = 500, targetWounded = 500)
        fixture.applier.apply(
            listOf(
                ModifierEffectChange(
                    spec = spec(
                        effectId = 281,
                        rounds = 2,
                        potency = TypedBattlePotency.percent(20),
                    ),
                    modifier = BattleModifier.RecoveryTakenPercent(20),
                ),
            ),
            round = 0,
        )

        fixture.applier.apply(
            listOf(RecoverTroopsChange(source, target, 100, 600, 10, 401)),
            round = 1,
        )
        assertEquals(620, fixture.state.view.state(target)?.troops)

        fixture.applier.onRoundEnd(1)
        fixture.applier.onRoundEnd(2)
        fixture.applier.apply(
            listOf(RecoverTroopsChange(source, target, 100, 720, 10, 401)),
            round = 3,
        )
        assertEquals(720, fixture.state.view.state(target)?.troops)
    }

    @Test
    fun `recovery dealt and taken modifiers share one additive recovery axis`() {
        val fixture = fixture(targetTroops = 500, targetWounded = 500)
        fixture.applier.apply(
            listOf(
                ModifierEffectChange(
                    spec = spec(
                        effectId = 271,
                        rounds = 2,
                        potency = TypedBattlePotency.percent(15),
                    ).copy(target = source),
                    modifier = BattleModifier.RecoveryDealtPercent(15),
                ),
                ModifierEffectChange(
                    spec = spec(
                        effectId = 281,
                        rounds = 2,
                        potency = TypedBattlePotency.percent(20),
                    ),
                    modifier = BattleModifier.RecoveryTakenPercent(20),
                ),
            ),
            round = 0,
        )

        fixture.applier.apply(
            listOf(RecoverTroopsChange(source, target, 100, 635, 10, 401)),
            round = 1,
        )

        assertEquals(635, fixture.state.view.state(target)?.troops)
    }

    @Test
    fun `special damage modifier only matches its configured ongoing status`() {
        val fixture = fixture(targetTroops = 1_000)
        fixture.applier.apply(
            listOf(
                ModifierEffectChange(
                    spec = spec(
                        effectId = 261,
                        rounds = 3,
                        potency = TypedBattlePotency.percent(50),
                    ),
                    modifier = BattleModifier.DamageTakenPercent(
                        tag = DamageTag.PANIC,
                        percent = 50,
                    ),
                ),
            ),
            round = 0,
        )
        val liveSource = fixture.state.liveHero(source)
        val liveTarget = fixture.state.liveHero(target)

        val panic = com.stzb.server.game.battle.BattleDamageCalculator.strategy(
            liveSource,
            liveTarget,
            ratePercent = 100,
            ongoing = true,
            tags = setOf(DamageTag.ONGOING, DamageTag.PANIC),
        )
        val burn = com.stzb.server.game.battle.BattleDamageCalculator.strategy(
            liveSource,
            liveTarget,
            ratePercent = 100,
            ongoing = true,
            tags = setOf(DamageTag.ONGOING, DamageTag.BURN),
        )

        assertTrue(panic > burn)
    }

    @Test
    fun `persistent action effect expires at its exact round boundary`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(ActionEffectChange(spec(544, rounds = 2), ActionEffectKind.DOUBLE_ATTACK)),
            round = 0,
        )

        fixture.applier.onRoundEnd(1)
        assertEquals(2, fixture.applier.permissionFor(target).normalAttackCount)
        fixture.applier.onRoundEnd(2)
        assertEquals(1, fixture.applier.permissionFor(target).normalAttackCount)
    }

    @Test
    fun `ongoing damage ticks from live state only before its target action`() {
        val fixture = fixture(targetTroops = 1_000)
        fixture.applier.apply(
            listOf(
                ScheduledDamageEffectChange(
                    spec = spec(
                        effectId = 304,
                        category = EffectCategory.HARMFUL,
                        rounds = 2,
                        potency = TypedBattlePotency.rate(100),
                    ),
                    school = DamageSchool.STRATEGY,
                    origin = DamageOrigin.COMMAND,
                    tags = setOf(DamageTag.ONGOING),
                    status = BattleStatus.PANIC,
                    coefficientSource = BattleCoefficientSource.NONE,
                    rawCoefficient = 0,
                    calculationTypes = emptyList(),
                ),
            ),
            round = 0,
        )

        val roundOne = fixture.applier.onRoundStart(1)
        assertTrue(roundOne.outputs.none { it is BattleStateOutput.DamageDealt })
        assertEquals(1_000, fixture.state.view.state(target)?.troops)

        val firstAction = fixture.applier.onActionStart(target, 1)
        val afterOne = fixture.state.view.state(target)?.troops
        assertTrue(firstAction.outputs.any { it is BattleStateOutput.DamageDealt })
        assertTrue(fixture.applier.onActionStart(target, 1).outputs.isEmpty())
        fixture.applier.onRoundEnd(1)

        val roundTwo = fixture.applier.onRoundStart(2)
        assertTrue(roundTwo.outputs.none { it is BattleStateOutput.DamageDealt })
        fixture.applier.onActionStart(target, 2)
        val afterTwo = fixture.state.view.state(target)?.troops
        fixture.applier.onRoundEnd(2)

        fixture.applier.onRoundStart(3)
        val roundThree = fixture.applier.onActionStart(target, 3)

        assertTrue(requireNotNull(afterTwo) < requireNotNull(afterOne))
        assertTrue(roundThree.outputs.none { it is BattleStateOutput.DamageDealt })
        assertTrue(fixture.state.effectStore.effectsFor(target).none { it.effectId == 304 })
    }

    @Test
    fun `action start ticks ongoing effects only for that target`() {
        val fixture = fixture(targetTroops = 1_000)
        fun ongoing(
            effectTarget: BattleHeroRef,
            effectSource: BattleHeroRef,
            detailId: Int,
        ) = ScheduledDamageEffectChange(
            spec = spec(
                effectId = 304,
                category = EffectCategory.HARMFUL,
                rounds = 2,
                potency = TypedBattlePotency.rate(100),
            ).copy(
                source = effectSource,
                target = effectTarget,
                detailId = detailId,
            ),
            school = DamageSchool.STRATEGY,
            origin = DamageOrigin.COMMAND,
            tags = setOf(DamageTag.ONGOING),
            status = BattleStatus.PANIC,
            coefficientSource = BattleCoefficientSource.NONE,
            rawCoefficient = 0,
            calculationTypes = emptyList(),
        )
        fixture.applier.apply(
            listOf(
                ongoing(target, source, 1_304),
                ongoing(source, target, 2_304),
            ),
            round = 0,
        )

        fixture.applier.onRoundStart(1)
        val targetAction = fixture.applier.onActionStart(target, 1)

        assertEquals(
            listOf(target),
            targetAction.outputs.filterIsInstance<BattleStateOutput.DamageDealt>()
                .map { it.target },
        )
        assertEquals(1_000, fixture.state.view.state(source)?.troops)
        assertTrue(fixture.applier.onActionStart(target, 1).outputs.isEmpty())

        val sourceAction = fixture.applier.onActionStart(source, 1)
        assertEquals(
            listOf(source),
            sourceAction.outputs.filterIsInstance<BattleStateOutput.DamageDealt>()
                .map { it.target },
        )
    }

    @Test
    fun `ongoing recovery ticks only before its target action and consumes exact rounds`() {
        val fixture = fixture(targetTroops = 900, targetWounded = 80)
        fixture.applier.apply(
            listOf(
                ScheduledRecoveryEffectChange(
                    spec = spec(
                        effectId = 402,
                        rounds = 2,
                        potency = TypedBattlePotency.flat(50),
                    ),
                    potency = TypedBattlePotency.flat(50),
                ),
            ),
            round = 0,
        )

        val roundOne = fixture.applier.onRoundStart(1)
        assertTrue(roundOne.outputs.none { it is BattleStateOutput.TroopsRecovered })
        assertEquals(900, fixture.state.view.state(target)?.troops)

        val firstAction = fixture.applier.onActionStart(target, 1)
        assertEquals(
            listOf(50),
            firstAction.outputs.filterIsInstance<BattleStateOutput.TroopsRecovered>()
                .map { it.amount },
        )
        assertEquals(950, fixture.state.view.state(target)?.troops)
        assertEquals(30, fixture.state.view.state(target)?.woundedTroops)
        assertTrue(fixture.applier.onActionStart(target, 1).outputs.isEmpty())
        fixture.applier.onRoundEnd(1)

        fixture.applier.onRoundStart(2)
        val secondAction = fixture.applier.onActionStart(target, 2)

        assertEquals(
            listOf(26),
            secondAction.outputs.filterIsInstance<BattleStateOutput.TroopsRecovered>()
                .map { it.amount },
        )
        assertEquals(976, fixture.state.view.state(target)?.troops)
        assertEquals(0, fixture.state.view.state(target)?.woundedTroops)
        assertTrue(fixture.state.effectStore.effectsFor(target).none { it.effectId == 402 })
    }

    @Test
    fun `specified ongoing trigger ticks only the requested effect id`() {
        val fixture = fixture(targetTroops = 1_000)
        fun ongoing(
            effectId: Int,
            status: BattleStatus,
            tag: DamageTag,
        ) = ScheduledDamageEffectChange(
            spec = spec(
                effectId = effectId,
                category = EffectCategory.HARMFUL,
                rounds = 2,
                potency = TypedBattlePotency.rate(100),
            ),
            school = DamageSchool.STRATEGY,
            origin = DamageOrigin.PASSIVE,
            tags = setOf(DamageTag.ONGOING, tag),
            status = status,
            coefficientSource = BattleCoefficientSource.NONE,
            rawCoefficient = 0,
            calculationTypes = emptyList(),
        )
        fixture.applier.apply(
            listOf(
                ongoing(304, BattleStatus.PANIC, DamageTag.PANIC),
                ongoing(305, BattleStatus.BURN, DamageTag.BURN),
            ),
            round = 0,
        )

        val result = fixture.applier.triggerSpecifiedOngoingDamage(
            target = target,
            effectId = 305,
            round = 1,
        )

        assertEquals(
            listOf(305),
            result.outputs.filterIsInstance<BattleStateOutput.DamageDealt>()
                .map { it.effectId },
        )
    }

    @Test
    fun `specified ongoing trigger matches the exact source and detail`() {
        val fixture = fixture(targetTroops = 1_000)
        fun ongoing(
            effectSource: BattleHeroRef,
            detailId: Int,
            skillId: Int,
        ) = ScheduledDamageEffectChange(
            spec = spec(
                effectId = 303,
                category = EffectCategory.HARMFUL,
                rounds = 0,
                potency = TypedBattlePotency.rate(100),
            ).copy(
                source = effectSource,
                rootSkillId = skillId,
                skillId = skillId,
                detailId = detailId,
                availableHit = 3,
            ),
            school = DamageSchool.PHYSICAL,
            origin = DamageOrigin.ACTIVE,
            tags = setOf(DamageTag.ONGOING, DamageTag.SHAKE),
            status = BattleStatus.SHAKE,
            coefficientSource = BattleCoefficientSource.NONE,
            rawCoefficient = 0,
            calculationTypes = emptyList(),
        )
        fixture.applier.apply(
            listOf(
                ongoing(source, detailId = 1_303, skillId = 10),
                ongoing(target, detailId = 2_303, skillId = 20),
            ),
            round = 0,
        )

        val result = fixture.applier.triggerSpecifiedOngoingDamage(
            target = target,
            effectId = 303,
            source = source,
            detailId = 1_303,
            round = 1,
        )

        assertEquals(
            listOf(10),
            result.outputs.filterIsInstance<BattleStateOutput.DamageDealt>()
                .map { it.skillId },
        )
    }

    @Test
    fun `action effects expose double pursuit split counter redirect evade ignore and first`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                ActionEffectChange(spec(544), ActionEffectKind.DOUBLE_ATTACK),
                ActionEffectChange(spec(545), ActionEffectKind.SECONDARY_ATTACK),
                ActionEffectChange(spec(551), ActionEffectKind.COUNTERATTACK),
                ActionEffectChange(spec(515).copy(target = source), ActionEffectKind.IGNORE_EVADE),
                ActionEffectChange(spec(761).copy(target = source), ActionEffectKind.FIRST_ACTION),
                ActionEffectChange(spec(514), ActionEffectKind.GUARD),
                DamageRedirectionEffectChange(spec(506), listOf(target), source),
            ),
            round = 0,
        )

        val sourcePermission = fixture.applier.permissionFor(source)
        val targetPermission = fixture.applier.permissionFor(target)
        assertEquals(2, targetPermission.normalAttackCount)
        assertEquals(2, targetPermission.pursuitOpportunityCount)
        assertTrue(targetPermission.splitAttack)
        assertTrue(targetPermission.counterattack)
        assertTrue(targetPermission.canEvade)
        assertTrue(sourcePermission.ignoresEvade)
        assertTrue(sourcePermission.firstAction)
        assertEquals(source, targetPermission.damageRedirectTarget)
    }

    @Test
    fun `troop counter immunity reaches live damage state and consumes its hit scope`() {
        val fixture = fixture()
        val immunity = spec(effectId = 871, rounds = 0).copy(
            target = source,
            availableHit = 1,
        )

        fixture.applier.apply(
            listOf(
                ActionEffectChange(
                    spec = immunity,
                    kind = ActionEffectKind.IGNORE_TROOP_COUNTER,
                ),
            ),
            round = 0,
        )

        assertTrue(
            BattleModifier.TroopCounterImmunity in fixture.state.liveHero(source).modifiers,
        )
        fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = target,
                    amount = 10,
                    troopsAfter = 990,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.NORMAL,
                    tags = emptySet(),
                    skillId = 295091,
                    effectId = 301,
                ),
            ),
            round = 1,
        )

        assertTrue(fixture.state.effectStore.effectsFor(source).none { it.effectId == 871 })
        assertTrue(
            BattleModifier.TroopCounterImmunity !in fixture.state.liveHero(source).modifiers,
        )
    }

    @Test
    fun `control activation cancels preparation immediately and live view exposes status`() {
        val runtime = SkillRuntimeState()
        runtime.prepare(
            PreparedSkill(
                source = target,
                skillId = 30,
                readyRound = 3,
            ),
        )
        val fixture = fixture(runtime = runtime)

        fixture.applier.apply(
            listOf(
                ActionEffectChange(spec(501), ActionEffectKind.GUARD),
                CancelPreparedSkillsChange(spec(501)),
            ),
            round = 1,
        )

        assertTrue(runtime.preparedSkills().isEmpty())
        assertTrue(BattleStatus.CONFUSION in requireNotNull(fixture.state.view.state(target)).statuses)
    }

    @Test
    fun `command and passive effects mutate live state without waiting for a battle round`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.DEFENSE,
                    TypedBattlePotency.flat(7),
                    2,
                    40,
                    102,
                ),
                TroopDamageChange(
                    source,
                    target,
                    10,
                    990,
                    DamageSchool.STRATEGY,
                    DamageOrigin.PASSIVE,
                    emptySet(),
                    41,
                    302,
                ),
            ),
            round = 0,
        )

        assertEquals(107, fixture.state.view.state(target)?.stats?.defense)
        assertEquals(990, fixture.state.view.state(target)?.troops)
    }

    @Test
    fun `unsupported change in a batch fails before any mutation`() {
        val fixture = fixture()
        val unsupported = object : BattleStateChange {}

        assertFailsWith<UnsupportedBattleStateChangeException> {
            fixture.applier.apply(
                listOf(
                    TroopDamageChange(
                        source,
                        target,
                        10,
                        990,
                        DamageSchool.PHYSICAL,
                        DamageOrigin.ACTIVE,
                        emptySet(),
                        50,
                        301,
                    ),
                    unsupported,
                ),
                round = 1,
            )
        }

        assertEquals(1_000, fixture.state.view.state(target)?.troops)
        assertEquals(0, fixture.state.view.accumulatedDamageDealt(source))
    }

    @Test
    fun `semantic failure in second change leaves troops effects and preparation unchanged`() {
        val runtime = SkillRuntimeState().also {
            assertTrue(it.prepare(PreparedSkill(target, 30, readyRound = 3)))
        }
        val fixture = fixture(runtime = runtime)
        val unknown = BattleHeroRef(Side.DEFENDER, 1, BattleHeroId(999))

        assertFailsWith<IllegalArgumentException> {
            fixture.applier.apply(
                listOf(
                    CancelPreparedSkillsChange(spec(501)),
                    ActionEffectChange(
                        spec(544).copy(target = unknown),
                        ActionEffectKind.DOUBLE_ATTACK,
                    ),
                ),
                round = 1,
            )
        }

        assertEquals(listOf(30), runtime.preparedSkills().map { it.skillId })
        assertEquals(1_000, fixture.state.view.state(target)?.troops)
        assertTrue(fixture.state.effectStore.effectsFor(target).isEmpty())
    }

    @Test
    fun `rejected persistent behavior never leaks and cleanse removes accepted behavior`() {
        val fixture = fixture()
        val accepted = ScheduledDamageEffectChange(
            spec = spec(304, category = EffectCategory.HARMFUL, rounds = 3)
                .copy(conflict = 91, replaceType = 1),
            school = DamageSchool.STRATEGY,
            origin = DamageOrigin.COMMAND,
            tags = setOf(DamageTag.ONGOING),
            status = BattleStatus.PANIC,
            coefficientSource = BattleCoefficientSource.NONE,
            rawCoefficient = 0,
            calculationTypes = emptyList(),
        )
        val rejected = accepted.copy(
            spec = accepted.spec.copy(
                source = target,
                skillId = 11,
                rootSkillId = 11,
                detailId = 2_304,
                potency = TypedBattlePotency.rate(500),
            ),
        )
        val applied = fixture.applier.apply(listOf(accepted, rejected), round = 0)

        assertEquals(1, fixture.state.effectStore.effectsFor(target).size)
        assertEquals(
            listOf(accepted.spec),
            applied.outputs.filterIsInstance<BattleStateOutput.EffectApplied>().map { it.spec },
        )
        assertTrue(BattleStatus.PANIC in requireNotNull(fixture.state.view.state(target)).statuses)
        fixture.applier.onRoundStart(1)
        val firstTick = fixture.applier.onActionStart(target, 1)
            .outputs.filterIsInstance<BattleStateOutput.DamageDealt>().single()
        assertTrue(firstTick.amount < 500)

        fixture.applier.apply(
            listOf(CleanseEffectsChange(spec(511), EffectCategory.HARMFUL)),
            round = 1,
        )
        assertTrue(fixture.state.effectStore.effectsFor(target).isEmpty())
        assertFalse(BattleStatus.PANIC in requireNotNull(fixture.state.view.state(target)).statuses)
        fixture.applier.onRoundStart(2)
        assertTrue(
            fixture.applier.onActionStart(target, 2)
                .outputs.none { it is BattleStateOutput.DamageDealt },
        )
    }

    @Test
    fun `cleanse and dispel remove signed stat and taken modifiers with synchronized caches`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                BattleStatChange(
                    source,
                    target,
                    BattleStatChange.Kind.ATTACK,
                    TypedBattlePotency.flat(-20),
                    durationRounds = 3,
                    skillId = 20,
                    effectId = 101,
                ),
                DamageModifierChange(
                    source,
                    target,
                    DamageModifierChange.Direction.TAKEN,
                    DamageSchool.PHYSICAL,
                    null,
                    null,
                    percent = 25,
                    durationRounds = 3,
                    skillId = 21,
                    effectId = 521,
                ),
                DamageModifierChange(
                    source,
                    target,
                    DamageModifierChange.Direction.TAKEN,
                    DamageSchool.STRATEGY,
                    null,
                    null,
                    percent = -30,
                    durationRounds = 3,
                    skillId = 22,
                    effectId = 524,
                ),
            ),
            round = 0,
        )

        assertEquals(80, fixture.state.view.state(target)?.stats?.attack)
        assertEquals(
            setOf(EffectCategory.HARMFUL, EffectCategory.HARMFUL, EffectCategory.BENEFICIAL),
            fixture.state.effectStore.effectsFor(target).map { it.category }.toSet(),
        )
        assertEquals(2, fixture.state.liveHero(target).modifiers.size)

        fixture.applier.apply(
            listOf(CleanseEffectsChange(spec(511), EffectCategory.HARMFUL)),
            round = 1,
        )
        assertEquals(100, fixture.state.view.state(target)?.stats?.attack)
        assertEquals(listOf(EffectCategory.BENEFICIAL), fixture.state.effectStore.effectsFor(target).map { it.category })
        assertEquals(
            listOf(-30),
            fixture.state.liveHero(target).modifiers
                .filterIsInstance<BattleModifier.DamageTakenPercent>()
                .map { it.percent },
        )

        fixture.applier.apply(
            listOf(CleanseEffectsChange(spec(594), EffectCategory.BENEFICIAL)),
            round = 1,
        )
        assertTrue(fixture.state.effectStore.effectsFor(target).isEmpty())
        assertTrue(fixture.state.liveHero(target).modifiers.isEmpty())
    }

    @Test
    fun `zero stat and damage modifiers are rejected atomically`() {
        val fixture = fixture()

        assertFailsWith<IllegalArgumentException> {
            fixture.applier.apply(
                listOf(
                    TroopDamageChange(
                        source,
                        target,
                        10,
                        990,
                        DamageSchool.PHYSICAL,
                        DamageOrigin.NORMAL,
                        emptySet(),
                        10,
                        301,
                    ),
                    BattleStatChange(
                        source,
                        target,
                        BattleStatChange.Kind.ATTACK,
                        TypedBattlePotency.flat(0),
                        2,
                        10,
                        101,
                    ),
                ),
                round = 1,
            )
        }
        assertEquals(1_000, fixture.state.view.state(target)?.troops)

        assertFailsWith<IllegalArgumentException> {
            fixture.applier.apply(
                listOf(
                    DamageModifierChange(
                        source,
                        target,
                        DamageModifierChange.Direction.DEALT,
                        null,
                        null,
                        null,
                        0,
                        2,
                        10,
                        531,
                    ),
                ),
                round = 1,
            )
        }
        assertTrue(fixture.state.effectStore.effectsFor(target).isEmpty())
    }

    @Test
    fun `stronger replacement swaps redirection behavior and expiry removes it`() {
        val fixture = fixture()
        val weak = DamageRedirectionEffectChange(
            spec(506, rounds = 2, potency = TypedBattlePotency.flat(1))
                .copy(conflict = 92, replaceType = 2),
            listOf(target),
            source,
        )
        val strongBearer = target
        val strong = weak.copy(
            spec = weak.spec.copy(
                source = strongBearer,
                rootSkillId = 12,
                skillId = 12,
                detailId = 2_506,
                potency = TypedBattlePotency.flat(9),
            ),
            damageBearer = strongBearer,
        )

        fixture.applier.apply(listOf(weak, strong), round = 0)
        assertEquals(strongBearer, fixture.applier.permissionFor(target).damageRedirectTarget)

        fixture.applier.onRoundEnd(1)
        assertEquals(strongBearer, fixture.applier.permissionFor(target).damageRedirectTarget)
        fixture.applier.onRoundEnd(2)
        assertEquals(null, fixture.applier.permissionFor(target).damageRedirectTarget)
    }

    @Test
    fun `damage sharing splits matching damage and consumes its single use`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                DamageRedirectionEffectChange(
                    spec = spec(127).copy(availableHit = 1),
                    protectedTargets = listOf(target),
                    damageBearer = source,
                    sharePercent = 50,
                    school = DamageSchool.STRATEGY,
                ),
            ),
            round = 0,
        )
        assertEquals(null, fixture.applier.permissionFor(target).damageRedirectTarget)

        val physical = fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source, target, 100, 900,
                    DamageSchool.PHYSICAL, DamageOrigin.ACTIVE, emptySet(), 20, 301,
                ),
            ),
            round = 1,
        )
        assertEquals(listOf(100), physical.outputs.filterIsInstance<BattleStateOutput.HurtReceived>().map { it.amount })
        assertEquals(900, fixture.state.view.state(target)?.troops)
        assertEquals(1_000, fixture.state.view.state(source)?.troops)

        val strategy = fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source, target, 100, 800,
                    DamageSchool.STRATEGY, DamageOrigin.ACTIVE, emptySet(), 20, 302,
                ),
            ),
            round = 1,
        )
        assertEquals(listOf(50, 50), strategy.outputs.filterIsInstance<BattleStateOutput.HurtReceived>().map { it.amount })
        assertEquals(850, fixture.state.view.state(target)?.troops)
        assertEquals(950, fixture.state.view.state(source)?.troops)
        assertTrue(fixture.state.effectStore.effectsFor(target).none { it.effectId == 127 })
    }

    @Test
    fun `linked hearts makes every other linked ally share fifteen percent damage`() {
        val middle = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(3))
        val rear = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(5))
        val request = BattleRequest(
            BattleTeam(
                listOf(
                    BattleHero(source.heroId, source.position, BattleStats(100, 100, 100, 100, 10, 3), 1_000),
                    BattleHero(middle.heroId, middle.position, BattleStats(100, 100, 100, 100, 10, 3), 1_000),
                    BattleHero(rear.heroId, rear.position, BattleStats(100, 100, 100, 100, 10, 3), 1_000),
                ),
            ),
            BattleTeam(listOf(BattleHero(BattleHeroId(4), 0, BattleStats(100, 100, 100, 100, 10, 3), 1_000))),
        )
        val state = SkillBattleState(request, SkillRuntimeState())
        val applier = BattleStateChangeApplier(state)
        applier.apply(
            listOf(
                LinkedDamageSharingEffectChange(
                    spec(409).copy(target = source),
                    members = listOf(source, middle, rear),
                    sharePercentPerAlly = 15,
                ),
            ),
            round = 0,
        )
        val enemy = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(4))

        val result = applier.apply(
            listOf(TroopDamageChange(enemy, rear, 100, 900, DamageSchool.PHYSICAL, DamageOrigin.ACTIVE, emptySet(), 30, 301)),
            round = 1,
        )

        assertEquals(listOf(70, 15, 15), result.outputs.filterIsInstance<BattleStateOutput.HurtReceived>().map { it.amount })
        assertEquals(930, state.view.state(rear)?.troops)
        assertEquals(985, state.view.state(source)?.troops)
        assertEquals(985, state.view.state(middle)?.troops)
    }

    @Test
    fun `jade seal absorbs protected damage into the current round accumulator`() {
        val ally = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(3))
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    BattleHero(
                        source.heroId,
                        source.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                    BattleHero(
                        ally.heroId,
                        ally.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    BattleHero(
                        target.heroId,
                        target.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                ),
            ),
        )
        val state = SkillBattleState(request, SkillRuntimeState())
        val applier = BattleStateChangeApplier(state)
        applier.apply(
            listOf(
                DamageAbsorptionAccumulatorEffectChange(
                    spec = spec(
                        effectId = 407,
                        rounds = 9,
                        potency = TypedBattlePotency.percent(25),
                    ).copy(target = source),
                    protectedTargets = listOf(source, ally),
                    absorbPercent = 25,
                ),
            ),
            round = 0,
        )

        val result = applier.apply(
            listOf(
                TroopDamageChange(
                    source = target,
                    target = ally,
                    amount = 100,
                    troopsAfter = 900,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 10,
                    effectId = 301,
                ),
            ),
            round = 1,
        )

        assertEquals(925, state.view.state(ally)?.troops)
        assertEquals(
            75,
            result.outputs.filterIsInstance<BattleStateOutput.DamageDealt>().single().amount,
        )
        assertEquals(
            BattleStateOutput.DamageAbsorbed(
                owner = source,
                target = ally,
                amount = 25,
                currentRoundTotal = 25,
                percent = 25,
            ),
            result.outputs.filterIsInstance<BattleStateOutput.DamageAbsorbed>().single(),
        )
        assertTrue(result.outputs.none { it is BattleStateOutput.TroopsRecovered })

        val ongoing = applier.apply(
            listOf(
                TroopDamageChange(
                    source = target,
                    target = source,
                    amount = 40,
                    troopsAfter = 960,
                    school = DamageSchool.STRATEGY,
                    origin = DamageOrigin.COMMAND,
                    tags = setOf(DamageTag.ONGOING),
                    skillId = 11,
                    effectId = 304,
                ),
            ),
            round = 1,
        )

        assertEquals(970, state.view.state(source)?.troops)
        assertEquals(
            BattleStateOutput.DamageAbsorbed(
                owner = source,
                target = source,
                amount = 10,
                currentRoundTotal = 35,
                percent = 25,
            ),
            ongoing.outputs.filterIsInstance<BattleStateOutput.DamageAbsorbed>().single(),
        )
    }

    @Test
    fun `jade seal rolls absorbed damage and releases it without recursive absorption`() {
        val runtime = SkillRuntimeState()
        val fixture = fixture(targetTroops = 1_000, runtime = runtime)
        fixture.applier.apply(
            listOf(
                DamageAbsorptionAccumulatorEffectChange(
                    spec = spec(
                        effectId = 407,
                        rounds = 9,
                        potency = TypedBattlePotency.percent(25),
                    ).copy(
                        source = target,
                        target = target,
                        rootSkillId = 200262,
                        skillId = 210262,
                        detailId = 21026201,
                    ),
                    protectedTargets = listOf(target),
                    absorbPercent = 25,
                ),
                DamageReleaseScheduleEffectChange(
                    spec = spec(
                        effectId = 408,
                        rounds = 9,
                        potency = TypedBattlePotency.percent(50),
                    ).copy(
                        source = target,
                        target = target,
                        rootSkillId = 200262,
                        skillId = 211262,
                        detailId = 21126204,
                    ),
                    target = target,
                    referencedDetailId = 21126202,
                    referencedEffectId = 302,
                    baseReleasePercent = 50,
                    firstReleaseRound = 2,
                ),
            ),
            round = 0,
        )
        fixture.applier.onRoundStart(1)
        fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source,
                    target,
                    200,
                    800,
                    DamageSchool.PHYSICAL,
                    DamageOrigin.ACTIVE,
                    emptySet(),
                    10,
                    301,
                ),
            ),
            round = 1,
        )
        fixture.applier.onRoundEnd(1)

        val roundTwo = fixture.applier.onRoundStart(2)

        val roundTwoRelease = roundTwo.outputs
            .filterIsInstance<BattleStateOutput.DamageDealt>()
            .single()
        assertEquals(25, roundTwoRelease.amount)
        assertEquals(setOf(DamageTag.IMPERIAL_SEAL_RELEASE), roundTwoRelease.tags)
        assertTrue(roundTwo.outputs.none { it is BattleStateOutput.DamageAbsorbed })

        fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source,
                    target,
                    80,
                    720,
                    DamageSchool.STRATEGY,
                    DamageOrigin.COMMAND,
                    setOf(DamageTag.ONGOING),
                    11,
                    304,
                ),
            ),
            round = 2,
        )
        runtime.addReferencedValueDelta(
            source = target,
            rootSkillId = 200262,
            detailId = 21126204,
            delta = 10,
        )
        fixture.applier.onRoundEnd(2)

        val roundThree = fixture.applier.onRoundStart(3)

        val roundThreeRelease = roundThree.outputs
            .filterIsInstance<BattleStateOutput.DamageDealt>()
            .single()
        assertEquals(12, roundThreeRelease.amount)
        assertEquals(setOf(DamageTag.IMPERIAL_SEAL_RELEASE), roundThreeRelease.tags)
        assertTrue(roundThree.outputs.none { it is BattleStateOutput.DamageAbsorbed })
        assertEquals(753, fixture.state.view.state(target)?.troops)
    }

    @Test
    fun `jade seal release bypasses linked sharing and damage redirection`() {
        val ally = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(3))
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    BattleHero(
                        source.heroId,
                        source.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                    BattleHero(
                        ally.heroId,
                        ally.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    BattleHero(
                        target.heroId,
                        target.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                ),
            ),
        )
        val state = SkillBattleState(request, SkillRuntimeState())
        val applier = BattleStateChangeApplier(state)
        applier.apply(
            listOf(
                LinkedDamageSharingEffectChange(
                    spec = spec(409).copy(target = source),
                    members = listOf(source, ally),
                    sharePercentPerAlly = 15,
                ),
                DamageRedirectionEffectChange(
                    spec = spec(127).copy(target = source),
                    protectedTargets = listOf(source),
                    damageBearer = ally,
                    sharePercent = 50,
                    school = DamageSchool.STRATEGY,
                ),
            ),
            round = 0,
        )

        val result = applier.apply(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = source,
                    amount = 100,
                    troopsAfter = 900,
                    school = DamageSchool.STRATEGY,
                    origin = DamageOrigin.COMMAND,
                    tags = setOf(DamageTag.IMPERIAL_SEAL_RELEASE),
                    skillId = 211262,
                    effectId = 302,
                ),
            ),
            round = 2,
        )

        assertEquals(900, state.view.state(source)?.troops)
        assertEquals(1_000, state.view.state(ally)?.troops)
        assertEquals(
            listOf(source to 100),
            result.outputs.filterIsInstance<BattleStateOutput.DamageDealt>()
                .map { it.target to it.amount },
        )
    }

    @Test
    fun `multiple jade seal owners accumulate independently and combine absorption`() {
        val secondOwner = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(3))
        val protected = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(4))
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    BattleHero(
                        source.heroId,
                        source.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                    BattleHero(
                        secondOwner.heroId,
                        secondOwner.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                    BattleHero(
                        protected.heroId,
                        protected.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    BattleHero(
                        target.heroId,
                        target.position,
                        BattleStats(100, 100, 100, 100, 10, 3),
                        1_000,
                    ),
                ),
            ),
        )
        val state = SkillBattleState(request, SkillRuntimeState())
        val applier = BattleStateChangeApplier(state)
        applier.apply(
            listOf(
                DamageAbsorptionAccumulatorEffectChange(
                    spec = spec(
                        effectId = 407,
                        rounds = 9,
                        potency = TypedBattlePotency.percent(25),
                    ).copy(target = source),
                    protectedTargets = listOf(protected),
                    absorbPercent = 25,
                ),
                DamageAbsorptionAccumulatorEffectChange(
                    spec = spec(
                        effectId = 407,
                        rounds = 9,
                        potency = TypedBattlePotency.percent(25),
                    ).copy(
                        source = secondOwner,
                        target = secondOwner,
                        rootSkillId = 200262,
                        skillId = 210262,
                        detailId = 21026201,
                    ),
                    protectedTargets = listOf(protected),
                    absorbPercent = 25,
                ),
            ),
            round = 0,
        )

        val result = applier.apply(
            listOf(
                TroopDamageChange(
                    source = target,
                    target = protected,
                    amount = 100,
                    troopsAfter = 900,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 10,
                    effectId = 301,
                ),
            ),
            round = 1,
        )

        assertEquals(950, state.view.state(protected)?.troops)
        assertEquals(
            setOf(source to 25, secondOwner to 25),
            result.outputs.filterIsInstance<BattleStateOutput.DamageAbsorbed>()
                .mapTo(linkedSetOf()) { it.owner to it.amount },
        )
    }

    @Test
    fun `jade seal skips release when its owner is already defeated`() {
        val fixture = fixture(targetTroops = 10)
        fixture.applier.apply(
            listOf(
                DamageAbsorptionAccumulatorEffectChange(
                    spec = spec(
                        effectId = 407,
                        rounds = 9,
                        potency = TypedBattlePotency.percent(25),
                    ).copy(
                        source = target,
                        target = target,
                        rootSkillId = 200262,
                        skillId = 210262,
                        detailId = 21026201,
                    ),
                    protectedTargets = listOf(target),
                    absorbPercent = 25,
                ),
                DamageReleaseScheduleEffectChange(
                    spec = spec(
                        effectId = 408,
                        rounds = 9,
                        potency = TypedBattlePotency.percent(50),
                    ).copy(
                        source = target,
                        target = target,
                        rootSkillId = 200262,
                        skillId = 211262,
                        detailId = 21126204,
                    ),
                    target = target,
                    referencedDetailId = 21126202,
                    referencedEffectId = 302,
                    baseReleasePercent = 50,
                    firstReleaseRound = 2,
                ),
            ),
            round = 0,
        )
        fixture.applier.onRoundStart(1)
        fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source,
                    target,
                    8,
                    2,
                    DamageSchool.PHYSICAL,
                    DamageOrigin.ACTIVE,
                    emptySet(),
                    10,
                    301,
                ),
                TroopDamageChange(
                    target,
                    target,
                    4,
                    0,
                    DamageSchool.STRATEGY,
                    DamageOrigin.COMMAND,
                    setOf(DamageTag.IMPERIAL_SEAL_RELEASE),
                    211262,
                    302,
                ),
            ),
            round = 1,
        )
        assertEquals(0, fixture.state.view.state(target)?.troops)
        fixture.applier.onRoundEnd(1)

        val roundTwo = fixture.applier.onRoundStart(2)

        assertTrue(roundTwo.outputs.none { it is BattleStateOutput.DamageDealt })
        assertTrue(roundTwo.outputs.none { it is BattleStateOutput.HurtReceived })
    }

    @Test
    fun `damage creates only actual wounded and paired recovery consumes only actual recovery`() {
        val fixture = fixture(targetTroops = 10, targetWounded = 5)
        fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source,
                    target,
                    99,
                    0,
                    DamageSchool.PHYSICAL,
                    DamageOrigin.ACTIVE,
                    emptySet(),
                    10,
                    301,
                ),
            ),
            round = 0,
        )
        assertEquals(14, fixture.state.view.state(target)?.woundedTroops)

        fixture.applier.apply(
            listOf(
                RecoverTroopsChange(source, target, 99, 1_000, 10, 401),
                ConsumeWoundedTroopsChange(target, 99, 0, 10, 401),
            ),
            round = 0,
        )
        assertEquals(14, fixture.state.view.state(target)?.troops)
        assertEquals(0, fixture.state.view.state(target)?.woundedTroops)

        val capacityFixture = fixture(targetTroops = 995, targetWounded = 50)
        capacityFixture.applier.apply(
            listOf(
                RecoverTroopsChange(source, target, 50, 1_000, 10, 401),
                ConsumeWoundedTroopsChange(target, 50, 0, 10, 401),
            ),
            round = 0,
        )
        assertEquals(1_000, capacityFixture.state.view.state(target)?.troops)
        assertEquals(45, capacityFixture.state.view.state(target)?.woundedTroops)
    }

    @Test
    fun `damage creates ninety five percent wounded and next round preserves eighty seven percent`() {
        val fixture = fixture(targetTroops = 1_000, targetWounded = 0)

        fixture.applier.onRoundStart(1)
        fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = target,
                    amount = 215,
                    troopsAfter = 785,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.NORMAL,
                    tags = emptySet(),
                    skillId = 0,
                    effectId = 0,
                ),
            ),
            round = 1,
        )

        assertEquals(204, fixture.state.view.state(target)?.woundedTroops)

        fixture.applier.onRoundEnd(1)
        fixture.applier.onRoundStart(2)

        assertEquals(177, fixture.state.view.state(target)?.woundedTroops)
    }

    @Test
    fun `live view advertises only available data and fails closed for missing adapters`() {
        val fixture = fixture()
        val capabilities = fixture.state.view.capabilities

        assertTrue(SkillBattleViewCapability.LIVE_STATE in capabilities)
        assertTrue(SkillBattleViewCapability.ACTIVE_EFFECTS in capabilities)
        assertFalse(SkillBattleViewCapability.HERO_METADATA in capabilities)
        assertFalse(SkillBattleViewCapability.TARGET_HISTORY in capabilities)
        assertFalse(SkillBattleViewCapability.STATE_FILTERS in capabilities)
        assertFailsWith<MissingLiveBattleViewData> { fixture.state.view.metadata(target) }
        assertFailsWith<MissingLiveBattleViewData> { fixture.state.view.currentTarget(source) }
        assertFailsWith<MissingLiveBattleViewData> {
            fixture.state.view.matchesStateFilter(SkillTargetStateFilter.FLAG_1, source, target)
        }
    }

    @Test
    fun `damage modifier reaches live hero damage calculation and expires with store`() {
        val fixture = fixture()
        val baseline = com.stzb.server.game.battle.BattleDamageCalculator.physical(
            fixture.state.liveHero(source),
            fixture.state.liveHero(target),
            origin = DamageOrigin.ACTIVE,
        )
        fixture.applier.apply(
            listOf(
                DamageModifierChange(
                    source = source,
                    target = source,
                    direction = DamageModifierChange.Direction.DEALT,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.ACTIVE,
                    tag = null,
                    percent = 50,
                    durationRounds = 1,
                    skillId = 10,
                    effectId = 531,
                ),
            ),
            round = 0,
        )

        assertTrue(
            fixture.state.liveHero(source).modifiers
                .filterIsInstance<BattleModifier.DamageDealtPercent>()
                .any { it.percent == 50 },
        )
        val modified = com.stzb.server.game.battle.BattleDamageCalculator.physical(
            fixture.state.liveHero(source),
            fixture.state.liveHero(target),
            origin = DamageOrigin.ACTIVE,
        )
        assertNotEquals(baseline, modified)

        fixture.applier.onRoundEnd(1)
        assertTrue(
            fixture.state.liveHero(source).modifiers
                .filterIsInstance<BattleModifier.DamageDealtPercent>()
                .none { it.percent == 50 },
        )
    }

    @Test
    fun `damage modifier preserves its required target status in live state`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                DamageModifierChange(
                    source = source,
                    target = target,
                    direction = DamageModifierChange.Direction.TAKEN,
                    school = null,
                    origin = null,
                    tag = null,
                    percent = -30,
                    durationRounds = 8,
                    skillId = 296023,
                    effectId = 421,
                    requiredTargetStatus = BattleStatus.CONFUSION,
                ),
            ),
            round = 0,
        )

        assertEquals(
            BattleStatus.CONFUSION,
            fixture.state.liveHero(target).modifiers
                .filterIsInstance<BattleModifier.DamageTakenPercent>()
                .single()
                .requiredStatus,
        )
    }

    @Test
    fun `conditional hit scoped modifier consumes only while its required status is active`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                DamageModifierChange(
                    source = source,
                    target = target,
                    direction = DamageModifierChange.Direction.TAKEN,
                    school = null,
                    origin = null,
                    tag = null,
                    percent = -30,
                    durationRounds = 8,
                    skillId = 296023,
                    effectId = 421,
                    availableHits = 2,
                    requiredTargetStatus = BattleStatus.CONFUSION,
                ),
            ),
            round = 0,
        )

        fun applyDamage(troopsAfter: Int) {
            fixture.applier.apply(
                listOf(
                    TroopDamageChange(
                        source = source,
                        target = target,
                        amount = 10,
                        troopsAfter = troopsAfter,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 10,
                        effectId = 301,
                    ),
                ),
                round = 1,
            )
        }

        applyDamage(troopsAfter = 990)
        assertEquals(
            2,
            fixture.state.effectStore.effectsFor(target)
                .single { it.effectId == 421 }
                .remainingHits,
        )

        fixture.applier.apply(
            listOf(ActionEffectChange(spec(501), ActionEffectKind.GUARD)),
            round = 1,
        )
        applyDamage(troopsAfter = 980)

        assertEquals(
            1,
            fixture.state.effectStore.effectsFor(target)
                .single { it.effectId == 421 }
                .remainingHits,
        )
    }

    @Test
    fun `dealt hit scoped modifier consumes on matching source damage`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                DamageModifierChange(
                    source = source,
                    target = source,
                    direction = DamageModifierChange.Direction.DEALT,
                    school = DamageSchool.PHYSICAL,
                    origin = null,
                    tag = null,
                    percent = 50,
                    durationRounds = 8,
                    skillId = 200036,
                    effectId = 531,
                    detailId = 20003625,
                    availableHits = 2,
                ),
            ),
            round = 1,
        )

        fixture.applier.apply(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = target,
                    amount = 10,
                    troopsAfter = 990,
                    school = DamageSchool.STRATEGY,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 10,
                    effectId = 302,
                ),
            ),
            round = 1,
        )
        assertEquals(
            2,
            fixture.state.effectStore.effectsFor(source).single().remainingHits,
        )

        repeat(2) {
            fixture.applier.apply(
                listOf(
                    TroopDamageChange(
                        source = source,
                        target = target,
                        amount = 10,
                        troopsAfter = 980 - it * 10,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 10,
                        effectId = 301,
                    ),
                ),
                round = 1,
            )
        }

        assertTrue(fixture.state.effectStore.effectsFor(source).isEmpty())
        assertTrue(
            fixture.state.liveHero(source).modifiers
                .filterIsInstance<BattleModifier.DamageDealtPercent>()
                .none { it.percent == 50 },
        )
    }

    @Test
    fun `damage modifier stacks up to its configured maximum`() {
        val fixture = fixture()
        val layer = DamageModifierChange(
            source = source,
            target = source,
            direction = DamageModifierChange.Direction.DEALT,
            school = DamageSchool.PHYSICAL,
            origin = null,
            tag = null,
            percent = 8,
            durationRounds = 8,
            skillId = 213961,
            effectId = 531,
            detailId = 21396101,
            maxStacks = 5,
        )

        fixture.applier.apply(List(5) { layer }, round = 1)

        val effect = fixture.state.effectStore.effectsFor(source).single {
            it.detailId == 21396101
        }
        assertEquals(5, effect.stacks)
        assertEquals(40, effect.effectiveStrength)
        assertEquals(
            40,
            fixture.state.liveHero(source).modifiers
                .filterIsInstance<BattleModifier.DamageDealtPercent>()
                .single()
                .percent,
        )
    }

    @Test
    fun `attribute ignore only bypasses its configured defensive attribute`() {
        val fixture = fixture()
        val sourceHero = fixture.state.liveHero(source)
        val targetHero = fixture.state.liveHero(target).copy(
            stats = BattleStats(100, 300, 300, 100, 10, 3),
        )
        val defenseIgnore = sourceHero.copy(
            modifiers = listOf(BattleModifier.DefenseIgnorePercent(100, BattleStat.DEFENSE)),
        )
        val strategyIgnore = sourceHero.copy(
            modifiers = listOf(BattleModifier.DefenseIgnorePercent(100, BattleStat.STRATEGY)),
        )

        val physicalBaseline = com.stzb.server.game.battle.BattleDamageCalculator.physical(
            sourceHero,
            targetHero,
        )
        val strategyBaseline = com.stzb.server.game.battle.BattleDamageCalculator.strategy(
            sourceHero,
            targetHero,
            ratePercent = 100,
        )

        assertTrue(
            com.stzb.server.game.battle.BattleDamageCalculator.physical(
                defenseIgnore,
                targetHero,
            ) > physicalBaseline,
        )
        assertEquals(
            strategyBaseline,
            com.stzb.server.game.battle.BattleDamageCalculator.strategy(
                defenseIgnore,
                targetHero,
                ratePercent = 100,
            ),
        )
        assertEquals(
            physicalBaseline,
            com.stzb.server.game.battle.BattleDamageCalculator.physical(
                strategyIgnore,
                targetHero,
            ),
        )
        assertTrue(
            com.stzb.server.game.battle.BattleDamageCalculator.strategy(
                strategyIgnore,
                targetHero,
                ratePercent = 100,
            ) > strategyBaseline,
        )
    }

    @Test
    fun `delayed activation rejects early succeeds exactly once at due boundary`() {
        val fixture = fixture()
        val scheduled = ScheduledEffectActivationChange(
            spec = spec(544).copy(
                startBoundary = EffectStartBoundary.AFTER_DELAY,
                delayRound = 1,
            ),
            actionKind = ActionEffectKind.DOUBLE_ATTACK,
            status = BattleStatus.DOUBLE_ATTACK,
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.applier.apply(listOf(scheduled), round = 1)
        }
        assertEquals(1, fixture.applier.permissionFor(target).normalAttackCount)

        val due = SkillTimingDue.mint(scheduled, dueRound = 2, dueHit = 0, sequence = 7)
        assertFailsWith<IllegalArgumentException> {
            fixture.applier.applyActivated(scheduled, due, round = 1, hit = 0)
        }
        assertEquals(1, fixture.applier.permissionFor(target).normalAttackCount)

        fixture.applier.applyActivated(scheduled, due, round = 2, hit = 0)
        assertEquals(2, fixture.applier.permissionFor(target).normalAttackCount)
        assertTrue(BattleStatus.DOUBLE_ATTACK in requireNotNull(fixture.state.view.state(target)).statuses)
        assertFailsWith<IllegalArgumentException> {
            fixture.applier.applyActivated(scheduled, due, round = 2, hit = 0)
        }
        assertEquals(2, fixture.applier.permissionFor(target).normalAttackCount)
    }

    @Test
    fun `round hooks are idempotent and reject backward rounds`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                ScheduledDamageEffectChange(
                    spec(304, rounds = 2, potency = TypedBattlePotency.rate(100)),
                    DamageSchool.STRATEGY,
                    DamageOrigin.COMMAND,
                    setOf(DamageTag.ONGOING),
                    BattleStatus.PANIC,
                    BattleCoefficientSource.NONE,
                    0,
                    emptyList(),
                ),
            ),
            round = 0,
        )

        assertTrue(fixture.applier.onRoundStart(1).outputs.isEmpty())
        assertTrue(fixture.applier.onRoundStart(1).outputs.isEmpty())
        assertTrue(fixture.applier.onActionStart(target, 1).outputs.isNotEmpty())
        assertTrue(fixture.applier.onActionStart(target, 1).outputs.isEmpty())
        fixture.applier.onRoundEnd(1)
        fixture.applier.onRoundEnd(1)
        fixture.applier.onRoundStart(2)
        assertFailsWith<IllegalArgumentException> { fixture.applier.onRoundStart(1) }
        assertFailsWith<IllegalArgumentException> {
            fixture.applier.onActionStart(target, 1)
        }
        assertFailsWith<IllegalArgumentException> { fixture.applier.onRoundEnd(1) }
    }

    @Test
    fun `forced target consumes its single use only after probability succeeds`() {
        val fixture = fixture()
        fixture.applier.apply(
            listOf(
                ForcedTargetEffectChange(
                    spec = spec(
                        effectId = 81,
                        rounds = 0,
                        potency = TypedBattlePotency.percent(50),
                    ).copy(
                        target = source,
                        availableHit = 1,
                    ),
                    forcedTarget = target,
                ),
            ),
            round = 1,
        )

        assertEquals(
            null,
            fixture.applier.tryConsumeForcedTarget(
                actor = source,
                eligibleTargets = listOf(target),
                random = com.stzb.server.game.battle.FixedBattleRandom(99),
            ),
        )
        assertEquals(1, fixture.state.effectStore.effectsFor(source).single().remainingHits)
        assertEquals(
            target,
            fixture.applier.tryConsumeForcedTarget(
                actor = source,
                eligibleTargets = listOf(target),
                random = com.stzb.server.game.battle.FixedBattleRandom(0),
            ),
        )
        assertTrue(fixture.state.effectStore.effectsFor(source).isEmpty())
        assertEquals(
            null,
            fixture.applier.tryConsumeForcedTarget(
                actor = source,
                eligibleTargets = listOf(target),
                random = com.stzb.server.game.battle.FixedBattleRandom(0),
            ),
        )
    }

    @Test
    fun `shared effect use consumes every grouped probability modifier together`() {
        val fixture = fixture()
        fun groupedSpec(
            detailId: Int,
            effectId: Int,
        ) = spec(
            effectId = effectId,
            rounds = 0,
            potency = TypedBattlePotency.percent(100),
        ).copy(
            source = source,
            target = source,
            rootSkillId = 200293,
            skillId = 211293,
            detailId = detailId,
            availableHit = 1,
        )
        fixture.applier.apply(
            listOf(
                ModifierEffectChange(
                    groupedSpec(21129311, 131),
                    BattleModifier.SkillProbabilityPercent(
                        percent = 100,
                        skillKind = SkillKind.ACTIVE,
                    ),
                ),
                ModifierEffectChange(
                    groupedSpec(21129312, 131),
                    BattleModifier.SkillProbabilityPercent(
                        percent = 100,
                        skillKind = SkillKind.PURSUIT,
                    ),
                ),
                SharedEffectUseGroupChange(
                    groupedSpec(21129318, 88),
                    memberDetailId = 21129311,
                ),
                SharedEffectUseGroupChange(
                    groupedSpec(21129319, 88),
                    memberDetailId = 21129312,
                ),
            ),
            round = 1,
        )

        assertEquals(
            listOf(131, 131, 88, 88),
            fixture.state.effectStore.effectsFor(source).map { it.effectId },
        )
        fixture.applier.consumeSkillProbabilityUses(
            actor = source,
            skillId = 200049,
            skillKind = SkillKind.ACTIVE,
        )

        assertTrue(fixture.state.effectStore.effectsFor(source).none {
            it.effectId == 131 || it.effectId == 88
        })
    }

    @Test
    fun `strict unknown meta intent is diagnostic and never silently ignored`() {
        val fixture = fixture()
        val unsupported = object : BattleStateChange {}

        val error = assertFailsWith<UnsupportedBattleStateChangeException> {
            fixture.applier.apply(listOf(unsupported), round = 1)
        }

        assertTrue(error.message.orEmpty().contains(unsupported::class.qualifiedName.orEmpty()))
        assertFalse(error.message.isNullOrBlank())
    }

    private fun fixture(
        targetTroops: Int = 1_000,
        targetWounded: Int = 0,
        runtime: SkillRuntimeState = SkillRuntimeState(),
    ): Fixture {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    BattleHero(
                        id = source.heroId,
                        position = source.position,
                        stats = BattleStats(100, 100, 100, 100, 10, 3),
                        troops = 1_000,
                        maxTroops = 1_000,
                    ),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    BattleHero(
                        id = target.heroId,
                        position = target.position,
                        stats = BattleStats(100, 100, 100, 100, 10, 3),
                        troops = targetTroops,
                        maxTroops = 1_000,
                    ),
                ),
            ),
        )
        val state = SkillBattleState(
            request = request,
            runtime = runtime,
            initialWoundedTroops = mapOf(target to targetWounded),
        )
        return Fixture(state, BattleStateChangeApplier(state))
    }

    private fun spec(
        effectId: Int,
        category: EffectCategory = EffectCategory.BENEFICIAL,
        rounds: Int = 3,
        potency: TypedBattlePotency.Resolved = TypedBattlePotency.flat(1),
    ) = PersistentEffectSpec(
        source = source,
        target = target,
        rootSkillId = 10,
        skillId = 10,
        skillKind = SkillKind.COMMAND,
        rawSkillType = 2,
        detailId = 1_000 + effectId,
        effectId = effectId,
        category = category,
        conflict = 0,
        replaceType = 0,
        bindFlag = 0,
        maxStacks = 1,
        delayRound = 0,
        delayHit = 0,
        availableRounds = rounds,
        availableHit = 0,
        clearPerHit = false,
        startBoundary = EffectStartBoundary.IMMEDIATE,
        potency = potency,
    )

    private data class Fixture(
        val state: SkillBattleState,
        val applier: BattleStateChangeApplier,
    )
}
