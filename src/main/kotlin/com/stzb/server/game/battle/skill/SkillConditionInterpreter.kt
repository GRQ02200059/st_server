package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleStatus
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

enum class SkillConditionField(val configName: String) {
    CAST_CONDITION("cast_condition"),
    PRECONDITION("precondition"),
    CONDITION("condition"),
}

data class SkillConditionCode(
    val skillId: Int,
    val field: SkillConditionField,
    val value: Int,
)

enum class Subject {
    SOURCE,
    CURRENT_TARGET,
}

enum class Comparison {
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    EQUAL,
    NOT_EQUAL,
    GREATER_THAN_OR_EQUAL,
    GREATER_THAN,
    ;

    internal fun matches(left: Long, right: Long): Boolean =
        when (this) {
            LESS_THAN -> left < right
            LESS_THAN_OR_EQUAL -> left <= right
            EQUAL -> left == right
            NOT_EQUAL -> left != right
            GREATER_THAN_OR_EQUAL -> left >= right
            GREATER_THAN -> left > right
        }
}

sealed interface SkillCondition {
    /**
     * A condition evaluated for each candidate by [SkillTargetSelector].
     * It is retained in the compiled condition list so coverage cannot
     * mistake a target predicate for an unconditional branch.
     */
    data class TargetPredicate(
        val kind: Kind,
        val value: Int? = null,
    ) : SkillCondition {
        enum class Kind {
            ALLY,
            ENEMY,
            MORALE_LOWER_THAN_SOURCE,
            MORALE_NOT_LOWER_THAN_SOURCE,
            HERO_ID,
            BASE_POSITION,
            NON_BASE_POSITION,
            FRONT_POSITION,
            TROOPS_BELOW_PERCENT,
            TROOPS_ABOVE_PERCENT,
            HAS_CONFUSION_OR_BERSERK,
            HAS_CONTROL_STATUS,
            HAS_ONGOING_DAMAGE_STATUS,
            MORALE_BELOW,
            HAS_HEX,
            MORALE_ABOVE,
            MORALE_AT_OR_BELOW,
            SPECIAL_TROOP_CATEGORY,
            NOT_SPECIAL_TROOP_CATEGORY,
            ATTACK_NOT_LOWER_THAN_STRATEGY,
            STRATEGY_GREATER_THAN_ATTACK,
            STRATEGY_LOWER_THAN_SOURCE,
            HAS_BERSERK,
            SPEED_LOWER_THAN_SOURCE,
            SPEED_NOT_LOWER_THAN_SOURCE,
            HAS_RECOVERY_BLOCK,
            MORALE_EQUAL,
            COUNTRY_DIFFERENT_FROM_SOURCE,
        }
    }

    data class RoundRange(
        val first: Int,
        val last: Int,
    ) : SkillCondition {
        init {
            require(first >= 0 && last >= first) {
                "Invalid round range: $first..$last"
            }
        }
    }

    data class TroopRatio(
        val side: Subject,
        val comparison: Comparison,
        val percent: Int,
    ) : SkillCondition {
        init {
            require(percent >= 0) { "Troop ratio must be non-negative: $percent" }
        }
    }

    data class AttackRange(
        val comparison: Comparison,
        val value: Int,
    ) : SkillCondition

    data class FormationRoster(
        val kind: Kind,
        val negated: Boolean = false,
    ) : SkillCondition {
        enum class Kind {
            SAME_COUNTRY,
            SAME_TROOP_TYPE,
            DISTINCT_COUNTRY,
            DISTINCT_BASE_ATTACK_RANGE,
        }
    }

    enum class CombatStat {
        ATTACK,
        STRATEGY,
        SPEED,
    }

    data class StatRef(
        val subject: Subject,
        val stat: CombatStat,
    )

    data class StatComparison(
        val left: StatRef,
        val comparison: Comparison,
        val right: StatRef,
    ) : SkillCondition

    data class ConfigBranch(
        val enabled: Boolean,
    ) : SkillCondition

    data class HasEffect(
        val subject: Subject,
        val effectId: Int,
        val negated: Boolean,
    ) : SkillCondition

    data class HasStatus(
        val subject: Subject,
        val status: BattleStatus,
        val negated: Boolean,
    ) : SkillCondition

    data class TriggerCount(
        val trigger: BattleTrigger,
        val comparison: Comparison,
        val value: Int,
        val subject: Subject = Subject.SOURCE,
        val skillId: Int? = null,
    ) : SkillCondition {
        init {
            require(value >= 0) { "Trigger count must be non-negative: $value" }
        }
    }

    data class HeroId(
        val subject: Subject,
        val heroId: Int,
        val negated: Boolean,
    ) : SkillCondition

    data class Country(
        val subject: Subject,
        val country: Int,
        val negated: Boolean = false,
    ) : SkillCondition

    data class HasMarker(
        val subject: Subject,
        val detailId: Int,
        val negated: Boolean = false,
    ) : SkillCondition

    sealed interface Unresolved : SkillCondition
}

data class SpecialConditionRequirement(
    val code: SkillConditionCode,
    val owner: String,
) : SkillCondition.Unresolved {
    @Deprecated("Use owner; retained for source compatibility")
    val pluginId: String
        get() = owner
}

interface SpecialSkillPlugin {
    val id: String
    val ownedConditions: Set<SkillConditionCode>

    fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition>
}

class CompiledSkillCondition internal constructor(
    val detailId: Int,
    conditions: Collection<SkillCondition>,
) {
    val conditions: List<SkillCondition> =
        Collections.unmodifiableList(ArrayList(conditions))

    fun matches(
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): Boolean =
        conditions.all { condition ->
            when (condition) {
                is SkillCondition.RoundRange -> context.round in condition.first..condition.last
                is SkillCondition.TroopRatio -> matchesTroopRatio(condition, context)
                is SkillCondition.AttackRange -> matchesAttackRange(condition, context)
                is SkillCondition.FormationRoster -> matchesFormationRoster(condition, context)
                is SkillCondition.StatComparison -> matchesStatComparison(condition, context)
                is SkillCondition.ConfigBranch -> condition.enabled
                is SkillCondition.HasEffect -> matchesEffect(condition, context)
                is SkillCondition.HasStatus -> matchesStatus(condition, context)
                is SkillCondition.TriggerCount -> matchesTriggerCount(condition, context)
                is SkillCondition.HeroId -> matchesHeroId(condition, context)
                is SkillCondition.Country -> matchesCountry(condition, context)
                is SkillCondition.HasMarker -> matchesMarker(condition, context)
                is SkillCondition.TargetPredicate -> true
                is SpecialConditionRequirement -> throw unresolved(condition, trigger)
            }
        }

    private fun matchesTroopRatio(
        condition: SkillCondition.TroopRatio,
        context: SkillBattleContext,
    ): Boolean {
        val ref = subject(condition.side, context) ?: return false
        if (SkillBattleViewCapability.LIVE_STATE !in context.battleView.capabilities) return false
        val state = context.battleView.state(ref) ?: return false
        if (state.maxTroops <= 0) return false
        return condition.comparison.matches(
            state.troops.toLong() * 100,
            state.maxTroops.toLong() * condition.percent,
        )
    }

    private fun matchesAttackRange(
        condition: SkillCondition.AttackRange,
        context: SkillBattleContext,
    ): Boolean {
        if (SkillBattleViewCapability.NORMAL_ATTACK_RANGE !in context.battleView.capabilities) {
            return false
        }
        val range = context.battleView.currentAttackRange(context.source) ?: return false
        return condition.comparison.matches(range.toLong(), condition.value.toLong())
    }

    private fun matchesFormationRoster(
        condition: SkillCondition.FormationRoster,
        context: SkillBattleContext,
    ): Boolean {
        if (SkillBattleViewCapability.HERO_ROSTER !in context.battleView.capabilities) return false
        val formation = context.battleView.heroes()
            .filter { it.side == context.source.side }
        if (formation.size != FORMATION_HERO_COUNT) return false
        val matches = when (condition.kind) {
            SkillCondition.FormationRoster.Kind.SAME_COUNTRY -> {
                if (SkillBattleViewCapability.HERO_METADATA !in context.battleView.capabilities) {
                    return false
                }
                formation.map { context.battleView.metadata(it)?.country ?: return false }
                    .distinct()
                    .size == 1
            }
            SkillCondition.FormationRoster.Kind.SAME_TROOP_TYPE -> {
                if (SkillBattleViewCapability.HERO_METADATA !in context.battleView.capabilities) {
                    return false
                }
                formation.map { context.battleView.metadata(it)?.troopType ?: return false }
                    .distinct()
                    .size == 1
            }
            SkillCondition.FormationRoster.Kind.DISTINCT_COUNTRY -> {
                if (SkillBattleViewCapability.HERO_METADATA !in context.battleView.capabilities) {
                    return false
                }
                formation.map { context.battleView.metadata(it)?.country ?: return false }
                    .distinct()
                    .size == FORMATION_HERO_COUNT
            }
            SkillCondition.FormationRoster.Kind.DISTINCT_BASE_ATTACK_RANGE -> {
                if (SkillBattleViewCapability.ENTRY_STATE !in context.battleView.capabilities) {
                    return false
                }
                formation.map { context.battleView.entryState(it)?.stats?.hitRange ?: return false }
                    .distinct()
                    .size == FORMATION_HERO_COUNT
            }
        }
        return if (condition.negated) !matches else matches
    }

    private fun matchesStatComparison(
        condition: SkillCondition.StatComparison,
        context: SkillBattleContext,
    ): Boolean {
        val left = statValue(condition.left, context) ?: return false
        val right = statValue(condition.right, context) ?: return false
        return condition.comparison.matches(left.toLong(), right.toLong())
    }

    private fun statValue(
        ref: SkillCondition.StatRef,
        context: SkillBattleContext,
    ): Int? {
        val hero = subject(ref.subject, context) ?: return null
        if (SkillBattleViewCapability.LIVE_STATE !in context.battleView.capabilities) return null
        val stats = context.battleView.state(hero)?.stats ?: return null
        return when (ref.stat) {
            SkillCondition.CombatStat.ATTACK -> stats.attack
            SkillCondition.CombatStat.STRATEGY -> stats.strategy
            SkillCondition.CombatStat.SPEED -> stats.speed
        }
    }

    private fun matchesEffect(
        condition: SkillCondition.HasEffect,
        context: SkillBattleContext,
    ): Boolean {
        val ref = subject(condition.subject, context) ?: return false
        if (SkillBattleViewCapability.ACTIVE_EFFECTS !in context.battleView.capabilities) {
            return false
        }
        val present = condition.effectId in context.battleView.activeEffectIds(ref)
        return if (condition.negated) !present else present
    }

    private fun matchesStatus(
        condition: SkillCondition.HasStatus,
        context: SkillBattleContext,
    ): Boolean {
        val ref = subject(condition.subject, context) ?: return false
        if (SkillBattleViewCapability.LIVE_STATE !in context.battleView.capabilities) return false
        val state = context.battleView.state(ref) ?: return false
        val present = condition.status in state.statuses
        return if (condition.negated) !present else present
    }

    private fun matchesTriggerCount(
        condition: SkillCondition.TriggerCount,
        context: SkillBattleContext,
    ): Boolean {
        val ref = subject(condition.subject, context) ?: return false
        val count = condition.skillId?.let {
            context.runtime.count(ref, condition.trigger, it)
        } ?: context.runtime.count(ref, condition.trigger)
        return condition.comparison.matches(count.toLong(), condition.value.toLong())
    }

    private fun matchesHeroId(
        condition: SkillCondition.HeroId,
        context: SkillBattleContext,
    ): Boolean {
        val ref = subject(condition.subject, context) ?: return false
        val matches = ref.heroId.value == condition.heroId
        return if (condition.negated) !matches else matches
    }

    private fun matchesCountry(
        condition: SkillCondition.Country,
        context: SkillBattleContext,
    ): Boolean {
        val ref = subject(condition.subject, context) ?: return false
        if (SkillBattleViewCapability.HERO_METADATA !in context.battleView.capabilities) return false
        val matches = context.battleView.metadata(ref)?.country == condition.country
        return if (condition.negated) !matches else matches
    }

    private fun matchesMarker(
        condition: SkillCondition.HasMarker,
        context: SkillBattleContext,
    ): Boolean {
        val ref = subject(condition.subject, context) ?: return false
        val present = context.runtime.hasMarker(ref, condition.detailId, context.round)
        return if (condition.negated) !present else present
    }

    private fun subject(
        subject: Subject,
        context: SkillBattleContext,
    ): BattleHeroRef? =
        when (subject) {
            Subject.SOURCE -> context.source
            Subject.CURRENT_TARGET -> {
                if (SkillBattleViewCapability.TARGET_HISTORY !in context.battleView.capabilities) {
                    null
                } else {
                    context.battleView.currentTarget(context.source)
                }
            }
        }

    private fun unresolved(
        requirement: SpecialConditionRequirement,
        trigger: BattleTrigger,
    ): UnsupportedPendingSkillConditionException {
        val code = requirement.code
        return UnsupportedPendingSkillConditionException(
            "Pending condition semantics: skill=${code.skillId} detail=$detailId " +
                "trigger=$trigger owner=${requirement.owner} " +
                "${code.field.configName}=${code.value}",
        )
    }
}

class SkillConditionInterpreter(
    private val graph: SkillRuleGraph,
    plugins: List<SpecialSkillPlugin> = emptyList(),
) : PendingSkillConditionInterpreter {
    private val cache = ConcurrentHashMap<CacheKey, CompiledSkillCondition>()
    private val unknown = Collections.synchronizedSet(linkedSetOf<SkillConditionCode>())
    private val pluginByCode: Map<SkillConditionCode, SpecialSkillPlugin>

    init {
        val custom = linkedMapOf<SkillConditionCode, SpecialSkillPlugin>()
        plugins.forEach { plugin ->
            require(plugin.id.isNotBlank()) { "Special skill plugin ID must not be blank" }
            plugin.ownedConditions.forEach { code ->
                if (plugin.id.startsWith("skill.")) {
                    require(plugin.id == "skill.${code.skillId}") {
                        "Plugin ${plugin.id} cannot own skill=${code.skillId}"
                    }
                }
                val previous = custom.putIfAbsent(code, plugin)
                require(previous == null) {
                    "Condition $code is owned by both ${previous?.id} and ${plugin.id}"
                }
            }
        }
        val all = linkedMapOf<SkillConditionCode, SpecialSkillPlugin>()
        all.putAll(custom)
        builtInTargetConditionPlugins(graph, custom.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInRoundConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInTroopRatioConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInStatusTargetConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInMoraleTargetConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInAttackRangeConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInFormationConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInAttributeConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInClientBranchConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInCountryConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        builtInMarkerConditionPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        defaultPendingPlugins(graph, all.keys).forEach { plugin ->
            plugin.ownedConditions.forEach { code -> all[code] = plugin }
        }
        pluginByCode = Collections.unmodifiableMap(all)
    }

    fun compile(rule: SkillEffectRule): CompiledSkillCondition {
        val key = CacheKey(
            detailId = rule.detailId,
            castCondition = rule.raw.castCondition,
            precondition = rule.raw.precondition,
            condition = rule.raw.condition,
        )
        return cache.computeIfAbsent(key) { compileUncached(rule) }
    }

    override fun matches(
        rule: SkillEffectRule,
        trigger: BattleTrigger,
        context: SkillBattleContext,
    ): Boolean = compile(rule).matches(trigger, context)

    fun unknownCodes(): Set<SkillConditionCode> =
        synchronized(unknown) {
            Collections.unmodifiableSet(LinkedHashSet(unknown))
        }

    private fun compileUncached(rule: SkillEffectRule): CompiledSkillCondition {
        val skillId = rule.detailId / 100
        val codes = listOf(
            SkillConditionCode(
                skillId,
                SkillConditionField.CAST_CONDITION,
                rule.raw.castCondition,
            ),
            SkillConditionCode(
                skillId,
                SkillConditionField.PRECONDITION,
                rule.raw.precondition,
            ),
            SkillConditionCode(
                skillId,
                SkillConditionField.CONDITION,
                rule.raw.condition,
            ),
        ).filter { it.value != 0 }
        val missing = codes.filterNot(pluginByCode::containsKey)
        if (missing.isNotEmpty()) {
            unknown += missing
            throw UnsupportedPendingSkillConditionException(
                "Unsupported condition semantics: skill=$skillId detail=${rule.detailId} " +
                    missing.joinToString { "${it.field.configName}=${it.value}" },
            )
        }
        val conditions = codes.flatMap { code ->
            val plugin = pluginByCode.getValue(code)
            val compiled = plugin.compile(code, rule)
            require(compiled.isNotEmpty()) {
                "Plugin ${plugin.id} returned no condition for $code detail=${rule.detailId}"
            }
            compiled
        }
        return CompiledSkillCondition(rule.detailId, conditions)
    }

    private data class CacheKey(
        val detailId: Int,
        val castCondition: Int,
        val precondition: Int,
        val condition: Int,
    )
}

private class BuiltInClientBranchConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            SkillCondition.ConfigBranch(
                enabled = when {
                    code.value.toString().startsWith(CURRENT_CLIENT_BRANCH_PREFIX) -> true
                    code.value.toString().startsWith(LEGACY_CLIENT_BRANCH_PREFIX) -> false
                    code.value in CURRENT_PARAMETER_BRANCHES -> true
                    code.value in LEGACY_PARAMETER_BRANCHES -> false
                    code.field == SkillConditionField.PRECONDITION && code.value == 18 -> true
                    code.field == SkillConditionField.PRECONDITION && code.value == -18 -> false
                    else -> error("Unsupported client branch condition $code")
                },
            ),
        )
}

private fun builtInClientBranchConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter {
            (
                it.field == SkillConditionField.CAST_CONDITION &&
                    (
                    it.value.toString().startsWith(CURRENT_CLIENT_BRANCH_PREFIX) ||
                        it.value.toString().startsWith(LEGACY_CLIENT_BRANCH_PREFIX) ||
                        it.value in CURRENT_PARAMETER_BRANCHES ||
                        it.value in LEGACY_PARAMETER_BRANCHES
                    )
                ) ||
                (
                    it.field == SkillConditionField.PRECONDITION &&
                        it.value in setOf(18, -18)
                    )
        }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInClientBranchConditionPlugin("builtin.client-balance-branch", codes))
    }
}

private class BuiltInCountryConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            SkillCondition.TargetPredicate(
                SkillCondition.TargetPredicate.Kind.COUNTRY_DIFFERENT_FROM_SOURCE,
            ),
        )
}

private fun builtInCountryConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter { it.field == SkillConditionField.CONDITION && it.value == 17000 }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInCountryConditionPlugin("builtin.target-country", codes))
    }
}

private class BuiltInMarkerConditionPlugin(
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val id: String = "builtin.detail-marker"
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> = listOf(
        when (code.value) {
            321001701 -> SkillCondition.HasMarker(
                Subject.SOURCE,
                detailId = 21001701,
            )
            421001701 -> SkillCondition.HasMarker(
                Subject.SOURCE,
                detailId = 21001701,
                negated = true,
            )
            else -> error("Unsupported marker condition $code")
        },
    )
}

private fun builtInMarkerConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter {
            it.field == SkillConditionField.CAST_CONDITION &&
                it.value in setOf(321001701, 421001701)
        }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) emptyList() else listOf(BuiltInMarkerConditionPlugin(codes))
}

private class BuiltInAttributeConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            when (code.value) {
                1103 -> sourceStatComparison(
                    SkillCondition.CombatStat.ATTACK,
                    Comparison.GREATER_THAN_OR_EQUAL,
                    SkillCondition.CombatStat.STRATEGY,
                )
                1123 -> sourceStatComparison(
                    SkillCondition.CombatStat.STRATEGY,
                    Comparison.GREATER_THAN,
                    SkillCondition.CombatStat.ATTACK,
                )
                3103 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.ATTACK_NOT_LOWER_THAN_STRATEGY,
                )
                3123 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.STRATEGY_GREATER_THAN_ATTACK,
                )
                2313 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.STRATEGY_LOWER_THAN_SOURCE,
                )
                4003 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.HAS_BERSERK,
                )
                2414 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.SPEED_LOWER_THAN_SOURCE,
                )
                2434 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.SPEED_NOT_LOWER_THAN_SOURCE,
                )
                4013 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.HAS_CONFUSION_OR_BERSERK,
                )
                5300 -> SkillCondition.Country(Subject.SOURCE, country = 3)
                6207 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.HAS_RECOVERY_BLOCK,
                )
                6306 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.HAS_HEX,
                )
                11079, 14100 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_EQUAL,
                    100,
                )
                11099 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_ABOVE,
                    100,
                )
                12080 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_BELOW,
                    100,
                )
                12100 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_AT_OR_BELOW,
                    100,
                )
                else -> error("Unsupported attribute condition $code")
            },
        )

    private fun sourceStatComparison(
        left: SkillCondition.CombatStat,
        comparison: Comparison,
        right: SkillCondition.CombatStat,
    ) = SkillCondition.StatComparison(
        SkillCondition.StatRef(Subject.SOURCE, left),
        comparison,
        SkillCondition.StatRef(Subject.SOURCE, right),
    )
}

private fun builtInAttributeConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter {
            it.field == SkillConditionField.CAST_CONDITION &&
                it.value in ATTRIBUTE_CAST_CONDITIONS
        }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInAttributeConditionPlugin("builtin.attribute-condition", codes))
    }
}

private class BuiltInFormationConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            when (code.value) {
                1 -> SkillCondition.FormationRoster(
                    SkillCondition.FormationRoster.Kind.SAME_COUNTRY,
                )
                2 -> SkillCondition.FormationRoster(
                    SkillCondition.FormationRoster.Kind.SAME_TROOP_TYPE,
                )
                -2 -> SkillCondition.FormationRoster(
                    SkillCondition.FormationRoster.Kind.SAME_TROOP_TYPE,
                    negated = true,
                )
                13 -> SkillCondition.FormationRoster(
                    SkillCondition.FormationRoster.Kind.DISTINCT_COUNTRY,
                )
                19 -> SkillCondition.FormationRoster(
                    SkillCondition.FormationRoster.Kind.DISTINCT_BASE_ATTACK_RANGE,
                )
                else -> error("Unsupported formation condition $code")
            },
        )
}

private fun builtInFormationConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter {
            it.field == SkillConditionField.PRECONDITION &&
                it.value in FORMATION_PRECONDITIONS
        }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInFormationConditionPlugin("builtin.formation", codes))
    }
}

private class BuiltInAttackRangeConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            when (code.value) {
                32002 -> SkillCondition.AttackRange(Comparison.LESS_THAN_OR_EQUAL, 1)
                32011 -> SkillCondition.AttackRange(Comparison.GREATER_THAN, 1)
                else -> error("Unsupported attack-range condition $code")
            },
        )
}

private fun builtInAttackRangeConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter { it.field == SkillConditionField.CONDITION && it.value in ATTACK_RANGE_CONDITIONS }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInAttackRangeConditionPlugin("builtin.attack-range", codes))
    }
}

private class BuiltInMoraleTargetConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            SkillCondition.TargetPredicate(
                SkillCondition.TargetPredicate.Kind.MORALE_BELOW,
                code.value % 10_000,
            ),
        )
}

private fun builtInMoraleTargetConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter { it.field == SkillConditionField.CONDITION && it.value in MORALE_TARGET_CONDITIONS }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInMoraleTargetConditionPlugin("builtin.target-morale", codes))
    }
}

private class BuiltInStatusTargetConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            SkillCondition.TargetPredicate(
                when (code.value) {
                    500 -> SkillCondition.TargetPredicate.Kind.HAS_CONFUSION_OR_BERSERK
                    4000 -> SkillCondition.TargetPredicate.Kind.HAS_CONTROL_STATUS
                    7001 -> SkillCondition.TargetPredicate.Kind.HAS_ONGOING_DAMAGE_STATUS
                    18306 -> SkillCondition.TargetPredicate.Kind.HAS_HEX
                    else -> error("Unsupported status target condition $code")
                },
            ),
        )
}

private fun builtInStatusTargetConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter {
            it.value in STATUS_TARGET_CONDITIONS &&
                it.field in setOf(
                    SkillConditionField.CAST_CONDITION,
                    SkillConditionField.CONDITION,
                )
        }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInStatusTargetConditionPlugin("builtin.target-status", codes))
    }
}

private class BuiltInTroopRatioConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> {
        val threshold = code.value % 100
        val kind = when (code.value / 1000) {
            1 -> SkillCondition.TargetPredicate.Kind.TROOPS_BELOW_PERCENT
            2 -> SkillCondition.TargetPredicate.Kind.TROOPS_ABOVE_PERCENT
            else -> error("Unsupported troop-ratio condition $code")
        }
        return listOf(SkillCondition.TargetPredicate(kind, threshold))
    }
}

private fun builtInTroopRatioConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter { it.field == SkillConditionField.CONDITION && it.value in TROOP_RATIO_CONDITIONS }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInTroopRatioConditionPlugin("builtin.target-troop-ratio", codes))
    }
}

private class BuiltInRoundConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            when (code.value) {
                104 -> SkillCondition.RoundRange(1, 3)
                203 -> SkillCondition.RoundRange(3, 3)
                205 -> SkillCondition.RoundRange(5, 5)
                207 -> SkillCondition.RoundRange(7, 7)
                303 -> SkillCondition.RoundRange(4, 8)
                else -> error("Unsupported built-in round condition $code")
            },
        )
}

private fun builtInRoundConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter { it.field == SkillConditionField.CAST_CONDITION && it.value in ROUND_CONDITIONS }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInRoundConditionPlugin("builtin.round-condition", codes))
    }
}

private class BuiltInTargetConditionPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(
            when (code.value) {
                80 -> SkillCondition.TargetPredicate(SkillCondition.TargetPredicate.Kind.ALLY)
                -80 -> SkillCondition.TargetPredicate(SkillCondition.TargetPredicate.Kind.ENEMY)
                70 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_LOWER_THAN_SOURCE,
                )
                -70 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_NOT_LOWER_THAN_SOURCE,
                )
                in HERO_ID_PRECONDITIONS -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.HERO_ID,
                    code.value,
                )
                14 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.BASE_POSITION,
                )
                -14 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.NON_BASE_POSITION,
                )
                16 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.FRONT_POSITION,
                )
                2099 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_ABOVE,
                    100,
                )
                3100 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.MORALE_AT_OR_BELOW,
                    100,
                )
                6000 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.SPECIAL_TROOP_CATEGORY,
                )
                -6000 -> SkillCondition.TargetPredicate(
                    SkillCondition.TargetPredicate.Kind.NOT_SPECIAL_TROOP_CATEGORY,
                )
                else -> error("Unsupported built-in target condition $code")
            },
        )
}

private fun builtInTargetConditionPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> {
    val codes = graph.details
        .flatMap(::conditionCodes)
        .filter { code ->
            code.field == SkillConditionField.PRECONDITION &&
                (
                    code.value in TARGET_PRECONDITIONS ||
                        code.value in HERO_ID_PRECONDITIONS ||
                        code.value in POSITION_PRECONDITIONS
                    )
        }
        .filterNot(overridden::contains)
        .toSet()
    return if (codes.isEmpty()) {
        emptyList()
    } else {
        listOf(BuiltInTargetConditionPlugin("builtin.target-precondition", codes))
    }
}

private class PendingSpecialSkillPlugin(
    override val id: String,
    ownedConditions: Set<SkillConditionCode>,
) : SpecialSkillPlugin {
    override val ownedConditions: Set<SkillConditionCode> =
        Collections.unmodifiableSet(LinkedHashSet(ownedConditions))

    override fun compile(
        code: SkillConditionCode,
        rule: SkillEffectRule,
    ): List<SkillCondition> =
        listOf(SpecialConditionRequirement(code, id))
}

private val TARGET_PRECONDITIONS = setOf(-6000, -80, -70, 70, 80, 2099, 3100, 6000)
private val HERO_ID_PRECONDITIONS = setOf(100003, 100010, 100479, 100661)
private val POSITION_PRECONDITIONS = setOf(-14, 14, 16)
private val ROUND_CONDITIONS = setOf(104, 203, 205, 207, 303)
private val TROOP_RATIO_CONDITIONS = setOf(1030, 1050, 1060, 1070, 1080, 1090, 2050, 2060)
private val STATUS_TARGET_CONDITIONS = setOf(500, 4000, 7001, 18306)
private val MORALE_TARGET_CONDITIONS = setOf(20160)
private val ATTACK_RANGE_CONDITIONS = setOf(32002, 32011)
private val FORMATION_PRECONDITIONS = setOf(1, 2, -2, 13, 19)
private val ATTRIBUTE_CAST_CONDITIONS =
    setOf(
        1103, 1123, 2313, 2414, 2434, 3103, 3123, 4003, 4013,
        5300, 6207, 6306, 11079, 11099, 12080, 12100, 14100,
    )
private const val FORMATION_HERO_COUNT = 3
private const val CURRENT_CLIENT_BRANCH_PREFIX = "127"
private const val LEGACY_CLIENT_BRANCH_PREFIX = "227"
private val CURRENT_PARAMETER_BRANCHES =
    setOf(230001912, 230005101, 130005205, 230005301)
private val LEGACY_PARAMETER_BRANCHES =
    setOf(130001912, 130005101, 130005301)

private fun defaultPendingPlugins(
    graph: SkillRuleGraph,
    overridden: Set<SkillConditionCode>,
): List<SpecialSkillPlugin> =
    graph.details
        .flatMap(::conditionCodes)
        .filter(ScopedConditionCodeCatalog::contains)
        .filterNot(overridden::contains)
        .groupBy(SkillConditionCode::skillId)
        .map { (skillId, codes) ->
            PendingSpecialSkillPlugin("skill.$skillId", codes.toSet())
        }

private fun conditionCodes(rule: SkillEffectRule): List<SkillConditionCode> {
    val skillId = rule.detailId / 100
    return listOf(
        SkillConditionCode(
            skillId,
            SkillConditionField.CAST_CONDITION,
            rule.raw.castCondition,
        ),
        SkillConditionCode(
            skillId,
            SkillConditionField.PRECONDITION,
            rule.raw.precondition,
        ),
        SkillConditionCode(
            skillId,
            SkillConditionField.CONDITION,
            rule.raw.condition,
        ),
    ).filter { it.value != 0 }
}

internal object ScopedConditionCodeCatalog {
    private val ownersByFieldAndValue = mapOf(
        SkillConditionField.CAST_CONDITION to mapOf(
            104 to setOf(200885), 203 to setOf(210265), 205 to setOf(210265),
            207 to setOf(210265), 303 to setOf(200292, 200885), 400 to setOf(200957),
            401 to setOf(200957), 402 to setOf(200957), 403 to setOf(200957),
            404 to setOf(200957), 405 to setOf(200957), 406 to setOf(200957),
            500 to setOf(200003), 1103 to setOf(213294), 1123 to setOf(213294),
            2313 to setOf(210968, 212991, 230963), 2414 to setOf(212961),
            2434 to setOf(212961), 3103 to setOf(200273, 210915, 211915),
            3123 to setOf(200273, 210915, 211915), 4000 to setOf(200243, 211965),
            4003 to setOf(210968), 4013 to setOf(200003, 220983), 5300 to setOf(214298),
            6207 to setOf(200024), 6306 to setOf(200796), 7001 to setOf(200243),
            11079 to setOf(211677), 11099 to setOf(210072, 210981),
            12080 to setOf(211677), 12100 to setOf(210072, 210981),
            14100 to setOf(210677), 121002401 to setOf(200024),
            121079601 to setOf(200796), 121196601 to setOf(210966),
            121329301 to setOf(214293), 121384301 to setOf(214843),
            127000501 to setOf(200005), 127000601 to setOf(200006),
            127001101 to setOf(200011), 127001701 to setOf(200017),
            127001901 to setOf(200019, 210019), 127002201 to setOf(200022),
            127002301 to setOf(200023), 127007201 to setOf(200072),
            127008001 to setOf(200080), 127027001 to setOf(210270),
            127065501 to setOf(200655), 127067701 to setOf(200677, 212677, 213677),
            127068001 to setOf(200680), 127068101 to setOf(200681),
            127068901 to setOf(200689), 127072301 to setOf(210723),
            127073201 to setOf(200732, 210732), 127075601 to setOf(210756),
            127076401 to setOf(200764), 127077101 to setOf(200771),
            127082801 to setOf(200828), 127084801 to setOf(200848),
            127084901 to setOf(200849), 127091501 to setOf(200915),
            127092701 to setOf(200927), 127093901 to setOf(210939),
            127094701 to setOf(200947), 130001912 to setOf(200719),
            130005101 to setOf(200194, 200198, 200201, 200204),
            130005205 to setOf(
                200643, 200644, 200645, 210643, 210644, 210645, 211643, 211644, 211645,
            ),
            130005301 to setOf(200184, 200734), 220028331 to setOf(200283),
            220096801 to setOf(200968), 220096802 to setOf(200968),
            220097913 to setOf(200979), 221095712 to setOf(211957),
            221384301 to setOf(212843), 227000501 to setOf(200005),
            227002201 to setOf(200022), 227002301 to setOf(200023),
            227003301 to setOf(200033), 227007201 to setOf(200072),
            227008001 to setOf(200080), 227027001 to setOf(210270),
            227065501 to setOf(200655), 227068001 to setOf(200680),
            227068101 to setOf(200681), 227068901 to setOf(200689),
            227072301 to setOf(210723), 227073201 to setOf(200732),
            227075601 to setOf(210756), 227077101 to setOf(200771),
            227082801 to setOf(200828), 227084801 to setOf(200848),
            227084901 to setOf(200849), 227091501 to setOf(200915),
            227092701 to setOf(200927), 227094701 to setOf(200947),
            230001912 to setOf(200719),
            230005101 to setOf(200194, 200198, 200201, 200204),
            230005301 to setOf(200184, 200734), 320000301 to setOf(200003),
            320024411 to setOf(214244), 320024421 to setOf(214244),
            320024601 to setOf(213246), 320025101 to setOf(200251, 210251),
            320025111 to setOf(213251), 320025122 to setOf(212251),
            320026412 to setOf(211264), 320026811 to setOf(211268),
            320092602 to setOf(200926), 321001701 to setOf(200017),
            321024601 to setOf(212246), 321025111 to setOf(212251),
            321025601 to setOf(211256), 321098402 to setOf(200984),
            321125401 to setOf(211254, 212254), 321126401 to setOf(215264),
            321199301 to setOf(212993), 321226402 to setOf(212264, 214264),
            321296501 to setOf(211965), 321299001 to setOf(213990),
            321324601 to setOf(211246), 321325201 to setOf(212252, 214252),
            321396501 to setOf(211965), 321399101 to setOf(212991),
            321496501 to setOf(211965), 321525101 to setOf(210251),
            321529301 to setOf(211293), 322200801 to setOf(221008),
            327002401 to setOf(210024), 420000802 to setOf(200008),
            420024301 to setOf(200243), 420024302 to setOf(211243),
            420026421 to setOf(200264), 420026822 to setOf(200268),
            421001701 to setOf(200017), 421196502 to setOf(211965),
            421196601 to setOf(211966), 421325701 to setOf(214257),
            421529301 to setOf(211293),
        ),
        SkillConditionField.PRECONDITION to mapOf(
            -6000 to setOf(200297, 211297),
            -80 to setOf(200273, 210677, 211254, 213244, 213246, 214244),
            -70 to setOf(200982), -18 to setOf(200884),
            -14 to setOf(200252, 200266, 200843, 200900, 200958, 200991, 200993),
            -2 to setOf(200789), 1 to setOf(200784), 2 to setOf(200789, 200844),
            13 to setOf(200964), 14 to setOf(200266), 16 to setOf(200674),
            18 to setOf(200884), 19 to setOf(200248, 210248),
            43 to setOf(210828, 211828, 213828), 70 to setOf(200982, 200992),
            80 to setOf(
                200273, 210265, 211016, 211256, 211264, 211265, 212266, 214244,
                214275, 215251,
            ),
            500 to setOf(210282), 2099 to setOf(200707, 200986),
            3100 to setOf(200762, 200986), 4040 to setOf(212255),
            6000 to setOf(200297, 211297), 100003 to setOf(200902),
            100010 to setOf(200902), 100479 to setOf(200902),
            100661 to setOf(200902),
        ),
        SkillConditionField.CONDITION to mapOf(
            1030 to setOf(200939, 200941),
            1050 to setOf(200256, 200884, 200939, 200941, 200958),
            1060 to setOf(200288, 200882),
            1070 to setOf(200288, 200939, 200941, 200958),
            1080 to setOf(200288),
            1090 to setOf(200288, 200939, 200941, 200958),
            2050 to setOf(200884), 2060 to setOf(200944), 5001 to setOf(200293),
            5003 to setOf(200016, 200244, 200253),
            5005 to setOf(200244, 200297, 200961),
            5006 to setOf(200277, 200294), 5007 to setOf(200950, 210298),
            5008 to setOf(200277), 5009 to setOf(200275), 15002 to setOf(210270),
            15003 to setOf(210270), 17000 to setOf(200964), 18306 to setOf(200795),
            20160 to setOf(200241), 21110 to setOf(200016),
            24001 to setOf(200989, 201006, 210257), 25002 to setOf(210269),
            25003 to setOf(210269), 25011 to setOf(214254), 26636 to setOf(200008),
            29001 to setOf(200264), 29004 to setOf(200255), 30000 to setOf(200255),
            32002 to setOf(200258), 32011 to setOf(200258), 33003 to setOf(210257),
            33004 to setOf(210257), 33005 to setOf(210298),
        ),
    )

    val codes: Set<SkillConditionCode> =
        ownersByFieldAndValue.flatMapTo(linkedSetOf()) { (field, values) ->
            values.flatMap { (value, skillIds) ->
                skillIds.map { skillId -> SkillConditionCode(skillId, field, value) }
            }
        }

    fun contains(code: SkillConditionCode): Boolean =
        code in codes
}
