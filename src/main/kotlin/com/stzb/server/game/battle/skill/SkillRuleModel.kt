package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.SkillDetailConfig
import com.stzb.server.game.battle.SkillKind

data class SkillRule(
    val skillId: Int,
    val kind: SkillKind,
    val probability: Int,
    val prepareRounds: Int,
    val hitRange: Int?,
    val details: List<SkillEffectRule>,
)

data class SkillEffectRule(
    val detailId: Int,
    val effectId: Int,
    val childSkillIds: Set<Int>,
    val raw: SkillDetailConfig,
)

data class SkillDiagnostic(
    val skillId: Int,
    val detailId: Int?,
    val effectId: Int?,
    val code: String,
    val dependencyPath: String,
)

class SkillRuleGraph(
    rules: Map<Int, SkillRule>,
    effectIds: Set<Int>,
) {
    private val rules: Map<Int, SkillRule> = rules.mapValues { (_, rule) ->
        rule.copy(
            details = rule.details.map { detail ->
                detail.copy(childSkillIds = detail.childSkillIds.toSet())
            },
        )
    }

    val executionNodeIds: Set<Int> = this.rules.keys.toSet()
    val effectIds: Set<Int> = effectIds.toSet()
    val details: List<SkillEffectRule> = this.rules.values.flatMap { it.details }.toList()

    fun rule(skillId: Int): SkillRule? = rules[skillId]

    fun validate(): List<SkillDiagnostic> =
        missingDependencyDiagnostics() + missingEffectDiagnostics() + cycleDiagnostics()

    private fun missingDependencyDiagnostics(): List<SkillDiagnostic> =
        rules.values.flatMap { rule ->
            rule.details.flatMap { detail ->
                detail.childSkillIds
                    .filterNot(rules::containsKey)
                    .map { childSkillId ->
                        SkillDiagnostic(
                            skillId = rule.skillId,
                            detailId = detail.detailId,
                            effectId = detail.effectId,
                            code = "MISSING_SKILL",
                            dependencyPath = "${rule.skillId} -> $childSkillId",
                        )
                    }
            }
        }

    private fun missingEffectDiagnostics(): List<SkillDiagnostic> =
        rules.values.flatMap { rule ->
            rule.details
                .filter { it.effectId != 0 && it.effectId !in effectIds }
                .map { detail ->
                    SkillDiagnostic(
                        skillId = rule.skillId,
                        detailId = detail.detailId,
                        effectId = detail.effectId,
                        code = "MISSING_EFFECT",
                        dependencyPath = rule.skillId.toString(),
                    )
                }
        }

    private fun cycleDiagnostics(): List<SkillDiagnostic> {
        val visited = mutableSetOf<Int>()
        val activePath = mutableListOf<Int>()
        val activeIndices = mutableMapOf<Int, Int>()
        val diagnostics = mutableListOf<SkillDiagnostic>()
        val reportedPaths = mutableSetOf<String>()

        fun visit(skillId: Int) {
            activeIndices[skillId] = activePath.size
            activePath += skillId

            rules.getValue(skillId).details.forEach { detail ->
                detail.childSkillIds.filter(rules::containsKey).forEach { childSkillId ->
                    val cycleStart = activeIndices[childSkillId]
                    when {
                        cycleStart != null -> {
                            val path = (activePath.drop(cycleStart) + childSkillId).joinToString(" -> ")
                            if (reportedPaths.add(path)) {
                                diagnostics += SkillDiagnostic(
                                    skillId = skillId,
                                    detailId = detail.detailId,
                                    effectId = detail.effectId,
                                    code = "DEPENDENCY_CYCLE",
                                    dependencyPath = path,
                                )
                            }
                        }
                        childSkillId !in visited -> visit(childSkillId)
                    }
                }
            }

            activePath.removeAt(activePath.lastIndex)
            activeIndices.remove(skillId)
            visited += skillId
        }

        rules.keys.forEach { skillId ->
            if (skillId !in visited) visit(skillId)
        }
        return diagnostics
    }
}
