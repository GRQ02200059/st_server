package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LandDefenderFactoryTest {
    // Land level/wid assertions below are pinned to the 2001 map so they stay
    // stable regardless of which season the server currently advertises
    // (LandMapRepository.loadDefault follows GameServerConfig.CFG_DB_ID).
    private fun factoryOn2001() = LandDefenderFactory(LandMapRepository.load(2001))

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
        val factory = factoryOn2001()

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
        assertEquals(listOf(1), first.single().skillLevels)
        assertEquals(emptyList(), first.single().troopFeatureIds)
        assertEquals(emptyList(), first.single().equipmentIds)

        val levelFiveFirst = factory.candidateSpecsForLevel(5).first()
        assertEquals(listOf(100759, 100761, 100285), levelFiveFirst.map { it.heroId })
        assertEquals(listOf(20, 20, 20), levelFiveFirst.map { it.level })
        assertEquals(listOf(3600, 3600, 3600), levelFiveFirst.map { it.troops })
        assertEquals(listOf(200190), levelFiveFirst[0].extraSkillIds)
        assertEquals(listOf(5, 5), levelFiveFirst[0].skillLevels)
        assertEquals(emptyList(), levelFiveFirst[1].extraSkillIds)
        assertEquals(listOf(5), levelFiveFirst[1].skillLevels)
        assertEquals(listOf(200639), levelFiveFirst[2].extraSkillIds)
        assertEquals(listOf(5, 5), levelFiveFirst[2].skillLevels)

        val levelSixFirst = factory.candidateSpecsForLevel(6).first()
        assertEquals(
            listOf(listOf(3201), listOf(3104), listOf(3104)),
            levelSixFirst.map { it.troopFeatureIds },
        )
    }

    @Test
    fun `client equipment keeps base gear separate from report skill slots`() {
        val repository = ClientNpcArmyRepository.loadDefault()
        val hero = repository.armiesForPool(9100).first().heroes.first()

        assertEquals(listOf(1009), hero.equipmentIds)
        assertEquals(
            listOf(400007, 400008, 400009),
            hero.equipmentSkillIds,
        )
        assertEquals(listOf(6, 6, 1), hero.equipmentSkillLevels)
        assertEquals(listOf(450034), hero.equipmentFeatureSkillIds)
        assertEquals(listOf(6), hero.equipmentFeatureSkillLevels)
    }

    @Test
    fun `resource land army selection follows the canonical level mapping`() {
        val factory = factoryOn2001()

        // These entries are Tcfg_army 101 and 203 respectively. The army
        // selection must be tied to land level, not the world-coordinate modulo.
        assertEquals(listOf(101), factory.armyIdsForWid(10_002))
        assertEquals(listOf(203), factory.armyIdsForWid(10_004))
        assertEquals(
            factory.candidateSpecsForLevel(1).first(),
            factory.specsForWid(10_002),
        )
        assertEquals(
            factory.candidateSpecsForLevel(2)[2],
            factory.specsForWid(10_004),
        )
    }

    @Test
    fun `client army count marks high level resource land as two defender teams`() {
        val factory = factoryOn2001()

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
