package com.stzb.server.game

import com.stzb.server.game.battle.BattleHeroSpec

/**
 * Builds deterministic NPC defenders for resource land.
 */
class LandDefenderFactory(
    private val landMapRepository: LandMapRepository = LandMapRepository.loadDefault(),
    private val npcArmyRepository: ClientNpcArmyRepository = ClientNpcArmyRepository.loadDefault(),
) {
    fun levelForWid(wid: Int): Int =
        requireNotNull(landMapRepository.tile(wid)) {
            "wid $wid is outside the configured resource map or is not resource land"
        }.level

    fun specsForWid(wid: Int): List<BattleHeroSpec> =
        teamsForWid(wid).first()

    fun specsForLevel(level: Int): List<BattleHeroSpec> =
        candidateSpecsForLevel(level).first()

    fun candidateSpecsForLevel(level: Int): List<List<BattleHeroSpec>> {
        val normalized = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        return npcArmyRepository.armiesForPool(normalized).map(::toBattleHeroSpecs)
    }

    fun teamCountForLevel(level: Int): Int =
        npcArmyRepository.teamCount(level.coerceIn(MIN_LEVEL, MAX_LEVEL))

    fun armyIdsForWid(wid: Int): List<Int> =
        armiesForWid(wid).map(ClientNpcArmy::armyId)

    fun teamsForWid(wid: Int): List<List<BattleHeroSpec>> =
        armiesForWid(wid).map(::toBattleHeroSpecs)

    private fun armiesForWid(wid: Int): List<ClientNpcArmy> {
        val level = levelForWid(wid)
        val candidates = npcArmyRepository.armiesForPool(level)
        val firstArmyId = canonicalArmyIds.getValue(level)
        val start = candidates.indexOfFirst { it.armyId == firstArmyId }
        require(start >= 0) {
            "missing canonical resource-land defender army $firstArmyId for level $level"
        }
        return List(teamCountForLevel(level)) { offset ->
            candidates[(start + offset) % candidates.size]
        }
    }

    private fun toBattleHeroSpecs(army: ClientNpcArmy): List<BattleHeroSpec> =
        army.heroes.mapIndexed { position, hero ->
            BattleHeroSpec(
                heroId = hero.heroId,
                position = position,
                troops = hero.troops.coerceAtMost(PlayerHero.MAX_TROOPS),
                level = hero.level,
                heroType = hero.heroType,
                surfaceSkillId = hero.heroFeatureSkillId,
                // BattleTeamBuilder already adds the configured initial skill.
                extraSkillIds = hero.skillIds.drop(1),
                skillLevels = hero.skillLevels,
                troopFeatureIds = hero.troopFeatureIds,
                equipmentIds = hero.equipmentIds,
                equipmentSkillIds = hero.equipmentSkillIds,
                equipmentSkillLevels = hero.equipmentSkillLevels,
                equipmentFeatureSkillIds = hero.equipmentFeatureSkillIds,
                equipmentFeatureSkillLevels = hero.equipmentFeatureSkillLevels,
            )
        }

    private companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 9

        val canonicalArmyIds = mapOf(
            1 to 101,
            2 to 203,
            3 to 305,
            4 to 407,
            5 to 509,
            6 to 611,
            7 to 713,
            8 to 815,
            9 to 915,
        )
    }
}
