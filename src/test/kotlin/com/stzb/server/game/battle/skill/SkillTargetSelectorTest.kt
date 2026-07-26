package com.stzb.server.game.battle.skill

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleHeroId
import com.stzb.server.game.battle.BattleHeroRef
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleStats
import com.stzb.server.game.battle.BattleStatus
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
    fun `every attack type has its structurally evidenced candidate scope`() {
        val view = view(previousTargets = mapOf(allyBase to enemyBase))
        val context = context(view)

        val expected = mapOf(
            0 to listOf(source),
            11 to listOf(allyBase, allyMiddle),
            13 to listOf(allyBase, allyMiddle),
            21 to listOf(allyBase, allyMiddle, source),
            23 to listOf(allyBase, allyMiddle),
            24 to listOf(allyBase, allyMiddle, source),
            41 to listOf(enemyFront, enemyMiddle),
            43 to listOf(enemyFront, enemyMiddle),
            94 to listOf(enemyFront, enemyMiddle),
            95 to listOf(enemyFront, enemyMiddle),
            96 to listOf(enemyFront, enemyMiddle),
            97 to listOf(enemyFront, enemyMiddle),
            98 to listOf(enemyFront),
            99 to listOf(enemyMiddle),
            113 to listOf(allyBase, allyMiddle, enemyFront, enemyMiddle, enemyBase),
            81 to listOf(enemyBase),
        )
        expected.forEach { (attackType, candidates) ->
            assertEquals(
                candidates,
                select(
                    rule(
                        attackType = attackType,
                        selectType = 34,
                        attackMax = 1,
                        skillHitRange = if (attackType in setOf(41, 43, 94, 95, 96, 97)) 2 else null,
                    ),
                    context,
                ),
                "attack_type=$attackType",
            )
        }
    }

    @Test
    fun `beneficial other ally selector falls back to source when no teammate exists`() {
        val soloView = FakeBattleView(
            refs = listOf(source, enemyBase),
            states = mapOf(source to state(attackRange = 5), enemyBase to state()),
            metadata = mapOf(
                source to metadata(SkillHeroGender.MALE, SkillTroopType.INFANTRY),
                enemyBase to metadata(SkillHeroGender.MALE, SkillTroopType.INFANTRY),
            ),
            damageDealt = emptyMap(),
            linkedTarget = enemyBase,
            currentTarget = enemyBase,
            previousTargets = emptyMap(),
            acceptedStateFilters = emptyMap(),
            activeEffects = emptyMap(),
        )
        val context = context(soloView)

        assertEquals(
            listOf(source),
            select(
                rule(
                    detailId = 20000101,
                    attackType = 11,
                    selectType = 0,
                    effectBuffType = 2,
                ),
                context,
            ),
        )
        assertTrue(
            select(
                rule(
                    detailId = 20000101,
                    attackType = 11,
                    selectType = 0,
                    effectBuffType = 1,
                ),
                context,
            ).isEmpty(),
        )
    }

    @Test
    fun `generic beneficial other ally selector does not fall back to source`() {
        val context = context(
            view(
                states = mapOf(
                    source to state(),
                    enemyBase to state(),
                ),
            ),
        )

        assertTrue(
            select(
                rule(
                    detailId = 99999901,
                    attackType = 11,
                    selectType = 0,
                    effectBuffType = 2,
                ),
                context,
            ).isEmpty(),
        )
    }

    @Test
    fun `precondition 80 and minus 80 filter mixed candidates by allegiance`() {
        val context = context(view(sourceRange = 5))

        assertEquals(
            listOf(allyBase, allyMiddle),
            select(rule(attackType = 113, selectType = 34, precondition = 80), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle, enemyBase),
            select(rule(attackType = 113, selectType = 34, precondition = -80), context),
        )
    }

    @Test
    fun `precondition 70 compares each target morale with source morale`() {
        val states = defaultStates().toMutableMap().apply {
            put(source, state(morale = 100))
            put(enemyBase, state(morale = 90))
            put(enemyMiddle, state(morale = 100))
            put(enemyFront, state(morale = 110))
        }
        val context = context(view(states = states, sourceRange = 5))

        assertEquals(
            listOf(enemyBase),
            select(rule(selectType = 34, precondition = 70), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(selectType = 34, precondition = -70), context),
        )
    }

    @Test
    fun `hero id precondition retains only the configured hero card`() {
        val wanted = ref(Side.DEFENDER, 1, 100010)
        val states = defaultStates() + (wanted to state())
        val customView = FakeBattleView(
            refs = allRefs() + wanted,
            states = states,
            metadata = (allRefs() + wanted).associateWith {
                metadata(SkillHeroGender.MALE, SkillTroopType.INFANTRY)
            },
            damageDealt = emptyMap(),
            linkedTarget = null,
            currentTarget = null,
            previousTargets = emptyMap(),
            acceptedStateFilters = emptyMap(),
            activeEffects = emptyMap(),
        )

        assertEquals(
            listOf(wanted),
            select(
                rule(
                    attackType = 113,
                    selectType = 34,
                    precondition = 100010,
                    skillHitRange = null,
                ),
                context(customView),
            ),
        )
    }

    @Test
    fun `position preconditions select base non base and front positions`() {
        val context = context(view(sourceRange = 5))

        assertEquals(
            listOf(enemyBase),
            select(rule(selectType = 34, precondition = 14), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(selectType = 34, precondition = -14), context),
        )
        assertEquals(
            listOf(enemyFront),
            select(rule(selectType = 34, precondition = 16), context),
        )
    }

    @Test
    fun `condition troop ratios filter each candidate at exact boundaries`() {
        val states = defaultStates().toMutableMap().apply {
            put(enemyBase, state(troops = 490))
            put(enemyMiddle, state(troops = 500))
            put(enemyFront, state(troops = 610))
        }
        val context = context(view(states = states, sourceRange = 5))

        assertEquals(
            listOf(enemyBase),
            select(rule(selectType = 34, condition = 1050), context),
        )
        assertEquals(
            listOf(enemyFront),
            select(rule(selectType = 34, condition = 2060), context),
        )
    }

    @Test
    fun `cast status conditions filter control and ongoing damage targets`() {
        val states = defaultStates().toMutableMap().apply {
            put(enemyBase, state(statuses = setOf(BattleStatus.CONFUSION)))
            put(enemyMiddle, state(statuses = setOf(BattleStatus.BURN, BattleStatus.HEX)))
            put(enemyFront, state(statuses = setOf(BattleStatus.HESITATION)))
        }
        val context = context(view(states = states, sourceRange = 5))

        assertEquals(
            listOf(enemyBase),
            select(rule(selectType = 34, castCondition = 500), context),
        )
        assertEquals(
            listOf(enemyFront, enemyBase),
            select(rule(selectType = 34, castCondition = 4000), context),
        )
        assertEquals(
            listOf(enemyMiddle),
            select(rule(selectType = 34, castCondition = 7001), context),
        )
        assertEquals(
            listOf(enemyMiddle),
            select(rule(selectType = 34, condition = 18306), context),
        )
    }

    @Test
    fun `condition 20160 retains targets below 160 morale`() {
        val states = defaultStates().toMutableMap().apply {
            put(allyBase, state(morale = 159))
            put(allyMiddle, state(morale = 160))
        }

        assertEquals(
            listOf(allyBase),
            select(
                rule(attackType = 13, selectType = 34, condition = 20160),
                context(view(states = states, sourceRange = 5)),
            ),
        )
    }

    @Test
    fun `morale band preconditions split high from normal or low morale`() {
        val states = defaultStates().toMutableMap().apply {
            put(enemyBase, state(morale = 101))
            put(enemyMiddle, state(morale = 100))
            put(enemyFront, state(morale = 99))
        }
        val context = context(view(states = states, sourceRange = 5))

        assertEquals(
            listOf(enemyBase),
            select(rule(selectType = 34, precondition = 2099), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(selectType = 34, precondition = 3100), context),
        )
    }

    @Test
    fun `special troop preconditions include barbarian rattan and elephant categories`() {
        val metadata = allRefs().associateWith {
            when (it) {
                enemyBase -> metadata(
                    SkillHeroGender.MALE,
                    SkillTroopType.INFANTRY,
                    categories = setOf(SkillTroopCategory.RATTAN_ARMOR),
                )
                enemyMiddle -> metadata(
                    SkillHeroGender.MALE,
                    SkillTroopType.CAVALRY,
                    categories = setOf(SkillTroopCategory.ELEPHANT),
                )
                enemyFront -> metadata(
                    SkillHeroGender.MALE,
                    SkillTroopType.CAVALRY,
                    categories = setOf(SkillTroopCategory.BARBARIAN),
                )
                else -> metadata(SkillHeroGender.MALE, SkillTroopType.INFANTRY)
            }
        }
        val context = context(view(metadata = metadata, sourceRange = 5))

        assertEquals(
            listOf(enemyFront, enemyMiddle, enemyBase),
            select(rule(selectType = 34, precondition = 6000), context),
        )
        assertEquals(
            emptyList(),
            select(rule(selectType = 34, precondition = -6000), context),
        )
    }

    @Test
    fun `attribute cast conditions filter candidates using exact live combat stats`() {
        val states = defaultStates().toMutableMap().apply {
            put(source, state(attack = 80, strategy = 90, speed = 50))
            put(enemyBase, state(attack = 110, strategy = 100, speed = 40))
            put(enemyMiddle, state(attack = 70, strategy = 80, speed = 50))
            put(enemyFront, state(attack = 70, strategy = 70, speed = 60))
        }
        val context = context(view(states = states, sourceRange = 5))

        assertEquals(
            listOf(enemyFront, enemyBase),
            select(rule(selectType = 34, castCondition = 3103), context),
        )
        assertEquals(
            listOf(enemyMiddle),
            select(rule(selectType = 34, castCondition = 3123), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(selectType = 34, castCondition = 2313), context),
        )
        assertEquals(
            listOf(enemyBase),
            select(rule(selectType = 34, castCondition = 2414), context),
        )
        assertEquals(
            listOf(enemyFront, enemyMiddle),
            select(rule(selectType = 34, castCondition = 2434), context),
        )
    }

    @Test
    fun `berserk cast condition accepts only candidates with berserk effect`() {
        val context = context(
            view(
                sourceRange = 5,
                activeEffects = mapOf(enemyMiddle to setOf(503)),
            ),
        )

        assertEquals(
            listOf(enemyMiddle),
            select(rule(selectType = 34, castCondition = 4003), context),
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
    fun `select flag codes are explicitly delegated to typed live filters`() {
        val accepted = mapOf(
            SkillTargetStateFilter.FLAG_1 to enemyBase,
            SkillTargetStateFilter.FLAG_2 to enemyMiddle,
            SkillTargetStateFilter.FLAG_3 to enemyFront,
            SkillTargetStateFilter.FLAG_99 to enemyMiddle,
        )
        val view = view(sourceRange = 5, acceptedStateFilters = accepted)
        val context = context(view)

        accepted.forEach { (filter, expected) ->
            assertEquals(
                listOf(expected),
                select(rule(selectType = 34, attackMax = 3, selectFlag = filter.rawCode), context),
                "select_flag=${filter.rawCode}",
            )
        }
        assertEquals(
            emptyList(),
            select(rule(selectType = 34, attackMax = 3, selectFlag = 1), context(view())),
        )
    }

    @Test
    fun `ordinary skill range comes from compiled parent skill while 907 and 908 use live normal range`() {
        val context = context(view(sourceRange = 1))

        assertEquals(
            listOf(enemyFront, enemyMiddle, enemyBase),
            select(rule(selectType = 34, attackMax = 3, skillHitRange = 5), context),
        )
        assertEquals(
            listOf(enemyFront),
            select(rule(selectType = 907, attackMax = 3, skillHitRange = 5), context),
        )
        assertEquals(
            listOf(enemyMiddle, enemyBase),
            select(rule(selectType = 908, attackMax = 3, skillHitRange = 1), context),
        )
    }

    @Test
    fun `entry snapshot advertises only snapshot capabilities and rejects unavailable live data`() {
        val request = com.stzb.server.game.battle.BattleRequest(
            attacker = com.stzb.server.game.battle.BattleTeam(
                listOf(
                    com.stzb.server.game.battle.BattleHero(
                        id = source.heroId,
                        position = source.position,
                        stats = BattleStats(1, 1, 1, 1, 0, 2),
                        troops = 100,
                    ),
                ),
            ),
            defender = com.stzb.server.game.battle.BattleTeam(emptyList()),
        )
        val view = SkillBattleView.entrySnapshot(request)

        assertEquals(
            setOf(SkillBattleViewCapability.HERO_ROSTER, SkillBattleViewCapability.ENTRY_STATE),
            view.capabilities,
        )
        assertTrue(view.entryState(source) != null)
        assertFailsWith<MissingLiveBattleViewData> { view.state(source) }
        assertFailsWith<MissingLiveBattleViewData> { view.metadata(source) }
        assertFailsWith<MissingLiveBattleViewData> { view.accumulatedDamageDealt(source) }
        assertFailsWith<MissingLiveBattleViewData> { view.currentMorale(source) }
        assertFailsWith<MissingLiveBattleViewData> { view.currentAttackRange(source) }
        assertFailsWith<MissingLiveBattleViewData> { view.linkedTarget(source) }
        assertFailsWith<MissingLiveBattleViewData> { view.currentTarget(source) }
        assertFailsWith<MissingLiveBattleViewData> { view.previousTarget(source) }
        assertFailsWith<MissingLiveBattleViewData> {
            view.matchesStateFilter(SkillTargetStateFilter.FLAG_1, source, source)
        }
    }

    @Test
    fun `special seeds still pass filters and configured cardinality`() {
        val states = defaultStates().toMutableMap().apply {
            put(enemyMiddle, state(troops = 0))
        }
        val metadata = allRefs().associateWith {
            metadata(
                SkillHeroGender.MALE,
                if (it == enemyFront) SkillTroopType.ARCHER else SkillTroopType.INFANTRY,
                country = if (it == enemyBase) 2 else 1,
            )
        }
        val context = context(
            view(
                states = states,
                metadata = metadata,
                linkedTarget = enemyFront,
                currentTarget = enemyMiddle,
                sourceRange = 5,
            ),
        )

        assertEquals(emptyList(), select(rule(selectType = 11, targetType = 20), context))
        assertEquals(
            listOf(enemyFront),
            select(rule(selectType = 3002, attackMax = 1, targetCountry = 1), context),
        )
        assertEquals(
            listOf(enemyFront, enemyBase),
            select(rule(selectType = 3002, attackMax = 2), context),
        )
        assertEquals(
            listOf(enemyFront),
            select(rule(selectType = 3002, attackMax = 3, skillHitRange = 1), context),
        )
    }

    @Test
    fun `select all returns the complete filtered scope regardless of attack max`() {
        assertEquals(
            listOf(enemyFront, enemyMiddle, enemyBase),
            select(rule(selectType = 34, attackMax = 1, skillHitRange = 5), context(view(sourceRange = 1))),
        )
    }

    @Test
    fun `attribute ties preserve stable client position order`() {
        val context = context(view(sourceRange = 5))

        assertEquals(listOf(enemyFront), select(rule(selectType = 1, selectAttri = 1), context))
        assertEquals(listOf(enemyFront), select(rule(selectType = 9, selectAttri = 1), context))
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
        assertEquals(
            5,
            select(rule(attackType = 113, attackMax = 5), context).size,
        )
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
        acceptedStateFilters: Map<SkillTargetStateFilter, BattleHeroRef> = emptyMap(),
        activeEffects: Map<BattleHeroRef, Set<Int>> = emptyMap(),
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
            acceptedStateFilters = acceptedStateFilters,
            activeEffects = activeEffects,
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
        statuses: Set<BattleStatus> = emptySet(),
    ) = SkillBattleHeroState(
        stats = BattleStats(attack, defense, strategy, speed, siege = 0, hitRange = attackRange),
        troops = troops,
        maxTroops = 1_000,
        statuses = statuses,
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
        detailId: Int = 1,
        attackType: Int = 43,
        targetType: Int = 0,
        selectType: Int = 0,
        selectAttri: Int = 0,
        targetCountry: Int = 0,
        attackMax: Int = 1,
        selectFlag: Int = 0,
        skillHitRange: Int? = null,
        effectBuffType: Int = 1,
        precondition: Int = 0,
        condition: Int = 0,
        castCondition: Int = 0,
    ) = SkillEffectRule(
        detailId = detailId,
        effectId = 301,
        childSkillIds = emptySet(),
        raw = SkillDetailConfig(
            detailId = detailId,
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
            selectFlag = selectFlag,
            precondition = precondition,
            condition = condition,
            castCondition = castCondition,
        ),
        skillHitRange = skillHitRange,
        effectBuffType = effectBuffType,
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
        private val acceptedStateFilters: Map<SkillTargetStateFilter, BattleHeroRef>,
        private val activeEffects: Map<BattleHeroRef, Set<Int>>,
    ) : SkillBattleView {
        override val capabilities: Set<SkillBattleViewCapability> =
            SkillBattleViewCapability.entries.toSet()
        override fun heroes(): List<BattleHeroRef> = refs
        override fun entryState(ref: BattleHeroRef): SkillBattleHeroState? = states[ref]
        override fun state(ref: BattleHeroRef): SkillBattleHeroState? = states[ref]
        override fun metadata(ref: BattleHeroRef): SkillBattleHeroMetadata? = metadata[ref]
        override fun accumulatedDamageDealt(ref: BattleHeroRef): Int = damageDealt[ref] ?: 0
        override fun currentMorale(ref: BattleHeroRef): Int? = states[ref]?.morale
        override fun currentAttackRange(ref: BattleHeroRef): Int? = states[ref]?.attackRange
        override fun linkedTarget(source: BattleHeroRef): BattleHeroRef? = linkedTarget
        override fun currentTarget(source: BattleHeroRef): BattleHeroRef? = currentTarget
        override fun previousTarget(source: BattleHeroRef): BattleHeroRef? = previousTargets[source]
        override fun matchesStateFilter(
            filter: SkillTargetStateFilter,
            source: BattleHeroRef,
            target: BattleHeroRef,
        ): Boolean = acceptedStateFilters[filter] == target

        override fun activeEffectIds(ref: BattleHeroRef): Set<Int> = activeEffects[ref].orEmpty()
    }
}
