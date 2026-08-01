package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserHeadIconResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `one user id returns one exact native default tuple`() {
        val response = response("[10001]")

        assertEquals(2, response.size())
        assertTrue(response[0].isInt)
        assertEquals(10001, response[0].intValue())
        assertDefaultTuple(response[1])
    }

    @Test
    fun `multiple user ids retain request order and every tuple is exact`() {
        val response = response("[30,-2,10]")

        assertEquals(listOf(30, -2, 10), response.userIds())
        assertEquals(6, response.size())
        response.defaultTuples().forEach(::assertDefaultTuple)
    }

    @Test
    fun `duplicate user ids are retained in request order`() {
        val response = response("[7,7,3,7]")

        assertEquals(listOf(7, 7, 3, 7), response.userIds())
        assertEquals(8, response.size())
        response.defaultTuples().forEach(::assertDefaultTuple)
    }

    @Test
    fun `empty malformed and non array input return an empty array`() {
        val invalidRequests = listOf<String?>(
            null,
            "",
            "not-json",
            "null",
            "0",
            "{}",
            "[]",
        )

        invalidRequests.forEach { request ->
            val response = response(request)
            assertTrue(response.isArray, "request=$request")
            assertEquals(0, response.size(), "request=$request")
        }
    }

    @Test
    fun `invalid entries are ignored without reordering valid ids`() {
        val response = response(
            """[5,null,"6",6.0,{},[],true,2147483648,-2147483649,-2147483648,2147483647,5]""",
        )

        assertEquals(
            listOf(5, Int.MIN_VALUE, Int.MAX_VALUE, 5),
            response.userIds(),
        )
        assertEquals(8, response.size())
        response.defaultTuples().forEach(::assertDefaultTuple)
    }

    private fun response(requestBody: String?): JsonNode =
        mapper.readTree(UserHeadIconResponses.response(requestBody))

    private fun JsonNode.userIds(): List<Int> =
        filterIndexed { index, _ -> index % 2 == 0 }.map(JsonNode::intValue)

    private fun JsonNode.defaultTuples(): List<JsonNode> =
        filterIndexed { index, _ -> index % 2 == 1 }

    private fun assertDefaultTuple(tuple: JsonNode) {
        assertTrue(tuple.isArray)
        assertEquals(4, tuple.size())
        assertTrue(tuple[0].isInt)
        assertEquals(301, tuple[0].intValue())
        assertTrue(tuple[1].isTextual)
        assertEquals("0,0", tuple[1].textValue())
        assertTrue(tuple[2].isInt)
        assertEquals(0, tuple[2].intValue())
        assertTrue(tuple[3].isTextual)
        assertEquals("", tuple[3].textValue())
    }
}
