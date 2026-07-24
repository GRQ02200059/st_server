package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.protocol.Cmd

data class ConscriptAllocation(
    val heroUid: Int,
    val count: Int,
)

data class ConscriptRequest(
    val type: Int,
    val allocations: List<ConscriptAllocation>,
)

object ConscriptRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parse(cmdId: Int, bodyText: String): ConscriptRequest? {
        val body = runCatching { mapper.readTree(bodyText) }.getOrNull() ?: return null
        if (!body.isArray || body.size() < 2) return null

        val type: Int
        val allocationsNode: JsonNode
        when (cmdId) {
            Cmd.CONSCRIPT -> {
                type = body[0].asInt()
                allocationsNode = body[1]
            }

            Cmd.CONSCRIPT_IMMEDIATELY -> {
                allocationsNode = body[0]
                type = body[1].asInt()
            }

            else -> return null
        }

        val allocations = allocationsNode.takeIf { it.isArray }
            ?.mapNotNull { item ->
                if (!item.isArray || item.size() < 2) return@mapNotNull null
                val heroUid = item[0].asInt()
                val count = item[1].asInt()
                ConscriptAllocation(heroUid, count).takeIf { heroUid > 0 && count > 0 }
            }
            .orEmpty()
        if (allocations.isEmpty()) return null
        return ConscriptRequest(type = type, allocations = allocations)
    }
}
