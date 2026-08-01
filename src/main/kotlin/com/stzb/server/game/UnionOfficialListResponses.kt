package com.stzb.server.game

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object UnionOfficialListResponses {
    private val mapper = jacksonObjectMapper()

    fun response(union: PlayerUnion?): String {
        if (union == null) return "[]"

        val rows = mapper.createArrayNode()
        rows.add(
            row(
                positionType = 1,
                positionNum = 0,
                userId = union.leaderUserId,
                userName = union.leaderRoleName,
                headIconId = 301,
            ),
        )
        rows.add(row(positionType = 2, positionNum = 0))
        repeat(10) { positionNum -> rows.add(row(positionType = 3, positionNum = positionNum)) }
        repeat(3) { positionNum -> rows.add(row(positionType = 4, positionNum = positionNum)) }
        repeat(2) { positionNum -> rows.add(row(positionType = 5, positionNum = positionNum)) }
        return mapper.writeValueAsString(rows)
    }

    private fun row(
        positionType: Int,
        positionNum: Int,
        userId: Int = 0,
        userName: String = "",
        headIconId: Int = 0,
    ): ArrayNode =
        mapper.createArrayNode()
            .add(positionType)
            .add(positionNum)
            .add(userId)
            .add(userName)
            .add(headIconId)
            .add(0)
            .add(0)
            .add(0)
}
