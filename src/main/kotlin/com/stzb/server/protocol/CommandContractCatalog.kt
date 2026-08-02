package com.stzb.server.protocol

object CommandContractCatalog {
    val registry: CommandContractRegistry by lazy {
        CommandContractRegistry(
            inventory = CommandContractRegistry.loadFromClasspath(),
            overrides = mergedOverrides(),
        )
    }

    private fun mergedOverrides(): List<CommandContract> {
        val byId = linkedMapOf<Int, CommandContract>()
        observedShapeContracts().forEach { contract -> byId[contract.id] = contract }
        rejectedContracts().forEach { contract -> byId[contract.id] = contract }
        provisionalHandlerContracts().forEach { contract -> byId[contract.id] = contract }
        provisionalPushContracts().forEach { contract -> byId[contract.id] = contract }
        return byId.values.sortedBy(CommandContract::id)
    }

    private fun provisionalHandlerContracts(): List<CommandContract> =
        listOf(
            Cmd.SYS_HEART_BEAT,
            Cmd.SYS_ACKNOWLEDGE,
            Cmd.SYS_CHECK_SID,
            Cmd.SYS_PLATFORM_LOGIN_CHECK,
            Cmd.GET_ALL_SERVER_INFO_NEW,
            Cmd.GET_CLASSIC_AND_YOUTH_SERVER_LIST,
            Cmd.SYS_PRE_SERVER_TOKEN_CHECK,
            Cmd.SYS_LOGIN,
            Cmd.RANDOM_ROLE_NAME,
            Cmd.CREATE_ROLE,
            Cmd.GET_SERVER_TIME,
            Cmd.SYNC_SERVER_TIME,
            Cmd.BATTLE_REPORT_PROFILE,
            Cmd.BATTLE_REPORT_DETAIL,
            Cmd.BATTLE_REPORT_SHORT_DETAIL,
            Cmd.GET_UNION_BATTLE_REPORT,
            Cmd.GET_BLACK_LIST,
            Cmd.QUERY_WANTED_TO_REPOTR,
            Cmd.COMMAND_PLAN_GET_UNION_TEMP_GROUP,
            Cmd.UNION_STATION_PLAYER_DANMU_LIST_GET,
            CurrentClientCommand.UNION_CREATE,
            CurrentClientCommand.UNION_INFO,
            CurrentClientCommand.UNION_MEMBER,
            Cmd.UNION_OFFICIAL_LIST,
            Cmd.UNION_NPC_CITY_LIST,
            Cmd.UNION_GET_GROUP_LIST,
            Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT,
            Cmd.CHAT_UNION_PLAN_HISTORY_ID,
            Cmd.COMMAND_PLAN_GEL_UNION_TEMP_GROUP_MEMBER,
            Cmd.UNION_STATION_ALL_RECORDS,
            CurrentClientCommand.GET_HOMEPAGE_INFO,
            CurrentClientCommand.CHAT,
            CurrentClientCommand.CHAT_HISTORY,
            Cmd.RANK_LIST,
            Cmd.USER_GET_USERS_HEADICON,
            Cmd.MAIL_INFO,
            Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID,
            Cmd.ARMY_BATTLE,
            Cmd.BUILD_BUILDING,
            Cmd.UPGRADE_BUILDING,
            Cmd.LAND_INFO,
            Cmd.GET_USER_NPC_ARMY,
            Cmd.GET_LAND_NPC_ARMY,
            Cmd.GET_LAND_DEFEND_ARMY,
            Cmd.ADD_HERO_TO_ARMY,
            Cmd.REMOVE_HERO_FROM_ARMY,
            Cmd.SWITCH_HERO_IN_ARMY,
            Cmd.CONSCRIPT,
            Cmd.CONSCRIPT_IMMEDIATELY,
            Cmd.LEARN_HERO_SKILL,
            Cmd.REPLACE_HERO_SKILL,
            Cmd.FORGET_HERO_SKILL,
            Cmd.REMOVE_USER_SKILL,
            Cmd.CARD_RECRUIT,
            Cmd.CARD_QUICK_RECRUIT,
            Cmd.CARD_SET_ALL_NOT_NEW,
            Cmd.HERO_SELECT_FACADE,
            CurrentClientCommand.HERO_USE_CARD_BORDER,
            CurrentClientCommand.ROTATE_CARD_BORDER_ADD,
            CurrentClientCommand.ROTATE_CARD_BORDER_REMOVE,
            CurrentClientCommand.HERO_ACTIVE_CARD_BORDER,
            CurrentClientCommand.BATCH_ACTIVE_ARMY_FACADE_CARD,
            CurrentClientCommand.UNLOCK_TROOP_FACADE_CARD,
            CurrentClientCommand.USE_TROOP_FACADE_CARD,
            CurrentClientCommand.HERO_ACTIVE_FACADE,
            Cmd.HERO_ADVANCE,
            Cmd.GEAR_EQUIP,
            Cmd.GEAR_FORGET,
            Cmd.CARD_ADD_POINT,
            Cmd.CARD_WASH_POINT,
            Cmd.CARD_PROTECT,
            Cmd.CARD_SAVE_POINT_PLAN,
            Cmd.CARD_CHANGE_POINT_PLAN,
            Cmd.CARD_EXTRACT_SWITCH,
            Cmd.CARD_SELECT_HERO,
            Cmd.GET_WORLD_SCENCE_INFO,
            Cmd.SYS_PING,
            Cmd.QUERY_ARMY_RELATED_FORT,
            Cmd.BUILD_FACADE_APPLY_BUILD_SCHEME,
            Cmd.SET_CLIENT_RED_DOT_DATA,
            Cmd.SET_FRONT_UNLOCK_ANIM,
            Cmd.USER_CHANGE_NAME,
            Cmd.HERO_TEAM_LIBRARY,
            Cmd.NORMAL_TEAM_COMPOSITION,
            Cmd.WORLD_BOSS_SAVE_TEAM,
            Cmd.EXERCISE_DAILY_SAVE_TEAM,
        ).distinct().map(::provisionalRequest) +
            listOf(
                Cmd.LOG_FPS,
                Cmd.SEND_ACSDK_CHEAT_INFO,
                Cmd.USER_CLOSE_UI,
                Cmd.USER_OPEN_UI,
                Cmd.LOG_MUSIC_OPEN,
                Cmd.RESFILE_LOG_HUB_RECORD,
                Cmd.DAILY_REPORT_LOG,
                Cmd.CCLIVE_MAIN_BTN_OPEN_LOG,
                Cmd.LOG_SHIELD_WORDS,
                Cmd.FEED_CLICKED_LOG,
                Cmd.ACTIVITY_SCENE_DIALOG_LOG,
                Cmd.ANNIVERSARY_COMPETITION_FOR_LOG,
                Cmd.TRIAL_SAND_TABLE_LOG,
                Cmd.REPORT_NEWBIE_GUIDE,
                Cmd.HELP_GUIDE_TIPS_LOG,
                Cmd.UPDATE_GUIDE_RECORD,
                Cmd.PRE_SERVER_QUERY_USER_OP,
                Cmd.IP_USER_COUNT_PRE,
                Cmd.DAILY_REPORT_GET_DETAIL,
                Cmd.GET_HERO_RECOMMEND_2,
                Cmd.GET_UDS_GUESS_SEASON,
                Cmd.GET_USER_SEASON_RECORD,
                Cmd.GET_SEASON_HISTROY_PARAMS,
                Cmd.CARD_RECORD,
                Cmd.CHECK_HAVE_UNION_TO_JOIN,
                Cmd.REALNAME_LOGOUT,
                Cmd.MAIL_OUTBOX,
                Cmd.NOTICE_LIST,
                Cmd.FRIEND_GROUP_GET_HISTORY_CHAT,
                Cmd.STRATEGY_HELP_GET,
                Cmd.SWITCH_ROLE_QUERY_ROLE_LIST,
                Cmd.MAIL_INBOX,
                Cmd.MAIL_GET_CONTACTS,
                Cmd.USER_GET_SEASON_COURSE_LIST,
                Cmd.CHAT_GET_ZHAO_XIAN_MSG,
                Cmd.PROGRESS_GET_INFO,
                Cmd.MAIL_NOTIFY_GET_ALL,
                Cmd.ARMY_REINFORCE_STAY_CHECK,
                Cmd.UNION_STATION_GET_DATA,
                Cmd.YOUTH_INK_MAP_TIPS,
                Cmd.XUANFUQIU_RECEIVED_MSG,
                Cmd.GAME_CHENGXIANGGE_RECEIVED,
            ).map(::provisionalClientRequest) +
            listOf(
                Cmd.REVENUE,
                Cmd.REVENUE_DOUBLE,
            ).map { id ->
                provisionalRequest(id).copy(domain = CommandDomain.CITY)
            } +
            listOf(
                provisionalClientRequest(Cmd.CCLIVE_GET_FOLLOW_LIST)
                    .copy(domain = CommandDomain.EXTERNAL),
                provisionalClientRequest(Cmd.FIRST_STATE_COOUPY_MSG)
                    .copy(domain = CommandDomain.WORLD),
                provisionalClientRequest(Cmd.UNION_RELATION_FULL_REQUEST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalClientRequest(Cmd.UNION_STATION_ENTER_SCENE)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.UNION_MEMBER_CLAN_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.CLAN_LOG_GET)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.UNION_LEADER_CLAN_CITY_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.CLAN_APPLICANT_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.CLAN_NEARBY_CLAN_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.CLAN_NPC_CITY_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.CLAN_GET_CONTRIBUTION_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.CLAN_GET_JUNXIAN_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.CLAN_SUPREME_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.UNION_NEARBY_PLAYER_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.UNION_APPLICANT_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalClientRequest(Cmd.CHAT_GET_SAND_TABLE_ROOM_MSG)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalClientRequest(Cmd.FRIEND_SEARCH)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.UNION_SEARCH_UNION_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.UNION_SEARCH_PLAYER_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalClientRequest(Cmd.SEARCH_USER)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalClientRequest(Cmd.FAMILY_PRAY_RESULT_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalClientRequest(Cmd.FAMILY_MINI_GAME_GET_SCORE_LIST)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.FAMILY_MINI_GAME_GET_ROOM_LIST)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.GET_INVITE_LIST)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.GET_ZHAOHUI_LIST)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.QUERY_OTHER_REGION_CLAN_LIST)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalClientRequest(Cmd.SUMMER_FARM_GET_USER_LIST)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.SUMMER_FARM_MESSAGE_RECORD)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.SUMMER_FARM_VISIT_RECORD)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.GET_UNION_LETTER)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.NOBILITY_TITLE_QUERY_EIGHT_OFFICER_RECORD)
                    .copy(domain = CommandDomain.SOCIAL),
                provisionalRequest(Cmd.GET_USER_RES_WID_LEVEL_MAP)
                    .copy(domain = CommandDomain.WORLD),
                provisionalClientRequest(Cmd.GET_NZ_EFFECT_LAND_LIST)
                    .copy(domain = CommandDomain.WORLD),
                provisionalClientRequest(Cmd.GET_FIELD_RES_TOTAL_STORE)
                    .copy(domain = CommandDomain.WORLD),
                provisionalClientRequest(Cmd.QUERY_USER_MARKET_SCORE)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalClientRequest(Cmd.WORLD_BOSS_TOP_THREE_RANK)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalRequest(Cmd.OWN_RANK)
                    .copy(domain = CommandDomain.ACTIVITY),
                provisionalRequest(Cmd.PROGRESS_GET_NPC_OCCUPY_INFO)
                    .copy(domain = CommandDomain.WORLD),
                provisionalRequest(Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX)
                    .copy(domain = CommandDomain.WORLD),
                provisionalClientRequest(Cmd.FENGLU_LEVEL_STATUS)
                    .copy(domain = CommandDomain.SOCIAL),
            ) +
            CommandContract(
                id = Cmd.GET_PREBOOK_SERVER_INFO,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.PROVISIONAL,
                owner = "GameServerHandler",
            )

    private fun provisionalPushContracts(): List<CommandContract> =
        listOf(
            Cmd.SYS_NOTIFY_SID,
            Cmd.SYS_NOTIFY_DB_UPDATE,
            Cmd.SEND_WORLD_SCENCE_FULL_INFO,
            CurrentClientCommand.NOTIFY_CHAT_MSG,
        ).map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.SERVER_PUSH,
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.PROVISIONAL,
                owner = "GameServerHandler",
            )
        }

    private fun observedShapeContracts(): List<CommandContract> =
        NetworkResponsePolicy.observedShapeCommandIds().map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.UNKNOWN,
                status = CommandStatus.OBSERVED_SHAPE,
                owner = "NetworkResponsePolicy",
            )
        }

    private fun rejectedContracts(): List<CommandContract> =
        listOf(
            Cmd.PHONE_BIND_SEND_VERIFY_CODE,
            Cmd.PHONE_BIND_CHECK_VERIFY_CODE,
            Cmd.PHONE_UNBIND,
            Cmd.GET_WHICH_CHANNEL_SERVER,
            Cmd.DMM_ACCOUNT_CHECK,
            Cmd.PRE_SERVER_QUERY_S2_RETRUN_ROLE_INFO,
            Cmd.PRE_SERVER_QUERY_ADVERTISEMENT_SIGN,
            Cmd.GET_CHANNEL_TRANSFER_TOKEN,
        ).map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            )
        } +
        listOf(
            CommandContract(
                id = Cmd.SET_CHANNEL_CERTIFICATION,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.COMMUNITY_GET_USER_TOKEN,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.PRE_SERVER_GEN_H5_SIGN,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.QUERY_NEW_COMMUNITY_INFO,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.QUERY_SIMULATE_TOKEN,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.FILE_PICKER_GET_TOKEN_DEFAULT,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.CHECK_ADD_WEIXIN,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.SOLDIER_GIFT_ACTIVATE,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.ACTIVITY,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.BLACK_MARKET_REFRESH_AUTO,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.ACTIVITY,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.PATORL_GET,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.WORLD,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.PATORL_HANDLE,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.WORLD,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = Cmd.PATORL_REWARD_GET,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.WORLD,
                status = CommandStatus.REJECTED,
                owner = "GameServerHandler",
            ),
            CommandContract(
                id = 98_765,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "LocalPrivilegePolicy",
            ),
        )

    private fun provisionalRequest(id: Int): CommandContract =
        CommandContract(
            id = id,
            names = emptyList(),
            direction = CommandDirection.DUPLEX,
            domain = CommandDomain.UNKNOWN,
            status = CommandStatus.PROVISIONAL,
            owner = "GameServerHandler",
        )

    private fun provisionalClientRequest(id: Int): CommandContract =
        CommandContract(
            id = id,
            names = emptyList(),
            direction = CommandDirection.CLIENT_REQUEST,
            domain = CommandDomain.UNKNOWN,
            status = CommandStatus.PROVISIONAL,
            owner = "GameServerHandler",
        )
}

private object CurrentClientCommand {
    const val UNION_INFO = 100
    const val UNION_CREATE = 102
    const val UNION_MEMBER = 103
    const val GET_HOMEPAGE_INFO = 3686
    const val HERO_USE_CARD_BORDER = 673
    const val HERO_ACTIVE_CARD_BORDER = 675
    const val USE_TROOP_FACADE_CARD = 677
    const val UNLOCK_TROOP_FACADE_CARD = 678
    const val BATCH_ACTIVE_ARMY_FACADE_CARD = 682
    const val CHAT = 710
    const val CHAT_HISTORY = 711
    const val ROTATE_CARD_BORDER_ADD = 1673
    const val ROTATE_CARD_BORDER_REMOVE = 1674
    const val NOTIFY_CHAT_MSG = 2100
    const val HERO_ACTIVE_FACADE = 2520
}
