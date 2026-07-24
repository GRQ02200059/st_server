package com.stzb.server.game

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class PlayerResources(
    var money: Int = UNLIMITED_AMOUNT,
    var wood: Int = UNLIMITED_AMOUNT,
    var stone: Int = UNLIMITED_AMOUNT,
    var iron: Int = UNLIMITED_AMOUNT,
    var food: Int = UNLIMITED_AMOUNT,
    var yuanBao: Int = UNLIMITED_AMOUNT,
    var hufu: Int = UNLIMITED_AMOUNT,
    var freeYuanBao: Int = UNLIMITED_AMOUNT,
) {
    companion object {
        const val UNLIMITED_AMOUNT = 2_000_000_000
    }
}

data class PlayerHero(
    val heroUid: Int,
    val heroId: Int,
    val createdAtSec: Int,
    var armyId: Int = 0,
    var troops: Int = 1_000,
    var stamina: Int = MAX_STAMINA,
    var level: Int = 1,
    var heroType: Int = PlayerHeroTypes.forHero(heroId),
) {
    companion object {
        // Tb_hero.energy uses 1/10,000 display units; 1,000,000 displays as 100 energy.
        const val MAX_STAMINA = 1_000_000
    }
}

data class PlayerMarch(
    val armyId: Int,
    val fromWid: Int,
    val targetWid: Int,
    val beginSec: Int,
    val endSec: Int,
)

/**
 * Tb_hero.hero_type is consumed directly by the client when calculating army
 * bonuses. Its low digit must be one of infantry (1), cavalry (2), or archer
 * (3); a missing value becomes 0 and crashes the client's grouping logic.
 */
object PlayerHeroTypes {
    fun forHero(heroId: Int): Int = HeroCatalog.heroType(heroId)
}

class PlayerState(
    val userId: Int,
    val cityWid: Int,
    var roleName: String,
) {
    val resources = PlayerResources()
    private val buildLevels = ConcurrentHashMap<Int, Int>().apply { this[10] = 1 }
    private val heroes = LinkedHashMap<Int, PlayerHero>()
    private val team = MutableList(3) { 0 }
    private val heroSeq = AtomicInteger(0)
    private var march: PlayerMarch? = null

    fun buildLevel(buildId: Int): Int =
        buildLevels[buildId] ?: 1

    fun upgradeBuild(buildId: Int, targetLevel: Int): Int {
        val next = maxOf(buildLevel(buildId) + 1, targetLevel).coerceIn(1, 20)
        buildLevels[buildId] = next
        return next
    }

    fun addHero(heroId: Int, nowSec: Int = (System.currentTimeMillis() / 1000).toInt()): PlayerHero {
        val heroUid = userId * 100_000 + heroSeq.incrementAndGet()
        val hero = PlayerHero(heroUid = heroUid, heroId = heroId, createdAtSec = nowSec)
        heroes[heroUid] = hero
        return hero
    }

    fun allHeroes(): List<PlayerHero> =
        heroes.values.toList()

    fun hero(heroUid: Int): PlayerHero? =
        heroes[heroUid]

    fun saveTeam(heroUids: List<Int>) {
        val normalized = heroUids.take(3) + List((3 - heroUids.size).coerceAtLeast(0)) { 0 }
        repeat(3) { team[it] = normalized[it] }
        refreshHeroArmyIds()
    }

    fun assignTeamHero(heroUid: Int, pos: Int) {
        if (heroUid <= 0 || hero(heroUid) == null) return
        val index = (pos - 1).coerceIn(0, 2)
        repeat(3) {
            if (team[it] == heroUid) team[it] = 0
        }
        hero(heroUid)?.takeIf { it.stamina <= 0 }?.stamina = PlayerHero.MAX_STAMINA
        team[index] = heroUid
        refreshHeroArmyIds()
    }

    fun removeTeamHero(pos: Int): Int {
        val index = (pos - 1).coerceIn(0, 2)
        val removedHeroUid = team[index]
        team[index] = 0
        refreshHeroArmyIds()
        return removedHeroUid
    }

    fun switchTeamHeroes(pos1: Int, pos2: Int): List<Int> {
        val index1 = (pos1 - 1).coerceIn(0, 2)
        val index2 = (pos2 - 1).coerceIn(0, 2)
        if (index1 == index2) return listOfNotNull(team[index1].takeIf { it > 0 })
        val first = team[index1]
        team[index1] = team[index2]
        team[index2] = first
        refreshHeroArmyIds()
        return listOf(team[index1], team[index2]).filter { it > 0 }
    }

    fun teamHeroes(): List<Int> =
        team.toList()

    fun primaryArmyId(): Int =
        cityWid * 10 + 1

    fun startMarch(targetWid: Int, nowSec: Int): PlayerMarch {
        val beginSec = nowSec.coerceAtLeast(1)
        return PlayerMarch(
            armyId = primaryArmyId(),
            fromWid = cityWid,
            targetWid = targetWid,
            beginSec = beginSec,
            endSec = beginSec + MARCH_DURATION_SECONDS,
        ).also { march = it }
    }

    fun activeMarch(): PlayerMarch? =
        march

    fun completeMarchIfDue(nowSec: Int): PlayerMarch? =
        march?.takeIf { nowSec >= it.endSec }?.also { march = null }

    private fun refreshHeroArmyIds() {
        val armyId = primaryArmyId()
        heroes.values.forEach { hero ->
            hero.armyId = if (hero.heroUid in team) armyId else 0
        }
    }

    private companion object {
        const val MARCH_DURATION_SECONDS = 3
    }
}

object PlayerStateRepository {
    private val players = ConcurrentHashMap<Int, PlayerState>()

    fun getOrCreate(userId: Int, cityWid: Int, roleName: String): PlayerState =
        players.compute(userId) { _, old ->
            old?.apply { this.roleName = roleName } ?: PlayerState(userId, cityWid, roleName)
        }!!
}
