package com.stzb.server.game

/**
 * Player-driven garrison: send an army to reside at a wid. On arrival the team
 * is frozen into a WorldState garrison snapshot so other players can attack it.
 */
class GarrisonService {
    fun startReside(
        state: PlayerState,
        wid: Int,
        armyId: Int = state.primaryArmyId(),
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): PlayerMarch? {
        if (wid <= 0 || wid == state.cityWid) return null
        if (state.activeMarch(armyId) != null) return null
        val participants = state.teamHeroes(armyId)
            .withIndex()
            .mapNotNull { (position, heroUid) ->
                state.hero(heroUid)
                    ?.takeIf { it.troops > 0 }
                    ?.let { hero ->
                        val loadout = InventoryCatalog.battleLoadoutForGearUid(hero.gearUid)
                        PlayerMarchHero(
                            heroUid = hero.heroUid,
                            position = position,
                            heroId = hero.heroId,
                            troops = hero.troops,
                            level = hero.level,
                            skillIds = hero.normalizedSkillIds(),
                            heroType = hero.heroType,
                            attributePoints = hero.attributePoints,
                            activeFeatureId = hero.activeFeatureId,
                            cardBorder = hero.cardBorder,
                            dynamicIcon = hero.dynamicIcon,
                            armyFacadeCardId = hero.armyFacadeCardId,
                            advanceNum = hero.advanceNum,
                            equipmentIds = loadout?.equipmentIds.orEmpty(),
                            equipmentFeatureSkillIds = loadout?.equipmentFeatureSkillIds.orEmpty(),
                            equipmentFeatureSkillLevels = loadout?.equipmentFeatureSkillLevels.orEmpty(),
                        )
                    }
            }
        if (participants.isEmpty()) return null
        return state.startMarch(
            targetWid = wid,
            nowSec = nowSec,
            armyId = armyId,
            participants = participants,
            targetType = MarchTargetType.RESIDE_GOING,
        )
    }

    fun settleReside(
        state: PlayerState,
        armyId: Int = state.primaryArmyId(),
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): GarrisonSnapshot? {
        val march = state.completeMarchIfDue(nowSec, armyId) ?: return null
        if (march.targetType != MarchTargetType.RESIDE_GOING) {
            // Not a reside march; reside settle only handles reside marches. Attack
            // marches are settled by PlayerBattleService, dispatched by target type.
            return null
        }
        val snapshot = GarrisonSnapshot(
            wid = march.targetWid,
            ownerUserId = state.userId,
            armyId = march.armyId,
            specs = march.participants.map(BattleSpecFactory::fromMarchHero),
            residedAtSec = nowSec,
        )
        WorldStateRepository.putGarrison(snapshot)
        return snapshot
    }
}
