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
    fun `cooldown prevents consecutive casts`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100036))
        val source = hero(100036, skillIds = listOf(200070))
        val enemies = BattleTeam(listOf(hero(1, position = 0)))
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
}
