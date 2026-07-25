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
        assertEquals(
            (1..6).toSet(),
            actions.filter { it.id == ClientBattleTextReplayProtocol.HERO_ACTION_START }
                .map { it.params.first() as Int }
                .toSet(),
        )
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
