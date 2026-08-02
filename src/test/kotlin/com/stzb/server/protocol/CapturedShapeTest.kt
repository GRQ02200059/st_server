package com.stzb.server.protocol

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedShapeTest {
    @Test
    fun `clan search list stays outside captured shape fallback`() {
        val commandId = 2_675

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                """["synthetic-private-canary",0]""",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `backflow empty lists stay outside captured shape fallback`() {
        val commands = listOf(2_576, 2_577)

        assertAll(
            "backflow empty list captured shape boundaries",
            commands.map { commandId ->
                Executable {
                    val contract = CommandContractCatalog.registry.contract(commandId)
                    assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$commandId")
                    assertEquals("GameServerHandler", contract?.owner, "cmd=$commandId")
                    assertNull(
                        NetworkResponsePolicy.observedShapeBody(
                            commandId,
                            "[] synthetic-backflow-canary",
                        ),
                        "cmd=$commandId",
                    )
                    assertTrue(
                        commandId !in NetworkResponsePolicy.observedShapeCommandIds(),
                        "cmd=$commandId",
                    )
                }
            },
        )
    }

    @Test
    fun `clan log get stays outside captured shape fallback`() {
        val commandId = 2_678

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[0,20000] synthetic-clan-log-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `nearby clan list stays outside captured shape fallback`() {
        val commandId = 2_701

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[] synthetic-nearby-clan-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `clan npc city list stays outside captured shape fallback`() {
        val commandId = 2_709

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[] synthetic-npc-city-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `clan contribution list stays outside captured shape fallback`() {
        val commandId = 2_711

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[] synthetic-contribution-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `clan junxian list stays outside captured shape fallback`() {
        val commandId = 2_712

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[] synthetic-junxian-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `clan supreme list stays outside captured shape fallback`() {
        val commandId = 2_714

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[] synthetic-supreme-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `strict empty query projections stay outside captured shape fallback`() {
        val commands = listOf(708, 2_670, 2_679, 2_683, 2_698, 3_845, 4_102, 6_089)

        assertAll(
            "strict empty query projection captured shape boundaries",
            commands.map { commandId ->
                Executable {
                    val contract = CommandContractCatalog.registry.contract(commandId)
                    assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$commandId")
                    assertEquals("GameServerHandler", contract?.owner, "cmd=$commandId")
                    assertNull(
                        NetworkResponsePolicy.observedShapeBody(
                            commandId,
                            "[] synthetic-private-canary",
                        ),
                        "cmd=$commandId",
                    )
                    assertTrue(
                        commandId !in NetworkResponsePolicy.observedShapeCommandIds(),
                        "cmd=$commandId",
                    )
                }
            },
        )
    }

    @Test
    fun `battlefield chat history stays outside captured shape fallback`() {
        val commandId = 724

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[17] synthetic-battlefield-chat-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `summer farm record queries stay outside captured shape fallback`() {
        val commands = listOf(5_120, 5_121)

        assertAll(
            "summer farm record query captured shape boundaries",
            commands.map { commandId ->
                Executable {
                    val contract = CommandContractCatalog.registry.contract(commandId)
                    assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$commandId")
                    assertEquals("GameServerHandler", contract?.owner, "cmd=$commandId")
                    assertNull(
                        NetworkResponsePolicy.observedShapeBody(
                            commandId,
                            """["synthetic-record-canary"] trailing""",
                        ),
                        "cmd=$commandId",
                    )
                    assertTrue(
                        commandId !in NetworkResponsePolicy.observedShapeCommandIds(),
                        "cmd=$commandId",
                    )
                }
            },
        )
    }

    @Test
    fun `summer farm user list stays outside captured shape fallback`() {
        val commandId = 5_109

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[1] synthetic-summer-farm-user-list-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `union letter query stays outside captured shape fallback`() {
        val commandId = 9_015

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(
            NetworkResponsePolicy.observedShapeBody(
                commandId,
                "[0] synthetic-letter-canary",
            ),
        )
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `nobility officer record query stays outside captured shape fallback`() {
        val commandId = 5_212

        val contract = CommandContractCatalog.registry.contract(commandId)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
        assertNull(NetworkResponsePolicy.observedShapeBody(commandId, "[1700000123]"))
        assertNull(NetworkResponsePolicy.observedShapeBody(commandId, "[17] []"))
        assertTrue(commandId !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `top level kind detects array object scalar`() {
        assertEquals("array", ShapeAssert.topLevelKind("[1,2]"))
        assertEquals("object", ShapeAssert.topLevelKind("{\"a\":1}"))
        assertEquals("boolean", ShapeAssert.topLevelKind("true"))
        assertEquals("number", ShapeAssert.topLevelKind("200"))
        assertEquals("null", ShapeAssert.topLevelKind("null"))
    }

    @Test
    fun `tuple size counts array elements`() {
        assertEquals(3, ShapeAssert.tupleSize("[0,0,[]]"))
    }

    @Test
    fun `same shape passes for matching arrays`() {
        ShapeAssert.assertSameShape("[0,\"\"]", "[200,\"x\"]")
    }

    @Test
    fun `same shape fails for mismatched kinds`() {
        assertFailsWith<AssertionError> {
            ShapeAssert.assertSameShape("[]", "{}")
        }
    }

    // 以下为抓包形状对照：正式服真实 recv 顶层类型 vs 私服兜底输出。

    @Test
    fun `handler owned read only empty projections are absent from observed shape fallback`() {
        val commands = listOf(3_739, 4_092, 4_112, 4_114, 5_096, 5_218)

        assertAll(
            "read only empty projection captured shape boundaries",
            commands.map { cmd ->
                Executable {
                    val contract = CommandContractCatalog.registry.contract(cmd)
                    assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
                    assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
                    assertNull(
                        NetworkResponsePolicy.observedShapeBody(cmd, "[] {}"),
                        "cmd=$cmd",
                    )
                    assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
                }
            },
        )
    }

    @Test
    fun `captured boolean commands keep boolean kind`() {
        listOf(748).forEach { cmd ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("boolean", ShapeAssert.topLevelKind(body), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned real name logout is absent from observed shape fallback`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.REALNAME_LOGOUT))
        assertTrue(Cmd.REALNAME_LOGOUT !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `handler owned union eligibility and channel certification are absent from observed shape fallback`() {
        listOf(
            Cmd.CHECK_HAVE_UNION_TO_JOIN,
            Cmd.SET_CHANNEL_CERTIFICATION,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned log acknowledgements are absent from observed shape fallback`() {
        listOf(
            Cmd.USER_CLOSE_UI,
            Cmd.LOG_MUSIC_OPEN,
            Cmd.RESFILE_LOG_HUB_RECORD,
            Cmd.DAILY_REPORT_LOG,
            Cmd.HELP_GUIDE_TIPS_LOG,
            Cmd.UPDATE_GUIDE_RECORD,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned body blind telemetry acknowledgements are absent from observed shape fallback`() {
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
    fun `handler owned external identity rejections are absent from observed shape fallback`() {
        val commands = listOf(331, 332, 336, 9_010, 29_003, 40_006, 40_007, 40_014)

        assertAll(
            "external identity rejection fallback boundaries",
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
    fun `handler owned revenue commands are absent from observed shape fallback`() {
        listOf(Cmd.REVENUE, Cmd.REVENUE_DOUBLE).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned card record and customer service rejection are absent from observed shape fallback`() {
        listOf(
            Cmd.CARD_RECORD,
            Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned season history queries are absent from observed shape fallback`() {
        listOf(
            Cmd.GET_USER_SEASON_RECORD,
            Cmd.GET_SEASON_HISTROY_PARAMS,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned world boss top three rank is absent from observed shape fallback`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.WORLD_BOSS_TOP_THREE_RANK))
        assertTrue(
            Cmd.WORLD_BOSS_TOP_THREE_RANK !in NetworkResponsePolicy.observedShapeCommandIds(),
        )
    }

    @Test
    fun `handler owned world rank and domestic status queries are absent from observed shape fallback`() {
        listOf(
            Cmd.OWN_RANK,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX,
            Cmd.FENGLU_LEVEL_STATUS,
        ).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd, "[17]"), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned external rejections and login flags are absent from observed shape fallback`() {
        listOf(
            Cmd.FILE_PICKER_GET_TOKEN_DEFAULT,
            Cmd.CHECK_ADD_WEIXIN,
            Cmd.YOUTH_INK_MAP_TIPS,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned union army and station queries are absent from observed shape fallback`() {
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
    fun `handler owned union nearby player list is absent from observed shape fallback`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.UNION_NEARBY_PLAYER_LIST))
        assertTrue(Cmd.UNION_NEARBY_PLAYER_LIST !in NetworkResponsePolicy.observedShapeCommandIds())
    }

    @Test
    fun `handler owned union social empty queries are absent from observed shape fallback`() {
        listOf(104, 736, 741, 3_410, 3_411).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned optional social and world queries are absent from observed shape fallback`() {
        listOf(
            Cmd.CCLIVE_GET_FOLLOW_LIST,
            Cmd.FIRST_STATE_COOUPY_MSG,
            Cmd.UNION_RELATION_FULL_REQUEST,
        ).forEach { cmd ->
            assertNull(NetworkResponsePolicy.observedShapeBody(cmd), "cmd=$cmd")
            assertTrue(cmd !in NetworkResponsePolicy.observedShapeCommandIds(), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned multiplexed 6242 is absent from observed shape fallback`() {
        assertNull(NetworkResponsePolicy.observedShapeBody(Cmd.UNION_STATION_ENTER_SCENE))
        assertTrue(
            Cmd.UNION_STATION_ENTER_SCENE !in NetworkResponsePolicy.observedShapeCommandIds(),
        )
    }

    @Test
    fun `captured indexed tuples reserve positional slots so client resp index reads survive`() {
        // 这些命令客户端按下标读取（resp[0]/resp[1]），空 [] 会取到 null 崩溃。
        val expectedSizes = mapOf(
            20003 to 2,
        )
        expectedSizes.forEach { (cmd, size) ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(size, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned request aware queries are absent from observed shape fallback`() {
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
    fun `handler owned sampled empty list queries are absent from observed shape fallback`() {
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
    fun `handler owned read only empty queries are absent from observed shape fallback`() {
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
    fun `captured list iterated commands stay empty arrays`() {
        // 这些命令客户端整体遍历列表，空 [] 即结构正确（保结构不保数值）。
        listOf(103, 711).forEach { cmd ->
            val body = NetworkResponsePolicy.observedShapeBody(cmd)!!
            assertEquals("array", ShapeAssert.topLevelKind(body), "cmd=$cmd")
            assertEquals(0, ShapeAssert.tupleSize(body), "cmd=$cmd")
        }
    }

    @Test
    fun `handler owned message acknowledgements and gift rejection are absent from fallback`() {
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
    fun `handler owned black market and patrol rejections are absent from fallback`() {
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
}
