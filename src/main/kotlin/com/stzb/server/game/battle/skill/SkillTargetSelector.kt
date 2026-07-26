package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.opposite

fun interface CompiledTargetSelector {
    fun select(context: SkillBattleContext): List<BattleHeroRef>
}

class SkillTargetSelector {
    fun compile(rule: SkillEffectRule): CompiledTargetSelector {
        val raw = rule.raw
        require(raw.targetType in TARGET_TYPES) {
            "Unsupported target_type=${raw.targetType} for detail ${rule.detailId}"
        }
        require(raw.selectType in SELECT_TYPES) {
            "Unsupported select_type=${raw.selectType} for detail ${rule.detailId}"
        }
        require(raw.attackType % 1000 in ATTACK_TYPES) {
            "Unsupported attack_type=${raw.attackType} for detail ${rule.detailId}"
        }
        if (raw.selectType == SELECT_MINIMUM || raw.selectType == SELECT_MAXIMUM) {
            require(raw.selectAttri in ATTRIBUTE_SELECTORS) {
                "Unsupported select_attri=${raw.selectAttri} for detail ${rule.detailId}"
            }
        }
        if (raw.selectFlag != 0) {
            SkillTargetStateFilter.fromRaw(raw.selectFlag)
        }

        return CompiledTargetSelector { context -> select(rule, context) }
    }

    private fun select(
        rule: SkillEffectRule,
        context: SkillBattleContext,
    ): List<BattleHeroRef> {
        val raw = rule.raw
        val view = context.battleView
        var candidates = seedCandidates(raw.selectType, raw.attackType, context)
            .ifEmpty {
                if (rule.detailId in SOLO_SELF_FALLBACK_DETAILS &&
                    raw.attackType % 1000 == 11 &&
                    rule.effectBuffType == BENEFICIAL_BUFF_TYPE
                ) {
                    listOf(context.source)
                } else {
                    emptyList()
                }
            }
            .filter { view.isTargetable(it) }
            .filter { target ->
                matchesTargetType(
                    raw.targetType,
                    if (raw.targetType == TARGET_ANY) null else view.metadata(target),
                )
            }
            .filter { target ->
                raw.targetCountry == 0 || view.metadata(target)?.country == raw.targetCountry
            }
            .filter { target ->
                raw.selectFlag == 0 ||
                    view.matchesStateFilter(
                        SkillTargetStateFilter.fromRaw(raw.selectFlag),
                        context.source,
                        target,
                    )
            }
            .filter { target -> matchesPreconditionTarget(raw.precondition, context, target) }
            .filter { target -> matchesConditionTarget(raw.condition, context, target) }
            .filter { target -> matchesCountryConditionTarget(raw.condition, context, target) }
            .filter { target -> matchesMoraleConditionTarget(raw.condition, context, target) }
            .filter { target ->
                matchesStatusConditionTarget(raw.castCondition, raw.condition, context, target)
            }
            .filter { target ->
                matchesAttributeCastConditionTarget(raw.castCondition, context, target)
            }
            .filter { target ->
                matchesMarkerCastConditionTarget(raw.castCondition, context, target)
            }
            .sortedWith(CLIENT_POSITION_ORDER)

        candidates = when (raw.selectType) {
            SELECT_INSIDE_CURRENT_RANGE -> candidates.filter { inCurrentAttackRange(context.source, it, view) }
            SELECT_OUTSIDE_CURRENT_RANGE -> candidates.filterNot {
                inCurrentAttackRange(context.source, it, view)
            }
            else -> candidates.filter { target ->
                target.side == context.source.side ||
                    attackTypeIgnoresRange(raw.attackType) ||
                    inSkillRange(context.source, target, rule.skillHitRange)
            }
        }

        val limit = raw.attackMax.coerceAtLeast(1)
        return when (raw.selectType) {
            SELECT_RANDOM -> randomWithoutReplacement(candidates, limit, context)
            SELECT_MINIMUM -> selectByAttribute(candidates, raw.selectAttri, view, minimum = true)
            SELECT_FARTHEST -> candidates.maxByOrNull { formationDistance(context.source, it) }?.let(::listOf).orEmpty()
            SELECT_BASE -> candidates.filter { it.position == BASE_POSITION }.take(1)
            SELECT_MIDDLE -> candidates.filter { it.position == MIDDLE_POSITION }.take(1)
            SELECT_FRONT -> candidates.filter { it.position == FRONT_POSITION }.take(1)
            SELECT_MALE -> randomWithoutReplacement(
                candidates.filter { view.metadata(it)?.gender == SkillHeroGender.MALE },
                limit,
                context,
            )
            SELECT_FEMALE -> randomWithoutReplacement(
                candidates.filter { view.metadata(it)?.gender == SkillHeroGender.FEMALE },
                limit,
                context,
            )
            SELECT_MAXIMUM -> selectByAttribute(candidates, raw.selectAttri, view, minimum = false)
            SELECT_RANDOM_COUNT -> {
                if (candidates.isEmpty()) {
                    emptyList()
                } else {
                    val maxCount = minOf(MAX_RANDOM_GROUP_SIZE, limit, candidates.size)
                    val count = context.random.nextInt(maxCount) + 1
                    randomWithoutReplacement(candidates, count, context)
                }
            }
            SELECT_ALL -> candidates
            SELECT_GREATEST_DAMAGE -> candidates
                .maxByOrNull(view::accumulatedDamageDealt)
                ?.let(::listOf)
                .orEmpty()
            SELECT_HIGHEST_MORALE -> candidates
                .maxByOrNull { view.currentMorale(it) ?: Int.MIN_VALUE }
                ?.let(::listOf)
                .orEmpty()
            SELECT_INSIDE_CURRENT_RANGE,
            SELECT_OUTSIDE_CURRENT_RANGE,
            -> randomWithoutReplacement(candidates, limit, context)
            SELECT_LINKED -> candidates.take(limit)
            SELECT_ADJACENT_TO_CURRENT -> candidates.take(limit)
            else -> error("Unsupported select_type=${raw.selectType}")
        }
    }

    private fun seedCandidates(
        selectType: Int,
        attackType: Int,
        context: SkillBattleContext,
    ): List<BattleHeroRef> =
        when (selectType) {
            SELECT_LINKED -> listOfNotNull(context.battleView.linkedTarget(context.source))
            SELECT_ADJACENT_TO_CURRENT -> adjacentTargets(context)
            else -> attackCandidates(attackType, context)
        }

    private fun attackCandidates(
        attackType: Int,
        context: SkillBattleContext,
    ): List<BattleHeroRef> {
        val normalized = attackType % 1000
        val source = context.source
        val view = context.battleView
        return when (normalized) {
            0 -> listOf(source)
            11, 13, 23 -> view.heroes().filter { it.side == source.side && it != source }
            21, 24 -> view.heroes().filter { it.side == source.side }
            41, 43, 94, 95, 96, 97 -> view.heroes().filter { it.side == source.side.opposite() }
            81 -> listOfNotNull(view.previousTarget(baseHero(view, source.side)))
            98 -> listOfNotNull(view.linkedTarget(source))
            99 -> listOfNotNull(view.currentTarget(source))
            113 -> view.heroes().filter { it != source }
            else -> throw IllegalArgumentException(
                "Unsupported attack_type=$attackType for skill ${context.currentSkillId}",
            )
        }
    }

    private fun adjacentTargets(context: SkillBattleContext): List<BattleHeroRef> {
        val view = context.battleView
        val current = view.currentTarget(context.source) ?: return emptyList()
        return view.heroes()
            .filter { it.side == current.side && kotlin.math.abs(it.position - current.position) <= 1 }
            .sortedWith(CLIENT_POSITION_ORDER)
    }

    private fun selectByAttribute(
        candidates: List<BattleHeroRef>,
        attribute: Int,
        view: SkillBattleView,
        minimum: Boolean,
    ): List<BattleHeroRef> {
        val selected = if (minimum) {
            candidates.minByOrNull { attributeValue(it, attribute, view) }
        } else {
            candidates.maxByOrNull { attributeValue(it, attribute, view) }
        }
        return selected?.let(::listOf).orEmpty()
    }

    private fun attributeValue(
        target: BattleHeroRef,
        attribute: Int,
        view: SkillBattleView,
    ): Int {
        val state = requireNotNull(view.state(target)) { "Missing live state for $target" }
        return when (attribute) {
            ATTRIBUTE_ATTACK -> state.stats.attack
            ATTRIBUTE_DEFENSE -> state.stats.defense
            ATTRIBUTE_STRATEGY -> state.stats.strategy
            ATTRIBUTE_SPEED -> state.stats.speed
            ATTRIBUTE_TROOPS -> state.troops
            else -> error("Unsupported select_attri=$attribute")
        }
    }

    private fun randomWithoutReplacement(
        candidates: List<BattleHeroRef>,
        count: Int,
        context: SkillBattleContext,
    ): List<BattleHeroRef> {
        if (candidates.size <= 1 || count <= 0) return candidates.take(count)
        val remaining = candidates.toMutableList()
        return buildList {
            repeat(minOf(count, remaining.size)) {
                add(remaining.removeAt(context.random.nextInt(remaining.size)))
            }
        }
    }

    private fun matchesTargetType(
        targetType: Int,
        metadata: SkillBattleHeroMetadata?,
    ): Boolean =
        when (targetType) {
            TARGET_ANY -> true
            TARGET_ARCHER_OR_INFANTRY ->
                requireMetadata(targetType, metadata).troopType in
                    setOf(SkillTroopType.ARCHER, SkillTroopType.INFANTRY)
            TARGET_CAVALRY_OR_INFANTRY ->
                requireMetadata(targetType, metadata).troopType in
                    setOf(SkillTroopType.CAVALRY, SkillTroopType.INFANTRY)
            TARGET_ARCHER -> requireMetadata(targetType, metadata).troopType == SkillTroopType.ARCHER
            TARGET_INFANTRY -> requireMetadata(targetType, metadata).troopType == SkillTroopType.INFANTRY
            TARGET_CAVALRY -> requireMetadata(targetType, metadata).troopType == SkillTroopType.CAVALRY
            TARGET_RATTAN_ARMOR ->
                SkillTroopCategory.RATTAN_ARMOR in requireMetadata(targetType, metadata).troopCategories
            TARGET_BARBARIAN ->
                SkillTroopCategory.BARBARIAN in requireMetadata(targetType, metadata).troopCategories
            TARGET_ELEPHANT ->
                SkillTroopCategory.ELEPHANT in requireMetadata(targetType, metadata).troopCategories
            else -> error("Unsupported target_type=$targetType")
        }

    private fun matchesPreconditionTarget(
        precondition: Int,
        context: SkillBattleContext,
        target: BattleHeroRef,
    ): Boolean =
        when (precondition) {
            80 -> target.side == context.source.side
            -80 -> target.side != context.source.side
            70 -> morale(target, context) < morale(context.source, context)
            -70 -> morale(target, context) >= morale(context.source, context)
            in HERO_ID_PRECONDITIONS -> target.heroId.value == precondition
            14 -> target.position == BASE_POSITION
            -14 -> target.position != BASE_POSITION
            16 -> target.position == FRONT_POSITION
            2099 -> morale(target, context) > HIGH_MORALE_THRESHOLD
            3100 -> morale(target, context) <= HIGH_MORALE_THRESHOLD
            6000 -> hasSpecialTroopCategory(target, context)
            -6000 -> !hasSpecialTroopCategory(target, context)
            else -> true
        }

    private fun hasSpecialTroopCategory(
        target: BattleHeroRef,
        context: SkillBattleContext,
    ): Boolean {
        require(SkillBattleViewCapability.HERO_METADATA in context.battleView.capabilities) {
            "precondition=${context.currentSkillId} requires live hero metadata"
        }
        val metadata = requireNotNull(context.battleView.metadata(target)) {
            "Missing live hero metadata for $target"
        }
        return metadata.troopCategories.any(SPECIAL_TROOP_CATEGORIES::contains)
    }

    private fun morale(
        ref: BattleHeroRef,
        context: SkillBattleContext,
    ): Int {
        require(SkillBattleViewCapability.LIVE_MORALE in context.battleView.capabilities) {
            "precondition=${context.currentSkillId} requires live morale"
        }
        return requireNotNull(context.battleView.currentMorale(ref)) {
            "Missing live morale for $ref"
        }
    }

    private fun matchesConditionTarget(
        condition: Int,
        context: SkillBattleContext,
        target: BattleHeroRef,
    ): Boolean {
        if (condition !in TROOP_RATIO_CONDITIONS) return true
        val state = requireNotNull(context.battleView.state(target)) {
            "Missing live state for $target"
        }
        if (state.maxTroops <= 0) return false
        val threshold = condition % 100
        return when (condition / 1000) {
            1 -> state.troops.toLong() * 100 < state.maxTroops.toLong() * threshold
            2 -> state.troops.toLong() * 100 > state.maxTroops.toLong() * threshold
            else -> error("Unsupported troop-ratio condition=$condition")
        }
    }

    private fun matchesCountryConditionTarget(
        condition: Int,
        context: SkillBattleContext,
        target: BattleHeroRef,
    ): Boolean {
        if (condition != 17000) return true
        require(SkillBattleViewCapability.HERO_METADATA in context.battleView.capabilities) {
            "condition=17000 requires live hero metadata"
        }
        val sourceCountry = requireNotNull(context.battleView.metadata(context.source)) {
            "Missing live hero metadata for ${context.source}"
        }.country
        val targetCountry = requireNotNull(context.battleView.metadata(target)) {
            "Missing live hero metadata for $target"
        }.country
        return targetCountry != sourceCountry
    }

    private fun matchesMoraleConditionTarget(
        condition: Int,
        context: SkillBattleContext,
        target: BattleHeroRef,
    ): Boolean =
        when (condition) {
            20160 -> morale(target, context) < 160
            else -> true
        }

    private fun matchesStatusConditionTarget(
        castCondition: Int,
        condition: Int,
        context: SkillBattleContext,
        target: BattleHeroRef,
    ): Boolean {
        val statusCondition = when {
            castCondition in STATUS_TARGET_CONDITIONS -> castCondition
            condition in STATUS_TARGET_CONDITIONS -> condition
            else -> return true
        }
        val statuses = requireNotNull(context.battleView.state(target)) {
            "Missing live state for $target"
        }.statuses
        return when (statusCondition) {
            500 -> BattleStatus.CONFUSION in statuses || BERSERK_EFFECT_IDS.any {
                it in context.battleView.activeEffectIds(target)
            }
            4000 -> statuses.any(CONTROL_STATUSES::contains) ||
                BERSERK_EFFECT_IDS.any { it in context.battleView.activeEffectIds(target) }
            4003 -> BERSERK_EFFECT_IDS.any { it in context.battleView.activeEffectIds(target) }
            4013 -> BattleStatus.CONFUSION in statuses || BERSERK_EFFECT_IDS.any {
                it in context.battleView.activeEffectIds(target)
            }
            6207 -> RECOVERY_BLOCK_EFFECT_ID in context.battleView.activeEffectIds(target)
            6306 -> BattleStatus.HEX in statuses
            7001 -> statuses.any(ONGOING_DAMAGE_STATUSES::contains)
            18306 -> BattleStatus.HEX in statuses
            else -> error("Unsupported status target condition=$statusCondition")
        }
    }

    private fun matchesAttributeCastConditionTarget(
        castCondition: Int,
        context: SkillBattleContext,
        target: BattleHeroRef,
    ): Boolean {
        if (castCondition !in ATTRIBUTE_TARGET_CAST_CONDITIONS) return true
        val sourceStats = requireNotNull(context.battleView.state(context.source)) {
            "Missing live state for ${context.source}"
        }.stats
        val targetStats = requireNotNull(context.battleView.state(target)) {
            "Missing live state for $target"
        }.stats
        return when (castCondition) {
            2313 -> targetStats.strategy < sourceStats.strategy
            2414 -> targetStats.speed < sourceStats.speed
            2434 -> targetStats.speed >= sourceStats.speed
            3103 -> targetStats.attack >= targetStats.strategy
            3123 -> targetStats.strategy > targetStats.attack
            11079, 14100 -> morale(target, context) == HIGH_MORALE_THRESHOLD
            11099 -> morale(target, context) > HIGH_MORALE_THRESHOLD
            12080 -> morale(target, context) < HIGH_MORALE_THRESHOLD
            12100 -> morale(target, context) <= HIGH_MORALE_THRESHOLD
            else -> error("Unsupported attribute cast condition=$castCondition")
        }
    }

    private fun matchesMarkerCastConditionTarget(
        castCondition: Int,
        context: SkillBattleContext,
        target: BattleHeroRef,
    ): Boolean =
        when (castCondition) {
            121002401 -> context.runtime.hasMarker(target, 21002401, context.round)
            321001701 -> context.runtime.hasMarker(target, 21001701, context.round)
            421001701 -> !context.runtime.hasMarker(target, 21001701, context.round)
            else -> true
        }

    private fun requireMetadata(
        targetType: Int,
        metadata: SkillBattleHeroMetadata?,
    ): SkillBattleHeroMetadata =
        requireNotNull(metadata) { "target_type=$targetType requires live hero metadata" }

    private fun inCurrentAttackRange(
        source: BattleHeroRef,
        target: BattleHeroRef,
        view: SkillBattleView,
    ): Boolean {
        if (source.side == target.side) return true
        val range = view.currentAttackRange(source) ?: return false
        return 5 - source.position - target.position <= range
    }

    private fun inSkillRange(
        source: BattleHeroRef,
        target: BattleHeroRef,
        skillHitRange: Int?,
    ): Boolean =
        skillHitRange == null || formationDistance(source, target) <= skillHitRange

    private fun formationDistance(source: BattleHeroRef, target: BattleHeroRef): Int =
        if (source.side == target.side) {
            kotlin.math.abs(source.position - target.position)
        } else {
            5 - source.position - target.position
        }

    private fun attackTypeIgnoresRange(attackType: Int): Boolean =
        attackType % 1000 in setOf(81, 98, 99, 113)

    private fun baseHero(view: SkillBattleView, side: Side): BattleHeroRef =
        view.heroes().firstOrNull { it.side == side && it.position == BASE_POSITION }
            ?: error("Missing base hero for $side")

    private fun SkillBattleView.isTargetable(ref: BattleHeroRef): Boolean =
        targetabilityState(ref)?.let { it.troops > 0 || it.canReceiveEffectsWhenDefeated } == true

    private fun SkillBattleView.targetabilityState(ref: BattleHeroRef): SkillBattleHeroState? =
        if (SkillBattleViewCapability.LIVE_STATE in capabilities) {
            state(ref)
        } else {
            entryState(ref)
        }

    private companion object {
        const val BASE_POSITION = 0
        const val MIDDLE_POSITION = 1
        const val FRONT_POSITION = 2
        val HERO_ID_PRECONDITIONS = setOf(100003, 100010, 100479, 100661)
        val TROOP_RATIO_CONDITIONS = setOf(1030, 1050, 1060, 1070, 1080, 1090, 2050, 2060)
        val STATUS_TARGET_CONDITIONS =
            setOf(500, 4000, 4003, 4013, 6207, 6306, 7001, 18306)
        val ATTRIBUTE_TARGET_CAST_CONDITIONS =
            setOf(2313, 2414, 2434, 3103, 3123, 11079, 11099, 12080, 12100, 14100)
        val SPECIAL_TROOP_CATEGORIES = setOf(
            SkillTroopCategory.BARBARIAN,
            SkillTroopCategory.RATTAN_ARMOR,
            SkillTroopCategory.ELEPHANT,
        )
        const val HIGH_MORALE_THRESHOLD = 100
        const val RECOVERY_BLOCK_EFFECT_ID = 207
        val CONTROL_STATUSES = setOf(
            BattleStatus.CONFUSION,
            BattleStatus.HESITATION,
            BattleStatus.DISARM,
        )
        val ONGOING_DAMAGE_STATUSES = setOf(
            BattleStatus.PANIC,
            BattleStatus.SHAKE,
            BattleStatus.BURN,
            BattleStatus.HEX,
        )
        val BERSERK_EFFECT_IDS = setOf(503, 703, 903)

        const val TARGET_ARCHER_OR_INFANTRY = -30
        const val TARGET_CAVALRY_OR_INFANTRY = -10
        const val TARGET_ANY = 0
        const val TARGET_ARCHER = 10
        const val TARGET_INFANTRY = 20
        const val TARGET_CAVALRY = 30
        const val TARGET_RATTAN_ARMOR = 42
        const val TARGET_BARBARIAN = 52
        const val TARGET_ELEPHANT = 53

        const val SELECT_RANDOM = 0
        const val SELECT_MINIMUM = 1
        const val SELECT_FARTHEST = 3
        const val SELECT_BASE = 4
        const val SELECT_MIDDLE = 5
        const val SELECT_FRONT = 6
        const val SELECT_MALE = 7
        const val SELECT_FEMALE = 8
        const val SELECT_MAXIMUM = 9
        const val SELECT_LINKED = 11
        const val SELECT_RANDOM_COUNT = 33
        const val SELECT_ALL = 34
        const val SELECT_GREATEST_DAMAGE = 900
        const val SELECT_HIGHEST_MORALE = 901
        const val SELECT_INSIDE_CURRENT_RANGE = 907
        const val SELECT_OUTSIDE_CURRENT_RANGE = 908
        const val SELECT_ADJACENT_TO_CURRENT = 3002

        const val ATTRIBUTE_ATTACK = 1
        const val ATTRIBUTE_DEFENSE = 2
        const val ATTRIBUTE_STRATEGY = 3
        const val ATTRIBUTE_SPEED = 4
        const val ATTRIBUTE_TROOPS = 8

        val TARGET_TYPES = setOf(-30, -10, 0, 10, 20, 30, 42, 52, 53)
        val SELECT_TYPES = setOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 11, 33, 34, 900, 901, 907, 908, 3002)
        val ATTRIBUTE_SELECTORS = setOf(1, 2, 3, 4, 8)
        val ATTACK_TYPES = setOf(0, 11, 13, 21, 23, 24, 41, 43, 81, 94, 95, 96, 97, 98, 99, 113)
        const val MAX_RANDOM_GROUP_SIZE = 2
        const val BENEFICIAL_BUFF_TYPE = 2
        val SOLO_SELF_FALLBACK_DETAILS = setOf(20000101, 20000102)

        val CLIENT_POSITION_ORDER = compareBy<BattleHeroRef> {
            when (it.side) {
                Side.ATTACKER -> it.position + 1
                Side.DEFENDER -> 6 - it.position
            }
        }
    }
}
