package com.stzb.server.game.battle.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillScopeCatalogTest {
    @Test
    fun `scope contains five star initial and learnable SA skills only`() {
        val scope = SkillScopeCatalog.loadDefault()

        assertEquals(234, scope.fiveStarInitialSkillIds.size)
        assertEquals(83, scope.learnableSaSkillIds.size)
        assertEquals(308, scope.mainSkillIds.size)
        assertTrue(200017 in scope.mainSkillIds)
        assertTrue(200235 in scope.mainSkillIds)
        assertFalse(200100 in scope.learnableSaSkillIds)
    }
}
