package com.stzb.server.game

import com.stzb.server.protocol.Cmd
import kotlin.test.Test
import kotlin.test.assertEquals

class TeamRequestParserTest {
    @Test
    fun `world boss save team reads the first three body entries`() {
        val heroes = TeamRequestParser.parseSavedTeam(Cmd.WORLD_BOSS_SAVE_TEAM, "[101,102,103,999]")

        assertEquals(listOf(101, 102, 103), heroes)
    }

    @Test
    fun `exercise save team reads the first team tuple slots`() {
        val heroes = TeamRequestParser.parseSavedTeam(
            Cmd.EXERCISE_DAILY_SAVE_TEAM,
            """[[1,201,202,203],[2,301,302,303]]""",
        )

        assertEquals(listOf(201, 202, 203), heroes)
    }

    @Test
    fun `malformed save body returns an empty team`() {
        val heroes = TeamRequestParser.parseSavedTeam(Cmd.EXERCISE_DAILY_SAVE_TEAM, """{"bad":true}""")

        assertEquals(emptyList(), heroes)
    }
}
