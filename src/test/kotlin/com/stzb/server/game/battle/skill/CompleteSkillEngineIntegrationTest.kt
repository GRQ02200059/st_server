package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEngine
import com.stzb.server.game.battle.BattleEvent
import com.stzb.server.game.battle.BattleHero
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleTeam
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `actual troop recovery records a recovery event occurrence`() {
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

        assertTrue(engine.state.runtime.count(source, BattleTrigger.RECOVERY_AFTER) > 0)
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
