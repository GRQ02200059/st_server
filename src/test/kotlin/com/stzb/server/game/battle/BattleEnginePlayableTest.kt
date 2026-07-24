package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertTrue

class BattleEnginePlayableTest {
    private val repo = BattleConfigRepository.loadDefault()

    @Test
    fun `equipment physical damage modifier increases skill damage`() {
        val baseAttacker = BattleTeam(
            listOf(hero(heroId = 100036, position = 0, skillIds = listOf(200070))),
        )
        val equippedAttacker = BattleTeam(
            listOf(
                hero(heroId = 100036, position = 0, skillIds = listOf(200070)).copy(
                    modifiers = listOf(BattleModifier.DamageDealtPercent(DamageKind.PHYSICAL, 8)),
                ),
            ),
        )
        val defender = BattleTeam(listOf(hero(heroId = 1, position = 0)))

        val base = BattleEngine.resolve(BattleRequest(baseAttacker, defender, maxRounds = 1), repo, FixedBattleRandom(0))
        val equipped = BattleEngine.resolve(BattleRequest(equippedAttacker, defender, maxRounds = 1), repo, FixedBattleRandom(0))

        val baseDamage = base.events.filterIsInstance<BattleEvent.SkillDamage>().first().damage
        val equippedDamage = equipped.events.filterIsInstance<BattleEvent.SkillDamage>().first().damage
        assertTrue(equippedDamage > baseDamage)
    }

    @Test
    fun `hesitation blocks active skills and disarm blocks normal attacks`() {
        val activeBlocked = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100036, position = 0, skillIds = listOf(200070), statuses = setOf(BattleStatus.HESITATION)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 0))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val normalBlocked = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100036, position = 0, attack = 500, statuses = setOf(BattleStatus.DISARM)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 0))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(activeBlocked.events.none { it is BattleEvent.SkillDamage })
        assertTrue(normalBlocked.events.none { it is BattleEvent.NormalAttack && it.source.heroId == BattleHeroId(100036) })
    }

    @Test
    fun `disorder applies control and damage over time statuses`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100002, position = 0, skillIds = listOf(200002)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 0))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.filterIsInstance<BattleEvent.StatusApplied>().any { it.status == BattleStatus.BURN })
        assertTrue(result.events.filterIsInstance<BattleEvent.StatusApplied>().any { it.status == BattleStatus.CONFUSION })
        assertTrue(result.events.filterIsInstance<BattleEvent.StatusApplied>().any { it.status == BattleStatus.DISARM })
    }

    @Test
    fun `damage over time ticks each round and expires`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(heroId = 100002, position = 0, skillIds = listOf(200002)).copy(
                            stats = BattleStats(attack = 100, defense = 20, strategy = 120, speed = 100, siege = 0, hitRange = 3),
                        ),
                    ),
                ),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 0))),
                maxRounds = 4,
            ),
            repo,
            FixedBattleRandom(0),
        )

        val dotEvents = result.events.filterIsInstance<BattleEvent.OngoingDamage>()
        assertTrue(dotEvents.any { it.status == BattleStatus.BURN && it.round == 2 })
        assertTrue(dotEvents.any { it.status == BattleStatus.BURN && it.round == 3 })
        assertTrue(dotEvents.none { it.status == BattleStatus.BURN && it.round == 4 })
    }

    @Test
    fun `control statuses expire and allow later normal attacks`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100002, position = 0, skillIds = listOf(200002)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 0, speed = 10))),
                maxRounds = 4,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.filterIsInstance<BattleEvent.NormalAttack>().none { it.source.side == Side.DEFENDER && it.round == 2 })
        assertTrue(result.events.filterIsInstance<BattleEvent.NormalAttack>().any { it.source.side == Side.DEFENDER && it.round >= 4 })
    }

    @Test
    fun `pursuit skills trigger after normal attacks`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100026, position = 0, skillIds = listOf(200206)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 0))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.any { it is BattleEvent.NormalAttack && it.source.heroId == BattleHeroId(100026) })
        assertTrue(result.events.any { it is BattleEvent.SkillDamage && it.source.heroId == BattleHeroId(100026) && it.skillId == 200206 })
    }

    @Test
    fun `insight status blocks control status application`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100002, position = 0, skillIds = listOf(200002)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 0, statuses = setOf(BattleStatus.INSIGHT)))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val controlsApplied = result.events.filterIsInstance<BattleEvent.StatusApplied>()
            .filter { it.status in setOf(BattleStatus.CONFUSION, BattleStatus.HESITATION, BattleStatus.DISARM) }
        assertTrue(controlsApplied.isEmpty(), "insight should block control, got: $controlsApplied")
    }

    @Test
    fun `evade status dodges one damage instance and is consumed`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 1, position = 0, attack = 200, speed = 200))),
                defender = BattleTeam(listOf(hero(heroId = 2, position = 0, defense = 0, speed = 50, statuses = setOf(BattleStatus.EVADE), troops = 1000))),
                maxRounds = 2,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val evaded = result.events.filterIsInstance<BattleEvent.Evaded>()
        assertTrue(evaded.isNotEmpty(), "should have Evaded event, events: ${result.events.map { it::class.simpleName }}")
        assertTrue(evaded.any { it.round == 1 }, "round 1 should have evade")
    }

    @Test
    fun `stat buff from skill increases damage on subsequent actions`() {
        val noBuff = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 1, position = 0, attack = 100, speed = 200))),
                defender = BattleTeam(listOf(hero(heroId = 2, position = 0, defense = 50, speed = 50))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val withBuffSkill = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100036, position = 0, attack = 100, speed = 200, skillIds = listOf(200036)))),
                defender = BattleTeam(listOf(hero(heroId = 2, position = 0, defense = 50, speed = 50))),
                maxRounds = 3,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val baseDmg = noBuff.events.filterIsInstance<BattleEvent.NormalAttack>().firstOrNull()?.damage ?: 0
        val laterAttacks = withBuffSkill.events.filterIsInstance<BattleEvent.NormalAttack>()
            .filter { it.source.heroId == BattleHeroId(100036) && it.round >= 2 }
        assertTrue(laterAttacks.isNotEmpty(), "should find normal attack after round 1, events: ${withBuffSkill.events}")
        assertTrue(laterAttacks.any { it.damage > baseDmg }, "stat buff should increase damage, base=$baseDmg, later=$laterAttacks")
    }

    @Test
    fun `dot and stat change events preserve their originating skill id`() {
        val dotResult = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(100002, 0, skillIds = listOf(200002)))),
                defender = BattleTeam(listOf(hero(1, 0))),
                maxRounds = 3,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val buffResult = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(100036, 0, skillIds = listOf(200001)))),
                defender = BattleTeam(listOf(hero(2, 0))),
                maxRounds = 2,
            ),
            repo,
            FixedBattleRandom(0),
        )

        val dotEvents = dotResult.events.filterIsInstance<BattleEvent.OngoingDamage>()
        val statChangedEvents = buffResult.events.filterIsInstance<BattleEvent.StatChanged>()
        assertTrue(dotEvents.isNotEmpty(), "expected DOT events, got: ${dotResult.events}")
        assertTrue(statChangedEvents.isNotEmpty(), "expected stat-change events, got: ${buffResult.events}")
        assertTrue(dotEvents.all { it.skillId == 200002 })
        assertTrue(statChangedEvents.all { it.skillId == 200001 })
    }

    private fun hero(
        heroId: Int,
        position: Int,
        attack: Int = 100,
        defense: Int = 20,
        strategy: Int = 80,
        speed: Int = 100,
        troops: Int = 1000,
        skillIds: List<Int> = emptyList(),
        statuses: Set<BattleStatus> = emptySet(),
    ): BattleHero =
        BattleHero(
            id = BattleHeroId(heroId),
            position = position,
            stats = BattleStats(attack = attack, defense = defense, strategy = strategy, speed = speed, siege = 0, hitRange = 3),
            troops = troops,
            maxTroops = troops,
            skillIds = skillIds,
            activeStatuses = statuses,
        )
}
