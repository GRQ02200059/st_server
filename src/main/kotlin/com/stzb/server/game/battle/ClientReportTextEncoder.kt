package com.stzb.server.game.battle

object ClientReportTextEncoder {
    fun encode(result: BattleResult): String =
        ClientBattleTextReplayAdapter.adapt(result).joinToString("#") { it.encode() }
}
