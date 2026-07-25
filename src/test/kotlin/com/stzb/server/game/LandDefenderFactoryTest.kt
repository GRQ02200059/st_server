package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LandDefenderFactoryTest {
    @Test
    fun `different land levels produce different defender strength`() {
        val factory = LandDefenderFactory()

        val levelOne = factory.specsForLevel(1)
        val levelNine = factory.specsForLevel(9)

        assertTrue(levelNine.sumOf { it.troops } > levelOne.sumOf { it.troops })
        assertTrue(levelNine.minOf { it.level } > levelOne.maxOf { it.level })
    }

    @Test
    fun `land level comes from map config instead of the wid suffix`() {
        val factory = LandDefenderFactory()

        // cfgDataIndex=2001 resources_in_map: both wids end in 1, but their
        // configured resource levels are 3 and 1 respectively.
        assertEquals(3, factory.levelForWid(10_001))
        assertEquals(1, factory.levelForWid(10_011))
    }

    @Test
    fun `resource land defenders come from the client army and hero tables`() {
        val factory = LandDefenderFactory()

        val levelOneCandidates = factory.candidateSpecsForLevel(1)
        val first = levelOneCandidates.first()

        assertEquals(10, levelOneCandidates.size)
        assertEquals(1, first.size)
        assertEquals(100180, first.single().heroId)
        assertEquals(2, first.single().level)
        assertEquals(100, first.single().troops)
        assertEquals(emptyList(), first.single().extraSkillIds)

        val levelFiveFirst = factory.candidateSpecsForLevel(5).first()
        assertEquals(listOf(100759, 100761, 100285), levelFiveFirst.map { it.heroId })
        assertEquals(listOf(20, 20, 20), levelFiveFirst.map { it.level })
        assertEquals(listOf(3600, 3600, 3600), levelFiveFirst.map { it.troops })
        assertEquals(listOf(200190), levelFiveFirst[0].extraSkillIds)
        assertEquals(emptyList(), levelFiveFirst[1].extraSkillIds)
        assertEquals(listOf(200639), levelFiveFirst[2].extraSkillIds)
    }

    @Test
    fun `same level lands select varied but stable client combinations`() {
        val factory = LandDefenderFactory()

        val first = factory.specsForWid(10_002)
        val repeated = factory.specsForWid(10_002)
        val other = factory.specsForWid(10_003)

        assertEquals(first, repeated)
        assertNotEquals(first.map { it.heroId }, other.map { it.heroId })
        assertTrue(first in factory.candidateSpecsForLevel(1))
        assertTrue(other in factory.candidateSpecsForLevel(1))
    }

    @Test
    fun `client army count marks high level resource land as two defender teams`() {
        val factory = LandDefenderFactory()

        assertEquals(1, factory.teamCountForLevel(5))
        assertEquals(2, factory.teamCountForLevel(6))
        assertEquals(2, factory.teamsForWid(10_146).size)
        assertTrue(factory.teamsForWid(10_146).all { it in factory.candidateSpecsForLevel(6) })
    }

    @Test
    fun `npc hero troops also respect the global ten thousand cap`() {
        val factory = LandDefenderFactory()

        assertTrue(factory.candidateSpecsForLevel(9).flatten().all {
            it.troops <= PlayerHero.MAX_TROOPS
        })
    }
}
