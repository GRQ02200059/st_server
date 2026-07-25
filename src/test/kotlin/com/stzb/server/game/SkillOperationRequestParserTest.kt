package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillOperationRequestParserTest {
    @Test
    fun `learn and replace requests use the first three client fields`() {
        assertEquals(
            LearnSkillRequest(heroUid = 4_200_001, skillId = 200012, slotIndex = 2),
            SkillOperationRequestParser.parseLearn("[4200001,200012,2]"),
        )
        assertEquals(
            LearnSkillRequest(heroUid = 4_200_001, skillId = 200070, slotIndex = 3),
            SkillOperationRequestParser.parseLearn("[4200001,200070,3,0,200031]"),
        )
    }

    @Test
    fun `forget request carries hero uid and equipped skill id`() {
        assertEquals(
            ForgetSkillRequest(heroUid = 4_200_001, skillId = 200012),
            SkillOperationRequestParser.parseForget("[4200001,200012]"),
        )
    }

    @Test
    fun `invalid skill operation requests are rejected`() {
        assertNull(SkillOperationRequestParser.parseLearn("[4200001,200012,1]"))
        assertNull(SkillOperationRequestParser.parseForget("[4200001]"))
    }
}
