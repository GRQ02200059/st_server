package com.stzb.server.game

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object MailInfoResponses {
    private val mapper = jacksonObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun response(requestBody: String?): String {
        val request = runCatching { mapper.readTree(requestBody ?: "") }
            .getOrNull()
            ?.takeIf(JsonNode::isArray)
        val mailId = request.integralIntAt(0)
        val source = request.integralIntAt(2)
        return mapper.writeValueAsString(
            listOf(mailId, "", "", "", "", "", source, 0, 0, "", "", "", "", 0, 0, ""),
        )
    }

    private fun JsonNode?.integralIntAt(index: Int): Int =
        this
            ?.takeIf { it.size() > index }
            ?.get(index)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.intValue()
            ?: 0
}
