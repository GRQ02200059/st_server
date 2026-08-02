package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedShapeTest {
    @Test
    fun `top level kind detects array object scalar`() {
        assertEquals("array", ShapeAssert.topLevelKind("[1,2]"))
        assertEquals("object", ShapeAssert.topLevelKind("{\"a\":1}"))
        assertEquals("boolean", ShapeAssert.topLevelKind("true"))
        assertEquals("number", ShapeAssert.topLevelKind("200"))
        assertEquals("null", ShapeAssert.topLevelKind("null"))
    }

    @Test
    fun `tuple size counts array elements`() {
        assertEquals(3, ShapeAssert.tupleSize("[0,0,[]]"))
    }

    @Test
    fun `same shape passes for matching arrays`() {
        ShapeAssert.assertSameShape("[0,\"\"]", "[200,\"x\"]")
    }

    @Test
    fun `same shape fails for mismatched kinds`() {
        assertFailsWith<AssertionError> {
            ShapeAssert.assertSameShape("[]", "{}")
        }
    }

    // 以下为抓包形状对照：正式服真实 recv 顶层类型 vs 私服兜底输出。

    @Test
    fun `captured null commands answer json null`() {
        listOf(933, 2402, 2404, 2600, 2601).forEach { cmd ->
            assertEquals("null", NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
        }
    }

    @Test
    fun `captured boolean commands keep boolean kind`() {
        listOf(748).forEach { cmd ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("boolean", ShapeAssert.topLevelKind(body), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned real name logout is absent from observed shape fallback`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.REALNAME_LOGOUT))
        assertTrue(Cmd.REALNAME_LOGOUT !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `handler owned union eligibility and channel certification are absent from observed shape fallback`() {
        listOf(
            Cmd.CHECK_HAVE_UNION_TO_JOIN,
            Cmd.SET_CHANNEL_CERTIFICATION,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned log acknowledgements are absent from observed shape fallback`() {
        listOf(
            Cmd.USER_CLOSE_UI,
            Cmd.LOG_MUSIC_OPEN,
            Cmd.RESFILE_LOG_HUB_RECORD,
            Cmd.DAILY_REPORT_LOG,
            Cmd.HELP_GUIDE_TIPS_LOG,
            Cmd.UPDATE_GUIDE_RECORD,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `captured scalar number commands keep number kind`() {
        mapOf(750 to "0", 752 to "6500").forEach { (cmd, expected) ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("number", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(expected, body, "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned card record and customer service rejection are absent from observed shape fallback`() {
        listOf(
            Cmd.CARD_RECORD,
            Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned season history queries are absent from observed shape fallback`() {
        listOf(
            Cmd.GET_USER_SEASON_RECORD,
            Cmd.GET_SEASON_HISTROY_PARAMS,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `captured fixed tuples keep captured arity`() {
        val expectedSizes = mapOf(
            2604 to 2,
            8009 to 6,
        )
        expectedSizes.forEach { (cmd, size) ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(size, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned external rejections and login flags are absent from observed shape fallback`() {
        listOf(
            Cmd.FILE_PICKER_GET_TOKEN_DEFAULT,
            Cmd.CHECK_ADD_WEIXIN,
            Cmd.YOUTH_INK_MAP_TIPS,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `captured indexed tuples reserve positional slots so client resp index reads survive`() {
        // 这些命令客户端按下标读取（resp[0]/resp[1]），空 [] 会取到 null 崩溃。
        val expectedSizes = mapOf(
            6242 to 1,
            6243 to 1,
            6244 to 1,
            20003 to 2,
        )
        expectedSizes.forEach { (cmd, size) ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(size, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned request aware queries are absent from observed shape fallback`() {
        listOf(
            Cmd.UNION_GET_GROUP_LIST,
            Cmd.DAILY_REPORT_GET_DETAIL,
            Cmd.GET_HERO_RECOMMEND_2,
            Cmd.GET_UDS_GUESS_SEASON,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned sampled empty list queries are absent from observed shape fallback`() {
        listOf(
            Cmd.GET_UNION_BATTLE_REPORT,
            Cmd.MAIL_OUTBOX,
            Cmd.GET_BLACK_LIST,
            Cmd.NOTICE_LIST,
            Cmd.FRIEND_GROUP_GET_HISTORY_CHAT,
            Cmd.QUERY_WANTED_TO_REPOTR,
            Cmd.STRATEGY_HELP_GET,
            Cmd.COMMAND_PLAN_GET_UNION_TEMP_GROUP,
            Cmd.UNION_STATION_PLAYER_DANMU_LIST_GET,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned read only empty queries are absent from observed shape fallback`() {
        listOf(
            Cmd.SWITCH_ROLE_QUERY_ROLE_LIST,
            Cmd.MAIL_INBOX,
            Cmd.MAIL_GET_CONTACTS,
            Cmd.USER_GET_SEASON_COURSE_LIST,
            Cmd.CHAT_GET_ZHAO_XIAN_MSG,
            Cmd.PROGRESS_GET_INFO,
            Cmd.MAIL_NOTIFY_GET_ALL,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `captured list iterated commands stay empty arrays`() {
        // 这些命令客户端整体遍历列表，空 [] 即结构正确（保结构不保数值）。
        listOf(103, 711).forEach { cmd ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(0, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `captured gift list stays a non null empty array`() {
        val body = assertNotNull(NetworkResponsePolicy.observedShapeBody(6030))
        assertEquals("array", ShapeAssert.topLevelKind(body))
        assertEquals(0, ShapeAssert.tupleSize(body))
    }

    @Test
    fun `gift list remains registered as observed shape`() {
        assertEquals(
            CommandStatus.OBSERVED_SHAPE,
            CommandContractCatalog.registry.contract(6030)?.status,
        )
    }
}
