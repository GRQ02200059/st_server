package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleDamageCalculatorTest {
    @Test
    fun `troop counter profiles apply advantage disadvantage and source immunity`() {
        val archer = hero(heroType = 1)
        val spear = hero(heroType = 22)
        val neutral = BattleDamageCalculator.physical(archer, spear)

        val advantaged = BattleDamageCalculator.physical(
            source = archer.copy(
                modifiers = listOf(
                    BattleModifier.TroopCounterDealtPercent(
                        targetHeroType = 22,
                        percent = 30,
                    ),
                ),
            ),
            target = spear,
        )
        val archerCounterProfile = archer.copy(
            modifiers = listOf(
                BattleModifier.TroopCounterTakenPercent(
                    sourceHeroType = 22,
                    percent = -30,
                ),
            ),
        )
        val disadvantaged = BattleDamageCalculator.physical(
            source = spear,
            target = archerCounterProfile,
        )
        val immune = BattleDamageCalculator.physical(
            source = spear.copy(
                modifiers = listOf(BattleModifier.TroopCounterImmunity),
            ),
            target = archerCounterProfile,
        )

        assertTrue(advantaged > neutral)
        assertTrue(disadvantaged < neutral)
        assertEquals(neutral, immune)
    }

    private fun hero(heroType: Int) = BattleHero(
        id = BattleHeroId(heroType),
        position = 2,
        stats = BattleStats(
            attack = 140,
            defense = 100,
            strategy = 100,
            speed = 80,
            siege = 20,
            hitRange = 5,
        ),
        troops = 10_000,
        maxTroops = 10_000,
        heroType = heroType,
    )
}
