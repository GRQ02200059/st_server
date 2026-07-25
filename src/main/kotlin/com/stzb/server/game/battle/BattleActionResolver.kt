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
    fun resolveNormalAttack(
        round: Int,
        sourceRef: BattleHeroRef,
        source: BattleHero,
        enemies: Collection<BattleHero>,
        random: BattleRandom? = null,
    ): NormalAttackResult? {
        val candidates = enemies
            .filter { it.troops > 0 }
            .map { it to formationDistance(source.position, it.position) }
            .filter { (_, distance) -> distance <= source.stats.hitRange }
            .sortedWith(compareBy<Pair<BattleHero, Int>> { it.second }.thenByDescending { it.first.position })
        val target = when {
            candidates.isEmpty() -> return null
            random == null -> candidates.first().first
            else -> candidates[random.nextInt(candidates.size)].first
        }
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

    private fun normalAttackDamage(
        source: BattleHero,
        target: BattleHero,
        random: BattleRandom?,
    ): Int {
        return BattleDamageCalculator.physical(
            source = source,
            target = target,
            attributeRandomTenths = 30 + (random?.nextInt(10) ?: 5),
        )
    }
}
