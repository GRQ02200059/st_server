package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ArmyBattleRequest(
    val targetWid: Int,
    val armyId: Int,
)

object ArmyBattleRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parse(bodyText: String): ArmyBattleRequest? {
        val body = runCatching { mapper.readTree(bodyText) }.getOrNull() ?: return null
        if (!body.isArray || body.size() < 2) return null
        val targetWid = body[0].asInt()
        val armyId = body[1].asInt()
        if (targetWid <= 0 || armyId <= 0) return null
        return ArmyBattleRequest(targetWid = targetWid, armyId = armyId)
    }
}
