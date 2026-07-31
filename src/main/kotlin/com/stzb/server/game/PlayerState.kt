package com.stzb.server.game

import com.stzb.server.protocol.GameServerConfig
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

class PlayerHero(
    val heroUid: Int,
    val heroId: Int,
    val createdAtSec: Int,
    var armyId: Int = 0,
    troops: Int = 1_000,
    var stamina: Int = MAX_STAMINA,
    var level: Int = DEFAULT_LEVEL,
    var heroType: Int = PlayerHeroTypes.forHero(heroId),
    var dynamicIcon: Int = 0,
    var awakeState: Int = 1,
    var skillIds: MutableList<Int> = HeroCatalog.defaultSkillIds(heroId).toMutableList(),
    var advanceNum: Int = initialAdvanceNum(heroId),
    val isAdvanceMaterial: Boolean = false,
) {
    var troops: Int = troops.coerceIn(0, MAX_TROOPS)
        set(value) {
            field = value.coerceIn(0, MAX_TROOPS)
        }

    fun normalizedSkillIds(): List<Int> =
        (skillIds.take(SKILL_SLOT_COUNT) + List(SKILL_SLOT_COUNT) { 0 })
            .take(SKILL_SLOT_COUNT)
            .mapIndexed { index, skillId ->
                if (index == 0) HeroCatalog.initialSkillId(heroId) else skillId.coerceAtLeast(0)
            }

    fun skillString(): String =
        normalizedSkillIds().joinToString(separator = "") { skillId ->
            if (skillId > 0) "$skillId,$MAX_SKILL_LEVEL;" else "0,0;"
        }

    companion object {
        // Tb_hero.energy uses 1/10,000 display units; 1,000,000 displays as 100 energy.
        const val MAX_STAMINA = 1_000_000
        const val MAX_TROOPS = 10_000
        const val DEFAULT_LEVEL = 50
        const val MAX_SKILL_LEVEL = 10
        const val SKILL_SLOT_COUNT = 3

        fun initialAdvanceNum(heroId: Int): Int =
            HeroCatalog.heroQuality(heroId).coerceAtLeast(0)

        fun maxAdvanceNum(heroId: Int): Int =
            HeroCatalog.heroQuality(heroId).coerceAtLeast(0) + 1
    }
}

data class HeroAdvanceResult(
    val hero: PlayerHero,
    val consumedMaterialUids: List<Int>,
)

data class PlayerMarch(
    val armyId: Int,
    val fromWid: Int,
    val targetWid: Int,
    val beginSec: Int,
    val endSec: Int,
    val participants: List<PlayerMarchHero> = emptyList(),
)

data class PlayerMarchHero(
    val heroUid: Int,
    val position: Int,
    val heroId: Int,
    val troops: Int,
    val level: Int,
    val skillIds: List<Int>,
)

data class PlayerHeroSnapshot(
    val heroUid: Int,
    val heroId: Int,
    val createdAtSec: Int,
    val armyId: Int = 0,
    val troops: Int = 1_000,
    val stamina: Int = PlayerHero.MAX_STAMINA,
    val level: Int = PlayerHero.DEFAULT_LEVEL,
    val heroType: Int = PlayerHeroTypes.forHero(heroId),
    val dynamicIcon: Int = 0,
    val awakeState: Int = 1,
    val skillIds: List<Int> = emptyList(),
    val advanceNum: Int = 0,
    val isAdvanceMaterial: Boolean = false,
)

data class PlayerMarchSnapshot(
    val armyId: Int,
    val fromWid: Int,
    val targetWid: Int,
    val beginSec: Int,
    val endSec: Int,
    val participants: List<PlayerMarchHero> = emptyList(),
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
    val armies: Map<Int, List<Int>> = emptyMap(),
    val march: PlayerMarchSnapshot? = null,
    val marches: Map<Int, PlayerMarchSnapshot> = emptyMap(),
    val occupiedLands: Set<Int> = emptySet(),
    val cardPacksSeen: Boolean = false,
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
    var cityWid: Int,
    var roleName: String,
    val accountKey: String = "legacy-user-$userId",
) {
    val resources = PlayerResources()
    private val buildLevels = ConcurrentHashMap<Int, Int>().apply { this[10] = maxBuildLevel(10) }
    private val heroes = LinkedHashMap<Int, PlayerHero>()
    private val armies = LinkedHashMap<Int, MutableList<Int>>().apply {
        repeat(MAX_ARMIES) { index -> this[cityWid * 10 + index + 1] = MutableList(3) { 0 } }
    }
    private val heroSeq = AtomicInteger(0)
    private val marches = LinkedHashMap<Int, PlayerMarch>()
    private val lands = linkedSetOf<Int>()
    var cardPacksSeen: Boolean = false
        private set

    fun markCardPacksSeen() {
        cardPacksSeen = true
    }

    fun buildLevel(buildId: Int): Int = maxBuildLevel(buildId)

    fun upgradeBuild(buildId: Int, targetLevel: Int): Int {
        val next = maxBuildLevel(buildId)
        buildLevels[buildId] = next
        return next
    }

    fun addHero(
        heroId: Int,
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
        isAdvanceMaterial: Boolean = false,
    ): PlayerHero {
        val heroUid = userId * 100_000 + heroSeq.incrementAndGet()
        val hero = PlayerHero(
            heroUid = heroUid,
            heroId = heroId,
            createdAtSec = nowSec,
            advanceNum = if (isAdvanceMaterial) 0 else PlayerHero.initialAdvanceNum(heroId),
            isAdvanceMaterial = isAdvanceMaterial,
        )
        heroes[heroUid] = hero
        return hero
    }

    fun addAdvanceMaterial(heroId: Int, nowSec: Int = (System.currentTimeMillis() / 1000).toInt()): PlayerHero =
        addHero(heroId, nowSec, isAdvanceMaterial = true)

    fun allHeroes(): List<PlayerHero> =
        heroes.values.toList()

    fun hero(heroUid: Int): PlayerHero? =
        heroes[heroUid]

    /**
     * Each playable copy receives one same-name disposable card. The client
     * requires a selected material before it sends cmd 83.
     */
    fun ensureAdvanceMaterials(nowSec: Int = (System.currentTimeMillis() / 1000).toInt()): List<PlayerHero> {
        val playableCounts = heroes.values
            .filter { !it.isAdvanceMaterial && it.advanceNum < PlayerHero.maxAdvanceNum(it.heroId) }
            .groupingBy { it.heroId }
            .eachCount()
        val materialCounts = heroes.values
            .filter(PlayerHero::isAdvanceMaterial)
            .groupingBy { it.heroId }
            .eachCount()
        return buildList {
            playableCounts.forEach { (heroId, playableCount) ->
                repeat((playableCount - materialCounts.getOrDefault(heroId, 0)).coerceAtLeast(0)) {
                    add(addAdvanceMaterial(heroId, nowSec))
                }
            }
        }
    }

    fun advanceHero(targetHeroUid: Int, materialHeroUids: List<Int>): HeroAdvanceResult? {
        val target = hero(targetHeroUid) ?: return null
        if (target.isAdvanceMaterial) return null

        val materials = materialHeroUids.distinct()
        val remainingAdvances = PlayerHero.maxAdvanceNum(target.heroId) - target.advanceNum
        if (materials.isEmpty() || materials.size > remainingAdvances) return null
        if (materials.any { materialUid ->
                val material = hero(materialUid)
                materialUid == targetHeroUid ||
                    material?.isAdvanceMaterial != true ||
                    material.heroId != target.heroId
            }
        ) {
            return null
        }

        target.advanceNum += materials.size
        materials.forEach(heroes::remove)
        armies.values.forEach { team ->
            team.replaceAll { heroUid -> if (heroUid in materials) 0 else heroUid }
        }
        refreshHeroArmyIds()
        return HeroAdvanceResult(target, materials)
    }

    fun relocateMainCity(newCityWid: Int) {
        require(newCityWid > 0) { "主城坐标必须为正数: $newCityWid" }
        if (cityWid == newCityWid) return

        val oldCityWid = cityWid
        val savedArmies = (0 until MAX_ARMIES).map { index ->
            armies[oldCityWid * 10 + index + 1]?.toList() ?: List(3) { 0 }
        }
        cityWid = newCityWid
        armies.clear()
        savedArmies.forEachIndexed { index, slots ->
            armies[cityWid * 10 + index + 1] = slots.toMutableList()
        }
        marches.clear()
        lands.clear()
        refreshHeroArmyIds()
    }

    fun selectHeroFacade(heroUid: Int, facadeHeroId: Int): Boolean {
        val hero = hero(heroUid) ?: return false
        if (facadeHeroId != 0 && !HeroFacadeCatalog.canUse(facadeHeroId, hero.heroId)) return false
        hero.dynamicIcon = facadeHeroId
        return true
    }

    fun learnHeroSkill(heroUid: Int, skillId: Int, slotIndex: Int): Boolean {
        val hero = hero(heroUid) ?: return false
        if (skillId <= 0 || slotIndex !in 2..PlayerHero.SKILL_SLOT_COUNT) return false
        val slots = hero.normalizedSkillIds().toMutableList()
        slots[slotIndex - 1] = skillId
        hero.skillIds = slots
        return true
    }

    fun forgetHeroSkill(heroUid: Int, skillId: Int): Boolean {
        val hero = hero(heroUid) ?: return false
        val slots = hero.normalizedSkillIds().toMutableList()
        val slotIndex = slots.indexOfFirst { it == skillId }
        if (slotIndex !in 1 until PlayerHero.SKILL_SLOT_COUNT) return false
        slots[slotIndex] = 0
        hero.skillIds = slots
        return true
    }

    fun saveTeam(heroUids: List<Int>, armyId: Int = primaryArmyId()) {
        val team = armySlots(armyId)
        val normalized = heroUids.take(3) + List((3 - heroUids.size).coerceAtLeast(0)) { 0 }
        repeat(3) { team[it] = normalized[it] }
        refreshHeroArmyIds()
    }

    fun assignTeamHero(heroUid: Int, pos: Int, armyId: Int = primaryArmyId()) {
        if (heroUid <= 0 || hero(heroUid) == null) return
        val index = (pos - 1).coerceIn(0, 2)
        armies.values.forEach { team ->
            repeat(3) {
                if (team[it] == heroUid) team[it] = 0
            }
        }
        hero(heroUid)?.takeIf { it.stamina <= 0 }?.stamina = PlayerHero.MAX_STAMINA
        armySlots(armyId)[index] = heroUid
        refreshHeroArmyIds()
    }

    fun removeTeamHero(pos: Int, armyId: Int = primaryArmyId()): Int {
        val team = armySlots(armyId)
        val index = (pos - 1).coerceIn(0, 2)
        val removedHeroUid = team[index]
        team[index] = 0
        refreshHeroArmyIds()
        return removedHeroUid
    }

    fun switchTeamHeroes(
        pos1: Int,
        pos2: Int,
        armyId1: Int = primaryArmyId(),
        armyId2: Int = armyId1,
    ): List<Int> {
        val team1 = armySlots(armyId1)
        val team2 = armySlots(armyId2)
        val index1 = (pos1 - 1).coerceIn(0, 2)
        val index2 = (pos2 - 1).coerceIn(0, 2)
        if (armyId1 == armyId2 && index1 == index2) return listOfNotNull(team1[index1].takeIf { it > 0 })
        val first = team1[index1]
        team1[index1] = team2[index2]
        team2[index2] = first
        refreshHeroArmyIds()
        return listOf(team1[index1], team2[index2]).filter { it > 0 }
    }

    fun teamHeroes(armyId: Int = primaryArmyId()): List<Int> =
        armySlots(armyId).toList()

    fun armyIds(): List<Int> = armies.keys.toList()

    fun primaryArmyId(): Int =
        cityWid * 10 + 1

    fun startMarch(
        targetWid: Int,
        nowSec: Int,
        armyId: Int = primaryArmyId(),
        participants: List<PlayerMarchHero> = emptyList(),
    ): PlayerMarch {
        val beginSec = nowSec.coerceAtLeast(1)
        return PlayerMarch(
            armyId = normalizeArmyId(armyId),
            fromWid = cityWid,
            targetWid = targetWid,
            beginSec = beginSec,
            endSec = beginSec + MARCH_DURATION_SECONDS,
            participants = participants,
        ).also { marches[it.armyId] = it }
    }

    fun activeMarch(armyId: Int = primaryArmyId()): PlayerMarch? =
        marches[normalizeArmyId(armyId)]

    fun activeMarches(): List<PlayerMarch> = marches.values.toList()

    fun completeMarchIfDue(
        nowSec: Int,
        armyId: Int = primaryArmyId(),
    ): PlayerMarch? {
        val normalizedArmyId = normalizeArmyId(armyId)
        return marches[normalizedArmyId]
            ?.takeIf { nowSec >= it.endSec }
            ?.also { marches.remove(normalizedArmyId) }
    }

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
                    dynamicIcon = hero.dynamicIcon,
                    awakeState = hero.awakeState,
                    skillIds = hero.normalizedSkillIds(),
                    advanceNum = hero.advanceNum,
                    isAdvanceMaterial = hero.isAdvanceMaterial,
                )
            },
            team = teamHeroes(),
            armies = armies.mapValues { it.value.toList() },
            march = activeMarch()?.let {
                PlayerMarchSnapshot(
                    armyId = it.armyId,
                    fromWid = it.fromWid,
                    targetWid = it.targetWid,
                    beginSec = it.beginSec,
                    endSec = it.endSec,
                    participants = it.participants,
                )
            },
            marches = marches.mapValues { (_, march) ->
                PlayerMarchSnapshot(
                    armyId = march.armyId,
                    fromWid = march.fromWid,
                    targetWid = march.targetWid,
                    beginSec = march.beginSec,
                    endSec = march.endSec,
                    participants = march.participants,
                )
            },
            occupiedLands = lands.toSet(),
            cardPacksSeen = cardPacksSeen,
        )

    private fun refreshHeroArmyIds() {
        val heroArmies = armies.flatMap { (armyId, slots) ->
            slots.filter { it > 0 }.map { heroUid -> heroUid to armyId }
        }.toMap()
        heroes.values.forEach { hero ->
            hero.armyId = heroArmies[hero.heroUid] ?: 0
        }
    }

    private fun armySlots(armyId: Int): MutableList<Int> =
        armies.getOrPut(normalizeArmyId(armyId)) { MutableList(3) { 0 } }

    private fun normalizeArmyId(armyId: Int): Int =
        armyId.takeIf { it in primaryArmyId() until (primaryArmyId() + MAX_ARMIES) }
            ?: primaryArmyId()

    companion object {
        private const val MARCH_DURATION_SECONDS = 3
        private const val MAX_ARMIES = 5
        const val MAX_BUILD_LEVEL = 8
        const val MAX_COUNTRY_BUILD_LEVEL = 10

        /**
         * cfg 344 gives each main-city building its own maximum.  Advertising
         * one global level makes BuildTreeUI.RefreshName request the nonexistent
         * next cost row (buildId * 100 + level + 1).
         */
        fun maxBuildLevel(buildId: Int): Int = MAIN_CITY_BUILD_MAX_LEVELS[buildId] ?: 1

        private val MAIN_CITY_BUILD_MAX_LEVELS = mapOf(
            10 to 8,
            13 to 20,
            20 to 20, 21 to 20, 22 to 20, 23 to 20, 24 to 20,
            25 to 1,
            30 to 20,
            31 to 10, 32 to 10, 33 to 10, 34 to 10, 35 to 10,
            36 to 20,
            37 to 10,
            40 to 5, 42 to 5,
            43 to 15,
            44 to 3,
            51 to 10, 52 to 10, 53 to 10, 54 to 10,
            61 to 5, 62 to 6, 63 to 5, 64 to 5, 65 to 5,
            66 to 10,
            67 to 3,
            160 to 10,
        )

        fun fromSnapshot(
            snapshot: PlayerStateSnapshot,
            nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
        ): PlayerState =
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
                state.buildLevels.putAll(
                    snapshot.buildLevels.mapValues { (buildId, _) ->
                        maxBuildLevel(buildId)
                    },
                )
                if (state.buildLevels.isEmpty()) state.buildLevels[10] = maxBuildLevel(10)
                state.heroes.clear()
                snapshot.heroes.forEach { saved ->
                    state.heroes[saved.heroUid] = PlayerHero(
                        heroUid = saved.heroUid,
                        heroId = saved.heroId,
                        createdAtSec = saved.createdAtSec,
                        armyId = saved.armyId,
                        troops = saved.troops.coerceIn(0, PlayerHero.MAX_TROOPS),
                        stamina = PlayerHero.MAX_STAMINA,
                        level = saved.level.coerceAtLeast(PlayerHero.DEFAULT_LEVEL),
                        heroType = saved.heroType.takeIf { it in 1..3 }
                            ?: PlayerHeroTypes.forHero(saved.heroId),
                        dynamicIcon = saved.dynamicIcon.takeIf {
                            it == 0 || HeroFacadeCatalog.canUse(it, saved.heroId)
                        } ?: 0,
                        awakeState = 1,
                        skillIds = normalizedSavedSkillIds(saved.heroId, saved.skillIds),
                        advanceNum = if (saved.isAdvanceMaterial) {
                            0
                        } else {
                            saved.advanceNum
                                .takeIf { it > 0 }
                                ?.coerceAtMost(PlayerHero.maxAdvanceNum(saved.heroId))
                                ?: PlayerHero.initialAdvanceNum(saved.heroId)
                        },
                        isAdvanceMaterial = saved.isAdvanceMaterial,
                    )
                }
                state.heroSeq.set(
                    snapshot.heroes.maxOfOrNull { it.heroUid % 100_000 } ?: 0,
                )
                state.armies.values.forEach { slots -> slots.fill(0) }
                val restoredArmies = snapshot.armies.ifEmpty {
                    mapOf(state.primaryArmyId() to snapshot.team)
                }
                restoredArmies.forEach { (armyId, savedSlots) ->
                    val slots = state.armySlots(armyId)
                    val normalized = savedSlots.take(3) + List((3 - savedSlots.size).coerceAtLeast(0)) { 0 }
                    repeat(3) { slots[it] = normalized[it] }
                }
                state.refreshHeroArmyIds()
                state.marches.clear()
                val restoredMarches = snapshot.marches.ifEmpty {
                    snapshot.march?.let { mapOf(it.armyId to it) }.orEmpty()
                }
                restoredMarches
                    .filterValues { saved -> saved.endSec > nowSec }
                    .forEach { (armyId, saved) ->
                    val normalizedArmyId = state.normalizeArmyId(armyId)
                    state.marches[normalizedArmyId] = PlayerMarch(
                        armyId = normalizedArmyId,
                        fromWid = saved.fromWid,
                        targetWid = saved.targetWid,
                        beginSec = saved.beginSec,
                        endSec = saved.endSec,
                        participants = saved.participants,
                    )
                }
                state.lands.clear()
                state.lands.addAll(snapshot.occupiedLands.filter { it > 0 && it != state.cityWid })
                state.cardPacksSeen = snapshot.cardPacksSeen
            }

        private fun normalizedSavedSkillIds(heroId: Int, savedSkillIds: List<Int>): MutableList<Int> {
            if (savedSkillIds.isEmpty()) return HeroCatalog.defaultSkillIds(heroId).toMutableList()
            return (savedSkillIds.take(PlayerHero.SKILL_SLOT_COUNT) +
                List(PlayerHero.SKILL_SLOT_COUNT) { 0 })
                .take(PlayerHero.SKILL_SLOT_COUNT)
                .mapIndexed { index, skillId ->
                    if (index == 0) HeroCatalog.initialSkillId(heroId) else skillId.coerceAtLeast(0)
                }
                .toMutableList()
        }
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
        return relocateLegacyMainCityIfNeeded(players.computeIfAbsent(accountKey) {
            repository.getOrCreate(accountKey, cityWid, roleName)
        }, cityWid)
    }

    fun getOrCreate(userId: Int, cityWid: Int, roleName: String): PlayerState {
        val accountKey = "legacy-user-$userId"
        return relocateLegacyMainCityIfNeeded(players.computeIfAbsent(accountKey) {
            repository.findByAccount(accountKey)
                ?: PlayerState(
                    userId = userId,
                    cityWid = cityWid,
                    roleName = roleName,
                    accountKey = accountKey,
                ).also(repository::save)
        }, cityWid)
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

    private fun relocateLegacyMainCityIfNeeded(state: PlayerState, requestedCityWid: Int): PlayerState {
        if (
            state.cityWid == GameServerConfig.LEGACY_CITY_WID &&
            requestedCityWid == GameServerConfig.CITY_WID
        ) {
            state.relocateMainCity(requestedCityWid)
            repository.save(state)
        }
        return state
    }

    private fun defaultRoot() =
        java.nio.file.Path.of(System.getenv("STZB_DATA_DIR") ?: "data")
}
