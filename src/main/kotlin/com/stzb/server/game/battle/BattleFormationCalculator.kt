package com.stzb.server.game.battle

import com.stzb.server.game.ClientTroopFeatureRepository
import com.stzb.server.game.ClientEquipmentSkillRepository

/**
 * Computes the immutable battle-entry snapshot. Runtime buffs never mutate
 * these values; they are layered by BattleEffectState.
 */
class BattleFormationCalculator(
    private val config: BattleConfigRepository,
    private val equipmentRepository: BattleEquipmentRepository? = null,
    private val troopFeatureRepository: ClientTroopFeatureRepository =
        ClientTroopFeatureRepository.loadDefault(),
    private val equipmentSkillRepository: ClientEquipmentSkillRepository =
        ClientEquipmentSkillRepository.loadDefault(),
) {
    fun calculate(specs: List<BattleHeroSpec>): BattleTeam {
        require(specs.map { it.position }.distinct().size == specs.size) { "同一部队内站位不能重复" }
        require(specs.all { it.position in 0..2 }) { "武将站位必须在 0..2" }

        val bonuses = config.armyBonusesFor(specs.map { it.heroId })
        val formationBonus = bonuses.fold(BattleStats.ZERO) { total, bonus -> total + bonus.stats }
        val troopFeatureSources = troopFeatureSources(specs)
        val resolvedSpecs = specs.map(::withResolvedEquipmentSkills)
        val preparationModifiers =
            troopFeatureModifiers(troopFeatureSources) +
                equipmentPreparationModifiers(resolvedSpecs)
        val staticEffects =
            countryEffects(resolvedSpecs) +
                troopTypeEffects(resolvedSpecs) +
                troopFeatureEffects(troopFeatureSources) +
                equipmentEffects(resolvedSpecs)
        val heroes = resolvedSpecs.map { spec ->
            val heroConfig = config.hero(spec.heroId) ?: error("未知武将配置: ${spec.heroId}")
            val equipmentModifiers = equipmentModifiers(spec)
            val level = spec.level.coerceAtLeast(1)
            val advance = spec.advanceLevel.coerceAtLeast(0)
            val skillIds = listOf(heroConfig.initialSkillId).filter { it > 0 } + spec.extraSkillIds
            val preStaticStats = heroConfig.stats +
                heroConfig.growth.scale(level - 1) +
                spec.attributePoints +
                formationBonus +
                if (spec.equipmentSkillIds.isEmpty()) {
                    equipmentModifiers.statBonus()
                } else {
                    BattleStats.ZERO
                }
            val finalStats = staticEffects
                .filter { it.targetPosition == spec.position }
                .fold(preStaticStats) { stats, effect ->
                    stats + effect.toStats(stats)
                }
            config.toBattleHero(spec.heroId, spec.position, spec.troops).copy(
                stats = finalStats,
                maxTroops = spec.troops + advance * 200,
                skillIds = skillIds,
                skillLevels = spec.skillLevels.take(skillIds.size).let { levels ->
                    levels + List((skillIds.size - levels.size).coerceAtLeast(0)) { DEFAULT_SKILL_LEVEL }
                },
                troopFeatureIds = spec.troopFeatureIds.take(2),
                equipment = spec.equipmentSkillIds.take(3).mapIndexed { index, skillId ->
                    BattleEquipmentSlot(
                        skillId,
                        spec.equipmentSkillLevels.getOrElse(index) { DEFAULT_EQUIPMENT_LEVEL },
                    )
                },
                equipmentIds = spec.equipmentIds,
                modifiers =
                    equipmentRuntimeModifiers(spec) +
                        troopRuntimeModifiers(troopFeatureSources, spec.position),
                level = level,
                advanceLevel = advance,
                morale = spec.morale.coerceAtLeast(0),
            )
        }
        return BattleTeam(
            heroes = heroes,
            armyBonuses = bonuses,
            preparationSources =
                troopFeatureSources.map { feature ->
                    BattlePreparationSource(
                        stage = BattlePreparationStage.TROOP,
                        sourceId = feature.skillId,
                        sourcePosition = feature.position,
                    )
                } +
                specs.flatMap { spec ->
                    spec.equipmentIds.map { equipmentId ->
                        BattlePreparationSource(
                            stage = BattlePreparationStage.EQUIPMENT,
                            sourceId = equipmentId,
                            sourcePosition = spec.position,
                        )
                    }
                },
            preparationEffects = materializeEffects(staticEffects, resolvedSpecs),
            preparationModifiers = preparationModifiers,
            preparationActions = equipmentPreparationActions(resolvedSpecs),
        )
    }

    private fun withResolvedEquipmentSkills(spec: BattleHeroSpec): BattleHeroSpec {
        if (spec.equipmentSkillIds.isNotEmpty()) return spec
        val slots = spec.equipmentIds.flatMap(equipmentSkillRepository::skillSlots)
        return spec.copy(
            equipmentSkillIds = slots.map(Pair<Int, Int>::first),
            equipmentSkillLevels = slots.map(Pair<Int, Int>::second),
        )
    }

    private fun troopFeatureSources(specs: List<BattleHeroSpec>): List<TroopFeatureSource> =
        specs.flatMap { spec ->
            spec.troopFeatureIds.take(2).flatMap { featureId ->
                troopFeatureRepository.skillIds(featureId).map { skillId ->
                    TroopFeatureSource(spec.position, skillId)
                }
            }
        }

    private fun troopFeatureEffects(sources: List<TroopFeatureSource>): List<StaticEffect> =
        sources.flatMap { source ->
            if (source.skillId != 296_104) return@flatMap emptyList()
            when (source.position) {
                0 -> listOf(BattleStat.ATTACK, BattleStat.DEFENSE, BattleStat.STRATEGY)
                    .map { stat -> fixedTroopEffect(source, stat, 6) }
                1 -> listOf(BattleStat.DEFENSE, BattleStat.STRATEGY)
                    .map { stat -> fixedTroopEffect(source, stat, 10) }
                2 -> listOf(fixedTroopEffect(source, BattleStat.DEFENSE, 24))
                else -> emptyList()
            }
        }

    private fun troopFeatureModifiers(
        sources: List<TroopFeatureSource>,
    ): List<BattlePreparationModifier> =
        sources.flatMap { source ->
            when (source.skillId) {
                296_105 -> listOf(522, 524).map { effectId ->
                    BattlePreparationModifier(
                        stage = BattlePreparationStage.TROOP,
                        sourceId = source.skillId,
                        sourcePosition = source.position,
                        targetPosition = source.position,
                        effectId = effectId,
                        amount = 8,
                    )
                }
                else -> emptyList()
            }
        }

    private fun troopRuntimeModifiers(
        sources: List<TroopFeatureSource>,
        position: Int,
    ): List<BattleModifier> =
        sources
            .filter { it.position == position && it.skillId == 296_105 }
            .map { BattleModifier.DamageTakenPercent(percent = -8) }

    private fun fixedTroopEffect(
        source: TroopFeatureSource,
        stat: BattleStat,
        amount: Int,
    ): StaticEffect =
        StaticEffect(
            stage = BattlePreparationStage.TROOP,
            sourceId = source.skillId,
            sourcePosition = source.position,
            targetPosition = source.position,
            stat = stat,
            strength = amount,
            percent = false,
        )

    private fun equipmentEffects(specs: List<BattleHeroSpec>): List<StaticEffect> =
        specs.flatMap { spec ->
            val equipmentId = spec.equipmentIds.firstOrNull() ?: return@flatMap emptyList()
            spec.equipmentSkillIds.flatMapIndexed { index, skillId ->
                val level = spec.equipmentSkillLevels.getOrElse(index) { DEFAULT_EQUIPMENT_LEVEL }
                config.skillDetails(skillId).mapNotNull { detail ->
                    val stat = detail.effectId.toBattleStat() ?: return@mapNotNull null
                    val percent =
                        detail.effectId != 106 &&
                            kotlin.math.abs(detail.constantParam) >= PERCENT_ATTRIBUTE_SCALE
                    val strength = when {
                        detail.effectId == 106 -> detail.constantParam.toDouble() * level
                        percent -> detail.constantParam.toDouble() / PERCENT_ATTRIBUTE_SCALE * level
                        else -> detail.constantParam.toDouble() / FLAT_ATTRIBUTE_SCALE * level
                    }
                    StaticEffect(
                        stage = BattlePreparationStage.EQUIPMENT,
                        sourceId = skillId,
                        containerSourceId = equipmentId,
                        sourcePosition = spec.position,
                        targetPosition = spec.position,
                        stat = stat,
                        strength = strength.toInt(),
                        strengthExact = strength,
                        percent = percent,
                    )
                }
            }
        }

    private fun equipmentPreparationModifiers(
        specs: List<BattleHeroSpec>,
    ): List<BattlePreparationModifier> =
        specs.flatMap { spec ->
            val equipmentId = spec.equipmentIds.firstOrNull() ?: return@flatMap emptyList()
            spec.equipmentSkillIds.flatMapIndexed { index, skillId ->
                val level = spec.equipmentSkillLevels.getOrElse(index) { DEFAULT_EQUIPMENT_LEVEL }
                config.skillDetails(skillId).mapNotNull { detail ->
                    if (
                        detail.effectId !in PREPARATION_MODIFIER_EFFECTS ||
                        (detail.effectId == 533 && detail.effectParam == 3)
                    ) {
                        return@mapNotNull null
                    }
                    BattlePreparationModifier(
                        stage = BattlePreparationStage.EQUIPMENT,
                        sourceId = skillId,
                        sourcePosition = spec.position,
                        targetPosition = spec.position,
                        effectId = detail.effectId,
                        amount = detail.constantParam * level,
                        containerSourceId = equipmentId,
                    )
                }
            }
        }

    private fun equipmentRuntimeModifiers(spec: BattleHeroSpec): List<BattleModifier> =
        spec.equipmentSkillIds.flatMapIndexed { index, skillId ->
            val level = spec.equipmentSkillLevels.getOrElse(index) { DEFAULT_EQUIPMENT_LEVEL }
            config.skillDetails(skillId).mapNotNull { detail ->
                val amount = detail.constantParam * level
                when (detail.effectId) {
                    321 -> BattleModifier.DamageDealtPercent(
                        origin = DamageOrigin.NORMAL,
                        percent = amount,
                    )
                    322 -> BattleModifier.DamageDealtPercent(
                        origin = DamageOrigin.ACTIVE,
                        percent = amount,
                    )
                    325 -> BattleModifier.DamageDealtPercent(
                        origin = DamageOrigin.PURSUIT,
                        percent = amount,
                    )
                    351 -> BattleModifier.DamageTakenPercent(
                        origin = DamageOrigin.NORMAL,
                        percent = -amount,
                    )
                    352 -> BattleModifier.DamageTakenPercent(
                        origin = DamageOrigin.ACTIVE,
                        percent = -amount,
                    )
                    531 -> BattleModifier.DamageDealtPercent(
                        school = DamageSchool.PHYSICAL,
                        percent = amount,
                    )
                    533 -> BattleModifier.DamageDealtPercent(
                        school = DamageSchool.STRATEGY,
                        percent = amount,
                    )
                    522 -> BattleModifier.DamageTakenPercent(
                        school = DamageSchool.PHYSICAL,
                        percent = -amount,
                    )
                    524 -> BattleModifier.DamageTakenPercent(
                        school = DamageSchool.STRATEGY,
                        percent = -amount,
                    )
                    else -> null
                }
            }
        }

    private fun equipmentPreparationActions(
        specs: List<BattleHeroSpec>,
    ): List<BattlePreparationAction> =
        specs.flatMap { spec ->
            val equipmentId = spec.equipmentIds.firstOrNull() ?: return@flatMap emptyList()
            spec.equipmentSkillIds.flatMapIndexed { index, skillId ->
                val level = spec.equipmentSkillLevels.getOrElse(index) { DEFAULT_EQUIPMENT_LEVEL }
                val details = config.skillDetails(skillId)
                val combinedActiveAndPursuit =
                    details.any { it.effectId == 322 } && details.any { it.effectId == 325 }
                details.mapNotNull { detail ->
                    val actionId = when (detail.effectId) {
                        321 -> "6x".toInt(36)
                        322 -> (
                            if (combinedActiveAndPursuit || detail.effectParam != 0) "bf" else "79"
                            ).toInt(36)
                        325 -> (if (combinedActiveAndPursuit) "bg" else "7d").toInt(36)
                        351 -> "6w".toInt(36)
                        352 -> "78".toInt(36)
                        281 -> "9b".toInt(36)
                        421 -> "7g".toInt(36)
                        422 -> "7m".toInt(36)
                        423 -> "7i".toInt(36)
                        424 -> "7k".toInt(36)
                        161 -> (if (detail.effectParam == 3) "a4" else "a3").toInt(36)
                        171 -> "a5".toInt(36)
                        251 -> "99".toInt(36)
                        533 -> if (detail.effectParam == 3) "dr".toInt(36) else return@mapNotNull null
                        504 -> "1w".toInt(36)
                        else -> return@mapNotNull null
                    }
                    val scale = if (detail.effectId == 161) 1_000.0 else 1.0
                    BattlePreparationAction(
                        stage = BattlePreparationStage.EQUIPMENT,
                        sourceId = skillId,
                        sourcePosition = spec.position,
                        targetPosition = if (detail.effectId == 504) 0 else spec.position,
                        actionId = actionId,
                        amountExact = (detail.constantParam * level / scale).takeUnless {
                            detail.effectId == 504
                        },
                        actionParameter = detail.effectParam.takeIf {
                            detail.effectId in setOf(251, 533)
                        },
                        appendSourcePosition = detail.effectId == 504,
                        containerSourceId = equipmentId,
                    )
                }
            }
        }

    private fun countryEffects(specs: List<BattleHeroSpec>): List<StaticEffect> {
        val countryGroup = specs
            .groupBy { config.hero(it.heroId)?.country ?: 0 }
            .entries
            .firstOrNull { (country, heroes) -> country in 1..6 && heroes.size >= 2 }
            ?: return emptyList()
        val sourceId = if (countryGroup.key == 6) 295_140 else 295_000 + countryGroup.key * 10
        return specs.flatMap { spec ->
            PRIMARY_STATS.map { stat ->
                StaticEffect(
                    stage = BattlePreparationStage.ARMY,
                    sourceId = sourceId,
                    targetPosition = spec.position,
                    stat = stat,
                    strength = COUNTRY_BONUS_PERCENT,
                )
            }
        }
    }

    private fun troopTypeEffects(specs: List<BattleHeroSpec>): List<StaticEffect> {
        val typeGroup = specs
            .groupBy { (config.hero(it.heroId)?.heroType ?: 0) % 10 }
            .entries
            .firstOrNull { (type, heroes) -> type in 1..3 && heroes.size >= 2 }
            ?: return emptyList()
        val sourceId = when (typeGroup.key) {
            1 -> if (typeGroup.value.size == 2) 291_005 else 291_006
            2 -> if (typeGroup.value.size == 2) 291_003 else 291_004
            else -> if (typeGroup.value.size == 2) 291_001 else 291_002
        }
        val stats = when (typeGroup.key) {
            1 -> listOf(BattleStat.DEFENSE, BattleStat.SPEED)
            2 -> listOf(BattleStat.ATTACK, BattleStat.DEFENSE)
            else -> listOf(BattleStat.ATTACK, BattleStat.SPEED)
        }
        val percent = if (typeGroup.value.size == 2) 5 else 10
        return typeGroup.value.flatMap { spec ->
            stats.map { stat ->
                StaticEffect(
                    stage = BattlePreparationStage.ARMY,
                    sourceId = sourceId,
                    targetPosition = spec.position,
                    stat = stat,
                    strength = percent,
                )
            }
        }
    }

    private fun materializeEffects(
        effects: List<StaticEffect>,
        specs: List<BattleHeroSpec>,
    ): List<BattlePreparationEffect> {
        val currentStats = specs.associate { spec ->
            val heroConfig = config.hero(spec.heroId) ?: error("未知武将配置: ${spec.heroId}")
            val equipmentModifiers = equipmentModifiers(spec)
            val formationBonus = config.armyBonusesFor(specs.map { it.heroId })
                .fold(BattleStats.ZERO) { total, bonus -> total + bonus.stats }
            spec.position to (
                heroConfig.stats +
                    heroConfig.growth.scale(spec.level.coerceAtLeast(1) - 1) +
                    spec.attributePoints +
                    formationBonus +
                    if (spec.equipmentSkillIds.isEmpty()) {
                        equipmentModifiers.statBonus()
                    } else {
                        BattleStats.ZERO
                    }
                )
        }.toMutableMap()
        return effects.map { effect ->
            val before = currentStats.getValue(effect.targetPosition)
            val deltaStats = effect.toStats(before)
            val after = before + deltaStats
            currentStats[effect.targetPosition] = after
            val delta = deltaStats.precise(effect.stat)
            val valueAfter = after.precise(effect.stat)
            BattlePreparationEffect(
                stage = effect.stage,
                sourceId = effect.sourceId,
                containerSourceId = effect.containerSourceId,
                sourcePosition = effect.sourcePosition,
                targetPosition = effect.targetPosition,
                stat = effect.stat,
                strength = effect.strength,
                delta = delta.toInt(),
                valueAfter = valueAfter.toInt(),
                deltaExact = roundOneDecimal(delta),
                valueAfterExact = roundOneDecimal(valueAfter),
                percent = effect.percent,
                strengthExact = effect.strengthExact,
            )
        }
    }

    private fun equipmentModifiers(spec: BattleHeroSpec): List<BattleModifier> =
        spec.equipmentIds.flatMap { equipmentId ->
            val equipment = equipmentRepository?.equipment(equipmentId)
                ?: return@flatMap listOf(BattleModifier.Unsupported(equipmentId, "未知装备: $equipmentId"))
            BattleModifierParser.parseEquipment(equipment, emptyList())
        }

    private companion object {
        const val DEFAULT_SKILL_LEVEL = 1
        const val DEFAULT_EQUIPMENT_LEVEL = 1
        const val COUNTRY_BONUS_PERCENT = 10
        const val FLAT_ATTRIBUTE_SCALE = 100.0
        const val PERCENT_ATTRIBUTE_SCALE = 1_000_000.0
        val PREPARATION_MODIFIER_EFFECTS = setOf(522, 524, 531, 533)
        val PRIMARY_STATS = listOf(
            BattleStat.ATTACK,
            BattleStat.DEFENSE,
            BattleStat.STRATEGY,
            BattleStat.SPEED,
        )
    }
}

private data class StaticEffect(
    val stage: BattlePreparationStage,
    val sourceId: Int,
    val targetPosition: Int,
    val stat: BattleStat,
    val strength: Int,
    val sourcePosition: Int? = null,
    val percent: Boolean = true,
    val containerSourceId: Int = sourceId,
    val strengthExact: Double = strength.toDouble(),
) {
    fun toStats(base: BattleStats): BattleStats {
        val amountHundredths = if (percent) {
            (base.precise(stat) * strengthExact).toInt()
        } else {
            (strengthExact * 100).toInt()
        }
        return when (stat) {
            BattleStat.ATTACK -> BattleStats.fromHundredths(amountHundredths, 0, 0, 0, 0, 0)
            BattleStat.DEFENSE -> BattleStats.fromHundredths(0, amountHundredths, 0, 0, 0, 0)
            BattleStat.STRATEGY -> BattleStats.fromHundredths(0, 0, amountHundredths, 0, 0, 0)
            BattleStat.SPEED -> BattleStats.fromHundredths(0, 0, 0, amountHundredths, 0, 0)
            BattleStat.SIEGE -> BattleStats.fromHundredths(0, 0, 0, 0, amountHundredths, 0)
            BattleStat.HIT_RANGE -> BattleStats(0, 0, 0, 0, 0, strengthExact.toInt())
        }
    }
}

private fun Int.toBattleStat(): BattleStat? = when (this) {
    101 -> BattleStat.ATTACK
    102 -> BattleStat.DEFENSE
    103 -> BattleStat.STRATEGY
    104 -> BattleStat.SPEED
    106 -> BattleStat.HIT_RANGE
    else -> null
}

private data class TroopFeatureSource(
    val position: Int,
    val skillId: Int,
)

private fun roundOneDecimal(value: Double): Double =
    kotlin.math.round(value * 10.0) / 10.0

private fun BattleStats.scale(times: Int): BattleStats =
    BattleStats.fromHundredths(
        attack = kotlin.math.round(precise(BattleStat.ATTACK) * times * 100).toInt(),
        defense = kotlin.math.round(precise(BattleStat.DEFENSE) * times * 100).toInt(),
        strategy = kotlin.math.round(precise(BattleStat.STRATEGY) * times * 100).toInt(),
        speed = kotlin.math.round(precise(BattleStat.SPEED) * times * 100).toInt(),
        siege = kotlin.math.round(precise(BattleStat.SIEGE) * times * 100).toInt(),
        hitRange = hitRange * times,
    )

private fun List<BattleModifier>.statBonus(): BattleStats =
    fold(BattleStats.ZERO) { total, modifier ->
        total + when (modifier) {
            is BattleModifier.Stat -> modifier.toStats()
            else -> BattleStats.ZERO
        }
    }

private fun BattleModifier.Stat.toStats(): BattleStats =
    when (stat) {
        BattleStat.ATTACK -> BattleStats(amount, 0, 0, 0, 0, 0)
        BattleStat.DEFENSE -> BattleStats(0, amount, 0, 0, 0, 0)
        BattleStat.STRATEGY -> BattleStats(0, 0, amount, 0, 0, 0)
        BattleStat.SPEED -> BattleStats(0, 0, 0, amount, 0, 0)
        BattleStat.SIEGE -> BattleStats(0, 0, 0, 0, amount, 0)
        BattleStat.HIT_RANGE -> BattleStats(0, 0, 0, 0, 0, amount)
    }
