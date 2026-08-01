package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class CityFacadeApplyRequest(
    val cityWid: Int,
    val customView: String,
)

object CityFacadeOperationRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parseApplyScheme(body: String): CityFacadeApplyRequest? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf {
                it.isArray &&
                    it.size() == 4 &&
                    it[0].asInt() > 0 &&
                    it[1].isTextual &&
                    it[2].asInt() == 0 &&
                    it[3].isTextual &&
                    it[3].asText().isEmpty()
            }
            ?.let { CityFacadeApplyRequest(it[0].asInt(), it[1].asText()) }
}
