package com.stzb.server.game.battle

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Damage curves ported from stzbBattleSimulator-main/battleCalcFunc.js.
 * All callers use this module so normal, active, pursuit and ongoing damage
 * cannot drift into separate formulas.
 */
object BattleDamageCalculator {
    fun physical(
        source: BattleHero,
        target: BattleHero,
        ratePercent: Int = 100,
        attributeRandomTenths: Int = 35,
        origin: DamageOrigin? = null,
        tags: Set<DamageTag> = emptySet(),
    ): Int {
        val rate = ratePercent.coerceAtLeast(1) / 100.0
        val damageFactor = modifierFactor(source, target, DamageSchool.PHYSICAL, origin, tags)
        val troopDamage = source.troops * 373.0 / (7_700 + source.troops)
        val attributeDamage =
            source.stats.attack *
                (attributeRandomTenths.coerceIn(30, 39) / 100.0) *
                rate *
                damageFactor
        val mainDamage =
            (300.0 * source.troops / (3_500 + source.troops)) *
                rate *
                attackDefenseFactor(source.stats.attack, target.stats.defense) *
                damageFactor
        return (troopDamage + attributeDamage + mainDamage)
            .roundToInt()
            .coerceIn(1, target.troops.coerceAtLeast(1))
    }

    fun strategy(
        source: BattleHero,
        target: BattleHero,
        ratePercent: Int,
        ongoing: Boolean = false,
        origin: DamageOrigin? = null,
        tags: Set<DamageTag> = emptySet(),
    ): Int {
        val rate = ratePercent.coerceAtLeast(1) / 100.0
        val damageFactor = modifierFactor(source, target, DamageSchool.STRATEGY, origin, tags)
        val strategyFactor = strategyDefenseFactor(target.stats.strategy)
        val troopDamage = source.troops * 178.0 / (6_459 + source.troops) * if (ongoing) 1.0 / 3 else 1.0
        val attributeDamage = source.stats.strategy * (if (ongoing) 0.25 else 0.5) * damageFactor * strategyFactor
        val mainDamage =
            (300.0 * source.troops / (3_500 + source.troops)) *
                rate *
                damageFactor *
                strategyFactor
        return (troopDamage + attributeDamage + mainDamage)
            .roundToInt()
            .coerceIn(1, target.troops.coerceAtLeast(1))
    }

    private fun modifierFactor(
        source: BattleHero,
        target: BattleHero,
        school: DamageSchool,
        origin: DamageOrigin?,
        tags: Set<DamageTag>,
    ): Double {
        val dealt = source.modifiers
            .filterIsInstance<BattleModifier.DamageDealtPercent>()
            .filter { it.matches(school, origin, tags) }
            .sumOf { it.percent }
        val taken = target.modifiers
            .filterIsInstance<BattleModifier.DamageTakenPercent>()
            .filter { it.matches(school, origin, tags) }
            .sumOf { it.percent }
        return (100 + dealt + taken).coerceAtLeast(10) / 100.0
    }

    private fun BattleModifier.DamageDealtPercent.matches(
        school: DamageSchool,
        origin: DamageOrigin?,
        tags: Set<DamageTag>,
    ): Boolean =
        (this.school == null || this.school == school) &&
            (this.origin == null || this.origin == origin) &&
            (tag == null || tag in tags)

    private fun BattleModifier.DamageTakenPercent.matches(
        school: DamageSchool,
        origin: DamageOrigin?,
        tags: Set<DamageTag>,
    ): Boolean =
        (this.school == null || this.school == school) &&
            (this.origin == null || this.origin == origin) &&
            (tag == null || tag in tags)

    private fun attackDefenseFactor(attack: Int, defense: Int): Double {
        val difference = attack - defense
        return if (difference >= 0) {
            3.0 - 500.0 / (250 + difference)
        } else {
            100.0 / (100 - difference)
        }
    }

    private fun strategyDefenseFactor(strategy: Int): Double =
        if (strategy <= 50) {
            1.0
        } else {
            ceil(100 - (75 - 9_375.0 / (75 + strategy))) / 100.0
        }
}
