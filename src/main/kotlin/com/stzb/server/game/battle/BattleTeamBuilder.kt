package com.stzb.server.game.battle

data class BattleHeroSpec(
    val heroId: Int,
    val position: Int,
    val troops: Int,
    val extraSkillIds: List<Int> = emptyList(),
    val equipmentIds: List<Int> = emptyList(),
    val level: Int = 1,
    val attributePoints: BattleStats = BattleStats.ZERO,
    val advanceLevel: Int = 0,
    val morale: Int = 100,
)

class BattleTeamBuilder(
    private val config: BattleConfigRepository,
    private val equipmentRepository: BattleEquipmentRepository? = null,
) {
    fun build(specs: List<BattleHeroSpec>): BattleTeam =
        BattleFormationCalculator(config, equipmentRepository).calculate(specs)
}
