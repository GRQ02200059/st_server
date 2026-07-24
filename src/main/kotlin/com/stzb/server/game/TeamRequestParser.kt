package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.protocol.Cmd

object TeamRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parseSavedTeam(cmdId: Int, bodyText: String): List<Int> {
        val body = runCatching { mapper.readTree(bodyText) }.getOrNull() ?: return emptyList()
        if (!body.isArray) return emptyList()

        if (cmdId == Cmd.WORLD_BOSS_SAVE_TEAM) {
            return body.take(3).map { it.asInt() }
        }

        val firstTeam = body.firstOrNull { it.isArray && it.size() >= 4 }
        return firstTeam?.let { listOf(it[1].asInt(), it[2].asInt(), it[3].asInt()) } ?: emptyList()
    }
}
