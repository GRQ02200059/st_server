package com.stzb.server.game.battle

internal data class ClientReportAction(
    val id: Int,
    val params: List<Any> = emptyList(),
) {
    fun encode(): String =
        buildString {
            append(id.toString(36).padStart(2, '0'))
            if (params.isNotEmpty()) append(params.joinToString(","))
        }
}

internal object ClientBattleTextReplayProtocol {
    const val HERO_NAME = 14
    const val HERO_INFO = 205
    const val PREPARE = 4
    const val ROUND = 9
    const val HERO_ACTION_START = 10
    const val HERO_ACTION_END = 11
    const val SKILL_PREPARATION_STARTED = 23
    const val NORMAL_ATTACK = 119
    const val NORMAL_DAMAGE = 121
    const val SKILL_BEGIN = 213
    const val SKILL_END = 214
    const val NORMAL_ATTACK_BEGIN = 222
    const val NORMAL_ATTACK_END = 223
    const val SKILL_CAST = 301
    const val SKILL_DAMAGE = 60
    const val ONGOING_DAMAGE = 59
    const val RECOVERY = 63
    const val STATUS = 102
    const val END = 13
    const val FINAL_TROOPS = 224

    fun position(side: Side, formationPosition: Int): Int {
        require(formationPosition in 0..2) { "battle formation position must be 0..2: $formationPosition" }
        return when (side) {
            Side.ATTACKER -> formationPosition + 1
            Side.DEFENDER -> 6 - formationPosition
        }
    }

    fun position(ref: BattleHeroRef): Int = position(ref.side, ref.position)

    fun effectId(status: BattleStatus): Int = when (status) {
        BattleStatus.CONFUSION -> 501
        BattleStatus.HESITATION -> 502
        BattleStatus.DISARM -> 552
        BattleStatus.SHAKE -> 303
        BattleStatus.PANIC -> 304
        BattleStatus.BURN -> 305
        BattleStatus.HEX -> 306
        BattleStatus.INSIGHT -> 771
        BattleStatus.EVADE -> 515
        BattleStatus.ATTACK_BUFF -> 101
        BattleStatus.DEFENSE_BUFF -> 102
        BattleStatus.STRATEGY_BUFF -> 103
        BattleStatus.SPEED_BUFF -> 104
        BattleStatus.ATTACK_DEBUFF -> 151
        BattleStatus.DEFENSE_DEBUFF -> 152
        BattleStatus.STRATEGY_DEBUFF -> 153
        BattleStatus.SPEED_DEBUFF -> 154
    }

    fun effectId(stat: BattleStat, delta: Int): Int = when (stat) {
        BattleStat.ATTACK -> if (delta >= 0) 101 else 151
        BattleStat.DEFENSE -> if (delta >= 0) 102 else 152
        BattleStat.STRATEGY -> if (delta >= 0) 103 else 153
        BattleStat.SPEED -> if (delta >= 0) 104 else 154
        BattleStat.SIEGE, BattleStat.HIT_RANGE -> 0
    }
}
