package com.stzb.server.game

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.exists

data class PlayerUnion(
    val unionId: Int,
    val name: String,
    val leaderUserId: Int,
    val leaderRoleName: String,
    val createdAtSec: Int,
    val memberUserIds: Set<Int>,
)

data class UnionStateSnapshot(
    val nextUnionId: Int = FIRST_UNION_ID,
    val unions: List<PlayerUnion> = emptyList(),
)

interface UnionRepository {
    fun load(): UnionStateSnapshot
    fun save(snapshot: UnionStateSnapshot)
}

class FileUnionRepository(
    private val root: Path,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : UnionRepository {
    private val path = root.resolve("unions.json")

    override fun load(): UnionStateSnapshot {
        if (!path.exists()) return UnionStateSnapshot()
        return runCatching {
            mapper.readValue(path.toFile(), UnionStateSnapshot::class.java)
        }.getOrElse {
            val backup = path.resolveSibling("${path.fileName}.corrupt.${System.currentTimeMillis()}")
            Files.move(path, backup, REPLACE_EXISTING)
            UnionStateSnapshot()
        }
    }

    override fun save(snapshot: UnionStateSnapshot) {
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

class UnionService(
    private val repository: UnionRepository,
) {
    private val lock = ReentrantReadWriteLock()
    private val unionsById = LinkedHashMap<Int, PlayerUnion>()
    private val unionIdByUser = LinkedHashMap<Int, Int>()
    private var nextUnionId: Int

    init {
        val snapshot = repository.load()
        nextUnionId = snapshot.nextUnionId.coerceAtLeast(FIRST_UNION_ID)
        snapshot.unions
            .filter { union -> union.unionId >= FIRST_UNION_ID && union.name.isNotBlank() }
            .sortedBy(PlayerUnion::unionId)
            .forEach { union ->
                val normalized = union.copy(
                    name = union.name.trim(),
                    memberUserIds = union.memberUserIds.filter { it > 0 }.toSortedSet(),
                )
                unionsById[normalized.unionId] = normalized
                normalized.memberUserIds.forEach { userId -> unionIdByUser.putIfAbsent(userId, normalized.unionId) }
                nextUnionId = maxOf(nextUnionId, normalized.unionId + 1)
            }
    }

    fun create(state: PlayerState, requestedName: String, nowSec: Int): Int = lock.write {
        unionIdByUser[state.userId]?.let { return@write it }

        val name = requestedName.trim().take(MAX_UNION_NAME_LENGTH)
        if (name.isBlank() || unionsById.values.any { it.name == name }) return@write 0

        val union = PlayerUnion(
            unionId = nextUnionId++,
            name = name,
            leaderUserId = state.userId,
            leaderRoleName = state.roleName,
            createdAtSec = nowSec,
            memberUserIds = sortedSetOf(state.userId),
        )
        unionsById[union.unionId] = union
        unionIdByUser[state.userId] = union.unionId
        persist()
        union.unionId
    }

    fun find(unionId: Int): PlayerUnion? = lock.read { unionsById[unionId] }

    fun forUser(userId: Int): PlayerUnion? = lock.read {
        unionIdByUser[userId]?.let(unionsById::get)
    }

    fun all(): List<PlayerUnion> = lock.read {
        Collections.unmodifiableList(
            unionsById.values
                .sortedBy(PlayerUnion::unionId)
                .map { union ->
                    union.copy(
                        memberUserIds = Collections.unmodifiableSet(
                            LinkedHashSet(union.memberUserIds.sorted()),
                        ),
                    )
                },
        )
    }

    private fun persist() {
        repository.save(
            UnionStateSnapshot(
                nextUnionId = nextUnionId,
                unions = unionsById.values.sortedBy(PlayerUnion::unionId),
            ),
        )
    }
}

object UnionStateRepository {
    @Volatile
    private var service = defaultService()

    @Synchronized
    fun configure(root: Path) {
        service = UnionService(FileUnionRepository(root))
    }

    @Synchronized
    fun reset() {
        service = defaultService()
    }

    fun create(state: PlayerState, requestedName: String, nowSec: Int): Int =
        service.create(state, requestedName, nowSec)

    fun find(unionId: Int): PlayerUnion? = service.find(unionId)

    fun forUser(userId: Int): PlayerUnion? = service.forUser(userId)

    fun all(): List<PlayerUnion> = service.all()

    private fun defaultService(): UnionService {
        val root = Path.of(System.getenv("STZB_DATA_DIR") ?: "data")
        return UnionService(FileUnionRepository(root))
    }
}

private const val FIRST_UNION_ID = 1_001
private const val MAX_UNION_NAME_LENGTH = 16
