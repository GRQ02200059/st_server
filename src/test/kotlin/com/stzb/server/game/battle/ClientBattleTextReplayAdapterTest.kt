package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientBattleTextReplayAdapterTest {
    @Test
    fun `creates one preparation stage and one client round per engine round`() {
        val actions = ClientBattleTextReplayAdapter.adapt(twoRoundResult())

        assertEquals(1, actions.count { it.id == ClientBattleTextReplayProtocol.PREPARE })
        assertEquals(
            listOf(listOf<Any>(1), listOf<Any>(2)),
            actions.filter { it.id == ClientBattleTextReplayProtocol.ROUND }.map { it.params },
        )
        assertEquals(
            listOf(1, 2, 6),
            actions.filter { it.id == ClientBattleTextReplayProtocol.HERO_NAME }.map { it.params.first() },
        )
    }

    @Test
    fun `initializes every named hero with fixed width detail data`() {
        val result = twoRoundResult().let { base ->
            base.copy(
                attacker = base.attacker.copy(
                    heroes = base.attacker.heroes.mapIndexed { index, hero ->
                        if (index == 0) hero.copy(level = 20, skillIds = listOf(200012, 200834)) else hero
                    },
                ),
            )
        }

        val actions = ClientBattleTextReplayAdapter.adapt(result)
        val heroInfo = actions.filter { it.id == ClientBattleTextReplayProtocol.HERO_INFO }

        assertEquals(3, heroInfo.size)
        assertEquals(
            listOf<Any>(1, 20, 1_000, 200012, 1, 200834, 1, 0, 0, 0, 0),
            heroInfo.first().params,
        )
        assertTrue(heroInfo.all { it.params.size == 11 })
        assertTrue(heroInfo.all { it.params.takeLast(2) == listOf<Any>(0, 0) })
    }

    @Test
    fun `projects normal skill damage and recovery into distinct text actions`() {
        val actions = ClientBattleTextReplayAdapter.adapt(eventResult())

        val normalDamageIndex = actions.indexOfFirst {
            it.id == ClientBattleTextReplayProtocol.NORMAL_DAMAGE &&
                it.params == listOf<Any>(6, 120, 880)
        }
        assertTrue(normalDamageIndex > 0)
        assertEquals(
            ClientReportAction(ClientBattleTextReplayProtocol.NORMAL_ATTACK, listOf(1, 6)),
            actions[normalDamageIndex - 2],
        )
        assertEquals(ClientBattleTextReplayProtocol.SKILL_BEGIN, actions[normalDamageIndex - 1].id)
        assertEquals(ClientBattleTextReplayProtocol.SKILL_END, actions[normalDamageIndex + 1].id)

        val skillDamageIndex = actions.indexOfFirst {
            it.id == ClientBattleTextReplayProtocol.SKILL_DAMAGE &&
                it.params == listOf<Any>(1, 200012, 6, 180, 700)
        }
        assertTrue(skillDamageIndex > 1)
        assertEquals(ClientBattleTextReplayProtocol.SKILL_BEGIN, actions[skillDamageIndex - 2].id)
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.SKILL_CAST &&
                it.params == listOf<Any>(1, 1, 200012)
        })
        assertEquals(ClientBattleTextReplayProtocol.SKILL_END, actions[skillDamageIndex + 1].id)
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.RECOVERY &&
                it.params == listOf<Any>(1, 200001, 1, 70, 950)
        })
    }

    @Test
    fun `projects preparation start with the real client action`() {
        val source = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(1))
        val result = twoRoundResult().copy(
            events = listOf(
                BattleEvent.RoundStart(1),
                BattleEvent.SkillPreparationStarted(1, source, 200031, readyRound = 2),
                BattleEvent.RoundEnd(1),
            ),
        )

        val actions = ClientBattleTextReplayAdapter.adapt(result)

        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.SKILL_PREPARATION_STARTED &&
                it.params == listOf<Any>(2, 200031)
        })
    }

    @Test
    fun `projects every hero action with the real client lifecycle`() {
        val source = BattleHeroRef(Side.DEFENDER, 2, BattleHeroId(4))
        val result = twoRoundResult().copy(
            events = listOf(
                BattleEvent.RoundStart(1),
                BattleEvent.HeroActionStart(1, source),
                BattleEvent.HeroActionEnd(1, source),
                BattleEvent.RoundEnd(1),
            ),
        )

        val actions = ClientBattleTextReplayAdapter.adapt(result)
        val startIndex = actions.indexOfFirst {
            it.id == ClientBattleTextReplayProtocol.HERO_ACTION_START &&
                it.params == listOf<Any>(4)
        }

        assertTrue(startIndex >= 0)
        assertEquals(
            ClientReportAction(ClientBattleTextReplayProtocol.HERO_ACTION_END, listOf(4)),
            actions[startIndex + 1],
        )
    }

    @Test
    fun `projects status dot evade and stat change with their original skill id`() {
        val actions = ClientBattleTextReplayAdapter.adapt(stateResult())

        val statusIndex = actions.indexOfFirst {
            it.id == ClientBattleTextReplayProtocol.STATUS &&
                it.params == listOf<Any>(1, 6, 200002, 305)
        }
        assertTrue(statusIndex > 1)
        assertEquals(ClientBattleTextReplayProtocol.SKILL_BEGIN, actions[statusIndex - 2].id)
        assertEquals(ClientBattleTextReplayProtocol.SKILL_CAST, actions[statusIndex - 1].id)
        assertEquals(ClientBattleTextReplayProtocol.SKILL_END, actions[statusIndex + 1].id)
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.ONGOING_DAMAGE &&
                it.params == listOf<Any>(1, 200002, 6, 60, 640, 305)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.STATUS &&
                it.params == listOf<Any>(1, 6, 0, 515)
        })
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.STATUS &&
                it.params == listOf<Any>(1, 1, 200036, 101)
        })
    }

    @Test
    fun `omits unattributed effect text actions while retaining evade`() {
        val actions = ClientBattleTextReplayAdapter.adapt(unattributedEffectsResult())

        assertTrue(actions.none { it.id == ClientBattleTextReplayProtocol.RECOVERY })
        assertTrue(actions.none { it.id == ClientBattleTextReplayProtocol.ONGOING_DAMAGE })
        assertEquals(
            listOf(listOf<Any>(1, 6, 0, 515)),
            actions.filter { it.id == ClientBattleTextReplayProtocol.STATUS }.map { it.params },
        )
        assertTrue(actions.none { it.id == ClientBattleTextReplayProtocol.SKILL_CAST })
    }

    @Test
    fun `retains unsupported skill effects and still projects the skill activation`() {
        val baseResult = twoRoundResult()
        val source = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val resultWithUnsupportedEffect = baseResult.copy(
            events = baseResult.events + BattleEvent.UnsupportedSkillEffect(
                round = 2,
                skillId = 200999,
                effectId = 999,
                source = source,
                rawDescription = "unsupported effect",
            ),
        )

        val json = BattleReportCodec.toJson(resultWithUnsupportedEffect)

        assertTrue(json.contains("UnsupportedSkillEffect"))
        assertTrue(json.contains("\"skillId\":200999"))
        val actions = ClientBattleTextReplayAdapter.adapt(resultWithUnsupportedEffect)
        assertTrue(actions.any {
            it.id == ClientBattleTextReplayProtocol.SKILL_CAST &&
                it.params == listOf<Any>(1, 1, 200999)
        })
        assertTrue(ClientReportTextEncoder.encode(resultWithUnsupportedEffect).contains("8d1,1,200999"))
    }

    @Test
    fun `ends the report before writing final troops for both sides`() {
        val actions = ClientBattleTextReplayAdapter.adapt(eventResult())
        val endIndex = actions.indexOfFirst { it.id == ClientBattleTextReplayProtocol.END }
        assertTrue(endIndex >= 0)
        assertTrue(
            actions.take(endIndex).none { it.id == ClientBattleTextReplayProtocol.FINAL_TROOPS },
        )
        val finalTroops = actions.drop(endIndex + 1)
            .filter { it.id == ClientBattleTextReplayProtocol.FINAL_TROOPS }

        assertEquals(3, finalTroops.size)
        assertEquals(listOf<Any>(1, 950, 50), finalTroops[0].params)
        assertEquals(listOf<Any>(2, 1000, 0), finalTroops[1].params)
        assertEquals(listOf<Any>(6, 700, 300), finalTroops[2].params)
    }

    private fun twoRoundResult(): BattleResult =
        BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(listOf(hero(1, 0), hero(2, 1))),
            defender = BattleTeam(listOf(hero(4, 0))),
            events = listOf(
                BattleEvent.BattleStart,
                BattleEvent.RoundStart(1),
                BattleEvent.RoundStart(2),
                BattleEvent.BattleEnd(BattleOutcome.ATTACKER_WIN),
            ),
        )

    private fun eventResult(): BattleResult {
        val attacker = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val defender = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(4))
        return BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(
                listOf(
                    hero(1, 0, troops = 950),
                    hero(2, 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(4, 0, troops = 700))),
            events = listOf(
                BattleEvent.NormalAttack(1, attacker, defender, 120, 880),
                BattleEvent.SkillDamage(1, 200012, 301, attacker, defender, 180, 700),
                BattleEvent.Recovery(1, attacker, attacker, 70, 950, skillId = 200001),
            ),
        )
    }

    private fun unattributedEffectsResult(): BattleResult {
        val attacker = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val defender = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(4))
        return BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(listOf(hero(1, 0))),
            defender = BattleTeam(listOf(hero(4, 0))),
            events = listOf(
                BattleEvent.Recovery(1, attacker, attacker, 70, 950),
                BattleEvent.StatusApplied(1, attacker, defender, BattleStatus.BURN, 2),
                BattleEvent.OngoingDamage(2, attacker, defender, BattleStatus.BURN, 60, 640),
                BattleEvent.StatChanged(2, attacker, attacker, BattleStat.ATTACK, 10, 2),
                BattleEvent.Evaded(2, attacker, defender),
            ),
        )
    }

    private fun stateResult(): BattleResult {
        val attacker = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
        val defender = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(4))
        return BattleResult(
            outcome = BattleOutcome.ATTACKER_WIN,
            attacker = BattleTeam(listOf(hero(1, 0))),
            defender = BattleTeam(listOf(hero(4, 0))),
            events = listOf(
                BattleEvent.StatusApplied(1, attacker, defender, BattleStatus.BURN, 2, skillId = 200002),
                BattleEvent.OngoingDamage(2, attacker, defender, BattleStatus.BURN, 60, 640, skillId = 200002),
                BattleEvent.Evaded(2, attacker, defender),
                BattleEvent.StatChanged(2, attacker, attacker, BattleStat.ATTACK, 10, 2, skillId = 200036),
            ),
        )
    }

    private fun hero(id: Int, position: Int, troops: Int = 1_000): BattleHero =
        BattleHero(
            id = BattleHeroId(id),
            position = position,
            stats = BattleStats(1, 1, 1, 1, 0, 1),
            troops = troops,
            maxTroops = 1_000,
        )
}
