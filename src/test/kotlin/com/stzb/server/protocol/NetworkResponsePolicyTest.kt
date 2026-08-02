package com.stzb.server.protocol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkResponsePolicyTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `read only empty projections require explicit handlers`() {
        val commands = listOf(3_739, 4_092, 4_112, 4_114, 5_096, 5_218)

        assertAll(
            "read only empty projection fallback boundaries",
            commands.map { cmd ->
                Executable {
                    val contract = CommandContractCatalog.registry.contract(cmd)
                    assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
                    assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
                    assertNull(
                        NetworkResponsePolicy.observedShapeBody(cmd, "not-json opaque text"),
                        "cmd=$cmd",
                    )
                    assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
                }
            },
        )
    }

    @Test
    fun `revenue commands require persistent local handlers`() {
        listOf(Cmd.REVENUE, Cmd.REVENUE_DOUBLE).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `explicit recorded array command returns its observed shape`() {
        assertEquals("[]", NetworkResponsePolicy.observedShapeBody(959))
    }

    @Test
    fun `request aware query handlers are not owned by network response policy`() {
        listOf(
            Cmd.UNION_GET_GROUP_LIST,
            Cmd.DAILY_REPORT_GET_DETAIL,
            Cmd.GET_HERO_RECOMMEND_2,
            Cmd.GET_UDS_GUESS_SEASON,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `sampled empty list query handlers are not owned by network response policy`() {
        listOf(
            Cmd.GET_UNION_BATTLE_REPORT,
            Cmd.MAIL_OUTBOX,
            Cmd.GET_BLACK_LIST,
            Cmd.NOTICE_LIST,
            Cmd.FRIEND_GROUP_GET_HISTORY_CHAT,
            Cmd.QUERY_WANTED_TO_REPOTR,
            Cmd.STRATEGY_HELP_GET,
            Cmd.COMMAND_PLAN_GET_UNION_TEMP_GROUP,
            Cmd.UNION_STATION_PLAYER_DANMU_LIST_GET,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `read only empty query handlers are not owned by network response policy`() {
        listOf(
            Cmd.SWITCH_ROLE_QUERY_ROLE_LIST,
            Cmd.MAIL_INBOX,
            Cmd.MAIL_GET_CONTACTS,
            Cmd.USER_GET_SEASON_COURSE_LIST,
            Cmd.CHAT_GET_ZHAO_XIAN_MSG,
            Cmd.PROGRESS_GET_INFO,
            Cmd.MAIL_NOTIFY_GET_ALL,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `read only union army and station queries require explicit handlers`() {
        listOf(
            Cmd.UNION_NPC_CITY_LIST,
            Cmd.CHAT_UNION_PLAN_HISTORY_ID,
            Cmd.COMMAND_PLAN_GEL_UNION_TEMP_GROUP_MEMBER,
            Cmd.ARMY_REINFORCE_STAY_CHECK,
            Cmd.UNION_STATION_GET_DATA,
            Cmd.UNION_STATION_ALL_RECORDS,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `union nearby player list requires its explicit handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.UNION_NEARBY_PLAYER_LIST))
        assertTrue(Cmd.UNION_NEARBY_PLAYER_LIST !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `union social empty queries require explicit handlers`() {
        listOf(104, 736, 741, 3_410, 3_411).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `multiplexed 6242 requires its request aware handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.UNION_STATION_ENTER_SCENE))
        assertTrue(
            Cmd.UNION_STATION_ENTER_SCENE !in NetworkResponsePolicy.observedShapeCommandIds(),
        )
    }

    @Test
    fun `season history handlers are not owned by network response policy`() {
        listOf(
            Cmd.GET_USER_SEASON_RECORD,
            Cmd.GET_SEASON_HISTROY_PARAMS,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `card record and customer service rejection require explicit handlers`() {
        listOf(
            Cmd.CARD_RECORD,
            Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `union eligibility and channel certification require explicit handlers`() {
        listOf(
            Cmd.CHECK_HAVE_UNION_TO_JOIN,
            Cmd.SET_CHANNEL_CERTIFICATION,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `real name logout requires the explicit channel closing handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.REALNAME_LOGOUT))
        assertTrue(Cmd.REALNAME_LOGOUT !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `message acknowledgements and gift rejection require explicit handlers`() {
        listOf(
            Cmd.XUANFUQIU_RECEIVED_MSG,
            Cmd.GAME_CHENGXIANGGE_RECEIVED,
            Cmd.SOLDIER_GIFT_ACTIVATE,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `black market and patrol rejections require explicit handlers`() {
        listOf(
            Cmd.BLACK_MARKET_REFRESH_AUTO,
            Cmd.PATORL_GET,
            Cmd.PATORL_HANDLE,
            Cmd.PATORL_REWARD_GET,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `union chat member list requires the explicit handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT))
    }

    @Test
    fun `union official list requires the explicit local state handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.UNION_OFFICIAL_LIST, "[1005]"))
        assertTrue(Cmd.UNION_OFFICIAL_LIST !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `rank list requires the explicit handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.RANK_LIST))
    }

    @Test
    fun `world boss top three rank requires the explicit handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.WORLD_BOSS_TOP_THREE_RANK))
        assertTrue(
            Cmd.WORLD_BOSS_TOP_THREE_RANK !in NetworkResponsePolicy.observedShapeCommandIds(),
        )
    }

    @Test
    fun `world rank and domestic status queries require explicit handlers`() {
        listOf(
            Cmd.OWN_RANK,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX,
            Cmd.FENGLU_LEVEL_STATUS,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd, "[17]"), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `user head icon lookup requires the explicit handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.USER_GET_USERS_HEADICON))
    }

    @Test
    fun `mail info requires the explicit request aware handler`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.MAIL_INFO, "[677829,1,9]"))
        assertTrue(Cmd.MAIL_INFO !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `mail brief info requires the explicit request aware handler`() {
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID,
                "[677829,0]",
            ),
        )
        assertTrue(
            Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID !in NetworkResponsePolicy.observedShapeCommandIds(),
        )
    }

    @Test
    fun `prebook info and community token require explicit handlers`() {
        listOf(
            Cmd.GET_PREBOOK_SERVER_INFO to """["11"]""",
            Cmd.COMMUNITY_GET_USER_TOKEN to "[]",
        ).forEach { (cmd, request) ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd, request), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `telemetry acknowledgements require explicit handlers`() {
        listOf(
            Cmd.LOG_FPS,
            Cmd.SEND_ACSDK_CHEAT_INFO,
            Cmd.USER_CLOSE_UI,
            Cmd.USER_OPEN_UI,
            Cmd.LOG_MUSIC_OPEN,
            Cmd.RESFILE_LOG_HUB_RECORD,
            Cmd.DAILY_REPORT_LOG,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `body blind telemetry acknowledgements require explicit handlers`() {
        listOf(2_524, 3_402, 3_604, 4_019, 5_202, 5_242, 8_040).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
            assertNull(
                NetworkResponsePolicy.observedShapeBody(cmd, "not-json private-marker"),
                "cmd=$cmd",
            )
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `external identity rejections require explicit handlers`() {
        val commands = listOf(331, 332, 336, 9_010, 29_003, 40_006, 40_007, 40_014)

        assertAll(
            "external identity rejection policy boundaries",
            commands.map { cmd ->
                Executable {
                    val contract = CommandContractCatalog.registry.contract(cmd)
                    assertEquals(CommandStatus.REJECTED, contract?.status, "cmd=$cmd")
                    assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
                    assertNull(
                        NetworkResponsePolicy.observedShapeBody(cmd, "not-json synthetic-credential-canary"),
                        "cmd=$cmd",
                    )
                    assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
                }
            },
        )
    }

    @Test
    fun `guide log acknowledgements require explicit handlers`() {
        listOf(
            Cmd.HELP_GUIDE_TIPS_LOG,
            Cmd.UPDATE_GUIDE_RECORD,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `pre login and external commands require explicit handlers`() {
        listOf(
            Cmd.PRE_SERVER_QUERY_USER_OP,
            Cmd.PRE_SERVER_GEN_H5_SIGN,
            Cmd.FILE_PICKER_GET_TOKEN_DEFAULT,
            Cmd.CHECK_ADD_WEIXIN,
            Cmd.YOUTH_INK_MAP_TIPS,
            Cmd.QUERY_NEW_COMMUNITY_INFO,
            Cmd.QUERY_SIMULATE_TOKEN,
            Cmd.IP_USER_COUNT_PRE,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
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
    fun `legion reinforce stay check keeps fallback dictionary required by conquest army ui`() {
        assertEquals("{}", NetworkResponsePolicy.observedShapeBody(6239))
    }

    @Test
    fun `recorded acknowledgement command returns a boolean instead of an array`() {
        assertEquals("true", NetworkResponsePolicy.observedShapeBody(748))
    }

    @Test
    fun `recorded fire and forget commands still receive json null`() {
        listOf(6, 2405, 3400, 5025, 7041).forEach { cmdId ->
            assertEquals("null", NetworkResponsePolicy.observedShapeBody(cmdId), "cmd=$cmdId")
        }
    }

    @Test
    fun `recorded tuple commands keep their wire shapes`() {
        assertEquals("[1001]", NetworkResponsePolicy.observedShapeBody(3877))
        assertEquals("[[],0]", NetworkResponsePolicy.observedShapeBody(6092))
    }

    @Test
    fun `recorded dictionary commands return objects instead of arrays`() {
        listOf(510, 6239).forEach { cmdId ->
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
            172 to 2,
            725 to 4,
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
