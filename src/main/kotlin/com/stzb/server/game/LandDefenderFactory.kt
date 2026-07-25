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
        return npcArmyRepository.armiesForPool(normalized).map { army ->
            army.heroes.mapIndexed { position, hero ->
                BattleHeroSpec(
                    heroId = hero.heroId,
                    position = position,
                    troops = hero.troops.coerceAtMost(PlayerHero.MAX_TROOPS),
                    level = hero.level,
                    // BattleTeamBuilder already adds the configured initial skill.
                    extraSkillIds = hero.skillIds.drop(1),
                )
            }
        }
    }

    fun teamCountForLevel(level: Int): Int =
        npcArmyRepository.teamCount(level.coerceIn(MIN_LEVEL, MAX_LEVEL))

    fun teamsForWid(wid: Int): List<List<BattleHeroSpec>> {
        val level = levelForWid(wid)
        val candidates = candidateSpecsForLevel(level)
        val start = Math.floorMod(wid, candidates.size)
        return List(teamCountForLevel(level)) { offset ->
            candidates[(start + offset) % candidates.size]
        }
    }

    private companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 9
    }
}
