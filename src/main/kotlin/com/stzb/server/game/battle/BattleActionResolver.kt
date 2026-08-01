package com.stzb.server.game.battle

data class NormalAttackResult(
    val target: BattleHero,
    val event: BattleEvent.NormalAttack,
)

/**
 * Resolves one normal attack without mutating battle state. The engine owns
 * orchestration while this module owns formation distance and damage rules.
 */
class BattleActionResolver {
    fun selectNormalAttackTarget(
        source: BattleHero,
        enemies: Collection<BattleHero>,
        random: BattleRandom? = null,
    ): BattleHero? {
        val candidates = normalAttackTargetsInRange(source, enemies)
        val rangedAttack = source.modifiers
            .filterIsInstance<BattleModifier.RangedNormalAttack>()
            .lastOrNull()
        return when {
            candidates.isEmpty() -> null
            rangedAttack != null -> candidates.maxByOrNull { it.second }?.first
            random == null -> candidates.first().first
            else -> candidates[random.nextInt(candidates.size)].first
        }
    }

    fun normalAttackTargetsInRange(
        source: BattleHero,
        enemies: Collection<BattleHero>,
    ): List<Pair<BattleHero, Int>> =
        enemies
            .filter { it.troops > 0 }
            .filterNot { target ->
                BattleModifier.TargetImmunity(BattleTargetingKind.NORMAL_ATTACK) in target.modifiers
            }
            .map { it to formationDistance(source.position, it.position) }
            .filter { (_, distance) -> distance <= source.stats.hitRange }
            .sortedWith(compareBy<Pair<BattleHero, Int>> { it.second }.thenByDescending { it.first.position })

    fun normalAttackDamage(
        source: BattleHero,
        target: BattleHero,
        random: BattleRandom? = null,
    ): Int {
        val rangedAttack = source.modifiers
            .filterIsInstance<BattleModifier.RangedNormalAttack>()
            .lastOrNull()
        val distanceBonus = rangedAttack
            ?.damagePercentPerDistance
            ?.times(formationDistance(source.position, target.position))
            ?.coerceAtLeast(0)
            ?: 0
        val effectiveSource = if (distanceBonus == 0) {
            source
        } else {
            source.copy(
                modifiers = source.modifiers + BattleModifier.DamageDealtPercent(
                    origin = DamageOrigin.NORMAL,
                    percent = distanceBonus,
                ),
            )
        }
        return BattleDamageCalculator.physical(
            source = effectiveSource,
            target = target,
            attributeRandomTenths = 30 + (random?.nextInt(10) ?: 5),
            origin = DamageOrigin.NORMAL,
        )
    }

    fun resolveNormalAttack(
        round: Int,
        sourceRef: BattleHeroRef,
        source: BattleHero,
        enemies: Collection<BattleHero>,
        random: BattleRandom? = null,
    ): NormalAttackResult? {
        val target = selectNormalAttackTarget(source, enemies, random) ?: return null
        val damage = normalAttackDamage(source, target, random)
        val updated = target.copy(troops = (target.troops - damage).coerceAtLeast(0))
        return NormalAttackResult(
            target = updated,
            event = BattleEvent.NormalAttack(
                round = round,
                source = sourceRef,
                target = BattleHeroRef(sourceRef.side.opposite(), target.position, target.id),
                damage = damage,
                targetTroopsAfter = updated.troops,
            ),
        )
    }

    private fun formationDistance(sourcePosition: Int, targetPosition: Int): Int =
        5 - sourcePosition - targetPosition

}
