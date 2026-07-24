package com.stzb.server.game

import com.stzb.server.protocol.Cmd
import kotlin.test.Test
import kotlin.test.assertEquals

class RecruitResultParserTest {
    @Test
    fun `card recruit hero ids are read from card list slot`() {
        val heroIds = RecruitResultParser.heroIdsFrom(Cmd.CARD_RECRUIT, """[1,[[0,100017,0,0],[0,100021,0,0]],0,0,0]""")

        assertEquals(listOf(100017, 100021), heroIds)
    }

    @Test
    fun `quick recruit hero ids are read from quick card list slot`() {
        val heroIds = RecruitResultParser.heroIdsFrom(
            Cmd.CARD_QUICK_RECRUIT,
            """[1,0,0,0,0,[2,0,0,1,1],[],[[0,100022,0,0],[0,100023,0,0]]]""",
        )

        assertEquals(listOf(100022, 100023), heroIds)
    }

    @Test
    fun `quick recruit capped result remains parseable`() {
        val response = GameResponses.quickCardRecruit(summonUid = 4_200_001, packId = 801, quickCount = 100)

        assertEquals(10, RecruitResultParser.heroIdsFrom(Cmd.CARD_QUICK_RECRUIT, response).size)
    }

    @Test
    fun `invalid recruit result has no hero ids`() {
        val heroIds = RecruitResultParser.heroIdsFrom(Cmd.CARD_RECRUIT, """[]""")

        assertEquals(emptyList(), heroIds)
    }
}
