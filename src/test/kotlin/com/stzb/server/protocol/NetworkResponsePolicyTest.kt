package com.stzb.server.protocol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkResponsePolicyTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `explicit recorded array command returns its observed shape`() {
        assertEquals("[]", NetworkResponsePolicy.observedShapeBody(959))
    }

    @Test
    fun `unregistered command has no shape response`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(45_678))
        assertTrue(!CommandContractCatalog.registry.isShapeResponseAllowed(45_678))
    }

    @Test
    fun `privileged test command is rejected rather than treated as a no op`() {
        assertEquals(CommandStatus.REJECTED, CommandContractCatalog.registry.contract(98_765)?.status)
        assertNull(NetworkResponsePolicy.observedShapeBody(98_765))
    }

    @Test
    fun `reinforce stay checks return dictionaries required by conquest army ui`() {
        assertEquals("{}", NetworkResponsePolicy.observedShapeBody(6219))
        assertEquals("{}", NetworkResponsePolicy.observedShapeBody(6239))
    }

    @Test
    fun `recorded acknowledgement commands return booleans instead of arrays`() {
        listOf(191, 748, 888, 2311).forEach { cmdId ->
            assertEquals("true", NetworkResponsePolicy.observedShapeBody(cmdId), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded fire and forget commands still receive json null`() {
        listOf(6, 875, 885, 2405, 3400, 5025, 6037, 6351, 7041).forEach { cmdId ->
            assertEquals("null", NetworkResponsePolicy.observedShapeBody(cmdId), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded scalar and tuple commands keep their wire shapes`() {
        assertEquals("200", NetworkResponsePolicy.observedShapeBody(5091))
        assertEquals("[1001]", NetworkResponsePolicy.observedShapeBody(3877))
        assertEquals("[false,[]]", NetworkResponsePolicy.observedShapeBody(4968))
        assertEquals("[[],0]", NetworkResponsePolicy.observedShapeBody(6092))
    }

    @Test
    fun `recorded dictionary commands return objects instead of arrays`() {
        listOf(510, 6053, 6068, 6219, 6239).forEach { cmdId ->
            assertEquals("{}", NetworkResponsePolicy.observedShapeBody(cmdId), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded role lookup command returns a safe local tuple`() {
        val response = mapper.readTree(
            NetworkResponsePolicy.observedShapeBody(5013, """[3,"remote-role-id"]"""),
        )

        assertEquals(3, response[0].asInt())
        assertEquals("remote-role-id", response[1].asText())
        assertEquals(0, response[2].size())
        assertEquals(10001, response[4].asInt())
        assertEquals("主公", response[7].asText())
    }

    @Test
    fun `recorded fixed tuple queries keep minimum client readable arity`() {
        val expectedSizes = mapOf(
            135 to 5,
            172 to 2,
            700 to 6,
            725 to 4,
            1436 to 3,
            2529 to 3,
            3686 to 2,
            3787 to 2,
            4979 to 3,
        )

        expectedSizes.forEach { (cmdId, size) ->
            val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(cmdId))
            assertEquals(size, response.size(), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded map in tuple queries keep dictionary slot`() {
        listOf(261, 262).forEach { cmdId ->
            val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(cmdId))
            assertEquals(1, response.size(), "cmd=$cmdId")
            assertEquals(true, response[0].isObject, "cmd=$cmdId")
        }
    }

    @Test
    fun `server ip port query returns a non success tuple the client can index`() {
        // InnerIpPortInfo.OnGetIpPortInfo reads val[0] unconditionally and only
        // touches val[1..3] when val[0] == 200. An empty array crashed the client
        // with ArgumentOutOfRangeException on the 4001-map (conquest) login path.
        val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(5201))
        assertEquals(1, response.size())
        assertEquals(0, response[0].asInt())
    }

    @Test
    fun `recorded name lookup echoes requested name with empty result lists`() {
        val response = mapper.readTree(
            NetworkResponsePolicy.observedShapeBody(4979, """["查找目标"]"""),
        )

        assertEquals("查找目标", response[0].asText())
        assertEquals(0, response[1].size())
        assertEquals(0, response[2].size())
    }

    @Test
    fun `friend mail user lookup returns user data tuple`() {
        val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(212))
        assertEquals(1, response[0].asInt())
        assertEquals(10001, response[1][0].asInt())
        assertEquals("role_10001", response[1][1].asText())
        assertEquals("主公", response[1][2].asText())
    }

    @Test
    fun `union info returns non success tuple to avoid complex union parser`() {
        assertEquals("[1,[]]", NetworkResponsePolicy.observedShapeBody(100))
    }

    @Test
    fun `create union returns a single int id the client casts directly`() {
        // UnionCreateData.ReciveUnionId does `int unionID = (int)package;` then
        // opens the union main UI (which fires cmd 100). A single int keeps the
        // create-then-open path alive without an ArgumentException.
        val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(102))
        assertTrue(response.isInt, "cmd 102 must return a bare int, got $response")
    }

    @Test
    fun `join union side lists never return null so the enumerator stays safe`() {
        // UnionJoinUI.OnShow fires 5049/111/4080; 5049 OnOtherDataCb enumerates
        // the packet with no null guard, so an empty array (not null) is required.
        assertEquals("[]", NetworkResponsePolicy.observedShapeBody(5049))
        assertEquals("[]", NetworkResponsePolicy.observedShapeBody(111))
        assertEquals("[]", NetworkResponsePolicy.observedShapeBody(4080))
    }

    @Test
    fun `other player profile returns a non success tuple that closes gracefully`() {
        // RoleForcesDetailUI._ReceiveUserProfile reads val[0]; only 0/2 refresh
        // the view, anything else shows a "not found" tip and closes. [1,""] is
        // safe (client reads val[1] only through the Count>1 guarded branch).
        val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(502))
        assertEquals(1, response[0].asInt())
    }

    @Test
    fun `homepage info returns a full dict so UpdateData does not crash`() {
        // UserMainView.ResponseData calls UpdateData(val[1]) when val[0] != 0, and
        // UpdateData does unguarded casts on the personal(22)/union(14)/server(4)/
        // zanAndvistor(3) sub-lists plus 11 required dict keys. Empty {} crashes.
        val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(3686))
        assertTrue(response[0].asInt() != 0, "status code must be non-zero to trigger UpdateData")
        val dict = response[1]
        // 11 required keys UpdateData reads with unguarded index/cast.
        for (key in listOf(
            "personal", "union", "server", "history", "zanAndvistor",
            "show_type", "history_choice", "fashion", "populartiy", "city_card", "area_rank_title",
        )) {
            assertTrue(dict.has(key), "homepage dict missing required key: $key")
        }
        // personal must expose indices 0..21 (unguarded segment).
        assertTrue(dict["personal"].size() >= 22, "personal needs >=22 elements")
        assertTrue(dict["union"].size() >= 14, "union needs >=14 elements")
        assertTrue(dict["server"].size() >= 4, "server needs >=4 elements")
        assertTrue(dict["zanAndvistor"].size() >= 3, "zanAndvistor needs >=3 elements")
        // area_rank_title is read via .ToString(); must not be JSON null.
        assertTrue(!dict["area_rank_title"].isNull, "area_rank_title must not be null")
    }

    @Test
    fun `paged list command returns list tuple`() {
        val response = mapper.readTree(NetworkResponsePolicy.observedShapeBody(91))
        assertEquals(0, response[0].asInt())
        assertEquals(0, response[1].size())
        assertEquals(0, response[2].asInt())
    }

    @Test
    fun `battle report commands require precise handlers`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.BATTLE_REPORT_PROFILE))
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.BATTLE_REPORT_DETAIL))
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.BATTLE_REPORT_SHORT_DETAIL))
        assertEquals(CommandStatus.PROVISIONAL, CommandContractCatalog.registry.contract(Cmd.BATTLE_REPORT_PROFILE)?.status)
    }

    @Test
    fun `unknown system command is not auto answered`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(96666))
    }
}
