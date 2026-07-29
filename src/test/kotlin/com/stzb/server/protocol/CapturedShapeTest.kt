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
}
