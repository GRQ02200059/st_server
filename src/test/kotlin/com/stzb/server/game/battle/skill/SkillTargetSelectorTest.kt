package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.FixedBattleRandom
import com.stzb.server.game.battle.Side
import com.stzb.server.game.battle.SkillDetailConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillTargetSelectorTest {
    private val source = ref(Side.ATTACKER, 2, 100)
    private val allyBase = ref(Side.ATTACKER, 0, 101)
    private val allyMiddle = ref(Side.ATTACKER, 1, 102)
    private val enemyBase = ref(Side.DEFENDER, 0, 201)
    private val enemyMiddle = ref(Side.DEFENDER, 1, 202)
    private val enemyFront = ref(Side.DEFENDER, 2, 203)

    @Test
    fun `selector compiles every target and select code in target scope`() {
        val graph = SkillRuleCatalog.build(
            SkillScopeCatalog.loadDefault(),
            BattleConfigRepository.loadDefault(),
        )

        assertEquals(
            setOf(-30, -10, 0, 10, 20, 30, 42, 52, 53),
            graph.details.map { it.raw.targetType }.toSet(),
        )
        assertEquals(
            setOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 11, 33, 34, 900, 901, 907, 908, 3002),
            graph.details.map { it.raw.selectType }.toSet(),
        )

        graph.details.forEach { SkillTargetSelector().compile(it) }
    }

    @Test
    fun `attack type selects self allies own side and enemies from live view`() {
        val view = view()
        val context = context(view)

        assertEquals(listOf(source), select(rule(attackType = 0), context))
        assertEquals(
            listOf(allyBase, allyMiddle),
            select(rule(attackType = 13, selectType = 34, attackMax = 3), context),
        )
        assertEquals(
            listOf(allyBase, allyMiddle),
            select(rule(attackType = 23, selectType = 34, attackMax = 3), context),
        )
        assertEquals(
            listOf(allyBase, allyMiddle, source),
            select(rule(attackType = 24, selectType = 34, attackMax = 3), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(attackType = 43, selectType = 34, attackMax = 3), context),
            "enemy candidates use the source's live attack range",
        )
    }

    @Test
    fun `minimum and maximum selectors use live troops and four combat stats`() {
        val states = defaultStates().toMutableMap().apply {
            put(enemyBase, state(troops = 300, attack = 80, defense = 10, strategy = 70, speed = 40))
            put(enemyMiddle, state(troops = 100, attack = 60, defense = 30, strategy = 90, speed = 20))
            put(enemyFront, state(troops = 200, attack = 100, defense = 20, strategy = 50, speed = 60))
        }
        val context = context(view(states = states, sourceRange = 5))

        val expectedMinimum = mapOf(
            1 to enemyMiddle,
            2 to enemyBase,
            3 to enemyFront,
            4 to enemyMiddle,
            8 to enemyMiddle,
        )
        val expectedMaximum = mapOf(
            1 to enemyFront,
            2 to enemyMiddle,
            3 to enemyMiddle,
            4 to enemyFront,
            8 to enemyBase,
        )

        expectedMinimum.forEach { (attribute, expected) ->
            assertEquals(
                listOf(expected),
                select(rule(selectType = 1, selectAttri = attribute), context),
            )
        }
        expectedMaximum.forEach { (attribute, expected) ->
            assertEquals(
                listOf(expected),
                select(rule(selectType = 9, selectAttri = attribute), context),
            )
        }
    }

    @Test
    fun `position and farthest selectors use formation positions`() {
        val context = context(view(sourceRange = 5))

        assertEquals(listOf(enemyBase), select(rule(selectType = 4), context))
        assertEquals(listOf(enemyMiddle), select(rule(selectType = 5), context))
        assertEquals(listOf(enemyFront), select(rule(selectType = 6), context))
        assertEquals(listOf(enemyBase), select(rule(selectType = 3), context))
    }

    @Test
    fun `gender troop type category and country filters require exact metadata`() {
        val metadata = mapOf(
            source to metadata(SkillHeroGender.MALE, SkillTroopType.INFANTRY, country = 1),
            allyBase to metadata(SkillHeroGender.MALE, SkillTroopType.ARCHER, country = 2),
            allyMiddle to metadata(
                SkillHeroGender.FEMALE,
                SkillTroopType.CAVALRY,
                categories = setOf(SkillTroopCategory.ELEPHANT),
                country = 3,
            ),
            enemyBase to metadata(SkillHeroGender.MALE, SkillTroopType.ARCHER, country = 2),
            enemyMiddle to metadata(
                SkillHeroGender.FEMALE,
                SkillTroopType.INFANTRY,
                categories = setOf(SkillTroopCategory.RATTAN_ARMOR),
                country = 3,
            ),
            enemyFront to metadata(
                SkillHeroGender.MALE,
                SkillTroopType.CAVALRY,
                categories = setOf(SkillTroopCategory.BARBARIAN),
                country = 4,
            ),
        )
        val context = context(view(metadata = metadata, sourceRange = 5))

        assertEquals(listOf(allyBase), select(rule(attackType = 13, selectType = 7, attackMax = 3), context))
        assertEquals(listOf(allyMiddle), select(rule(attackType = 13, selectType = 8, attackMax = 3), context))
        assertEquals(listOf(enemyBase), select(rule(targetType = 10, selectType = 34, attackMax = 3), context))
        assertEquals(listOf(enemyMiddle), select(rule(targetType = 20, selectType = 34, attackMax = 3), context))
        assertEquals(listOf(enemyFront), select(rule(targetType = 30, selectType = 34, attackMax = 3), context))
        assertEquals(
            listOf(enemyMiddle, enemyBase),
            select(rule(targetType = -30, selectType = 34, attackMax = 3), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(targetType = -10, selectType = 34, attackMax = 3), context),
        )
        assertEquals(listOf(enemyMiddle), select(rule(targetType = 42, selectType = 34), context))
        assertEquals(listOf(enemyFront), select(rule(targetType = 52, selectType = 34), context))
        assertEquals(listOf(allyMiddle), select(rule(attackType = 13, targetType = 53, selectType = 34), context))
        assertEquals(
            listOf(enemyMiddle),
            select(rule(selectType = 34, targetCountry = 3, attackMax = 3), context),
        )
    }

    @Test
    fun `stateful selector codes use their approved live inputs exactly`() {
        val states = defaultStates().toMutableMap().apply {
            put(allyBase, state(morale = 110))
            put(allyMiddle, state(morale = 160))
            put(enemyBase, state(attackRange = 5))
        }
        val view = view(
            states = states,
            sourceRange = 2,
            damageDealt = mapOf(enemyBase to 900, enemyMiddle to 1_200, enemyFront to 600),
            linkedTarget = enemyFront,
            currentTarget = enemyMiddle,
            previousTargets = mapOf(allyBase to enemyBase),
        )
        val context = context(view)

        assertEquals(listOf(enemyFront), select(rule(selectType = 11), context))
        assertEquals(listOf(enemyMiddle), select(rule(selectType = 900), context))
        assertEquals(
            listOf(allyMiddle),
            select(rule(attackType = 13, selectType = 901), context),
        )
        assertEquals(listOf(enemyFront), select(rule(selectType = 907), context))
        assertEquals(listOf(enemyBase), select(rule(selectType = 908, attackMax = 2), context))
        assertEquals(
            listOf(enemyFront, enemyMiddle, enemyBase),
            select(rule(attackType = 23, selectType = 3002, attackMax = 3), context),
        )
        assertEquals(enemyBase, view.previousTarget(allyBase))
    }

    @Test
    fun `random candidates are stable and removed after each draw`() {
        val random = SequenceRandom(2, 0)
        val context = context(view(sourceRange = 5), random)

        assertEquals(
            listOf(enemyBase, enemyFront),
            select(rule(selectType = 0, attackMax = 2), context),
        )
        assertEquals(listOf(3, 2), random.bounds)
    }

    @Test
    fun `random group chooses deterministic positive count then samples without replacement`() {
        val random = SequenceRandom(1, 0, 1)
        val context = context(view(sourceRange = 5), random)

        val selected = select(rule(selectType = 33, attackMax = 3), context)

        assertEquals(listOf(enemyFront, enemyBase), selected)
        assertEquals(listOf(2, 3, 2), random.bounds)
        assertEquals(selected.size, selected.distinct().size)
    }

    @Test
    fun `defeated heroes are excluded unless live state explicitly permits targeting`() {
        val states = defaultStates().toMutableMap().apply {
            put(enemyBase, state(troops = 0))
            put(enemyMiddle, state(troops = 0, canReceiveEffectsWhenDefeated = true))
        }
        val context = context(view(states = states, sourceRange = 5))

        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(selectType = 34, attackMax = 3), context),
        )
    }

    @Test
    fun `missing linked targets do not degrade to random selection`() {
        val context = context(view(linkedTarget = null, sourceRange = 5))

        assertEquals(emptyList(), select(rule(selectType = 11), context))
    }

    @Test
    fun `metadata filters fail explicitly when live metadata is absent`() {
        val context = context(view(metadata = emptyMap(), sourceRange = 5))

        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                select(rule(targetType = 10, selectType = 34), context)
            }.message.orEmpty().contains("requires live hero metadata"),
        )
    }

    @Test
    fun `unknown target selector and attribute codes fail strictly`() {
        val selector = SkillTargetSelector()

        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                selector.compile(rule(targetType = 999))
            }.message.orEmpty().contains("target_type=999"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                selector.compile(rule(selectType = 999))
            }.message.orEmpty().contains("select_type=999"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                selector.compile(rule(selectType = 1, selectAttri = 99))
            }.message.orEmpty().contains("select_attri=99"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                selector.compile(rule(attackType = 999))
            }.message.orEmpty().contains("attack_type=999"),
        )
    }

    @Test
    fun `current and previous target attack types use explicit battle history`() {
        val view = view(
            currentTarget = enemyMiddle,
            previousTargets = mapOf(allyBase to enemyBase),
            sourceRange = 1,
        )
        val context = context(view)

        assertEquals(listOf(enemyMiddle), select(rule(attackType = 99), context))
        assertEquals(listOf(enemyMiddle), select(rule(attackType = 113), context))
        assertEquals(listOf(enemyBase), select(rule(attackType = 81), context))
    }

    private fun select(rule: SkillEffectRule, context: SkillBattleContext): List<BattleHeroRef> =
        SkillTargetSelector().compile(rule).select(context)

    private fun context(
        view: SkillBattleView,
        random: BattleRandom = FixedBattleRandom(0),
    ) = SkillBattleContext(
        request = com.stzb.server.game.battle.BattleRequest(
            attacker = com.stzb.server.game.battle.BattleTeam(emptyList()),
            defender = com.stzb.server.game.battle.BattleTeam(emptyList()),
        ),
        runtime = SkillRuntimeState(),
        random = random,
        round = 1,
        source = source,
        rootSkillId = 1,
        currentSkillId = 1,
        trigger = BattleTrigger.ACTIVE_SKILL_ATTEMPT,
        battleView = view,
    )

    private fun view(
        states: Map<BattleHeroRef, SkillBattleHeroState> = defaultStates(),
        metadata: Map<BattleHeroRef, SkillBattleHeroMetadata> =
            allRefs().associateWith { metadata(SkillHeroGender.MALE, SkillTroopType.INFANTRY) },
        sourceRange: Int = 2,
        damageDealt: Map<BattleHeroRef, Int> = emptyMap(),
        linkedTarget: BattleHeroRef? = enemyFront,
        currentTarget: BattleHeroRef? = enemyMiddle,
        previousTargets: Map<BattleHeroRef, BattleHeroRef> = emptyMap(),
    ): SkillBattleView {
        val liveStates = states.toMutableMap()
        liveStates[source] = liveStates.getValue(source).copy(attackRange = sourceRange)
        return FakeBattleView(
            refs = allRefs(),
            states = liveStates,
            metadata = metadata,
            damageDealt = damageDealt,
            linkedTarget = linkedTarget,
            currentTarget = currentTarget,
            previousTargets = previousTargets,
        )
    }

    private fun defaultStates(): Map<BattleHeroRef, SkillBattleHeroState> =
        allRefs().associateWith { state() }

    private fun allRefs() =
        listOf(source, allyBase, allyMiddle, enemyBase, enemyMiddle, enemyFront)

    private fun state(
        troops: Int = 1_000,
        attack: Int = 50,
        defense: Int = 50,
        strategy: Int = 50,
        speed: Int = 50,
        morale: Int = 100,
        attackRange: Int = 2,
        canReceiveEffectsWhenDefeated: Boolean = false,
    ) = SkillBattleHeroState(
        stats = BattleStats(attack, defense, strategy, speed, siege = 0, hitRange = attackRange),
        troops = troops,
        maxTroops = 1_000,
        statuses = emptySet(),
        morale = morale,
        attackRange = attackRange,
        canReceiveEffectsWhenDefeated = canReceiveEffectsWhenDefeated,
    )

    private fun metadata(
        gender: SkillHeroGender,
        troopType: SkillTroopType,
        categories: Set<SkillTroopCategory> = emptySet(),
        country: Int = 0,
    ) = SkillBattleHeroMetadata(gender, troopType, categories, country)

    private fun rule(
        attackType: Int = 43,
        targetType: Int = 0,
        selectType: Int = 0,
        selectAttri: Int = 0,
        targetCountry: Int = 0,
        attackMax: Int = 1,
    ) = SkillEffectRule(
        detailId = 1,
        effectId = 301,
        childSkillIds = emptySet(),
        raw = SkillDetailConfig(
            detailId = 1,
            effectId = 301,
            attackType = attackType,
            targetType = targetType,
            selectType = selectType,
            intelParam = 0,
            constantParam = 0,
            probabilityInit = 100,
            probabilityMax = 100,
            attackMax = attackMax,
            availableRounds = 0,
            effectName = "",
            selectAttri = selectAttri,
            targetCountry = targetCountry,
        ),
    )

    private fun ref(side: Side, position: Int, heroId: Int) =
        BattleHeroRef(side, position, BattleHeroId(heroId))

    private class SequenceRandom(vararg values: Int) : BattleRandom {
        private val values = ArrayDeque(values.toList())
        val bounds = mutableListOf<Int>()

        override fun nextInt(bound: Int): Int {
            bounds += bound
            return values.removeFirst()
        }
    }

    private class FakeBattleView(
        private val refs: List<BattleHeroRef>,
        private val states: Map<BattleHeroRef, SkillBattleHeroState>,
        private val metadata: Map<BattleHeroRef, SkillBattleHeroMetadata>,
        private val damageDealt: Map<BattleHeroRef, Int>,
        private val linkedTarget: BattleHeroRef?,
        private val currentTarget: BattleHeroRef?,
        private val previousTargets: Map<BattleHeroRef, BattleHeroRef>,
    ) : SkillBattleView {
        override fun heroes(): List<BattleHeroRef> = refs
        override fun state(ref: BattleHeroRef): SkillBattleHeroState? = states[ref]
        override fun metadata(ref: BattleHeroRef): SkillBattleHeroMetadata? = metadata[ref]
        override fun accumulatedDamageDealt(ref: BattleHeroRef): Int = damageDealt[ref] ?: 0
        override fun linkedTarget(source: BattleHeroRef): BattleHeroRef? = linkedTarget
        override fun currentTarget(source: BattleHeroRef): BattleHeroRef? = currentTarget
        override fun previousTarget(source: BattleHeroRef): BattleHeroRef? = previousTargets[source]
    }
}
