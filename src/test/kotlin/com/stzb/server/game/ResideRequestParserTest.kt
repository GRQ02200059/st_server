package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResideRequestParserTest {
    @Test
    fun `parses wid and armyId from the six-field reside body`() {
        val req = ResideRequestParser.parse("[20001,150615061,0,0,0,0]")
        assertEquals(20001, req?.wid)
        assertEquals(150615061, req?.armyId)
    }

    @Test
    fun `rejects non positive wid or armyId`() {
        assertNull(ResideRequestParser.parse("[0,150615061,0,0,0,0]"))
        assertNull(ResideRequestParser.parse("[20001,0,0,0,0,0]"))
    }

    @Test
    fun `rejects malformed body`() {
        assertNull(ResideRequestParser.parse("not-json"))
        assertNull(ResideRequestParser.parse("[]"))
    }
}
