package com.stzb.server.auth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class ServerSessionRegistry(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val random = SecureRandom()
    private val entries = ConcurrentHashMap<String, TokenEntry>()

    fun issue(identity: AccountIdentity): String {
        removeExpired()
        var token: String
        do {
            token = ByteArray(TOKEN_BYTES)
                .also(random::nextBytes)
                .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        } while (entries.putIfAbsent(token, TokenEntry(identity, clockMillis() + ttlMillis)) != null)
        return token
    }

    fun resolve(token: String): AccountIdentity? {
        val entry = entries[token] ?: return null
        if (entry.expiresAtMillis <= clockMillis()) {
            entries.remove(token, entry)
            return null
        }
        return entry.identity
    }

    fun removeExpired() {
        val now = clockMillis()
        entries.entries.removeIf { (_, entry) -> entry.expiresAtMillis <= now }
    }

    fun clear() {
        entries.clear()
    }

    private data class TokenEntry(
        val identity: AccountIdentity,
        val expiresAtMillis: Long,
    )

    companion object {
        private const val TOKEN_BYTES = 32
        private const val DEFAULT_TTL_MILLIS = 10 * 60 * 1_000L
    }
}
