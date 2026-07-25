package com.stzb.server.game

import com.stzb.server.game.battle.BattleConfigRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillInventoryCatalogTest {
    @Test
    fun `catalog contains every default client warfare skill and no internal hero skills`() {
        val skills = SkillInventoryCatalog.allSkillIds()

        assertEquals(145, skills.size)
        assertEquals(200099, skills.first())
        assertEquals(201008, skills.last())
        assertTrue(200223 in skills)
        assertTrue(200031 !in skills)
        assertTrue(skills.all { BattleConfigRepository.loadDefault().skill(it) != null })
    }
}
