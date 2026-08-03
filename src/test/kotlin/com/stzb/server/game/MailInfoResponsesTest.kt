package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class MailInfoResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `valid mail request echoes mail id and zero source in exact tuple`() {
        assertEquals(
            mapper.readTree("""[677829,"","","","","",0,0,0,"","","","",0,0,""]"""),
            response("[677829,1,0]"),
        )
    }

    @Test
    fun `nonzero source is echoed`() {
        val response = response("[677829,1,42]")

        assertEquals(16, response.size())
        assertEquals(677829, response[0].intValue())
        assertEquals(42, response[6].intValue())
    }

    @Test
    fun `invalid identity slots default independently while preserving exact tuple`() {
        val cases = listOf(
            Triple<String?, Int, Int>(null, 0, 0),
            Triple("", 0, 0),
            Triple("not-json", 0, 0),
            Triple("0", 0, 0),
            Triple("{}", 0, 0),
            Triple("[]", 0, 0),
            Triple("[677829]", 677829, 0),
            Triple("""["677829",1,9]""", 0, 9),
            Triple("[1.5,1,9]", 0, 9),
            Triple("[true,1,9]", 0, 9),
            Triple("[2147483648,1,9]", 0, 9),
            Triple("[-2147483649,1,9]", 0, 9),
            Triple("""[677829,1,"9"]""", 677829, 0),
            Triple("[677829,1,false]", 677829, 0),
            Triple("[677829,1,1.5]", 677829, 0),
            Triple("[677829,1,2147483648]", 677829, 0),
            Triple("[677829,1,-2147483649]", 677829, 0),
        )

        cases.forEach { (request, expectedMailId, expectedSource) ->
            assertExactTuple(
                response = response(request),
                mailId = expectedMailId,
                source = expectedSource,
                message = "request=$request",
            )
        }
    }

    @Test
    fun `trailing tokens invalidate request while preserving exact tuple`() {
        listOf("[677829,1,9] trailing", "[677829,1,9] []").forEach { request ->
            assertExactTuple(
                response = response(request),
                mailId = 0,
                source = 0,
                message = "request=$request",
            )
        }
    }

    private fun response(requestBody: String?): JsonNode =
        mapper.readTree(MailInfoResponses.response(requestBody))

    private fun assertExactTuple(response: JsonNode, mailId: Int, source: Int, message: String) {
        assertEquals(
            mapper.readTree("""[$mailId,"","","","","",$source,0,0,"","","","",0,0,""]"""),
            response,
            message,
        )
    }
}
