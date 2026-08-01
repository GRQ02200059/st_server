package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnionOfficialListResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `local leader and vacant offices use the exact 17 row wire layout`() {
        val union = PlayerUnion(
            unionId = 1_005,
            name = "Local Union",
            leaderUserId = 42_424,
            leaderRoleName = "Local Leader",
            createdAtSec = 1_700_000_000,
            memberUserIds = setOf(42_424, 42_425),
        )

        val response = response(union)

        assertEquals(
            mapper.readTree(
                """
                [
                  [1,0,42424,"Local Leader",301,0,0,0],
                  [2,0,0,"",0,0,0,0],
                  [3,0,0,"",0,0,0,0],
                  [3,1,0,"",0,0,0,0],
                  [3,2,0,"",0,0,0,0],
                  [3,3,0,"",0,0,0,0],
                  [3,4,0,"",0,0,0,0],
                  [3,5,0,"",0,0,0,0],
                  [3,6,0,"",0,0,0,0],
                  [3,7,0,"",0,0,0,0],
                  [3,8,0,"",0,0,0,0],
                  [3,9,0,"",0,0,0,0],
                  [4,0,0,"",0,0,0,0],
                  [4,1,0,"",0,0,0,0],
                  [4,2,0,"",0,0,0,0],
                  [5,0,0,"",0,0,0,0],
                  [5,1,0,"",0,0,0,0]
                ]
                """.trimIndent(),
            ),
            response,
        )
        assertEquals(17, response.size())
        response.forEach { row ->
            assertTrue(row.isArray)
            assertEquals(8, row.size())
            listOf(0, 1, 2, 4, 5, 6, 7).forEach { slot ->
                assertTrue(row[slot].isIntegralNumber, "row=$row slot=$slot")
            }
            assertTrue(row[3].isTextual, "row=$row slot=3")
        }
        assertEquals(1, response.count { row -> row[2].intValue() == union.leaderUserId })
        assertEquals(1, response.count { row -> row[3].textValue() == union.leaderRoleName })
        assertTrue(response.drop(1).all { row -> row[2].intValue() == 0 && row[3].textValue().isEmpty() })
    }

    @Test
    fun `missing union returns an exact empty array`() {
        assertEquals("[]", UnionOfficialListResponses.response(null))
    }

    private fun response(union: PlayerUnion): JsonNode =
        mapper.readTree(UnionOfficialListResponses.response(union))
}
