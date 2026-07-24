package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BattleSkillInterpreterTest {
    private val repo = BattleConfigRepository.loadDefault()
    private val interpreter = BattleSkillInterpreter(repo)

    @Test
    fun `applies attribute buff effects from skill details`() {
        val team = BattleTeam(
            listOf(
                hero(heroId = 1, position = 0, attack = 100, defense = 80, strategy = 70, speed = 60, skillIds = listOf(200072)),
                hero(heroId = 2, position = 1, attack = 50, defense = 50, strategy = 50, speed = 50),
            ),
        )

        val buffed = interpreter.applyPreBattle(team)
        val source = buffed.heroes.first { it.position == 0 }
        val ally = buffed.heroes.first { it.position == 1 }

        assertEquals(120, source.stats.attack)
        assertEquals(100, source.stats.defense)
        assertEquals(90, source.stats.strategy)
        assertEquals(80, source.stats.speed)
        assertEquals(70, ally.stats.attack)
    }

    @Test
    fun `casts physical damage skill when probability roll succeeds`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100479))
        val source = hero(heroId = 100479, position = 0, attack = 100, defense = 50, strategy = 20, speed = 50, skillIds = listOf(200012))
        val defender = BattleTeam(listOf(hero(heroId = 3, position = 0, attack = 10, defense = 20, strategy = 10, speed = 10)))

        val result = interpreter.tryCastActiveSkill(
            round = 1,
            sourceRef = sourceRef,
            source = source,
            enemies = defender,
            random = FixedBattleRandom(0),
        )

        assertNotNull(result)
        assertEquals(200012, result.skillId)
        assertTrue(result.events.any { it is BattleEvent.SkillDamage })
        assertTrue(result.updatedEnemies.heroes.single().troops < 1000)
    }

    @Test
    fun `casts strategy damage skill using strategy stat`() {
        val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100001))
        val source = hero(heroId = 100001, position = 0, attack = 10, defense = 20, strategy = 120, speed = 50, skillIds = listOf(200007))
        val defender = BattleTeam(listOf(hero(heroId = 3, position = 0, attack = 10, defense = 20, strategy = 30, speed = 10)))

        val result = interpreter.tryCastActiveSkill(
            round = 1,
            sourceRef = sourceRef,
            source = source,
            enemies = defender,
            random = FixedBattleRandom(0),
        )

        assertNotNull(result)
        assertEquals(200007, result.skillId)
        val damage = result.events.filterIsInstance<BattleEvent.SkillDamage>().single()
        assertEquals(302, damage.effectId)
        assertTrue(damage.damage > 0)
    }

    private fun hero(
        heroId: Int,
        position: Int,
        attack: Int,
        defense: Int,
        strategy: Int,
        speed: Int,
        skillIds: List<Int> = emptyList(),
    ): BattleHero =
        BattleHero(
            id = BattleHeroId(heroId),
            position = position,
            stats = BattleStats(
                attack = attack,
                defense = defense,
                strategy = strategy,
                speed = speed,
                siege = 0,
                hitRange = 3,
            ),
            troops = 1000,
            skillIds = skillIds,
        )
}
