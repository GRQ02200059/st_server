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
        assertEquals(3, skill.rawSkillType)
        assertEquals(35, skill.probabilityMax)
        assertEquals(5, skill.hitRange)
        assertEquals(20001212, skill.mainDetailId)
        assertEquals(140, skill.mainDetail?.constantParam)
        assertEquals(3, skill.mainDetail?.attackMax)
        assertEquals(301, skill.mainEffect?.effectId)
        assertEquals("攻击伤害", skill.mainEffect?.name)
    }

    @Test
    fun `preserves raw skill type outside the four battle categories`() {
        val copiedSkill = repo.skill(270012)

        assertNotNull(copiedSkill)
        assertEquals(14, copiedSkill.rawSkillType)
        assertEquals(SkillKind.UNKNOWN, copiedSkill.kind)
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
    fun `repository preserves every field consumed by rule interpreters`() {
        fun detail(detailId: Int) =
            repo.skillDetails(detailId / 100).single { it.detailId == detailId }

        assertEquals(20000301, detail(20000331).effectParam)
        assertEquals(311, detail(20000102).calcPos)
        assertEquals(1, detail(20001213).calcParam)
        assertEquals(2, detail(20000712).attributeType)
        assertEquals(3, detail(20002316).selectSkillParam)
        assertEquals(2, detail(21195411).targetCountry)
        assertEquals(8, detail(20008022).selectAttri)
        assertEquals(0, detail(20000101).customSelectFlag)
        assertEquals(1, detail(20000311).availableHit)
        assertEquals(1, detail(20019403).bindFlag)
        assertEquals(4013, detail(20000301).castCondition)
        assertEquals(19, detail(20024801).precondition)
        assertEquals(26636, detail(20000802).condition)
        assertEquals(2, detail(20002103).addCountMax)
        assertEquals(0, detail(20000101).buffType)
        assertEquals(2, detail(20001403).delayRound)
        assertEquals(2, detail(20100801).delayHit)
        assertEquals(2, detail(20000101).availableRounds)
        assertEquals(true, detail(20079534).clearPerHit)
        assertEquals(1, detail(20000200).selectFlag)
        assertEquals(1, detail(20000802).inherent)
        assertEquals(true, detail(20019601).moraleAffected)
        assertEquals(0, detail(20000101).calculationType)
        assertEquals(
            listOf(1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            detail(40000101).calculationTypes,
        )

        val effect = repo.skillEffect(77)
        assertNotNull(effect)
        assertEquals(1, effect.replaceType)
        assertEquals(0, effect.valueType)

        assertEquals(1, repo.skillEffect(301)?.valueType)
        assertEquals(2, repo.skillEffect(201)?.valueType)
    }

    @Test
    fun `real configured rows preserve typed value units and raw encoding losslessly`() {
        fun detail(detailId: Int) =
            repo.skillDetails(detailId / 100).single { it.detailId == detailId }

        assertEquals(
            ConfiguredBattleEffectValue(
                unit = BattleEffectValueUnit.RATE,
                rawValueType = 1,
                rawConstant = 300,
                rawCoefficient = 0,
                rawAttributeType = 0,
                rawCalcPosition = 0,
                rawCalcParameter = 0,
            ),
            repo.configuredValue(detail(20095701)),
        )
        assertEquals(
            ConfiguredBattleEffectValue(
                unit = BattleEffectValueUnit.PERCENT,
                rawValueType = 2,
                rawConstant = 11_400_000,
                rawCoefficient = 9_000_000,
                rawAttributeType = 0,
                rawCalcPosition = 0,
                rawCalcParameter = 0,
            ),
            repo.configuredValue(detail(20002301)),
        )
        assertEquals(
            ConfiguredBattleEffectValue(
                unit = BattleEffectValueUnit.PERCENT,
                rawValueType = 2,
                rawConstant = 500_000,
                rawCoefficient = 0,
                rawAttributeType = 0,
                rawCalcPosition = 0,
                rawCalcParameter = 0,
            ),
            repo.configuredValue(detail(29500101)),
        )
        assertEquals(
            ConfiguredBattleEffectValue(
                unit = BattleEffectValueUnit.RATE,
                rawValueType = 1,
                rawConstant = 35,
                rawCoefficient = 35,
                rawAttributeType = 2,
                rawCalcPosition = 0,
                rawCalcParameter = 0,
            ),
            repo.configuredValue(detail(20000712)),
        )
    }

    @Test
    fun `raw detail loader preserves nonzero custom select flag`() {
        val row = mutableMapOf<String, String>().withDefault { "0" }.apply {
            put("detail_id", "90000101")
            put("effect_id", "301")
            put("attack_type", "43")
            put("target_type", "0")
            put("select_type", "0")
            put("attack_max", "1")
            put("custom_select_flag", "7123")
            put("effect_name", "fixture")
        }

        assertEquals(7123, BattleConfigRepository.loadSkillDetail(row).customSelectFlag)
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
