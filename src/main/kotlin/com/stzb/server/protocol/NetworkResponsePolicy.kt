package com.stzb.server.protocol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.GenericGameResponses
import com.stzb.server.game.ProfileResponses

/**
 * 客户端大量 UI 请求没有服务端响应时会卡在等待态。这里集中管理协议兜底策略：
 * 已知业务协议优先由 GameServerHandler 精确处理；抓包已确认的命令必须保持其 JSON
 * 顶层类型和元组长度，未知普通业务 cmd 才回空数组。
 */
object NetworkResponsePolicy {
    private val mapper = jacksonObjectMapper()

    private val noOpArrayCommands = setOf(
        22, 92, 103, 111, Cmd.UNION_GET_GROUP_LIST, 171, 202, 203, 220, 509, 701, 711, 714, 727, 780, 871,
        959, 963, 974, 3758, 3846, 4080, 4331, 4967, 5043, 5044, 5045, 5049, 5082,
        6030, 6067, 6256, 9099,
    )

    fun observedShapeCommandIds(): Set<Int> =
        booleanCommands +
            jsonNullCommands +
            scalarNumberCommands.keys +
            stringCommands +
            dictionaryCommands +
            fixedTupleCommands.keys +
            pagedListCommands +
            objectResultCommands +
            noOpArrayCommands +
            setOf(
                100,
                102,
                3686,
                212,
                502,
                5013,
                4979,
                3877,
                4968,
                5091,
                5070,
                5210,
                6078,
                6092,
            )

    fun observedShapeBody(cmdId: Int, requestBody: String? = null): String? =
        when {
            cmdId == 100 -> GenericGameResponses.unionInfoUnavailable()
            cmdId == 102 -> "0"
            cmdId == 212 -> GenericGameResponses.userLookup()
            cmdId == 502 -> "[1,\"\"]"  // GET_USER_PROFILE(他人)：num=1(非0非2) => 客户端提示"无结果"并关闭，不崩
            cmdId == 3686 -> ProfileResponses.homepageInfo() // GET_HOMEPAGE_INFO(自己主页)：完整字典，空 {} 会崩
            cmdId == 5013 -> roleLookup(requestBody)
            cmdId == 4979 -> nameLookup(requestBody)
            cmdId == 5070 -> dailyReportDetail(requestBody)
            cmdId == 5210 -> heroRecommendation(requestBody)
            cmdId in booleanCommands -> "true"
            cmdId in jsonNullCommands -> "null"
            cmdId in scalarNumberCommands -> scalarNumberCommands.getValue(cmdId)
            cmdId in stringCommands -> "\"\""
            cmdId in fixedTupleCommands -> fixedTupleCommands.getValue(cmdId)
            cmdId == 3877 -> "[${GameServerConfig.SERVER_ID}]"
            cmdId == 4968 -> "[false,[]]"
            cmdId == 5091 -> "200"
            cmdId == 6078 -> transferSeasonRecommendations(requestBody)
            cmdId == 6092 -> "[[],0]"
            cmdId in dictionaryCommands -> GenericGameResponses.emptyObject()
            cmdId in pagedListCommands -> GenericGameResponses.emptyPagedList()
            cmdId in objectResultCommands -> GenericGameResponses.emptyObjectResult()
            cmdId in noOpArrayCommands -> GenericGameResponses.emptyArray()
            else -> null
        }

    private val booleanCommands = setOf(
        191, 748, 888, 981, 2311, 4087,
    )

    /**
     * 字符串 "null" 表示需要在网络上发送 JSON null；Kotlin null 则表示系统命令不应答。
     */
    private val jsonNullCommands = setOf(
        Cmd.ARMY_BATTLE,
        24,
        875,
        885,
        933,
        2402,
        2404,
        2405,
        2600,
        2601,
        4326,
        4966,
        Cmd.SET_CLIENT_RED_DOT_DATA,
        Cmd.GET_WORLD_SCENCE_INFO,
        6037,
        6351,
        7041,
    )

    /** 抓包确认返回顶层数字标量的命令（否则客户端按 int 解析会崩）。 */
    private val scalarNumberCommands = mapOf(
        750 to "0",
        752 to "6500",
        5069 to "200",
    )

    /** 抓包确认返回顶层字符串（不透明 token/序列化串）的命令，兜底回空串。 */
    private val stringCommands = setOf(
        671, 980, 40004, 40016,
    )

    private val dictionaryCommands = setOf(
        510, 5021, 6053, 6068, 6219, 6239,
    )

    private val fixedTupleCommands = mapOf(
        135 to "[[],{},{},{},{}]",
        172 to "[200,\"\"]",
        261 to "[{}]",
        262 to "[{}]",
        725 to "[0,[],[],{}]",
        2529 to "[0,0,[]]",
        2604 to "[0,[]]",
        3787 to "[[0,0],[]]",
        3928 to "[\"\",\"\"]",
        5201 to "[0]",
        6242 to "[0]",
        6243 to "[[]]",
        6244 to "[[]]",
        8009 to "[0,0,{},0,[],null]",
        20003 to "[0,[]]",
        40003 to "[0,0,[]]",
        40018 to "[0,0]",
        40020 to "[0,\"\",{},[],\"\"]",
        40021 to "[0]",
        40022 to "[0,0]",
    )

    private val pagedListCommands = setOf(
        91, 180, 206, 3611, 3719, 4331, 5022,
    )

    private val objectResultCommands = setOf(
        8020, 8021, 8025, 8027, 8031, 8032,
    )

    private fun roleLookup(requestBody: String?): String {
        val request = runCatching { mapper.readTree(requestBody ?: "[]") }.getOrNull()
        val lookupType = request?.get(0)?.asInt() ?: 3
        val roleId = request?.get(1)?.asText()?.takeIf { it.isNotBlank() } ?: "role_10001"
        return mapper.writeValueAsString(
            listOf(lookupType, roleId, emptyList<Any>(), roleId, 10001, 0, 2, GameServerConfig.ROLE_NAME),
        )
    }

    private fun nameLookup(requestBody: String?): String {
        val request = runCatching { mapper.readTree(requestBody ?: "[]") }.getOrNull()
        val name = request?.get(0)?.asText() ?: ""
        return mapper.writeValueAsString(listOf(name, emptyList<Any>(), emptyList<Any>()))
    }

    private fun dailyReportDetail(requestBody: String?): String {
        val request = runCatching { mapper.readTree(requestBody ?: "[]") }.getOrNull()
        val timestamp = request
            ?.takeIf { it.isArray && it.size() > 0 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?: 0
        return mapper.writeValueAsString(listOf(emptyList<Any>(), 0, "", "", timestamp))
    }

    private fun heroRecommendation(requestBody: String?): String {
        val request = runCatching { mapper.readTree(requestBody ?: "[]") }.getOrNull()
        val heroId = request
            ?.takeIf { it.isArray && it.size() > 0 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?: 0
        return mapper.writeValueAsString(listOf(heroId))
    }

    private fun transferSeasonRecommendations(requestBody: String?): String {
        val request = runCatching { mapper.readTree(requestBody ?: "[]") }.getOrNull()
        val recType = request
            ?.takeIf { it.isArray && it.size() > 1 }
            ?.takeIf { value ->
                (0..1).all { index ->
                    value[index].isIntegralNumber && value[index].canConvertToInt()
                }
            }
            ?.get(1)
            ?.asInt()
            ?: 0
        return mapper.writeValueAsString(listOf(recType, emptyList<Any>()))
    }
}
