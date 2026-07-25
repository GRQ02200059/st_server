package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleEngineSkillTest {
    private val repo = BattleConfigRepository.loadDefault()

    @Test
    fun `configured engine casts active skill before normal attack`() {
        val attacker = BattleTeam(
            listOf(
                hero(
                    heroId = 100479,
                    position = 2,
                    attack = 100,
                    defense = 50,
                    strategy = 20,
                    speed = 100,
                    skillIds = listOf(200012),
                ),
            ),
        )
        val defender = BattleTeam(
            listOf(hero(heroId = 1, position = 2, attack = 10, defense = 20, strategy = 10, speed = 10)),
        )

        val result = BattleEngine.resolve(
            request = BattleRequest(attacker, defender, maxRounds = 1),
            config = repo,
            random = FixedBattleRandom(0),
        )

        val skillIndex = result.events.indexOfFirst {
            it is BattleEvent.SkillDamage && it.source.heroId == BattleHeroId(100479)
        }
        val attackIndex = result.events.indexOfFirst {
            it is BattleEvent.NormalAttack && it.source.heroId == BattleHeroId(100479)
        }

        assertTrue(skillIndex >= 0)
        assertTrue(attackIndex > skillIndex)
    }

    @Test
    fun `preparing an active skill does not consume the normal attack`() {
        val attacker = BattleTeam(
            listOf(hero(100017, 2, 100, 50, 120, 100, skillIds = listOf(200031))),
        )
        val defender = BattleTeam(
            listOf(hero(1, 2, 10, 20, 10, 10)),
        )

        val result = BattleEngine.resolve(
            BattleRequest(attacker, defender, maxRounds = 1),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.none { it is BattleEvent.SkillDamage })
        assertTrue(result.events.any {
            it is BattleEvent.NormalAttack && it.source.heroId == BattleHeroId(100017)
        })
    }

    @Test
    fun `each equipped active skill gets an independent activation attempt`() {
        val attacker = BattleTeam(
            listOf(hero(100017, 2, 100, 50, 120, 100, skillIds = listOf(200012, 200031))),
        )
        val defender = BattleTeam(
            listOf(hero(1, 2, 10, 20, 10, 10)),
        )

        val result = BattleEngine.resolve(
            BattleRequest(attacker, defender, maxRounds = 1),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.any {
            it is BattleEvent.SkillDamage && it.skillId == 200012
        })
        assertTrue(result.events.any {
            it is BattleEvent.SkillPreparationStarted && it.skillId == 200031
        })
    }

    @Test
    fun `command skills execute during the preparation stage`() {
        val attacker = BattleTeam(
            listOf(hero(100023, 0, 100, 100, 100, 100, skillIds = listOf(200023))),
        )
        val defender = BattleTeam(
            listOf(hero(1, 0, 10, 20, 10, 10)),
        )

        val result = BattleEngine.resolve(
            BattleRequest(attacker, defender, maxRounds = 1),
            repo,
            FixedBattleRandom(0),
        )

        val commandIndex = result.events.indexOfFirst {
            it is BattleEvent.UnsupportedSkillEffect && it.skillId == 200023
        }
        val roundIndex = result.events.indexOfFirst { it is BattleEvent.RoundStart }
        assertTrue(commandIndex >= 0)
        assertTrue(commandIndex < roundIndex)
    }

    @Test
    fun `confused hero skips action`() {
        val attacker = BattleTeam(
            listOf(
                hero(
                    heroId = 100479,
                    position = 0,
                    attack = 500,
                    defense = 50,
                    strategy = 20,
                    speed = 100,
                    activeStatuses = setOf(BattleStatus.CONFUSION),
                ),
            ),
        )
        val defender = BattleTeam(
            listOf(hero(heroId = 1, position = 0, attack = 10, defense = 20, strategy = 10, speed = 10)),
        )

        val result = BattleEngine.resolve(
            request = BattleRequest(attacker, defender, maxRounds = 1),
            config = repo,
            random = FixedBattleRandom(0),
        )

        assertTrue(
            result.events
                .filterIsInstance<BattleEvent.NormalAttack>()
                .none { it.source.heroId == BattleHeroId(100479) },
        )
        assertEquals(1000, result.defender.heroes.single().troops)
    }

    @Test
    fun `legacy engine resolve remains available`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 1, position = 2, attack = 500, defense = 0, strategy = 0, speed = 100))),
                defender = BattleTeam(listOf(hero(heroId = 2, position = 2, attack = 1, defense = 0, strategy = 0, speed = 1))),
                maxRounds = 1,
            ),
        )

        assertTrue(result.events.any { it is BattleEvent.NormalAttack })
    }

    private fun hero(
        heroId: Int,
        position: Int,
        attack: Int,
        defense: Int,
        strategy: Int,
        speed: Int,
        skillIds: List<Int> = emptyList(),
        activeStatuses: Set<BattleStatus> = emptySet(),
    ): BattleHero =
        BattleHero(
            id = BattleHeroId(heroId),
            position = position,
            stats = BattleStats(
                attack = attack,
                defense = defense,
                strategy = strategy,
                speed = speed,
                siege = 0,
                hitRange = 3,
            ),
            troops = 1000,
            skillIds = skillIds,
            activeStatuses = activeStatuses,
        )
}
