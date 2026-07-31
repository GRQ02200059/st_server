package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class GearOperationRequest(
    val heroUid: Int,
    val gearUid: Int,
)

object GearOperationRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parse(body: String): GearOperationRequest? =
        runCatching { mapper.readTree(body) }
            .getOrNull()
            ?.takeIf { it.isArray && it.size() >= 2 }
            ?.let { root ->
                GearOperationRequest(
                    heroUid = root[0].asInt(),
                    gearUid = root[1].asInt(),
                ).takeIf { request ->
                    request.heroUid > 0 && request.gearUid > 0
                }
            }
}
