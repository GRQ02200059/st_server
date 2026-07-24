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
    const val GET_SERVER_TIME = 25
    const val ADD_HERO_TO_ARMY = 30
    const val REMOVE_HERO_FROM_ARMY = 31
    const val SWITCH_HERO_IN_ARMY = 32
    const val CONSCRIPT = 37
    const val CONSCRIPT_IMMEDIATELY = 38
    const val CARD_RECRUIT = 301
    const val CARD_SET_ALL_NOT_NEW = 302
    const val CARD_QUICK_RECRUIT = 304
    const val CARD_ADD_POINT = 80
    const val CARD_WASH_POINT = 81
    const val CARD_PROTECT = 82
    const val CARD_SAVE_POINT_PLAN = 185
    const val CARD_CHANGE_POINT_PLAN = 186
    const val CARD_EXTRACT_SWITCH = 300
    const val CARD_SELECT_HERO = 308
    const val USER_CHANGE_NAME = 507
    const val RANDOM_ROLE_NAME = 511
    const val SYNC_SERVER_TIME = 694
    const val SET_CLIENT_RED_DOT_DATA = 3400
    const val QUERY_ARMY_RELATED_FORT = 4159
    const val GET_WORLD_SCENCE_INFO = 5025
    const val SEND_WORLD_SCENCE_FULL_INFO = 5026
    const val BATTLE_REPORT_SHORT_DETAIL = 6231
    const val SET_FRONT_UNLOCK_ANIM = 7046
    const val SYS_PING = 90006
    const val WORLD_BOSS_SAVE_TEAM = 8005
    const val EXERCISE_DAILY_SAVE_TEAM = 8011
    const val NORMAL_TEAM_COMPOSITION = 9026
    const val HERO_TEAM_LIBRARY = 9029
}

/**
 * P0 离线推演用的服务器/角色常量。
 * 服务器列表里下发的 host:port 即客户端选服后回连的游戏服地址 (这里就是本进程)。
 */
object GameServerConfig {
    const val SERVER_ID = 1001
    const val RUN_SERVER_ID = 1001
    const val CFG_DB_ID = 2001
    const val SERVER_NAME = "本地一区"
    // 真机联调使用 adb reverse: 手机 127.0.0.1:59979 -> Mac 127.0.0.1:59979。
    // AVD 联调时可改回 10.0.2.2; 同网段真机直连可改为 Mac 局域网 IP。
    const val DEFAULT_HOST = "127.0.0.1"
    const val ROLE_NAME = "主公"
    /** 主城 wid (= Tb_user.city_wid = Tb_user_city.city_wid = Tb_world_city.wid)。 */
    const val CITY_WID = 100001
    /** 开服秒级时间戳 (固定一个较早的值, 让各种 end_time 判定处于 "已过期/无进行中")。 */
    const val OPEN_TIME_SEC = 1_600_000_000L

    fun advertisedHost(): String =
        System.getProperty("stzb.publicHost")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv("STZB_PUBLIC_HOST")
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
