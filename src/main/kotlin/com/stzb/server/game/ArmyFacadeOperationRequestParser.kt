package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ArmyFacadeBindRequest(
    val facadeId: Int,
    val heroUids: List<Int>,
)

data class ArmyFacadeUseRequest(
    val facadeId: Int,
    val heroUid: Int,
)

data class SpecialArmyFacadeStateRequest(
    val specialCardUid: Int,
    val state: Int,
)

object ArmyFacadeOperationRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parseBatch(body: String): ArmyFacadeBindRequest? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 && it[1].isArray }
            ?.let { root ->
                val facadeId = root[0].asInt()
                val heroUids = root[1].map { it.asInt() }
                ArmyFacadeBindRequest(facadeId, heroUids)
                    .takeIf { facadeId > 0 && heroUids.isNotEmpty() && heroUids.all { it > 0 } }
            }

    fun parseSingle(body: String): ArmyFacadeBindRequest? =
        parsePair(body, allowDefaultFacade = false)?.let { (facadeId, heroUid) ->
            ArmyFacadeBindRequest(facadeId, listOf(heroUid))
        }

    fun parseUse(body: String): ArmyFacadeUseRequest? =
        parsePair(body, allowDefaultFacade = true)?.let { (facadeId, heroUid) ->
            ArmyFacadeUseRequest(facadeId, heroUid)
        }

    fun parseSpecialState(body: String): SpecialArmyFacadeStateRequest? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 }
            ?.let { root ->
                SpecialArmyFacadeStateRequest(root[0].asInt(), root[1].asInt())
                    .takeIf { it.specialCardUid > 0 && it.state in setOf(0, 2) }
            }

    private fun parsePair(body: String, allowDefaultFacade: Boolean): Pair<Int, Int>? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 }
            ?.let { root -> root[0].asInt() to root[1].asInt() }
            ?.takeIf { (facadeId, heroUid) ->
                heroUid > 0 && if (allowDefaultFacade) facadeId >= 0 else facadeId > 0
            }
}
