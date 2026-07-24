package com.stzb.server.game.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientBattleReportStoreTest {
    private val mapper = jacksonObjectMapper()

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
}
