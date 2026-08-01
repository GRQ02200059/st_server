package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleDamageCalculator
import com.stzb.server.game.battle.BattleEngine
import com.stzb.server.game.battle.BattleEquipmentRepository
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleHeroSpec
import com.stzb.server.game.battle.BattleModifier
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStat
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.BattleTeamBuilder
import com.stzb.server.game.battle.DamageOrigin
import com.stzb.server.game.battle.DamageSchool
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompleteSkillEngineIntegrationTest {
    private val config = BattleConfigRepository.loadDefault()

    @Test
    fun `production engine exposes client hero metadata to skill conditions`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100582, 100, listOf(200828), position = 2)),
            ),
            defender = BattleTeam(
                listOf(hero(100003, 100, position = 2)),
            ),
            maxRounds = 1,
        )

        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }

        assertTrue(SkillBattleViewCapability.HERO_METADATA in engine.state.view.capabilities)
        assertEquals(
            SkillBattleHeroMetadata(
                gender = SkillHeroGender.MALE,
                troopType = SkillTroopType.INFANTRY,
                country = 3,
            ),
            engine.state.view.metadata(source),
        )
    }

    @Test
    fun `generic meta intents fail closed unless the engine consumes their operation`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100023, 100, position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val graph = SkillRuleCatalog.build(SkillScopeCatalog.loadDefault(), config)
        val parameters = MetaEffectParameters.from(
            graph.details.single { it.detailId == 20002316 },
        )
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 200023,
            currentSkillId = 200023,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        assertFailsWith<UnsupportedBattleStateChangeException> {
            engine.applyChanges(
                listOf(
                    MetaEffectChange(
                        source = source,
                        target = target,
                        rootSkillId = 200023,
                        skillId = 200023,
                        detailId = parameters.detailId,
                        effectId = 77,
                        operation = MetaEffectOperation.MARKER,
                        parameters = parameters,
                    ),
                ),
                context,
            )
        }
    }

    @Test
    fun `specified effect trigger ticks only the requested ongoing effect`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100023, 100, position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 297173,
            currentSkillId = 297173,
            trigger = BattleTrigger.BATTLE_PASSIVE,
            battleView = engine.state.view,
        )
        fun ongoing(
            effectId: Int,
            status: com.stzb.server.game.battle.BattleStatus,
            tag: com.stzb.server.game.battle.DamageTag,
        ): ScheduledDamageEffectChange {
            val spec = PersistentEffectSpec(
                source = source,
                target = target,
                rootSkillId = 900000,
                skillId = 900000,
                skillKind = SkillKind.PASSIVE,
                rawSkillType = 1,
                detailId = 90000000 + effectId,
                effectId = effectId,
                category = com.stzb.server.game.battle.EffectCategory.HARMFUL,
                conflict = 0,
                replaceType = 0,
                bindFlag = 0,
                maxStacks = 1,
                delayRound = 0,
                delayHit = 0,
                availableRounds = 2,
                availableHit = 0,
                clearPerHit = false,
                startBoundary = EffectStartBoundary.IMMEDIATE,
                potency = TypedBattlePotency.rate(100),
            )
            return ScheduledDamageEffectChange(
                spec = spec,
                school = DamageSchool.STRATEGY,
                origin = DamageOrigin.PASSIVE,
                tags = setOf(com.stzb.server.game.battle.DamageTag.ONGOING, tag),
                status = status,
                coefficientSource = BattleCoefficientSource.NONE,
                rawCoefficient = 0,
                calculationTypes = emptyList(),
            )
        }
        engine.applyChanges(
            listOf(
                ongoing(
                    304,
                    com.stzb.server.game.battle.BattleStatus.PANIC,
                    com.stzb.server.game.battle.DamageTag.PANIC,
                ),
                ongoing(
                    305,
                    com.stzb.server.game.battle.BattleStatus.BURN,
                    com.stzb.server.game.battle.DamageTag.BURN,
                ),
            ),
            context,
        )
        val graph = SkillRuleCatalog.build(SkillScope(setOf(297173), emptySet()), config)
        val parameters = MetaEffectParameters.from(
            graph.details.single { it.detailId == 29717301 },
        )

        val events = engine.applyChanges(
            listOf(
                TriggerSpecifiedEffectChange(
                    source = source,
                    target = target,
                    rootSkillId = 297173,
                    skillId = 297173,
                    detailId = 29717301,
                    triggeredEffectId = 305,
                    parameters = parameters,
                ),
            ),
            context,
        )

        assertEquals(
            com.stzb.server.game.battle.BattleStatus.BURN,
            events.filterIsInstance<BattleEvent.OngoingDamage>().single().status,
        )
    }

    @Test
    fun `named flag counter intents clamp per target and flag id`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100001, 100, position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 200264,
            currentSkillId = 213264,
            trigger = BattleTrigger.ROUND_START,
            battleView = engine.state.view,
        )
        val change = NamedFlagCounterChange(
            source = source,
            target = source,
            rootSkillId = 200264,
            skillId = 213264,
            detailId = 21326401,
            flagId = 210264,
            delta = 2,
            maximum = 3,
        )

        engine.applyChanges(listOf(change, change), context)

        assertEquals(
            3,
            engine.state.runtime.counter(source, "skill.named-flag.210264"),
        )
    }

    @Test
    fun `engine applies hit scoped damage modifier without round duration`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100001, 100, position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 200069,
            currentSkillId = 200069,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )

        engine.applyChanges(
            listOf(
                DamageModifierChange(
                    source = source,
                    target = source,
                    direction = DamageModifierChange.Direction.DEALT,
                    school = DamageSchool.PHYSICAL,
                    origin = null,
                    tag = null,
                    percent = 90,
                    durationRounds = 0,
                    skillId = 200069,
                    effectId = 531,
                    detailId = 20006901,
                    availableHits = 1,
                ),
            ),
            context,
        )

        val effect = engine.state.effectStore.effectsFor(source).single()
        assertEquals(20006901, effect.detailId)
        assertEquals(1, effect.remainingHits)
        assertTrue(
            engine.state.liveHero(source).modifiers
                .filterIsInstance<BattleModifier.DamageDealtPercent>()
                .any { it.percent == 90 },
        )
    }

    @Test
    fun `engine rejects damage modifier without round or hit lifecycle`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100001, 100, position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 900001,
            currentSkillId = 900001,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )

        assertFailsWith<IllegalArgumentException> {
            engine.applyChanges(
                listOf(
                    DamageModifierChange(
                        source = source,
                        target = source,
                        direction = DamageModifierChange.Direction.DEALT,
                        school = DamageSchool.PHYSICAL,
                        origin = null,
                        tag = null,
                        percent = 10,
                        durationRounds = 0,
                        skillId = 900001,
                        effectId = 531,
                        availableHits = 0,
                    ),
                ),
                context,
            )
        }
    }

    @Test
    fun `neizhuzhixian official command stat bonus persists through eight rounds`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100001, 100, listOf(300021), position = 2),
                    hero(100002, 90, position = 1),
                    hero(100003, 80, position = 0),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 8,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)

        val allies = engine.state.view.heroes().filter { it.side == Side.ATTACKER }
        allies.forEach { ally ->
            assertTrue(requireNotNull(engine.state.view.state(ally)).stats.strategy > 100)
            val effect = engine.state.effectStore.effectsFor(ally).single {
                it.detailId == 30002101
            }
            assertEquals(null, effect.remainingRounds)
            assertEquals(1, effect.remainingHits)
        }

        (1..8).forEach(engine::finishRound)

        allies.forEach { ally ->
            assertTrue(requireNotNull(engine.state.view.state(ally)).stats.strategy > 100)
            assertTrue(engine.state.effectStore.effectsFor(ally).any {
                it.detailId == 30002101
            })
        }
    }

    @Test
    fun `skill range meta intent scopes a configured skill without affecting its peers`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100871, 100, position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val graph = SkillRuleCatalog.build(
            SkillScope(setOf(200871), emptySet()),
            config,
        )
        val parameters = MetaEffectParameters.from(
            graph.details.single { it.detailId == 20087102 },
        )
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = source,
            rootSkillId = 200871,
            currentSkillId = 200871,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.applyChanges(
            listOf(
                MetaEffectChange(
                    source = source,
                    target = source,
                    rootSkillId = 200871,
                    skillId = 200871,
                    detailId = parameters.detailId,
                    effectId = 171,
                    operation = MetaEffectOperation.SKILL_RANGE_INCREASE,
                    parameters = parameters,
                ),
            ),
            context,
        )

        assertEquals(
            1,
            engine.state.view.skillRangeBonus(source, SkillKind.ACTIVE, skillId = 200690),
        )
        assertEquals(
            0,
            engine.state.view.skillRangeBonus(source, SkillKind.ACTIVE, skillId = 200105),
        )
    }

    @Test
    fun `shenshidingji registers resistance for every ally during command setup`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100257, 100, listOf(200257), position = 2),
                    hero(100001, 90, position = 1),
                    hero(100002, 80, position = 0),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100257) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        val events = engine.prepareBattle(context)

        engine.state.view.heroes()
            .filter { it.side == Side.ATTACKER }
            .forEach { ally ->
                assertTrue(
                    engine.state.effectStore.effectsFor(ally).any {
                        it.source == owner &&
                            it.rootSkillId == 200257 &&
                            it.skillId == 210257 &&
                            it.effectId == 118
                    },
                    "ally=$ally events=$events effects=${engine.state.effectStore.effectsFor(ally)}",
                )
            }
        assertTrue(events.none { it is BattleEvent.SkillDamage || it is BattleEvent.Recovery })
    }

    @Test
    fun `shenshidingji reacts before an enemy receives a harmful stat effect`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100257, 100, listOf(200257), position = 2).copy(troops = 9_000),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.state.mutable(owner).woundedTroops = 1_000
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)

        val events = engine.applyChanges(
            listOf(statChange(owner, enemy, 201, -10)),
            context.copy(
                round = 1,
                rootSkillId = 900000,
                currentSkillId = 900000,
                trigger = BattleTrigger.EFFECT_APPLYING,
            ),
        )

        assertTrue(
            engine.state.effectStore.effectsFor(enemy).any {
                it.source == owner && it.skillId == 212257 && it.effectId == 521
            },
            "events=$events effects=${engine.state.effectStore.effectsFor(enemy)}",
        )
        assertTrue(
            engine.state.effectStore.effectsFor(enemy).any {
                it.source == owner && it.skillId == 212257 && it.effectId == 523
            },
            "events=$events effects=${engine.state.effectStore.effectsFor(enemy)}",
        )
        assertTrue(
            events.filterIsInstance<BattleEvent.Recovery>().any {
                it.source == owner && it.skillId == 212257 && it.amount > 0
            },
            "events=$events",
        )
    }

    @Test
    fun `qiqinqizong shares its first seven damage guards across the allied group`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100298, 100, listOf(200298), position = 2),
                    hero(100001, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100298) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = enemy,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.DAMAGE_BEFORE,
            battleView = engine.state.view,
        )
        val protectedTroops = mapOf(
            owner to requireNotNull(engine.state.view.state(owner)).troops,
            ally to requireNotNull(engine.state.view.state(ally)).troops,
        )

        val firstSeven = (0 until 7).map { index ->
            val target = if (index % 2 == 0) owner else ally
            engine.applyChanges(
                listOf(
                    TroopDamageChange(
                        source = enemy,
                        target = target,
                        amount = 100,
                        troopsAfter = requireNotNull(engine.state.view.state(target)).troops - 100,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 900000,
                        effectId = 301,
                    ),
                ),
                context,
            )
        }

        assertTrue(
            firstSeven.all { events -> events.any { it is BattleEvent.Evaded } },
            "events=$firstSeven",
        )
        assertEquals(
            protectedTroops,
            protectedTroops.keys.associateWith {
                requireNotNull(engine.state.view.state(it)).troops
            },
        )

        val eighth = engine.applyChanges(
            listOf(
                TroopDamageChange(
                    source = enemy,
                    target = ally,
                    amount = 100,
                    troopsAfter = protectedTroops.getValue(ally) - 100,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 900000,
                    effectId = 301,
                ),
            ),
            context,
        )

        assertTrue(eighth.none { it is BattleEvent.Evaded }, "events=$eighth")
        assertEquals(
            protectedTroops.getValue(ally) - 100,
            requireNotNull(engine.state.view.state(ally)).troops,
        )
    }

    @Test
    fun `qiqinqizong shares one seven event budget between control and damage`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100298, 100, listOf(200298), position = 2)),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = enemy,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.EFFECT_APPLYING,
            battleView = engine.state.view,
        )

        val control = engine.applyChanges(
            listOf(controlChange(enemy, owner)),
            context,
        )
        val damageEvents = (0 until 6).map {
            engine.applyChanges(
                listOf(
                    TroopDamageChange(
                        source = enemy,
                        target = owner,
                        amount = 100,
                        troopsAfter = requireNotNull(engine.state.view.state(owner)).troops - 100,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 900000,
                        effectId = 301,
                    ),
                ),
                context.copy(trigger = BattleTrigger.DAMAGE_BEFORE),
            )
        }

        assertTrue(
            control.filterIsInstance<BattleEvent.EffectBlocked>()
                .any { it.target == owner && it.blockingEffectId == 118 },
            "events=$control",
        )
        assertTrue(engine.state.effectStore.effectsFor(owner).none { it.effectId == 501 })
        assertTrue(
            damageEvents.all { events -> events.any { it is BattleEvent.Evaded } },
            "events=$damageEvents",
        )
        val troopsBeforeEighth = requireNotNull(engine.state.view.state(owner)).troops

        val eighth = engine.applyChanges(
            listOf(
                TroopDamageChange(
                    source = enemy,
                    target = owner,
                    amount = 100,
                    troopsAfter = troopsBeforeEighth - 100,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 900000,
                    effectId = 301,
                ),
            ),
            context.copy(trigger = BattleTrigger.DAMAGE_BEFORE),
        )

        assertTrue(eighth.none { it is BattleEvent.Evaded }, "events=$eighth")
        assertEquals(
            troopsBeforeEighth - 100,
            requireNotNull(engine.state.view.state(owner)).troops,
        )
    }

    @Test
    fun `qiqinqizong shu branch debuffs the highest damage enemy on the next round`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100582, 100, listOf(200298), position = 2)),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 20, position = 2),
                    hero(200002, 10, position = 1),
                ),
            ),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val highestDamageEnemy = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 1
        }
        val otherEnemy = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 2
        }
        engine.state.recordDamage(highestDamageEnemy, 1_000)
        engine.state.recordDamage(otherEnemy, 100)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = highestDamageEnemy,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.DAMAGE_BEFORE,
            battleView = engine.state.view,
        )

        repeat(7) {
            engine.applyChanges(
                listOf(
                    TroopDamageChange(
                        source = highestDamageEnemy,
                        target = owner,
                        amount = 100,
                        troopsAfter = requireNotNull(engine.state.view.state(owner)).troops - 100,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 900000,
                        effectId = 301,
                    ),
                ),
                context,
            )
        }

        assertTrue(
            engine.state.effectStore.effectsFor(highestDamageEnemy)
                .none { it.skillId == 214298 },
        )

        engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(
                round = 2,
                source = owner,
                trigger = BattleTrigger.ROUND_START,
            ),
        )

        val highestEffects = engine.state.effectStore.effectsFor(highestDamageEnemy)
            .filter { it.skillId == 214298 }
        assertTrue(highestEffects.any { it.effectId == 202 }, "effects=$highestEffects")
        assertTrue(
            highestEffects.none { it.effectId == 532 || it.effectId == 534 },
            "effects=$highestEffects",
        )
        assertTrue(
            engine.state.effectStore.effectsFor(otherEnemy)
                .none { it.skillId == 214298 },
        )
    }

    @Test
    fun `qiqinqizong non shu branch reduces both damage schools on the next round`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100001, 100, listOf(200298), position = 2)),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 20, position = 2),
                    hero(200002, 10, position = 1),
                ),
            ),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val highestDamageEnemy = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 1
        }
        val otherEnemy = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 2
        }
        engine.state.recordDamage(highestDamageEnemy, 1_000)
        engine.state.recordDamage(otherEnemy, 100)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = highestDamageEnemy,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.DAMAGE_BEFORE,
            battleView = engine.state.view,
        )

        repeat(7) {
            engine.applyChanges(
                listOf(
                    TroopDamageChange(
                        source = highestDamageEnemy,
                        target = owner,
                        amount = 100,
                        troopsAfter = requireNotNull(engine.state.view.state(owner)).troops - 100,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 900000,
                        effectId = 301,
                    ),
                ),
                context,
            )
        }

        engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(
                round = 2,
                source = owner,
                trigger = BattleTrigger.ROUND_START,
            ),
        )

        val highestEffects = engine.state.effectStore.effectsFor(highestDamageEnemy)
            .filter { it.skillId == 214298 }
        assertTrue(highestEffects.any { it.effectId == 532 }, "effects=$highestEffects")
        assertTrue(highestEffects.any { it.effectId == 534 }, "effects=$highestEffects")
        assertTrue(highestEffects.none { it.effectId == 202 }, "effects=$highestEffects")
        assertTrue(
            engine.state.effectStore.effectsFor(otherEnemy)
                .none { it.skillId == 214298 },
        )
    }

    @Test
    fun `fuboyangsha converts normal attack uplift into layers and queued extra attacks`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100785, 100, listOf(200255), position = 2),
                    hero(100001, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100785) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        val normalDamageBonus = engine.state.view.activeEffectStrength(ally, 20025525)
        assertTrue(normalDamageBonus > 0)

        engine.trigger(
            BattleTrigger.NORMAL_ATTACK_AFTER,
            context.copy(
                round = 1,
                source = ally,
                trigger = BattleTrigger.NORMAL_ATTACK_AFTER,
            ),
        )

        val upliftNamespace = "skill.200255.normal-damage-uplift"
        val layerNamespace = "skill.200255.yangsha-layers"
        assertEquals(normalDamageBonus % 40, engine.state.runtime.counter(owner, upliftNamespace))
        assertEquals(normalDamageBonus / 40, engine.state.runtime.counter(owner, layerNamespace))

        engine.state.runtime.addCounter(
            owner,
            layerNamespace,
            delta = 8 - engine.state.runtime.counter(owner, layerNamespace),
            maximum = 20,
        )
        engine.trigger(
            BattleTrigger.NORMAL_ATTACK_AFTER,
            context.copy(
                round = 1,
                source = owner,
                trigger = BattleTrigger.NORMAL_ATTACK_AFTER,
            ),
        )

        val progressAfterOwner = (normalDamageBonus % 40) + normalDamageBonus
        val layersBeforeConsumption = 8 + progressAfterOwner / 40
        assertEquals(progressAfterOwner % 40, engine.state.runtime.counter(owner, upliftNamespace))
        assertEquals(layersBeforeConsumption % 4, engine.state.runtime.counter(owner, layerNamespace))
        assertEquals(
            layersBeforeConsumption / 4,
            engine.consumePendingExtraNormalAttacks(owner),
        )
        assertEquals(0, engine.consumePendingExtraNormalAttacks(owner))
    }

    @Test
    fun `fuboyangsha caps accumulated yangsha at twenty layers`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100785, 100, listOf(200255), position = 2),
                    hero(100001, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100785) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        val normalDamageBonus = engine.state.view.activeEffectStrength(ally, 20025525)
        val attacksForTwentyOneLayers =
            (21 * 40 + normalDamageBonus - 1) / normalDamageBonus

        repeat(attacksForTwentyOneLayers) {
            engine.trigger(
                BattleTrigger.NORMAL_ATTACK_AFTER,
                context.copy(
                    round = 1,
                    source = ally,
                    trigger = BattleTrigger.NORMAL_ATTACK_AFTER,
                ),
            )
        }

        assertEquals(
            20,
            engine.state.runtime.counter(owner, "skill.200255.yangsha-layers"),
        )
    }

    @Test
    fun `configured battle consumes fuboyangsha queue as repeated normal attacks`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100785, 300, listOf(200255), position = 2),
                        hero(100001, 200, position = 1),
                        hero(100002, 100, position = 0),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(200001, 10, position = 2).copy(
                            troops = 100_000,
                            maxTroops = 100_000,
                        ),
                    ),
                ),
                maxRounds = 8,
            ),
            config,
            FixedBattleRandom(0),
        )

        val ownerNormalAttacks = result.events
            .filterIsInstance<BattleEvent.NormalAttack>()
            .count { it.source.heroId == BattleHeroId(100785) }
        assertTrue(
            ownerNormalAttacks > 8,
            "normalAttacks=$ownerNormalAttacks",
        )
    }

    @Test
    fun `pibingjuyi grants two birui layers and consumes one before each damage`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100791, 100, listOf(200264), position = 2),
                    hero(100001, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100791) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(99),
            round = 1,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.ROUND_START,
            battleView = engine.state.view,
        )

        engine.trigger(BattleTrigger.ROUND_START, context)

        val layerNamespace = "skill.200264.birui-layers"
        assertEquals(2, engine.state.runtime.counter(owner, layerNamespace))
        assertEquals(2, engine.state.runtime.counter(ally, layerNamespace))
        val losses = (0 until 3).map {
            val troopsBefore = requireNotNull(engine.state.view.state(ally)).troops
            engine.applyChanges(
                listOf(
                    TroopDamageChange(
                        source = enemy,
                        target = ally,
                        amount = 100,
                        troopsAfter = troopsBefore - 100,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.NORMAL,
                        tags = emptySet(),
                        skillId = 0,
                        effectId = 0,
                    ),
                ),
                context.copy(
                    source = enemy,
                    trigger = BattleTrigger.DAMAGE_BEFORE,
                ),
            )
            troopsBefore - requireNotNull(engine.state.view.state(ally)).troops
        }

        assertTrue(losses[0] in 1 until 100, "losses=$losses")
        assertTrue(losses[1] in 1 until 100, "losses=$losses")
        assertEquals(100, losses[2], "losses=$losses")
        assertEquals(0, engine.state.runtime.counter(ally, layerNamespace))
    }

    @Test
    fun `pibingjuyi burn uses fifty percent chance and grows for the same enemy`() {
        fun fixture(randomValue: Int): Pair<DefaultCompleteSkillEngine, SkillBattleContext> {
            val request = BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100791, 100, listOf(200264), position = 2),
                        hero(100001, 90, position = 1),
                    ),
                ),
                defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
                maxRounds = 2,
            )
            val engine = DefaultCompleteSkillEngine.create(request, config)
            val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100791) }
            val context = SkillBattleContext(
                request = request,
                runtime = engine.state.runtime,
                random = FixedBattleRandom(randomValue),
                round = 1,
                source = owner,
                rootSkillId = 0,
                currentSkillId = 0,
                trigger = BattleTrigger.ROUND_START,
                battleView = engine.state.view,
            )
            engine.trigger(BattleTrigger.ROUND_START, context)
            return engine to context
        }

        fun applyHit(
            engine: DefaultCompleteSkillEngine,
            context: SkillBattleContext,
        ) {
            val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
            val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
            val troopsBefore = requireNotNull(engine.state.view.state(ally)).troops
            engine.applyChanges(
                listOf(
                    TroopDamageChange(
                        source = enemy,
                        target = ally,
                        amount = 100,
                        troopsAfter = troopsBefore - 100,
                        school = DamageSchool.PHYSICAL,
                        origin = DamageOrigin.NORMAL,
                        tags = emptySet(),
                        skillId = 0,
                        effectId = 0,
                    ),
                ),
                context.copy(
                    source = enemy,
                    trigger = BattleTrigger.DAMAGE_BEFORE,
                ),
            )
        }

        val (successEngine, successContext) = fixture(0)
        val successOwner = successEngine.state.view.heroes()
            .single { it.heroId == BattleHeroId(100791) }
        val successEnemy = successEngine.state.view.heroes().single { it.side == Side.DEFENDER }
        applyHit(successEngine, successContext)
        val firstBurn = successEngine.state.effectStore.effectsFor(successEnemy)
            .single { it.skillId == 216264 && it.effectId == 305 }
        val growthAfterFirst = successEngine.state.runtime.counter(
            successEnemy,
            "skill.200264.burn-growth",
        )
        assertTrue(growthAfterFirst > 0, "growth=$growthAfterFirst")

        successEngine.trigger(
            BattleTrigger.ROUND_START,
            successContext.copy(
                round = 2,
                source = successOwner,
                trigger = BattleTrigger.ROUND_START,
            ),
        )
        applyHit(successEngine, successContext.copy(round = 2))
        val secondBurn = successEngine.state.effectStore.effectsFor(successEnemy)
            .single { it.skillId == 216264 && it.effectId == 305 }
        assertTrue(
            secondBurn.effectiveStrength > firstBurn.effectiveStrength,
            "first=${firstBurn.effectiveStrength} second=${secondBurn.effectiveStrength}",
        )

        val (failedEngine, failedContext) = fixture(99)
        val failedEnemy = failedEngine.state.view.heroes().single { it.side == Side.DEFENDER }
        applyHit(failedEngine, failedContext)
        assertTrue(
            failedEngine.state.effectStore.effectsFor(failedEnemy)
                .none { it.skillId == 216264 && it.effectId == 305 },
        )
    }

    @Test
    fun `marker effects become queryable runtime state for later skill branches`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100017, 100, listOf(200017), position = 2)),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.recordTarget(source, target)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 200017,
            currentSkillId = 200017,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )

        engine.trigger(BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)

        assertTrue(engine.state.runtime.hasMarker(source, 21001701, round = 1))
    }

    @Test
    fun `huangyi registers emergency recovery without healing during preparation`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100016, 100, listOf(200016), position = 2).copy(
                        troops = 9_000,
                        maxTroops = 10_000,
                    ),
                    hero(100017, 90, position = 1).copy(
                        troops = 9_000,
                        maxTroops = 10_000,
                    ),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == BattleHeroId(100016) }
        engine.state.view.heroes()
            .filter { it.side == Side.ATTACKER }
            .forEach { engine.state.mutable(it).woundedTroops = 1_000 }
        val troopsBefore = engine.state.view.heroes()
            .filter { it.side == Side.ATTACKER }
            .associateWith { requireNotNull(engine.state.view.state(it)).troops }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(99),
            round = 0,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)

        assertEquals(
            troopsBefore,
            engine.state.view.heroes()
                .filter { it.side == Side.ATTACKER }
                .associateWith { requireNotNull(engine.state.view.state(it)).troops },
        )
        assertEquals(0, engine.state.runtime.count(source, BattleTrigger.RECOVERY_AFTER))
        engine.state.view.heroes()
            .filter { it.side == Side.ATTACKER }
            .forEach { target ->
                assertTrue(
                    engine.state.effectStore.effectsFor(target).any {
                        it.source == source && it.skillId == 200016 && it.effectId == 401
                    },
                    "missing huangyi emergency-recovery registration for $target",
                )
            }
    }

    @Test
    fun `huangyi heals a registered ally after damage and counts recoveries on liubei`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100016, 100, listOf(200016), position = 2).copy(troops = 9_000),
                    hero(100017, 90, position = 1).copy(troops = 9_000),
                    hero(100018, 80, position = 0).copy(troops = 9_000),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == BattleHeroId(100016) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        val events = engine.applyNormalDamage(
            round = 1,
            source = enemy,
            target = ally,
            amount = 600,
            context = context.copy(round = 1, source = enemy),
        )

        assertTrue(
            events.filterIsInstance<BattleEvent.Recovery>().any {
                it.source == source && it.target == ally && it.skillId == 200016 && it.amount > 0
            },
        )
        assertEquals(1, engine.state.runtime.count(source, BattleTrigger.RECOVERY_AFTER))
        assertEquals(0, engine.state.runtime.count(ally, BattleTrigger.RECOVERY_AFTER))
    }

    @Test
    fun `huangyi increases its chance after every three actual ally recoveries`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100016, 100, listOf(200016), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 8,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == BattleHeroId(100016) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)

        val events = buildList {
            repeat(6) {
                addAll(
                    engine.applyNormalDamage(
                        round = it + 1,
                        source = enemy,
                        target = ally,
                        amount = 100,
                        context = context.copy(round = it + 1, source = enemy),
                    ),
                )
            }
        }

        assertEquals(6, engine.state.runtime.count(source, BattleTrigger.RECOVERY_AFTER))
        assertEquals(
            2,
            events.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 211016 },
        )
    }

    @Test
    fun `baizhan spends one initial stack for round start recovery`() {
        val ownerHero = hero(100252, 200, listOf(200252), position = 1).copy(
            troops = 9_000,
            maxTroops = 10_000,
            skillLevels = listOf(10),
        )
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero)),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_PASSIVE,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)
        engine.state.mutable(owner).woundedTroops = 1_000
        val events = engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 1, trigger = BattleTrigger.ROUND_START),
        )

        assertEquals(2, engine.state.runtime.counter(owner, "skill.200252.stacks"))
        assertTrue(events.any {
            it is BattleEvent.Recovery &&
                it.source == owner &&
                it.target == owner &&
                it.skillId == 214252 &&
                it.amount > 0
        })
    }

    @Test
    fun `baizhan spends one stack and recovers after receiving damage`() {
        val ownerHero = hero(100252, 200, listOf(200252), position = 1).copy(
            troops = 9_000,
            maxTroops = 10_000,
            skillLevels = listOf(10),
        )
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero)),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_PASSIVE,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)

        val events = engine.applyNormalDamage(
            round = 1,
            source = enemy,
            target = owner,
            amount = 100,
            context = context.copy(round = 1, source = enemy),
        )

        assertEquals(2, engine.state.runtime.counter(owner, "skill.200252.stacks"))
        assertTrue(events.any {
            it is BattleEvent.Recovery &&
                it.source == owner &&
                it.target == owner &&
                it.skillId == 214252 &&
                it.amount > 0
        })
    }

    @Test
    fun `baizhan replenishes one stack after dealing damage up to three`() {
        val ownerHero = hero(100252, 200, listOf(200252), position = 1).copy(
            troops = 9_000,
            maxTroops = 10_000,
            skillLevels = listOf(10),
        )
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero)),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_PASSIVE,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        engine.state.mutable(owner).woundedTroops = 1_000
        engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 1, trigger = BattleTrigger.ROUND_START),
        )

        repeat(2) {
            engine.applyNormalDamage(
                round = 1,
                source = owner,
                target = enemy,
                amount = 1,
                context = context.copy(round = 1, source = owner),
            )
        }

        assertEquals(3, engine.state.runtime.counter(owner, "skill.200252.stacks"))
    }

    @Test
    fun `bingzhe listener fires once after three active or pursuit attempts`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(
                        100253,
                        100,
                        listOf(200253, 200001, 200002, 200251),
                        position = 2,
                    ),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val base = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )

        val events = engine.trigger(BattleTrigger.ACTIVE_SKILL_ATTEMPT, base) +
            engine.trigger(
                BattleTrigger.PURSUIT_ATTEMPT,
                base.copy(trigger = BattleTrigger.PURSUIT_ATTEMPT),
            )

        assertEquals(3, engine.state.runtime.attemptCount(source, BattleTrigger.ACTIVE_SKILL_ATTEMPT) +
            engine.state.runtime.attemptCount(source, BattleTrigger.PURSUIT_ATTEMPT))
        assertEquals(
            1,
            events.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 211253 },
        )
    }

    @Test
    fun `zhengshi waits until the owners next action after fifteen enemy damage events`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100701, 100, listOf(200244), position = 2)),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val roundOne = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = enemy,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_AFTER,
            battleView = engine.state.view,
        )
        repeat(15) { engine.recordDamageThresholds(enemy, roundOne) }

        assertTrue(
            engine.trigger(
                BattleTrigger.ACTION_BEFORE,
                roundOne.copy(source = owner, trigger = BattleTrigger.ACTION_BEFORE),
            ).none { it is BattleEvent.SkillTriggered && it.skillId == 213244 },
        )
        val roundTwo = roundOne.copy(
            round = 2,
            source = owner,
            trigger = BattleTrigger.ACTION_BEFORE,
        )
        val first = engine.trigger(BattleTrigger.ACTION_BEFORE, roundTwo)
        val repeated = engine.trigger(BattleTrigger.ACTION_BEFORE, roundTwo)

        assertEquals(1, first.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 213244 })
        assertTrue(repeated.none { it is BattleEvent.SkillTriggered && it.skillId == 213244 })
    }

    @Test
    fun `xinzhan lowers each allied damage target morale for only the first nine hits`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100275, 100, listOf(200275), position = 2),
                    hero(100001, 90, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 20, position = 2),
                    hero(200002, 10, position = 1),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val attacker = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val firstTarget = engine.state.view.heroes().single { it.heroId == BattleHeroId(200001) }
        val secondTarget = engine.state.view.heroes().single { it.heroId == BattleHeroId(200002) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = attacker,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.NORMAL_ATTACK_BEFORE,
            battleView = engine.state.view,
        )

        repeat(8) {
            engine.applyNormalDamage(1, attacker, firstTarget, 1, context)
        }
        engine.applyNormalDamage(1, attacker, secondTarget, 1, context)
        engine.applyNormalDamage(1, attacker, secondTarget, 1, context)

        assertEquals(60, engine.state.view.currentMorale(firstTarget))
        assertEquals(95, engine.state.view.currentMorale(secondTarget))
    }

    @Test
    fun `xinzhan damage limit is isolated by side`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100275, 100, listOf(200275), position = 2)),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200275, 90, listOf(200275), position = 2),
                    hero(200001, 80, position = 1),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val attacker = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val defender = engine.state.view.heroes().single { it.heroId == BattleHeroId(200275) }
        val defenderAlly = engine.state.view.heroes().single { it.heroId == BattleHeroId(200001) }
        val base = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = attacker,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.NORMAL_ATTACK_BEFORE,
            battleView = engine.state.view,
        )

        repeat(9) { engine.applyNormalDamage(1, attacker, defender, 1, base) }
        engine.applyNormalDamage(1, defenderAlly, attacker, 1, base.copy(source = defenderAlly))

        assertEquals(55, engine.state.view.currentMorale(defender))
        assertEquals(95, engine.state.view.currentMorale(attacker))
    }

    @Test
    fun `shoujing triggers its configured children once at rounds six and eight`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100277, 100, listOf(200277), position = 2)),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 8,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 5,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.ROUND_START,
            battleView = engine.state.view,
        )

        val roundFive = engine.trigger(BattleTrigger.ROUND_START, context)
        val roundSix = engine.trigger(BattleTrigger.ROUND_START, context.copy(round = 6))
        val repeatedSix = engine.trigger(BattleTrigger.ROUND_START, context.copy(round = 6))
        val roundEight = engine.trigger(BattleTrigger.ROUND_START, context.copy(round = 8))

        assertTrue(roundFive.none { it is BattleEvent.SkillTriggered && it.skillId in setOf(210277, 211277) })
        assertEquals(1, roundSix.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 210277 })
        assertTrue(repeatedSix.none { it is BattleEvent.SkillTriggered && it.skillId == 210277 })
        assertEquals(1, roundEight.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 211277 })
    }

    @Test
    fun `huiyan grants its team effects once after six allied damage events`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100294, 100, listOf(200294), position = 2),
                    hero(100001, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100294) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = ally,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.NORMAL_ATTACK_BEFORE,
            battleView = engine.state.view,
        )

        repeat(5) { engine.applyNormalDamage(1, ally, enemy, 1, context) }
        assertEquals(100, engine.state.view.currentMorale(owner))
        assertEquals(100, engine.state.view.currentMorale(ally))

        val sixth = engine.applyNormalDamage(1, ally, enemy, 1, context)
        engine.applyNormalDamage(1, ally, enemy, 1, context)

        assertEquals(100, engine.state.view.currentMorale(owner))
        assertEquals(106, engine.state.view.currentMorale(ally))
        assertEquals(1, sixth.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 211294 })
    }

    @Test
    fun `manwang counter chain triggers on each fifth actual hit to its owner`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100297, 100, listOf(200297), position = 2),
                    hero(100001, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100297) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = enemy,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.NORMAL_ATTACK_BEFORE,
            battleView = engine.state.view,
        )

        repeat(4) { engine.applyNormalDamage(1, enemy, owner, 1, context) }
        engine.applyNormalDamage(1, enemy, ally, 1, context)
        val fifth = engine.applyNormalDamage(1, enemy, owner, 1, context)
        val sixth = engine.applyNormalDamage(1, enemy, owner, 1, context)

        assertEquals(1, fifth.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 211297 })
        assertTrue(sixth.none { it is BattleEvent.SkillTriggered && it.skillId == 211297 })
    }

    @Test
    fun `sanjunduoshuai registers at preparation and responds once after a normal attack`() {
        val owner = hero(100705, 100, listOf(200987), position = 2)
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(owner)),
                defender = BattleTeam(
                    listOf(
                        hero(200001, 10, position = 2).copy(
                            troops = 100_000,
                            maxTroops = 100_000,
                        ),
                    ),
                ),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )
        val ownerRef = BattleHeroRef(Side.ATTACKER, owner.position, owner.id)

        assertEquals(
            1,
            result.events.filterIsInstance<BattleEvent.SkillTriggered>()
                .count { it.round == 0 && it.source == ownerRef && it.skillId == 200987 },
        )
        assertTrue(result.events.filterIsInstance<BattleEvent.SkillDamage>().none {
            it.round == 0 && it.source == ownerRef && it.skillId == 211987
        })
        assertEquals(
            1,
            result.events.filterIsInstance<BattleEvent.SkillTriggered>()
                .count { it.round == 1 && it.source == ownerRef && it.skillId == 211987 },
        )
        assertTrue(result.events.filterIsInstance<BattleEvent.SkillDamage>().any {
            it.round == 1 && it.source == ownerRef && it.skillId == 211987
        })
    }

    @Test
    fun `prepared command control registers its per round probability without rolling at setup`() {
        val owner = hero(100001, 100, listOf(200228), position = 2).copy(
            skillLevels = listOf(10),
        )
        val request = BattleRequest(
            attacker = BattleTeam(listOf(owner)),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 20, position = 1),
                    hero(200003, 10, position = 0),
                ),
            ),
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val ownerRef = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(99),
            round = 0,
            source = ownerRef,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)

        val registrations = engine.state.view.heroes()
            .filter { it.side == Side.DEFENDER }
            .flatMap(engine.state.effectStore::effectsFor)
            .filter { it.detailId == 20022801 }
        assertEquals(2, registrations.size)
        assertTrue(registrations.all { it.strength == 90 })
    }

    @Test
    fun `qibu recovers allies on every seventh team normal or skill attempt`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100950, 100, listOf(200950), position = 2),
                    hero(100001, 90, position = 1).copy(troops = 9_000),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100950) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100001) }
        engine.state.mutable(ally).woundedTroops = 1_000
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = ally,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.NORMAL_ATTACK_AFTER,
            battleView = engine.state.view,
        )

        repeat(6) {
            engine.state.runtime.recordBattleTriggerOccurrence(ally, BattleTrigger.NORMAL_ATTACK_AFTER)
            engine.trigger(BattleTrigger.NORMAL_ATTACK_AFTER, context)
        }
        engine.state.runtime.recordBattleTriggerOccurrence(ally, BattleTrigger.NORMAL_ATTACK_AFTER)
        val seventh = engine.trigger(BattleTrigger.NORMAL_ATTACK_AFTER, context)
        engine.state.runtime.recordBattleTriggerOccurrence(owner, BattleTrigger.NORMAL_ATTACK_AFTER)
        val eighth = engine.trigger(BattleTrigger.NORMAL_ATTACK_AFTER, context.copy(source = owner))

        assertEquals(1, seventh.filterIsInstance<BattleEvent.SkillTriggered>().count { it.skillId == 212950 })
        assertTrue(eighth.none { it is BattleEvent.SkillTriggered && it.skillId == 212950 })
        assertTrue(requireNotNull(engine.state.view.state(ally)).troops > 9_000)
    }

    @Test
    fun `huangtian recovers its caster only after its own sorcery damage ticks`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100008, 100, listOf(200008), position = 2).copy(troops = 9_000),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.state.mutable(source).woundedTroops = 1_000
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 200008,
            currentSkillId = 200008,
            trigger = BattleTrigger.ROUND_START,
            battleView = engine.state.view,
        )
        val spec = PersistentEffectSpec(
            source = source,
            target = target,
            rootSkillId = 200008,
            skillId = 200008,
            skillKind = SkillKind.ACTIVE,
            rawSkillType = 3,
            detailId = 20000811,
            effectId = 306,
            category = com.stzb.server.game.battle.EffectCategory.HARMFUL,
            conflict = 306,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = 2,
            availableHit = 0,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = TypedBattlePotency.rate(40),
        )
        engine.applyChanges(
            listOf(
                ScheduledDamageEffectChange(
                    spec = spec,
                    school = DamageSchool.STRATEGY,
                    origin = DamageOrigin.ACTIVE,
                    tags = setOf(com.stzb.server.game.battle.DamageTag.ONGOING),
                    status = com.stzb.server.game.battle.BattleStatus.HEX,
                    coefficientSource = BattleCoefficientSource.STRATEGY,
                    rawCoefficient = 350,
                    calculationTypes = emptyList(),
                ),
            ),
            context,
        )

        engine.trigger(BattleTrigger.ROUND_START, context)

        assertTrue(requireNotNull(engine.state.view.state(source)).troops > 9_000)
    }

    @Test
    fun `xianming follows only the first ongoing damage suffered by each enemy in a round`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100784, 100, listOf(200254), position = 2)),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 20, position = 2),
                    hero(200002, 10, position = 1),
                ),
            ),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val targets = engine.state.view.heroes().filter { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_AFTER,
            battleView = engine.state.view,
        )

        val events = buildList {
            repeat(2) {
                addAll(
                    engine.applyChanges(
                        listOf(ongoingHit(owner, targets[0])),
                        context,
                    ),
                )
            }
            addAll(
                engine.applyChanges(
                    listOf(ongoingHit(owner, targets[1])),
                    context,
                ),
            )
        }

        assertEquals(
            2,
            events.filterIsInstance<BattleEvent.SkillDamage>().count { it.skillId == 212254 },
        )
    }

    @Test
    fun `xianming immediately ticks an accepted ongoing effect from round three`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100784, 100, listOf(200254), position = 2)),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 3,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val before = requireNotNull(engine.state.view.state(target)).troops
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 3,
            source = owner,
            rootSkillId = 200254,
            currentSkillId = 200254,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.applyChanges(
            listOf(ongoingDamage(owner, target, detailId = 900011)),
            context,
        )

        assertTrue(requireNotNull(engine.state.view.state(target)).troops < before)
    }

    @Test
    fun `xianming does not immediately tick a conflict rejected ongoing effect`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100784, 100, listOf(200254), position = 2)),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 3,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 2,
            source = owner,
            rootSkillId = 200254,
            currentSkillId = 200254,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.applyChanges(
            listOf(ongoingDamage(owner, target, detailId = 900021)),
            context,
        )
        val beforeRejected = requireNotNull(engine.state.view.state(target)).troops

        engine.applyChanges(
            listOf(ongoingDamage(owner, target, detailId = 900022)),
            context.copy(round = 3),
        )

        assertEquals(beforeRejected, requireNotNull(engine.state.view.state(target)).troops)
    }

    @Test
    fun `qixurulin splashes strategy damage only to enemies adjacent to the original target`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100282, 100, listOf(200282), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 20, position = 1),
                    hero(200003, 10, position = 0),
                ),
            ),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        val original = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 1
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.DAMAGE_AFTER,
            battleView = engine.state.view,
        )

        val events = engine.applyChanges(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = original,
                    amount = 200,
                    troopsAfter = 9_800,
                    school = DamageSchool.STRATEGY,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 900000,
                    effectId = 302,
                ),
            ),
            context,
        )

        val splash = events.filterIsInstance<BattleEvent.SkillDamage>()
            .filter { it.skillId == 210282 }
        assertEquals(setOf(0, 2), splash.mapTo(linkedSetOf()) { it.target.position })
        assertTrue(splash.none { it.target == original })
    }

    @Test
    fun `qixurulin value progression advances once at each round end`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100282, 100, listOf(200282), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.heroId == BattleHeroId(100282)
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)

        assertEquals(
            0,
            engine.state.runtime.referencedValueDelta(owner, 200282, 20028212),
        )
        engine.finishRound(1)
        val firstIncrease =
            engine.state.runtime.referencedValueDelta(owner, 200282, 20028212)
        assertTrue(firstIncrease > 0)
        engine.finishRound(1)
        assertEquals(
            firstIncrease,
            engine.state.runtime.referencedValueDelta(owner, 200282, 20028212),
        )
        engine.finishRound(2)
        assertEquals(
            firstIncrease * 2,
            engine.state.runtime.referencedValueDelta(owner, 200282, 20028212),
        )
    }

    @Test
    fun `qixurulin command setup does not execute its conditional damage template`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100282, 100, listOf(200282), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 1),
                    hero(200002, 20, position = 0),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.heroId == BattleHeroId(100282)
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        val troopsBefore = engine.state.view.heroes().associateWith {
            requireNotNull(engine.state.view.state(it)).troops
        }

        val events = engine.prepareBattle(context)

        assertEquals(
            troopsBefore,
            engine.state.view.heroes().associateWith {
                requireNotNull(engine.state.view.state(it)).troops
            },
            events.joinToString(separator = "\n"),
        )
    }

    @Test
    fun `jade seal releases the previous round absorption at its progressing percentage`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100262, 200, listOf(200262), position = 2),
                    hero(100001, 100, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(hero(200001, 10, position = 2)),
            ),
            maxRounds = 3,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val ally = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 1
        }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        val prepareEvents = engine.prepareBattle(context)

        assertTrue(
            prepareEvents.filterIsInstance<BattleEvent.SkillDamage>()
                .none { it.skillId == 211262 },
            "events=$prepareEvents",
        )
        engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 1, trigger = BattleTrigger.ROUND_START),
        )
        val allyBeforeRoundOneDamage = requireNotNull(engine.state.view.state(ally)).troops
        engine.applyNormalDamage(
            round = 1,
            source = enemy,
            target = ally,
            amount = 100,
            context = context.copy(round = 1, source = enemy),
        )
        val allyAfterRoundOneDamage = requireNotNull(engine.state.view.state(ally)).troops
        val roundOneAbsorbed = 100 - (allyBeforeRoundOneDamage - allyAfterRoundOneDamage)
        assertTrue(roundOneAbsorbed > 0)
        engine.finishRound(1)
        assertEquals(
            0,
            engine.state.runtime.referencedValueDelta(owner, 200262, 21126204),
        )

        val roundTwo = engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 2, trigger = BattleTrigger.ROUND_START),
        )

        assertEquals(
            roundOneAbsorbed * 50 / 100,
            roundTwo.filterIsInstance<BattleEvent.SkillDamage>()
                .single { it.skillId == 211262 }
                .damage,
        )
        val allyBeforeRoundTwoDamage = requireNotNull(engine.state.view.state(ally)).troops
        engine.applyNormalDamage(
            round = 2,
            source = enemy,
            target = ally,
            amount = 100,
            context = context.copy(round = 2, source = enemy),
        )
        val allyAfterRoundTwoDamage = requireNotNull(engine.state.view.state(ally)).troops
        val roundTwoAbsorbed = 100 - (allyBeforeRoundTwoDamage - allyAfterRoundTwoDamage)
        engine.finishRound(2)
        assertEquals(
            10,
            engine.state.runtime.referencedValueDelta(owner, 200262, 21126204),
        )

        val roundThree = engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 3, trigger = BattleTrigger.ROUND_START),
        )

        assertEquals(
            roundTwoAbsorbed * 60 / 100,
            roundThree.filterIsInstance<BattleEvent.SkillDamage>()
                .single { it.skillId == 211262 }
                .damage,
        )
    }

    @Test
    fun `jade seal release does not trigger qixurulin splash onto adjacent allies`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100262, 200, listOf(200262), position = 2),
                    hero(100282, 100, listOf(200282), position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(hero(200001, 10, position = 2)),
            ),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val jadeSealOwner = engine.state.view.heroes().single {
            it.heroId == BattleHeroId(100262)
        }
        val qixurulinOwner = engine.state.view.heroes().single {
            it.heroId == BattleHeroId(100282)
        }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = jadeSealOwner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 1, trigger = BattleTrigger.ROUND_START),
        )
        engine.applyNormalDamage(
            round = 1,
            source = enemy,
            target = qixurulinOwner,
            amount = 100,
            context = context.copy(round = 1, source = enemy),
        )
        engine.finishRound(1)
        val qixurulinTroopsBeforeRelease =
            requireNotNull(engine.state.view.state(qixurulinOwner)).troops

        val roundTwo = engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 2, trigger = BattleTrigger.ROUND_START),
        )

        assertEquals(
            1,
            roundTwo.filterIsInstance<BattleEvent.SkillDamage>()
                .count { it.skillId == 211262 },
            "events=$roundTwo",
        )
        assertTrue(
            roundTwo.filterIsInstance<BattleEvent.SkillDamage>()
                .none { it.skillId == 210282 },
            "events=$roundTwo",
        )
        assertEquals(
            qixurulinTroopsBeforeRelease,
            requireNotNull(engine.state.view.state(qixurulinOwner)).troops,
            "events=$roundTwo",
        )
    }

    @Test
    fun `jade seal does not release damage that activates in the current round start`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100262, 200, listOf(200262), position = 2),
                    hero(100001, 100, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(hero(200001, 10, position = 2)),
            ),
            maxRounds = 3,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val ally = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 1
        }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 1, trigger = BattleTrigger.ROUND_START),
        )
        engine.schedule(
            ScheduledTimingChange(
                snapshot = DelayedEffect(
                    source = enemy,
                    rootSkillId = 10,
                    skillId = 10,
                    detailId = 1001,
                    dueRound = 0,
                ),
                delayRound = 1,
                delayHit = 0,
                change = TroopDamageChange(
                    source = enemy,
                    target = ally,
                    amount = 100,
                    troopsAfter = 9_900,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 10,
                    effectId = 301,
                ),
            ),
            round = 1,
        )
        engine.finishRound(1)
        val allyBefore = requireNotNull(engine.state.view.state(ally)).troops

        val roundTwo = engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 2, trigger = BattleTrigger.ROUND_START),
        )

        assertTrue(
            roundTwo.filterIsInstance<BattleEvent.SkillDamage>()
                .none { it.skillId == 211262 },
            "events=$roundTwo",
        )
        val allyAfter = requireNotNull(engine.state.view.state(ally)).troops
        val absorbed = 100 - (allyBefore - allyAfter)
        assertTrue(absorbed > 0)
        engine.finishRound(2)

        val roundThree = engine.trigger(
            BattleTrigger.ROUND_START,
            context.copy(round = 3, trigger = BattleTrigger.ROUND_START),
        )

        assertEquals(
            absorbed * 60 / 100,
            roundThree.filterIsInstance<BattleEvent.SkillDamage>()
                .single { it.skillId == 211262 }
                .damage,
        )
    }

    @Test
    fun `qixurulin splash consumes its current referenced value`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100282, 100, listOf(200282), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 1),
                    hero(200002, 20, position = 0),
                ),
            ),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.heroId == BattleHeroId(100282)
        }
        val source = engine.state.view.heroes().single {
            it.heroId == BattleHeroId(100017)
        }
        val original = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 1
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)

        fun splashDamage(round: Int): Int {
            val troops = requireNotNull(engine.state.view.state(original)).troops
            return engine.applyChanges(
                listOf(
                    TroopDamageChange(
                        source = source,
                        target = original,
                        amount = 1_000,
                        troopsAfter = troops - 1_000,
                        school = DamageSchool.STRATEGY,
                        origin = DamageOrigin.ACTIVE,
                        tags = emptySet(),
                        skillId = 900000,
                        effectId = 302,
                    ),
                ),
                context.copy(round = round, source = source),
            ).filterIsInstance<BattleEvent.SkillDamage>()
                .single { it.skillId == 210282 }
                .damage
        }

        val roundOne = splashDamage(1)
        engine.finishRound(1)
        val increase =
            engine.state.runtime.referencedValueDelta(owner, 200282, 20028212)
        val roundTwo = splashDamage(2)

        assertEquals(roundOne + 1_000 * increase / 100, roundTwo)
    }

    @Test
    fun `juxian reacts before successful ally increases and enemy decreases from round one`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100269, 100, listOf(200269), position = 2),
                    hero(100017, 90, position = 1).copy(troops = 9_000),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100269) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.state.mutable(ally).woundedTroops = 1_000
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = owner,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.EFFECT_APPLYING,
            battleView = engine.state.view,
        )

        val allyEvents = engine.applyChanges(
            listOf(statChange(owner, ally, 101, 10)),
            context,
        )
        val enemyEvents = engine.applyChanges(
            listOf(statChange(owner, enemy, 201, -10)),
            context,
        )

        assertTrue(allyEvents.filterIsInstance<BattleEvent.Recovery>().any { it.target == ally })
        assertTrue(
            enemyEvents.filterIsInstance<BattleEvent.SkillDamage>()
                .any { it.skillId == 214269 && it.target == enemy },
        )
    }

    @Test
    fun `juxian does not react to setup round stat changes`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100269, 100, listOf(200269), position = 2),
                    hero(100017, 90, position = 1).copy(troops = 9_000),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100269) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        engine.state.mutable(ally).woundedTroops = 1_000
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        val events = engine.applyChanges(
            listOf(statChange(owner, ally, 101, 10)),
            context,
        )

        assertTrue(events.none { it is BattleEvent.Recovery })
    }

    @Test
    fun `chijie raises source offense and target defense before damage is applied`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100989, 100, listOf(200989), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(100989, 20, listOf(200989), position = 2),
                    hero(200001, 10, position = 1),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        val target = engine.state.view.heroes().single { it.heroId == BattleHeroId(200001) }
        val sourceBefore = requireNotNull(engine.state.view.state(source)).stats
        val targetBefore = requireNotNull(engine.state.view.state(target)).stats
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 900000,
            currentSkillId = 900000,
            trigger = BattleTrigger.DAMAGE_BEFORE,
            battleView = engine.state.view,
        )

        val events = engine.applyChanges(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = target,
                    amount = 200,
                    troopsAfter = 9_800,
                    school = DamageSchool.STRATEGY,
                    origin = DamageOrigin.ACTIVE,
                    tags = emptySet(),
                    skillId = 900000,
                    effectId = 302,
                ),
            ),
            context,
        )

        val damageIndex = events.indexOfFirst { it is BattleEvent.SkillDamage }
        val statIndices = events.mapIndexedNotNull { index, event ->
            index.takeIf { event is BattleEvent.StatChanged }
        }
        assertTrue(statIndices.isNotEmpty())
        assertTrue(statIndices.all { it < damageIndex })
        assertTrue(requireNotNull(engine.state.view.state(source)).stats.strategy > sourceBefore.strategy)
        assertTrue(requireNotNull(engine.state.view.state(target)).stats.defense > targetBefore.defense)
    }

    @Test
    fun `chijie attack increase uses its configured owner attack coefficient`() {
        val ownerHero = hero(100989, 100, listOf(200989), position = 2).copy(
            stats = BattleStats.fromHundredths(
                attack = 28_690,
                defense = 20_000,
                strategy = 29_840,
                speed = 10_000,
                siege = 0,
                hitRange = 5,
            ),
            skillLevels = listOf(10),
        )
        val sourceHero = hero(100705, 90, position = 1).copy(
            stats = BattleStats.fromHundredths(
                attack = 19_260,
                defense = 19_160,
                strategy = 26_930,
                speed = 12_980,
                siege = 2_720,
                hitRange = 4,
            ),
        )
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero, sourceHero)),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == sourceHero.id }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_BEFORE,
            battleView = engine.state.view,
        )

        val events = engine.applyChanges(
            listOf(physicalDamage(source, target, 1)),
            context,
        )

        val increase = events.filterIsInstance<BattleEvent.StatChanged>().single {
            it.target == source && it.skillId == 213989
        }
        assertEquals(
            53.035,
            engine.state.effectStore.effectsFor(source)
                .single { it.detailId == 21398901 }
                .effectiveStrengthExact,
            0.001,
        )
        assertEquals(53.04, increase.deltaExact, 0.001)
    }

    @Test
    fun `chijie configured add count allows four total stat stacks`() {
        val ownerHero = hero(100989, 100, listOf(200989), position = 2).copy(
            stats = BattleStats.fromHundredths(
                attack = 28_690,
                defense = 20_000,
                strategy = 29_840,
                speed = 10_000,
                siege = 0,
                hitRange = 5,
            ),
            skillLevels = listOf(10),
        )
        val sourceHero = hero(100705, 90, position = 1).copy(
            stats = BattleStats.fromHundredths(
                attack = 19_260,
                defense = 19_160,
                strategy = 26_930,
                speed = 12_980,
                siege = 2_720,
                hitRange = 4,
            ),
        )
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero, sourceHero)),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == sourceHero.id }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_BEFORE,
            battleView = engine.state.view,
        )

        repeat(5) {
            engine.applyChanges(
                listOf(physicalDamage(source, target, 1)),
                context,
            )
        }

        assertEquals(
            404.74,
            requireNotNull(engine.state.view.state(source)).stats.precise(BattleStat.ATTACK),
            0.001,
        )
        assertEquals(
            4,
            engine.state.effectStore.effectsFor(source)
                .single { it.detailId == 21398901 }
                .stacks,
        )
    }

    @Test
    fun `chijie source increase affects the skill damage being applied`() {
        val ownerHero = hero(100989, 100, listOf(200989), position = 2).copy(
            stats = BattleStats(attack = 200, defense = 100, strategy = 100, speed = 100, siege = 0, hitRange = 5),
            skillLevels = listOf(10),
        )
        val actorHero = hero(100705, 90, listOf(200987), position = 1).copy(
            skillLevels = listOf(10),
        )
        val targetHero = hero(200001, 10, position = 2)
        val request = BattleRequest(
            attacker = BattleTeam(listOf(ownerHero, actorHero)),
            defender = BattleTeam(listOf(targetHero)),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.heroId == actorHero.id }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = actor,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.NORMAL_ATTACK_AFTER,
            battleView = engine.state.view,
        )

        val events = engine.trigger(BattleTrigger.NORMAL_ATTACK_AFTER, context)

        val increaseIndex = events.indexOfFirst {
            it is BattleEvent.StatChanged && it.skillId == 213989 && it.target == actor
        }
        val damageIndex = events.indexOfFirst {
            it is BattleEvent.SkillDamage && it.skillId == 211987
        }
        assertTrue(increaseIndex in 0 until damageIndex)
        assertEquals(
            BattleDamageCalculator.physical(
                source = actorHero.copy(
                    stats = BattleStats(
                        attack = 140,
                        defense = 100,
                        strategy = 100,
                        speed = 90,
                        siege = 0,
                        hitRange = 5,
                    ),
                ),
                target = targetHero,
                ratePercent = 180,
                attributeRandomTenths = 30,
                origin = DamageOrigin.PASSIVE,
            ),
            (events[damageIndex] as BattleEvent.SkillDamage).damage,
        )
    }

    @Test
    fun `chijie chooses attack instead of strategy for physical damage`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100989, 100, listOf(200989), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val before = requireNotNull(engine.state.view.state(source)).stats
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_BEFORE,
            battleView = engine.state.view,
        )

        engine.applyChanges(
            listOf(
                TroopDamageChange(
                    source = source,
                    target = target,
                    amount = 200,
                    troopsAfter = 9_800,
                    school = DamageSchool.PHYSICAL,
                    origin = DamageOrigin.NORMAL,
                    tags = emptySet(),
                    skillId = 0,
                    effectId = 0,
                ),
            ),
            context,
        )

        val after = requireNotNull(engine.state.view.state(source)).stats
        assertTrue(after.attack > before.attack)
        assertEquals(before.strategy, after.strategy)
    }

    @Test
    fun `configured battle applies chijie before a real normal attack`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100989, 200, listOf(200989), position = 2),
                        hero(100017, 190, position = 1),
                    ),
                ),
                defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        val normalIndex = result.events.indexOfFirst { it is BattleEvent.NormalAttack }
        val attackBuffIndex = result.events.indexOfFirst {
            it is BattleEvent.StatChanged &&
                it.stat == com.stzb.server.game.battle.BattleStat.ATTACK &&
                it.target.heroId == BattleHeroId(100989)
        }
        assertTrue(attackBuffIndex in 0 until normalIndex)
    }

    @Test
    fun `zhongke follows attack damage on its marked target at most twice`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100268, 100, listOf(200268), position = 2),
                    hero(100017, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100268) }
        val ally = engine.state.view.heroes().single { it.heroId == BattleHeroId(100017) }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.state.runtime.recordMarker(target, 20026811, 0, 1, 8)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = ally,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_AFTER,
            battleView = engine.state.view,
        )

        val first = engine.applyNormalDamage(1, ally, target, 1, context)
        val second = engine.applyNormalDamage(1, ally, target, 1, context)
        val third = engine.applyNormalDamage(1, ally, target, 1, context)

        assertTrue(first.filterIsInstance<BattleEvent.SkillDamage>().any { it.source == owner })
        assertTrue(second.filterIsInstance<BattleEvent.SkillDamage>().any { it.source == owner })
        assertTrue(third.filterIsInstance<BattleEvent.SkillDamage>().none { it.source == owner })
    }

    @Test
    fun `tianzi applies its threshold effects after marked target is hurt twice in a round`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100270, 100, listOf(200270), position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.state.runtime.recordMarker(target, 21027012, 0, 1, 1)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_AFTER,
            battleView = engine.state.view,
        )
        engine.applyNormalDamage(1, owner, target, 1, context)
        engine.applyNormalDamage(1, owner, target, 1, context)
        val before = requireNotNull(engine.state.view.state(target)).stats.copy()
        assertEquals(2, engine.state.runtime.roundHurtCount(target, 1))
        assertTrue(engine.state.runtime.hasMarker(target, 21027012, 1))
        assertTrue(200270 in engine.liveHero(owner).skillIds)

        val events = engine.trigger(
            BattleTrigger.ROUND_END,
            context.copy(trigger = BattleTrigger.ROUND_END),
        )

        val after = requireNotNull(engine.state.view.state(target)).stats
        assertTrue(events.filterIsInstance<BattleEvent.StatChanged>().isNotEmpty())
        assertTrue(after.attack < before.attack)
        assertTrue(after.defense < before.defense)
        assertTrue(after.strategy < before.strategy)
        assertTrue(after.speed < before.speed)
    }

    @Test
    fun `dingjun removes opening damage suppression on its owners fourth round action`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100293, 100, listOf(200293), position = 2))),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 4,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 4,
            source = owner,
            rootSkillId = 200293,
            currentSkillId = 200293,
            trigger = BattleTrigger.ACTION_BEFORE,
            battleView = engine.state.view,
        )

        val third = engine.trigger(BattleTrigger.ACTION_BEFORE, context.copy(round = 3))
        val fourth = engine.trigger(BattleTrigger.ACTION_BEFORE, context)

        assertTrue(third.none { it is BattleEvent.SkillTriggered && it.skillId == 210293 })
        assertTrue(fourth.any { it is BattleEvent.SkillTriggered && it.skillId == 210293 })
    }

    @Test
    fun `tongchou buffs only allies within one position after actual hurt`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100006, 100, listOf(201006), position = 2),
                    hero(100007, 90, position = 1),
                    hero(100008, 80, position = 0),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val hurt = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val near = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 1
        }
        val far = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 0
        }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = enemy,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_AFTER,
            battleView = engine.state.view,
        )

        engine.applyNormalDamage(1, enemy, hurt, 1, context)

        assertTrue(engine.state.effectStore.effectsFor(hurt).any { it.skillId == 223006 })
        assertTrue(engine.state.effectStore.effectsFor(near).any { it.skillId == 223006 })
        assertTrue(engine.state.effectStore.effectsFor(far).none { it.skillId == 223006 })
    }

    @Test
    fun `tongchou preparation registers its derived listener on every ally`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(200001, 10, position = 2))),
            defender = BattleTeam(
                listOf(
                    hero(100006, 100, listOf(201006), position = 0),
                    hero(100007, 90, position = 1),
                    hero(100008, 80, position = 2),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100006) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_PASSIVE,
            battleView = engine.state.view,
        )

        val listeners = engine.prepareBattle(context)
            .filterIsInstance<BattleEvent.SkillTriggered>()
            .filter { it.rootSkillId == 201006 && it.skillId == 221006 }

        assertEquals(
            listOf(0, 1, 2),
            listeners.map { it.source.position },
        )
    }

    @Test
    fun `tongchou does not react to damage before the first combat round`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100006, 100, listOf(201006), position = 2),
                    hero(100007, 90, position = 1),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val hurt = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = enemy,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.DAMAGE_AFTER,
            battleView = engine.state.view,
        )

        val events = engine.applyNormalDamage(0, enemy, hurt, 1, context)

        assertTrue(
            events.filterIsInstance<BattleEvent.ModifierApplied>()
                .none { it.skillId == 223006 },
        )
    }

    @Test
    fun `fenji command setup emits its root trigger without executing its action chain`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100961, 200, listOf(200961), position = 2).copy(
                        skillLevels = listOf(10),
                    ),
                    hero(100001, 20, position = 1),
                    hero(100002, 10, position = 0),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 25, position = 1),
                    hero(200003, 15, position = 0),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100961) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        val troopsBefore = engine.state.view.heroes().associateWith {
            requireNotNull(engine.state.view.state(it)).troops
        }

        val events = engine.prepareBattle(context)

        assertEquals(
            1,
            events.filterIsInstance<BattleEvent.SkillTriggered>().count {
                it.round == 0 &&
                    it.source == owner &&
                    it.rootSkillId == 200961 &&
                    it.skillId == 200961 &&
                    it.trigger == BattleTrigger.BATTLE_COMMAND
            },
            "events=$events",
        )
        assertTrue(
            engine.state.effectStore.effectsFor(owner).none { it.detailId == 21396101 },
            "events=$events effects=${engine.state.effectStore.effectsFor(owner)}",
        )
        assertEquals(
            troopsBefore,
            engine.state.view.heroes().associateWith {
                requireNotNull(engine.state.view.state(it)).troops
            },
            "events=$events",
        )
        assertTrue(events.filterIsInstance<BattleEvent.SkillTriggered>().none {
            it.skillId in setOf(212961, 210961, 213961, 211961)
        })
    }

    @Test
    fun `fenji attacks at forty percent then starts a new damage stack`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100961, 200, listOf(200961), position = 2).copy(
                        skillLevels = listOf(10),
                    ),
                    hero(100001, 20, position = 1),
                    hero(100002, 10, position = 0),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 25, position = 1),
                    hero(200003, 15, position = 0),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single { it.heroId == BattleHeroId(100961) }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = owner,
            rootSkillId = 200961,
            currentSkillId = 200961,
            trigger = BattleTrigger.ACTION_BEFORE,
            battleView = engine.state.view,
        )

        val events = engine.trigger(BattleTrigger.ACTION_BEFORE, context)

        val configuredSkillSequence = buildList {
            events.forEach { event ->
                when {
                    event is BattleEvent.SkillTriggered &&
                        event.skillId in setOf(212961, 210961) -> add(event.skillId)
                    event is BattleEvent.ModifierApplied &&
                        event.skillId == 213961 -> add(event.skillId)
                    event is BattleEvent.SkillDamage &&
                        event.skillId == 211961 &&
                        lastOrNull() != event.skillId -> add(event.skillId)
                }
            }
        }
        assertEquals(
            listOf(
                212961,
                210961, 213961,
                210961, 213961,
                210961, 213961,
                210961, 213961,
                210961, 213961, 211961,
                210961, 213961,
            ),
            configuredSkillSequence,
            "events=$events effects=${engine.state.effectStore.effectsFor(owner).map {
                listOf(it.detailId, it.stacks, it.maxStacks, it.effectiveStrength)
            }}",
        )
        val expectedDamage = BattleDamageCalculator.physical(
            source = request.attacker.heroes.first().copy(
                modifiers = listOf(
                    BattleModifier.DamageDealtPercent(
                        school = DamageSchool.PHYSICAL,
                        percent = 40,
                    ),
                ),
            ),
            target = request.defender.heroes.first(),
            ratePercent = 190,
            attributeRandomTenths = 30,
            origin = DamageOrigin.ACTIVE,
        )
        val attackEvents = events.filterIsInstance<BattleEvent.SkillDamage>()
            .filter { it.skillId == 211961 }
        assertTrue(attackEvents.isNotEmpty(), "events=$events")
        assertTrue(
            attackEvents.all { it.damage == expectedDamage },
            "expectedDamage=$expectedDamage attackEvents=$attackEvents",
        )
        val remaining = engine.state.effectStore.effectsFor(owner)
            .filter { it.detailId == 21396101 }
        assertEquals(1, remaining.size)
        assertEquals(8, remaining.single().effectiveStrength)
    }

    @Test
    fun `same cast marker branches observe and consume earlier detail markers`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100003, 100, listOf(200003), position = 2)),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 10, position = 2).copy(
                        activeStatuses = setOf(com.stzb.server.game.battle.BattleStatus.CONFUSION),
                    ),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.recordTarget(source, target)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 200003,
            currentSkillId = 200003,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )

        engine.trigger(BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)

        assertEquals(false, engine.state.runtime.hasMarker(target, 20000301, round = 1))
    }

    @Test
    fun `liehuo pursuit applies burn and consumes its target marker`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100251, 100, listOf(200251), position = 2)),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 10, position = 2),
                    hero(200002, 10, position = 1),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val primary = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 2
        }
        engine.recordTarget(source, primary)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 200251,
            currentSkillId = 200251,
            trigger = BattleTrigger.PURSUIT_ATTEMPT,
            battleView = engine.state.view,
        )

        val events = engine.trigger(BattleTrigger.PURSUIT_ATTEMPT, context)

        assertTrue(
            events.filterIsInstance<BattleEvent.StatusApplied>().any {
                it.target == primary &&
                    it.status == com.stzb.server.game.battle.BattleStatus.BURN
            },
        )
        assertEquals(false, engine.state.runtime.hasMarker(primary, 20025101, round = 1))
    }

    @Test
    fun `safe production engine executes known conditions instead of suppressing every conditional detail`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100885, 200, listOf(200885), position = 2)),
            ),
            defender = BattleTeam(
                listOf(hero(200001, 10, position = 2)),
            ),
            maxRounds = 4,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        engine.recordTarget(actor, target)
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = actor,
            rootSkillId = 200885,
            currentSkillId = 200885,
            trigger = BattleTrigger.PURSUIT_ATTEMPT,
            battleView = engine.state.view,
        )

        val events = engine.trigger(BattleTrigger.PURSUIT_ATTEMPT, context)

        assertTrue(
            events.filterIsInstance<BattleEvent.SkillDamage>()
                .any { it.skillId == 200885 },
            "known cast_condition=104 must execute in the production-safe engine",
        )
    }

    @Test
    fun `complete engine routes fuwangyikou through its single plugin path`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100036, 100, listOf(200036), position = 0),
                    hero(100001, 150, listOf(200012), position = 1),
                    hero(100002, 200, listOf(200012), position = 2),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 0
        }
        val middle = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 1
        }
        val front = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 200036,
            currentSkillId = 200036,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        val commandEvents = engine.prepareBattle(context)

        assertEquals(
            setOf(middle, front),
            engine.state.effectStore.effectsFor(middle)
                .plus(engine.state.effectStore.effectsFor(front))
                .filter { it.skillId == 200036 && it.effectId == 352 }
                .mapTo(linkedSetOf()) { it.target },
        )
        assertEquals(
            1,
            commandEvents.filterIsInstance<BattleEvent.SkillTriggered>()
                .count { it.skillId == 200036 },
            "plugin and configured interpreter must not both execute 200036",
        )

        val attackBefore = engine.liveHero(front).stats.attack
        val strategyBefore = engine.liveHero(front).stats.strategy
        engine.trigger(
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context.copy(
                round = 1,
                source = front,
                rootSkillId = 200012,
                currentSkillId = 200012,
                trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            ),
        )

        assertEquals(attackBefore + 11, engine.liveHero(front).stats.attack)
        assertEquals(strategyBefore + 13, engine.liveHero(front).stats.strategy)
    }

    @Test
    fun `prepared active completion also triggers fuwangyikou response`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100036, 100, listOf(200036), position = 0),
                    hero(100001, 200, listOf(200031), position = 2),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 0
        }
        val actor = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 200036,
            currentSkillId = 200036,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        engine.trigger(
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context.copy(
                source = actor,
                round = 1,
                rootSkillId = 200031,
                currentSkillId = 200031,
                trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            ),
        )
        assertEquals(
            0,
            engine.state.runtime.count(actor, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200031),
        )
        val attackBeforeCompletion = engine.liveHero(actor).stats.attack
        val strategyBeforeCompletion = engine.liveHero(actor).stats.strategy

        engine.trigger(
            BattleTrigger.ACTION_BEFORE,
            context.copy(
                source = actor,
                round = 2,
                rootSkillId = 200031,
                currentSkillId = 200031,
                trigger = BattleTrigger.ACTION_BEFORE,
            ),
        )

        assertEquals(attackBeforeCompletion + 11, engine.liveHero(actor).stats.attack)
        assertEquals(strategyBeforeCompletion + 13, engine.liveHero(actor).stats.strategy)
        assertEquals(
            1,
            engine.state.runtime.count(actor, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200031),
        )
    }

    @Test
    fun `prepared active response applies after fenji resolution`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100036, 100, listOf(200036), position = 0),
                    hero(100961, 200, listOf(200031, 200961), position = 2).copy(
                        skillLevels = listOf(1, 10),
                    ),
                    hero(100001, 20, position = 1),
                ),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 25, position = 1),
                    hero(200003, 15, position = 0),
                ),
            ),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = actor,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        engine.trigger(
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context.copy(
                round = 1,
                rootSkillId = 200031,
                currentSkillId = 200031,
                trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            ),
        )
        val attackBeforeCompletion = engine.liveHero(actor).stats.attack

        val events = engine.trigger(
            BattleTrigger.ACTION_BEFORE,
            context.copy(
                round = 2,
                rootSkillId = 200031,
                currentSkillId = 200031,
                trigger = BattleTrigger.ACTION_BEFORE,
            ),
        )

        val expectedFenjiDamage = BattleDamageCalculator.physical(
            source = request.attacker.heroes.single { it.position == 2 }.copy(
                modifiers = listOf(
                    BattleModifier.DamageDealtPercent(
                        school = DamageSchool.PHYSICAL,
                        percent = 40,
                    ),
                ),
            ),
            target = request.defender.heroes.first(),
            ratePercent = 190,
            attributeRandomTenths = 30,
            origin = DamageOrigin.ACTIVE,
        )
        val fenjiDamage = events.filterIsInstance<BattleEvent.SkillDamage>()
            .first { it.skillId == 211961 }
        assertEquals(expectedFenjiDamage, fenjiDamage.damage, "events=$events")
        assertEquals(attackBeforeCompletion + 11, engine.liveHero(actor).stats.attack)
    }

    @Test
    fun `cancelled prepared active never consumes fuwangyikou layer`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100036, 100, listOf(200036), position = 0),
                    hero(100001, 200, listOf(200031), position = 2),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10))),
            maxRounds = 2,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 0
        }
        val actor = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = owner,
            rootSkillId = 200036,
            currentSkillId = 200036,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )
        engine.prepareBattle(context)
        val attackBefore = engine.liveHero(actor).stats.attack
        engine.trigger(
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context.copy(
                source = actor,
                round = 1,
                rootSkillId = 200031,
                currentSkillId = 200031,
                trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            ),
        )
        engine.state.runtime.interruptPreparations(actor)
        engine.trigger(
            BattleTrigger.ACTION_BEFORE,
            context.copy(source = actor, round = 2, trigger = BattleTrigger.ACTION_BEFORE),
        )

        assertEquals(attackBefore, engine.liveHero(actor).stats.attack)
        assertEquals(
            0,
            engine.state.runtime.count(actor, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200031),
        )
    }

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
    fun `every applied hit advances delay hit timing and dispatches damage hooks`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100479, 200))),
            defender = BattleTeam(listOf(hero(1, 10))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.NORMAL_ATTACK_BEFORE,
            battleView = engine.state.view,
        )
        engine.trigger(BattleTrigger.ROUND_START, context.copy(trigger = BattleTrigger.ROUND_START))
        engine.schedule(
            ScheduledEffectActivationChange(
                PersistentEffectSpec(
                    source = source,
                    target = source,
                    rootSkillId = 1,
                    skillId = 1,
                    skillKind = SkillKind.PASSIVE,
                    rawSkillType = 1,
                    detailId = 101,
                    effectId = 544,
                    category = com.stzb.server.game.battle.EffectCategory.BENEFICIAL,
                    conflict = 544,
                    replaceType = 0,
                    bindFlag = 0,
                    maxStacks = 1,
                    delayRound = 0,
                    delayHit = 1,
                    availableRounds = 2,
                    availableHit = 0,
                    clearPerHit = false,
                    startBoundary = EffectStartBoundary.AFTER_DELAY,
                    potency = TypedBattlePotency.rate(100),
                ),
                actionKind = ActionEffectKind.DOUBLE_ATTACK,
            ),
            round = 1,
        )

        val events = engine.applyNormalDamage(1, source, target, 1, context)

        assertEquals(TimingPosition(1, 1), engine.timingPosition())
        assertEquals(2, engine.permissionFor(source, context).normalAttackCount)
        assertEquals(1, engine.state.runtime.count(source, BattleTrigger.DAMAGE_AFTER))
        assertEquals(1, engine.state.runtime.count(target, BattleTrigger.HURT_AFTER))
        assertTrue(events.any {
            it is BattleEvent.TriggerPoint && it.source == source && it.trigger == BattleTrigger.DAMAGE_AFTER
        })
        assertTrue(events.any {
            it is BattleEvent.TriggerPoint && it.source == target && it.trigger == BattleTrigger.HURT_AFTER
        })
    }

    @Test
    fun `complete engine applies clear and reduce referenced effect changes`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100479, 200))),
            defender = BattleTeam(listOf(hero(1, 10))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val source = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val target = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val spec = PersistentEffectSpec(
            source = source,
            target = target,
            rootSkillId = 1,
            skillId = 1,
            skillKind = SkillKind.PASSIVE,
            rawSkillType = 1,
            detailId = 201,
            effectId = 77,
            category = com.stzb.server.game.battle.EffectCategory.BENEFICIAL,
            conflict = 77,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = 2,
            availableHit = 2,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = TypedBattlePotency.flat(1),
        )
        engine.state.effectStore.apply(spec.toActiveSkillEffect())
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = source,
            rootSkillId = 1,
            currentSkillId = 1,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )

        engine.applyChanges(
            listOf(
                ReduceReferencedEffectUseChange(
                    source, target, 1, 1, 101, 201, 77, 1,
                    MetaEffectParameters.from(configRule(101, 313, effectParam = 201)),
                ),
            ),
            context,
        )
        assertEquals(1, engine.state.effectStore.effectsFor(target).single().remainingHits)

        engine.applyChanges(
            listOf(
                ClearReferencedEffectChange(
                    source, target, 1, 1, 102, 201, 77,
                    MetaEffectParameters.from(configRule(102, 152, effectParam = 201)),
                ),
            ),
            context,
        )
        assertTrue(engine.state.effectStore.effectsFor(target).isEmpty())
    }

    @Test
    fun `due change identity skips only the matching tail copy`() {
        val source = BattleHeroRef(Side.ATTACKER, 2, BattleHeroId(1))
        val target = BattleHeroRef(Side.DEFENDER, 2, BattleHeroId(2))
        val spec = PersistentEffectSpec(
            source, target, 1, 1, SkillKind.PASSIVE, 1, 101, 544,
            com.stzb.server.game.battle.EffectCategory.BENEFICIAL,
            0, 0, 0, 2, 0, 1, 2, 0, false,
            EffectStartBoundary.AFTER_DELAY, TypedBattlePotency.rate(100),
        )
        val scheduled = ScheduledEffectActivationChange(spec, actionKind = ActionEffectKind.DOUBLE_ATTACK)
        val activated = scheduled.activationChanges().single()
        val due = SkillTimingDue.mint(
            scheduled,
            activatedChanges = listOf(activated),
            dueRound = 1,
            dueHit = 1,
            sequence = 1,
        )
        val result = SkillExecutionResult.immutable(
            stateChanges = listOf(activated, activated),
            events = emptyList(),
            executedSkillIds = emptyList(),
            diagnostics = emptyList(),
            timingDues = listOf(due),
        )

        assertEquals(listOf(false, true), result.dueChangeIndexMask().toList())
    }

    @Test
    fun `counterattack and secondary attack consume configured active effect strength`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100479, 200, listOf(200225), position = 2),
                        hero(100017, 10, position = 0),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(100010, 100, listOf(200010), position = 2),
                        hero(2, 20, position = 1),
                    ),
                ),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        val split = result.events.filterIsInstance<BattleEvent.SkillDamage>()
            .first { it.effectId == 545 }
        val counter = result.events.filterIsInstance<BattleEvent.SkillDamage>()
            .first { it.effectId == 551 }
        assertEquals(200225, split.skillId)
        assertEquals(Side.ATTACKER, split.source.side)
        assertEquals(Side.DEFENDER, split.target.side)
        assertEquals(200010, counter.skillId)
        assertEquals(Side.DEFENDER, counter.source.side)
        assertEquals(Side.ATTACKER, counter.target.side)
        assertTrue(counter.damage > split.damage, "200% counter must exceed 75% secondary damage")
    }

    @Test
    fun `counterattack immunity prevents the defender counterattack`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100479, 200, position = 2).copy(
                            modifiers = listOf(BattleModifier.CounterattackImmunity),
                        ),
                        hero(100017, 10, position = 0),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(100010, 100, listOf(200010), position = 2),
                        hero(2, 20, position = 1),
                    ),
                ),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        assertTrue(
            result.events.filterIsInstance<BattleEvent.NormalAttack>()
                .any { it.source.side == Side.ATTACKER },
        )
        assertTrue(
            result.events.filterIsInstance<BattleEvent.SkillDamage>()
                .none {
                    it.effectId == 551 &&
                        it.source.side == Side.DEFENDER &&
                        it.target.heroId == BattleHeroId(100479)
                },
        )
    }

    @Test
    fun `troop scatter performs one secondary attack on the first normal attack`() {
        val attacker = BattleTeamBuilder(
            config,
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
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = attacker,
                defender = BattleTeam(
                    listOf(
                        hero(200001, 30, position = 0),
                        hero(200002, 20, position = 1),
                        hero(200003, 10, position = 2),
                    ),
                ),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        val splits = result.events.filterIsInstance<BattleEvent.SkillDamage>()
            .filter { it.skillId == 297108 && it.effectId == 545 }
        assertEquals(1, splits.size)
    }

    @Test
    fun `secondary attack runs damage before hooks before calculating damage`() {
        val actorHero = hero(100017, 190, listOf(200225), position = 1)
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100989, 200, listOf(200989), position = 2)
                            .copy(skillLevels = listOf(10)),
                        actorHero,
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(200001, 30, position = 1),
                        hero(200002, 20, position = 2),
                    ),
                ),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )
        val actor = BattleHeroRef(Side.ATTACKER, actorHero.position, actorHero.id)
        val splitIndex = result.events.indexOfFirst {
            it is BattleEvent.SkillDamage && it.effectId == 545 && it.source == actor
        }
        val increases = result.events.mapIndexedNotNull { index, event ->
            index.takeIf {
                event is BattleEvent.StatChanged &&
                    event.skillId == 213989 &&
                    event.target == actor
            }
        }

        assertTrue(splitIndex >= 0)
        assertEquals(2, increases.size)
        assertTrue(increases.all { it < splitIndex })
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
        val rangeChanges = result.events.filterIsInstance<BattleEvent.SkillRangeChanged>()
        assertEquals(3, rangeChanges.size)
        assertTrue(rangeChanges.all { it.skillId == 200023 && it.skillKind == SkillKind.ACTIVE })
        assertTrue(rangeChanges.all { it.delta == 1 })
        assertEquals(
            setOf(
                Side.ATTACKER to 0,
                Side.ATTACKER to 1,
                Side.ATTACKER to 2,
                Side.DEFENDER to 0,
                Side.DEFENDER to 1,
                Side.DEFENDER to 2,
            ),
            result.events.filterIsInstance<BattleEvent.HeroActionStart>()
                .mapTo(linkedSetOf()) { it.source.side to it.source.position },
        )
    }

    @Test
    fun `all living heroes enter the action scheduler in every one of eight rounds`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(
                    listOf(
                        hero(100001, 60, position = 0),
                        hero(100002, 50, position = 1),
                        hero(100003, 40, position = 2),
                    ),
                ),
                defender = BattleTeam(
                    listOf(
                        hero(200001, 30, position = 0),
                        hero(200002, 20, position = 1),
                        hero(200003, 10, position = 2),
                    ),
                ),
                maxRounds = 8,
            ),
            config,
            FixedBattleRandom(0),
        )

        val starts = result.events.filterIsInstance<BattleEvent.HeroActionStart>()
        val ends = result.events.filterIsInstance<BattleEvent.HeroActionEnd>()
        assertEquals(starts.map { it.round to it.source }, ends.map { it.round to it.source })
        val entryRefs = (requestHeroRefs(result.entryAttacker.orEmpty(), Side.ATTACKER) +
            requestHeroRefs(result.entryDefender.orEmpty(), Side.DEFENDER)).toSet()
        entryRefs.forEach { ref ->
            val survived = result.events.none {
                it is BattleEvent.NormalAttack && it.target == ref && it.targetTroopsAfter == 0 ||
                    it is BattleEvent.SkillDamage && it.target == ref && it.targetTroopsAfter == 0
            }
            if (survived) {
                assertEquals((1..8).toList(), starts.filter { it.source == ref }.map { it.round })
            }
        }
    }

    @Test
    fun `yibingbizhan only suppresses attacks for its configured first two rounds`() {
        val owner = hero(100701, 60, listOf(200761), position = 2).copy(
            stats = BattleStats(attack = 1, defense = 10_000, strategy = 100, speed = 60, siege = 0, hitRange = 5),
        )
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(owner)),
                defender = BattleTeam(
                    listOf(
                        hero(200001, 10, position = 2).copy(
                            stats = BattleStats(attack = 1, defense = 10_000, strategy = 100, speed = 10, siege = 0, hitRange = 5),
                        ),
                    ),
                ),
                maxRounds = 8,
            ),
            config,
            FixedBattleRandom(0),
        )
        val ownerRef = BattleHeroRef(Side.ATTACKER, owner.position, owner.id)
        val actionRounds = result.events.filterIsInstance<BattleEvent.HeroActionStart>()
            .filter { it.source == ownerRef }
            .map { it.round }
        val normalRounds = result.events.filterIsInstance<BattleEvent.NormalAttack>()
            .filter { it.source == ownerRef }
            .map { it.round }

        assertEquals((1..8).toList(), actionRounds)
        assertEquals((3..8).toList(), normalRounds)
    }

    @Test
    fun `xingbingzhiji preparation probability increase changes later active rolls`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(
                    hero(100003, 60, listOf(200813), position = 2),
                    hero(100006, 50, position = 1),
                    hero(100001, 40, listOf(200001), position = 0),
                ),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 2))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val owner = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 2
        }
        val actor = engine.state.view.heroes().single {
            it.side == Side.ATTACKER && it.position == 0
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(35),
            round = 0,
            source = owner,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_COMMAND,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)
        val events = engine.trigger(
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context.copy(
                round = 1,
                source = actor,
                trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            ),
        )

        assertTrue(engine.liveHero(actor).modifiers.any {
            it is com.stzb.server.game.battle.BattleModifier.SkillProbabilityPercent &&
                it.percent == 10
        })
        assertTrue(events.any { it is BattleEvent.SkillTriggered && it.skillId == 200001 })
    }

    @Test
    fun `shared probability use is consumed through engine when active skill rolls`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100001, 100, listOf(200049), position = 0)),
            ),
            defender = BattleTeam(listOf(hero(200001, 10, position = 0))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = actor,
            rootSkillId = 200049,
            currentSkillId = 200049,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )
        fun groupedSpec(
            detailId: Int,
            effectId: Int,
        ) = PersistentEffectSpec(
            source = actor,
            target = actor,
            rootSkillId = 200293,
            skillId = 211293,
            skillKind = SkillKind.COMMAND,
            rawSkillType = 2,
            detailId = detailId,
            effectId = effectId,
            category = com.stzb.server.game.battle.EffectCategory.BENEFICIAL,
            conflict = 0,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = 0,
            availableHit = 1,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = TypedBattlePotency.percent(100),
        )
        engine.applyChanges(
            listOf(
                ModifierEffectChange(
                    groupedSpec(21129311, 131),
                    com.stzb.server.game.battle.BattleModifier.SkillProbabilityPercent(
                        percent = 100,
                        skillKind = SkillKind.ACTIVE,
                    ),
                ),
                ModifierEffectChange(
                    groupedSpec(21129312, 131),
                    com.stzb.server.game.battle.BattleModifier.SkillProbabilityPercent(
                        percent = 100,
                        skillKind = SkillKind.PURSUIT,
                    ),
                ),
                SharedEffectUseGroupChange(
                    groupedSpec(21129318, 88),
                    memberDetailId = 21129311,
                ),
                SharedEffectUseGroupChange(
                    groupedSpec(21129319, 88),
                    memberDetailId = 21129312,
                ),
            ),
            context,
        )
        assertEquals(
            listOf(131, 131, 88, 88),
            engine.state.effectStore.effectsFor(actor).map { it.effectId },
        )

        engine.trigger(BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)

        assertTrue(engine.state.effectStore.effectsFor(actor).none {
            it.effectId == 131 || it.effectId == 88
        })
    }

    @Test
    fun `dingjun forced normal attack selects enemy base on fourth round and consumes once`() {
        val owner = hero(100810, 100, listOf(200293), position = 2).copy(
            stats = BattleStats(
                attack = 1,
                defense = 10_000,
                strategy = 100,
                speed = 100,
                siege = 0,
                hitRange = 1,
            ),
        )
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(owner)),
                defender = BattleTeam(
                    listOf(
                        hero(200001, 20, position = 0).copy(
                            stats = BattleStats(1, 10_000, 100, 20, 0, 5),
                        ),
                        hero(200002, 10, position = 2).copy(
                            stats = BattleStats(1, 10_000, 100, 10, 0, 5),
                        ),
                    ),
                ),
                maxRounds = 5,
            ),
            config,
            FixedBattleRandom(0),
        )
        val ownerRef = BattleHeroRef(Side.ATTACKER, owner.position, owner.id)

        assertEquals(
            listOf(
                1 to 2,
                2 to 2,
                3 to 2,
                4 to 0,
                5 to 2,
            ),
            result.events.filterIsInstance<BattleEvent.NormalAttack>()
                .filter { it.source == ownerRef }
                .map { it.round to it.target.position },
        )
    }

    @Test
    fun `joint attack redirects the first active damage beyond skill range and consumes once`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100001, 100, listOf(200049), position = 0)),
            ),
            defender = BattleTeam(
                listOf(
                    hero(200001, 10, position = 0),
                    hero(200002, 20, position = 2),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemyBase = engine.state.view.heroes().single {
            it.side == Side.DEFENDER && it.position == 0
        }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = actor,
            rootSkillId = 200049,
            currentSkillId = 200049,
            trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            battleView = engine.state.view,
        )
        engine.applyChanges(
            listOf(
                ForcedTargetEffectChange(
                    spec = PersistentEffectSpec(
                        source = actor,
                        target = actor,
                        rootSkillId = 200293,
                        skillId = 211293,
                        skillKind = SkillKind.COMMAND,
                        rawSkillType = 2,
                        detailId = 21129316,
                        effectId = 81,
                        category = com.stzb.server.game.battle.EffectCategory.BENEFICIAL,
                        conflict = 0,
                        replaceType = 0,
                        bindFlag = 0,
                        maxStacks = 1,
                        delayRound = 0,
                        delayHit = 0,
                        availableRounds = 0,
                        availableHit = 1,
                        clearPerHit = false,
                        startBoundary = EffectStartBoundary.IMMEDIATE,
                        potency = TypedBattlePotency.percent(100),
                    ),
                    forcedTarget = enemyBase,
                ),
            ),
            context,
        )

        val events = engine.trigger(BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)

        assertTrue(events.filterIsInstance<BattleEvent.SkillDamage>().any {
            it.skillId == 200049 && it.target == enemyBase
        })
        assertTrue(engine.state.effectStore.effectsFor(actor).none { it.effectId == 81 })
    }

    @Test
    fun `next control duration modifier extends one successful control and then expires`() {
        val request = BattleRequest(
            attacker = BattleTeam(listOf(hero(100001, 100, listOf(200049)))),
            defender = BattleTeam(listOf(hero(200001, 10))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = actor,
            rootSkillId = 200049,
            currentSkillId = 200049,
            trigger = BattleTrigger.EFFECT_APPLYING,
            battleView = engine.state.view,
        )
        engine.applyChanges(
            listOf(
                ModifierEffectChange(
                    spec = PersistentEffectSpec(
                        source = actor,
                        target = actor,
                        rootSkillId = 470044,
                        skillId = 471044,
                        skillKind = SkillKind.PASSIVE,
                        rawSkillType = 19,
                        detailId = 47104401,
                        effectId = 312,
                        category = com.stzb.server.game.battle.EffectCategory.BENEFICIAL,
                        conflict = 0,
                        replaceType = 0,
                        bindFlag = 0,
                        maxStacks = 1,
                        delayRound = 0,
                        delayHit = 0,
                        availableRounds = 0,
                        availableHit = 1,
                        clearPerHit = false,
                        startBoundary = EffectStartBoundary.IMMEDIATE,
                        potency = TypedBattlePotency.flat(1),
                    ),
                    modifier = BattleModifier.ControlDurationIncrease(
                        rounds = 1,
                        mainSkillOnly = false,
                    ),
                ),
            ),
            context,
        )

        engine.applyChanges(
            listOf(controlChange(actor, enemy).copy(spec = controlChange(actor, enemy).spec.copy(
                rootSkillId = 200049,
                skillId = 200049,
                detailId = 20004991,
            ))),
            context,
        )

        assertEquals(
            2,
            engine.state.effectStore.effectsFor(enemy)
                .single { it.detailId == 20004991 }
                .remainingRounds,
        )
        assertTrue(engine.state.effectStore.effectsFor(actor).none { it.effectId == 312 })

        engine.applyChanges(
            listOf(controlChange(actor, enemy).copy(spec = controlChange(actor, enemy).spec.copy(
                rootSkillId = 200049,
                skillId = 200049,
                detailId = 20004992,
                effectId = 502,
                conflict = 502,
            ))),
            context,
        )
        assertEquals(
            1,
            engine.state.effectStore.effectsFor(enemy)
                .single { it.detailId == 20004992 }
                .remainingRounds,
        )
    }

    @Test
    fun `main skill control duration modifier ignores non main controls without consuming`() {
        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100001, 100, listOf(200049, 200001))),
            ),
            defender = BattleTeam(listOf(hero(200001, 10))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val enemy = engine.state.view.heroes().single { it.side == Side.DEFENDER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = actor,
            rootSkillId = 200049,
            currentSkillId = 200049,
            trigger = BattleTrigger.EFFECT_APPLYING,
            battleView = engine.state.view,
        )
        engine.applyChanges(
            listOf(
                controlDurationModifier(
                    source = actor,
                    effectId = 311,
                    detailId = 46106101,
                    modifier = BattleModifier.ControlDurationIncrease(
                        rounds = 1,
                        mainSkillOnly = true,
                    ),
                ),
            ),
            context,
        )

        engine.applyChanges(
            listOf(controlChange(actor, enemy).copy(spec = controlChange(actor, enemy).spec.copy(
                rootSkillId = 200001,
                skillId = 200001,
                detailId = 20000191,
            ))),
            context,
        )

        assertEquals(
            1,
            engine.state.effectStore.effectsFor(enemy)
                .single { it.detailId == 20000191 }
                .remainingRounds,
        )
        assertTrue(engine.state.effectStore.effectsFor(actor).any { it.effectId == 311 })

        engine.applyChanges(
            listOf(controlChange(actor, enemy).copy(spec = controlChange(actor, enemy).spec.copy(
                rootSkillId = 200049,
                skillId = 200049,
                detailId = 20004992,
                effectId = 502,
                conflict = 502,
            ))),
            context,
        )
        assertEquals(
            2,
            engine.state.effectStore.effectsFor(enemy)
                .single { it.detailId == 20004992 }
                .remainingRounds,
        )
        assertTrue(engine.state.effectStore.effectsFor(actor).none { it.effectId == 311 })
    }

    @Test
    fun `simulated normal attacks reuse range targeting and normal attack hooks`() {
        val attacker = hero(100001, 100, position = 2).copy(
            stats = BattleStats(
                attack = 100,
                defense = 100,
                strategy = 100,
                speed = 100,
                siege = 0,
                hitRange = 2,
            ),
        )
        val request = BattleRequest(
            attacker = BattleTeam(listOf(attacker)),
            defender = BattleTeam(
                listOf(
                    hero(200001, 30, position = 2),
                    hero(200002, 20, position = 1),
                    hero(200003, 10, position = 0),
                ),
            ),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 1,
            source = actor,
            rootSkillId = 410113,
            currentSkillId = 410113,
            trigger = BattleTrigger.NORMAL_ATTACK_AFTER,
            battleView = engine.state.view,
        )

        val allEvents = engine.applyChanges(
            listOf(
                SimulatedNormalAttackChange(
                    source = actor,
                    mode = SimulatedNormalAttackMode.ALL_IN_RANGE,
                    skillId = 410113,
                    effectId = 80,
                    detailId = 41011321,
                ),
            ),
            context,
        )
        val allAttacks = allEvents.filterIsInstance<BattleEvent.NormalAttack>()
        assertEquals(setOf(2, 1), allAttacks.mapTo(mutableSetOf()) { it.target.position })
        assertEquals(
            2,
            allEvents.filterIsInstance<BattleEvent.TriggerPoint>()
                .count { it.trigger == BattleTrigger.NORMAL_ATTACK_BEFORE },
        )
        assertEquals(
            2,
            allEvents.filterIsInstance<BattleEvent.TriggerPoint>()
                .count { it.trigger == BattleTrigger.NORMAL_ATTACK_AFTER },
        )

        val singleEvents = engine.applyChanges(
            listOf(
                SimulatedNormalAttackChange(
                    source = actor,
                    mode = SimulatedNormalAttackMode.SINGLE,
                    skillId = 411112,
                    effectId = 79,
                    detailId = 41111211,
                ),
            ),
            context,
        )
        assertEquals(1, singleEvents.filterIsInstance<BattleEvent.NormalAttack>().size)
    }

    @Test
    fun `real advisor unlock enables the configured locked skill detail`() {
        val lockedRequest = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100001, 100, listOf(200126))),
            ),
            defender = BattleTeam(listOf(hero(200001, 10))),
            maxRounds = 1,
        )
        val lockedEngine = DefaultCompleteSkillEngine.create(lockedRequest, config)
        val lockedActor = lockedEngine.state.view.heroes().single { it.side == Side.ATTACKER }
        val lockedContext = SkillBattleContext(
            request = lockedRequest,
            runtime = lockedEngine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = lockedActor,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_PASSIVE,
            battleView = lockedEngine.state.view,
        )
        lockedEngine.prepareBattle(lockedContext)
        val lockedEvents = lockedEngine.trigger(
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            lockedContext.copy(
                round = 1,
                rootSkillId = 200126,
                currentSkillId = 200126,
                trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            ),
        )
        assertEquals(
            1,
            lockedEvents.filterIsInstance<BattleEvent.SkillDamage>()
                .count { it.skillId == 200126 && it.effectId == 302 },
            "events=$lockedEvents",
        )

        val request = BattleRequest(
            attacker = BattleTeam(
                listOf(hero(100001, 100, listOf(200870, 200126))),
            ),
            defender = BattleTeam(listOf(hero(200001, 10))),
            maxRounds = 1,
        )
        val engine = DefaultCompleteSkillEngine.create(request, config)
        val actor = engine.state.view.heroes().single { it.side == Side.ATTACKER }
        val context = SkillBattleContext(
            request = request,
            runtime = engine.state.runtime,
            random = FixedBattleRandom(0),
            round = 0,
            source = actor,
            rootSkillId = 0,
            currentSkillId = 0,
            trigger = BattleTrigger.BATTLE_PASSIVE,
            battleView = engine.state.view,
        )

        engine.prepareBattle(context)

        assertTrue(
            engine.liveHero(actor).modifiers.contains(
                BattleModifier.SkillEnhancementUnlock(200870),
            ),
        )
        val events = engine.trigger(
            BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            context.copy(
                round = 1,
                rootSkillId = 200126,
                currentSkillId = 200126,
                trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
            ),
        )
        assertEquals(
            2,
            events.filterIsInstance<BattleEvent.SkillDamage>()
                .count { it.skillId == 200126 && it.effectId == 302 },
            "events=$events",
        )
    }

    private fun requestHeroRefs(team: BattleTeam, side: Side): List<BattleHeroRef> =
        team.heroes.map { BattleHeroRef(side, it.position, it.id) }

    private fun BattleTeam?.orEmpty(): BattleTeam = this ?: BattleTeam(emptyList())

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

    private fun ongoingDamage(
        source: BattleHeroRef,
        target: BattleHeroRef,
        detailId: Int,
    ): ScheduledDamageEffectChange {
        val spec = PersistentEffectSpec(
            source = source,
            target = target,
            rootSkillId = 900000,
            skillId = 900000,
            skillKind = SkillKind.ACTIVE,
            rawSkillType = 3,
            detailId = detailId,
            effectId = 305,
            category = com.stzb.server.game.battle.EffectCategory.HARMFUL,
            conflict = 305,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = 2,
            availableHit = 0,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = TypedBattlePotency.rate(40),
        )
        return ScheduledDamageEffectChange(
            spec = spec,
            school = DamageSchool.STRATEGY,
            origin = DamageOrigin.ACTIVE,
            tags = setOf(com.stzb.server.game.battle.DamageTag.ONGOING),
            status = com.stzb.server.game.battle.BattleStatus.BURN,
            coefficientSource = BattleCoefficientSource.STRATEGY,
            rawCoefficient = 350,
            calculationTypes = emptyList(),
        )
    }

    private fun ongoingHit(
        source: BattleHeroRef,
        target: BattleHeroRef,
    ) = TroopDamageChange(
        source = source,
        target = target,
        amount = 100,
        troopsAfter = 9_900,
        school = DamageSchool.STRATEGY,
        origin = DamageOrigin.ACTIVE,
        tags = setOf(com.stzb.server.game.battle.DamageTag.ONGOING),
        skillId = 900000,
        effectId = 305,
    )

    private fun physicalDamage(
        source: BattleHeroRef,
        target: BattleHeroRef,
        amount: Int,
    ) = TroopDamageChange(
        source = source,
        target = target,
        amount = amount,
        troopsAfter = 10_000 - amount,
        school = DamageSchool.PHYSICAL,
        origin = DamageOrigin.NORMAL,
        tags = emptySet(),
        skillId = 0,
        effectId = 0,
    )

    private fun controlDurationModifier(
        source: BattleHeroRef,
        effectId: Int,
        detailId: Int,
        modifier: BattleModifier.ControlDurationIncrease,
    ) = ModifierEffectChange(
        spec = PersistentEffectSpec(
            source = source,
            target = source,
            rootSkillId = detailId / 100,
            skillId = detailId / 100,
            skillKind = SkillKind.PASSIVE,
            rawSkillType = 17,
            detailId = detailId,
            effectId = effectId,
            category = com.stzb.server.game.battle.EffectCategory.BENEFICIAL,
            conflict = 0,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = 0,
            availableHit = 1,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = TypedBattlePotency.flat(modifier.rounds),
        ),
        modifier = modifier,
    )

    private fun controlChange(
        source: BattleHeroRef,
        target: BattleHeroRef,
    ) = ApplyBattleEffectChange(
        PersistentEffectSpec(
            source = source,
            target = target,
            rootSkillId = 900000,
            skillId = 900000,
            skillKind = SkillKind.ACTIVE,
            rawSkillType = 3,
            detailId = 90000001,
            effectId = 501,
            category = com.stzb.server.game.battle.EffectCategory.HARMFUL,
            conflict = 501,
            replaceType = 0,
            bindFlag = 0,
            maxStacks = 1,
            delayRound = 0,
            delayHit = 0,
            availableRounds = 1,
            availableHit = 0,
            clearPerHit = false,
            startBoundary = EffectStartBoundary.IMMEDIATE,
            potency = TypedBattlePotency.flat(1),
        ),
    )

    private fun statChange(
        source: BattleHeroRef,
        target: BattleHeroRef,
        effectId: Int,
        value: Int,
    ) = BattleStatChange(
        source = source,
        target = target,
        kind = when (effectId) {
            101, 201 -> BattleStatChange.Kind.ATTACK
            else -> error("Unsupported test stat effect $effectId")
        },
        potency = TypedBattlePotency.flat(value),
        durationRounds = 1,
        skillId = 900000,
        effectId = effectId,
        detailId = 900000 + effectId,
    )

    private fun configRule(
        detailId: Int,
        effectId: Int,
        effectParam: Int,
    ) = SkillEffectRule(
        detailId = detailId,
        effectId = effectId,
        childSkillIds = emptySet(),
        raw = com.stzb.server.game.battle.SkillDetailConfig(
            detailId = detailId,
            effectId = effectId,
            effectParam = effectParam,
            attackType = 11,
            targetType = 0,
            selectType = 0,
            intelParam = 0,
            constantParam = 1,
            probabilityInit = 100,
            probabilityMax = 100,
            attackMax = 1,
            availableRounds = 1,
            effectName = "test",
        ),
    )
}
