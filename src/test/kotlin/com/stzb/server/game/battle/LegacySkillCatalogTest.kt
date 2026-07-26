package com.stzb.server.game.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LegacySkillCatalogTest {
    private val config = BattleConfigRepository.loadDefault()
    private val runtime = BattleSkillRuntime(config)
    private val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100479))

    @Test
    fun `first eight reference skills map to client skill ids`() {
        val expected = mapOf(
            1001 to 200223,
            1002 to 200208,
            1004 to 200088,
            1005 to 200233,
            1006 to 200658,
            1007 to 200016,
            1008 to 200027,
        )

        expected.forEach { (legacyId, clientId) ->
            val definition = assertNotNull(LegacySkillCatalog.findLegacy(legacyId))
            assertTrue(clientId in definition.clientSkillIds)
        }
        assertEquals("血溅黄砂", LegacySkillCatalog.findLegacy(1003)?.name)
    }

    @Test
    fun `legacy compatibility runtime no longer invokes handwritten skill definition`() {
        val source = hero(200223)
        val cast = runtime.tryAct(
            round = 1,
            sourceRef = sourceRef,
            source = source,
            targets = BattleTeam(listOf(enemy())),
            allies = BattleTeam(listOf(source)),
            random = FixedBattleRandom(0),
            state = SkillRuntimeState(),
            allowedKinds = setOf(SkillKind.ACTIVE),
        )

        assertTrue(cast!!.events.any {
            it is BattleEvent.StatusApplied && it.status == BattleStatus.DOUBLE_ATTACK
        })
        assertTrue(cast.events.none { it is BattleEvent.UnsupportedSkillEffect })
    }

    @Test
    fun `complete engine executes lianzhan from configuration without unsupported fallback`() {
        val result = BattleEngine.resolve(
            BattleRequest(
                attacker = BattleTeam(listOf(hero(200223))),
                defender = BattleTeam(listOf(enemy())),
                maxRounds = 1,
            ),
            config,
            FixedBattleRandom(0),
        )

        assertTrue(result.events.any {
            it is BattleEvent.SkillTriggered && it.skillId == 200223
        })
        assertTrue(result.events.any {
            it is BattleEvent.StatusApplied &&
                it.skillId == 200223 &&
                it.status == BattleStatus.DOUBLE_ATTACK
        })
        assertTrue(result.events.none { it is BattleEvent.UnsupportedSkillEffect })
    }

    @Test
    fun `legacy xianqu reference remains documented`() {
        val source = hero(200233)
        val cast = LegacySkillCatalog.findClient(200233)!!.execute(
            LegacySkillContext(
                round = 0,
                skillId = 200233,
                sourceRef = sourceRef,
                source = source,
                enemies = BattleTeam(listOf(enemy())),
                allies = BattleTeam(listOf(source)),
                random = FixedBattleRandom(0),
            ),
        )

        assertTrue(cast.events.filterIsInstance<BattleEvent.StatusApplied>().any {
            it.status == BattleStatus.FIRST_ACTION && it.durationRounds == 3
        })
        assertTrue(cast.events.filterIsInstance<BattleEvent.StatusApplied>().any {
            it.status == BattleStatus.DOUBLE_ATTACK && it.durationRounds == 3
        })
        assertEquals(30, cast.selfStatDelta.attack)
    }

    @Test
    fun `legacy qijirufeng reference remains documented`() {
        val source = hero(200027)
        val ally = source.copy(id = BattleHeroId(100017), position = 1)
        val cast = LegacySkillCatalog.findClient(200027)!!.execute(
            LegacySkillContext(
                round = 0,
                skillId = 200027,
                sourceRef = sourceRef,
                source = source,
                enemies = BattleTeam(listOf(enemy())),
                allies = BattleTeam(listOf(source, ally)),
                random = FixedBattleRandom(0),
            ),
        )

        val statuses = cast.events.filterIsInstance<BattleEvent.StatusApplied>()
        assertEquals(2, statuses.count { it.status == BattleStatus.DOUBLE_ATTACK })
        assertEquals(2, statuses.count { it.status == BattleStatus.SPEED_BUFF })
    }

    @Test
    fun `all thirty two reference skills have executable definitions`() {
        (1001..1032).forEach { legacyId ->
            val definition = assertNotNull(LegacySkillCatalog.findLegacy(legacyId), "missing $legacyId")
            assertTrue(definition.clientSkillIds.isNotEmpty(), "${definition.name} lacks client id")
            val source = hero(definition.clientSkillIds.first())
            val result = definition.execute(
                LegacySkillContext(
                    round = if (legacyId in commandOrPassiveSkills) 0 else 1,
                    skillId = definition.clientSkillIds.first(),
                    sourceRef = sourceRef,
                    source = source,
                    enemies = BattleTeam(listOf(enemy())),
                    allies = BattleTeam(listOf(source)),
                    random = FixedBattleRandom(0),
                ),
            )
            assertTrue(result.events.isNotEmpty(), "${definition.name} has no battle effect")
            assertTrue(result.events.none { it is BattleEvent.UnsupportedSkillEffect })
        }
    }

    private fun hero(skillId: Int) = BattleHero(
        id = sourceRef.heroId,
        position = sourceRef.position,
        stats = BattleStats(100, 100, 100, 100, 0, 5),
        troops = 1_000,
        skillIds = listOf(skillId),
    )

    private fun enemy() = BattleHero(
        id = BattleHeroId(2),
        position = 0,
        stats = BattleStats(50, 50, 50, 50, 0, 5),
        troops = 1_000,
    )

    private companion object {
        val commandOrPassiveSkills = setOf(
            1003, 1005, 1007, 1008, 1009, 1010, 1012, 1013, 1015, 1016,
            1017, 1018, 1019, 1020, 1021, 1023, 1024, 1025, 1028, 1030, 1031, 1032,
        )
    }
}
