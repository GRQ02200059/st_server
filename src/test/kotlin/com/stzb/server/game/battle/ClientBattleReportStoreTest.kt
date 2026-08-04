package com.stzb.server.game.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClientBattleReportStoreTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `default report uses its configured random source`() {
        var requestedSeed: Int? = null
        val store = ClientBattleReportStore.createDefault(
            nowSec = 1_700_000_000,
            battleRandomFactory = { seed ->
                requestedSeed = seed
                FixedBattleRandom(0)
            },
        )

        val report = store.getOrCreateDefault()

        assertEquals(report.battleId xor report.timeSec, requestedSeed)
    }

    @Test
    fun `default three hero replay has complete client action segments`() {
        val report = ClientBattleReportStore.createDefault(
            nowSec = 1_700_000_000,
            battleRandomFactory = { FixedBattleRandom(0) },
        ).getOrCreateDefault()
        val actions = ClientBattleTextReplayAdapter.adapt(report.result)

        assertEquals(
            (1..6).toList(),
            actions.filter { it.id == ClientBattleTextReplayProtocol.HERO_NAME }
                .map { it.params.first() },
        )
        assertTrue(actions.any { it.id == ClientBattleTextReplayProtocol.SKILL_PREPARATION_STARTED })
        val actionSources = actions
            .filter { it.id == ClientBattleTextReplayProtocol.HERO_ACTION_START }
            .map { it.params.first() as Int }
            .toSet()
        assertTrue(actionSources.any { it in 1..3 })
        assertTrue(actionSources.any { it in 4..6 })
        assertTrue(actionSources.all { it in 1..6 })
        assertEquals(
            actions.count { it.id == ClientBattleTextReplayProtocol.SKILL_BEGIN },
            actions.count { it.id == ClientBattleTextReplayProtocol.SKILL_END },
        )
        assertActionSegmentsAreBalanced(actions)

        val normalAttackSources = actions
            .filter { it.id == ClientBattleTextReplayProtocol.NORMAL_ATTACK }
            .map { it.params[0] as Int }
        assertTrue(normalAttackSources.any { it in 1..3 })
        assertTrue(normalAttackSources.any { it in 4..6 })
    }

    @Test
    fun `default replay does not inject disorder or unexplained panic damage`() {
        val result = ClientBattleReportStore.createDefault(
            nowSec = 1_700_000_000,
            battleRandomFactory = { FixedBattleRandom(0) },
        ).getOrCreateDefault().result

        assertTrue(result.attacker.heroes.none { 200002 in it.skillIds })
        assertTrue(result.defender.heroes.none { 200002 in it.skillIds })
        assertTrue(
            result.events.none {
                it is BattleEvent.StatusApplied &&
                    it.skillId == 200002
            },
        )
        assertTrue(result.events.filterIsInstance<BattleEvent.NormalAttack>().isNotEmpty())
    }

    @Test
    fun `independent report stores do not reuse cached battle ids`() {
        val firstStore = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val secondStore = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)

        val firstDefault = firstStore.getOrCreateDefault()
        val secondDefault = secondStore.getOrCreateDefault()
        val firstBattle = firstStore.record(wid = 1, timeSec = 1_700_000_000, result = firstDefault.result)
        val secondBattle = secondStore.record(wid = 1, timeSec = 1_700_000_000, result = secondDefault.result)

        assertNotEquals(firstDefault.battleId, secondDefault.battleId)
        assertNotEquals(firstBattle.battleId, secondBattle.battleId)
    }

    @Test
    fun `a user cannot resolve another users battle report`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val base = store.getOrCreateDefault(ownerUserId = 10001)
        val owned = store.record(
            ownerUserId = 10001,
            wid = 1,
            timeSec = 1_700_000_000,
            result = base.result,
        )

        val resolved = store.findOrDefault(ownerUserId = 10002, battleId = owned.battleId)

        assertNotEquals(owned.battleId, resolved.battleId)
        assertEquals(10002, resolved.ownerUserId)
    }

    @Test
    fun `provides client profile response for requested battle ids`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val report = store.getOrCreateDefault()

        val profileJson = store.profileResponse(listOf(report.battleId), serverId = 0)
        val root = mapper.readTree(profileJson)

        assertEquals(0, root[0].asInt())
        assertEquals(1, root[1].size())
        val profile = root[1][0]
        assertEquals(report.battleId, profile["battle_id"].asInt())
        assertEquals(report.wid, profile["wid"].asInt())
        assertTrue(profile["attack_all_hero_info"].asText().isNotBlank())
        assertTrue(profile["defend_all_hero_info"].asText().isNotBlank())
        assertTrue(profile["attack_all_hero_info"].asText().contains(";"))
        assertTrue(profile["defend_all_hero_info"].asText().contains(";"))
        assertEquals("", profile["attacker_base_hero_detail"].asText())
        assertEquals("", profile["defender_base_hero_detail"].asText())
        assertTrue(profile["attack_all_hero_info"].asText().split(";").all { it.split(",").size == 5 })
        assertTrue(profile["defend_all_hero_info"].asText().split(";").all { it.split(",").size == 5 })
        assertEquals("0,0,0,0,0", profile["attack_idu"].asText())
        assertEquals("0,0,0,0,0", profile["defend_idu"].asText())
        assertTrue(profile["attacker_surface"].asText().split(";").all { it.split(",").size == 3 })
        assertTrue(profile["defender_surface"].asText().split(";").all { it.split(",").size == 3 })
    }

    @Test
    fun `profile encodes frozen hero card border and dynamic icon surfaces`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val base = store.getOrCreateDefault()
        val report = store.record(
            ownerUserId = 10001,
            wid = 10001,
            timeSec = 1_700_000_001,
            result = base.result,
            attackerSurfaces = listOf(
                BattleHeroSurface(
                    heroId = 100017,
                    position = 0,
                    cardBorder = 101260,
                    dynamicIcon = 100534,
                ),
                BattleHeroSurface(
                    heroId = 100023,
                    position = 1,
                    cardBorder = 110997,
                    dynamicIcon = 0,
                ),
            ),
        )

        val profile = mapper.readTree(
            store.profileResponse(listOf(report.battleId), serverId = 0),
        )[1][0]

        assertEquals(
            "100017,100534;100023,0;0,0",
            profile["attack_all_surface"].asText(),
        )
        assertEquals(
            "0,0,0;101260,100534,0;110997,0,0;0,0,0",
            profile["attacker_surface"].asText(),
        )
        assertEquals("0,0;0,0;0,0", profile["defend_all_surface"].asText())
        assertEquals("0,0,0;0,0,0;0,0,0;0,0,0", profile["defender_surface"].asText())
    }

    @Test
    fun `profile encodes a normal battle draw with the client result six`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val base = store.getOrCreateDefault()
        val draw = store.record(
            wid = 10002,
            timeSec = 1_700_000_001,
            result = base.result.copy(outcome = BattleOutcome.DRAW),
        )

        val profile = mapper.readTree(store.profileResponse(listOf(draw.battleId), serverId = 0))[1][0]

        assertEquals(6, profile["result"].asInt())
    }

    @Test
    fun `provides compressed client detail response`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val report = store.getOrCreateDefault()

        val detailJson = store.detailResponse(report.battleId, serverId = 0)
        val root = mapper.readTree(detailJson)

        assertEquals(0, root[0].asInt())
        assertEquals(report.battleId, root[1]["battle_id"].asInt())
        assertTrue(root[1]["report"].asText().startsWith("zzz"))
        assertEquals(1, root[2].asInt())

        val reportText = root[1]["report"].asText()
        val text = GZIPInputStream(
            ByteArrayInputStream(Base64.getDecoder().decode(reportText.removePrefix("zzz"))),
        ).reader(Charsets.UTF_8).readText()
        val records = text.split("#")

        assertEquals(1, records.count { it == "04" })
        assertTrue(records.count { it.startsWith("09") } >= 1)
        assertTrue(records.none { it.startsWith("0u") })
        assertTrue(records.any { it == "0d" })
        assertTrue(records.any { it.startsWith("68") })
    }

    @Test
    fun `returns default report when requested battle id is unknown`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)

        val report = store.findOrDefault(999999)

        assertNotNull(report)
        assertTrue(report.battleId > 0)
    }

    @Test
    fun `profile encodes attacker equipped gear ids as gear info column zero`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val base = store.getOrCreateDefault()
        val equippedAttacker = base.result.attacker.copy(
            heroes = base.result.attacker.heroes.map { hero ->
                hero.copy(equipmentIds = listOf(1000 + hero.position))
            },
        )
        val report = store.record(
            ownerUserId = 10001,
            wid = 10001,
            timeSec = 1_700_000_001,
            result = base.result.copy(attacker = equippedAttacker),
        )

        val profile = mapper.readTree(
            store.profileResponse(listOf(report.battleId), serverId = 0),
        )[1][0]
        val gearRows = profile["attacker_gear_info"].asText().split(";")

        // 4 rows x 3 columns; row 0 is the placeholder, hero rows are 1..3.
        assertEquals(4, gearRows.size)
        assertTrue(gearRows.all { it.split(",").size == 3 })
        assertEquals("0,0,0", gearRows[0])
        assertEquals("1000", gearRows[1].split(",")[0])
        assertEquals("1001", gearRows[2].split(",")[0])
        assertEquals("1002", gearRows[3].split(",")[0])
    }

    @Test
    fun `attacker gear info carries default weapon feature ids`() {
        val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
        val base = store.getOrCreateDefault()
        val attackerHeroes = base.result.attacker.heroes.sortedBy { it.position }
        val equippedAttacker = base.result.attacker.copy(
            heroes = listOf(
                attackerHeroes[0].copy(equipmentIds = listOf(1024)),
                attackerHeroes[1].copy(equipmentIds = emptyList()),
                attackerHeroes[2].copy(equipmentIds = emptyList()),
            ),
        )
        val report = store.record(
            ownerUserId = 10001,
            wid = 10001,
            timeSec = 1_700_000_001,
            result = base.result.copy(attacker = equippedAttacker),
        )

        val profile = mapper.readTree(
            store.profileResponse(listOf(report.battleId), serverId = 0),
        )[1][0]
        val gearRows = profile["attacker_gear_info"].asText().split(";")

        assertEquals(4, gearRows.size)
        assertEquals("0,0,0", gearRows[0])
        assertEquals("1024,0,10111", gearRows[1])
        assertEquals("0,0,0", gearRows[2])
        assertEquals("0,0,0", gearRows[3])

        assertEquals("0,0,0;0,0,0;0,0,0;0,0,0", profile["defender_gear_info"].asText())
    }

    private fun assertActionSegmentsAreBalanced(actions: List<ClientReportAction>) {
        var openSegment: Int? = null
        actions.forEach { action ->
            when (action.id) {
                ClientBattleTextReplayProtocol.SKILL_BEGIN -> {
                    assertEquals(null, openSegment)
                    openSegment = action.id
                }
                ClientBattleTextReplayProtocol.SKILL_END -> {
                    assertEquals(ClientBattleTextReplayProtocol.SKILL_BEGIN, openSegment)
                    openSegment = null
                }
            }
        }
        assertEquals(null, openSegment)
    }
}
