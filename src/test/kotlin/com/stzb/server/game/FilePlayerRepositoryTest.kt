package com.stzb.server.game

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePlayerRepositoryTest {
    @Test
    fun `legacy file without accumulated money or revenue loads compatible defaults`() {
        val root = createTempDirectory("stzb-legacy-revenue")
        val path = root.resolve("accounts").resolve("legacy-revenue.json")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """
            {
              "accountKey": "legacy-revenue",
              "userId": 38,
              "cityWid": 10038,
              "roleName": "主公",
              "resources": {
                "money": 6500,
                "wood": 0,
                "stone": 0,
                "iron": 0,
                "food": 0,
                "yuanBao": 0,
                "hufu": 0,
                "freeYuanBao": 0
              }
            }
            """.trimIndent(),
        )

        val restored = FilePlayerRepository(root).findByAccount("legacy-revenue")!!

        assertEquals(6500, restored.resources.money)
        assertEquals(6500, restored.resources.moneyAccumulated)
        assertTrue(restored.revenue.collections.isEmpty())
        assertTrue(restored.revenue.gifts.isEmpty())
        assertEquals(0, restored.revenue.revenueTime)
        assertEquals(0, restored.revenue.nextRefreshTime)
        assertEquals(0, restored.revenue.forceCount)
    }

    @Test
    fun `file repository round trips structured revenue state`() {
        val root = createTempDirectory("stzb-revenue-repository")
        val repository = FilePlayerRepository(root)
        val state = repository.getOrCreate("revenue-round-trip", 10039, "主公")
        state.resources.money = 6500
        state.resources.moneyAccumulated = 6500
        state.revenue.collections += RevenueCollection(collectedAtSec = 10, amount = 6500)
        state.revenue.gifts += RevenueGift(amount = 6500, extra = 0, claimed = true)
        state.revenue.revenueTime = 10
        state.revenue.nextRefreshTime = 20
        state.revenue.forceCount = 1
        repository.save(state)

        val restored = FilePlayerRepository(root).findByAccount("revenue-round-trip")!!

        assertEquals(6500, restored.resources.money)
        assertEquals(6500, restored.resources.moneyAccumulated)
        assertEquals(listOf(RevenueCollection(10, 6500)), restored.revenue.collections)
        assertEquals(listOf(RevenueGift(6500, 0, true)), restored.revenue.gifts)
        assertEquals(10, restored.revenue.revenueTime)
        assertEquals(20, restored.revenue.nextRefreshTime)
        assertEquals(1, restored.revenue.forceCount)
    }

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

    @Test
    fun `read only lookup preserves corrupt account file without quarantine`() {
        val root = createTempDirectory("stzb-player-repository")
        val repository = FilePlayerRepository(root)
        val path = root.resolve("accounts").resolve("acct-read-only.json")
        val originalBytes = "{broken".toByteArray()
        Files.createDirectories(path.parent)
        Files.write(path, originalBytes)

        assertNull(repository.findByAccountReadOnly("acct-read-only"))

        assertTrue(Files.exists(path))
        assertContentEquals(originalBytes, Files.readAllBytes(path))
        assertFalse(
            Files.list(path.parent).use { paths ->
                paths.iterator().asSequence().any {
                    it.fileName.toString().startsWith("acct-read-only.json.corrupt.")
                }
            },
        )
    }

    @Test
    fun `new account id advances beyond ids already stored on disk`() {
        val root = createTempDirectory("stzb-player-repository")
        FilePlayerRepository(root).save(
            PlayerState(
                userId = 1_500_000,
                cityWid = 100001,
                roleName = "旧账号",
                accountKey = "old-account",
            ),
        )

        val newState = FilePlayerRepository(root).getOrCreate("new-account", 100001, "新账号")

        assertTrue(newState.userId > 1_500_000)
    }
}
