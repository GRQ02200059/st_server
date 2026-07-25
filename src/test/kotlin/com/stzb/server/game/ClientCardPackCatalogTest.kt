package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientCardPackCatalogTest {
    @Test
    fun `catalog merges card packs from every client season`() {
        val packs = ClientCardPackCatalog.allPacks()

        assertEquals(271, packs.size)
        assertEquals(packs.size, packs.map { it.packId }.distinct().size)
        assertTrue(packs.any { it.packId == 281 })
        assertTrue(packs.any { it.packId == 2004 })
    }

    @Test
    fun `every activated pack resolves to its real direct or child hero pool`() {
        val packs = ClientCardPackCatalog.allPacks()

        assertEquals(271, packs.size)
        assertTrue(
            packs.all { pack -> pack.heroIds.all(HeroCatalog.recruitableHeroIds()::contains) },
        )
        assertTrue(
            ClientCardPackCatalog.heroIdsForPack(801)
                .containsAll(ClientCardPackCatalog.heroIdsForPack(901)),
            "container packs must aggregate their configured child pools",
        )
    }

    @Test
    fun `summon uid maps back to the activated pack`() {
        val userId = 42
        val pack = ClientCardPackCatalog.allPacks().first { it.packId == 2004 }
        val summonUid = ClientCardPackCatalog.summonUid(userId, pack.packId)

        assertEquals(2004, ClientCardPackCatalog.packIdForSummonUid(userId, summonUid))
        assertEquals(null, ClientCardPackCatalog.packIdForSummonUid(userId, Int.MAX_VALUE))
    }

    @Test
    fun `unknown card pack never falls back to another pool`() {
        assertTrue(HeroCatalog.fiveStarHeroIdsForCardPack(Int.MAX_VALUE).isEmpty())
    }
}
