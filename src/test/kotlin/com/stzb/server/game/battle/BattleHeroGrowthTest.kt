package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertTrue

class BattleHeroGrowthTest {
    private val repo = BattleConfigRepository.loadDefault()

    @Test
    fun `builder applies hero level growth stats`() {
        val builder = BattleTeamBuilder(repo)

        val levelOne = builder.build(
            listOf(BattleHeroSpec(heroId = 100479, position = 0, troops = 1000, level = 1)),
        ).heroes.single()
        val levelTwenty = builder.build(
            listOf(BattleHeroSpec(heroId = 100479, position = 0, troops = 1000, level = 20)),
        ).heroes.single()

        assertTrue(levelTwenty.stats.attack > levelOne.stats.attack)
        assertTrue(levelTwenty.stats.defense > levelOne.stats.defense)
        assertTrue(levelTwenty.stats.speed > levelOne.stats.speed)
        assertTrue(levelTwenty.level == 20)
    }

    @Test
    fun `first configured heroes are buildable`() {
        val builder = BattleTeamBuilder(repo)

        repo.allHeroIds().take(50).forEach { heroId ->
            val team = builder.build(listOf(BattleHeroSpec(heroId = heroId, position = 0, troops = 100)))
            assertTrue(team.heroes.single().id.value == heroId)
        }
    }
}
