package com.stzb.server.game

import com.stzb.server.protocol.Cmd
import kotlin.test.Test
import kotlin.test.assertEquals

class ConscriptRequestParserTest {
    @Test
    fun `normal conscript request reads type and hero allocations`() {
        val request = ConscriptRequestParser.parse(Cmd.CONSCRIPT, """[0,[[90100001,120],[90100002,80]]]""")

        assertEquals(0, request?.type)
        assertEquals(listOf(ConscriptAllocation(90100001, 120), ConscriptAllocation(90100002, 80)), request?.allocations)
    }

    @Test
    fun `immediate conscript request reads allocations before type`() {
        val request = ConscriptRequestParser.parse(Cmd.CONSCRIPT_IMMEDIATELY, """[[[90100001,200]],1]""")

        assertEquals(1, request?.type)
        assertEquals(listOf(ConscriptAllocation(90100001, 200)), request?.allocations)
    }

    @Test
    fun `invalid conscript request is ignored`() {
        val request = ConscriptRequestParser.parse(Cmd.CONSCRIPT, """[]""")

        assertEquals(null, request)
    }
}
