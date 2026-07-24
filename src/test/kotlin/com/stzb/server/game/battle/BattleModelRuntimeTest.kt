package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals

class BattleModelRuntimeTest {
    @Test
    fun `hero can carry level equipment and runtime modifiers`() {
        val hero = BattleHero(
            id = BattleHeroId(100479),
            position = 0,
            stats = BattleStats(attack = 100, defense = 80, strategy = 50, speed = 60, siege = 10, hitRange = 4),
            troops = 1000,
            level = 20,
            equipmentIds = listOf(1024),
            modifiers = listOf(BattleModifier.Stat(BattleStat.ATTACK, 2)),
        )

        assertEquals(20, hero.level)
        assertEquals(listOf(1024), hero.equipmentIds)
        assertEquals(BattleModifier.Stat(BattleStat.ATTACK, 2), hero.modifiers.single())
    }

    @Test
    fun `runtime events expose unsupported status and recovery details`() {
        val source = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100479))
        val target = BattleHeroRef(Side.DEFENDER, 1, BattleHeroId(100017))

        val unsupportedSkill = BattleEvent.UnsupportedSkillEffect(
            round = 2,
            skillId = 200999,
            effectId = 999,
            source = source,
            rawDescription = "复杂效果",
        )
        val unsupportedEquipment = BattleEvent.UnsupportedEquipmentEffect(
            round = 1,
            equipmentId = 1024,
            source = source,
            rawDescription = "未知装备效果",
        )
        val status = BattleEvent.StatusApplied(
            round = 3,
            source = source,
            target = target,
            status = BattleStatus.DISARM,
            durationRounds = 2,
        )
        val recovery = BattleEvent.Recovery(
            round = 4,
            source = source,
            target = target,
            amount = 120,
            targetTroopsAfter = 900,
        )

        assertEquals(999, unsupportedSkill.effectId)
        assertEquals(1024, unsupportedEquipment.equipmentId)
        assertEquals(BattleStatus.DISARM, status.status)
        assertEquals(120, recovery.amount)
    }
}
