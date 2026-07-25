package com.stzb.server.game

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class PlayerStateRepositoryTest {
    @Test
    fun `account registry restores the same identity across repository instances`() {
        val root = createTempDirectory("stzb-account-registry")
        try {
            PlayerStateRepository.configure(FilePlayerRepository(root))
            val first = PlayerStateRepository.getOrCreate("persistent-acct", 100001, "主公")
            val hero = first.addHero(100017, 1_700_000_000)
            PlayerStateRepository.save(first)

            PlayerStateRepository.configure(FilePlayerRepository(root))
            val restored = PlayerStateRepository.getOrCreate("persistent-acct", 100001, "主公")

            assertEquals(first.userId, restored.userId)
            assertEquals(hero.heroUid, restored.allHeroes().single().heroUid)
            assertNotEquals(first, restored)
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `default player state has resources main build and empty team`() {
        val state = PlayerStateRepository.getOrCreate(userId = 77, cityWid = 10077, roleName = "测试")

        assertEquals(77, state.userId)
        assertEquals(10077, state.cityWid)
        assertEquals("测试", state.roleName)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.wood)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.yuanBao)
        assertEquals(1, state.buildLevel(10))
        assertEquals(listOf(0, 0, 0), state.teamHeroes())
    }

    @Test
    fun `building upgrade is stored in player state`() {
        val state = PlayerStateRepository.getOrCreate(userId = 78, cityWid = 10078, roleName = "测试")

        assertEquals(2, state.upgradeBuild(10, 0))
        assertEquals(2, PlayerStateRepository.getOrCreate(78, 10078, "测试").buildLevel(10))
    }

    @Test
    fun `building upgrade does not spend unlimited resources`() {
        val state = PlayerStateRepository.getOrCreate(userId = 81, cityWid = 10081, roleName = "测试")

        assertEquals(2, state.upgradeBuild(10, 0))

        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.wood)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.stone)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.iron)
        assertEquals(PlayerResources.UNLIMITED_AMOUNT, state.resources.food)
    }

    @Test
    fun `building upgrade succeeds even when client resource balance is zero`() {
        val state = PlayerStateRepository.getOrCreate(userId = 82, cityWid = 10082, roleName = "测试")
        state.resources.wood = 0

        assertEquals(2, state.upgradeBuild(10, 0))

        assertEquals(2, state.buildLevel(10))
        assertEquals(0, state.resources.wood)
    }

    @Test
    fun `recruited heroes get unique instance ids`() {
        val state = PlayerStateRepository.getOrCreate(userId = 79, cityWid = 10079, roleName = "测试")

        val first = state.addHero(100017)
        val second = state.addHero(100021)

        assertNotEquals(first.heroUid, second.heroUid)
        assertEquals(100017, first.heroId)
        assertEquals(100021, second.heroId)
    }

    @Test
    fun `team save keeps exactly three slots`() {
        val state = PlayerStateRepository.getOrCreate(userId = 80, cityWid = 10080, roleName = "测试")

        state.saveTeam(listOf(1, 2))

        assertEquals(listOf(1, 2, 0), state.teamHeroes())
    }

    @Test
    fun `assign team hero updates one based slot and hero army id`() {
        val state = PlayerStateRepository.getOrCreate(userId = 83, cityWid = 10083, roleName = "测试")
        val hero = state.addHero(100017)

        state.assignTeamHero(hero.heroUid, pos = 2)

        assertEquals(listOf(0, hero.heroUid, 0), state.teamHeroes())
        assertEquals(10083 * 10 + 1, state.hero(hero.heroUid)?.armyId)
    }

    @Test
    fun `assign team hero restores an invalid zero stamina hero`() {
        val state = PlayerStateRepository.getOrCreate(userId = 86, cityWid = 10086, roleName = "测试")
        val hero = state.addHero(100017)
        hero.stamina = 0

        state.assignTeamHero(hero.heroUid, pos = 1)

        assertEquals(PlayerHero.MAX_STAMINA, state.hero(hero.heroUid)?.stamina)
    }

    @Test
    fun `loading legacy display unit stamina migrates it to protocol units`() {
        val snapshot = PlayerStateSnapshot(
            accountKey = "legacy-stamina",
            userId = 87,
            cityWid = 10087,
            roleName = "测试",
            heroes = listOf(
                PlayerHeroSnapshot(
                    heroUid = 8_700_001,
                    heroId = 100017,
                    createdAtSec = 1_700_000_000,
                    stamina = 60,
                ),
            ),
        )

        val state = PlayerState.fromSnapshot(snapshot)

        assertEquals(600_000, state.hero(8_700_001)?.stamina)
    }

    @Test
    fun `hero stamina uses the client protocol full energy value`() {
        assertEquals(1_000_000, PlayerHero.MAX_STAMINA)
    }

    @Test
    fun `session account key resolves the same state used by login`() {
        val loginState = PlayerStateRepository.getOrCreate(
            accountKey = "session-account",
            cityWid = 10088,
            roleName = "测试",
        )
        loginState.addHero(100017)

        val businessState = PlayerStateRepository.getOrCreateForSession(
            accountKey = "session-account",
            userId = loginState.userId,
            cityWid = 10088,
            roleName = "测试",
        )

        assertSame(loginState, businessState)
        assertEquals(1, businessState.allHeroes().size)
    }

    @Test
    fun `session account migrates heroes written by the legacy user id path`() {
        val root = createTempDirectory("stzb-session-migration")
        try {
            val repository = FilePlayerRepository(root)
            repository.save(
                PlayerState(
                    accountKey = "session-account",
                    userId = 10_001,
                    cityWid = 100001,
                    roleName = "主公",
                ),
            )
            repository.save(
                PlayerState(
                    accountKey = "legacy-user-10001",
                    userId = 10_001,
                    cityWid = 100001,
                    roleName = "主公",
                ).also { it.addHero(100017) },
            )
            PlayerStateRepository.configure(repository)

            val migrated = PlayerStateRepository.getOrCreateForSession(
                accountKey = "session-account",
                userId = 10_001,
                cityWid = 100001,
                roleName = "主公",
            )

            assertEquals(100017, migrated.allHeroes().single().heroId)
            assertEquals("session-account", migrated.accountKey)
        } finally {
            PlayerStateRepository.reset()
        }
    }

    @Test
    fun `switch team heroes swaps one based slots`() {
        val state = PlayerStateRepository.getOrCreate(userId = 84, cityWid = 10084, roleName = "测试")
        val first = state.addHero(100017)
        val second = state.addHero(100021)
        state.saveTeam(listOf(first.heroUid, 0, second.heroUid))

        val affected = state.switchTeamHeroes(pos1 = 1, pos2 = 3)

        assertEquals(listOf(second.heroUid, 0, first.heroUid), state.teamHeroes())
        assertEquals(listOf(second.heroUid, first.heroUid), affected)
        assertEquals(10084 * 10 + 1, state.hero(first.heroUid)?.armyId)
        assertEquals(10084 * 10 + 1, state.hero(second.heroUid)?.armyId)
    }

    @Test
    fun `remove team hero clears requested slot and hero army id`() {
        val state = PlayerStateRepository.getOrCreate(userId = 85, cityWid = 10085, roleName = "测试")
        val base = state.addHero(100017)
        val middle = state.addHero(100021)
        state.saveTeam(listOf(base.heroUid, middle.heroUid, 0))

        val removedHeroUid = state.removeTeamHero(pos = 2)

        assertEquals(middle.heroUid, removedHeroUid)
        assertEquals(listOf(base.heroUid, 0, 0), state.teamHeroes())
        assertEquals(10085 * 10 + 1, state.hero(base.heroUid)?.armyId)
        assertEquals(0, state.hero(middle.heroUid)?.armyId)
    }
}
