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
            Cmd.UNION_CREATE,
            Cmd.UNION_INFO,
            Cmd.UNION_MEMBER,
            Cmd.GET_HOMEPAGE_INFO,
            Cmd.CHAT,
            Cmd.CHAT_HISTORY,
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
            Cmd.HERO_USE_CARD_BORDER,
            Cmd.ROTATE_CARD_BORDER_ADD,
            Cmd.ROTATE_CARD_BORDER_REMOVE,
            Cmd.HERO_ACTIVE_CARD_BORDER,
            Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
            Cmd.UNLOCK_TROOP_FACADE_CARD,
            Cmd.USE_TROOP_FACADE_CARD,
            Cmd.HERO_ACTIVE_FACADE,
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
            Cmd.SET_CLIENT_RED_DOT_DATA,
            Cmd.SET_FRONT_UNLOCK_ANIM,
            Cmd.USER_CHANGE_NAME,
            Cmd.HERO_TEAM_LIBRARY,
            Cmd.NORMAL_TEAM_COMPOSITION,
            Cmd.WORLD_BOSS_SAVE_TEAM,
            Cmd.EXERCISE_DAILY_SAVE_TEAM,
        ).distinct().map(::provisionalRequest)

    private fun provisionalPushContracts(): List<CommandContract> =
        listOf(
            Cmd.SYS_NOTIFY_SID,
            Cmd.SYS_NOTIFY_DB_UPDATE,
            Cmd.SEND_WORLD_SCENCE_FULL_INFO,
            Cmd.NOTIFY_CHAT_MSG,
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
        listOf(98_765).map { id ->
            CommandContract(
                id = id,
                names = emptyList(),
                direction = CommandDirection.CLIENT_REQUEST,
                domain = CommandDomain.EXTERNAL,
                status = CommandStatus.REJECTED,
                owner = "LocalPrivilegePolicy",
            )
        }

    private fun provisionalRequest(id: Int): CommandContract =
        CommandContract(
            id = id,
            names = emptyList(),
            direction = CommandDirection.DUPLEX,
            domain = CommandDomain.UNKNOWN,
            status = CommandStatus.PROVISIONAL,
            owner = "GameServerHandler",
        )
}
