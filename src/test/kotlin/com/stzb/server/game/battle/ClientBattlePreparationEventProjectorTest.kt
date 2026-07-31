package com.stzb.server.game.battle

import com.stzb.server.game.battle.skill.BattleTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientBattlePreparationEventProjectorTest {
    private val source = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
    private val target = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(2))

    @Test
    fun `successful preparation status uses official applied action and configured effect id`() {
        val actions = ClientBattlePreparationEventProjector.project(
            BattleEvent.StatusApplied(
                round = 0,
                source = source,
                target = target,
                status = BattleStatus.DISARM,
                durationRounds = 2,
                skillId = 200648,
                effectId = 752,
            ),
            {},
        )

        assertEquals(listOf("0s6,752"), actions.map(ClientReportAction::encode))
        assertTrue(actions.none { it.id == "0t".toInt(36) })
    }

    @Test
    fun `round zero damage is diagnosed instead of using battle skill wrappers`() {
        val diagnostics = mutableListOf<String>()

        val projection = ClientBattlePreparationEventProjector.project(
            BattleEvent.SkillDamage(
                round = 0,
                skillId = 200648,
                effectId = 301,
                source = source,
                target = target,
                damage = 358,
                targetTroopsAfter = 9_242,
            ),
            diagnostics::add,
        )

        assertEquals(emptyList(), projection)
        assertTrue(diagnostics.single().contains("round-zero SkillDamage"))
    }

    @Test
    fun `unknown preparation modifier is diagnosed instead of becoming ja`() {
        val diagnostics = mutableListOf<String>()

        val actions = ClientBattlePreparationEventProjector.project(
            BattleEvent.ModifierApplied(
                round = 0,
                source = source,
                target = target,
                skillId = 200001,
                effectId = 999,
                amount = 10,
                durationRounds = 2,
            ),
            diagnostics::add,
        )

        assertEquals(emptyList(), actions)
        assertTrue(diagnostics.single().contains("effect=999"))
    }

    @Test
    fun `unverified derived preparation skill is diagnosed instead of becoming 8c`() {
        val diagnostics = mutableListOf<String>()

        val actions = ClientBattlePreparationEventProjector.project(
            BattleEvent.SkillTriggered(
                round = 0,
                source = source,
                rootSkillId = 200001,
                skillId = 219999,
                trigger = BattleTrigger.BATTLE_COMMAND,
            ),
            diagnostics::add,
        )

        assertEquals(emptyList(), actions)
        assertTrue(diagnostics.single().contains("derived skill=219999"))
    }
}
