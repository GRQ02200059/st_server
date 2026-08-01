package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArmyFacadeOperationRequestParserTest {
    @Test
    fun `batch and single facade requests require positive facade and hero ids`() {
        assertEquals(
            ArmyFacadeBindRequest(101138, listOf(10001, 10002)),
            ArmyFacadeOperationRequestParser.parseBatch("""[101138,[10001,10002]]"""),
        )
        assertEquals(
            ArmyFacadeBindRequest(101138, listOf(10001)),
            ArmyFacadeOperationRequestParser.parseSingle("""[101138,10001]"""),
        )
        assertNull(ArmyFacadeOperationRequestParser.parseBatch("""[101138,[]]"""))
        assertNull(ArmyFacadeOperationRequestParser.parseSingle("""[0,10001]"""))
    }

    @Test
    fun `use and special state requests accept only exact scalar shapes`() {
        assertEquals(
            ArmyFacadeUseRequest(101073, 10001),
            ArmyFacadeOperationRequestParser.parseUse("""[101073,10001]"""),
        )
        assertEquals(
            SpecialArmyFacadeStateRequest(ArmyFacadeCatalog.specialCardUid(101515), 2),
            ArmyFacadeOperationRequestParser.parseSpecialState(
                "[${ArmyFacadeCatalog.specialCardUid(101515)},2]",
            ),
        )
        assertNull(ArmyFacadeOperationRequestParser.parseUse("""[101073]"""))
        assertNull(ArmyFacadeOperationRequestParser.parseSpecialState("""[1,1]"""))
    }
}
