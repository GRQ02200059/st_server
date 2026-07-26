package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.ActiveSkillEffect
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.EffectCategory
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BattleEffectStoreTest {
    private val source = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(1))
    private val otherSource = BattleHeroRef(Side.ATTACKER, 1, BattleHeroId(3))
    private val target = BattleHeroRef(Side.DEFENDER, 0, BattleHeroId(2))
    private val otherTarget = BattleHeroRef(Side.DEFENDER, 1, BattleHeroId(4))

    @Test
    fun `stronger same conflict effect replaces weaker effect`() {
        val store = BattleEffectStore()

        assertEquals(EffectApplyOutcome.APPLIED, store.apply(effect(strength = 10)).outcome)
        val result = store.apply(effect(effectId = 522, detailId = 1002, strength = 20))

        assertEquals(EffectApplyOutcome.REPLACED, result.outcome)
        assertEquals(listOf(511), result.removed.map { it.effectId })
        assertEquals(listOf(20), store.effectsFor(target).map { it.strength })
    }

    @Test
    fun `equal conflict effect refreshes duration while weaker is rejected`() {
        val store = BattleEffectStore()
        store.apply(effect(strength = 20, remainingRounds = 1))

        assertEquals(
            EffectApplyOutcome.REFRESHED,
            store.apply(effect(strength = 20, remainingRounds = 3)).outcome,
        )
        assertEquals(3, store.effectsFor(target).single().remainingRounds)
        assertEquals(
            EffectApplyOutcome.REJECTED,
            store.apply(effect(strength = 19, remainingRounds = 5)).outcome,
        )
        assertEquals(3, store.effectsFor(target).single().remainingRounds)
    }

    @Test
    fun `refresh adopts incoming lifecycle without creating accidental permanence`() {
        val store = BattleEffectStore()
        store.apply(effect(strength = 20, remainingRounds = null, remainingHits = 2))

        store.apply(effect(strength = 20, remainingRounds = 3, remainingHits = null))

        assertEquals(3, store.effectsFor(target).single().remainingRounds)
        assertEquals(null, store.effectsFor(target).single().remainingHits)
    }

    @Test
    fun `stacking increments to maximum then refreshes at cap`() {
        val store = BattleEffectStore()
        store.apply(effect(replaceType = 0, maxStacks = 2, remainingRounds = 1))

        assertEquals(
            EffectApplyOutcome.STACKED,
            store.apply(effect(replaceType = 0, maxStacks = 2, remainingRounds = 2)).outcome,
        )
        assertEquals(2, store.effectsFor(target).single().stacks)
        assertEquals(20, store.effectsFor(target).single().effectiveStrength)
        assertEquals(
            EffectApplyOutcome.REFRESHED,
            store.apply(effect(replaceType = 0, maxStacks = 2, remainingRounds = 4)).outcome,
        )
        assertEquals(2, store.effectsFor(target).single().stacks)
        assertEquals(4, store.effectsFor(target).single().remainingRounds)
    }

    @Test
    fun `raw non stacking policies reject rather than replace`() {
        val rejectStore = BattleEffectStore()
        rejectStore.apply(effect(replaceType = 1, strength = 10))
        assertEquals(
            EffectApplyOutcome.REJECTED,
            rejectStore.apply(effect(replaceType = 1, effectId = 522, strength = 30)).outcome,
        )

        val anyTypeStore = BattleEffectStore()
        anyTypeStore.apply(effect(replaceType = 3, strength = 30))
        val rejected = anyTypeStore.apply(
            effect(replaceType = 3, effectId = 522, strength = 10, remainingRounds = 4),
        )
        assertEquals(EffectApplyOutcome.REJECTED, rejected.outcome)
        assertEquals(30, anyTypeStore.effectsFor(target).single().strength)
        assertEquals(2, anyTypeStore.effectsFor(target).single().remainingRounds)
    }

    @Test
    fun `same type non stacking policy rejects stronger equal and weaker effects from every origin`() {
        listOf(source, otherSource).forEach { incomingSource ->
            listOf(9, 10, 11).forEach { incomingStrength ->
                val store = BattleEffectStore()
                store.apply(effect(replaceType = 1, strength = 10))

                assertEquals(
                    EffectApplyOutcome.REJECTED,
                    store.apply(
                        effect(
                            source = incomingSource,
                            replaceType = 1,
                            strength = incomingStrength,
                        ),
                    ).outcome,
                )
                assertEquals(source, store.effectsFor(target).single().source)
                assertEquals(10, store.effectsFor(target).single().strength)
            }
        }
    }

    @Test
    fun `any type non stacking policy rejects stronger equal and weaker effects across known skill types`() {
        listOf(source, otherSource).forEach { incomingSource ->
            listOf(9, 10, 11).forEach { incomingStrength ->
                val store = BattleEffectStore()
                store.apply(effect(replaceType = 3, strength = 10, sourceSkillType = 3))

                assertEquals(
                    EffectApplyOutcome.REJECTED,
                    store.apply(
                        effect(
                            source = incomingSource,
                            replaceType = 3,
                            strength = incomingStrength,
                            skillKind = SkillKind.PURSUIT,
                            sourceSkillType = 4,
                        ),
                    ).outcome,
                )
                assertEquals(3, store.effectsFor(target).single().sourceSkillType)
                assertEquals(10, store.effectsFor(target).single().strength)
            }
        }
    }

    @Test
    fun `stacking requires the exact hero and root and current skill origin`() {
        val store = BattleEffectStore()
        store.apply(effect(replaceType = 0, maxStacks = 4))

        assertEquals(
            EffectApplyOutcome.STACKED,
            store.apply(effect(replaceType = 0, maxStacks = 4)).outcome,
        )
        assertEquals(
            EffectApplyOutcome.REJECTED,
            store.apply(effect(source = otherSource, replaceType = 0, maxStacks = 4)).outcome,
        )
        assertEquals(
            EffectApplyOutcome.REJECTED,
            store.apply(effect(skillId = 101, replaceType = 0, maxStacks = 4)).outcome,
        )
        assertEquals(2, store.effectsFor(target).single().stacks)
    }

    @Test
    fun `same nonzero conflict origin stacks across detail and effect ids`() {
        val store = BattleEffectStore()
        store.apply(
            effect(
                replaceType = 0,
                maxStacks = 3,
                detailId = 1001,
                effectId = 511,
                strength = 10,
            ),
        )

        val result = store.apply(
            effect(
                replaceType = 0,
                maxStacks = 3,
                detailId = 1002,
                effectId = 512,
                strength = 15,
            ),
        )

        assertEquals(EffectApplyOutcome.STACKED, result.outcome)
        assertEquals(25, store.effectsFor(target).single().effectiveStrength)
    }

    @Test
    fun `same nonzero conflict origin refreshes across detail and effect ids`() {
        val store = BattleEffectStore()
        store.apply(effect(detailId = 1001, effectId = 511, strength = 20, remainingRounds = 1))

        val result = store.apply(
            effect(detailId = 1002, effectId = 512, strength = 20, remainingRounds = 4),
        )

        assertEquals(EffectApplyOutcome.REFRESHED, result.outcome)
        assertEquals(4, store.effectsFor(target).single().remainingRounds)
    }

    @Test
    fun `stacking aggregates unequal layer strengths`() {
        val store = BattleEffectStore()
        store.apply(effect(replaceType = 0, maxStacks = 3, strength = 10))

        store.apply(effect(replaceType = 0, maxStacks = 3, strength = 15))

        assertEquals(25, store.effectsFor(target).single().effectiveStrength)
    }

    @Test
    fun `stacking accepts weaker equal and stronger layers only from the exact origin`() {
        val store = BattleEffectStore()
        store.apply(effect(replaceType = 0, maxStacks = 4, strength = 10))

        listOf(9, 10, 11).forEach { layerStrength ->
            assertEquals(
                EffectApplyOutcome.STACKED,
                store.apply(
                    effect(replaceType = 0, maxStacks = 4, strength = layerStrength),
                ).outcome,
            )
        }
        assertEquals(40, store.effectsFor(target).single().effectiveStrength)

        listOf(9, 10, 11).forEach { layerStrength ->
            assertEquals(
                EffectApplyOutcome.REJECTED,
                store.apply(
                    effect(
                        source = otherSource,
                        replaceType = 0,
                        maxStacks = 4,
                        strength = layerStrength,
                    ),
                ).outcome,
            )
        }
    }

    @Test
    fun `equal strongest effect refreshes only for the exact origin`() {
        val sameOrigin = BattleEffectStore()
        sameOrigin.apply(effect(strength = 20, remainingRounds = 1))
        assertEquals(
            EffectApplyOutcome.REFRESHED,
            sameOrigin.apply(effect(strength = 20, remainingRounds = 3)).outcome,
        )

        val otherHero = BattleEffectStore()
        otherHero.apply(effect(strength = 20, remainingRounds = 1))
        assertEquals(
            EffectApplyOutcome.REJECTED,
            otherHero.apply(effect(source = otherSource, strength = 20, remainingRounds = 3)).outcome,
        )

        val otherSkill = BattleEffectStore()
        otherSkill.apply(effect(strength = 20, remainingRounds = 1))
        assertEquals(
            EffectApplyOutcome.REJECTED,
            otherSkill.apply(effect(skillId = 101, strength = 20, remainingRounds = 3)).outcome,
        )
        assertEquals(source, otherHero.effectsFor(target).single().source)
        assertEquals(100, otherSkill.effectsFor(target).single().skillId)
    }

    @Test
    fun `strongest policy handles stronger equal and weaker effects across sources without hybrid attribution`() {
        val store = BattleEffectStore()
        store.apply(effect(strength = 20, remainingRounds = 1))

        assertEquals(
            EffectApplyOutcome.REJECTED,
            store.apply(effect(source = otherSource, strength = 19, remainingRounds = 5)).outcome,
        )
        assertEquals(
            EffectApplyOutcome.REJECTED,
            store.apply(effect(source = otherSource, strength = 20, remainingRounds = 5)).outcome,
        )
        assertEquals(
            EffectApplyOutcome.REPLACED,
            store.apply(effect(source = otherSource, strength = 21, remainingRounds = 5)).outcome,
        )
        val active = store.effectsFor(target).single()
        assertEquals(otherSource, active.source)
        assertEquals(21, active.strength)
        assertEquals(5, active.remainingRounds)
        assertTrue(store.clear(target, 511, source).removed.isEmpty())
        assertEquals(listOf(511), store.clear(target, 511, otherSource).removed.map { it.effectId })
    }

    @Test
    fun `hit consumption remains source qualified after strongest replacement`() {
        val store = BattleEffectStore()
        store.apply(effect(strength = 10, remainingRounds = null, remainingHits = 1))
        store.apply(
            effect(
                source = otherSource,
                strength = 20,
                remainingRounds = null,
                remainingHits = 1,
            ),
        )

        assertTrue(store.consumeHit(target, 511, source).expired.isEmpty())
        assertEquals(
            listOf(otherSource),
            store.consumeHit(target, 511, otherSource).expired.map { it.source },
        )
    }

    @Test
    fun `conflict identity includes category group target and source skill kind`() {
        val store = BattleEffectStore()
        store.apply(effect())
        store.apply(effect(effectId = 521, conflict = 52))
        store.apply(effect(effectId = 531, category = EffectCategory.BENEFICIAL))
        store.apply(effect(effectId = 532, skillKind = SkillKind.COMMAND, sourceSkillType = 2))
        store.apply(effect(effectId = 533, target = otherTarget))

        assertEquals(4, store.effectsFor(target).size)
        assertEquals(1, store.effectsFor(otherTarget).size)
    }

    @Test
    fun `unknown and mismatched source skill types fail before insertion`() {
        assertFailsWith<IllegalArgumentException> {
            effect(skillKind = SkillKind.UNKNOWN, sourceSkillType = 14)
        }
        assertFailsWith<IllegalArgumentException> {
            effect(skillKind = SkillKind.ACTIVE, sourceSkillType = 14)
        }
        assertFailsWith<IllegalArgumentException> {
            effect(skillKind = SkillKind.ACTIVE, sourceSkillType = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            effect(skillKind = SkillKind.PASSIVE, sourceSkillType = 2)
        }
    }

    @Test
    fun `zero conflict group uses effect detail and origin identity instead of becoming global`() {
        val store = BattleEffectStore()
        store.apply(effect(conflict = 0, effectId = 511, detailId = 1001))
        store.apply(effect(conflict = 0, effectId = 512, detailId = 1002))
        store.apply(effect(conflict = 0, effectId = 511, detailId = 1001, source = otherSource))

        assertEquals(3, store.effectsFor(target).size)
    }

    @Test
    fun `zero conflict group remains isolated across detail and effect ids for the same origin`() {
        val store = BattleEffectStore()
        store.apply(effect(conflict = 0, effectId = 511, detailId = 1001))
        store.apply(effect(conflict = 0, effectId = 512, detailId = 1002))

        assertEquals(2, store.effectsFor(target).size)
    }

    @Test
    fun `aggregate strength is derived and detached snapshots preserve legitimate stacks`() {
        val constructorNames = ActiveSkillEffect::class.constructors
            .flatMap { constructor -> constructor.parameters.mapNotNull { it.name } }
        assertTrue("aggregateStrength" !in constructorNames)

        val store = BattleEffectStore()
        store.apply(effect(replaceType = 0, maxStacks = 3, strength = 10))
        store.apply(effect(replaceType = 0, maxStacks = 3, strength = 15))

        val snapshot = store.effectsFor(target).single()
        assertEquals(25, snapshot.effectiveStrength)

        val replacementStore = BattleEffectStore()
        replacementStore.apply(snapshot)
        assertEquals(
            EffectApplyOutcome.REJECTED,
            replacementStore.apply(effect(strength = 24)).outcome,
        )
        assertEquals(25, replacementStore.effectsFor(target).single().effectiveStrength)
        assertEquals(
            EffectApplyOutcome.REPLACED,
            replacementStore.apply(effect(strength = 26)).outcome,
        )
    }

    @Test
    fun `round effect expires only when round end is ticked`() {
        val store = BattleEffectStore()
        store.apply(effect(remainingRounds = 2))

        assertTrue(store.tick(EffectTickBoundary.ACTION_END).expired.isEmpty())
        assertEquals(1, store.tick(EffectTickBoundary.ROUND_END).updated.single().remainingRounds)
        val result = store.tick(EffectTickBoundary.ROUND_END)

        assertEquals(listOf(511), result.expired.map { it.effectId })
        assertTrue(store.effectsFor(target).isEmpty())
    }

    @Test
    fun `hit based effect expires after configured uses`() {
        val store = BattleEffectStore()
        store.apply(effect(remainingHits = 2, remainingRounds = null))

        assertEquals(1, store.consumeHit(target, 511).updated.single().remainingHits)
        assertEquals(listOf(511), store.consumeHit(target, 511).expired.map { it.effectId })
        assertTrue(store.effectsFor(target).isEmpty())
    }

    @Test
    fun `consuming a hit ignores effects without a hit lifecycle`() {
        val store = BattleEffectStore()
        store.apply(effect(remainingRounds = 2, remainingHits = null))

        val result = store.consumeHit(target, 511)

        assertTrue(result.updated.isEmpty())
        assertEquals(2, store.effectsFor(target).single().remainingRounds)
    }

    @Test
    fun `clear per hit expires on first matching hit`() {
        val store = BattleEffectStore()
        store.apply(effect(remainingHits = 5, remainingRounds = null, clearPerHit = true))

        assertEquals(listOf(511), store.consumeHit(target, 511).expired.map { it.effectId })
    }

    @Test
    fun `clearing a bound effect removes clearable siblings from exact source and target`() {
        val store = BattleEffectStore()
        store.apply(effect(effectId = 511, bindFlag = 7))
        store.apply(effect(effectId = 512, detailId = 1002, bindFlag = 7, conflict = 99))
        store.apply(effect(effectId = 513, detailId = 1003, bindFlag = 7, conflict = 98, source = otherSource))
        store.apply(effect(effectId = 514, detailId = 1004, bindFlag = 7, conflict = 97, target = otherTarget))
        store.apply(
            effect(effectId = 515, detailId = 1005, bindFlag = 7, conflict = 96, clearable = false),
        )

        val result = store.clear(target, 511, source)

        assertEquals(setOf(511, 512), result.removed.map { it.effectId }.toSet())
        assertEquals(setOf(513, 515), store.effectsFor(target).map { it.effectId }.toSet())
        assertEquals(listOf(514), store.effectsFor(otherTarget).map { it.effectId })
    }

    @Test
    fun `hit consumption is isolated by exact target and optional source`() {
        val store = BattleEffectStore()
        store.apply(effect(remainingRounds = null, remainingHits = 1))
        store.apply(
            effect(
                source = otherSource,
                detailId = 1002,
                conflict = 99,
                remainingRounds = null,
                remainingHits = 1,
            ),
        )
        store.apply(
            effect(
                target = otherTarget,
                detailId = 1003,
                remainingRounds = null,
                remainingHits = 1,
            ),
        )

        store.consumeHit(target, 511, source)

        assertEquals(listOf(otherSource), store.effectsFor(target).map { it.source })
        assertEquals(1, store.effectsFor(otherTarget).size)
    }

    @Test
    fun `invalid negative or zero lifecycle values fail fast`() {
        assertFailsWith<IllegalArgumentException> { effect(remainingRounds = -1) }
        assertFailsWith<IllegalArgumentException> { effect(remainingHits = -1) }
        assertFailsWith<IllegalArgumentException> { effect(remainingRounds = 0) }
        assertFailsWith<IllegalArgumentException> { effect(remainingHits = 0) }
        assertFailsWith<IllegalArgumentException> { effect(maxStacks = 0) }
        assertFailsWith<IllegalArgumentException> { effect(stacks = 0) }
        assertFailsWith<IllegalArgumentException> { effect(stacks = 2, maxStacks = 1) }
        assertFailsWith<IllegalArgumentException> { effect(replaceType = 4) }
    }

    @Test
    fun `returned collections and effects are detached immutable snapshots`() {
        val store = BattleEffectStore()
        val input = effect(remainingRounds = 2)
        store.apply(input)
        input.remainingRounds = 99

        val snapshot = store.effectsFor(target)
        snapshot.single().remainingRounds = 77
        assertEquals(2, store.effectsFor(target).single().remainingRounds)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableList<ActiveSkillEffect>).add(effect(effectId = 999))
        }

        val lifecycle = store.tick()
        assertEquals(1, lifecycle.updated.single().remainingRounds)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (lifecycle.updated as MutableList<ActiveSkillEffect>).add(effect(effectId = 998))
        }
    }

    @Test
    fun `clear matching keeps bound expansion inside the requested category`() {
        val store = BattleEffectStore()
        store.apply(
            effect(
                effectId = 501,
                detailId = 1501,
                category = EffectCategory.HARMFUL,
                bindFlag = 7,
            ),
        )
        store.apply(
            effect(
                effectId = 514,
                detailId = 1514,
                category = EffectCategory.BENEFICIAL,
                conflict = 52,
                bindFlag = 7,
            ),
        )
        store.apply(
            effect(
                source = otherSource,
                effectId = 502,
                detailId = 1502,
                category = EffectCategory.HARMFUL,
                conflict = 53,
                bindFlag = 7,
            ),
        )

        val removed = store.clearMatching(target) { it.category == EffectCategory.HARMFUL }

        assertEquals(setOf(501, 502), removed.removed.mapTo(mutableSetOf()) { it.effectId })
        assertEquals(listOf(514), store.effectsFor(target).map { it.effectId })
    }

    private fun effect(
        source: BattleHeroRef = this.source,
        target: BattleHeroRef = this.target,
        rootSkillId: Int = 100,
        skillId: Int = 100,
        skillKind: SkillKind = SkillKind.ACTIVE,
        sourceSkillType: Int = 3,
        detailId: Int = 1001,
        effectId: Int = 511,
        category: EffectCategory = EffectCategory.HARMFUL,
        conflict: Int = 51,
        strength: Int = 10,
        replaceType: Int = 2,
        bindFlag: Int = 0,
        maxStacks: Int = 1,
        stacks: Int = 1,
        remainingRounds: Int? = 2,
        remainingHits: Int? = null,
        clearPerHit: Boolean = false,
        clearable: Boolean = true,
    ) = ActiveSkillEffect(
        source = source,
        target = target,
        rootSkillId = rootSkillId,
        skillId = skillId,
        skillKind = skillKind,
        sourceSkillType = sourceSkillType,
        detailId = detailId,
        effectId = effectId,
        category = category,
        conflict = conflict,
        strength = strength,
        replaceType = replaceType,
        bindFlag = bindFlag,
        maxStacks = maxStacks,
        stacks = stacks,
        remainingRounds = remainingRounds,
        remainingHits = remainingHits,
        clearPerHit = clearPerHit,
        clearable = clearable,
    )
}
