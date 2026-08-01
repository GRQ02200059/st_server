package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileResponsesTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `homepage profile exposes the player union`() {
        val union = PlayerUnion(
            unionId = 1001,
            name = "洛阳同盟",
            leaderUserId = 10_001,
            leaderRoleName = "盟主",
            createdAtSec = 1_700_000_000,
            memberUserIds = setOf(10_001),
        )

        val response = mapper.readTree(
            ProfileResponses.homepageInfo(
                userId = union.leaderUserId,
                roleName = union.leaderRoleName,
                playerUnion = union,
            ),
        )

        assertEquals(union.unionId, response[1]["union"][2].asInt())
        assertEquals(union.name, response[1]["union"][3].asText())
    }
}
