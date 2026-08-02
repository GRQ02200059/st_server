package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `configured battle can randomly select any enemy inside attack range`() {
        val source = hero(1, position = 2, hitRange = 4, attack = 100)
        val enemies = listOf(
            hero(2, position = 0, hitRange = 1, attack = 10),
            hero(3, position = 1, hitRange = 1, attack = 10),
            hero(4, position = 2, hitRange = 1, attack = 10),
        )

        val result = resolver.resolveNormalAttack(
            round = 1,
            sourceRef = source.ref(Side.ATTACKER),
            source = source,
            enemies = enemies,
            random = FixedBattleRandom(2),
        )

        assertEquals(BattleHeroId(2), result?.target?.id)
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

    @Test
    fun `dead formation slots compress both sides of the normal attack distance`() {
        val source = hero(1, position = 0, hitRange = 1, attack = 100)
        val deadAlliedFront = hero(2, position = 2, hitRange = 1, attack = 10)
            .copy(troops = 0)
        val deadEnemyFront = hero(3, position = 2, hitRange = 1, attack = 10)
            .copy(troops = 0)
        val target = hero(4, position = 1, hitRange = 1, attack = 10)

        val selected = resolver.selectNormalAttackTarget(
            source = source,
            enemies = listOf(deadEnemyFront, target),
            allies = listOf(source, deadAlliedFront),
        )

        assertEquals(target.id, selected?.id)
    }

    @Test
    fun `normal attack skips targets immune to normal targeting`() {
        val source = hero(1, position = 2, hitRange = 3, attack = 100)
        val immuneFront = hero(2, position = 2, hitRange = 1, attack = 10).copy(
            modifiers = listOf(
                BattleModifier.TargetImmunity(BattleTargetingKind.NORMAL_ATTACK),
            ),
        )
        val targetableMiddle = hero(3, position = 1, hitRange = 1, attack = 10)

        val selected = resolver.selectNormalAttackTarget(
            source = source,
            enemies = listOf(immuneFront, targetableMiddle),
        )

        assertEquals(targetableMiddle.id, selected?.id)
    }

    @Test
    fun `normal attack uses the reference troop attack and defense curve`() {
        val source = hero(1, position = 2, hitRange = 3, attack = 200).copy(
            troops = 10_000,
            maxTroops = 10_000,
        )
        val target = hero(2, position = 2, hitRange = 1, attack = 10).copy(
            stats = BattleStats(attack = 10, defense = 100, strategy = 50, speed = 10, siege = 0, hitRange = 1),
            troops = 10_000,
            maxTroops = 10_000,
        )

        val result = resolver.resolveNormalAttack(
            round = 1,
            sourceRef = source.ref(Side.ATTACKER),
            source = source,
            enemies = listOf(target),
            random = FixedBattleRandom(0),
        )

        assertEquals(620, result?.event?.damage)
    }

    @Test
    fun `ranged normal attack selects the farthest target and scales damage by distance`() {
        val source = hero(1, position = 2, hitRange = 5, attack = 200).copy(
            troops = 10_000,
            maxTroops = 10_000,
            modifiers = listOf(
                BattleModifier.RangedNormalAttack(
                    damagePercentPerDistance = 25,
                ),
            ),
        )
        val near = hero(2, position = 2, hitRange = 1, attack = 10).copy(
            troops = 10_000,
            maxTroops = 10_000,
        )
        val far = near.copy(id = BattleHeroId(3), position = 0)

        val selected = resolver.selectNormalAttackTarget(
            source = source,
            enemies = listOf(near, far),
            random = FixedBattleRandom(0),
        )
        val nearDamage = resolver.normalAttackDamage(source, near, FixedBattleRandom(0))
        val farDamage = resolver.normalAttackDamage(source, far, FixedBattleRandom(0))

        assertEquals(far.id, selected?.id)
        assertTrue(farDamage > nearDamage)
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
