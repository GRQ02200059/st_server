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
        val candidates = enemies
            .filter { it.troops > 0 }
            .map { it to formationDistance(source.position, it.position) }
            .filter { (_, distance) -> distance <= source.stats.hitRange }
            .sortedWith(compareBy<Pair<BattleHero, Int>> { it.second }.thenByDescending { it.first.position })
        return when {
            candidates.isEmpty() -> null
            random == null -> candidates.first().first
            else -> candidates[random.nextInt(candidates.size)].first
        }
    }

    fun normalAttackDamage(
        source: BattleHero,
        target: BattleHero,
        random: BattleRandom? = null,
    ): Int =
        BattleDamageCalculator.physical(
            source = source,
            target = target,
            attributeRandomTenths = 30 + (random?.nextInt(10) ?: 5),
            origin = DamageOrigin.NORMAL,
        )

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
