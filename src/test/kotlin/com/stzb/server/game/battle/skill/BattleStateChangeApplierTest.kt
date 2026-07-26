package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleEffectValueUnit
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
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
import kotlin.test.assertTrue

class BattleStateChangeApplierTest {
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
        assertEquals(20, fixture.state.view.state(target)?.troops)
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
    fun `recovery is capped by live maximum troops`() {
        val fixture = fixture(targetTroops = 990)

        fixture.applier.apply(
            listOf(TroopRecoveryChange(source, target, 99, 1_089, 10, 401)),
            round = 0,
        )

        assertEquals(1_000, fixture.state.view.state(target)?.troops)
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
    fun `ongoing damage ticks from live state and expires with its effect`() {
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
        val afterOne = fixture.state.view.state(target)?.troops
        assertTrue(roundOne.outputs.any { it is BattleStateOutput.DamageDealt })
        fixture.applier.onRoundEnd(1)
        fixture.applier.onRoundStart(2)
        val afterTwo = fixture.state.view.state(target)?.troops
        fixture.applier.onRoundEnd(2)
        val roundThree = fixture.applier.onRoundStart(3)

        assertTrue(requireNotNull(afterTwo) < requireNotNull(afterOne))
        assertTrue(roundThree.outputs.none { it is BattleStateOutput.DamageDealt })
        assertTrue(fixture.state.effectStore.effectsFor(target).none { it.effectId == 304 })
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
