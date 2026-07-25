package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals

class BattleFormationCalculatorTest {
    private val config = BattleConfigRepository.loadDefault()
    private val calculator = BattleFormationCalculator(config)

    @Test
    fun `formation applies growth allocation advance and camp attributes`() {
        val base = calculator.calculate(
            listOf(BattleHeroSpec(heroId = 100479, position = 0, troops = 1000, level = 1)),
        ).heroes.single()

        val developed = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    level = 40,
                    attributePoints = BattleStats(
                        attack = 40,
                        defense = 0,
                        strategy = 0,
                        speed = 0,
                        siege = 0,
                        hitRange = 0,
                    ),
                    advanceLevel = 5,
                ),
            ),
        ).heroes.single()
        val hero = config.hero(100479)!!

        assertEquals(hero.stats.attack + 20, base.stats.attack)
        assertEquals(hero.stats.defense + 20, base.stats.defense)
        assertEquals(hero.stats.strategy + 20, base.stats.strategy)
        assertEquals(hero.stats.speed + 20, base.stats.speed)
        assertEquals(hero.growth.attack * 39 + 40, developed.stats.attack - base.stats.attack)
        assertEquals(hero.growth.defense * 39, developed.stats.defense - base.stats.defense)
        assertEquals(hero.growth.strategy * 39, developed.stats.strategy - base.stats.strategy)
        assertEquals(hero.growth.speed * 39, developed.stats.speed - base.stats.speed)
        assertEquals(2_000, developed.maxTroops)
        assertEquals(5, developed.advanceLevel)
        assertEquals(100, developed.morale)
    }
}
