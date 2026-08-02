package com.stzb.server.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandContractRegistryTest {
    @Test
    fun `revenue commands expose handler owned provisional city contracts`() {
        val commands = mapOf(
            Cmd.REVENUE to (750 to "REVENUE"),
            Cmd.REVENUE_DOUBLE to (752 to "REVENUE_DOUBLE"),
        )

        commands.forEach { (cmd, expected) ->
            assertEquals(expected.first, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(listOf(expected.second), contract?.names, "cmd=$cmd")
            assertEquals(CommandDirection.DUPLEX, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.CITY, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `union nearby player list exposes a handler owned provisional social contract`() {
        assertEquals(112, Cmd.UNION_NEARBY_PLAYER_LIST)

        val contract = CommandContractCatalog.registry.contract(Cmd.UNION_NEARBY_PLAYER_LIST)
        assertEquals(listOf("UNION_NEARBY_PLAYER_LIST"), contract?.names)
        assertEquals(CommandDirection.DUPLEX, contract?.direction)
        assertEquals(CommandDomain.SOCIAL, contract?.domain)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `multiplexed 6242 exposes both names and a handler owned social contract`() {
        assertEquals(6_242, Cmd.TEAM_INVITATIONAL_QUERY_MEMBER_FOR_INVITE)
        assertEquals(6_242, Cmd.UNION_STATION_ENTER_SCENE)

        val contract = CommandContractCatalog.registry.contract(Cmd.UNION_STATION_ENTER_SCENE)
        assertEquals(
            listOf(
                "TEAM_INVITATIONAL_QUERY_MEMBER_FOR_INVITE",
                "UNION_STATION_ENTER_SCENE",
            ),
            contract?.names,
        )
        assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction)
        assertEquals(CommandDomain.SOCIAL, contract?.domain)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `optional social and world queries expose handler owned contracts`() {
        val expectedDomains = mapOf(
            Cmd.CCLIVE_GET_FOLLOW_LIST to (2_529 to CommandDomain.EXTERNAL),
            Cmd.FIRST_STATE_COOUPY_MSG to (6_037 to CommandDomain.WORLD),
            Cmd.UNION_RELATION_FULL_REQUEST to (6_351 to CommandDomain.SOCIAL),
        )

        expectedDomains.forEach { (cmd, expected) ->
            assertEquals(expected.first, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(expected.second, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `read only union army and station queries expose handler owned contracts`() {
        val duplexCommands = mapOf(
            Cmd.UNION_NPC_CITY_LIST to 135,
            Cmd.CHAT_UNION_PLAN_HISTORY_ID to 6_053,
            Cmd.COMMAND_PLAN_GEL_UNION_TEMP_GROUP_MEMBER to 6_068,
            Cmd.UNION_STATION_ALL_RECORDS to 6_244,
        )
        val clientRequestCommands = mapOf(
            Cmd.ARMY_REINFORCE_STAY_CHECK to 6_219,
            Cmd.UNION_STATION_GET_DATA to 6_243,
        )

        duplexCommands.forEach { (cmd, expectedId) ->
            assertEquals(expectedId, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.DUPLEX, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.UNKNOWN, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
        clientRequestCommands.forEach { (cmd, expectedId) ->
            assertEquals(expectedId, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.UNKNOWN, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `message acknowledgements and gift rejection expose handler owned contracts`() {
        assertEquals(2_402, Cmd.XUANFUQIU_RECEIVED_MSG)
        assertEquals(2_404, Cmd.GAME_CHENGXIANGGE_RECEIVED)
        assertEquals(6_030, Cmd.SOLDIER_GIFT_ACTIVATE)

        listOf(
            Cmd.XUANFUQIU_RECEIVED_MSG,
            Cmd.GAME_CHENGXIANGGE_RECEIVED,
        ).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.UNKNOWN, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }

        val gift = CommandContractCatalog.registry.contract(Cmd.SOLDIER_GIFT_ACTIVATE)
        assertEquals(CommandDirection.CLIENT_REQUEST, gift?.direction)
        assertEquals(CommandDomain.ACTIVITY, gift?.domain)
        assertEquals(CommandStatus.REJECTED, gift?.status)
        assertEquals("GameServerHandler", gift?.owner)
    }

    @Test
    fun `black market and patrol rejections expose handler owned contracts`() {
        val expected = mapOf(
            Cmd.BLACK_MARKET_REFRESH_AUTO to Triple(
                933,
                "BLACK_MARKET_REFRESH_AUTO",
                CommandDomain.ACTIVITY,
            ),
            Cmd.PATORL_GET to Triple(2_600, "PATORL_GET", CommandDomain.WORLD),
            Cmd.PATORL_HANDLE to Triple(2_601, "PATORL_HANDLE", CommandDomain.WORLD),
            Cmd.PATORL_REWARD_GET to Triple(2_604, "PATORL_REWARD_GET", CommandDomain.WORLD),
        )

        expected.forEach { (cmd, details) ->
            assertEquals(details.first, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(listOf(details.second), contract?.names, "cmd=$cmd")
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(details.third, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.REJECTED, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `external service rejections and login flags expose handler owned contracts`() {
        assertEquals(3_928, Cmd.FILE_PICKER_GET_TOKEN_DEFAULT)
        assertEquals(4_968, Cmd.CHECK_ADD_WEIXIN)
        assertEquals(40_018, Cmd.YOUTH_INK_MAP_TIPS)

        listOf(
            Cmd.FILE_PICKER_GET_TOKEN_DEFAULT,
            Cmd.CHECK_ADD_WEIXIN,
        ).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.EXTERNAL, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.REJECTED, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }

        val loginFlags = CommandContractCatalog.registry.contract(Cmd.YOUTH_INK_MAP_TIPS)
        assertEquals(CommandDirection.CLIENT_REQUEST, loginFlags?.direction)
        assertEquals(CommandDomain.UNKNOWN, loginFlags?.domain)
        assertEquals(CommandStatus.PROVISIONAL, loginFlags?.status)
        assertEquals("GameServerHandler", loginFlags?.owner)
    }

    @Test
    fun `read only empty queries expose handler owned provisional client request contracts`() {
        val commands = mapOf(
            Cmd.SWITCH_ROLE_QUERY_ROLE_LIST to 171,
            Cmd.MAIL_INBOX to 202,
            Cmd.MAIL_GET_CONTACTS to 220,
            Cmd.USER_GET_SEASON_COURSE_LIST to 509,
            Cmd.CHAT_GET_ZHAO_XIAN_MSG to 727,
            Cmd.PROGRESS_GET_INFO to 871,
            Cmd.MAIL_NOTIFY_GET_ALL to 3_758,
        )

        commands.forEach { (cmd, expectedId) ->
            assertEquals(expectedId, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.UNKNOWN, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `pre login and external commands expose readable handler owned contracts`() {
        assertEquals(40_003, Cmd.PRE_SERVER_QUERY_USER_OP)
        assertEquals(40_004, Cmd.PRE_SERVER_GEN_H5_SIGN)
        assertEquals(40_020, Cmd.QUERY_NEW_COMMUNITY_INFO)
        assertEquals(40_021, Cmd.QUERY_SIMULATE_TOKEN)
        assertEquals(40_022, Cmd.IP_USER_COUNT_PRE)

        listOf(
            Cmd.PRE_SERVER_QUERY_USER_OP,
            Cmd.IP_USER_COUNT_PRE,
        ).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.UNKNOWN, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
        listOf(
            Cmd.PRE_SERVER_GEN_H5_SIGN,
            Cmd.QUERY_NEW_COMMUNITY_INFO,
            Cmd.QUERY_SIMULATE_TOKEN,
        ).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandDomain.EXTERNAL, contract?.domain, "cmd=$cmd")
            assertEquals(CommandStatus.REJECTED, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `telemetry commands expose readable handler owned client request contracts`() {
        val commands = listOf(
            Cmd.LOG_FPS,
            Cmd.SEND_ACSDK_CHEAT_INFO,
            Cmd.USER_CLOSE_UI,
            Cmd.USER_OPEN_UI,
            Cmd.LOG_MUSIC_OPEN,
            Cmd.RESFILE_LOG_HUB_RECORD,
            Cmd.DAILY_REPORT_LOG,
        )
        assertEquals(24, Cmd.LOG_FPS)
        assertEquals(191, Cmd.SEND_ACSDK_CHEAT_INFO)
        assertEquals(875, Cmd.USER_CLOSE_UI)
        assertEquals(885, Cmd.USER_OPEN_UI)
        assertEquals(888, Cmd.LOG_MUSIC_OPEN)
        assertEquals(4_326, Cmd.RESFILE_LOG_HUB_RECORD)
        assertEquals(4_966, Cmd.DAILY_REPORT_LOG)

        commands.forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `guide log commands expose readable handler owned client request contracts`() {
        assertEquals(5_069, Cmd.HELP_GUIDE_TIPS_LOG)
        assertEquals(5_091, Cmd.UPDATE_GUIDE_RECORD)

        listOf(
            Cmd.HELP_GUIDE_TIPS_LOG,
            Cmd.UPDATE_GUIDE_RECORD,
        ).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `sampled empty list queries expose readable handler owned contracts with captured directions`() {
        val duplexCommands = mapOf(
            Cmd.GET_UNION_BATTLE_REPORT to 92,
            Cmd.GET_BLACK_LIST to 714,
            Cmd.QUERY_WANTED_TO_REPOTR to 4_967,
            Cmd.COMMAND_PLAN_GET_UNION_TEMP_GROUP to 6_067,
            Cmd.UNION_STATION_PLAYER_DANMU_LIST_GET to 6_256,
        )
        val clientRequestCommands = mapOf(
            Cmd.MAIL_OUTBOX to 203,
            Cmd.NOTICE_LIST to 780,
            Cmd.FRIEND_GROUP_GET_HISTORY_CHAT to 3_846,
            Cmd.STRATEGY_HELP_GET to 5_082,
        )

        duplexCommands.forEach { (cmd, expectedId) ->
            assertEquals(expectedId, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.DUPLEX, contract?.direction, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
        clientRequestCommands.forEach { (cmd, expectedId) ->
            assertEquals(expectedId, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `season history queries expose handler owned provisional client request contracts`() {
        val commands = mapOf(
            Cmd.GET_USER_SEASON_RECORD to 980,
            Cmd.GET_SEASON_HISTROY_PARAMS to 5_021,
        )

        commands.forEach { (cmd, expectedId) ->
            assertEquals(expectedId, cmd)
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }
    }

    @Test
    fun `card record and customer service rejection expose exact handler owned contracts`() {
        assertEquals(671, Cmd.CARD_RECORD)
        val cardRecord = CommandContractCatalog.registry.contract(Cmd.CARD_RECORD)
        assertEquals(CommandDirection.CLIENT_REQUEST, cardRecord?.direction)
        assertEquals(CommandDomain.UNKNOWN, cardRecord?.domain)
        assertEquals(CommandStatus.PROVISIONAL, cardRecord?.status)
        assertEquals("GameServerHandler", cardRecord?.owner)

        assertEquals(40_016, Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE)
        val customerService = CommandContractCatalog.registry.contract(
            Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE,
        )
        assertEquals(CommandDirection.CLIENT_REQUEST, customerService?.direction)
        assertEquals(CommandDomain.EXTERNAL, customerService?.domain)
        assertEquals(CommandStatus.REJECTED, customerService?.status)
        assertEquals("GameServerHandler", customerService?.owner)
    }

    @Test
    fun `union eligibility and channel certification expose exact handler owned contracts`() {
        assertEquals(4_087, Cmd.CHECK_HAVE_UNION_TO_JOIN)
        val unionEligibility = CommandContractCatalog.registry.contract(
            Cmd.CHECK_HAVE_UNION_TO_JOIN,
        )
        assertEquals(CommandDirection.CLIENT_REQUEST, unionEligibility?.direction)
        assertEquals(CommandDomain.UNKNOWN, unionEligibility?.domain)
        assertEquals(CommandStatus.PROVISIONAL, unionEligibility?.status)
        assertEquals("GameServerHandler", unionEligibility?.owner)

        assertEquals(2_311, Cmd.SET_CHANNEL_CERTIFICATION)
        val channelCertification = CommandContractCatalog.registry.contract(
            Cmd.SET_CHANNEL_CERTIFICATION,
        )
        assertEquals(CommandDirection.CLIENT_REQUEST, channelCertification?.direction)
        assertEquals(CommandDomain.EXTERNAL, channelCertification?.domain)
        assertEquals(CommandStatus.REJECTED, channelCertification?.status)
        assertEquals("GameServerHandler", channelCertification?.owner)
    }

    @Test
    fun `real name logout exposes a handler owned provisional client request contract`() {
        assertEquals(981, Cmd.REALNAME_LOGOUT)
        val contract = CommandContractCatalog.registry.contract(Cmd.REALNAME_LOGOUT)
        assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction)
        assertEquals(CommandDomain.UNKNOWN, contract?.domain)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `union group commands expose readable ids`() {
        assertEquals(142, Cmd.UNION_GET_GROUP_LIST)
        assertEquals(143, Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT)
    }

    @Test
    fun `union official list exposes a readable handler owned contract`() {
        assertEquals(110, Cmd.UNION_OFFICIAL_LIST)
        val contract = CommandContractCatalog.registry.contract(Cmd.UNION_OFFICIAL_LIST)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `rank list exposes a readable handler owned contract`() {
        assertEquals(700, Cmd.RANK_LIST)
        val contract = CommandContractCatalog.registry.contract(Cmd.RANK_LIST)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `world boss top three rank exposes an anonymous handler owned activity contract`() {
        assertEquals(8_009, Cmd.WORLD_BOSS_TOP_THREE_RANK)

        val contract = CommandContractCatalog.registry.contract(Cmd.WORLD_BOSS_TOP_THREE_RANK)
        assertEquals(emptyList(), contract?.names)
        assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction)
        assertEquals(CommandDomain.ACTIVITY, contract?.domain)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `user head icon lookup exposes a readable handler owned contract`() {
        assertEquals(514, Cmd.USER_GET_USERS_HEADICON)
        val contract = CommandContractCatalog.registry.contract(Cmd.USER_GET_USERS_HEADICON)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `mail info exposes a readable handler owned contract`() {
        assertEquals(204, Cmd.MAIL_INFO)
        val contract = CommandContractCatalog.registry.contract(Cmd.MAIL_INFO)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `mail brief info exposes a readable handler owned contract`() {
        assertEquals(209, Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID)
        val contract = CommandContractCatalog.registry.contract(Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID)
        assertEquals(CommandStatus.PROVISIONAL, contract?.status)
        assertEquals("GameServerHandler", contract?.owner)
    }

    @Test
    fun `prebook info is provisional and community token is locally rejected`() {
        assertEquals(40_008, Cmd.GET_PREBOOK_SERVER_INFO)
        val prebook = CommandContractCatalog.registry.contract(Cmd.GET_PREBOOK_SERVER_INFO)
        assertEquals(CommandDirection.CLIENT_REQUEST, prebook?.direction)
        assertEquals(CommandStatus.PROVISIONAL, prebook?.status)
        assertEquals("GameServerHandler", prebook?.owner)

        assertEquals(1_436, Cmd.COMMUNITY_GET_USER_TOKEN)
        val community = CommandContractCatalog.registry.contract(Cmd.COMMUNITY_GET_USER_TOKEN)
        assertEquals(CommandDirection.CLIENT_REQUEST, community?.direction)
        assertEquals(CommandDomain.EXTERNAL, community?.domain)
        assertEquals(CommandStatus.REJECTED, community?.status)
        assertEquals("GameServerHandler", community?.owner)

        val privileged = CommandContractCatalog.registry.contract(98_765)
        assertEquals(CommandStatus.REJECTED, privileged?.status)
        assertEquals("LocalPrivilegePolicy", privileged?.owner)
    }

    @Test
    fun `production registry contains every generated 9 2 2 inventory command`() {
        val registry = CommandContractCatalog.registry
        val all = registry.all()

        assertTrue(all.size >= 2_591)
        assertEquals(all.map(CommandContract::id).sorted(), all.map(CommandContract::id))
        assertNotNull(registry.contract(Cmd.GET_WORLD_SCENCE_INFO))
        assertNotNull(registry.contract(Cmd.SEND_WORLD_SCENCE_FULL_INFO))
        assertNotNull(registry.contract(Cmd.SYS_NOTIFY_DB_UPDATE))
        assertNotNull(registry.contract(2_100))
    }

    @Test
    fun `existing handler and emitted commands stay provisional until audited`() {
        val registry = CommandContractCatalog.registry

        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.CARD_RECRUIT)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.GET_WORLD_SCENCE_INFO)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(710)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.SYS_NOTIFY_DB_UPDATE)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(Cmd.SEND_WORLD_SCENCE_FULL_INFO)?.status)
        assertEquals(CommandStatus.PROVISIONAL, registry.contract(2_100)?.status)
    }

    @Test
    fun `request aware query contracts expose readable ids and explicit handler ownership`() {
        assertEquals(142, Cmd.UNION_GET_GROUP_LIST)
        assertEquals(5_070, Cmd.DAILY_REPORT_GET_DETAIL)
        assertEquals(5_210, Cmd.GET_HERO_RECOMMEND_2)
        assertEquals(6_078, Cmd.GET_UDS_GUESS_SEASON)

        val groupList = CommandContractCatalog.registry.contract(Cmd.UNION_GET_GROUP_LIST)
        assertEquals(CommandDirection.DUPLEX, groupList?.direction)
        assertEquals(CommandStatus.PROVISIONAL, groupList?.status)
        assertEquals("GameServerHandler", groupList?.owner)

        listOf(
            Cmd.DAILY_REPORT_GET_DETAIL,
            Cmd.GET_HERO_RECOMMEND_2,
            Cmd.GET_UDS_GUESS_SEASON,
        ).forEach { cmd ->
            val contract = CommandContractCatalog.registry.contract(cmd)
            assertEquals(CommandDirection.CLIENT_REQUEST, contract?.direction, "cmd=$cmd")
            assertEquals(CommandStatus.PROVISIONAL, contract?.status, "cmd=$cmd")
            assertEquals("GameServerHandler", contract?.owner, "cmd=$cmd")
        }

        val chatMembers = CommandContractCatalog.registry.contract(Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT)
        assertEquals(CommandStatus.PROVISIONAL, chatMembers?.status)
        assertEquals("GameServerHandler", chatMembers?.owner)
    }

    @Test
    fun `recorded shape command is eligible but unknown command is not`() {
        val registry = CommandContractCatalog.registry

        assertEquals(CommandStatus.OBSERVED_SHAPE, registry.contract(959)?.status)
        assertTrue(registry.isShapeResponseAllowed(959))
        assertTrue(!registry.isShapeResponseAllowed(45_678))
    }

    @Test
    fun `exact contracts require ownership shape projection and evidence`() {
        val inventory = ClientCommandInventory(
            clientVersion = "9.2.2",
            commands = listOf(ClientCommandInventoryEntry(id = 1)),
        )

        assertFailsWith<IllegalArgumentException> {
            CommandContractRegistry(
                inventory = inventory,
                overrides = listOf(
                    CommandContract(
                        id = 1,
                        names = listOf("ONE"),
                        direction = CommandDirection.CLIENT_REQUEST,
                        domain = CommandDomain.WORLD,
                        status = CommandStatus.EXACT,
                    ),
                ),
            )
        }
    }

    @Test
    fun `every inventory entry has one effective contract and observed shapes have bodies`() {
        val registry = CommandContractCatalog.registry
        val inventoryIds = CommandContractRegistry.loadFromClasspath()
            .commands
            .map(ClientCommandInventoryEntry::id)
            .toSet()
        val contracts = registry.all()

        assertEquals(inventoryIds, contracts.map(CommandContract::id).toSet())
        contracts
            .filter { it.status == CommandStatus.OBSERVED_SHAPE }
            .forEach { contract ->
                assertNotNull(
                    NetworkResponsePolicy.observedShapeBody(contract.id),
                    "missing observed response shape for ${contract.id}",
                )
            }
    }
}
