package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class GameServerConfigTest {
    @Test
    fun `login config selects a season map bundled in the client with non-empty card packs`() {
        // index 5 is a regular season whose map (map_game_data/5) is already
        // cached on the client, so login does not stall at 100%. Unlike 984
        // (conquest X season), index 5 maps tb_cfg_card_extract -> a full
        // ~55KB bin, so the summon card packs and the warfare-skill tab that
        // hangs off a category==3 pack both stay visible.
        assertEquals(5, GameServerConfig.CFG_DB_ID)
    }

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
