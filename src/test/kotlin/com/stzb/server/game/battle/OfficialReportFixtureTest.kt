package com.stzb.server.game.battle

import com.stzb.server.game.battle.skill.BattleTargetDecisionRequest
import com.stzb.server.game.battle.skill.BattleTrigger
import com.stzb.server.game.battle.skill.SkillBattleContext
import com.stzb.server.game.battle.skill.SkillRuleCatalog
import com.stzb.server.game.battle.skill.SkillRuntimeState
import com.stzb.server.game.battle.skill.SkillScope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class OfficialReportFixtureTest {
    private val officialReport =
        Path.of("assent/cfg/paper/11/cap_20260312014510506_0000000b_zlib.json")

    @Test
    fun `battle request uses the final precise stats recorded before battle start`() {
        val config = BattleConfigRepository.loadDefault()
        val actions = OfficialReportFixture.read(officialReport)
        val request = OfficialReportFixture.reconstructBattleRequest(actions, config)

        assertEquals(236.4, request.attacker.heroes.single { it.position == 2 }.stats.precise(BattleStat.STRATEGY))
        assertEquals(247.8, request.defender.heroes.single { it.position == 2 }.stats.precise(BattleStat.STRATEGY))
        assertEquals(198.0, request.defender.heroes.single { it.position == 2 }.inherentStats.precise(BattleStat.STRATEGY))

        val battleStart = actions.indexOfFirst { it.id == "hr".toInt(36) }
        val withPostStartMutation = actions.toMutableList().apply {
            add(battleStart + 1, OfficialReportFixture.parseText("0x3,999999,3,1,999.99").single())
        }
        val unchanged = OfficialReportFixture.reconstructBattleRequest(withPostStartMutation, config)
        assertEquals(236.4, unchanged.attacker.heroes.single { it.position == 2 }.stats.precise(BattleStat.STRATEGY))
    }

    @Test
    fun `target replay preserves report order and consumes repeated decision groups`() {
        val config = BattleConfigRepository.loadDefault()
        val rule = SkillRuleCatalog.build(
            SkillScope(setOf(200198), emptySet()),
            config,
        ).details.single { it.detailId == 20019801 }
        val source = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(3))
        val front = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(3))
        val middle = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(2))
        val base = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val context = SkillBattleContext(
            request = BattleRequest(BattleTeam(emptyList()), BattleTeam(emptyList())),
            runtime = SkillRuntimeState(),
            random = FixedBattleRandom(0),
            round = 0,
            source = source,
            rootSkillId = 200198,
            currentSkillId = 200198,
            trigger = BattleTrigger.BATTLE_PASSIVE,
        )
        val decisions = OfficialReportFixture.targetDecisions(
            OfficialReportFixture.parseText(
                "ja3,200198,3,531,53#ja3,200198,1,531,53#" +
                    "ja3,200198,3,533,53#ja3,200198,2,531,53",
            ),
        )
        fun select() = decisions.select(
            BattleTargetDecisionRequest(rule, context, listOf(front, middle, base), limit = 2),
        )

        assertEquals(listOf(front, base), select())
        assertEquals(listOf(middle), select())
    }
}
