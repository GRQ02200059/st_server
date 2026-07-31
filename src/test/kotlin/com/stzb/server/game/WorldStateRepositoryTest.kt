package com.stzb.server.game

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStateRepositoryTest {
    @Test
    fun `file repository round trips cities and land claims`() {
        val root = createTempDirectory("stzb-world-repository")
        val repository = FileWorldRepository(root)
        val snapshot = WorldStateSnapshot(
            cities = listOf(WorldCity(cityWid = 15_061_506, userId = 10_001, roleName = "alice")),
            lands = listOf(
                LandClaim(
                    wid = 15_081_508,
                    userId = 10_001,
                    belongCity = 15_061_506,
                    claimedAtSec = 100,
                ),
            ),
        )

        repository.save(snapshot)

        assertEquals(snapshot, FileWorldRepository(root).load())
    }
}
