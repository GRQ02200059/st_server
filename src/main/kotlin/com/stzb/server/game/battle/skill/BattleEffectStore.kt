package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActiveSkillEffect
import com.stzb.server.game.battle.BattleHeroRef
import java.util.Collections

enum class EffectApplyOutcome {
    APPLIED,
    REPLACED,
    STACKED,
    REFRESHED,
    REJECTED,
}

data class EffectApplyResult(
    val outcome: EffectApplyOutcome,
    val effect: ActiveSkillEffect?,
    val removed: List<ActiveSkillEffect> = emptyList(),
)

enum class EffectTickBoundary {
    ACTION_END,
    ROUND_END,
}

data class EffectLifecycleResult(
    val updated: List<ActiveSkillEffect> = emptyList(),
    val expired: List<ActiveSkillEffect> = emptyList(),
    val removed: List<ActiveSkillEffect> = emptyList(),
)

class BattleEffectStore {
    private val active = mutableMapOf<BattleHeroRef, MutableList<ActiveSkillEffect>>()

    fun apply(effect: ActiveSkillEffect): EffectApplyResult {
        val incoming = effect.snapshot()
        if (incoming.isAlreadyExpired()) {
            return applyResult(EffectApplyOutcome.REJECTED, null)
        }

        val effects = active.getOrPut(incoming.target) { mutableListOf() }
        val conflict = effects.firstOrNull { it.conflictsWith(incoming) }
        if (conflict == null) {
            effects += incoming
            return applyResult(EffectApplyOutcome.APPLIED, incoming)
        }

        return when (incoming.replaceType) {
            STACK -> stackOrRefresh(conflict, incoming)
            KEEP_EXISTING -> applyResult(EffectApplyOutcome.REJECTED, conflict)
            REPLACE_BY_STRENGTH -> replaceByStrength(effects, conflict, incoming)
            REPLACE_ALWAYS -> replace(effects, conflict, incoming)
            else -> error("ActiveSkillEffect validates replaceType")
        }
    }

    fun effectsFor(target: BattleHeroRef): List<ActiveSkillEffect> =
        immutableSnapshots(active[target].orEmpty())

    fun consumeHit(
        target: BattleHeroRef,
        effectId: Int,
        source: BattleHeroRef? = null,
    ): EffectLifecycleResult {
        val effects = active[target] ?: return lifecycle()
        val updated = mutableListOf<ActiveSkillEffect>()
        val expired = mutableListOf<ActiveSkillEffect>()
        effects
            .filter {
                it.effectId == effectId &&
                    (source == null || it.source == source) &&
                    (it.remainingHits != null || it.clearPerHit)
            }
            .toList()
            .forEach { effect ->
                if (effect.clearPerHit) {
                    effects.remove(effect)
                    expired += effect
                } else {
                    effect.remainingHits?.let { effect.remainingHits = it - 1 }
                    if (effect.remainingHits == 0) {
                        effects.remove(effect)
                        expired += effect
                    } else {
                        updated += effect
                    }
                }
            }
        removeTargetIfEmpty(target)
        return lifecycle(updated = updated, expired = expired)
    }

    fun tick(
        boundary: EffectTickBoundary = EffectTickBoundary.ROUND_END,
    ): EffectLifecycleResult {
        if (boundary != EffectTickBoundary.ROUND_END) return lifecycle()

        val updated = mutableListOf<ActiveSkillEffect>()
        val expired = mutableListOf<ActiveSkillEffect>()
        active.values.forEach { effects ->
            effects.toList().forEach { effect ->
                effect.remainingRounds?.let { effect.remainingRounds = it - 1 }
                if (effect.remainingRounds == 0) {
                    effects.remove(effect)
                    expired += effect
                } else if (effect.remainingRounds != null) {
                    updated += effect
                }
            }
        }
        removeEmptyTargets()
        return lifecycle(updated = updated, expired = expired)
    }

    fun clear(
        target: BattleHeroRef,
        effectId: Int? = null,
        source: BattleHeroRef? = null,
    ): EffectLifecycleResult =
        clearMatching(target, effectId, source)

    private fun clearMatching(
        target: BattleHeroRef,
        effectId: Int?,
        source: BattleHeroRef?,
    ): EffectLifecycleResult {
        val effects = active[target] ?: return lifecycle()
        val directlyMatched = effects.filter {
            it.clearable &&
                (source == null || it.source == source) &&
                (effectId == null || it.effectId == effectId)
        }
        if (directlyMatched.isEmpty()) return lifecycle()

        val boundKeys = directlyMatched
            .filter { it.bindFlag != UNBOUND }
            .map { BoundKey(it.source, it.bindFlag) }
            .toSet()
        val removed = effects.filter { effect ->
            effect.clearable &&
                (effect in directlyMatched ||
                    effect.bindFlag != UNBOUND &&
                    BoundKey(effect.source, effect.bindFlag) in boundKeys)
        }
        effects.removeAll(removed.toSet())
        removeTargetIfEmpty(target)
        return lifecycle(removed = removed)
    }

    private fun stackOrRefresh(
        existing: ActiveSkillEffect,
        incoming: ActiveSkillEffect,
    ): EffectApplyResult {
        val stacked = existing.stacks < existing.maxStacks
        if (stacked) existing.stacks++
        refreshLifecycle(existing, incoming)
        return applyResult(
            if (stacked) EffectApplyOutcome.STACKED else EffectApplyOutcome.REFRESHED,
            existing,
        )
    }

    private fun replaceByStrength(
        effects: MutableList<ActiveSkillEffect>,
        existing: ActiveSkillEffect,
        incoming: ActiveSkillEffect,
    ): EffectApplyResult =
        when {
            incoming.strength > existing.strength -> replace(effects, existing, incoming)
            incoming.strength == existing.strength -> {
                refreshLifecycle(existing, incoming)
                applyResult(EffectApplyOutcome.REFRESHED, existing)
            }
            else -> applyResult(EffectApplyOutcome.REJECTED, existing)
        }

    private fun replace(
        effects: MutableList<ActiveSkillEffect>,
        existing: ActiveSkillEffect,
        incoming: ActiveSkillEffect,
    ): EffectApplyResult {
        val index = effects.indexOf(existing)
        effects[index] = incoming
        return applyResult(
            outcome = EffectApplyOutcome.REPLACED,
            effect = incoming,
            removed = listOf(existing),
        )
    }

    private fun refreshLifecycle(
        existing: ActiveSkillEffect,
        incoming: ActiveSkillEffect,
    ) {
        existing.remainingRounds = incoming.remainingRounds
        existing.remainingHits = incoming.remainingHits
    }

    private fun ActiveSkillEffect.conflictsWith(other: ActiveSkillEffect): Boolean =
        target == other.target &&
            category == other.category &&
            conflict == other.conflict &&
            skillKind == other.skillKind

    private fun ActiveSkillEffect.isAlreadyExpired(): Boolean =
        remainingRounds == 0 || remainingHits == 0

    private fun removeTargetIfEmpty(target: BattleHeroRef) {
        if (active[target].isNullOrEmpty()) active.remove(target)
    }

    private fun removeEmptyTargets() {
        active.entries.removeAll { it.value.isEmpty() }
    }

    private fun applyResult(
        outcome: EffectApplyOutcome,
        effect: ActiveSkillEffect?,
        removed: List<ActiveSkillEffect> = emptyList(),
    ): EffectApplyResult =
        EffectApplyResult(
            outcome = outcome,
            effect = effect?.snapshot(),
            removed = immutableSnapshots(removed),
        )

    private fun lifecycle(
        updated: List<ActiveSkillEffect> = emptyList(),
        expired: List<ActiveSkillEffect> = emptyList(),
        removed: List<ActiveSkillEffect> = emptyList(),
    ): EffectLifecycleResult =
        EffectLifecycleResult(
            updated = immutableSnapshots(updated),
            expired = immutableSnapshots(expired),
            removed = immutableSnapshots(removed),
        )

    private fun immutableSnapshots(
        effects: Collection<ActiveSkillEffect>,
    ): List<ActiveSkillEffect> =
        Collections.unmodifiableList(effects.map { it.snapshot() })

    private fun ActiveSkillEffect.snapshot(): ActiveSkillEffect = copy()

    private data class BoundKey(val source: BattleHeroRef, val bindFlag: Int)

    private companion object {
        const val STACK = 0
        const val KEEP_EXISTING = 1
        const val REPLACE_BY_STRENGTH = 2
        const val REPLACE_ALWAYS = 3
        const val UNBOUND = 0
    }
}
