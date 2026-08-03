package com.stzb.server.game

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.protocol.GameServerConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.exists

object GameHome {
    const val LUOYANG_WID = 15_011_501
    const val DEFAULT_CITY_WID = GameServerConfig.CITY_WID
    private const val LUOYANG_X = 1501
    private const val LUOYANG_Y = 1501
    private const val MAP_SIZE = 3001
    private const val MIN_SPAWN_RADIUS = 5
    private const val MAX_SPAWN_RADIUS = 400

    fun spawnCandidates(): Sequence<Int> = sequence {
        for (radius in MIN_SPAWN_RADIUS..MAX_SPAWN_RADIUS) {
            for (xOffset in -radius..radius) {
                for (yOffset in -radius..radius) {
                    if (maxOf(kotlin.math.abs(xOffset), kotlin.math.abs(yOffset)) != radius) continue
                    val x = LUOYANG_X + xOffset
                    val y = LUOYANG_Y + yOffset
                    if (x in 2 until MAP_SIZE && y in 2 until MAP_SIZE) {
                        yield(x * 10_000 + y)
                    }
                }
            }
        }
    }
}

data class WorldCity(
    val cityWid: Int,
    val userId: Int,
    val roleName: String,
    val customView: String = FacadeCatalog.DEFAULT_CITY_CUSTOM_VIEW,
)

data class LandClaim(
    val wid: Int,
    val userId: Int,
    val belongCity: Int,
    val claimedAtSec: Int,
)

data class GarrisonSnapshot(
    val wid: Int,
    val ownerUserId: Int,
    val armyId: Int,
    val specs: List<com.stzb.server.game.battle.BattleHeroSpec>,
    val residedAtSec: Int,
)

data class WorldStateSnapshot(
    val version: Int = 1,
    val cities: List<WorldCity> = emptyList(),
    val lands: List<LandClaim> = emptyList(),
)

data class WorldProjection(
    val cities: List<WorldCity>,
    val lands: List<LandClaim>,
) {
    fun withPlayer(state: PlayerState): WorldProjection {
        return withPlayer(
            userId = state.userId,
            cityWid = state.cityWid,
            roleName = state.roleName,
            occupiedLands = state.occupiedLands(),
        )
    }

    fun withPlayer(
        userId: Int,
        cityWid: Int,
        roleName: String,
        occupiedLands: Set<Int> = emptySet(),
    ): WorldProjection {
        val city = WorldCity(cityWid, userId, roleName)
        val citiesByUser = cities.associateBy(WorldCity::userId).toMutableMap()
        citiesByUser.putIfAbsent(userId, city)

        val landsByWid = lands.associateBy(LandClaim::wid).toMutableMap()
        occupiedLands.forEach { wid ->
            landsByWid.putIfAbsent(
                wid,
                LandClaim(wid, userId, cityWid, claimedAtSec = 0),
            )
        }
        return WorldProjection(
            cities = citiesByUser.values.sortedBy(WorldCity::userId),
            lands = landsByWid.values.sortedBy(LandClaim::wid),
        )
    }

    companion object {
        val EMPTY = WorldProjection(emptyList(), emptyList())
    }
}

interface WorldRepository {
    fun load(): WorldStateSnapshot
    fun save(snapshot: WorldStateSnapshot)
}

class FileWorldRepository(
    private val root: Path,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : WorldRepository {
    private val path = root.resolve("world.json")

    override fun load(): WorldStateSnapshot {
        if (!path.exists()) return WorldStateSnapshot()
        return runCatching {
            mapper.readValue(path.toFile(), WorldStateSnapshot::class.java)
        }.getOrElse {
            val backup = path.resolveSibling("${path.fileName}.corrupt.${System.currentTimeMillis()}")
            Files.move(path, backup, REPLACE_EXISTING)
            WorldStateSnapshot()
        }
    }

    override fun save(snapshot: WorldStateSnapshot) {
        Files.createDirectories(root)
        val temporary = path.resolveSibling(
            "${path.fileName}.tmp-${ProcessHandle.current().pid()}-${System.nanoTime()}",
        )
        val bytes = mapper.writeValueAsBytes(snapshot)
        try {
            FileChannel.open(temporary, WRITE, CREATE_NEW).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/**
 * The world owns city and land uniqueness. PlayerState keeps a denormalized
 * land index for existing protocol builders and is reconciled on every login.
 */
class WorldService(
    private val repository: WorldRepository,
    private val savePlayer: (PlayerState) -> Unit,
) {
    private val lock = ReentrantReadWriteLock()
    private val citiesByUser = LinkedHashMap<Int, WorldCity>()
    private val landsByWid = LinkedHashMap<Int, LandClaim>()
    private val garrisonsByWid = LinkedHashMap<Int, GarrisonSnapshot>()

    init {
        val snapshot = repository.load()
        snapshot.cities.forEach { city -> citiesByUser[city.userId] = city }
        snapshot.lands.forEach { claim -> landsByWid[claim.wid] = claim }
    }

    fun registerOrRestorePlayer(state: PlayerState): PlayerState = lock.write {
        val existing = citiesByUser[state.userId]
        val city = when {
            existing != null -> existing.copy(roleName = state.roleName)
            isCityFootprintAvailable(state.cityWid) ->
                WorldCity(state.cityWid, state.userId, state.roleName)
            else ->
                WorldCity(allocateCityWid(), state.userId, state.roleName)
        }
        val cityChanged = existing != city
        citiesByUser[state.userId] = city
        if (state.cityWid != city.cityWid) state.relocateMainCity(city.cityWid)

        val claimedLands = landsByWid.values
            .filter { it.userId == state.userId }
            .map(LandClaim::wid)
        state.replaceOccupiedLands(claimedLands)

        if (cityChanged) persist()
        savePlayer(state)
        state
    }

    fun claimLand(state: PlayerState, wid: Int, nowSec: Int): Boolean = lock.write {
        val previous = landsByWid[wid]
        when {
            previous != null -> return@write previous.userId == state.userId
            wid <= 0 ||
                isCityFootprintWid(wid) ||
                (StaticCityCatalog.contains(wid) && wid != GameHome.LUOYANG_WID) ->
                return@write false
        }

        landsByWid[wid] = LandClaim(
            wid = wid,
            userId = state.userId,
            belongCity = state.cityWid,
            claimedAtSec = nowSec,
        )
        state.occupyLand(wid)
        persist()
        savePlayer(state)
        true
    }

    fun updateCityCustomView(state: PlayerState, cityWid: Int, customView: String): Boolean = lock.write {
        if (cityWid != state.cityWid) return@write false
        val normalizedView = CityFacadeLayout.normalize(customView) ?: return@write false
        val city = citiesByUser[state.userId] ?: return@write false
        if (city.cityWid != cityWid) return@write false
        if (city.customView == normalizedView) return@write false

        citiesByUser[state.userId] = city.copy(customView = normalizedView)
        persist()
        true
    }

    fun projection(): WorldProjection = lock.read {
        WorldProjection(
            cities = citiesByUser.values.sortedBy(WorldCity::userId),
            lands = landsByWid.values.sortedBy(LandClaim::wid),
        )
    }

    fun ownerOf(wid: Int): LandClaim? = lock.read { landsByWid[wid] }

    fun putGarrison(snapshot: GarrisonSnapshot): Unit = lock.write {
        garrisonsByWid[snapshot.wid] = snapshot
    }

    fun garrisonAt(wid: Int): GarrisonSnapshot? = lock.read { garrisonsByWid[wid] }

    fun removeGarrison(wid: Int): GarrisonSnapshot? = lock.write {
        garrisonsByWid.remove(wid)
    }

    fun garrisons(): List<GarrisonSnapshot> = lock.read { garrisonsByWid.values.toList() }

    private fun allocateCityWid(): Int =
        GameHome.spawnCandidates()
            .firstOrNull(::isCityFootprintAvailable)
            ?: error("洛阳周边没有可用的 3x3 主城出生点")

    private fun isCityFootprintAvailable(cityWid: Int): Boolean {
        val footprint = cityFootprint(cityWid)
        return footprint.none { wid ->
            StaticCityCatalog.contains(wid) ||
                landsByWid.containsKey(wid) ||
                citiesByUser.values.any { existing -> wid in cityFootprint(existing.cityWid) }
        }
    }

    private fun isCityFootprintWid(wid: Int): Boolean =
        citiesByUser.values.any { city -> wid in cityFootprint(city.cityWid) }

    private fun cityFootprint(cityWid: Int): Set<Int> =
        (HomeCity.suburbWids(cityWid) + cityWid).toSet()

    private fun persist() {
        repository.save(
            WorldStateSnapshot(
                cities = citiesByUser.values.sortedBy(WorldCity::userId),
                lands = landsByWid.values.sortedBy(LandClaim::wid),
            ),
        )
    }
}

object WorldStateRepository {
    @Volatile
    private var service = defaultService()

    @Synchronized
    fun configure(root: Path) {
        service = WorldService(FileWorldRepository(root), PlayerStateRepository::save)
        UnionStateRepository.configure(root)
    }

    @Synchronized
    fun reset() {
        service = defaultService()
        UnionStateRepository.reset()
    }

    fun registerOrRestorePlayer(state: PlayerState): PlayerState =
        service.registerOrRestorePlayer(state)

    fun claimLand(state: PlayerState, wid: Int, nowSec: Int): Boolean =
        service.claimLand(state, wid, nowSec)

    fun updateCityCustomView(state: PlayerState, cityWid: Int, customView: String): Boolean =
        service.updateCityCustomView(state, cityWid, customView)

    fun projection(): WorldProjection = service.projection()

    fun ownerOf(wid: Int): LandClaim? = service.ownerOf(wid)

    fun putGarrison(snapshot: GarrisonSnapshot): Unit = service.putGarrison(snapshot)

    fun garrisonAt(wid: Int): GarrisonSnapshot? = service.garrisonAt(wid)

    fun removeGarrison(wid: Int): GarrisonSnapshot? = service.removeGarrison(wid)

    fun garrisons(): List<GarrisonSnapshot> = service.garrisons()

    private fun defaultService(): WorldService {
        val root = Path.of(System.getenv("STZB_DATA_DIR") ?: "data")
        return WorldService(FileWorldRepository(root), PlayerStateRepository::save)
    }
}

/**
 * The cfg-5 Luoyang model occupies its anchor tile and the tile immediately
 * north. New spawns start at radius five, but keeping these explicit guards
 * also protects legacy/default locations from accidental claims.
 */
/**
 * Explicit NPC-city cells from the client configuration for the configured
 * season. Dynamic auxiliary rows are intentionally not included: they are
 * derived at runtime from map-area data, while these rows are hard blockers
 * for player home-city allocation and land claims.
 */
object StaticCityCatalog {
    private val wids: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        loadExplicitCityWids()
    }

    val count: Int
        get() = wids.size

    fun contains(wid: Int): Boolean = wid in wids

    private fun loadExplicitCityWids(): Set<Int> {
        val resource = "/client-config/tb_cfg_world_city_${GameServerConfig.CFG_DB_ID}.bin"
        val bytes = requireNotNull(StaticCityCatalog::class.java.getResourceAsStream(resource)) {
            "missing static city configuration $resource"
        }.use { it.readBytes() }
        val reader = StaticCityTableReader(bytes)
        val stringTableLength = reader.int()
        val stringTableEnd = reader.position + stringTableLength
        require(stringTableEnd in 4..bytes.size) {
            "invalid string table in $resource"
        }
        reader.position = stringTableEnd
        require(reader.unsignedByte() == 4) {
            "invalid world city table group header in $resource"
        }
        require(reader.unsignedByte() == 2) {
            "invalid world city row dictionary in $resource"
        }
        val rowCount = reader.int()
        require(rowCount in 1..100_000) {
            "invalid world city row count $rowCount in $resource"
        }
        val keys = List(rowCount) { reader.int() }
        require(reader.int() == rowCount) {
            "world city key/value count mismatch in $resource"
        }

        return buildSet(rowCount) {
            keys.forEach { key ->
                require(reader.unsignedByte() == 13) {
                    "invalid world city row in $resource"
                }
                val wid = reader.int()
                require(wid == key) {
                    "world city key $key does not match row wid $wid in $resource"
                }
                repeat(8) { reader.int() }
                repeat(4) { reader.int() }
                add(wid)
            }
        }
    }
}

private class StaticCityTableReader(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    var position: Int
        get() = buffer.position()
        set(value) {
            buffer.position(value)
        }

    fun unsignedByte(): Int = buffer.get().toInt() and 0xff

    fun int(): Int = buffer.int
}
