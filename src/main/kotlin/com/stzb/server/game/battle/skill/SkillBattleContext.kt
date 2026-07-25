package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleRequest

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
