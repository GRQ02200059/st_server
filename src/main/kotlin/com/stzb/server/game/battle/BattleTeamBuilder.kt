package com.stzb.server.game.battle

data class BattleHeroSpec(
    val heroId: Int,
    val position: Int,
    val troops: Int,
    val extraSkillIds: List<Int> = emptyList(),
    val equipmentIds: List<Int> = emptyList(),
    val level: Int = 1,
)

class BattleTeamBuilder(
    private val config: BattleConfigRepository,
    private val equipmentRepository: BattleEquipmentRepository? = null,
) {
    fun build(specs: List<BattleHeroSpec>): BattleTeam {
        require(specs.map { it.position }.distinct().size == specs.size) { "同一部队内站位不能重复" }

        val bonuses = config.armyBonusesFor(specs.map { it.heroId })
        val totalBonus = bonuses.fold(BattleStats(0, 0, 0, 0, 0, 0)) { acc, bonus ->
            acc + bonus.stats
        }
        val heroes = specs.map { spec ->
            val heroConfig = config.hero(spec.heroId) ?: error("未知武将配置: ${spec.heroId}")
            val equipmentModifiers = spec.equipmentIds.flatMap { equipmentId ->
                val equipment = equipmentRepository?.equipment(equipmentId)
                    ?: return@flatMap listOf(BattleModifier.Unsupported(equipmentId, "未知装备: $equipmentId"))
                BattleModifierParser.parseEquipment(equipment, equipmentRepository.features(equipment.featureGroup))
            }
            val level = spec.level.coerceAtLeast(1)
            val grownStats = heroConfig.stats + heroConfig.growth.scale(level - 1)
            config.toBattleHero(spec.heroId, spec.position, spec.troops).copy(
                stats = grownStats + totalBonus + equipmentModifiers.statBonus(),
                skillIds = listOf(heroConfig.initialSkillId).filter { it > 0 } + spec.extraSkillIds,
                equipmentIds = spec.equipmentIds,
                modifiers = equipmentModifiers.filterNot { it is BattleModifier.Stat },
                level = level,
            )
        }

        return BattleTeam(heroes = heroes, armyBonuses = bonuses)
    }

    private operator fun BattleStats.plus(other: BattleStats): BattleStats =
        BattleStats(
            attack = attack + other.attack,
            defense = defense + other.defense,
            strategy = strategy + other.strategy,
            speed = speed + other.speed,
            siege = siege + other.siege,
            hitRange = hitRange + other.hitRange,
        )

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
        fold(BattleStats(0, 0, 0, 0, 0, 0)) { acc, modifier ->
            when (modifier) {
                is BattleModifier.Stat -> acc + modifier.toStats()
                else -> acc
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
}
