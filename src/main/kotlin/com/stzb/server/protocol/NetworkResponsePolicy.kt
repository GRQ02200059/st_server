package com.stzb.server.protocol

import com.stzb.server.game.GenericGameResponses

/**
 * 客户端大量 UI 请求没有服务端响应时会卡在等待态。这里集中管理协议兜底策略：
 * 已知业务协议优先由 GameServerHandler 精确处理；未实现的普通业务 cmd 先回空数组，
 * 保持 UI 可关闭/可继续操作，同时在日志中保留 cmd/body 供后续补精确协议。
 */
object NetworkResponsePolicy {
    private val noOpArrayCommands = setOf(
        22, 191, 220, 509, 700, 701, 888, 959, 963, 974, 2311,
        4330, 4331, 5013, 5043, 5044, 5045, 5070, 5201, 6037, 6351, 9099,
    )

    fun fallbackBody(cmdId: Int): String? =
        when {
            cmdId == 100 -> GenericGameResponses.unionInfoUnavailable()
            cmdId == 212 -> GenericGameResponses.userLookup()
            cmdId in pagedListCommands -> GenericGameResponses.emptyPagedList()
            cmdId in objectResultCommands -> GenericGameResponses.emptyObjectResult()
            cmdId in noOpArrayCommands -> GenericGameResponses.emptyArray()
            isBusinessCommand(cmdId) -> GenericGameResponses.emptyArray()
            else -> null
        }

    private val pagedListCommands = setOf(
        91, 180, 206, 3611, 3719, 4331, 5022,
    )

    private val objectResultCommands = setOf(
        8020, 8021, 8025, 8027, 8031, 8032,
    )

    private fun isBusinessCommand(cmdId: Int): Boolean =
        cmdId in 1..99999 && cmdId !in setOf(
            Cmd.SYS_HEART_BEAT,
            Cmd.SYS_NOTIFY_DB_UPDATE,
            Cmd.SYS_SID_INVALID,
            Cmd.SYS_CHECK_SID,
            Cmd.SYS_ACKNOWLEDGE,
            Cmd.SYS_NOTIFY_SID,
            Cmd.SYS_QUEUE,
            Cmd.SYS_PLATFORM_LOGIN_CHECK,
            Cmd.SYS_PRE_SERVER_TOKEN_CHECK,
            Cmd.SYS_LOGIN,
            Cmd.BATTLE_REPORT_PROFILE,
            Cmd.BATTLE_REPORT_DETAIL,
            Cmd.BATTLE_REPORT_SHORT_DETAIL,
        )
}
