package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleStatus
import com.stzb.server.game.battle.Side

enum class SkillHeroGender {
    MALE,
    FEMALE,
    UNKNOWN,
}

enum class SkillTroopType {
    ARCHER,
    INFANTRY,
    CAVALRY,
    UNKNOWN,
}

enum class SkillTroopCategory {
    RATTAN_ARMOR,
    BARBARIAN,
    ELEPHANT,
}

data class SkillBattleHeroState(
    val stats: BattleStats,
    val troops: Int,
    val maxTroops: Int,
    val statuses: Set<BattleStatus>,
    val morale: Int,
    val attackRange: Int,
    val canReceiveEffectsWhenDefeated: Boolean = false,
)

data class SkillBattleHeroMetadata(
    val gender: SkillHeroGender,
    val troopType: SkillTroopType,
    val troopCategories: Set<SkillTroopCategory> = emptySet(),
    val country: Int = 0,
)

interface SkillBattleView {
    fun heroes(): List<BattleHeroRef>

    fun state(ref: BattleHeroRef): SkillBattleHeroState?

    fun metadata(ref: BattleHeroRef): SkillBattleHeroMetadata?

    fun accumulatedDamageDealt(ref: BattleHeroRef): Int

    fun currentMorale(ref: BattleHeroRef): Int? = state(ref)?.morale

    fun currentAttackRange(ref: BattleHeroRef): Int? = state(ref)?.attackRange

    fun linkedTarget(source: BattleHeroRef): BattleHeroRef?

    fun currentTarget(source: BattleHeroRef): BattleHeroRef?

    fun previousTarget(source: BattleHeroRef): BattleHeroRef?

    companion object {
        fun entrySnapshot(request: BattleRequest): SkillBattleView =
            EntrySnapshotSkillBattleView(request)
    }
}

private class EntrySnapshotSkillBattleView(
    request: BattleRequest,
) : SkillBattleView {
    private val states = buildMap {
        request.attacker.heroes.forEach { hero ->
            put(
                BattleHeroRef(Side.ATTACKER, hero.position, hero.id),
                hero.toSkillState(),
            )
        }
        request.defender.heroes.forEach { hero ->
            put(
                BattleHeroRef(Side.DEFENDER, hero.position, hero.id),
                hero.toSkillState(),
            )
        }
    }

    override fun heroes(): List<BattleHeroRef> = states.keys.toList()

    override fun state(ref: BattleHeroRef): SkillBattleHeroState? = states[ref]

    override fun metadata(ref: BattleHeroRef): SkillBattleHeroMetadata? = null

    override fun accumulatedDamageDealt(ref: BattleHeroRef): Int = 0

    override fun linkedTarget(source: BattleHeroRef): BattleHeroRef? = null

    override fun currentTarget(source: BattleHeroRef): BattleHeroRef? = null

    override fun previousTarget(source: BattleHeroRef): BattleHeroRef? = null
}

private fun com.stzb.server.game.battle.BattleHero.toSkillState() =
    SkillBattleHeroState(
        stats = stats,
        troops = troops,
        maxTroops = maxTroops,
        statuses = activeStatuses,
        morale = morale,
        attackRange = stats.hitRange,
    )

enum class BattleTrigger {
    BATTLE_PASSIVE,
    BATTLE_COMMAND,
    ROUND_START,
    ACTION_BEFORE,
    ACTIVE_SKILL_ATTEMPT,
    NORMAL_ATTACK_BEFORE,
    NORMAL_ATTACK_AFTER,
    DAMAGE_BEFORE,
    DAMAGE_AFTER,
    HURT_AFTER,
    PURSUIT_ATTEMPT,
    ACTION_AFTER,
    ROUND_END,
    BASE_HERO_DEFEATED,
}

data class SkillBattleContext(
    val request: BattleRequest,
    val runtime: SkillRuntimeState,
    val random: BattleRandom,
    val round: Int,
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val currentSkillId: Int,
    val trigger: BattleTrigger,
    val battleView: SkillBattleView = SkillBattleView.entrySnapshot(request),
)

data class PreparedSkill(
    val source: BattleHeroRef,
    val skillId: Int,
    val rootSkillId: Int = skillId,
    val trigger: BattleTrigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
    val startedRound: Int = 0,
    val readyRound: Int,
)

data class DelayedEffect(
    val source: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val detailId: Int,
    val dueRound: Int,
    val dueHit: Int = 0,
    val sequence: Long = UNSCHEDULED_SEQUENCE,
) {
    companion object {
        const val UNSCHEDULED_SEQUENCE: Long = -1
    }
}
