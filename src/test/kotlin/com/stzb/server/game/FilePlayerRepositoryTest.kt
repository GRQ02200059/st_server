package com.stzb.server.game

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilePlayerRepositoryTest {
    @Test
    fun `file repository round trips player state`() {
        val root = createTempDirectory("stzb-player-repository")
        val first = FilePlayerRepository(root)
        val state = first.getOrCreate("acct/a", 100001, "主公")
        val hero = state.addHero(100017, 1_700_000_000)
        hero.troops = 321
        first.save(state)

        val restored = FilePlayerRepository(root).findByAccount("acct/a")!!

        assertEquals(state.userId, restored.userId)
        assertEquals(321, restored.hero(hero.heroUid)?.troops)
    }

    @Test
    fun `corrupt file is backed up and replaced by a new account`() {
        val root = createTempDirectory("stzb-player-repository")
        val repository = FilePlayerRepository(root)
        val path = root.resolve("accounts").resolve("acct-b.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, "{broken")

        val state = repository.getOrCreate("acct-b", 100001, "主公")

        assertTrue(
            Files.list(path.parent).use { paths ->
                paths.iterator().asSequence().any {
                    it.fileName.toString().startsWith("acct-b.json.corrupt.")
                }
            },
        )
        assertEquals("acct-b", state.accountKey)
    }
}
