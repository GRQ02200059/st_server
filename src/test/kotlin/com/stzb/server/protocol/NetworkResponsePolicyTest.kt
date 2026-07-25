package com.stzb.server.protocol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkResponsePolicyTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `known ui command falls back to empty array`() {
        assertEquals("[]", NetworkResponsePolicy.fallbackBody(959))
    }

    @Test
    fun `unknown business command falls back to empty array`() {
        assertEquals("[]", NetworkResponsePolicy.fallbackBody(45678))
    }

    @Test
    fun `reinforce stay checks return dictionaries required by conquest army ui`() {
        assertEquals("{}", NetworkResponsePolicy.fallbackBody(6219))
        assertEquals("{}", NetworkResponsePolicy.fallbackBody(6239))
    }

    @Test
    fun `friend mail user lookup returns user data tuple`() {
        val response = mapper.readTree(NetworkResponsePolicy.fallbackBody(212))
        assertEquals(1, response[0].asInt())
        assertEquals(10001, response[1][0].asInt())
        assertEquals("role_10001", response[1][1].asText())
        assertEquals("主公", response[1][2].asText())
    }

    @Test
    fun `union info returns non success tuple to avoid complex union parser`() {
        assertEquals("[1,[]]", NetworkResponsePolicy.fallbackBody(100))
    }

    @Test
    fun `paged list command returns list tuple`() {
        val response = mapper.readTree(NetworkResponsePolicy.fallbackBody(91))
        assertEquals(0, response[0].asInt())
        assertEquals(0, response[1].size())
        assertEquals(0, response[2].asInt())
    }

    @Test
    fun `gm command is still treated as business fallback`() {
        assertEquals("[]", NetworkResponsePolicy.fallbackBody(98765))
    }

    @Test
    fun `battle report commands require precise handlers`() {
        assertNull(NetworkResponsePolicy.fallbackBody(Cmd.BATTLE_REPORT_PROFILE))
        assertNull(NetworkResponsePolicy.fallbackBody(Cmd.BATTLE_REPORT_DETAIL))
        assertNull(NetworkResponsePolicy.fallbackBody(Cmd.BATTLE_REPORT_SHORT_DETAIL))
    }

    @Test
    fun `unknown system command is not auto answered`() {
        assertNull(NetworkResponsePolicy.fallbackBody(96666))
    }
}
