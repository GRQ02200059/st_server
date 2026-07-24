package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BattleConfigRepositoryTest {
    private val repo = BattleConfigRepository.loadDefault()

    @Test
    fun `loads hero config from parsed client csv`() {
        val luBu = repo.hero(100479)

        assertNotNull(luBu)
        assertEquals("吕布", luBu.name)
        assertEquals(3.5, luBu.cost)
        assertEquals(4, luBu.hitRange)
        assertEquals(101, luBu.stats.attack)
        assertEquals(72, luBu.stats.defense)
        assertEquals(29, luBu.stats.strategy)
        assertEquals(77, luBu.stats.speed)
        assertEquals(9, luBu.stats.siege)
        assertEquals(200012, luBu.initialSkillId)
    }

    @Test
    fun `loads skill config and joins main effect`() {
        val skill = repo.skill(200012)

        assertNotNull(skill)
        assertEquals("辕门射戟", skill.name)
        assertEquals(SkillKind.ACTIVE, skill.kind)
        assertEquals(35, skill.probabilityMax)
        assertEquals(5, skill.hitRange)
        assertEquals(20001212, skill.mainDetailId)
        assertEquals(140, skill.mainDetail?.constantParam)
        assertEquals(3, skill.mainDetail?.attackMax)
        assertEquals(301, skill.mainEffect?.effectId)
        assertEquals("攻击伤害", skill.mainEffect?.name)
    }

    @Test
    fun `creates battle hero from hero config`() {
        val hero = repo.toBattleHero(heroId = 100017, position = 1, troops = 1000)

        assertEquals(BattleHeroId(100017), hero.id)
        assertEquals(1, hero.position)
        assertEquals(2, hero.stats.hitRange)
        assertEquals(1000, hero.troops)
        assertEquals(1000, hero.maxTroops)
        assertTrue(hero.stats.strategy > hero.stats.attack)
    }

    @Test
    fun `loads supplemental hero and skill metadata from assent cfg json`() {
        val luBu = repo.heroExtra(100479)
        val skill = repo.skillExtra(200012)

        assertNotNull(luBu)
        assertEquals("吕布", luBu.name)
        assertTrue(luBu.methodDesc.contains("伤害"))

        assertNotNull(skill)
        assertEquals("辕门射戟", skill.name)
        assertTrue(skill.description.contains("有效距离"))
    }

    @Test
    fun `loads all skill details for a skill id prefix`() {
        val details = repo.skillDetails(200012)

        assertTrue(details.size >= 4)
        assertEquals(listOf(20001201, 20001212, 20001213, 20001224), details.take(4).map { it.detailId })
        assertTrue(details.any { it.effectId == 301 && it.constantParam == 140 })
    }

    @Test
    fun `matches army bonus when team contains configured heroes`() {
        val bonuses = repo.armyBonusesFor(listOf(100352, 100345, 100344))

        assertTrue(bonuses.any { it.name == "旗本八骑" })
        val bonus = bonuses.first { it.name == "旗本八骑" }
        assertEquals(69, bonus.stats.speed)
        assertEquals(1, bonus.stats.hitRange)
    }
}
