package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleFormationCalculatorTest {
    private val config = BattleConfigRepository.loadDefault()
    private val calculator = BattleFormationCalculator(config)

    @Test
    fun `formation applies growth allocation advance and camp attributes`() {
        val base = calculator.calculate(
            listOf(BattleHeroSpec(heroId = 100479, position = 0, troops = 1000, level = 1)),
        ).heroes.single()

        val developed = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1000,
                    level = 40,
                    attributePoints = BattleStats(
                        attack = 40,
                        defense = 0,
                        strategy = 0,
                        speed = 0,
                        siege = 0,
                        hitRange = 0,
                    ),
                    advanceLevel = 5,
                ),
            ),
        ).heroes.single()
        val hero = config.hero(100479)!!

        assertEquals(hero.stats.attack, base.stats.attack)
        assertEquals(hero.stats.defense, base.stats.defense)
        assertEquals(hero.stats.strategy, base.stats.strategy)
        assertEquals(hero.stats.speed, base.stats.speed)
        assertEquals(
            hero.growth.precise(BattleStat.ATTACK) * 39 + 40,
            developed.stats.precise(BattleStat.ATTACK) - base.stats.precise(BattleStat.ATTACK),
            0.001,
        )
        assertEquals(
            hero.growth.precise(BattleStat.SPEED) * 39,
            developed.stats.precise(BattleStat.SPEED) - base.stats.precise(BattleStat.SPEED),
            0.001,
        )
        assertEquals(2_000, developed.maxTroops)
        assertEquals(5, developed.advanceLevel)
        assertEquals(100, developed.morale)
    }

    @Test
    fun `formation uses verified country and troop sources instead of army-extra ids`() {
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(heroId = 100683, position = 0, troops = 1_000),
                BattleHeroSpec(heroId = 100672, position = 1, troops = 1_000),
                BattleHeroSpec(heroId = 100025, position = 2, troops = 1_000),
            ),
        )

        assertEquals(listOf(5), team.armyBonuses.map { it.id })
        assertEquals(
            setOf(295020, 291001),
            team.preparationEffects.mapTo(mutableSetOf()) { it.sourceId },
        )
        assertEquals(
            setOf(0, 1),
            team.preparationEffects
                .filter { it.sourceId == 291001 }
                .mapTo(mutableSetOf()) { it.targetPosition },
        )
        assertEquals(
            setOf(BattleStat.ATTACK, BattleStat.SPEED),
            team.preparationEffects
                .filter { it.sourceId == 291001 }
                .mapTo(mutableSetOf()) { it.stat },
        )
        assertEquals(false, team.preparationEffects.any { it.sourceId == 291005 })
    }

    @Test
    fun `two archers receive the verified five percent defense and speed bonus`() {
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(heroId = 100028, position = 0, troops = 1_000, level = 45),
                BattleHeroSpec(heroId = 100035, position = 1, troops = 1_000, level = 43),
                BattleHeroSpec(heroId = 100023, position = 2, troops = 1_000, level = 43),
            ),
        )

        val effects = team.preparationEffects.filter { it.sourceId == 291005 }

        assertEquals(setOf(0, 1), effects.mapTo(mutableSetOf()) { it.targetPosition })
        assertEquals(
            setOf(BattleStat.DEFENSE, BattleStat.SPEED),
            effects.mapTo(mutableSetOf()) { it.stat },
        )
        assertEquals(setOf(5), effects.mapTo(mutableSetOf()) { it.strength })
        assertEquals(true, effects.all { it.deltaExact > 0.0 })
    }

    @Test
    fun `two matching countries grant the country bonus to the whole team`() {
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(heroId = 100028, position = 0, troops = 1_000, level = 45),
                BattleHeroSpec(heroId = 100035, position = 1, troops = 1_000, level = 43),
                BattleHeroSpec(heroId = 100023, position = 2, troops = 1_000, level = 43),
            ),
        )

        val effects = team.preparationEffects.filter { it.sourceId == 295020 }

        assertEquals(setOf(0, 1, 2), effects.mapTo(mutableSetOf()) { it.targetPosition })
        assertEquals(12, effects.size)
    }

    @Test
    fun `active hero feature applies its configured flat attributes during surface preparation`() {
        val baseline = calculator.calculate(
            listOf(BattleHeroSpec(heroId = 100648, position = 1, troops = 1_000, level = 44)),
        ).heroes.single()
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100648,
                    position = 1,
                    troops = 1_000,
                    level = 44,
                    surfaceSkillId = 285314,
                ),
            ),
        )
        val hero = team.heroes.single()
        val effects = team.preparationEffects.filter { it.sourceId == 285314 }

        assertEquals(
            listOf(BattleStat.SPEED, BattleStat.DEFENSE, BattleStat.STRATEGY),
            effects.map { it.stat },
        )
        assertEquals(listOf(2.0, 5.0, 2.0), effects.map { it.deltaExact })
        assertEquals(setOf(BattlePreparationStage.SURFACE), effects.mapTo(mutableSetOf()) { it.stage })
        assertEquals(baseline.stats.speed + 2, hero.stats.speed)
        assertEquals(baseline.stats.defense + 5, hero.stats.defense)
        assertEquals(baseline.stats.strategy + 2, hero.stats.strategy)
    }

    @Test
    fun `non battle hero feature placeholder details do not change battle attributes`() {
        val baseline = calculator.calculate(
            listOf(BattleHeroSpec(heroId = 100648, position = 1, troops = 1_000, level = 44)),
        ).heroes.single()
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100648,
                    position = 1,
                    troops = 1_000,
                    level = 44,
                    surfaceSkillId = 281015,
                ),
            ),
        )

        assertEquals(baseline.stats, team.heroes.single().stats)
        assertEquals(emptyList(), team.preparationEffects.filter { it.sourceId == 281015 })
    }

    @Test
    fun `active hero feature applies configured damage modifier and preparation action`() {
        val team = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100648,
                    position = 1,
                    troops = 1_000,
                    surfaceSkillId = 281003,
                ),
            ),
        )
        val hero = team.heroes.single()

        assertEquals(
            listOf(BattleModifier.DamageDealtPercent(school = DamageSchool.STRATEGY, percent = 5)),
            hero.modifiers.filterIsInstance<BattleModifier.DamageDealtPercent>(),
        )
        assertEquals(
            listOf(
                BattlePreparationAction(
                    stage = BattlePreparationStage.SURFACE,
                    sourceId = 281003,
                    sourcePosition = 1,
                    targetPosition = 1,
                    actionId = "0s".toInt(36),
                    actionParameter = 533,
                    compactStatusAction = true,
                ),
            ),
            team.preparationActions,
        )
    }

    @Test
    fun `equipment recovery taken effect is preserved as a runtime modifier`() {
        val hero = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100648,
                    position = 1,
                    troops = 1_000,
                    equipmentSkillIds = listOf(400048),
                    equipmentSkillLevels = listOf(1),
                ),
            ),
        ).heroes.single()

        assertEquals(
            BattleModifier.RecoveryTakenPercent(25),
            hero.modifiers.filterIsInstance<BattleModifier.RecoveryTakenPercent>().single(),
        )
    }

    @Test
    fun `equipment feature recovery dealt effect uses its feature level`() {
        val hero = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100648,
                    position = 1,
                    troops = 1_000,
                    equipmentFeatureSkillIds = listOf(450016),
                    equipmentFeatureSkillLevels = listOf(7),
                ),
            ),
        ).heroes.single()

        assertEquals(
            BattleModifier.RecoveryDealtPercent(7),
            hero.modifiers.filterIsInstance<BattleModifier.RecoveryDealtPercent>().single(),
        )
    }

    @Test
    fun `equipment damage modifiers preserve configured tags and command origin`() {
        val hero = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100648,
                    position = 1,
                    troops = 1_000,
                    equipmentSkillIds = listOf(400042),
                    equipmentSkillLevels = listOf(1),
                    equipmentFeatureSkillIds = listOf(450025),
                    equipmentFeatureSkillLevels = listOf(7),
                ),
            ),
        ).heroes.single()

        assertEquals(
            setOf<Pair<String?, Int>>(
                "PANIC" to 10,
                "BURN" to 10,
                "HEX" to 10,
                "FIRE" to 10,
            ),
            hero.modifiers
                .filterIsInstance<BattleModifier.DamageDealtPercent>()
                .mapTo(mutableSetOf()) { modifier ->
                    modifier.tag?.name to modifier.percent
                },
        )
        assertEquals(
            BattleModifier.DamageTakenPercent(
                origin = DamageOrigin.COMMAND,
                percent = -7,
            ),
            hero.modifiers.filterIsInstance<BattleModifier.DamageTakenPercent>().single(),
        )
        assertEquals(
            5,
            hero.modifiers.count {
                it is BattleModifier.DamageDealtPercent ||
                    it is BattleModifier.DamageTakenPercent
            },
        )
    }

    @Test
    fun `equipment control damage reductions retain their required statuses`() {
        listOf(400046, 400080, 400107).forEach { skillId ->
            val hero = calculator.calculate(
                listOf(
                    BattleHeroSpec(
                        heroId = 100648,
                        position = 1,
                        troops = 1_000,
                        equipmentSkillIds = listOf(skillId),
                        equipmentSkillLevels = listOf(6),
                    ),
                ),
            ).heroes.single()

            assertEquals(
                setOf<Pair<BattleStatus?, Int>>(
                    BattleStatus.CONFUSION to -6,
                    BattleStatus.HESITATION to -6,
                    BattleStatus.BERSERK to -6,
                    BattleStatus.DISARM to -6,
                ),
                hero.modifiers
                    .filterIsInstance<BattleModifier.DamageTakenPercent>()
                    .mapTo(mutableSetOf()) { modifier ->
                        modifier.requiredStatus to modifier.percent
                    },
                "skill=$skillId modifiers=${hero.modifiers}",
            )
        }
    }

    @Test
    fun `troop special damage modifiers execute through the battle runtime`() {
        val heroType = requireNotNull(
            com.stzb.server.game.ClientTroopTypeRepository.loadDefault()
                .heroTypeForSkillIds(listOf(296321)),
        )
        val attacker = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100648,
                    position = 1,
                    troops = 1_000,
                    heroType = heroType,
                ),
            ),
        )
        assertEquals(
            true,
            296321 in attacker.heroes.single().skillIds,
            "skills=${attacker.heroes.single().skillIds}",
        )

        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = attacker,
                defender = calculator.calculate(
                    listOf(
                        BattleHeroSpec(
                            heroId = 100479,
                            position = 1,
                            troops = 1_000,
                        ),
                    ),
                ),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        assertEquals(
            setOf<Pair<String?, Int>>(
                "BURN" to 15,
                "FIRE" to 15,
                "SHAKE" to -30,
                "PANIC" to -30,
                "HEX" to -30,
            ),
            result.attacker.heroes.single().modifiers
                .filterIsInstance<BattleModifier.DamageTakenPercent>()
                .filter { it.tag != null }
                .mapTo(mutableSetOf()) { modifier ->
                    modifier.tag?.name to modifier.percent
                },
        )
    }

    @Test
    fun `formation preserves resolved hero type and projects configured troop counter profile`() {
        val converted = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1_000,
                    heroType = 21,
                ),
            ),
        ).heroes.single()
        val archer = calculator.calculate(
            listOf(
                BattleHeroSpec(
                    heroId = 100479,
                    position = 0,
                    troops = 1_000,
                    heroType = 1,
                ),
            ),
        ).heroes.single()

        assertEquals(21, converted.heroType)
        assertTrue(
            BattleModifier.TroopCounterDealtPercent(
                targetHeroType = 22,
                percent = 30,
            ) in archer.modifiers,
            "modifiers=${archer.modifiers}",
        )
        assertTrue(
            BattleModifier.TroopCounterTakenPercent(
                sourceHeroType = 22,
                percent = -30,
            ) in archer.modifiers,
            "modifiers=${archer.modifiers}",
        )
    }
}
