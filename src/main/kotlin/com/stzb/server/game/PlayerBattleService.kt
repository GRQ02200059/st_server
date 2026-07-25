package com.stzb.server.game

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleEngine
import com.stzb.server.game.battle.BattleEquipmentRepository
import com.stzb.server.game.battle.BattleHeroSpec
import com.stzb.server.game.battle.BattleOutcome
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleTeamBuilder
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.SeededBattleRandom

data class PlayerBattleLaunchResult(
    val battleId: Int,
    val targetWid: Int,
    val outcome: BattleOutcome? = null,
)

class PlayerBattleService(
    private val reportStore: ClientBattleReportStore,
    private val config: BattleConfigRepository = BattleConfigRepository.loadDefault(),
    equipmentRepository: BattleEquipmentRepository = BattleEquipmentRepository.loadDefault(),
    private val battleRandomFactory: (Int) -> BattleRandom = ::SeededBattleRandom,
) {
    private val builder = BattleTeamBuilder(config, equipmentRepository)

    fun launchPveBattle(
        state: PlayerState,
        targetWid: Int,
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): PlayerBattleLaunchResult? {
        if (state.activeMarch() != null) return null
        val participants = state.teamHeroes()
            .withIndex()
            .mapNotNull { (position, heroUid) ->
                state.hero(heroUid)
                    ?.takeIf { it.troops > 0 && it.stamina >= STAMINA_COST }
                    ?.let { position to it }
            }
        if (participants.isEmpty()) return null

        participants.forEach { (_, hero) -> hero.stamina -= STAMINA_COST }
        state.startMarch(targetWid = targetWid, nowSec = nowSec)
        return PlayerBattleLaunchResult(battleId = 0, targetWid = targetWid)
    }

    fun settlePveBattle(
        state: PlayerState,
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): PlayerBattleLaunchResult? {
        val march = state.completeMarchIfDue(nowSec) ?: return null
        val participants = state.teamHeroes()
            .withIndex()
            .mapNotNull { (position, heroUid) ->
                state.hero(heroUid)
                    ?.takeIf { it.troops > 0 }
                    ?.let { position to it }
            }
        if (participants.isEmpty()) return null

        val attacker = builder.build(
            participants.map { (position, hero) ->
                BattleHeroSpec(
                    heroId = hero.heroId,
                    position = position,
                    troops = hero.troops,
                    level = hero.level,
                )
            },
        )
        val defender = buildDefender()
        val result = BattleEngine.resolve(
            BattleRequest(attacker = attacker, defender = defender, maxRounds = 8),
            config,
            battleRandomFactory(march.targetWid xor nowSec),
        )

        result.attacker.heroes.forEach { battleHero ->
            val heroUid = state.teamHeroes().getOrElse(battleHero.position) { 0 }
            state.hero(heroUid)?.troops = battleHero.troops.coerceAtLeast(1)
        }
        if (result.outcome == BattleOutcome.ATTACKER_WIN) {
            state.occupyLand(march.targetWid)
        }
        val report = reportStore.record(wid = march.targetWid, timeSec = nowSec, result = result)
        return PlayerBattleLaunchResult(
            battleId = report.battleId,
            targetWid = march.targetWid,
            outcome = result.outcome,
        )
    }

    private fun buildDefender() =
        builder.build(
            listOf(
                BattleHeroSpec(heroId = 100352, position = 0, troops = 800, level = 12),
                BattleHeroSpec(heroId = 100345, position = 1, troops = 800, level = 10),
                BattleHeroSpec(heroId = 100344, position = 2, troops = 800, level = 10),
            ),
        )

    private companion object {
        // Tb_hero.energy stores 1/10,000 display units.
        private const val STAMINA_COST = 200_000
    }
}
