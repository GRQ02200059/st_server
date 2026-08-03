package com.stzb.server.game

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEngine
import com.stzb.server.game.battle.BattleEquipmentRepository
import com.stzb.server.game.battle.BattleOutcome
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleTeamBuilder
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.SeededBattleRandom

data class PvpSettlement(
    val battleId: Int,
    val targetWid: Int,
    val outcome: BattleOutcome,
    val defenderUserId: Int,
    val ownershipTransferred: Boolean,
)

/**
 * Resolves an attacker march against a player garrison snapshot. On victory the
 * garrison is cleared and the target land ownership transfers to the attacker;
 * the defender's persisted state is reconciled by the caller-supplied loader.
 */
class PvpBattleService(
    private val reportStore: ClientBattleReportStore,
    private val config: BattleConfigRepository = BattleConfigRepository.loadDefault(),
    equipmentRepository: BattleEquipmentRepository = BattleEquipmentRepository.loadDefault(),
    private val battleRandomFactory: (Int) -> BattleRandom = ::SeededBattleRandom,
) {
    private val builder = BattleTeamBuilder(config, equipmentRepository)

    fun settle(
        attacker: PlayerState,
        march: PlayerMarch,
        garrison: GarrisonSnapshot,
        nowSec: Int,
        loadDefenderState: (Int) -> PlayerState?,
    ): PvpSettlement {
        val attackerTeam = builder.build(march.participants.map(BattleSpecFactory::fromMarchHero))
        val defenderTeam = builder.build(garrison.specs)
        val result = BattleEngine.resolve(
            BattleRequest(attacker = attackerTeam, defender = defenderTeam, maxRounds = 8),
            config,
            battleRandomFactory(seed(march)),
        )
        val report = reportStore.record(
            ownerUserId = attacker.userId,
            wid = march.targetWid,
            timeSec = nowSec,
            result = result,
        )

        // Persist attacker hero troops from the settled result.
        result.attacker.heroes.forEach { battleHero ->
            val heroUid = march.participants.firstOrNull { it.position == battleHero.position }?.heroUid ?: 0
            attacker.hero(heroUid)?.troops = battleHero.troops.coerceIn(0, PlayerHero.MAX_TROOPS)
        }

        var transferred = false
        if (result.outcome == BattleOutcome.ATTACKER_WIN) {
            WorldStateRepository.removeGarrison(march.targetWid)
            transferred = WorldStateRepository.claimLand(attacker, march.targetWid, nowSec)
            val defenderState = loadDefenderState(garrison.ownerUserId)
            if (defenderState != null && transferred) {
                defenderState.replaceOccupiedLands(
                    defenderState.occupiedLands().filter { it != march.targetWid },
                )
            }
        }

        return PvpSettlement(
            battleId = report.battleId,
            targetWid = march.targetWid,
            outcome = result.outcome,
            defenderUserId = garrison.ownerUserId,
            ownershipTransferred = transferred,
        )
    }

    private fun seed(march: PlayerMarch): Int =
        march.armyId * 31 xor
            march.fromWid * 17 xor
            march.targetWid xor
            march.beginSec
}
