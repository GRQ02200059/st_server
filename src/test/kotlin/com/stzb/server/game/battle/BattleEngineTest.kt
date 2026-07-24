package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleEngineTest {
    @Test
    fun `faster heroes act first and normal attacks reduce troops`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(pos = 0, heroId = 100479, speed = 120, attack = 90, defense = 20, troops = 100),
                        hero(pos = 1, heroId = 100017, speed = 60, attack = 30, defense = 20, troops = 100),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(pos = 0, heroId = 100023, speed = 80, attack = 40, defense = 10, troops = 100),
                    ),
                ),
                maxRounds = 1,
            ),
        )

        val attacks = result.events.filterIsInstance<BattleEvent.NormalAttack>()
        assertEquals(Side.ATTACKER, attacks[0].source.side)
        assertEquals(0, attacks[0].source.position)
        assertEquals(Side.DEFENDER, attacks[1].source.side)
        assertEquals(0, attacks[1].source.position)
        assertTrue(result.defender.heroes.single().troops < 100)
    }

    @Test
    fun `front hero cannot hit back target when hit range is too short`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(hero(pos = 0, hitRange = 1, speed = 100, attack = 100, defense = 0, troops = 100)),
                ),
                defender = BattleTeam(
                    listOf(hero(pos = 2, speed = 10, attack = 10, defense = 0, troops = 100)),
                ),
                maxRounds = 1,
            ),
        )

        assertTrue(result.events.none { it is BattleEvent.NormalAttack })
        assertEquals(100, result.defender.heroes.single().troops)
    }

    @Test
    fun `battle ends when one side is defeated`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(hero(pos = 0, speed = 100, attack = 500, defense = 0, troops = 100)),
                ),
                defender = BattleTeam(
                    listOf(hero(pos = 0, speed = 10, attack = 10, defense = 0, troops = 80)),
                ),
                maxRounds = 8,
            ),
        )

        assertEquals(BattleOutcome.ATTACKER_WIN, result.outcome)
        assertEquals(0, result.defender.heroes.single().troops)
        assertEquals(1, result.events.filterIsInstance<BattleEvent.RoundStart>().size)
        assertTrue(result.events.last() is BattleEvent.BattleEnd)
    }

    private fun hero(
        pos: Int,
        heroId: Int = 1,
        hitRange: Int = 3,
        speed: Int,
        attack: Int,
        defense: Int,
        troops: Int,
    ): BattleHero =
        BattleHero(
            id = BattleHeroId(heroId),
            position = pos,
            stats = BattleStats(
                attack = attack,
                defense = defense,
                strategy = 0,
                speed = speed,
                siege = 0,
                hitRange = hitRange,
            ),
            troops = troops,
        )
}
