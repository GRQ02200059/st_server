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
    fun `builder does not apply every possible random feature in the equipment group`() {
        val builder = BattleTeamBuilder(configRepo, equipmentRepo)

        val hero = builder.build(
            listOf(BattleHeroSpec(heroId = 100479, position = 0, troops = 1000, equipmentIds = listOf(1024))),
        ).heroes.single()

        assertEquals(103, hero.stats.attack)
        assertEquals(75, hero.stats.defense)
        assertTrue(hero.modifiers.none { it is BattleModifier.SkillProbabilityPercent })
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
        assertEquals(
            listOf(
                BattleEquipmentSlot(400022, 1),
                BattleEquipmentSlot(400023, 1),
                BattleEquipmentSlot(400024, 1),
            ),
            hero.equipment,
        )
        assertEquals(103, hero.stats.attack)
        assertEquals(75, hero.stats.defense)
        assertTrue(hero.modifiers.contains(BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 8)))
    }

    @Test
    fun `equipment fixed stats retain their official skill slot source`() {
        val builder = BattleTeamBuilder(configRepo, equipmentRepo)

        val team = builder.build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    equipmentIds = listOf(1024),
                    equipmentSkillIds = listOf(400022, 400023, 400024),
                    equipmentSkillLevels = listOf(1, 1, 1),
                ),
            ),
        )

        assertEquals(
            listOf(400022, 400023),
            team.preparationEffects.map { it.sourceId },
        )
        assertEquals(
            listOf(BattleStat.ATTACK, BattleStat.DEFENSE),
            team.preparationEffects.map { it.stat },
        )
        assertTrue(team.preparationEffects.all {
            it.stage == BattlePreparationStage.EQUIPMENT &&
                it.sourcePosition == 0 &&
                it.targetPosition == 0 &&
                !it.percent
        })
    }

    @Test
    fun `equipment skill details apply level scaled flat and percent attributes`() {
        val builder = BattleTeamBuilder(configRepo, equipmentRepo)

        val speedBase = configRepo.hero(100479)!!.stats.precise(BattleStat.SPEED)
        val speedTeam = builder.build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    equipmentIds = listOf(1054),
                    equipmentSkillIds = listOf(400052, 400053, 400054),
                    equipmentSkillLevels = listOf(6, 6, 1),
                ),
            ),
        )

        assertEquals(
            12.0,
            speedTeam.preparationEffects
                .single { it.sourceId == 400053 }
                .deltaExact,
        )
        assertEquals(speedBase + 12.0, speedTeam.heroes.single().stats.precise(BattleStat.SPEED))
        assertEquals(
            8,
            speedTeam.preparationEffects
                .single { it.sourceId == 400054 }
                .strength,
        )
        assertEquals(
            true,
            speedTeam.preparationEffects
                .single { it.sourceId == 400054 }
                .percent,
        )
    }

    @Test
    fun `equipment skill details retain preparation and runtime damage modifiers`() {
        val builder = BattleTeamBuilder(configRepo, equipmentRepo)

        val hero = builder.build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    equipmentIds = listOf(1024),
                    equipmentSkillIds = listOf(400022, 400023, 400024),
                    equipmentSkillLevels = listOf(1, 1, 1),
                ),
            ),
        )

        assertEquals(
            listOf(
                BattlePreparationModifier(
                    stage = BattlePreparationStage.EQUIPMENT,
                    sourceId = 400024,
                    sourcePosition = 0,
                    targetPosition = 0,
                    effectId = 531,
                    amount = 8,
                    containerSourceId = 1024,
                ),
            ),
            hero.preparationModifiers,
        )
        assertTrue(
            hero.heroes.single().modifiers.contains(
                BattleModifier.DamageDealtPercent(
                    school = DamageSchool.PHYSICAL,
                    percent = 8,
                ),
            ),
        )
    }

    @Test
    fun `equipment origin damage effects become runtime modifiers`() {
        val builder = BattleTeamBuilder(configRepo, equipmentRepo)

        val hero = builder.build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    equipmentIds = listOf(1102),
                    equipmentSkillIds = listOf(400114, 400115),
                    equipmentSkillLevels = listOf(6, 6),
                ),
            ),
        ).heroes.single()

        assertTrue(
            hero.modifiers.contains(
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.NORMAL, percent = 6),
            ),
        )
        assertTrue(
            hero.modifiers.contains(
                BattleModifier.DamageDealtPercent(origin = DamageOrigin.PURSUIT, percent = 12),
            ),
        )
    }

    @Test
    fun `equipment damage reductions use their configured damage origins`() {
        val hero = BattleTeamBuilder(configRepo, equipmentRepo).build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    equipmentIds = listOf(1048),
                    equipmentSkillIds = listOf(400071, 400047),
                    equipmentSkillLevels = listOf(6, 6),
                ),
            ),
        ).heroes.single()

        assertTrue(
            hero.modifiers.contains(
                BattleModifier.DamageTakenPercent(origin = DamageOrigin.NORMAL, percent = -12),
            ),
        )
        assertTrue(
            hero.modifiers.contains(
                BattleModifier.DamageTakenPercent(origin = DamageOrigin.ACTIVE, percent = -12),
            ),
        )
    }

    @Test
    fun `equipment attack range is applied and retained as a sourced preparation effect`() {
        val baseRange = configRepo.hero(100479)!!.stats.hitRange
        val team = BattleTeamBuilder(configRepo, equipmentRepo).build(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    equipmentIds = listOf(1048),
                    equipmentSkillIds = listOf(400084),
                    equipmentSkillLevels = listOf(1),
                ),
            ),
        )

        assertEquals(baseRange + 1, team.heroes.single().stats.hitRange)
        assertEquals(BattleStat.HIT_RANGE, team.preparationEffects.single().stat)
        assertEquals(400084, team.preparationEffects.single().sourceId)
    }

    @Test
    fun `equipment feature skills become sourced derived preparation actions`() {
        val team = BattleTeamBuilder(configRepo, equipmentRepo).build(
            listOf(
                BattleHeroSpec(
                    heroId = 100683,
                    position = 0,
                    troops = 9_700,
                    equipmentIds = listOf(1102),
                    equipmentSkillIds = listOf(400114),
                    equipmentSkillLevels = listOf(6),
                    equipmentFeatureSkillIds = listOf(450037),
                    equipmentFeatureSkillLevels = listOf(8),
                ),
            ),
        )

        assertEquals(
            BattlePreparationAction(
                stage = BattlePreparationStage.EQUIPMENT,
                sourceId = 450037,
                sourcePosition = 0,
                targetPosition = 0,
                actionId = "8x".toInt(36),
                amountExact = 8.0,
                actionParameter = 200957,
                containerSourceId = 1102,
            ),
            team.preparationActions.single { it.sourceId == 450037 },
        )
    }
}
