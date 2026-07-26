package com.stzb.server.game.battle

@JvmInline
value class BattleHeroId(val value: Int)

enum class Side {
    ATTACKER,
    DEFENDER,
}

enum class BattleOutcome {
    ATTACKER_WIN,
    DEFENDER_WIN,
    DRAW,
}

enum class BattleStatus {
    CONFUSION,
    HESITATION,
    PANIC,
    SHAKE,
    BURN,
    HEX,
    DISARM,
    INSIGHT,
    EVADE,
    DOUBLE_ATTACK,
    FIRST_ACTION,
    EMERGENCY_RECOVERY,
    ATTACK_BUFF,
    DEFENSE_BUFF,
    STRATEGY_BUFF,
    SPEED_BUFF,
    ATTACK_DEBUFF,
    DEFENSE_DEBUFF,
    STRATEGY_DEBUFF,
    SPEED_DEBUFF,
    PHYSICAL_DAMAGE_DEALT_INCREASED,
    PHYSICAL_DAMAGE_DEALT_REDUCED,
    STRATEGY_DAMAGE_DEALT_INCREASED,
    STRATEGY_DAMAGE_DEALT_REDUCED,
    PHYSICAL_DAMAGE_TAKEN_INCREASED,
    PHYSICAL_DAMAGE_TAKEN_REDUCED,
    STRATEGY_DAMAGE_TAKEN_INCREASED,
    STRATEGY_DAMAGE_TAKEN_REDUCED,
}

enum class BattleStat {
    ATTACK,
    DEFENSE,
    STRATEGY,
    SPEED,
    SIEGE,
    HIT_RANGE,
}

enum class DamageKind {
    PHYSICAL,
    STRATEGY,
    NORMAL,
    ACTIVE_SKILL,
}

sealed interface BattleModifier {
    data class Stat(val stat: BattleStat, val amount: Int) : BattleModifier
    data class DamageDealtPercent(val kind: DamageKind?, val percent: Int) : BattleModifier
    data class DamageTakenPercent(val kind: DamageKind?, val percent: Int) : BattleModifier
    data class SkillProbabilityPercent(val percent: Int) : BattleModifier
    data class DefenseIgnorePercent(val percent: Int) : BattleModifier
    data class Unsupported(val sourceId: Int, val rawDescription: String) : BattleModifier
}

data class BattleStats(
    val attack: Int,
    val defense: Int,
    val strategy: Int,
    val speed: Int,
    val siege: Int,
    val hitRange: Int,
) {
    operator fun plus(other: BattleStats) = BattleStats(
        attack = attack + other.attack,
        defense = defense + other.defense,
        strategy = strategy + other.strategy,
        speed = speed + other.speed,
        siege = siege + other.siege,
        hitRange = hitRange + other.hitRange,
    )
    operator fun minus(other: BattleStats) = BattleStats(
        attack = attack - other.attack,
        defense = defense - other.defense,
        strategy = strategy - other.strategy,
        speed = speed - other.speed,
        siege = siege - other.siege,
        hitRange = hitRange - other.hitRange,
    )
    companion object {
        val ZERO = BattleStats(0, 0, 0, 0, 0, 0)
    }
}

data class BattleHero(
    val id: BattleHeroId,
    val position: Int,
    val stats: BattleStats,
    val troops: Int,
    val maxTroops: Int = troops,
    val skillIds: List<Int> = emptyList(),
    val activeStatuses: Set<BattleStatus> = emptySet(),
    val level: Int = 1,
    val equipmentIds: List<Int> = emptyList(),
    val modifiers: List<BattleModifier> = emptyList(),
    val advanceLevel: Int = 0,
    val morale: Int = 100,
)

data class BattleTeam(
    val heroes: List<BattleHero>,
    val armyBonuses: List<ArmyBonusConfig> = emptyList(),
) {
    init {
        require(heroes.all { it.position in 0..2 }) { "武将站位必须在 0..2" }
        require(heroes.map { it.position }.distinct().size == heroes.size) { "同一部队内站位不能重复" }
    }
}

data class BattleRequest(
    val attacker: BattleTeam,
    val defender: BattleTeam,
    val maxRounds: Int = 8,
) {
    init {
        require(maxRounds in 1..8) { "常规战斗回合数必须在 1..8" }
    }
}

data class BattleHeroRef(
    val side: Side,
    val position: Int,
    val heroId: BattleHeroId,
)

sealed interface BattleEvent {
    data object BattleStart : BattleEvent
    data class RoundStart(val round: Int) : BattleEvent
    data class HeroActionStart(val round: Int, val source: BattleHeroRef) : BattleEvent
    data class NormalAttack(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val damage: Int,
        val targetTroopsAfter: Int,
    ) : BattleEvent
    data class SkillDamage(
        val round: Int,
        val skillId: Int,
        val effectId: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val damage: Int,
        val targetTroopsAfter: Int,
    ) : BattleEvent
    data class SkillPreparationStarted(
        val round: Int,
        val source: BattleHeroRef,
        val skillId: Int,
        val readyRound: Int,
    ) : BattleEvent
    data class Recovery(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val amount: Int,
        val targetTroopsAfter: Int,
        val skillId: Int = 0,
    ) : BattleEvent
    data class StatusApplied(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val status: BattleStatus,
        val durationRounds: Int,
        val power: Int = 0,
        val statDelta: BattleStats = BattleStats.ZERO,
        val skillId: Int = 0,
    ) : BattleEvent
    data class OngoingDamage(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val status: BattleStatus,
        val damage: Int,
        val targetTroopsAfter: Int,
        val skillId: Int = 0,
    ) : BattleEvent
    data class Evaded(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
    ) : BattleEvent
    data class StatChanged(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val stat: BattleStat,
        val delta: Int,
        val durationRounds: Int,
        val skillId: Int = 0,
    ) : BattleEvent
    data class UnsupportedSkillEffect(
        val round: Int,
        val skillId: Int,
        val effectId: Int,
        val source: BattleHeroRef,
        val rawDescription: String,
    ) : BattleEvent
    data class UnsupportedEquipmentEffect(
        val round: Int,
        val equipmentId: Int,
        val source: BattleHeroRef,
        val rawDescription: String,
    ) : BattleEvent
    data class HeroActionEnd(val round: Int, val source: BattleHeroRef) : BattleEvent
    data class RoundEnd(val round: Int) : BattleEvent
    data class BattleEnd(val outcome: BattleOutcome) : BattleEvent
}

data class BattleResult(
    val outcome: BattleOutcome,
    val attacker: BattleTeam,
    val defender: BattleTeam,
    val events: List<BattleEvent>,
)

data class ActiveBattleStatus(
    val status: BattleStatus,
    val remainingRounds: Int,
    val source: BattleHeroRef,
    val power: Int = 0,
    val statDelta: BattleStats = BattleStats.ZERO,
    val skillId: Int = 0,
    val sourceSnapshot: BattleHero? = null,
)

enum class EffectCategory(val clientBuffType: Int) {
    NEUTRAL(0),
    HARMFUL(1),
    BENEFICIAL(2),
    ;

    companion object {
        fun fromClientBuffType(clientBuffType: Int): EffectCategory =
            entries.singleOrNull { it.clientBuffType == clientBuffType }
                ?: throw IllegalArgumentException("Unsupported client buff_type=$clientBuffType")
    }
}

data class ActiveSkillEffect(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val skillKind: SkillKind,
    val sourceSkillType: Int,
    val detailId: Int,
    val effectId: Int,
    val category: EffectCategory,
    val conflict: Int,
    val strength: Int,
    val replaceType: Int,
    val bindFlag: Int,
    val maxStacks: Int,
    var stacks: Int,
    var remainingRounds: Int?,
    var remainingHits: Int?,
    val clearPerHit: Boolean,
    val clearable: Boolean = true,
    var aggregateStrength: Int = strength * stacks,
) {
    val effectiveStrength: Int
        get() = aggregateStrength

    init {
        require(skillKind != SkillKind.UNKNOWN) { "UNKNOWN skill kind cannot produce an active effect" }
        require(sourceSkillType > 0) { "sourceSkillType must preserve a positive raw skill_type" }
        require(replaceType in 0..3) { "Unsupported replace_type=$replaceType" }
        require(bindFlag >= 0) { "bindFlag must not be negative: $bindFlag" }
        require(maxStacks > 0) { "maxStacks must be positive: $maxStacks" }
        require(stacks in 1..maxStacks) {
            "stacks must be within 1..maxStacks: stacks=$stacks maxStacks=$maxStacks"
        }
        require(remainingRounds == null || remainingRounds!! > 0) {
            "remainingRounds must be positive when present: $remainingRounds"
        }
        require(remainingHits == null || remainingHits!! > 0) {
            "remainingHits must be positive when present: $remainingHits"
        }
    }
}

data class SkillCastResult(
    val skillId: Int,
    val updatedEnemies: BattleTeam,
    val events: List<BattleEvent>,
    val updatedAllies: BattleTeam? = null,
    val selfStatDelta: BattleStats = BattleStats.ZERO,
    val selfBuffDuration: Int? = null,
)

fun Side.opposite(): Side =
    if (this == Side.ATTACKER) Side.DEFENDER else Side.ATTACKER
