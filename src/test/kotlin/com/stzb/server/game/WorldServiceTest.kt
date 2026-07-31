package com.stzb.server.game

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldServiceTest {
    @Test
    fun `static city catalog reserves every configured city wid`() {
        assertTrue(StaticCityCatalog.contains(GameHome.LUOYANG_WID))
        assertTrue(StaticCityCatalog.count > 1_000)
        assertFalse(StaticCityCatalog.contains(GameHome.DEFAULT_CITY_WID))
    }

    @Test
    fun `world service refuses claims on static city cells`() {
        val root = createTempDirectory("stzb-world-static-city")
        val playerRepository = FilePlayerRepository(root)
        val world = WorldService(FileWorldRepository(root), playerRepository::save)
        val state = world.registerOrRestorePlayer(player(10_001, "alice"))

        assertFalse(world.claimLand(state, GameHome.LUOYANG_WID, nowSec = 100))
        assertTrue(state.occupiedLands().isEmpty())
    }

    @Test
    fun `new players receive non-overlapping three by three cities near Luoyang`() {
        val root = createTempDirectory("stzb-world-spawn")
        val playerRepository = FilePlayerRepository(root)
        val world = WorldService(FileWorldRepository(root), playerRepository::save)
        val first = world.registerOrRestorePlayer(player(10_001, "alice"))
        val second = world.registerOrRestorePlayer(player(10_002, "bob"))

        val firstFootprint = HomeCity.suburbWids(first.cityWid) + first.cityWid
        val secondFootprint = HomeCity.suburbWids(second.cityWid) + second.cityWid
        assertNotEquals(first.cityWid, second.cityWid)
        assertTrue(firstFootprint.none(secondFootprint::contains))
        assertTrue(kotlin.math.abs(first.cityWid / 10_000 - 1501) >= 5)
        assertTrue(kotlin.math.abs(second.cityWid / 10_000 - 1501) >= 5)
    }

    @Test
    fun `first player wins a contested land claim`() {
        val root = createTempDirectory("stzb-world-claim")
        val playerRepository = FilePlayerRepository(root)
        val world = WorldService(FileWorldRepository(root), playerRepository::save)
        val first = world.registerOrRestorePlayer(player(10_001, "alice"))
        val second = world.registerOrRestorePlayer(player(10_002, "bob"))

        assertTrue(world.claimLand(first, 15_081_508, nowSec = 100))
        assertFalse(world.claimLand(second, 15_081_508, nowSec = 101))
        assertEquals(setOf(15_081_508), first.occupiedLands())
        assertTrue(second.occupiedLands().isEmpty())
        assertEquals(first.userId, world.projection().lands.single().userId)
    }

    @Test
    fun `world ownership restores a missing account land index after restart`() {
        val root = createTempDirectory("stzb-world-reconcile")
        val playerRepository = FilePlayerRepository(root)
        val world = WorldService(FileWorldRepository(root), playerRepository::save)
        val original = world.registerOrRestorePlayer(player(10_001, "alice"))
        assertTrue(world.claimLand(original, 15_081_508, nowSec = 100))

        val missingIndex = PlayerState.fromSnapshot(
            original.toSnapshot().copy(occupiedLands = emptySet()),
        )
        val reloadedWorld = WorldService(FileWorldRepository(root), playerRepository::save)
        val reconciled = reloadedWorld.registerOrRestorePlayer(missingIndex)

        assertEquals(setOf(15_081_508), reconciled.occupiedLands())
        assertEquals(
            10_001,
            reloadedWorld.projection().lands.single { it.wid == 15_081_508 }.userId,
        )
    }

    private fun player(userId: Int, account: String): PlayerState =
        PlayerState(
            userId = userId,
            cityWid = GameHome.DEFAULT_CITY_WID,
            roleName = account,
            accountKey = account,
        )
}
