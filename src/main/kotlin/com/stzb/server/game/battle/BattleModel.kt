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
    IGNORE_EVADE,
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

enum class DamageSchool {
    PHYSICAL,
    STRATEGY,
}

enum class DamageOrigin {
    NORMAL,
    ACTIVE,
    PURSUIT,
    COMMAND,
    PASSIVE,
    ONGOING,
}

enum class DamageTag {
    ONGOING,
    FIRE,
}

sealed interface BattleModifier {
    data class Stat(val stat: BattleStat, val amount: Int) : BattleModifier
    data class DamageDealtPercent(
        val school: DamageSchool? = null,
        val origin: DamageOrigin? = null,
        val tag: DamageTag? = null,
        val percent: Int,
    ) : BattleModifier
    data class DamageTakenPercent(
        val school: DamageSchool? = null,
        val origin: DamageOrigin? = null,
        val tag: DamageTag? = null,
        val percent: Int,
    ) : BattleModifier
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
    private val attackHundredths: Int = attack * 100,
    private val defenseHundredths: Int = defense * 100,
    private val strategyHundredths: Int = strategy * 100,
    private val speedHundredths: Int = speed * 100,
    private val siegeHundredths: Int = siege * 100,
) {
    operator fun plus(other: BattleStats) = fromHundredths(
        attack = hundredths(BattleStat.ATTACK) + other.hundredths(BattleStat.ATTACK),
        defense = hundredths(BattleStat.DEFENSE) + other.hundredths(BattleStat.DEFENSE),
        strategy = hundredths(BattleStat.STRATEGY) + other.hundredths(BattleStat.STRATEGY),
        speed = hundredths(BattleStat.SPEED) + other.hundredths(BattleStat.SPEED),
        siege = hundredths(BattleStat.SIEGE) + other.hundredths(BattleStat.SIEGE),
        hitRange = hitRange + other.hitRange,
    )
    operator fun minus(other: BattleStats) = fromHundredths(
        attack = hundredths(BattleStat.ATTACK) - other.hundredths(BattleStat.ATTACK),
        defense = hundredths(BattleStat.DEFENSE) - other.hundredths(BattleStat.DEFENSE),
        strategy = hundredths(BattleStat.STRATEGY) - other.hundredths(BattleStat.STRATEGY),
        speed = hundredths(BattleStat.SPEED) - other.hundredths(BattleStat.SPEED),
        siege = hundredths(BattleStat.SIEGE) - other.hundredths(BattleStat.SIEGE),
        hitRange = hitRange - other.hitRange,
    )

    fun precise(stat: BattleStat): Double =
        hundredths(stat) / 100.0

    private fun hundredths(stat: BattleStat): Int =
        when (stat) {
            BattleStat.ATTACK -> attackHundredths.takeIf { it / 100 == attack } ?: attack * 100
            BattleStat.DEFENSE -> defenseHundredths.takeIf { it / 100 == defense } ?: defense * 100
            BattleStat.STRATEGY -> strategyHundredths.takeIf { it / 100 == strategy } ?: strategy * 100
            BattleStat.SPEED -> speedHundredths.takeIf { it / 100 == speed } ?: speed * 100
            BattleStat.SIEGE -> siegeHundredths.takeIf { it / 100 == siege } ?: siege * 100
            BattleStat.HIT_RANGE -> hitRange * 100
        }

    companion object {
        val ZERO = BattleStats(0, 0, 0, 0, 0, 0)

        fun fromHundredths(
            attack: Int,
            defense: Int,
            strategy: Int,
            speed: Int,
            siege: Int,
            hitRange: Int,
        ): BattleStats =
            BattleStats(
                attack = attack / 100,
                defense = defense / 100,
                strategy = strategy / 100,
                speed = speed / 100,
                siege = siege / 100,
                hitRange = hitRange,
                attackHundredths = attack,
                defenseHundredths = defense,
                strategyHundredths = strategy,
                speedHundredths = speed,
                siegeHundredths = siege,
            )
    }
}

data class BattleHero(
    val id: BattleHeroId,
    val position: Int,
    val stats: BattleStats,
    val troops: Int,
    val maxTroops: Int = troops,
    val skillIds: List<Int> = emptyList(),
    val skillLevels: List<Int> = emptyList(),
    val troopFeatureIds: List<Int> = emptyList(),
    val equipment: List<BattleEquipmentSlot> = emptyList(),
    val activeStatuses: Set<BattleStatus> = emptySet(),
    val level: Int = 1,
    val equipmentIds: List<Int> = emptyList(),
    val modifiers: List<BattleModifier> = emptyList(),
    val advanceLevel: Int = 0,
    val morale: Int = 100,
)

data class BattleEquipmentSlot(
    val equipmentId: Int,
    val level: Int = 1,
)

enum class BattlePreparationStage {
    SYSTEM,
    ARMY,
    TROOP,
    EQUIPMENT,
}

data class BattlePreparationEffect(
    val stage: BattlePreparationStage,
    val sourceId: Int,
    val targetPosition: Int,
    val stat: BattleStat,
    val strength: Int,
    val delta: Int,
    val valueAfter: Int,
    val sourcePosition: Int? = null,
    val percent: Boolean = true,
    val deltaExact: Double = delta.toDouble(),
    val valueAfterExact: Double = valueAfter.toDouble(),
    val containerSourceId: Int = sourceId,
    val strengthExact: Double = strength.toDouble(),
)

data class BattlePreparationSource(
    val stage: BattlePreparationStage,
    val sourceId: Int,
    val sourcePosition: Int? = null,
)

data class BattlePreparationModifier(
    val stage: BattlePreparationStage,
    val sourceId: Int,
    val sourcePosition: Int,
    val targetPosition: Int,
    val effectId: Int,
    val amount: Int,
    val containerSourceId: Int = sourceId,
)

data class BattlePreparationAction(
    val stage: BattlePreparationStage,
    val sourceId: Int,
    val sourcePosition: Int,
    val targetPosition: Int,
    val actionId: Int,
    val amountExact: Double? = null,
    val actionParameter: Int? = null,
    val appendSourcePosition: Boolean = false,
    val containerSourceId: Int = sourceId,
)

data class BattleTeam(
    val heroes: List<BattleHero>,
    val armyBonuses: List<ArmyBonusConfig> = emptyList(),
    val preparationSources: List<BattlePreparationSource> = emptyList(),
    val preparationEffects: List<BattlePreparationEffect> = emptyList(),
    val preparationModifiers: List<BattlePreparationModifier> = emptyList(),
    val preparationActions: List<BattlePreparationAction> = emptyList(),
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

data class ActionPermission(
    val canAct: Boolean = true,
    val canCastActive: Boolean = true,
    val canNormalAttack: Boolean = true,
    val redirectTarget: BattleHeroRef? = null,
    val normalAttackCount: Int = 1,
    val grantsPursuitOpportunityPerNormal: Boolean = true,
    val resolvedAllegiance: Side? = null,
    val resolvedTargetPool: List<BattleHeroRef> = emptyList(),
    val counterattack: Boolean = false,
    val secondaryAttack: Boolean = false,
    val firstAction: Boolean = false,
)

sealed interface BattleEvent {
    data object BattleStart : BattleEvent
    data class SkillTriggered(
        val round: Int,
        val source: BattleHeroRef,
        val rootSkillId: Int,
        val skillId: Int,
        val trigger: com.stzb.server.game.battle.skill.BattleTrigger,
    ) : BattleEvent
    data class TriggerPoint(
        val round: Int,
        val source: BattleHeroRef,
        val trigger: com.stzb.server.game.battle.skill.BattleTrigger,
    ) : BattleEvent
    data class SkillPreparationCompleted(
        val round: Int,
        val source: BattleHeroRef,
        val rootSkillId: Int,
        val skillId: Int,
        val startedRound: Int,
        val readyRound: Int,
        val trigger: com.stzb.server.game.battle.skill.BattleTrigger,
    ) : BattleEvent
    data class SkillPreparationCancelled(
        val round: Int,
        val source: BattleHeroRef,
        val rootSkillId: Int,
        val skillId: Int,
        val reason: String,
    ) : BattleEvent
    data class StatusRemoved(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val skillId: Int,
        val effectId: Int,
    ) : BattleEvent
    data class EffectExpired(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val skillId: Int,
        val effectId: Int,
    ) : BattleEvent
    data class EffectBlocked(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val skillId: Int,
        val effectId: Int,
        val blockingEffectId: Int,
    ) : BattleEvent
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
        val effectId: Int? = null,
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
        val effectId: Int = 0,
        val strength: Int = durationRounds,
        val valueAfter: Int? = null,
        val deltaExact: Double = delta.toDouble(),
        val valueAfterExact: Double? = valueAfter?.toDouble(),
    ) : BattleEvent
    data class ModifierApplied(
        val round: Int,
        val source: BattleHeroRef,
        val target: BattleHeroRef,
        val skillId: Int,
        val effectId: Int,
        val amount: Int,
        val durationRounds: Int,
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
    val entryAttacker: BattleTeam? = null,
    val entryDefender: BattleTeam? = null,
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

class ActiveSkillEffect(
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
    stacks: Int,
    var remainingRounds: Int?,
    var remainingHits: Int?,
    val clearPerHit: Boolean,
    val clearable: Boolean = true,
) {
    private var layerStrengths: List<Int> = List(stacks) { strength }

    val effectiveStrength: Int
        get() = layerStrengths.sum()

    val stacks: Int
        get() = layerStrengths.size

    init {
        require(sourceSkillType > 0) { "sourceSkillType must preserve a positive raw skill_type" }
        val normalizedKind = SkillKind.fromRawType(sourceSkillType)
        require(normalizedKind != SkillKind.UNKNOWN) {
            "Unsupported sourceSkillType=$sourceSkillType cannot produce an active effect"
        }
        require(skillKind == normalizedKind) {
            "skillKind=$skillKind does not match sourceSkillType=$sourceSkillType ($normalizedKind)"
        }
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

    internal fun addLayer(layerStrength: Int) {
        require(stacks < maxStacks) { "Cannot exceed maxStacks=$maxStacks" }
        layerStrengths = layerStrengths + layerStrength
    }

    internal fun detachedCopy(): ActiveSkillEffect =
        ActiveSkillEffect(
            source = source,
            target = target,
            rootSkillId = rootSkillId,
            skillId = skillId,
            skillKind = skillKind,
            sourceSkillType = sourceSkillType,
            detailId = detailId,
            effectId = effectId,
            category = category,
            conflict = conflict,
            strength = strength,
            replaceType = replaceType,
            bindFlag = bindFlag,
            maxStacks = maxStacks,
            stacks = stacks,
            remainingRounds = remainingRounds,
            remainingHits = remainingHits,
            clearPerHit = clearPerHit,
            clearable = clearable,
        ).also { copy ->
            copy.layerStrengths = layerStrengths.toList()
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
