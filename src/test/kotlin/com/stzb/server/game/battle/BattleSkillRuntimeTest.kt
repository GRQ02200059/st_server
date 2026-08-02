package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BattleSkillRuntimeTest {
    private val repo = BattleConfigRepository.loadDefault()
    private val runtime = BattleSkillRuntime(repo)

    @Test
    fun `prepared skill waits before dealing damage`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100479))
        val source = hero(100479, skillIds = listOf(200031))
        val enemies = BattleTeam(listOf(hero(1, position = 0)))
        val allies = BattleTeam(listOf(source))
        val state = SkillRuntimeState()

        val first = runtime.tryAct(1, sourceRef, source, enemies, allies, FixedBattleRandom(0), state)
        val second = runtime.tryAct(2, sourceRef, source, enemies, allies, FixedBattleRandom(0), state)

        assertNotNull(first)
        assertTrue(first.events.none { it is BattleEvent.SkillDamage })
        assertNotNull(second)
        assertTrue(second.events.any { it is BattleEvent.SkillDamage })
    }

    @Test
    fun `prepared area skill hits the csv attack max targets`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100479))
        val source = hero(100479, skillIds = listOf(200031))
        val enemies = BattleTeam(
            listOf(
                hero(1, position = 0),
                hero(2, position = 1),
                hero(3, position = 2),
            ),
        )
        val state = SkillRuntimeState()

        runtime.tryAct(1, sourceRef, source, enemies, BattleTeam(listOf(source)), FixedBattleRandom(0), state)
        val result = runtime.tryAct(
            2,
            sourceRef,
            source,
            enemies,
            BattleTeam(listOf(source)),
            FixedBattleRandom(0),
            state,
        )

        assertNotNull(result)
        assertEquals(3, result.events.filterIsInstance<BattleEvent.SkillDamage>().size)
        assertEquals(3, result.events.filterIsInstance<BattleEvent.StatusApplied>().size)
    }

    @Test
    fun `active skill only targets enemies inside its csv hit range`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100001))
        val source = hero(100001, position = 0, skillIds = listOf(200097))
        val result = runtime.tryAct(
            1,
            sourceRef,
            source,
            BattleTeam(listOf(hero(1, position = 2))),
            BattleTeam(listOf(source)),
            FixedBattleRandom(0),
            SkillRuntimeState(),
        )

        assertNotNull(result)
        assertTrue(result.events.none { it is BattleEvent.SkillDamage })
    }

    @Test
    fun `prepared skill completes without rolling activation again`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100479))
        val source = hero(100479, skillIds = listOf(200031))
        val enemies = BattleTeam(listOf(hero(1, position = 0)))
        val allies = BattleTeam(listOf(source))
        val state = SkillRuntimeState()
        val random = SequenceBattleRandom(0, 99)

        val first = runtime.tryAct(1, sourceRef, source, enemies, allies, random, state)
        val second = runtime.tryAct(2, sourceRef, source, enemies, allies, random, state)

        assertNotNull(first)
        assertEquals(1, random.calls)
        assertTrue(first.events.any {
            it is BattleEvent.SkillPreparationStarted && it.skillId == 200031 && it.readyRound == 2
        })
        assertNotNull(second)
        assertTrue(second.events.any { it is BattleEvent.SkillDamage && it.skillId == 200031 })
    }

    @Test
    fun `cooldown prevents consecutive casts`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(100036))
        val source = hero(100036, position = 2, skillIds = listOf(200070))
        val enemies = BattleTeam(listOf(hero(1, position = 2)))
        val allies = BattleTeam(listOf(source))
        val state = SkillRuntimeState(defaultCooldownRounds = 1)

        val first = runtime.tryAct(1, sourceRef, source, enemies, allies, FixedBattleRandom(0), state)
        val second = runtime.tryAct(2, sourceRef, source, enemies, allies, FixedBattleRandom(0), state)

        assertNotNull(first)
        assertTrue(first.events.any { it is BattleEvent.SkillDamage })
        assertEquals(null, second)
    }

    @Test
    fun `recovery status and unsupported effects are emitted`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
        val source = hero(100017, skillIds = listOf(200001))
        val enemies = BattleTeam(listOf(hero(1, position = 0)))
        val allies = BattleTeam(listOf(source.copy(troops = 500), hero(2, position = 1, troops = 400)))
        val state = SkillRuntimeState()

        val result = runtime.tryAct(1, sourceRef, source, enemies, allies, FixedBattleRandom(0), state)
        val disorder = runtime.tryAct(1, sourceRef, source.copy(skillIds = listOf(200002)), enemies, allies, FixedBattleRandom(0), SkillRuntimeState())

        assertNotNull(result)
        assertTrue(result.events.any { it is BattleEvent.Recovery })
        assertNotNull(disorder)
        assertTrue(disorder.events.any { it is BattleEvent.StatusApplied })
    }

    @Test
    fun `recovery and status events retain the casting skill id`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
        val source = hero(100017, skillIds = listOf(200001))
        val allies = BattleTeam(listOf(source.copy(troops = 500), hero(2, position = 1, troops = 400)))
        val recovery = runtime.tryAct(
            1,
            sourceRef,
            source,
            BattleTeam(listOf(hero(1, position = 0))),
            allies,
            FixedBattleRandom(0),
            SkillRuntimeState(),
        )!!.events.filterIsInstance<BattleEvent.Recovery>()

        val disorder = runtime.tryAct(
            1,
            sourceRef,
            source.copy(skillIds = listOf(200002)),
            BattleTeam(listOf(hero(1, position = 0))),
            allies,
            FixedBattleRandom(0),
            SkillRuntimeState(),
        )!!.events.filterIsInstance<BattleEvent.StatusApplied>()

        assertTrue(recovery.isNotEmpty())
        assertTrue(recovery.all { it.skillId == 200001 })
        assertTrue(disorder.isNotEmpty())
        assertTrue(disorder.all { it.skillId == 200002 })
    }

    @Test
    fun `disorder rolls each of its three effects independently from csv candidates`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100002))
        val source = hero(100002, skillIds = listOf(200002))
        val result = runtime.tryAct(
            1,
            sourceRef,
            source,
            BattleTeam(listOf(hero(1, position = 0))),
            BattleTeam(listOf(source)),
            SequenceBattleRandom(0, 1, 2, 3),
            SkillRuntimeState(),
        )

        assertNotNull(result)
        assertEquals(
            listOf(BattleStatus.PANIC, BattleStatus.BURN, BattleStatus.HEX),
            result.events.filterIsInstance<BattleEvent.StatusApplied>().map { it.status },
        )
    }

    @Test
    fun `a failed skill activation is not retried in the same round`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
        val source = hero(100017, skillIds = listOf(200031))
        val random = SequenceBattleRandom(99, 0)
        val state = SkillRuntimeState()

        val first = runtime.tryAct(
            1,
            sourceRef,
            source,
            BattleTeam(listOf(hero(1))),
            BattleTeam(listOf(source)),
            random,
            state,
        )
        val retry = runtime.tryAct(
            1,
            sourceRef,
            source,
            BattleTeam(listOf(hero(1))),
            BattleTeam(listOf(source)),
            random,
            state,
        )

        assertEquals(null, first)
        assertEquals(null, retry)
        assertEquals(1, random.calls)
    }

    @Test
    fun `hesitation interruption cancels an in progress prepared skill`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
        val source = hero(100017, skillIds = listOf(200031))
        val state = SkillRuntimeState()
        val enemies = BattleTeam(listOf(hero(1)))
        val allies = BattleTeam(listOf(source))

        val preparation = runtime.tryAct(
            1, sourceRef, source, enemies, allies, FixedBattleRandom(0), state,
        )
        state.interruptPreparations(sourceRef)
        val nextAction = runtime.tryAct(
            2, sourceRef, source, enemies, allies, FixedBattleRandom(0), state,
        )

        assertTrue(preparation!!.events.any { it is BattleEvent.SkillPreparationStarted })
        assertTrue(nextAction!!.events.any { it is BattleEvent.SkillPreparationStarted })
        assertTrue(nextAction.events.none { it is BattleEvent.SkillDamage })
    }

    @Test
    fun `zhuge pouch applies its beneficial effects to allies rather than enemies`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
        val source = hero(100017, position = 0, troops = 500, skillIds = listOf(200017))
        val ally = hero(100021, position = 1, troops = 500)
        val enemy = hero(1, position = 0, troops = 1_000)

        val result = runtime.tryAct(
            round = 1,
            sourceRef = sourceRef,
            source = source,
            targets = BattleTeam(listOf(enemy)),
            allies = BattleTeam(listOf(source, ally)),
            random = FixedBattleRandom(0),
            state = SkillRuntimeState(),
        )

        assertNotNull(result)
        val statuses = result.events.filterIsInstance<BattleEvent.StatusApplied>()
        assertTrue(statuses.none { it.target.side == Side.DEFENDER })
        assertTrue(statuses.any {
            it.target.heroId == source.id &&
                it.status == BattleStatus.STRATEGY_DAMAGE_TAKEN_REDUCED
        })
        assertTrue(statuses.any {
            it.target.heroId == ally.id &&
                it.status == BattleStatus.PHYSICAL_DAMAGE_DEALT_INCREASED
        })
        assertTrue(statuses.any {
            it.target.heroId == source.id &&
                it.status == BattleStatus.FIRST_ACTION
        })
    }

    @Test
    fun `morale and probability modifiers affect active skill activation rate`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
        val enemies = BattleTeam(listOf(hero(1)))
        val normal = hero(100017, skillIds = listOf(200017)).copy(morale = 50)
        val boosted = normal.copy(
            modifiers = listOf(BattleModifier.SkillProbabilityPercent(20)),
        )

        val failed = runtime.tryAct(
            1, sourceRef, normal, enemies, BattleTeam(listOf(normal)),
            FixedBattleRandom(30), SkillRuntimeState(),
        )
        val succeeded = runtime.tryAct(
            1, sourceRef, boosted, enemies, BattleTeam(listOf(boosted)),
            FixedBattleRandom(30), SkillRuntimeState(),
        )

        assertEquals(null, failed)
        assertNotNull(succeeded)
    }

    @Test
    fun `legacy runtime applies probability modifiers before morale scaling`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100024))
        val source = hero(100024, skillIds = listOf(200684)).copy(
            morale = 121,
            modifiers = listOf(BattleModifier.SkillProbabilityPercent(10)),
        )

        val result = runtime.tryAct(
            round = 1,
            sourceRef = sourceRef,
            source = source,
            targets = BattleTeam(listOf(hero(1))),
            allies = BattleTeam(listOf(source)),
            random = FixedBattleRandom(55),
            state = SkillRuntimeState(),
        )

        assertNotNull(result)
    }

    @Test
    fun `physical skill damage follows the reference troop and attack curve`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100021))
        val source = hero(100021, troops = 10_000, skillIds = listOf(200021)).copy(
            maxTroops = 10_000,
            stats = BattleStats(attack = 200, defense = 100, strategy = 100, speed = 100, siege = 0, hitRange = 5),
        )
        val enemy = hero(1, troops = 10_000).copy(
            maxTroops = 10_000,
            stats = BattleStats(attack = 100, defense = 100, strategy = 100, speed = 50, siege = 0, hitRange = 3),
        )

        val result = runtime.tryAct(
            1, sourceRef, source, BattleTeam(listOf(enemy)), BattleTeam(listOf(source)),
            FixedBattleRandom(0), SkillRuntimeState(),
        )

        assertNotNull(result)
        assertEquals(
            listOf(840, 840),
            result.events.filterIsInstance<BattleEvent.SkillDamage>().map { it.damage },
        )
    }

    @Test
    fun `strategy skill damage follows the reference troop and strategy curve`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
        val source = hero(100017, troops = 10_000, skillIds = listOf(200031)).copy(
            maxTroops = 10_000,
            stats = BattleStats(attack = 100, defense = 100, strategy = 200, speed = 100, siege = 0, hitRange = 5),
        )
        val enemy = hero(1, troops = 10_000).copy(
            maxTroops = 10_000,
            stats = BattleStats(attack = 100, defense = 100, strategy = 100, speed = 50, siege = 0, hitRange = 3),
        )
        val state = SkillRuntimeState()

        runtime.tryAct(
            1, sourceRef, source, BattleTeam(listOf(enemy)), BattleTeam(listOf(source)),
            FixedBattleRandom(0), state,
        )
        val result = runtime.tryAct(
            2, sourceRef, source, BattleTeam(listOf(enemy)), BattleTeam(listOf(source)),
            FixedBattleRandom(0), state,
        )

        assertNotNull(result)
        assertEquals(
            303,
            result.events.filterIsInstance<BattleEvent.SkillDamage>().single().damage,
        )
    }

    private fun hero(
        heroId: Int,
        position: Int = 0,
        troops: Int = 1000,
        skillIds: List<Int> = emptyList(),
    ): BattleHero =
        BattleHero(
            id = BattleHeroId(heroId),
            position = position,
            stats = BattleStats(attack = 100, defense = 50, strategy = 90, speed = 60, siege = 0, hitRange = 3),
            troops = troops,
            maxTroops = 1000,
            skillIds = skillIds,
        )

    private class SequenceBattleRandom(
        vararg values: Int,
    ) : BattleRandom {
        private val values = values.toList()
        var calls: Int = 0
            private set

        override fun nextInt(bound: Int): Int =
            values.getOrElse(calls++) { bound - 1 }.coerceIn(0, bound - 1)
    }
}
