package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BattleEquipmentRepositoryTest {
    private val repo = BattleEquipmentRepository.loadDefault()

    @Test
    fun `loads equipment config from gear id json`() {
        val equipment = repo.equipment(1024)

        assertNotNull(equipment)
        assertEquals(1024, equipment.id)
        assertEquals("戚", equipment.name)
        assertEquals("稀世", equipment.quality)
        assertEquals(1, equipment.featureGroup)
        assertTrue(equipment.skillDescription.contains("攻击属性提高2.0"))
        assertTrue(equipment.skillDescription.contains("造成的攻击伤害提高8.0%"))
    }

    @Test
    fun `loads equipment feature group definitions`() {
        val features = repo.features(2)

        assertTrue(features.any { it.name == "破敌" })
        assertTrue(features.any { it.description.contains("无视目标") })
        assertTrue(features.any { it.name == "英勇" })
    }

    @Test
    fun `exposes all equipment ids`() {
        val ids = repo.allEquipmentIds()

        assertTrue(1024 in ids)
        assertTrue(ids.size >= 90)
    }
}
