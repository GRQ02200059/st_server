package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStatePersistenceTest {
    @Test
    fun `card pack opening marker survives snapshot restore`() {
        val state = PlayerState(
            userId = 39,
            cityWid = 10039,
            roleName = "主公",
            accountKey = "card-packs-seen-test",
        )

        assertFalse(state.cardPacksSeen)
        state.markCardPacksSeen()

        val restored = PlayerState.fromSnapshot(state.toSnapshot())

        assertTrue(restored.cardPacksSeen)
    }

    @Test
    fun `player hero troops cannot be assigned above ten thousand`() {
        val state = PlayerState(userId = 89, cityWid = 189, roleName = "主公")
        val hero = state.addHero(100017)

        hero.troops = 12_000

        assertEquals(PlayerHero.MAX_TROOPS, hero.troops)
    }

    @Test
    fun `restored legacy hero troops are capped at ten thousand`() {
        val restored = PlayerState.fromSnapshot(
            PlayerStateSnapshot(
                accountKey = "legacy-over-cap",
                userId = 88,
                cityWid = 188,
                roleName = "主公",
                heroes = listOf(
                    PlayerHeroSnapshot(
                        heroUid = 8_800_001,
                        heroId = 100017,
                        createdAtSec = 1,
                        troops = 12_000,
                    ),
                ),
            ),
            nowSec = 2,
        )

        assertEquals(PlayerHero.MAX_TROOPS, restored.hero(8_800_001)?.troops)
    }

    @Test
    fun `new and restored heroes are awakened with the same three persistent skill slots`() {
        val state = PlayerState(
            userId = 40,
            cityWid = 10040,
            roleName = "主公",
            accountKey = "skill-slot-test",
        )
        val hero = state.addHero(100017)
        assertEquals(1, hero.awakeState)
        assertEquals(listOf(200017, 200223, 200031), hero.skillIds)

        val restored = PlayerState.fromSnapshot(state.toSnapshot())

        assertEquals(1, restored.hero(hero.heroUid)?.awakeState)
        assertEquals(hero.skillIds, restored.hero(hero.heroUid)?.skillIds)
    }

    @Test
    fun `legacy snapshots migrate to awakened heroes with three skill slots`() {
        val snapshot = PlayerStateSnapshot(
            accountKey = "legacy-skill-slot-test",
            userId = 41,
            cityWid = 10041,
            roleName = "主公",
            heroes = listOf(
                PlayerHeroSnapshot(
                    heroUid = 4_100_001,
                    heroId = 100021,
                    createdAtSec = 1_700_000_000,
                ),
            ),
        )

        val restored = PlayerState.fromSnapshot(snapshot)
        val hero = restored.hero(4_100_001) ?: error("hero should restore")

        assertEquals(1, hero.awakeState)
        assertEquals(listOf(200021, 200223, 200031), hero.skillIds)
    }

    @Test
    fun `learn replace and forget mutate only the two removable skill slots`() {
        val state = PlayerState(userId = 42, cityWid = 10042, roleName = "主公")
        val hero = state.addHero(100017)

        assertTrue(state.learnHeroSkill(hero.heroUid, 200012, slotIndex = 2))
        assertEquals(listOf(200017, 200012, 200031), hero.skillIds)
        assertTrue(state.learnHeroSkill(hero.heroUid, 200070, slotIndex = 3))
        assertEquals(listOf(200017, 200012, 200070), hero.skillIds)

        assertFalse(state.forgetHeroSkill(hero.heroUid, 200017))
        assertTrue(state.forgetHeroSkill(hero.heroUid, 200012))
        assertEquals(listOf(200017, 0, 200070), hero.skillIds)
    }

    @Test
    fun `selected hero facade survives snapshot restore`() {
        val state = PlayerState(
            userId = 42,
            cityWid = 10001,
            roleName = "主公",
            accountKey = "facade-test",
        )
        val hero = state.addHero(100067)

        assertTrue(state.selectHeroFacade(hero.heroUid, 100534))
        assertFalse(state.selectHeroFacade(hero.heroUid, 101300))

        val restored = PlayerState.fromSnapshot(state.toSnapshot())
        assertEquals(100534, restored.hero(hero.heroUid)?.dynamicIcon)
        assertTrue(restored.selectHeroFacade(hero.heroUid, 0))
        assertEquals(0, restored.hero(hero.heroUid)?.dynamicIcon)
    }

    @Test
    fun `advance count and material card state survive snapshot restore`() {
        val state = PlayerState(userId = 43, cityWid = 10043, roleName = "主公")
        val target = state.addHero(100017, nowSec = 1_700_000_000)
        val material = state.ensureAdvanceMaterials(nowSec = 1_700_000_000).single()

        val result = state.advanceHero(target.heroUid, listOf(material.heroUid))
        val restored = PlayerState.fromSnapshot(state.toSnapshot())

        assertEquals(5, result?.hero?.advanceNum)
        assertEquals(5, restored.hero(target.heroUid)?.advanceNum)
        assertFalse(restored.hero(target.heroUid)?.isAdvanceMaterial ?: true)
        assertEquals(null, restored.hero(material.heroUid))
    }

    @Test
    fun `relocating main city preserves team and clears old world state`() {
        val state = PlayerState(userId = 44, cityWid = 100001, roleName = "主公")
        val hero = state.addHero(100017)
        state.saveTeam(listOf(hero.heroUid))
        state.occupyLand(100002)
        state.startMarch(targetWid = 100003, nowSec = 1_700_000_000)

        state.relocateMainCity(15_061_506)

        assertEquals(15_061_506, state.cityWid)
        assertEquals(listOf(hero.heroUid, 0, 0), state.teamHeroes())
        assertEquals(150_615_061, state.primaryArmyId())
        assertEquals(150_615_061, state.hero(hero.heroUid)?.armyId)
        assertTrue(state.occupiedLands().isEmpty())
        assertTrue(state.activeMarches().isEmpty())
    }

    @Test
    fun `snapshot restores account heroes resources buildings team and march`() {
        val state = PlayerState(
            userId = 101,
            accountKey = "acct-a",
            cityWid = 100101,
            roleName = "测试主公",
        )
        val hero = state.addHero(100017, nowSec = 1_700_000_000)
        hero.troops = 777
        hero.stamina = 555_000
        hero.level = 8
        state.saveTeam(listOf(hero.heroUid))
        state.upgradeBuild(10, 4)
        state.resources.food = 123456
        state.occupyLand(100103)
        state.startMarch(targetWid = 100102, nowSec = 1_700_000_010)

        val restored = PlayerState.fromSnapshot(state.toSnapshot(), nowSec = 1_700_000_011)

        assertEquals("acct-a", restored.accountKey)
        assertEquals(101, restored.userId)
        assertEquals(777, restored.hero(hero.heroUid)?.troops)
        assertEquals(PlayerHero.MAX_STAMINA, restored.hero(hero.heroUid)?.stamina)
        assertEquals(PlayerHero.DEFAULT_LEVEL, restored.hero(hero.heroUid)?.level)
        assertEquals(listOf(hero.heroUid, 0, 0), restored.teamHeroes())
        assertEquals(PlayerState.MAX_BUILD_LEVEL, restored.buildLevel(10))
        assertEquals(123456, restored.resources.food)
        assertEquals(setOf(100103), restored.occupiedLands())
        assertEquals(100102, restored.activeMarch()?.targetWid)
    }

    @Test
    fun `snapshot restore discards marches that already arrived while server was offline`() {
        val snapshot = PlayerStateSnapshot(
            accountKey = "expired-march",
            userId = 102,
            cityWid = 100001,
            roleName = "主公",
            marches = mapOf(
                1_000_011 to PlayerMarchSnapshot(
                    armyId = 1_000_011,
                    fromWid = 100001,
                    targetWid = 100003,
                    beginSec = 1_700_000_000,
                    endSec = 1_700_000_003,
                ),
            ),
        )

        val restored = PlayerState.fromSnapshot(snapshot, nowSec = 1_700_000_010)

        assertTrue(restored.activeMarches().isEmpty())
    }
}
