package com.stzb.server.game

import com.stzb.server.game.battle.BattleHeroSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldGarrisonTest {
    private fun snapshot(wid: Int, owner: Int) = GarrisonSnapshot(
        wid = wid,
        ownerUserId = owner,
        armyId = 1,
        specs = listOf(BattleHeroSpec(heroId = 100021, position = 0, troops = 1000)),
        residedAtSec = 1_700_000_000,
    )

    @Test
    fun `put then read then remove a garrison`() {
        WorldStateRepository.putGarrison(snapshot(20001, owner = 501))

        val read = WorldStateRepository.garrisonAt(20001)
        assertEquals(501, read?.ownerUserId)
        assertEquals(1, read?.specs?.size)

        val removed = WorldStateRepository.removeGarrison(20001)
        assertEquals(501, removed?.ownerUserId)
        assertNull(WorldStateRepository.garrisonAt(20001))
    }

    @Test
    fun `garrisons lists every stored wid`() {
        WorldStateRepository.putGarrison(snapshot(20002, owner = 502))
        WorldStateRepository.putGarrison(snapshot(20003, owner = 503))

        val wids = WorldStateRepository.garrisons().map { it.wid }
        assertEquals(true, wids.containsAll(listOf(20002, 20003)))
    }
}
