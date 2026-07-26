package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEngine
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompleteSkillEngineIntegrationTest {
    private val config = BattleConfigRepository.loadDefault()

    @Test
    fun `configured battle executes skill phases around the action in exact order`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(
                            id = 100479,
                            speed = 200,
                            skills = listOf(200009, 200014, 200012, 200206),
                        ),
                    ),
                ),
                defender = BattleTeam(listOf(hero(id = 1, speed = 10))),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        val attackerEvents = result.events.filter {
            when (it) {
                is BattleEvent.SkillTriggered -> it.source.side == Side.ATTACKER
                is BattleEvent.TriggerPoint -> it.source.side == Side.ATTACKER
                is BattleEvent.NormalAttack -> it.source.side == Side.ATTACKER
                else -> false
            }
        }
        val phases = attackerEvents.map {
            when (it) {
                is BattleEvent.SkillTriggered -> it.trigger
                is BattleEvent.TriggerPoint -> it.trigger
                is BattleEvent.NormalAttack -> "NORMAL_ATTACK"
                else -> error("unexpected $it")
            }
        }

        assertOrdered(
            phases,
            BattleTrigger.BATTLE_PASSIVE,
            BattleTrigger.BATTLE_COMMAND,
            BattleTrigger.ROUND_START,
            BattleTrigger.ACTION_BEFORE,
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            BattleTrigger.NORMAL_ATTACK_BEFORE,
            "NORMAL_ATTACK",
            BattleTrigger.NORMAL_ATTACK_AFTER,
            BattleTrigger.PURSUIT_ATTEMPT,
            BattleTrigger.ACTION_AFTER,
            BattleTrigger.ROUND_END,
        )
    }

    @Test
    fun `configured battle records damage and hurt hooks around skill damage`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(100479, 200, listOf(200012)))),
                defender = BattleTeam(listOf(hero(1, 10))),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        val damage = result.events.indexOfFirst { it is BattleEvent.SkillDamage }
        val before = result.events.indexOfFirst {
            it is BattleEvent.TriggerPoint && it.trigger == BattleTrigger.DAMAGE_BEFORE
        }
        val after = result.events.indexOfFirst {
            it is BattleEvent.TriggerPoint && it.trigger == BattleTrigger.DAMAGE_AFTER
        }
        val hurt = result.events.indexOfFirst {
            it is BattleEvent.TriggerPoint && it.trigger == BattleTrigger.HURT_AFTER
        }

        assertTrue(before in 0 until damage)
        assertTrue(after > damage)
        assertTrue(hurt > after)
    }

    @Test
    fun `configured battle uses one complete engine for command effects and all living positions`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100023, 60, listOf(200023), position = 0),
                        hero(100479, 50, position = 1),
                        hero(100017, 40, position = 2),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(1, 30, position = 0),
                        hero(2, 20, position = 1),
                        hero(3, 10, position = 2),
                    ),
                ),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.any {
            it is BattleEvent.SkillTriggered &&
                it.skillId == 200023 &&
                it.trigger == BattleTrigger.BATTLE_COMMAND
        })
        assertEquals(
            listOf(
                Side.ATTACKER to 0,
                Side.ATTACKER to 1,
                Side.ATTACKER to 2,
                Side.DEFENDER to 0,
                Side.DEFENDER to 1,
                Side.DEFENDER to 2,
            ),
            result.events.filterIsInstance<BattleEvent.HeroActionStart>()
                .map { it.source.side to it.source.position },
        )
    }

    private fun assertOrdered(actual: List<Any>, vararg expected: Any) {
        var previous = -1
        expected.forEach { value ->
            val index = actual.indexOfFirst { it == value }
            assertTrue(index > previous, "expected $value after index $previous, actual=$actual")
            previous = index
        }
    }

    private fun hero(
        id: Int,
        speed: Int,
        skills: List<Int> = emptyList(),
        position: Int = 2,
    ) = BattleHero(
        id = BattleHeroId(id),
        position = position,
        stats = BattleStats(attack = 100, defense = 100, strategy = 100, speed = speed, siege = 0, hitRange = 5),
        troops = 10_000,
        maxTroops = 10_000,
        skillIds = skills,
    )
}
