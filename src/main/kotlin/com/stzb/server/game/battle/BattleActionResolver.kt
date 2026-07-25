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
    ): NormalAttackResult? {
        val target = enemies
            .filter { it.troops > 0 }
            .map { it to formationDistance(source.position, it.position) }
            .filter { (_, distance) -> distance <= source.stats.hitRange }
            .minWithOrNull(compareBy<Pair<BattleHero, Int>> { it.second }.thenByDescending { it.first.position })
            ?.first
            ?: return null
        val damage = normalAttackDamage(source, target)
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

    private fun normalAttackDamage(source: BattleHero, target: BattleHero): Int {
        val troopScale = source.troops.toDouble() / source.maxTroops.coerceAtLeast(1)
        val raw = (source.stats.attack - target.stats.defense / 2).coerceAtLeast(1)
        val modifier = source.modifiers
            .filterIsInstance<BattleModifier.DamageDealtPercent>()
            .filter { it.kind == null || it.kind == DamageKind.NORMAL || it.kind == DamageKind.PHYSICAL }
            .sumOf { it.percent }
        val takenModifier = target.modifiers
            .filterIsInstance<BattleModifier.DamageTakenPercent>()
            .filter { it.kind == null || it.kind == DamageKind.NORMAL || it.kind == DamageKind.PHYSICAL }
            .sumOf { it.percent }
        return (raw * troopScale * (100 + modifier) / 100 * (100 + takenModifier) / 100)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(target.troops)
    }
}
