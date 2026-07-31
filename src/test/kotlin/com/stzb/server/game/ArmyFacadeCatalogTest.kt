package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmyFacadeCatalogTest {
    @Test
    fun `catalog parses every normal facade from the client shop table`() {
        assertEquals(
            listOf(
                101138, 101156, 101174, 101216, 101239, 101342,
                101417, 101460, 101554, 101565, 101611, 101682,
            ),
            ArmyFacadeCatalog.standardFacadeIds(),
        )
        assertEquals(12 * ArmyFacadeCatalog.COPIES_PER_STANDARD_FACADE, ArmyFacadeCatalog.cardCount())
    }

    @Test
    fun `catalog separates supported special facades from unsupported ids`() {
        assertEquals(setOf(101073, 101515, 101618, 101680), ArmyFacadeCatalog.specialFacadeIds())
        assertTrue(ArmyFacadeCatalog.isSpecialFacade(101073))
        assertTrue(ArmyFacadeCatalog.isStandardFacade(101682))
        listOf(101681, 101155, 5100, 999991).forEach { id ->
            assertFalse(ArmyFacadeCatalog.isStandardFacade(id))
            assertFalse(ArmyFacadeCatalog.isSpecialFacade(id))
        }
    }
}
