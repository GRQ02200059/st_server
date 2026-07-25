package com.stzb.server.session

import com.stzb.server.game.PlayerStateRepository
import com.stzb.server.protocol.GameServerConfig
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单条连接的会话状态。
 * userId/sid 由服务端在握手 (98888) 时分配, 之后校验上行帧。
 */
class Session(
    val userId: Int,
    val sid: ByteArray,
    val accountKey: String,
) {
    /** 服务端下发时的初始 cmdIndex, 客户端后续沿用并递增。 */
    val cmdIndex = AtomicInteger(0)

    @Volatile
    var lastRecvTime: Long = System.currentTimeMillis()

    companion object {
        private val secureRandom = SecureRandom()

        /** 为账号创建新连接: 玩家身份持久化, SID 仅对本次连接有效。 */
        fun create(accountKey: String = DEFAULT_ACCOUNT_KEY): Session {
            val uid = PlayerStateRepository.getOrCreate(
                accountKey = accountKey,
                cityWid = GameServerConfig.CITY_WID,
                roleName = GameServerConfig.ROLE_NAME,
            ).userId
            val sid = ByteArray(32)
            secureRandom.nextBytes(sid)
            return Session(uid, sid, accountKey)
        }

        private const val DEFAULT_ACCOUNT_KEY = "local-dev-account"
    }
}
