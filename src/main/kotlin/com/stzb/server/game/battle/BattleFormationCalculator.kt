package com.stzb.server.game.battle

/**
 * Computes the immutable battle-entry snapshot. Runtime buffs never mutate
 * these values; they are layered by BattleEffectState.
 */
class BattleFormationCalculator(
    private val config: BattleConfigRepository,
    private val equipmentRepository: BattleEquipmentRepository? = null,
) {
    fun calculate(specs: List<BattleHeroSpec>): BattleTeam {
        require(specs.map { it.position }.distinct().size == specs.size) { "同一部队内站位不能重复" }
        require(specs.all { it.position in 0..2 }) { "武将站位必须在 0..2" }

        val bonuses = config.armyBonusesFor(specs.map { it.heroId })
        val formationBonus = bonuses.fold(BattleStats.ZERO) { total, bonus -> total + bonus.stats }
        val campBonus = BattleStats(20, 20, 20, 20, 0, 0)
        val heroes = specs.map { spec ->
            val heroConfig = config.hero(spec.heroId) ?: error("未知武将配置: ${spec.heroId}")
            val equipmentModifiers = equipmentModifiers(spec)
            val level = spec.level.coerceAtLeast(1)
            val advance = spec.advanceLevel.coerceAtLeast(0)
            config.toBattleHero(spec.heroId, spec.position, spec.troops).copy(
                stats = heroConfig.stats +
                    heroConfig.growth.scale(level - 1) +
                    spec.attributePoints +
                    campBonus +
                    formationBonus +
                    equipmentModifiers.statBonus(),
                maxTroops = spec.troops + advance * 200,
                skillIds = listOf(heroConfig.initialSkillId).filter { it > 0 } + spec.extraSkillIds,
                equipmentIds = spec.equipmentIds,
                modifiers = equipmentModifiers.filterNot { it is BattleModifier.Stat },
                level = level,
                advanceLevel = advance,
                morale = spec.morale.coerceAtLeast(0),
            )
        }
        return BattleTeam(heroes = heroes, armyBonuses = bonuses)
    }

    private fun equipmentModifiers(spec: BattleHeroSpec): List<BattleModifier> =
        spec.equipmentIds.flatMap { equipmentId ->
            val equipment = equipmentRepository?.equipment(equipmentId)
                ?: return@flatMap listOf(BattleModifier.Unsupported(equipmentId, "未知装备: $equipmentId"))
            BattleModifierParser.parseEquipment(equipment, equipmentRepository.features(equipment.featureGroup))
        }
}

private fun BattleStats.scale(times: Int): BattleStats =
    BattleStats(
        attack = attack * times,
        defense = defense * times,
        strategy = strategy * times,
        speed = speed * times,
        siege = siege * times,
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
