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

        assertEquals(84 + 69, liangXing.stats.speed)
        assertEquals(3, liangXing.stats.hitRange)
        assertTrue(team.armyBonuses.any { it.name == "旗本八骑" })
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
