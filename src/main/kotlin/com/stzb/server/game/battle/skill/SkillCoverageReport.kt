package com.stzb.server.game.battle.skill

import java.util.Collections

data class SkillCoverageReport(
    val missingPluginSkillIds: Set<Int>,
    val duplicateExecutionSkillIds: Set<Int>,
    val pendingConditionCodes: Set<SkillConditionCode>,
    val unresolvedConditionOwnerSkillIds: Set<Int>,
    val executionPluginSkillIds: Set<Int>,
) {
    companion object {
        fun generate(
            graph: SkillRuleGraph,
            conditionInterpreter: SkillConditionInterpreter,
            executionPlugins: SpecialSkillPluginRegistry,
            ownershipCatalog: SkillExecutionOwnershipCatalog =
                ConfiguredSpecialSkillPlugins.ownershipCatalog,
        ): SkillCoverageReport {
            val pending = graph.details
                .flatMap { conditionInterpreter.compile(it).conditions }
                .filterIsInstance<SpecialConditionRequirement>()
                .mapTo(linkedSetOf()) { it.code }
            val executionIds = executionPlugins.ownedSkillIds()
            val replacingExecutionIds = executionPlugins.all()
                .filter(SkillExecutionPlugin::replacesConfiguredExecution)
                .flatMapTo(linkedSetOf(), SkillExecutionPlugin::skillIds)
            val requiredIds = ownershipCatalog.requiredNonDeclarativeSkillIds
            return SkillCoverageReport(
                missingPluginSkillIds = immutableSet(
                    requiredIds - replacingExecutionIds,
                ),
                duplicateExecutionSkillIds = immutableSet(
                    executionIds.filter { skillId ->
                        graph.rule(skillId) != null &&
                            executionPlugins.pluginFor(skillId)?.replacesConfiguredExecution != true
                    },
                ),
                pendingConditionCodes = immutableSet(pending),
                unresolvedConditionOwnerSkillIds = immutableSet(pending.map { it.skillId }),
                executionPluginSkillIds = immutableSet(executionIds),
            )
        }

        private fun <T> immutableSet(values: Collection<T>): Set<T> =
            Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
