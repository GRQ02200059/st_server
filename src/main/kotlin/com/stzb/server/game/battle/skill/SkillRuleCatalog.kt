package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.SkillDetailConfig

object SkillRuleCatalog {
    fun build(scope: SkillScope, config: BattleConfigRepository): SkillRuleGraph {
        val rules = linkedMapOf<Int, SkillRule>()

        fun visit(skillId: Int) {
            if (skillId in rules) return
            val skill = config.skill(skillId) ?: return
            val details = config.skillDetails(skillId).map { detail ->
                SkillEffectRule(
                    detailId = detail.detailId,
                    effectId = detail.effectId,
                    childSkillIds = childSkillIds(detail, config),
                    raw = detail,
                )
            }
            rules[skillId] = SkillRule(
                skillId = skill.id,
                kind = skill.kind,
                probability = skill.probabilityMax,
                prepareRounds = skill.prepareRounds,
                hitRange = skill.hitRange,
                details = details,
            )
            details.flatMapTo(linkedSetOf()) { it.childSkillIds }.forEach(::visit)
        }

        scope.mainSkillIds.sorted().forEach(::visit)
        val effectIds = rules.values
            .flatMap { it.details }
            .mapNotNull { detail ->
                when {
                    detail.effectId == 0 -> 0
                    else -> config.skillEffect(detail.effectId)?.effectId
                }
            }
            .toSet()
        return SkillRuleGraph(rules, effectIds, scope.mainSkillIds)
    }

    private fun childSkillIds(
        detail: SkillDetailConfig,
        config: BattleConfigRepository,
    ): Set<Int> {
        val candidates = when (detail.effectId) {
            122, 123 -> listOf(detail.constantParam)
            210 -> listOf(detail.effectParam)
            else -> emptyList()
        }
        return candidates
            .filter { config.skill(it) != null }
            .toSet()
    }
}
