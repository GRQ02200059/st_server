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
            CurrentClientCommand.UNION_CREATE,
            CurrentClientCommand.UNION_INFO,
            CurrentClientCommand.UNION_MEMBER,
            Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT,
            CurrentClientCommand.GET_HOMEPAGE_INFO,
            CurrentClientCommand.CHAT,
            CurrentClientCommand.CHAT_HISTORY,
            Cmd.RANK_LIST,
            Cmd.USER_GET_USERS_HEADICON,
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
        ).distinct().map(::provisionalRequest)

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
