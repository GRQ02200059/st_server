package com.stzb.server.session

import io.netty.channel.embedded.EmbeddedChannel
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class OnlineSessionRegistryTest {
    @Test
    fun `binding a second channel returns the old channel and preserves the new channel`() {
        val registry = OnlineSessionRegistry()
        val first = EmbeddedChannel()
        val second = EmbeddedChannel()

        assertNull(registry.bind("sdkuid-alice", first))
        assertSame(first, registry.bind("sdkuid-alice", second))
        registry.remove("sdkuid-alice", first)

        assertSame(second, registry.current("sdkuid-alice"))
        first.finishAndReleaseAll()
        second.finishAndReleaseAll()
    }
}
