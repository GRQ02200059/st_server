package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.BattleHeroSpec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GameResponsesGarrisonTest {
    private val mapper = jacksonObjectMapper()

    @BeforeTest
    fun setUp() {
        WorldStateRepository.configure(Files.createTempDirectory("stzb-responses-garrison-"))
    }

    @AfterTest
    fun tearDown() {
        WorldStateRepository.reset()
    }

    @Test
    fun `world scene includes stored garrisons as reside armies`() {
        val wid = 15051540
        WorldStateRepository.putGarrison(
            GarrisonSnapshot(
                wid = wid,
                ownerUserId = 810,
                armyId = 88_001,
                specs = listOf(BattleHeroSpec(heroId = 100021, position = 0, troops = 5000)),
                residedAtSec = 1_700_000_000,
            ),
        )

        val json = GameResponses.worldSceneFullInfo(
            userId = 999,
            cityWid = 15061599,
            roleName = "观察者",
        )
        val armies = mapper.readTree(json)[6]
        val garrisonArmy = armies["88001"]
        assertEquals(5, garrisonArmy[0].asInt())   // state = RESIDE
        assertEquals(810, garrisonArmy[1].asInt()) // userId = defender
        assertEquals(wid, garrisonArmy[10].asInt()) // reside_wid
    }

    @Test
    fun `army upsert keeps a settled garrison army at the target wid`() {
        val targetWid = 15051541
        val state = PlayerState(userId = 811, cityWid = 15061541, roleName = "守军")
        val armyId = state.primaryArmyId()
        WorldStateRepository.putGarrison(
            GarrisonSnapshot(
                wid = targetWid,
                ownerUserId = state.userId,
                armyId = armyId,
                specs = listOf(BattleHeroSpec(heroId = 100021, position = 0, troops = 5000)),
                residedAtSec = 1_700_000_100,
            ),
        )

        val row = mapper.readTree(GameResponses.armyUpsertNotify(state, armyId))[0][2]

        assertEquals(targetWid, row[2].asInt()) // reside_wid
        assertEquals(targetWid, row[4].asInt()) // last_reside_wid
        assertEquals(5, row[11].asInt()) // state = RESIDE
        assertEquals(0, row[13].asInt()) // target_wid
        assertEquals(targetWid, row[14].asInt()) // stay_wid
        assertEquals(1_700_000_100, row[15].asInt()) // reside_time
    }

    @Test
    fun `world scene does not remove a settled garrison army for its owner`() {
        val targetWid = 15051543
        val armyId = 150615431
        WorldStateRepository.putGarrison(
            GarrisonSnapshot(
                wid = targetWid,
                ownerUserId = 812,
                armyId = armyId,
                specs = listOf(BattleHeroSpec(heroId = 100021, position = 0, troops = 5000)),
                residedAtSec = 1_700_000_300,
            ),
        )

        val army = mapper.readTree(
            GameResponses.worldSceneFullInfo(
                userId = 812,
                cityWid = 15061543,
                roleName = "守军",
                removedArmyId = armyId,
            ),
        )[6][armyId.toString()]

        assertEquals(5, army[0].asInt())
        assertEquals(targetWid, army[10].asInt())
    }
}
