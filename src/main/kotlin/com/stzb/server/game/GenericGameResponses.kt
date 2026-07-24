package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object GenericGameResponses {
    private val mapper = jacksonObjectMapper()

    fun emptyArray(): String = "[]"

    fun successZero(): String = "0"

    fun successOne(): String = "1"

    fun unionInfoUnavailable(): String =
        "[1,[]]"

    fun userLookup(userId: Int = 10001, roleName: String = "主公"): String =
        mapper.writeValueAsString(listOf(1, listOf(userId, "role_$userId", roleName, "0", "")))

    fun emptyPagedList(): String =
        mapper.writeValueAsString(listOf(0, emptyList<Any>(), 0))

    fun emptyObjectResult(): String =
        mapper.writeValueAsString(mapOf("res" to emptyList<Any>()))
}
