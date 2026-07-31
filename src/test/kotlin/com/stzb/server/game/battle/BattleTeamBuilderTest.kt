package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BattleTeamBuilderTest {
    private val repo = BattleConfigRepository.loadDefault()
    private val builder = BattleTeamBuilder(repo)

    @Test
    fun `builds heroes with initial skills from config`() {
        val team = builder.build(
            listOf(
                BattleHeroSpec(heroId = 100479, position = 0, troops = 1000),
                BattleHeroSpec(heroId = 100017, position = 1, troops = 800, extraSkillIds = listOf(200001)),
            ),
        )

        val luBu = team.heroes.first { it.id == BattleHeroId(100479) }
        val zhenLuo = team.heroes.first { it.id == BattleHeroId(100017) }

        assertEquals(listOf(200012), luBu.skillIds)
        assertEquals(listOf(200017, 200001), zhenLuo.skillIds)
    }

    @Test
    fun `applies matching army bonus to all built heroes`() {
        val team = builder.build(
            listOf(
                BattleHeroSpec(heroId = 100352, position = 0, troops = 1000),
                BattleHeroSpec(heroId = 100345, position = 1, troops = 1000),
                BattleHeroSpec(heroId = 100344, position = 2, troops = 1000),
            ),
        )

        val liangXing = team.heroes.first { it.id == BattleHeroId(100352) }

        assertEquals(185.13, liangXing.stats.precise(BattleStat.SPEED), 0.001)
        assertEquals(3, liangXing.stats.hitRange)
        assertTrue(team.armyBonuses.any { it.name == "旗本八骑" })
    }

    @Test
    fun `expands troop feature config into official preparation skill sources`() {
        val team = builder.build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    troopFeatureIds = listOf(3104),
                ),
            ),
        )

        assertEquals(
            listOf(BattlePreparationSource(BattlePreparationStage.TROOP, 296104, 0)),
            team.preparationSources,
        )
        assertEquals(
            listOf(BattleStat.ATTACK, BattleStat.DEFENSE, BattleStat.STRATEGY),
            team.preparationEffects.map { it.stat },
        )
        assertTrue(team.preparationEffects.all {
            it.stage == BattlePreparationStage.TROOP &&
                it.sourceId == 296104 &&
                it.sourcePosition == 0 &&
                it.targetPosition == 0 &&
                !it.percent &&
                it.deltaExact == 6.0
        })
    }

    @Test
    fun `troop feature damage reduction retains both official modifier effects`() {
        val team = builder.build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    troopFeatureIds = listOf(3105),
                ),
            ),
        )

        assertEquals(
            listOf(522, 524),
            team.preparationModifiers.map { it.effectId },
        )
        assertTrue(team.preparationModifiers.all {
            it.sourceId == 296105 &&
                it.sourcePosition == 0 &&
                it.targetPosition == 0 &&
                it.amount == 8
        })
        assertTrue(
            team.heroes.single().modifiers.contains(
                BattleModifier.DamageTakenPercent(percent = -8),
            ),
        )
    }

    @Test
    fun `troop feature damage bonus retains both official modifier effects`() {
        val teams = listOf(
            builder.build(
                listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    troopFeatureIds = listOf(3106),
                ),
                ),
            ),
            builder.build(
                listOf(
                BattleHeroSpec(
                    heroId = 100017,
                    position = 0,
                    troops = 1000,
                    troopFeatureIds = listOf(3206),
                ),
                ),
            ),
        )

        assertEquals(
            setOf(296106, 296132, 296206, 296232),
            teams.flatMap { it.preparationSources }.mapTo(mutableSetOf()) { it.sourceId },
        )
        val modifiers = teams.flatMap { it.preparationModifiers }
            .filter { it.sourceId == 296132 || it.sourceId == 296232 }
        assertEquals(setOf(296132, 296232), modifiers.mapTo(mutableSetOf()) { it.sourceId })
        assertEquals(setOf(531, 533), modifiers.mapTo(mutableSetOf()) { it.effectId })
        assertEquals(4, modifiers.size)
        assertTrue(modifiers.all {
            it.sourcePosition == it.targetPosition && it.amount == 8
        })
        assertTrue(teams.flatMap { it.heroes }.all { hero ->
            hero.modifiers.contains(BattleModifier.DamageDealtPercent(percent = 8))
        })
    }

    @Test
    fun `rejects duplicate positions before building team`() {
        assertFailsWith<IllegalArgumentException> {
            builder.build(
                listOf(
                    BattleHeroSpec(heroId = 100479, position = 0, troops = 1000),
                    BattleHeroSpec(heroId = 100017, position = 0, troops = 1000),
                ),
            )
        }
    }
}
