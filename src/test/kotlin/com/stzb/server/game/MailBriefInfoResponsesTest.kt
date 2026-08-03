package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class MailBriefInfoResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `captured inbox request echoes source and mail id in exact 22 slot record`() {
        val response = response("[677829,0]")

        assertEquals(2, response.size())
        assertEquals(0, response[0].intValue())
        assertEquals(22, response[1].size())
        assertEquals(677829, response[1][4].intValue())
        assertEquals(677829, response[1][14].intValue())
        assertEquals(
            mapper.readTree(
                """[0,["","",0,1,677829,0,0,1,0,0,0,0,0,0,677829,0,"",0,"",0,0,0]]""",
            ),
            response,
        )
    }

    @Test
    fun `nonzero source returns exact 17 slot outbox record and echoes mail id`() {
        val response = response("[677829,7]")

        assertEquals(2, response.size())
        assertEquals(7, response[0].intValue())
        assertEquals(17, response[1].size())
        assertEquals(677829, response[1][0].intValue())
        assertEquals(677829, response[1][11].intValue())
        assertEquals(
            mapper.readTree(
                """[7,[677829,"",0,"",0,3,0,0,0,0,0,677829,0,"",0,"",0]]""",
            ),
            response,
        )
    }

    @Test
    fun `invalid identity values default independently and trailing tokens invalidate the request`() {
        val cases = listOf(
            Triple<String?, Int, Int>(null, 0, 0),
            Triple("", 0, 0),
            Triple("not-json", 0, 0),
            Triple("0", 0, 0),
            Triple("{}", 0, 0),
            Triple("[]", 0, 0),
            Triple("[677829]", 677829, 0),
            Triple("""["677829",7]""", 0, 7),
            Triple("[1.5,7]", 0, 7),
            Triple("[true,7]", 0, 7),
            Triple("[2147483648,7]", 0, 7),
            Triple("[-2147483649,7]", 0, 7),
            Triple("""[677829,"7"]""", 677829, 0),
            Triple("[677829,false]", 677829, 0),
            Triple("[677829,1.5]", 677829, 0),
            Triple("[677829,2147483648]", 677829, 0),
            Triple("[677829,-2147483649]", 677829, 0),
            Triple("[677829,7] trailing", 0, 0),
            Triple("[677829,7] []", 0, 0),
        )

        cases.forEach { (request, expectedMailId, expectedSource) ->
            assertEquals(
                expectedResponse(expectedMailId, expectedSource),
                response(request),
                "request=$request",
            )
        }
    }

    private fun response(requestBody: String?): JsonNode =
        mapper.readTree(MailBriefInfoResponses.response(requestBody))

    private fun expectedResponse(mailId: Int, source: Int): JsonNode =
        if (source == 0) {
            mapper.readTree(
                """[$source,["","",0,1,$mailId,0,0,1,0,0,0,0,0,0,$mailId,0,"",0,"",0,0,0]]""",
            )
        } else {
            mapper.readTree(
                """[$source,[$mailId,"",0,"",0,3,0,0,0,0,0,$mailId,0,"",0,"",0]]""",
            )
        }
}
