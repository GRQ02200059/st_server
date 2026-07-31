package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GearOperationRequestParserTest {
    @Test
    fun `both native gear commands use hero uid then gear uid`() {
        assertEquals(
            GearOperationRequest(heroUid = 1_000_071, gearUid = 800_001_042),
            GearOperationRequestParser.parse("[1000071,800001042]"),
        )
        assertEquals(
            GearOperationRequest(heroUid = 1_000_071, gearUid = 840_100_050),
            GearOperationRequestParser.parse("[1000071,840100050,0]"),
        )
    }

    @Test
    fun `malformed incomplete and nonpositive gear requests are rejected`() {
        assertNull(GearOperationRequestParser.parse("not-json"))
        assertNull(GearOperationRequestParser.parse("""{"heroUid":1000071}"""))
        assertNull(GearOperationRequestParser.parse("[1000071]"))
        assertNull(GearOperationRequestParser.parse("[0,800001042]"))
        assertNull(GearOperationRequestParser.parse("[1000071,0]"))
    }
}
