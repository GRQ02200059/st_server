package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankListResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `type zero ranks local users by land count with exact seven slot shape`() {
        val response = response("[0,3,0]", userId = 30)

        assertEquals(7, response.size())
        assertEquals(0, response[0].asInt())
        assertEquals(1, response[1].asInt())
        assertEquals(response[4][1][1], response[2])
        assertEquals(3, response[3].asInt())
        assertEquals(listOf(10, 30, 20), response[4].map { it[1]["user_id"].asInt() })
        assertEquals(listOf(0, 1, 2), response[4].map { it[0].asInt() })
        assertTrue(response[5].isNull)
        assertTrue(response[6].isNull)

        val expectedKeys = setOf(
            "area",
            "branch_city_count",
            "force",
            "fort_count",
            "land_count",
            "name",
            "power",
            "refresh_time",
            "region",
            "role_id",
            "shu_cheng_count",
            "user_id",
            "show_info",
        )
        response[4].forEach { row ->
            assertEquals(expectedKeys, row[1].fieldNames().asSequence().toSet())
        }
        assertEquals("三十", response[2]["name"].asText())
        assertEquals("role_30", response[2]["role_id"].asText())
        assertEquals(2, response[2]["land_count"].asInt())
        assertEquals(1000, response[2]["power"].asInt())
        assertEquals("", response[2]["show_info"].asText())
        listOf(
            "area",
            "branch_city_count",
            "force",
            "fort_count",
            "refresh_time",
            "region",
            "shu_cheng_count",
        ).forEach { key -> assertEquals(0, response[2][key].asInt(), key) }
    }

    @Test
    fun `type one ranks local unions by member count with exact six slot shape`() {
        val response = response("[0,3,1]", userId = 22)

        assertEquals(6, response.size())
        assertEquals(1, response[0].asInt())
        assertEquals(1, response[1].asInt())
        assertEquals(response[4][1][1], response[2])
        assertEquals(3, response[3].asInt())
        assertEquals(listOf(1002, 1003, 1001), response[4].map { it[1]["union_id"].asInt() })
        assertEquals(listOf(0, 1, 2), response[4].map { it[0].asInt() })
        assertTrue(response[5].isObject && response[5].isEmpty)

        val expectedKeys = setOf(
            "area",
            "force",
            "joined_union_id",
            "level",
            "name",
            "occupy_city_value",
            "power",
            "refresh_time",
            "region",
            "total_member",
            "total_npc_city",
            "union_id",
        )
        response[4].forEach { row ->
            assertEquals(expectedKeys, row[1].fieldNames().asSequence().toSet())
        }
        assertEquals("丙盟", response[2]["name"].asText())
        assertEquals(1003, response[2]["joined_union_id"].asInt())
        assertEquals(4, response[2]["total_member"].asInt())
        assertEquals(4000, response[2]["power"].asInt())
        listOf(
            "area",
            "force",
            "level",
            "occupy_city_value",
            "refresh_time",
            "region",
            "total_npc_city",
        ).forEach { key -> assertEquals(0, response[2][key].asInt(), key) }
    }

    @Test
    fun `invalid request rank type falls back to type zero`() {
        val invalidRequests = listOf<String?>(
            null,
            "",
            "not-json",
            "0",
            "{}",
            "[]",
            "[0,3]",
            "[0,3,null]",
            """[0,3,"51"]""",
            "[0,3,1.5]",
            "[0,3,2147483648]",
            "[0,3,-2147483649]",
        )

        invalidRequests.forEach { request ->
            assertEquals(0, response(request, userId = 30)[0].asInt(), "request=$request")
        }
    }

    @Test
    fun `special rank types keep their exact client readable layouts`() {
        val expected = mapOf(
            "[0,3,31]" to
                """[31,-1,{"userid":0,"user_id":0,"name":"","score":0,"show_data":"[\"0,0\",\"0,0\",\"\"]"},0,[],null]""",
            "[0,3,44]" to "[44,-1,null,0,[],null]",
            "[0,3,46]" to "[46,-1,null,0,[],null]",
            "[0,3,47,100521]" to "[47,-1,null,0,[],100521]",
            "[0,3,51]" to
                """[51,-1,{"defend_strength":0,"kill_count":0,"lose_count":0,"refresh_time":0,"user_id":30,"user_info":"[\"三十\",\"0\",\"0,0\"]"},0,[],null]""",
            "[0,3,27]" to "[27,0,[],0,[],null]",
            "[0,3,7]" to "[7,-1,null,0,[],-1]",
            "[0,3,9]" to "[9,-1,null,0,[],-1]",
            "[0,3,50]" to
                """[50,0,{"user_id":30,"userid":30,"name":"三十","role_id":"role_30","wuxun":0,"union_id":1002},1,[[0,{"user_id":30,"userid":30,"name":"三十","role_id":"role_30","wuxun":0,"union_id":1002}]],null]""",
            "[0,3,123]" to "[123,-1,null,0,[],null]",
        )

        expected.forEach { (request, expectedJson) ->
            assertEquals(mapper.readTree(expectedJson), response(request, userId = 30), request)
        }
    }

    @Test
    fun `type forty seven echoes only an integral in range hero id`() {
        listOf(
            "[0,3,47]",
            "[0,3,47,null]",
            """[0,3,47,"100521"]""",
            "[0,3,47,1.5]",
            "[0,3,47,2147483648]",
        ).forEach { request ->
            assertEquals(0, response(request, userId = 30)[5].asInt(), request)
        }
    }

    @Test
    fun `type fifty one self data has the exact six key local shape`() {
        val selfData = response("[0,3,51]", userId = 30)[2]

        assertEquals(
            setOf(
                "defend_strength",
                "kill_count",
                "lose_count",
                "refresh_time",
                "user_id",
                "user_info",
            ),
            selfData.fieldNames().asSequence().toSet(),
        )
        assertEquals(30, selfData["user_id"].asInt())
        assertEquals("""["三十","0","0,0"]""", selfData["user_info"].asText())
    }

    private fun response(request: String?, userId: Int): JsonNode =
        mapper.readTree(
            RankListResponses.response(
                requestBody = request,
                userId = userId,
                world = world,
                unions = unions,
            ),
        )

    private val world = WorldProjection(
        cities = listOf(
            WorldCity(cityWid = 200, userId = 20, roleName = "二十"),
            WorldCity(cityWid = 100, userId = 10, roleName = "十"),
            WorldCity(cityWid = 300, userId = 30, roleName = "三十"),
        ),
        lands = listOf(
            LandClaim(wid = 1, userId = 20, belongCity = 200, claimedAtSec = 0),
            LandClaim(wid = 2, userId = 10, belongCity = 100, claimedAtSec = 0),
            LandClaim(wid = 3, userId = 10, belongCity = 100, claimedAtSec = 0),
            LandClaim(wid = 4, userId = 30, belongCity = 300, claimedAtSec = 0),
            LandClaim(wid = 5, userId = 30, belongCity = 300, claimedAtSec = 0),
        ),
    )

    private val unions = listOf(
        union(1003, "丙盟", 21, 22, 23, 24),
        union(1001, "甲盟", 11, 12),
        union(1002, "乙盟", 30, 31, 32, 33),
    )

    private fun union(unionId: Int, name: String, vararg members: Int): PlayerUnion =
        PlayerUnion(
            unionId = unionId,
            name = name,
            leaderUserId = members.first(),
            leaderRoleName = name,
            createdAtSec = 0,
            memberUserIds = members.toSet(),
        )
}
