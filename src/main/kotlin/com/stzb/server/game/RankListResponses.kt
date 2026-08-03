package com.stzb.server.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object RankListResponses {
    private val mapper = jacksonObjectMapper()

    fun response(
        requestBody: String?,
        userId: Int,
        world: WorldProjection,
        unions: List<PlayerUnion>,
    ): String {
        val request = runCatching { mapper.readTree(requestBody ?: "") }.getOrNull()
        val rankType = request.integralSlot(2) ?: 0
        val response = when (rankType) {
            0 -> userRanking(userId, world)
            1 -> unionRanking(userId, unions)
            31 -> response(
                rankType,
                selfRank = -1,
                selfData = mapper.createObjectNode().apply {
                    put("userid", 0)
                    put("user_id", 0)
                    put("name", "")
                    put("score", 0)
                    put("show_data", """["0,0","0,0",""]""")
                },
            )
            44, 46 -> response(rankType)
            47 -> response(rankType, extra = request.integralSlot(3) ?: 0)
            51 -> response(rankType, selfData = worldBossSelfData(userId, world))
            27 -> response(rankType, selfRank = 0, selfData = mapper.createArrayNode())
            7, 9 -> response(rankType, extra = -1)
            50 -> localWuxunRanking(userId, world, unions)
            else -> response(rankType)
        }
        return mapper.writeValueAsString(response)
    }

    private fun userRanking(userId: Int, world: WorldProjection): ArrayNode {
        val landCounts = world.lands.groupingBy(LandClaim::userId).eachCount()
        val ranked = world.cities
            .distinctBy(WorldCity::userId)
            .sortedWith(
                compareByDescending<WorldCity> { landCounts[it.userId] ?: 0 }
                    .thenBy(WorldCity::userId),
            )
            .map { city -> userData(city, landCounts[city.userId] ?: 0) }
        val selfRank = ranked.indexOfFirst { data -> data["user_id"].asInt() == userId }
        return rankedResponse(
            rankType = 0,
            selfRank = selfRank,
            selfData = ranked.getOrNull(selfRank),
            rows = ranked,
            trailingValue = null,
            appendSeventhSlot = true,
        )
    }

    private fun unionRanking(userId: Int, unions: List<PlayerUnion>): ArrayNode {
        val ranked = unions
            .sortedWith(
                compareByDescending<PlayerUnion> { it.memberUserIds.size }
                    .thenBy(PlayerUnion::unionId),
            )
            .map(::unionData)
        val selfUnionId = unions
            .filter { union -> userId in union.memberUserIds }
            .minOfOrNull(PlayerUnion::unionId)
        val selfRank = ranked.indexOfFirst { data -> data["union_id"].asInt() == selfUnionId }
        return rankedResponse(
            rankType = 1,
            selfRank = selfRank,
            selfData = ranked.getOrNull(selfRank),
            rows = ranked,
            trailingValue = mapper.createObjectNode(),
        )
    }

    private fun userData(city: WorldCity, landCount: Int): ObjectNode =
        mapper.createObjectNode().apply {
            put("area", 0)
            put("branch_city_count", 0)
            put("force", 0)
            put("fort_count", 0)
            put("land_count", landCount)
            put("name", city.roleName)
            put("power", 1000)
            put("refresh_time", 0)
            put("region", 0)
            put("role_id", "role_${city.userId}")
            put("shu_cheng_count", 0)
            put("user_id", city.userId)
            put("show_info", "")
        }

    private fun unionData(union: PlayerUnion): ObjectNode =
        mapper.createObjectNode().apply {
            put("area", 0)
            put("force", 0)
            put("joined_union_id", union.unionId)
            put("level", 0)
            put("name", union.name)
            put("occupy_city_value", 0)
            put("power", union.memberUserIds.size * 1000)
            put("refresh_time", 0)
            put("region", 0)
            put("total_member", union.memberUserIds.size)
            put("total_npc_city", 0)
            put("union_id", union.unionId)
        }

    private fun worldBossSelfData(userId: Int, world: WorldProjection): ObjectNode {
        val roleName = world.cities.firstOrNull { city -> city.userId == userId }?.roleName.orEmpty()
        return mapper.createObjectNode().apply {
            put("defend_strength", 0)
            put("kill_count", 0)
            put("lose_count", 0)
            put("refresh_time", 0)
            put("user_id", userId)
            put("user_info", mapper.writeValueAsString(listOf(roleName, "0", "0,0")))
        }
    }

    private fun localWuxunRanking(
        userId: Int,
        world: WorldProjection,
        unions: List<PlayerUnion>,
    ): ArrayNode {
        val roleName = world.cities.firstOrNull { city -> city.userId == userId }?.roleName.orEmpty()
        val unionId = unions
            .filter { union -> userId in union.memberUserIds }
            .minOfOrNull(PlayerUnion::unionId)
            ?: 0
        val selfData = mapper.createObjectNode().apply {
            put("user_id", userId)
            put("userid", userId)
            put("name", roleName)
            put("role_id", "role_$userId")
            put("wuxun", 0)
            put("union_id", unionId)
        }
        return rankedResponse(
            rankType = 50,
            selfRank = 0,
            selfData = selfData,
            rows = listOf(selfData),
            trailingValue = null,
        )
    }

    private fun rankedResponse(
        rankType: Int,
        selfRank: Int,
        selfData: JsonNode?,
        rows: List<JsonNode>,
        trailingValue: JsonNode?,
        appendSeventhSlot: Boolean = false,
    ): ArrayNode =
        mapper.createArrayNode().apply {
            add(rankType)
            add(selfRank)
            addNodeOrNull(selfData)
            add(rows.size)
            add(
                mapper.createArrayNode().apply {
                    rows.forEachIndexed { rank, data ->
                        add(mapper.createArrayNode().add(rank).add(data))
                    }
                },
            )
            addNodeOrNull(trailingValue)
            if (appendSeventhSlot) addNull()
        }

    private fun response(
        rankType: Int,
        selfRank: Int = -1,
        selfData: JsonNode? = null,
        extra: Int? = null,
    ): ArrayNode =
        mapper.createArrayNode().apply {
            add(rankType)
            add(selfRank)
            addNodeOrNull(selfData)
            add(0)
            add(mapper.createArrayNode())
            if (extra == null) addNull() else add(extra)
        }

    private fun ArrayNode.addNodeOrNull(value: JsonNode?) {
        if (value == null) addNull() else add(value)
    }

    private fun JsonNode?.integralSlot(index: Int): Int? =
        this
            ?.takeIf { it.isArray && it.size() > index }
            ?.get(index)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
}
