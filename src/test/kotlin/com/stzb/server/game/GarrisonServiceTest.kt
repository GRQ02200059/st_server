package com.stzb.server.game

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GarrisonServiceTest {
    @BeforeTest
    fun setUp() {
        WorldStateRepository.configure(Files.createTempDirectory("stzb-garrison-service-"))
    }

    @AfterTest
    fun tearDown() {
        WorldStateRepository.reset()
    }

    @Test
    fun `reside arrival records a garrison snapshot at the target wid`() {
        val state = PlayerState(userId = 601, cityWid = 15061510, roleName = "主公")
        val hero = state.addHero(heroId = 100021).apply { troops = 8_000; level = 40 }
        state.saveTeam(listOf(hero.heroUid))
        val service = GarrisonService()

        val march = service.startReside(state, wid = 15051599, armyId = state.primaryArmyId(), nowSec = 1_700_000_000)
        assertNotNull(march)
        assertEquals(MarchTargetType.RESIDE_GOING, march.targetType)
        // not due yet
        assertNull(service.settleReside(state, state.primaryArmyId(), nowSec = 1_700_000_001))

        val snapshot = service.settleReside(state, state.primaryArmyId(), nowSec = 1_700_000_600)
        assertNotNull(snapshot)
        assertEquals(601, snapshot.ownerUserId)
        assertEquals(15051599, snapshot.wid)
        assertEquals(100021, snapshot.specs.single().heroId)
        assertEquals(snapshot.wid, WorldStateRepository.garrisonAt(15051599)?.wid)
    }

    @Test
    fun `empty team cannot reside`() {
        val state = PlayerState(userId = 602, cityWid = 15061511, roleName = "主公")
        assertNull(GarrisonService().startReside(state, wid = 15051598, armyId = state.primaryArmyId(), nowSec = 1_700_000_000))
    }
}
