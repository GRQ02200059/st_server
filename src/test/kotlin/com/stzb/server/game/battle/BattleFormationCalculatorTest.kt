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

        assertEquals(hero.stats.attack, base.stats.attack)
        assertEquals(hero.stats.defense, base.stats.defense)
        assertEquals(hero.stats.strategy, base.stats.strategy)
        assertEquals(hero.stats.speed, base.stats.speed)
        assertEquals(
            hero.growth.precise(BattleStat.ATTACK) * 39 + 40,
            developed.stats.precise(BattleStat.ATTACK) - base.stats.precise(BattleStat.ATTACK),
            0.001,
        )
        assertEquals(
            hero.growth.precise(BattleStat.SPEED) * 39,
            developed.stats.precise(BattleStat.SPEED) - base.stats.precise(BattleStat.SPEED),
            0.001,
        )
        assertEquals(2_000, developed.maxTroops)
        assertEquals(5, developed.advanceLevel)
        assertEquals(100, developed.morale)
    }

    @Test
    fun `formation uses verified country and troop sources instead of army-extra ids`() {
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(heroId = 100683, position = 0, troops = 1_000),
                BattleHeroSpec(heroId = 100672, position = 1, troops = 1_000),
                BattleHeroSpec(heroId = 100025, position = 2, troops = 1_000),
            ),
        )

        assertEquals(listOf(5), team.armyBonuses.map { it.id })
        assertEquals(
            setOf(295020, 291001),
            team.preparationEffects.mapTo(mutableSetOf()) { it.sourceId },
        )
        assertEquals(
            setOf(0, 1),
            team.preparationEffects
                .filter { it.sourceId == 291001 }
                .mapTo(mutableSetOf()) { it.targetPosition },
        )
        assertEquals(
            setOf(BattleStat.ATTACK, BattleStat.SPEED),
            team.preparationEffects
                .filter { it.sourceId == 291001 }
                .mapTo(mutableSetOf()) { it.stat },
        )
        assertEquals(false, team.preparationEffects.any { it.sourceId == 291005 })
    }

    @Test
    fun `two archers receive the verified five percent defense and speed bonus`() {
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(heroId = 100028, position = 0, troops = 1_000, level = 45),
                BattleHeroSpec(heroId = 100035, position = 1, troops = 1_000, level = 43),
                BattleHeroSpec(heroId = 100023, position = 2, troops = 1_000, level = 43),
            ),
        )

        val effects = team.preparationEffects.filter { it.sourceId == 291005 }

        assertEquals(setOf(0, 1), effects.mapTo(mutableSetOf()) { it.targetPosition })
        assertEquals(
            setOf(BattleStat.DEFENSE, BattleStat.SPEED),
            effects.mapTo(mutableSetOf()) { it.stat },
        )
        assertEquals(setOf(5), effects.mapTo(mutableSetOf()) { it.strength })
        assertEquals(true, effects.all { it.deltaExact > 0.0 })
    }

    @Test
    fun `two matching countries grant the country bonus to the whole team`() {
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(heroId = 100028, position = 0, troops = 1_000, level = 45),
                BattleHeroSpec(heroId = 100035, position = 1, troops = 1_000, level = 43),
                BattleHeroSpec(heroId = 100023, position = 2, troops = 1_000, level = 43),
            ),
        )

        val effects = team.preparationEffects.filter { it.sourceId == 295020 }

        assertEquals(setOf(0, 1, 2), effects.mapTo(mutableSetOf()) { it.targetPosition })
        assertEquals(12, effects.size)
    }
}
