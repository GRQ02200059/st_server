package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleEquipmentApplierTest {
    private val equipmentRepo = BattleEquipmentRepository.loadDefault()
    private val configRepo = BattleConfigRepository.loadDefault()

    @Test
    fun `parses equipment stat and damage modifiers`() {
        val equipment = equipmentRepo.equipment(1024) ?: error("missing equipment")
        val modifiers = BattleModifierParser.parseEquipment(equipment, equipmentRepo.features(equipment.featureGroup))

        assertTrue(modifiers.contains(BattleModifier.Stat(BattleStat.ATTACK, 2)))
        assertTrue(modifiers.contains(BattleModifier.Stat(BattleStat.DEFENSE, 3)))
        assertTrue(modifiers.contains(BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 8)))
    }

    @Test
    fun `unknown equipment phrase becomes unsupported modifier`() {
        val equipment = EquipmentConfig(
            id = 999999,
            name = "测试装备",
            quality = "测试",
            type = "测试",
            skillName = "未知",
            skillDescription = "战斗开始后触发一段完全未知的复杂效果",
            featureGroup = 0,
        )

        val modifiers = BattleModifierParser.parseEquipment(equipment, emptyList())

        assertTrue(modifiers.any { it is BattleModifier.Unsupported && it.sourceId == 999999 })
    }

    @Test
    fun `team builder attaches equipment modifiers and applies flat stat bonuses`() {
        val builder = BattleTeamBuilder(configRepo, equipmentRepo)

        val team = builder.build(
            listOf(
                BattleHeroSpec(heroId = 100479, position = 0, troops = 1000, equipmentIds = listOf(1024)),
            ),
        )

        val hero = team.heroes.single()
        assertEquals(listOf(1024), hero.equipmentIds)
        assertEquals(123, hero.stats.attack)
        assertEquals(95, hero.stats.defense)
        assertTrue(hero.modifiers.contains(BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 8)))
    }
}
