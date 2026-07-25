package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleEffectStateTest {
    private val source = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
    private val target = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(2))

    @Test
    fun `stronger same category replaces weaker effect`() {
        val state = BattleEffectState()
        state.apply(BattleEffect.status(source, target, 1001, BattleStatus.DISARM, 1, category = "control", value = 10))
        state.apply(BattleEffect.status(source, target, 1002, BattleStatus.HESITATION, 2, category = "control", value = 30))

        assertFalse(state.hasStatus(target, BattleStatus.DISARM))
        assertTrue(state.hasStatus(target, BattleStatus.HESITATION))
    }

    @Test
    fun `different damage categories stack and expire`() {
        val state = BattleEffectState()
        state.apply(BattleEffect.damage(source, target, 1001, DamageKind.PHYSICAL, 20, 1, "command"))
        state.apply(BattleEffect.damage(source, target, 1002, DamageKind.PHYSICAL, 30, 2, "active"))

        assertEquals(1.5, state.damageFactor(target, DamageKind.PHYSICAL))

        state.tick(BattleEffectPhase.ROUND_END)
        assertEquals(1.3, state.damageFactor(target, DamageKind.PHYSICAL))

        state.tick(BattleEffectPhase.ROUND_END)
        assertEquals(1.0, state.damageFactor(target, DamageKind.PHYSICAL))
    }

    @Test
    fun `stat layers are reflected in effective stats`() {
        val state = BattleEffectState()
        val hero = BattleHero(
            id = target.heroId,
            position = target.position,
            stats = BattleStats(100, 90, 80, 70, 20, 3),
            troops = 1000,
        )
        state.apply(
            BattleEffect.stat(
                source = source,
                target = target,
                skillId = 1005,
                delta = BattleStats(30, 0, 0, 10, 0, 0),
                durationRounds = 2,
                category = "command",
            ),
        )

        assertEquals(130, state.effectiveStats(target, hero).attack)
        assertEquals(80, state.effectiveStats(target, hero).speed)
    }
}
