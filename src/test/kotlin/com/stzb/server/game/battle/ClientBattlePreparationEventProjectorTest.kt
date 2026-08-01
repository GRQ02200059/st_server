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
        assertTrue(
            diagnostics.single().contains(
                "round-zero SkillDamage: skill=200648 effect=301 source=1 target=6",
            ),
        )
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
    fun `preparation reductions encode their positive magnitude in ja`() {
        val defenderFront = BattleHeroRef(Side.DEFENDER, 2, BattleHeroId(4))

        val actions = ClientBattlePreparationEventProjector.project(
            BattleEvent.ModifierApplied(
                round = 0,
                source = defenderFront,
                target = defenderFront,
                skillId = 200773,
                effectId = 522,
                amount = -37,
                durationRounds = 3,
            ),
            {},
        )

        assertEquals(listOf("ja4,200773,4,522,37"), actions.map(ClientReportAction::encode))
    }

    @Test
    fun `counter strategy active damage reduction uses official 7a action`() {
        val defenderFront = BattleHeroRef(Side.DEFENDER, 2, BattleHeroId(4))

        val actions = ClientBattlePreparationEventProjector.project(
            BattleEvent.ModifierApplied(
                round = 0,
                source = source,
                target = defenderFront,
                skillId = 200220,
                effectId = 332,
                amount = -90,
                durationRounds = 3,
            ),
            {},
        )

        assertEquals(listOf("7a1,200220,4,1001"), actions.map(ClientReportAction::encode))
    }

    @Test
    fun `flat preparation attributes use official compact action family`() {
        val events = listOf(
            BattleEvent.StatChanged(
                round = 0,
                source = source,
                target = source,
                stat = BattleStat.ATTACK,
                delta = 30,
                durationRounds = 3,
                skillId = 200233,
                effectId = 101,
                strength = 30,
                valueAfter = 238,
                valueAfterExact = 237.7,
                unit = BattleEffectValueUnit.FLAT,
            ),
            BattleEvent.StatChanged(
                round = 0,
                source = source,
                target = source,
                stat = BattleStat.DEFENSE,
                delta = 100,
                durationRounds = 10,
                skillId = 200689,
                effectId = 102,
                strength = 100,
                valueAfter = 273,
                valueAfterExact = 272.8,
                unit = BattleEffectValueUnit.FLAT,
            ),
            BattleEvent.StatChanged(
                round = 0,
                source = source,
                target = source,
                stat = BattleStat.STRATEGY,
                delta = 25,
                durationRounds = 10,
                skillId = 200689,
                effectId = 103,
                strength = 25,
                valueAfter = 314,
                valueAfterExact = 314.4,
                unit = BattleEffectValueUnit.FLAT,
            ),
        )

        assertEquals(
            listOf(
                "0v1,200233,1,30,237.7",
                "0w1,200689,1,100,272.8",
                "0x1,200689,1,25,314.4",
            ),
            events.flatMap { ClientBattlePreparationEventProjector.project(it, {}) }
                .map(ClientReportAction::encode),
        )
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
