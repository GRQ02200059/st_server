package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals

class ArmyBattleRequestParserTest {
    @Test
    fun `battle request body reads target wid and army id`() {
        val request = ArmyBattleRequestParser.parse("""[1902,901001,0,0,0,"",0,0,[],0,0]""")

        assertEquals(1902, request?.targetWid)
        assertEquals(901001, request?.armyId)
    }

    @Test
    fun `invalid battle request body is ignored`() {
        val request = ArmyBattleRequestParser.parse("""[]""")

        assertEquals(null, request)
    }
}
