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
}
