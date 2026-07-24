package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeroCatalogTest {
    @Test
    fun `hero table is packaged as runtime resource`() {
        val resource = Thread.currentThread().contextClassLoader.getResource("hero_table.csv")

        assertTrue(resource != null)
    }

    @Test
    fun `catalog exposes every recruitable hero with a valid troop type`() {
        val heroIds = HeroCatalog.recruitableHeroIds()

        assertTrue(heroIds.size >= 2_000)
        assertTrue(100003 in heroIds)
        assertTrue(100352 in heroIds)
        assertEquals(3, HeroCatalog.heroType(100003))
        assertTrue(HeroCatalog.heroType(100352) in 1..3)
    }

    @Test
    fun `configured card packs expose only five-star heroes`() {
        val pack281 = HeroCatalog.fiveStarHeroIdsForCardPack(281)

        assertEquals(11, pack281.size)
        assertTrue(100006 in pack281)
        assertTrue(pack281.all { HeroCatalog.heroQuality(it) == 4 })
    }

    @Test
    fun `parent card pack falls back to child five-star union`() {
        val parentPool = HeroCatalog.fiveStarHeroIdsForCardPack(801)

        assertTrue(parentPool.size > 11)
        assertTrue(100008 in parentPool)
        assertTrue(parentPool.all { HeroCatalog.heroQuality(it) == 4 })
    }
}
