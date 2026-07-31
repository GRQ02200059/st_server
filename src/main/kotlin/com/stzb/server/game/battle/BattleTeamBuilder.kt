package com.stzb.server.game.battle

import com.stzb.server.game.ClientTroopFeatureRepository
import com.stzb.server.game.ClientEquipmentSkillRepository

data class BattleHeroSpec(
    val heroId: Int,
    val position: Int,
    val troops: Int,
    val extraSkillIds: List<Int> = emptyList(),
    val skillLevels: List<Int> = emptyList(),
    val troopFeatureIds: List<Int> = emptyList(),
    val equipmentIds: List<Int> = emptyList(),
    val equipmentSkillIds: List<Int> = emptyList(),
    val equipmentSkillLevels: List<Int> = emptyList(),
    val equipmentFeatureSkillIds: List<Int> = emptyList(),
    val equipmentFeatureSkillLevels: List<Int> = emptyList(),
    val level: Int = 1,
    val attributePoints: BattleStats = BattleStats.ZERO,
    val advanceLevel: Int = 0,
    val morale: Int = 100,
)

class BattleTeamBuilder(
    private val config: BattleConfigRepository,
    private val equipmentRepository: BattleEquipmentRepository? = null,
    private val troopFeatureRepository: ClientTroopFeatureRepository =
        ClientTroopFeatureRepository.loadDefault(),
    private val equipmentSkillRepository: ClientEquipmentSkillRepository =
        ClientEquipmentSkillRepository.loadDefault(),
) {
    fun build(specs: List<BattleHeroSpec>): BattleTeam =
        BattleFormationCalculator(
            config,
            equipmentRepository,
            troopFeatureRepository,
            equipmentSkillRepository,
        ).calculate(specs)
}
