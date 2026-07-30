package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        listOf(24, 933, 2600, 2601, 4326, 4966).forEach { cmd ->
            assertEquals("null", NetworkResponsePolicy.fallbackBody(cmd), "cmd=$cmd")
        }
    }

    @Test
    fun `captured scalar number commands keep number kind`() {
        mapOf(5069 to "200", 750 to "0", 752 to "6500").forEach { (cmd, expected) ->
            val body = NetworkResponsePolicy.fallbackBody(cmd)!!
            assertEquals("number", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(expected, body, "cmd=$cmd")
        }
    }

    @Test
    fun `captured opaque string commands keep string kind`() {
        listOf(671, 40004, 40016).forEach { cmd ->
            val body = NetworkResponsePolicy.fallbackBody(cmd)!!
            assertEquals("string", ShapeAssert.topLevelKind(body), "cmd=$cmd")
        }
    }

    @Test
    fun `captured object command keeps object kind`() {
        val body = NetworkResponsePolicy.fallbackBody(40008)!!
        assertEquals("object", ShapeAssert.topLevelKind(body), "cmd=40008")
    }

    @Test
    fun `captured fixed tuples keep captured arity`() {
        val expectedSizes = mapOf(
            2604 to 2,
            3928 to 2,
            8009 to 6,
            40003 to 3,
            40018 to 2,
            40020 to 5,
            40021 to 1,
            40022 to 2,
        )
        expectedSizes.forEach { (cmd, size) ->
            val body = NetworkResponsePolicy.fallbackBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(size, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `captured indexed tuples reserve positional slots so client resp index reads survive`() {
        // 这些命令客户端按下标读取（resp[0]/resp[1]），空 [] 会取到 null 崩溃。
        val expectedSizes = mapOf(
            142 to 1,
            5210 to 2,
            6242 to 1,
            6243 to 1,
            6244 to 1,
            20003 to 2,
        )
        expectedSizes.forEach { (cmd, size) ->
            val body = NetworkResponsePolicy.fallbackBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(size, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `captured list iterated commands stay empty arrays`() {
        // 这些命令客户端整体遍历列表，空 [] 即结构正确（保结构不保数值）。
        listOf(92, 103, 143, 171, 711, 871, 6256).forEach { cmd ->
            val body = NetworkResponsePolicy.fallbackBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(0, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }
}
