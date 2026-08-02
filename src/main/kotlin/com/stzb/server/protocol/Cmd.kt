package com.stzb.server.protocol

/**
 * 系统级命令号 (逆向自 Tenth.Network/NetCommandDef.cs)。
 * 业务命令号极多, 这里只放 P0 协议骨架需要的系统命令。
 */
object Cmd {
    const val SYS_HEART_BEAT = 90003        // 心跳 (客户端每 180s 发)
    const val SYS_NOTIFY_DB_UPDATE = 90005  // DB 增删改通知
    const val SYS_SID_INVALID = 90007       // SID 失效通知 (下行)
    const val SYS_CHECK_SID = 90008         // 重连校验 SID (上行)
    const val SYS_ACKNOWLEDGE = 90009       // ACK (收到带 hashCode 的包后回)
    const val SYS_NOTIFY_SID = 98888        // 握手: 服务端下发 userId/cmdIndex/sid
    const val SYS_QUEUE = 96666             // 登录排队 (下行, 特殊二进制包)
    const val SYS_PLATFORM_LOGIN_CHECK = 99992 // 平台校验, 服务端签发 ServerSession (登录服前置硬卡点)
    const val SYS_PRE_SERVER_TOKEN_CHECK = 99994 // 预登录服 token 校验
    const val SYS_LOGIN = 99991             // 登录请求/响应

    const val GET_ALL_SERVER_INFO_NEW = 20003
    const val GET_CLASSIC_AND_YOUTH_SERVER_LIST = 98702

    const val CREATE_ROLE = 2
    const val ARMY_BATTLE = 6
    const val BATTLE_REPORT_PROFILE = 10
    const val BATTLE_REPORT_DETAIL = 11
    const val BUILD_BUILDING = 13
    const val UPGRADE_BUILDING = 14
    const val LAND_INFO = 21
    const val LOG_FPS = 24
    const val GET_SERVER_TIME = 25
    const val ADD_HERO_TO_ARMY = 30
    const val REMOVE_HERO_FROM_ARMY = 31
    const val SWITCH_HERO_IN_ARMY = 32
    const val CONSCRIPT = 37
    const val CONSCRIPT_IMMEDIATELY = 38
    const val LEARN_HERO_SKILL = 71
    const val FORGET_HERO_SKILL = 72
    const val REMOVE_USER_SKILL = 77
    const val GET_UNION_BATTLE_REPORT = 92
    const val REPLACE_HERO_SKILL = 98
    const val UNION_INFO = 100
    const val UNION_CREATE = 102
    const val UNION_MEMBER = 103
    const val UNION_APPLICANT_LIST = 104
    const val UNION_OFFICIAL_LIST = 110
    const val UNION_NEARBY_PLAYER_LIST = 112
    const val UNION_NPC_CITY_LIST = 135
    const val UNION_GET_GROUP_LIST = 142
    const val UNION_GET_ALL_MEMBER_LIST_FOR_CHAT = 143
    const val SWITCH_ROLE_QUERY_ROLE_LIST = 171
    const val SEND_ACSDK_CHEAT_INFO = 191
    const val MAIL_INBOX = 202
    const val MAIL_OUTBOX = 203
    const val MAIL_INFO = 204
    const val MAIL_BRIEF_INFO_BY_MAIL_ID = 209
    const val MAIL_GET_CONTACTS = 220
    const val COMMUNITY_GET_USER_TOKEN = 1436
    const val CARD_RECRUIT = 301
    const val CARD_SET_ALL_NOT_NEW = 302
    const val CARD_QUICK_RECRUIT = 304
    const val CARD_ADD_POINT = 80
    const val CARD_WASH_POINT = 81
    const val CARD_PROTECT = 82
    const val HERO_ADVANCE = 83
    const val CARD_SAVE_POINT_PLAN = 185
    const val CARD_CHANGE_POINT_PLAN = 186
    const val CARD_EXTRACT_SWITCH = 300
    const val CARD_SELECT_HERO = 308
    const val CARD_RECORD = 671
    const val HERO_USE_CARD_BORDER = 673
    const val HERO_SELECT_FACADE = 674
    const val HERO_ACTIVE_CARD_BORDER = 675
    const val USE_TROOP_FACADE_CARD = 677
    const val UNLOCK_TROOP_FACADE_CARD = 678
    const val BATCH_ACTIVE_ARMY_FACADE_CARD = 682
    const val RANK_LIST = 700
    const val OWN_RANK = 703
    const val CHAT = 710
    const val CHAT_HISTORY = 711
    const val GET_BLACK_LIST = 714
    const val REVENUE = 750
    const val REVENUE_DOUBLE = 752
    const val NOTICE_LIST = 780
    const val USER_CLOSE_UI = 875
    const val USER_OPEN_UI = 885
    const val LOG_MUSIC_OPEN = 888
    const val BLACK_MARKET_REFRESH_AUTO = 933
    const val GET_USER_SEASON_RECORD = 980
    const val REALNAME_LOGOUT = 981
    const val GEAR_EQUIP = 1226
    const val GEAR_FORGET = 1227
    const val FENGLU_LEVEL_STATUS = 1265
    const val HERO_ACTIVE_FACADE = 2520
    const val NOTIFY_CHAT_MSG = 2100
    const val SET_CHANNEL_CERTIFICATION = 2311
    const val XUANFUQIU_RECEIVED_MSG = 2402
    const val GAME_CHENGXIANGGE_RECEIVED = 2404
    const val CCLIVE_GET_FOLLOW_LIST = 2529
    const val PATORL_GET = 2600
    const val PATORL_HANDLE = 2601
    const val PATORL_REWARD_GET = 2604
    const val USER_CHANGE_NAME = 507
    const val USER_GET_SEASON_COURSE_LIST = 509
    const val RANDOM_ROLE_NAME = 511
    const val USER_GET_USERS_HEADICON = 514
    const val SYNC_SERVER_TIME = 694
    const val CHAT_GET_ZHAO_XIAN_MSG = 727
    const val CHAT_GET_SAND_TABLE_ROOM_MSG = 736
    const val FRIEND_SEARCH = 741
    const val ROTATE_CARD_BORDER_ADD = 1673
    const val ROTATE_CARD_BORDER_REMOVE = 1674
    const val PROGRESS_GET_INFO = 871
    const val PROGRESS_GET_NPC_OCCUPY_INFO = 873
    const val PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX = 874
    const val SET_CLIENT_RED_DOT_DATA = 3400
    const val UNION_SEARCH_UNION_LIST = 3410
    const val UNION_SEARCH_PLAYER_LIST = 3411
    const val GET_HOMEPAGE_INFO = 3686
    const val MAIL_NOTIFY_GET_ALL = 3758
    const val FRIEND_GROUP_GET_HISTORY_CHAT = 3846
    const val FILE_PICKER_GET_TOKEN_DEFAULT = 3928
    const val BUILD_FACADE_APPLY_BUILD_SCHEME = 3945
    const val CHECK_HAVE_UNION_TO_JOIN = 4087
    const val RESFILE_LOG_HUB_RECORD = 4326
    const val GET_USER_NPC_ARMY = 4329
    const val GET_LAND_NPC_ARMY = 4330
    const val GET_LAND_DEFEND_ARMY = 4331
    const val QUERY_ARMY_RELATED_FORT = 4159
    const val DAILY_REPORT_LOG = 4966
    const val QUERY_WANTED_TO_REPOTR = 4967
    const val CHECK_ADD_WEIXIN = 4968
    const val GET_SEASON_HISTROY_PARAMS = 5021
    const val GET_WORLD_SCENCE_INFO = 5025
    const val SEND_WORLD_SCENCE_FULL_INFO = 5026
    const val HELP_GUIDE_TIPS_LOG = 5069
    const val DAILY_REPORT_GET_DETAIL = 5070
    const val STRATEGY_HELP_GET = 5082
    const val UPDATE_GUIDE_RECORD = 5091
    const val GET_HERO_RECOMMEND_2 = 5210
    const val SOLDIER_GIFT_ACTIVATE = 6030
    const val FIRST_STATE_COOUPY_MSG = 6037
    const val CHAT_UNION_PLAN_HISTORY_ID = 6053
    const val COMMAND_PLAN_GET_UNION_TEMP_GROUP = 6067
    const val COMMAND_PLAN_GEL_UNION_TEMP_GROUP_MEMBER = 6068
    const val GET_UDS_GUESS_SEASON = 6078
    const val ARMY_REINFORCE_STAY_CHECK = 6219
    const val BATTLE_REPORT_SHORT_DETAIL = 6231
    const val TEAM_INVITATIONAL_QUERY_MEMBER_FOR_INVITE = 6242
    const val UNION_STATION_ENTER_SCENE = 6242
    const val UNION_STATION_GET_DATA = 6243
    const val UNION_STATION_ALL_RECORDS = 6244
    const val UNION_STATION_PLAYER_DANMU_LIST_GET = 6256
    const val UNION_RELATION_FULL_REQUEST = 6351
    const val SET_FRONT_UNLOCK_ANIM = 7046
    const val SYS_PING = 90006
    const val WORLD_BOSS_SAVE_TEAM = 8005
    const val WORLD_BOSS_TOP_THREE_RANK = 8009
    const val EXERCISE_DAILY_SAVE_TEAM = 8011
    const val NORMAL_TEAM_COMPOSITION = 9026
    const val HERO_TEAM_LIBRARY = 9029
    const val PRE_SERVER_QUERY_USER_OP = 40003
    const val PRE_SERVER_GEN_H5_SIGN = 40004
    const val GET_PREBOOK_SERVER_INFO = 40008
    const val USER_GET_CUSTOMER_SERVICE_TOKEN_PRE = 40016
    const val YOUTH_INK_MAP_TIPS = 40018
    const val QUERY_NEW_COMMUNITY_INFO = 40020
    const val QUERY_SIMULATE_TOKEN = 40021
    const val IP_USER_COUNT_PRE = 40022
}

/**
 * P0 离线推演用的服务器/角色常量。
 * 服务器列表里下发的 host:port 即客户端选服后回连的游戏服地址 (这里就是本进程)。
 */
object GameServerConfig {
    const val SERVER_ID = 1001
    const val RUN_SERVER_ID = 1001
    /**
     * 当前客户端内置并可安全加载的地图/登录配置。
     * 用 index 5（常规赛季图，map_game_data/5 已缓存，不卡 100%）而非 984 征服图：
     * 984 在客户端 MultiCfgTable 里把 tb_cfg_card_extract 映射到 17 字节空表，
     * 导致招募卡包与依赖 category==3 pack 的战法 tab 全部消失；
     * index 5 的 tb_cfg_card_extract_5.bin 完整（~55KB），卡包/战法可正常显示。
     * cfgDataIndex / sys_param(26) 均引用本常量，回退时单点即可。
     */
    const val CFG_DB_ID = 5
    const val SERVER_NAME = "本地一区"
    // 真机联调使用 adb reverse: 手机 127.0.0.1:59979 -> Mac 127.0.0.1:59979。
    // AVD 联调时可改回 10.0.2.2; 同网段真机直连可改为 Mac 局域网 IP。
    const val DEFAULT_HOST = "127.0.0.1"
    const val ROLE_NAME = "主公"
    /**
     * 洛阳 (15011501) 东南约五格的 3x3 空地中心。已核对 cfg-5 静态城池表，
     * 该九格范围不与洛阳本体或其它静态城池重叠。
     */
    const val CITY_WID = 15_061_506
    const val LEGACY_CITY_WID = 100_001
    /** 开服秒级时间戳 (固定一个较早的值, 让各种 end_time 判定处于 "已过期/无进行中")。 */
    const val OPEN_TIME_SEC = 1_600_000_000L

    fun advertisedHost(): String =
        System.getProperty("stzb.publicHost")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_HOST
}

/** 上行包体编码标志 (帧头 flag 字节)。 */
object UpFlag {
    const val PLAIN: Byte = 1               // 明文
    const val XOR: Byte = 5                 // body 用 (byte)checkCode 逐字节 XOR
}

/** 下行包体编码类型 (dataType 字节)。 */
object DownType {
    const val PLAIN: Byte = 1               // 明文 JSON
    const val ZLIB: Byte = 3                // zlib 压缩 (前置 4B 原始长度)
    const val XOR: Byte = 5                 // 固定 0x98 XOR
    const val XOR_KEY: Int = 0x98           // 152
}
