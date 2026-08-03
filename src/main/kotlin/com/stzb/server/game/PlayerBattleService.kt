package com.stzb.server.game

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleEngine
import com.stzb.server.game.battle.BattleEquipmentRepository
import com.stzb.server.game.battle.BattleHeroSpec
import com.stzb.server.game.battle.BattleOutcome
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleResult
import com.stzb.server.game.battle.BattleTeamBuilder
import com.stzb.server.game.battle.BattleHeroSurface
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.SeededBattleRandom

data class PlayerBattleLaunchResult(
    val battleId: Int,
    val targetWid: Int,
    val outcome: BattleOutcome? = null,
    val mayClaimLand: Boolean = false,
)

class PlayerBattleService(
    private val reportStore: ClientBattleReportStore,
    private val config: BattleConfigRepository = BattleConfigRepository.loadDefault(),
    equipmentRepository: BattleEquipmentRepository = BattleEquipmentRepository.loadDefault(),
    private val battleRandomFactory: (Int) -> BattleRandom = ::SeededBattleRandom,
    private val defenderFactory: LandDefenderFactory = LandDefenderFactory(),
) {
    private val builder = BattleTeamBuilder(config, equipmentRepository)

    fun launchPveBattle(
        state: PlayerState,
        targetWid: Int,
        armyId: Int = state.primaryArmyId(),
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): PlayerBattleLaunchResult? {
        state.activeMarch(armyId)
            ?.takeIf { nowSec >= it.endSec }
            ?.let { settlePveBattle(state, armyId, nowSec) }
        if (state.activeMarch(armyId) != null) return null
        val participants = state.teamHeroes(armyId)
            .withIndex()
            .mapNotNull { (position, heroUid) ->
                state.hero(heroUid)
                    ?.takeIf { it.troops > 0 }
                    ?.let { position to it }
            }
        if (participants.isEmpty()) return null

        participants.forEach { (_, hero) -> hero.stamina = PlayerHero.MAX_STAMINA }
        state.startMarch(
            targetWid = targetWid,
            nowSec = nowSec,
            armyId = armyId,
            participants = participants.map { (position, hero) ->
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
            },
            specialArmyFacadeId = state.activeSpecialArmyFacadeId(),
        )
        return PlayerBattleLaunchResult(battleId = 0, targetWid = targetWid)
    }

    fun settlePveBattle(
        state: PlayerState,
        armyId: Int = state.primaryArmyId(),
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): PlayerBattleLaunchResult? {
        val march = state.completeMarchIfDue(nowSec, armyId) ?: return null
        val participants = march.participants.ifEmpty {
            state.teamHeroes(armyId)
                .withIndex()
                .mapNotNull { (position, heroUid) ->
                    state.hero(heroUid)
                        ?.takeIf { it.troops > 0 }
                        ?.let { hero ->
                            val loadout =
                                InventoryCatalog.battleLoadoutForGearUid(hero.gearUid)
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
                                equipmentFeatureSkillIds =
                                    loadout?.equipmentFeatureSkillIds.orEmpty(),
                                equipmentFeatureSkillLevels =
                                    loadout?.equipmentFeatureSkillLevels.orEmpty(),
                            )
                        }
                }
        }
        if (participants.isEmpty()) return null

        val attackerSurfaces = participants.map { participant ->
            BattleHeroSurface(
                heroId = participant.heroId,
                position = participant.position,
                cardBorder = participant.cardBorder,
                dynamicIcon = participant.dynamicIcon,
                activeFeatureId = participant.activeFeatureId,
            )
        }
        var attacker = builder.build(
            participants.map { participant ->
                BattleHeroSpec(
                    heroId = participant.heroId,
                    position = participant.position,
                    troops = participant.troops.coerceAtMost(PlayerHero.MAX_TROOPS),
                    level = participant.level,
                    extraSkillIds = participant.skillIds.drop(1).filter { it > 0 },
                    skillLevels = participant.skillIds.filter { it > 0 }
                        .map { PlayerHero.MAX_SKILL_LEVEL },
                    heroType = participant.heroType,
                    surfaceSkillId = participant.activeFeatureId,
                    attributePoints = participant.attributePoints,
                    advanceLevel = participant.advanceNum,
                    equipmentIds = participant.equipmentIds,
                    equipmentFeatureSkillIds = participant.equipmentFeatureSkillIds,
                    equipmentFeatureSkillLevels = participant.equipmentFeatureSkillLevels,
                )
            },
        )
        var result: BattleResult? = null
        var report = null as com.stzb.server.game.battle.ClientBattleReport?
        for ((index, defenderSpecs) in defenderFactory.teamsForWid(march.targetWid).withIndex()) {
            val defender = builder.build(defenderSpecs)
            result = BattleEngine.resolve(
                BattleRequest(attacker = attacker, defender = defender, maxRounds = 8),
                config,
                battleRandomFactory(stableBattleSeed(march, index)),
            )
            report = reportStore.record(
                ownerUserId = state.userId,
                wid = march.targetWid,
                timeSec = nowSec,
                result = result,
                attackerSurfaces = attackerSurfaces,
            )
            attacker = builder.build(
                participants.map { participant ->
                    val remainingTroops = result.attacker.heroes
                        .firstOrNull { it.position == participant.position }
                        ?.troops
                        ?: 0
                    BattleHeroSpec(
                        heroId = participant.heroId,
                        position = participant.position,
                        troops = remainingTroops,
                        level = participant.level,
                        extraSkillIds = participant.skillIds.drop(1).filter { it > 0 },
                        skillLevels = participant.skillIds.filter { it > 0 }
                            .map { PlayerHero.MAX_SKILL_LEVEL },
                        heroType = participant.heroType,
                        surfaceSkillId = participant.activeFeatureId,
                        attributePoints = participant.attributePoints,
                        advanceLevel = participant.advanceNum,
                        equipmentIds = participant.equipmentIds,
                        equipmentFeatureSkillIds = participant.equipmentFeatureSkillIds,
                        equipmentFeatureSkillLevels = participant.equipmentFeatureSkillLevels,
                    )
                },
            )
            if (result.outcome != BattleOutcome.ATTACKER_WIN) break
        }
        val finalResult = requireNotNull(result)

        finalResult.attacker.heroes.forEach { battleHero ->
            val heroUid = participants.firstOrNull { it.position == battleHero.position }?.heroUid ?: 0
            state.hero(heroUid)?.troops =
                battleHero.troops.coerceIn(0, PlayerHero.MAX_TROOPS)
        }
        return PlayerBattleLaunchResult(
            battleId = requireNotNull(report).battleId,
            targetWid = march.targetWid,
            outcome = finalResult.outcome,
            mayClaimLand = finalResult.outcome == BattleOutcome.ATTACKER_WIN,
        )
    }

    private fun stableBattleSeed(march: PlayerMarch, defenderIndex: Int): Int =
        march.armyId * 31 xor
            march.fromWid * 17 xor
            march.targetWid xor
            march.beginSec xor
            defenderIndex
}
