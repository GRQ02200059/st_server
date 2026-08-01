package com.stzb.server.game

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object UserHeadIconResponses {
    private val mapper = jacksonObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun response(requestBody: String?): String {
        val request = runCatching { mapper.readTree(requestBody ?: "") }
            .getOrNull()
            ?.takeIf(JsonNode::isArray)
        val response = mapper.createArrayNode()
        request?.forEach { entry ->
            if (entry.isIntegralNumber && entry.canConvertToInt()) {
                response.add(entry.intValue())
                response.add(defaultTuple())
            }
        }
        return mapper.writeValueAsString(response)
    }

    private fun defaultTuple(): ArrayNode =
        mapper.createArrayNode()
            .add(301)
            .add("0,0")
            .add(0)
            .add("")
}
