package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BattleActionResolverTest {
    private val resolver = BattleActionResolver()

    @Test
    fun `normal attack chooses nearest target inside range`() {
        val source = hero(1, position = 2, hitRange = 3, attack = 100)
        val enemies = listOf(
            hero(2, position = 0, hitRange = 1, attack = 10),
            hero(3, position = 2, hitRange = 1, attack = 10),
        )

        val result = resolver.resolveNormalAttack(
            round = 1,
            sourceRef = source.ref(Side.ATTACKER),
            source = source,
            enemies = enemies,
        )

        assertEquals(BattleHeroId(3), result?.target?.id)
        assertEquals(2, result?.event?.target?.position)
    }

    @Test
    fun `normal attack returns no result when every target is outside range`() {
        val source = hero(1, position = 0, hitRange = 4, attack = 100)

        assertNull(
            resolver.resolveNormalAttack(
                round = 1,
                sourceRef = source.ref(Side.ATTACKER),
                source = source,
                enemies = listOf(hero(2, position = 0, hitRange = 1, attack = 10)),
            ),
        )
    }

    private fun hero(
        id: Int,
        position: Int,
        hitRange: Int,
        attack: Int,
    ) = BattleHero(
        id = BattleHeroId(id),
        position = position,
        stats = BattleStats(attack, 0, 0, 10, 0, hitRange),
        troops = 1_000,
    )

    private fun BattleHero.ref(side: Side) = BattleHeroRef(side, position, id)
}
