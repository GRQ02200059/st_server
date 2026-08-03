package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ResideRequest(
    val wid: Int,
    val armyId: Int,
)

/** Parses cmd 60 body [wid, armyId, needPortWid, techID, isJianJun, useSpeedup]. */
object ResideRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parse(bodyText: String): ResideRequest? {
        val body = runCatching { mapper.readTree(bodyText) }.getOrNull() ?: return null
        if (!body.isArray || body.size() < 2) return null
        val wid = body[0].asInt()
        val armyId = body[1].asInt()
        if (wid <= 0 || armyId <= 0) return null
        return ResideRequest(wid = wid, armyId = armyId)
    }
}
