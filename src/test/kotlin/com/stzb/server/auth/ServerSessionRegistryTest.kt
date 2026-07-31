package com.stzb.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ServerSessionRegistryTest {
    @Test
    fun `issued token resolves its account until expiry`() {
        var now = 1_000L
        val registry = ServerSessionRegistry(
            clockMillis = { now },
            ttlMillis = 100,
        )
        val identity = AccountIdentity(accountKey = "sdkuid-hash", displayId = "sdkuid:alice")
        val token = registry.issue(identity)

        assertEquals(identity, registry.resolve(token))
        now += 99
        assertEquals(identity, registry.resolve(token))
        now += 1
        assertNull(registry.resolve(token))
    }

    @Test
    fun `tokens are opaque unique and unknown tokens fail`() {
        val registry = ServerSessionRegistry()
        val identity = AccountIdentity(accountKey = "sdkuid-hash", displayId = "sdkuid:alice")

        val first = registry.issue(identity)
        val second = registry.issue(identity)

        assertNotEquals(first, second)
        assertNull(registry.resolve("not-issued"))
    }
}
