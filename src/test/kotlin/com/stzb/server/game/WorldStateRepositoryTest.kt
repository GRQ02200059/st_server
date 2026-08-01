package com.stzb.server.game

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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


    @Test
    fun `legacy world city without custom view uses the default layout`() {
        val root = createTempDirectory("stzb-legacy-world-city")
        try {
            Files.writeString(
                root.resolve("world.json"),
                """{"version":1,"cities":[{"cityWid":15061506,"userId":10001,"roleName":"alice"}],"lands":[]}""",
            )

            assertEquals(
                FacadeCatalog.DEFAULT_CITY_CUSTOM_VIEW,
                FileWorldRepository(root).load().cities.single().customView,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
    @Test
    fun `custom city view survives repository reconstruction and unchanged updates`() {
        val root = createTempDirectory("stzb-world-city-facade")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            WorldStateRepository.configure(root)
            val state = PlayerStateRepository.getOrCreate(
                accountKey = "city-facade-owner",
                cityWid = 15_061_506,
                roleName = "alice",
            )
            WorldStateRepository.registerOrRestorePlayer(state)
            val customView = "3433080,100010;"

            assertTrue(WorldStateRepository.updateCityCustomView(state, state.cityWid, customView))
            assertFalse(WorldStateRepository.updateCityCustomView(state, state.cityWid, customView))

            WorldStateRepository.configure(root)

            assertEquals(
                customView,
                WorldStateRepository.projection().cities.single().customView,
            )
        } finally {
            PlayerStateRepository.reset()
            WorldStateRepository.reset()
            root.toFile().deleteRecursively()
        }
    }
}
