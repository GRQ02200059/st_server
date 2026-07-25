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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import kotlin.io.path.exists

interface PlayerRepository {
    fun findByAccount(accountKey: String): PlayerState?

    fun getOrCreate(accountKey: String, cityWid: Int, roleName: String): PlayerState

    fun save(state: PlayerState)
}

class FilePlayerRepository(
    private val root: Path,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) : PlayerRepository {
    private val accountsDir = root.resolve("accounts")
    private val locks = ConcurrentHashMap<String, Any>()

    override fun findByAccount(accountKey: String): PlayerState? =
        synchronized(lockFor(accountKey)) {
            read(accountKey)
        }

    override fun getOrCreate(accountKey: String, cityWid: Int, roleName: String): PlayerState =
        synchronized(lockFor(accountKey)) {
            read(accountKey) ?: PlayerState(
                userId = nextUserId(),
                cityWid = cityWid,
                roleName = roleName,
                accountKey = accountKey,
            ).also(::saveLocked)
        }

    override fun save(state: PlayerState) {
        synchronized(lockFor(state.accountKey)) {
            saveLocked(state)
        }
    }

    private fun read(accountKey: String): PlayerState? {
        val path = accountPath(accountKey)
        if (!path.exists()) return null
        return runCatching {
            mapper.readValue(path.toFile(), PlayerStateSnapshot::class.java)
                .let(PlayerState::fromSnapshot)
        }.getOrElse { error ->
            val backup = path.resolveSibling(
                "${path.fileName}.corrupt.${System.currentTimeMillis()}",
            )
            runCatching { Files.move(path, backup, REPLACE_EXISTING) }
                .onFailure { moveError ->
                    throw IllegalStateException(
                        "无法备份损坏的账号存档: $path",
                        moveError,
                    )
                }
            null
        }
    }

    private fun saveLocked(state: PlayerState) {
        Files.createDirectories(accountsDir)
        val target = accountPath(state.accountKey)
        val temp = target.resolveSibling(
            "${target.fileName}.tmp-${ProcessHandle.current().pid()}-${System.nanoTime()}",
        )
        val bytes = mapper.writeValueAsBytes(state.toSnapshot())
        try {
            FileChannel.open(temp, WRITE, CREATE_NEW).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun lockFor(accountKey: String): Any =
        locks.computeIfAbsent(accountKey) { Any() }

    private fun accountPath(accountKey: String): Path =
        accountsDir.resolve(safeFileName(accountKey) + ".json")

    private fun safeFileName(accountKey: String): String =
        if (accountKey.matches(SAFE_ACCOUNT_KEY)) {
            accountKey
        } else {
            sha256(accountKey)
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun nextUserId(): Int =
        userIdSequence.incrementAndGet()

    companion object {
        private val SAFE_ACCOUNT_KEY = Regex("[A-Za-z0-9._-]+")
        private val userIdSequence = AtomicInteger(10_000)
    }
}
