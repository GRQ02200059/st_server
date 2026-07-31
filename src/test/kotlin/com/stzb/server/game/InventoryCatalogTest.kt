package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InventoryCatalogTest {
    @Test
    fun `catalog unlocks every configured item and creates requested weapon copies`() {
        val normalWeapons = InventoryCatalog.normalWeapons()
        val hongjiCopies = InventoryCatalog.hongjiCopies()
        val items = InventoryCatalog.items()

        assertEquals(114, normalWeapons.size)
        assertEquals(114, normalWeapons.map(InventoryGearDefinition::gearId).distinct().size)
        assertTrue(normalWeapons.all { it.featureTier.advance == 1 })
        assertEquals(50, hongjiCopies.size)
        assertTrue(hongjiCopies.all { it.featureTier.advance == 1 })
        assertEquals(50, hongjiCopies.map(InventoryGearDefinition::uid).distinct().size)

        assertEquals(111, items.size)
        assertEquals(111, items.map(InventoryItemDefinition::itemId).distinct().size)
        assertTrue(items.all { it.repoType >= 0 })
    }
}
