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

data class PlayerHeroSnapshot(
    val heroUid: Int,
    val heroId: Int,
    val createdAtSec: Int,
    val armyId: Int = 0,
    val troops: Int = 1_000,
    val stamina: Int = PlayerHero.MAX_STAMINA,
    val level: Int = 1,
    val heroType: Int = PlayerHeroTypes.forHero(heroId),
)

data class PlayerMarchSnapshot(
    val armyId: Int,
    val fromWid: Int,
    val targetWid: Int,
    val beginSec: Int,
    val endSec: Int,
)

data class PlayerStateSnapshot(
    val accountKey: String,
    val userId: Int,
    val cityWid: Int,
    val roleName: String,
    val resources: PlayerResources = PlayerResources(),
    val buildLevels: Map<Int, Int> = emptyMap(),
    val heroes: List<PlayerHeroSnapshot> = emptyList(),
    val team: List<Int> = List(3) { 0 },
    val march: PlayerMarchSnapshot? = null,
    val occupiedLands: Set<Int> = emptySet(),
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
    val accountKey: String = "legacy-user-$userId",
) {
    val resources = PlayerResources()
    private val buildLevels = ConcurrentHashMap<Int, Int>().apply { this[10] = 1 }
    private val heroes = LinkedHashMap<Int, PlayerHero>()
    private val team = MutableList(3) { 0 }
    private val heroSeq = AtomicInteger(0)
    private var march: PlayerMarch? = null
    private val lands = linkedSetOf<Int>()

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

    fun occupyLand(wid: Int) {
        if (wid > 0 && wid != cityWid) lands += wid
    }

    fun ownsLand(wid: Int): Boolean = wid in lands

    fun occupiedLands(): Set<Int> = lands.toSet()

    fun toSnapshot(): PlayerStateSnapshot =
        PlayerStateSnapshot(
            accountKey = accountKey,
            userId = userId,
            cityWid = cityWid,
            roleName = roleName,
            resources = resources.copy(),
            buildLevels = buildLevels.toMap(),
            heroes = heroes.values.map { hero ->
                PlayerHeroSnapshot(
                    heroUid = hero.heroUid,
                    heroId = hero.heroId,
                    createdAtSec = hero.createdAtSec,
                    armyId = hero.armyId,
                    troops = hero.troops,
                    stamina = hero.stamina,
                    level = hero.level,
                    heroType = hero.heroType,
                )
            },
            team = team.toList(),
            march = march?.let {
                PlayerMarchSnapshot(
                    armyId = it.armyId,
                    fromWid = it.fromWid,
                    targetWid = it.targetWid,
                    beginSec = it.beginSec,
                    endSec = it.endSec,
                )
            },
            occupiedLands = lands.toSet(),
        )

    private fun refreshHeroArmyIds() {
        val armyId = primaryArmyId()
        heroes.values.forEach { hero ->
            hero.armyId = if (hero.heroUid in team) armyId else 0
        }
    }

    companion object {
        private const val MARCH_DURATION_SECONDS = 3

        fun fromSnapshot(snapshot: PlayerStateSnapshot): PlayerState =
            PlayerState(
                userId = snapshot.userId,
                cityWid = snapshot.cityWid,
                roleName = snapshot.roleName,
                accountKey = snapshot.accountKey,
            ).also { state ->
                state.resources.money = snapshot.resources.money
                state.resources.wood = snapshot.resources.wood
                state.resources.stone = snapshot.resources.stone
                state.resources.iron = snapshot.resources.iron
                state.resources.food = snapshot.resources.food
                state.resources.yuanBao = snapshot.resources.yuanBao
                state.resources.hufu = snapshot.resources.hufu
                state.resources.freeYuanBao = snapshot.resources.freeYuanBao
                state.buildLevels.clear()
                state.buildLevels.putAll(snapshot.buildLevels)
                if (state.buildLevels.isEmpty()) state.buildLevels[10] = 1
                state.heroes.clear()
                snapshot.heroes.forEach { saved ->
                    state.heroes[saved.heroUid] = PlayerHero(
                        heroUid = saved.heroUid,
                        heroId = saved.heroId,
                        createdAtSec = saved.createdAtSec,
                        armyId = saved.armyId,
                        troops = saved.troops,
                        stamina = migrateLegacyStamina(saved.stamina),
                        level = saved.level,
                        heroType = saved.heroType.takeIf { it in 1..3 }
                            ?: PlayerHeroTypes.forHero(saved.heroId),
                    )
                }
                state.heroSeq.set(
                    snapshot.heroes.maxOfOrNull { it.heroUid % 100_000 } ?: 0,
                )
                state.team.clear()
                state.team.addAll(snapshot.team.take(3).let { it + List(3 - it.size) { 0 } })
                state.march = snapshot.march?.let {
                    PlayerMarch(
                        armyId = it.armyId,
                        fromWid = it.fromWid,
                        targetWid = it.targetWid,
                        beginSec = it.beginSec,
                        endSec = it.endSec,
                    )
                }
                state.lands.clear()
                state.lands.addAll(snapshot.occupiedLands.filter { it > 0 && it != state.cityWid })
            }

        private fun migrateLegacyStamina(stamina: Int): Int =
            if (stamina in 1..100) stamina * 10_000 else stamina
    }
}

object PlayerStateRepository {
    private val players = ConcurrentHashMap<String, PlayerState>()
    @Volatile
    private var repository: PlayerRepository = FilePlayerRepository(defaultRoot())

    @Synchronized
    fun configure(repository: PlayerRepository) {
        this.repository = repository
        players.clear()
    }

    @Synchronized
    fun reset() {
        repository = FilePlayerRepository(defaultRoot())
        players.clear()
    }

    fun getOrCreate(accountKey: String, cityWid: Int, roleName: String): PlayerState {
        require(accountKey.isNotBlank()) { "accountKey 不能为空" }
        return players.computeIfAbsent(accountKey) {
            repository.getOrCreate(accountKey, cityWid, roleName)
        }
    }

    fun getOrCreate(userId: Int, cityWid: Int, roleName: String): PlayerState {
        val accountKey = "legacy-user-$userId"
        return players.computeIfAbsent(accountKey) {
            repository.findByAccount(accountKey)
                ?: PlayerState(
                    userId = userId,
                    cityWid = cityWid,
                    roleName = roleName,
                    accountKey = accountKey,
                ).also(repository::save)
        }
    }

    /**
     * Resolve every request in an authenticated connection through the same
     * account key used by login. Older builds accidentally wrote gameplay
     * state to legacy-user-{userId}; migrate that state when the account save
     * is still empty so existing heroes are not lost.
     */
    @Synchronized
    fun getOrCreateForSession(
        accountKey: String,
        userId: Int,
        cityWid: Int,
        roleName: String,
    ): PlayerState {
        val accountState = getOrCreate(accountKey, cityWid, roleName)
        if (accountState.allHeroes().isNotEmpty() || accountKey == "legacy-user-$userId") {
            return accountState
        }

        val legacyKey = "legacy-user-$userId"
        val legacyState = players[legacyKey] ?: repository.findByAccount(legacyKey)
        if (legacyState == null || legacyState.allHeroes().isEmpty()) return accountState

        return PlayerState.fromSnapshot(
            legacyState.toSnapshot().copy(
                accountKey = accountKey,
                userId = userId,
                cityWid = cityWid,
                roleName = accountState.roleName,
            ),
        ).also { migrated ->
            players[accountKey] = migrated
            repository.save(migrated)
        }
    }

    fun save(state: PlayerState) {
        repository.save(state)
    }

    private fun defaultRoot() =
        java.nio.file.Path.of(System.getenv("STZB_DATA_DIR") ?: "data")
}
