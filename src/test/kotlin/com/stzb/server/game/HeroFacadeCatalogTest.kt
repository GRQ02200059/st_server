package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroFacadeCatalogTest {
    @Test
    fun `catalog contains normal and achievement facades but excludes ordinary heroes`() {
        val facadeIds = HeroFacadeCatalog.all().map { it.facadeHeroId }

        assertTrue(100534 in facadeIds)
        assertTrue(101300 in facadeIds)
        assertFalse(100067 in facadeIds)
        assertTrue(facadeIds.size > 700)
        assertTrue(facadeIds.distinct().size == facadeIds.size)
    }

    @Test
    fun `catalog validates base and extended hero bindings`() {
        assertTrue(HeroFacadeCatalog.canUse(facadeHeroId = 100534, baseHeroId = 100067))
        assertFalse(HeroFacadeCatalog.canUse(facadeHeroId = 100534, baseHeroId = 100003))
    }
}
