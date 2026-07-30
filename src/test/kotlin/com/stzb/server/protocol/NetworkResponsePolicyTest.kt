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
    fun `recorded acknowledgement commands return booleans instead of arrays`() {
        listOf(191, 748, 888, 2311).forEach { cmdId ->
            assertEquals("true", NetworkResponsePolicy.fallbackBody(cmdId), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded fire and forget commands still receive json null`() {
        listOf(6, 875, 885, 2405, 3400, 5025, 6037, 6351, 7041).forEach { cmdId ->
            assertEquals("null", NetworkResponsePolicy.fallbackBody(cmdId), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded scalar and tuple commands keep their wire shapes`() {
        assertEquals("200", NetworkResponsePolicy.fallbackBody(5091))
        assertEquals("[1001]", NetworkResponsePolicy.fallbackBody(3877))
        assertEquals("[false,[]]", NetworkResponsePolicy.fallbackBody(4968))
        assertEquals("[[],0]", NetworkResponsePolicy.fallbackBody(6092))
    }

    @Test
    fun `recorded dictionary commands return objects instead of arrays`() {
        listOf(510, 6053, 6068, 6219, 6239).forEach { cmdId ->
            assertEquals("{}", NetworkResponsePolicy.fallbackBody(cmdId), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded role lookup command returns a safe local tuple`() {
        val response = mapper.readTree(
            NetworkResponsePolicy.fallbackBody(5013, """[3,"remote-role-id"]"""),
        )

        assertEquals(3, response[0].asInt())
        assertEquals("remote-role-id", response[1].asText())
        assertEquals(0, response[2].size())
        assertEquals(10001, response[4].asInt())
        assertEquals("主公", response[7].asText())
    }

    @Test
    fun `recorded fixed tuple queries keep minimum client readable arity`() {
        val expectedSizes = mapOf(
            135 to 5,
            172 to 2,
            700 to 6,
            725 to 4,
            1436 to 3,
            2529 to 3,
            3686 to 2,
            3787 to 2,
            4979 to 3,
        )

        expectedSizes.forEach { (cmdId, size) ->
            val response = mapper.readTree(NetworkResponsePolicy.fallbackBody(cmdId))
            assertEquals(size, response.size(), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded map in tuple queries keep dictionary slot`() {
        listOf(261, 262).forEach { cmdId ->
            val response = mapper.readTree(NetworkResponsePolicy.fallbackBody(cmdId))
            assertEquals(1, response.size(), "cmd=$cmdId")
            assertEquals(true, response[0].isObject, "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded name lookup echoes requested name with empty result lists`() {
        val response = mapper.readTree(
            NetworkResponsePolicy.fallbackBody(4979, """["查找目标"]"""),
        )

        assertEquals("查找目标", response[0].asText())
        assertEquals(0, response[1].size())
        assertEquals(0, response[2].size())
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
