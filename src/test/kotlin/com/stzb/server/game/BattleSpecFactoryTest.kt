package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals

class BattleSpecFactoryTest {
    @Test
    fun `march hero maps its combat fields including equipment into a spec`() {
        val hero = PlayerMarchHero(
            heroUid = 1,
            position = 2,
            heroId = 100021,
            troops = 9_000,
            level = 40,
            skillIds = listOf(200021, 200012, 0),
            equipmentIds = listOf(1021),
            equipmentFeatureSkillIds = listOf(450019),
            equipmentFeatureSkillLevels = listOf(9),
            advanceNum = 3,
        )

        val spec = BattleSpecFactory.fromMarchHero(hero)

        assertEquals(100021, spec.heroId)
        assertEquals(2, spec.position)
        assertEquals(40, spec.level)
        assertEquals(listOf(1021), spec.equipmentIds)
        assertEquals(listOf(450019), spec.equipmentFeatureSkillIds)
        assertEquals(listOf(9), spec.equipmentFeatureSkillLevels)
        // extra skills drop the first slot and filter zeros, matching PvE builder
        assertEquals(listOf(200012), spec.extraSkillIds)
        assertEquals(3, spec.advanceLevel)
        assertEquals(9_000, spec.troops)
    }

    @Test
    fun `troops are capped at the maximum`() {
        val hero = PlayerMarchHero(
            heroUid = 1, position = 0, heroId = 100021,
            troops = PlayerHero.MAX_TROOPS + 5_000, level = 50, skillIds = listOf(200021),
        )
        assertEquals(PlayerHero.MAX_TROOPS, BattleSpecFactory.fromMarchHero(hero).troops)
    }
}
