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
        // 981 REALNAME_LOGOUT / 4087 CHECK_HAVE_UNION_TO_JOIN 正式服真实 recv 为 bool。
        listOf(748, 981, 2311, 4087).forEach { cmd ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("boolean", ShapeAssert.topLevelKind(body), "cmd=$cmd")
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
    fun `captured opaque string commands keep string kind`() {
        listOf(671, 40016).forEach { cmd ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("string", ShapeAssert.topLevelKind(body), "cmd=$cmd")
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
            3928 to 2,
            8009 to 6,
            40018 to 2,
        )
        expectedSizes.forEach { (cmd, size) ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(size, ShapeAssert.tupleSize(body), "cmd=$cmd")
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
    fun `captured list iterated commands stay empty arrays`() {
        // 这些命令客户端整体遍历列表，空 [] 即结构正确（保结构不保数值）。
        listOf(103, 171, 711, 871).forEach { cmd ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(0, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `captured mail chat notification and gift lists stay non null empty arrays`() {
        listOf(202, 727, 3758, 6030).forEach { cmd ->
            val body = assertNotNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(0, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `first captured response batch is registered as observed shape`() {
        listOf(202, 727, 3758, 6030).forEach { cmd ->
            assertEquals(
                CommandStatus.OBSERVED_SHAPE,
                CommandContractCatalog.registry.contract(cmd)?.status,
                "cmd=$cmd",
            )
        }
    }
}
