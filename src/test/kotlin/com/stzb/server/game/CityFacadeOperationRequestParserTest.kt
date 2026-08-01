package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CityFacadeOperationRequestParserTest {
    @Test
    fun `apply scheme parser accepts the observed four slot body`() {
        assertEquals(
            CityFacadeApplyRequest(
                cityWid = 15_061_506,
                customView = "3433080,100010;",
            ),
            CityFacadeOperationRequestParser.parseApplyScheme(
                """[15061506,"3433080,100010;",0,""]""",
            ),
        )
    }

    @Test
    fun `apply scheme parser rejects bodies outside the observed shape`() {
        listOf(
            """[15061506,"3433080,100010;"]""",
            """[15061506,"3433080,100010;",1,""]""",
            """[15061506,"3433080,100010;",0,"not-empty"]""",
            """[0,"3433080,100010;",0,""]""",
            """[15061506,0,0,""]""",
        ).forEach { body ->
            assertNull(CityFacadeOperationRequestParser.parseApplyScheme(body), body)
        }
    }
}
