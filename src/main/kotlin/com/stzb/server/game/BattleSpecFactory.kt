package com.stzb.server.game

import com.stzb.server.game.battle.BattleHeroSpec

/**
 * Single source of truth for turning a departed march hero into a battle spec.
 * Attacker settlement, PvP defender snapshots, and reside snapshots all use this
 * so equipment/skill wiring never drifts between paths.
 */
object BattleSpecFactory {
    fun fromMarchHero(hero: PlayerMarchHero): BattleHeroSpec =
        BattleHeroSpec(
            heroId = hero.heroId,
            position = hero.position,
            troops = hero.troops.coerceAtMost(PlayerHero.MAX_TROOPS),
            level = hero.level,
            extraSkillIds = hero.skillIds.drop(1).filter { it > 0 },
            skillLevels = hero.skillIds.filter { it > 0 }
                .map { PlayerHero.MAX_SKILL_LEVEL },
            heroType = hero.heroType,
            surfaceSkillId = hero.activeFeatureId,
            attributePoints = hero.attributePoints,
            advanceLevel = hero.advanceNum,
            equipmentIds = hero.equipmentIds,
            equipmentFeatureSkillIds = hero.equipmentFeatureSkillIds,
            equipmentFeatureSkillLevels = hero.equipmentFeatureSkillLevels,
        )
}
