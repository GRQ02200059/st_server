package com.stzb.server.game

import com.stzb.server.game.battle.BattleStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `card border defaults to yulong persists and accepts only supported borders`() {
        val state = PlayerState(userId = 57, cityWid = 10057, roleName = "主公")
        val hero = state.addHero(100017)

        assertEquals(CardBorderCatalog.DEFAULT_ID, hero.cardBorder)
        assertTrue(state.selectHeroCardBorder(hero.heroUid, 110997))
        assertFalse(state.selectHeroCardBorder(hero.heroUid, 777777))

        val restored = PlayerState.fromSnapshot(state.toSnapshot())
        assertEquals(110997, restored.hero(hero.heroUid)?.cardBorder)
    }

    @Test
    fun `legacy hero snapshot gains the yulong default card border`() {
        val restored = PlayerState.fromSnapshot(
            PlayerStateSnapshot(
                accountKey = "legacy-card-border",
                userId = 58,
                cityWid = 10058,
                roleName = "主公",
                heroes = listOf(
                    PlayerHeroSnapshot(
                        heroUid = 58_000_001,
                        heroId = 100017,
                        createdAtSec = 1_700_000_000,
                    ),
                ),
            ),
        )

        assertEquals(CardBorderCatalog.DEFAULT_ID, restored.hero(58_000_001)?.cardBorder)
    }

    @Test
    fun `granted gear replaces and transfers through one to one state`() {
        val state = PlayerState(userId = 61, cityWid = 10061, roleName = "主公")
        val firstHero = state.addHero(100017)
        val secondHero = state.addHero(100021)
        val firstGear = InventoryCatalog.normalWeapons().first().uid
        val secondGear = InventoryCatalog.normalWeapons().drop(1).first().uid

        assertEquals(
            GearEquipResult(
                heroGearUids = mapOf(firstHero.heroUid to firstGear),
                gearHeroUids = mapOf(firstGear to firstHero.heroUid),
            ),
            state.equipGrantedGear(firstHero.heroUid, firstGear),
        )
        assertEquals(
            GearEquipResult(
                heroGearUids = mapOf(firstHero.heroUid to secondGear),
                gearHeroUids = mapOf(firstGear to 0, secondGear to firstHero.heroUid),
            ),
            state.equipGrantedGear(firstHero.heroUid, secondGear),
        )
        assertEquals(
            GearEquipResult(
                heroGearUids = mapOf(firstHero.heroUid to 0, secondHero.heroUid to secondGear),
                gearHeroUids = mapOf(secondGear to secondHero.heroUid),
            ),
            state.equipGrantedGear(secondHero.heroUid, secondGear),
        )
        assertEquals(0, state.equippedGearUid(firstHero.heroUid))
        assertEquals(secondGear, state.equippedGearUid(secondHero.heroUid))
    }

    @Test
    fun `gear operations reject foreign pairs and forget only an exact equipment pair`() {
        val state = PlayerState(userId = 62, cityWid = 10062, roleName = "主公")
        val hero = state.addHero(100017)
        val gearUid = InventoryCatalog.normalWeapons().first().uid

        assertNull(state.equipGrantedGear(heroUid = 999_999, gearUid = gearUid))
        assertNull(state.equipGrantedGear(heroUid = hero.heroUid, gearUid = 123_456))
        assertEquals(0, state.equippedGearUid(hero.heroUid))

        assertEquals(
            GearEquipResult(
                heroGearUids = mapOf(hero.heroUid to gearUid),
                gearHeroUids = mapOf(gearUid to hero.heroUid),
            ),
            state.equipGrantedGear(hero.heroUid, gearUid),
        )
        assertNull(state.forgetGrantedGear(hero.heroUid, InventoryCatalog.normalWeapons().drop(1).first().uid))
        assertEquals(
            GearEquipResult(
                heroGearUids = mapOf(hero.heroUid to 0),
                gearHeroUids = mapOf(gearUid to 0),
            ),
            state.forgetGrantedGear(hero.heroUid, gearUid),
        )
        assertNull(state.forgetGrantedGear(hero.heroUid, gearUid))
    }

    @Test
    fun `gear persists and snapshot recovery keeps only the smallest hero owner`() {
        val gearUid = InventoryCatalog.normalWeapons().first().uid
        val restored = PlayerState.fromSnapshot(
            PlayerStateSnapshot(
                accountKey = "gear-normalization",
                userId = 63,
                cityWid = 10063,
                roleName = "主公",
                heroes = listOf(
                    PlayerHeroSnapshot(
                        heroUid = 63_000_002,
                        heroId = 100017,
                        createdAtSec = 1,
                        gearUid = gearUid,
                    ),
                    PlayerHeroSnapshot(
                        heroUid = 63_000_001,
                        heroId = 100021,
                        createdAtSec = 1,
                        gearUid = gearUid,
                    ),
                    PlayerHeroSnapshot(
                        heroUid = 63_000_003,
                        heroId = 100023,
                        createdAtSec = 1,
                        gearUid = 123_456,
                    ),
                ),
            ),
        )

        assertEquals(gearUid, restored.equippedGearUid(63_000_001))
        assertEquals(0, restored.equippedGearUid(63_000_002))
        assertEquals(0, restored.equippedGearUid(63_000_003))
        assertEquals(
            gearUid,
            PlayerState.fromSnapshot(restored.toSnapshot()).equippedGearUid(63_000_001),
        )
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
        assertEquals(4, restored.buildLevel(10))
        assertEquals(123456, restored.resources.food)
        assertEquals(setOf(100103), restored.occupiedLands())
        assertEquals(100102, restored.activeMarch()?.targetWid)
    }

    @Test
    fun `march snapshot retains battle attributes and selected troop type`() {
        val participant = PlayerMarchHero(
            heroUid = 123,
            position = 1,
            heroId = 100035,
            troops = 900,
            level = 43,
            skillIds = listOf(200648, 200220, 200684),
            heroType = 31,
            attributePoints = BattleStats(18, 7, 12, 3, 0, 0),
            activeFeatureId = 281004,
        )
        val snapshot = PlayerMarchSnapshot(
            armyId = 1001,
            fromWid = 10,
            targetWid = 11,
            beginSec = 1,
            endSec = 9,
            participants = listOf(participant),
        )

        assertEquals(31, snapshot.participants.single().heroType)
        assertEquals(18, snapshot.participants.single().attributePoints.attack)
        assertEquals(281004, snapshot.participants.single().activeFeatureId)
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

    @Test
    fun `each normal army facade has five cards and bound cards survive restore`() {
        val state = PlayerState(userId = 71, cityWid = 10071, roleName = "主公")
        val heroes = HeroCatalog.defaultFiveStarHeroIds().take(5).map(state::addHero)
        require(heroes.size == 5)

        assertEquals(60, state.armyFacadeCards().size)
        val mutation = requireNotNull(
            state.bindArmyFacadeCards(101138, heroes.map(PlayerHero::heroUid)),
        )
        assertEquals(5, mutation.cardCfgHeroIds.size)
        assertTrue(heroes.all { it.armyFacadeCardId == 101138 })

        val restored = PlayerState.fromSnapshot(state.toSnapshot())
        assertEquals(
            heroes.map(PlayerHero::heroId).toSet(),
            restored.armyFacadeCards()
                .filter { it.facadeId == 101138 && it.cfgHeroId > 0 }
                .map(PlayerArmyFacadeCard::cfgHeroId)
                .toSet(),
        )
        assertTrue(heroes.all { restored.hero(it.heroUid)?.armyFacadeCardId == 101138 })
    }

    @Test
    fun `army facade binding rejects duplicate configs non five stars and unsupported facades`() {
        val state = PlayerState(userId = 72, cityWid = 10072, roleName = "主公")
        val fiveStarId = HeroCatalog.defaultFiveStarHeroIds().first()
        val sameConfigA = state.addHero(fiveStarId)
        val sameConfigB = state.addHero(fiveStarId)
        val nonFiveStar = state.addHero(
            HeroCatalog.recruitableHeroIds().first { HeroCatalog.heroQuality(it) != 4 },
        )

        assertNull(state.bindArmyFacadeCards(101138, listOf(sameConfigA.heroUid, sameConfigB.heroUid)))
        assertNull(state.bindArmyFacadeCards(101138, listOf(nonFiveStar.heroUid)))
        assertNull(state.bindArmyFacadeCards(999999, listOf(sameConfigA.heroUid)))
        assertEquals(0, sameConfigA.armyFacadeCardId)
    }

    @Test
    fun `legacy snapshots gain special army facades and marches retain the selected facade`() {
        val restored = PlayerState.fromSnapshot(
            PlayerStateSnapshot(
                accountKey = "legacy-army-facade",
                userId = 73,
                cityWid = 10073,
                roleName = "主公",
            ),
        )
        val hero = restored.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
        restored.assignTeamHero(hero.heroUid, pos = 1)
        assertTrue(restored.useArmyFacade(hero.heroUid, ArmyFacadeCatalog.YUXI_FACADE_ID) != null)

        val xiyuan = restored.specialArmyFacadeCards().single { it.facadeId == 101515 }
        val xiyuanYuxi = restored.specialArmyFacadeCards().single { it.facadeId == 101618 }
        assertTrue(restored.setSpecialArmyFacadeState(xiyuan.heroUid, 2) != null)
        assertTrue(restored.setSpecialArmyFacadeState(xiyuanYuxi.heroUid, 2) != null)
        assertEquals(0, restored.specialArmyFacadeCards().single { it.heroUid == xiyuan.heroUid }.state)
        assertEquals(2, restored.specialArmyFacadeCards().single { it.heroUid == xiyuanYuxi.heroUid }.state)

        val march = restored.startMarch(
            targetWid = 10074,
            nowSec = 1,
            participants = listOf(
                PlayerMarchHero(
                    heroUid = hero.heroUid,
                    position = 0,
                    heroId = hero.heroId,
                    troops = hero.troops,
                    level = hero.level,
                    skillIds = hero.normalizedSkillIds(),
                    armyFacadeCardId = hero.armyFacadeCardId,
                ),
            ),
            specialArmyFacadeId = restored.activeSpecialArmyFacadeId(),
        )

        assertEquals(60, restored.armyFacadeCards().size)
        assertEquals(4, restored.specialArmyFacadeCards().size)
        assertEquals("101618,0;", march.facadeIds())
        assertEquals(
            "101618,0;",
            PlayerState.fromSnapshot(restored.toSnapshot(), nowSec = 1).activeMarch()?.facadeIds(),
        )
    }
}
