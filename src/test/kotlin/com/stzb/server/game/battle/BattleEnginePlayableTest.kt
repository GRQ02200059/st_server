package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleEnginePlayableTest {
    private val repo = BattleConfigRepository.loadDefault()

    @Test
    fun `equipment physical damage modifier increases skill damage`() {
        val baseAttacker = BattleTeam(
            listOf(hero(heroId = 100036, position = 2, skillIds = listOf(200070))),
        )
        val equippedAttacker = BattleTeam(
            listOf(
                hero(heroId = 100036, position = 2, skillIds = listOf(200070)).copy(
                    modifiers = listOf(BattleModifier.DamageDealtPercent(school = DamageSchool.PHYSICAL, percent = 8)),
                ),
            ),
        )
        val defender = BattleTeam(listOf(hero(heroId = 1, position = 2)))

        val base = BattleEngine.resolve(BattleRequest(baseAttacker, defender, maxRounds = 1), repo, FixedBattleRandom(0))
        val equipped = BattleEngine.resolve(BattleRequest(equippedAttacker, defender, maxRounds = 1), repo, FixedBattleRandom(0))

        val baseDamage = base.events.filterIsInstance<BattleEvent.SkillDamage>().first().damage
        val equippedDamage = equipped.events.filterIsInstance<BattleEvent.SkillDamage>().first().damage
        assertTrue(equippedDamage > baseDamage)
    }

    @Test
    fun `hesitation blocks active skill but allows normal attack`() {
        val result = controlledResult(BattleStatus.HESITATION, skillIds = listOf(200070))

        assertTrue(result.events.none {
            it is BattleEvent.SkillDamage && it.source.side == Side.ATTACKER
        })
        assertTrue(result.events.any {
            it is BattleEvent.NormalAttack && it.source.side == Side.ATTACKER
        })
    }

    @Test
    fun `disarm blocks normal attack but allows active skill`() {
        val result = controlledResult(BattleStatus.DISARM, skillIds = listOf(200070))

        assertTrue(result.events.any {
            it is BattleEvent.SkillDamage && it.source.side == Side.ATTACKER
        })
        assertTrue(result.events.none {
            it is BattleEvent.NormalAttack && it.source.side == Side.ATTACKER
        })
    }

    @Test
    fun `confusion blocks both active skill and normal attack`() {
        val result = controlledResult(BattleStatus.CONFUSION, skillIds = listOf(200070))

        assertTrue(result.events.none {
            (it is BattleEvent.SkillDamage || it is BattleEvent.NormalAttack) &&
                when (it) {
                    is BattleEvent.SkillDamage -> it.source.side == Side.ATTACKER
                    is BattleEvent.NormalAttack -> it.source.side == Side.ATTACKER
                    else -> false
                }
        })
    }

    @Test
    fun `disorder applies control and damage over time statuses`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100002, position = 2, skillIds = listOf(200002)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 2))),
                maxRounds = 1,
            ),
            repo,
            SequenceBattleRandom(0, 1, 2, 3),
        )

        assertEquals(
            listOf(BattleStatus.PANIC, BattleStatus.BURN, BattleStatus.HEX),
            result.events.filterIsInstance<BattleEvent.StatusApplied>().map { it.status },
        )
    }

    @Test
    fun `damage over time ticks each round and expires`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(heroId = 100002, position = 2, skillIds = listOf(200002)).copy(
                            stats = BattleStats(attack = 100, defense = 20, strategy = 120, speed = 100, siege = 0, hitRange = 3),
                        ),
                    ),
                ),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 2))),
                maxRounds = 4,
            ),
            repo,
            SequenceBattleRandom(0, 1, 1, 1),
        )

        val dotEvents = result.events.filterIsInstance<BattleEvent.OngoingDamage>()
        assertTrue(dotEvents.any { it.status == BattleStatus.PANIC && it.round == 2 })
        assertTrue(dotEvents.any { it.status == BattleStatus.PANIC && it.round == 3 })
        assertTrue(dotEvents.none { it.status == BattleStatus.PANIC && it.round == 4 })
        assertEquals(
            48,
            dotEvents.first { it.status == BattleStatus.PANIC && it.round == 2 }.damage,
            "ongoing strategy damage must use the reference troop and strategy curve",
        )
    }

    @Test
    fun `stacked damage over time statuses update target troops between ticks`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(hero(heroId = 1, position = 0, troops = 100, statuses = setOf(BattleStatus.DISARM))),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(
                            heroId = 2,
                            position = 0,
                            troops = 100,
                            statuses = setOf(BattleStatus.BURN, BattleStatus.PANIC, BattleStatus.DISARM),
                        ),
                    ),
                ),
                maxRounds = 1,
            ),
        )

        val dotEvents = result.events.filterIsInstance<BattleEvent.OngoingDamage>()

        assertEquals(listOf(99, 98), dotEvents.map { it.targetTroopsAfter })
        assertEquals(98, result.defender.heroes.single().troops)
    }

    @Test
    fun `control statuses expire and allow later normal attacks`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100002, position = 2, skillIds = listOf(200002)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 2, speed = 10))),
                maxRounds = 4,
            ),
            repo,
            SequenceBattleRandom(0, 4, 4, 4),
        )

        assertTrue(result.events.filterIsInstance<BattleEvent.NormalAttack>().none { it.source.side == Side.DEFENDER && it.round == 2 })
        assertTrue(result.events.filterIsInstance<BattleEvent.NormalAttack>().any { it.source.side == Side.DEFENDER && it.round >= 4 })
    }

    @Test
    fun `pursuit skills trigger after normal attacks`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100026, position = 2, skillIds = listOf(200206)))),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 2))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.any { it is BattleEvent.NormalAttack && it.source.heroId == BattleHeroId(100026) })
        assertTrue(result.events.any { it is BattleEvent.SkillDamage && it.source.heroId == BattleHeroId(100026) && it.skillId == 200206 })
    }

    @Test
    fun `normal attack that defeats the enemy base does not open pursuit`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(
                            heroId = 100026,
                            position = 2,
                            attack = 500,
                            skillIds = listOf(200206),
                        ),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(
                            heroId = 1,
                            position = 2,
                            defense = 0,
                            troops = 1,
                        ),
                    ),
                ),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertEquals(BattleOutcome.ATTACKER_WIN, result.outcome)
        assertEquals(
            0,
            result.events.filterIsInstance<BattleEvent.NormalAttack>()
                .single { it.source.heroId == BattleHeroId(100026) }
                .targetTroopsAfter,
        )
        assertEquals(
            1,
            result.events.filterIsInstance<BattleEvent.NormalAttack>()
                .count { it.source.heroId == BattleHeroId(100026) },
        )
        assertTrue(
            result.events.none {
                it is BattleEvent.TriggerPoint &&
                    it.source.heroId == BattleHeroId(100026) &&
                    it.trigger in setOf(
                        com.stzb.server.game.battle.skill.BattleTrigger.NORMAL_ATTACK_AFTER,
                        com.stzb.server.game.battle.skill.BattleTrigger.ACTION_AFTER,
                    )
            },
        )
        assertTrue(
            result.events.none {
                it is BattleEvent.HeroActionEnd &&
                    it.source.heroId == BattleHeroId(100026)
            },
        )
        assertTrue(
            result.events.none {
                it is BattleEvent.SkillTriggered &&
                    it.source.heroId == BattleHeroId(100026) &&
                    it.skillId == 200206
            },
        )
        assertTrue(
            result.events.none {
                it is BattleEvent.SkillDamage &&
                    it.source.heroId == BattleHeroId(100026) &&
                    it.skillId == 200206
            },
        )
        assertEquals(
            1,
            result.events.filterIsInstance<BattleEvent.TriggerPoint>().count {
                it.trigger ==
                    com.stzb.server.game.battle.skill.BattleTrigger.BASE_HERO_DEFEATED
            },
        )
        assertEquals(1, result.events.filterIsInstance<BattleEvent.BattleEnd>().size)
    }

    @Test
    fun `secondary attack that defeats the enemy base ends before normal attack after`() {
        val attacker = BattleTeamBuilder(
            repo,
            BattleEquipmentRepository.loadDefault(),
        ).build(
            listOf(
                BattleHeroSpec(
                    heroId = 100017,
                    position = 2,
                    troops = 10_000,
                    extraSkillIds = listOf(200233),
                    skillLevels = listOf(10, 1),
                    troopFeatureIds = listOf(3108),
                ),
            ),
        )
        val source = attacker.heroes.single()
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = attacker,
                defender = BattleTeam(
                    listOf(
                        hero(heroId = 1, position = 0, defense = 0, troops = 1),
                        hero(
                            heroId = 2,
                            position = 2,
                            defense = 500,
                            troops = 100_000,
                        ),
                    ),
                ),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertTrue(
            result.events.filterIsInstance<BattleEvent.SkillDamage>().any {
                it.source.heroId == source.id &&
                    it.target.heroId == BattleHeroId(1) &&
                    it.effectId == 545 &&
                    it.targetTroopsAfter == 0
            },
            "events=${result.events}",
        )
        assertEquals(BattleOutcome.ATTACKER_WIN, result.outcome)
        assertTrue(
            result.events.none {
                it is BattleEvent.TriggerPoint &&
                    it.source.heroId == source.id &&
                    it.trigger ==
                    com.stzb.server.game.battle.skill.BattleTrigger.NORMAL_ATTACK_AFTER
            },
        )
        assertEquals(1, result.events.filterIsInstance<BattleEvent.BattleEnd>().size)
    }

    @Test
    fun `counterattack that defeats the attacking base ends before normal attack after`() {
        val source = hero(
            heroId = 100026,
            position = 0,
            attack = 10,
            defense = 0,
            speed = 200,
            troops = 1,
            skillIds = listOf(200206),
        )
        val counterattacker = hero(
            heroId = 100010,
            position = 0,
            attack = 500,
            speed = 100,
            troops = 10_000,
            skillIds = listOf(200010),
        )
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(source)),
                defender = BattleTeam(listOf(counterattacker)),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertEquals(BattleOutcome.DEFENDER_WIN, result.outcome)
        assertTrue(
            result.events.filterIsInstance<BattleEvent.SkillDamage>().any {
                it.source.heroId == counterattacker.id &&
                    it.target.heroId == source.id &&
                    it.effectId == 551 &&
                    it.targetTroopsAfter == 0
            },
            "events=${result.events}",
        )
        assertTrue(
            result.events.none {
                it is BattleEvent.TriggerPoint &&
                    it.source.heroId == source.id &&
                    it.trigger ==
                    com.stzb.server.game.battle.skill.BattleTrigger.NORMAL_ATTACK_AFTER
            },
        )
        assertTrue(
            result.events.none {
                it is BattleEvent.SkillTriggered &&
                    it.source.heroId == source.id &&
                    it.skillId == 200206
            },
        )
        assertEquals(
            1,
            result.events.filterIsInstance<BattleEvent.TriggerPoint>().count {
                it.trigger ==
                    com.stzb.server.game.battle.skill.BattleTrigger.BASE_HERO_DEFEATED
            },
        )
        assertEquals(1, result.events.filterIsInstance<BattleEvent.BattleEnd>().size)
    }

    @Test
    fun `double attack performs two normal attacks and two pursuit attempts`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(
                            heroId = 100026,
                            position = 2,
                            attack = 20,
                            skillIds = listOf(200206),
                            statuses = setOf(BattleStatus.DOUBLE_ATTACK),
                        ),
                    ),
                ),
                defender = BattleTeam(listOf(hero(heroId = 1, position = 2, defense = 200, troops = 2_000))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertEquals(
            2,
            result.events.filterIsInstance<BattleEvent.NormalAttack>()
                .count { it.source.side == Side.ATTACKER },
        )
        assertEquals(
            2,
            result.events.filterIsInstance<BattleEvent.SkillDamage>()
                .count { it.source.side == Side.ATTACKER && it.skillId == 200206 },
        )
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
                attacker = BattleTeam(listOf(hero(heroId = 1, position = 2, attack = 200, speed = 200))),
                defender = BattleTeam(listOf(hero(heroId = 2, position = 2, defense = 0, speed = 50, statuses = setOf(BattleStatus.EVADE), troops = 1000))),
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
                attacker = BattleTeam(listOf(hero(heroId = 1, position = 2, attack = 100, speed = 200))),
                defender = BattleTeam(listOf(hero(heroId = 2, position = 2, defense = 50, speed = 50, troops = 10_000))),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val withBuffSkill = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(heroId = 100036, position = 2, attack = 100, speed = 200, skillIds = listOf(200001)))),
                defender = BattleTeam(listOf(hero(heroId = 2, position = 2, defense = 50, speed = 50, troops = 10_000))),
                maxRounds = 3,
            ),
            repo,
            FixedBattleRandom(0),
        )
        val baseDmg = noBuff.events.filterIsInstance<BattleEvent.NormalAttack>().firstOrNull()?.damage ?: 0
        val buffedAttacks = withBuffSkill.events.filterIsInstance<BattleEvent.NormalAttack>()
            .filter { it.source.heroId == BattleHeroId(100036) }
        val laterAttacks = buffedAttacks.filter { it.round >= 2 }
        assertTrue(laterAttacks.isNotEmpty(), "should find normal attack after round 1, events: ${withBuffSkill.events}")
        assertTrue(
            withBuffSkill.events.filterIsInstance<BattleEvent.StatChanged>()
                .any { it.skillId == 200001 && it.target.heroId == BattleHeroId(100036) },
            "configured skill must apply a real stat change",
        )
        assertTrue(
            buffedAttacks.first().damage > baseDmg,
            "configured stat buff should increase same-round damage, base=$baseDmg, buffed=$buffedAttacks",
        )
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

    @Test
    fun `target action damage defeats the base before it can act`() {
        val targetId = BattleHeroId(1)
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(
                            heroId = 100035,
                            position = 2,
                            attack = 500,
                            strategy = 500,
                            speed = 200,
                            skillIds = listOf(200684),
                            statuses = setOf(BattleStatus.DISARM),
                        ),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(
                            heroId = targetId.value,
                            position = 2,
                            defense = 0,
                            speed = 50,
                            troops = 1,
                        ),
                    ),
                ),
                maxRounds = 1,
            ),
            repo,
            FixedBattleRandom(0),
        )

        assertEquals(BattleOutcome.ATTACKER_WIN, result.outcome)
        assertTrue(
            result.events.any {
                it is BattleEvent.HeroActionStart && it.source.heroId == targetId
            },
            "events=${result.events}",
        )
        assertTrue(
            result.events.none {
                it is BattleEvent.NormalAttack && it.source.heroId == targetId
            },
            "events=${result.events}",
        )
    }

    private fun controlledResult(
        status: BattleStatus,
        skillIds: List<Int>,
    ): BattleResult = BattleEngine.resolve(
        BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100036, 2, attack = 500, skillIds = skillIds, statuses = setOf(status))),
            ),
            defender = BattleTeam(listOf(hero(1, 2))),
            maxRounds = 1,
        ),
        repo,
        FixedBattleRandom(0),
    )

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

    private class SequenceBattleRandom(
        vararg values: Int,
    ) : BattleRandom {
        private val values = values.toList()
        private var index = 0

        override fun nextInt(bound: Int): Int =
            values.getOrElse(index++) { bound - 1 }.coerceIn(0, bound - 1)
    }
}
