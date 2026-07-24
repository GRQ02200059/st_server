package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class GameServerConfigTest {
    @Test
    fun `advertised host can be overridden for remote deployment`() {
        val key = "stzb.publicHost"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "152.136.236.184")

            assertEquals("152.136.236.184", GameServerConfig.advertisedHost())
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun `advertised host falls back to default for absent or blank override`() {
        val key = "stzb.publicHost"
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)
            assertEquals(GameServerConfig.DEFAULT_HOST, GameServerConfig.advertisedHost())

            System.setProperty(key, "  ")
            assertEquals(GameServerConfig.DEFAULT_HOST, GameServerConfig.advertisedHost())
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }
}
