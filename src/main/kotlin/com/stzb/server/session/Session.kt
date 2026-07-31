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
    val wireUserId: Int,
    val sid: ByteArray,
) {
    @Volatile
    var accountKey: String? = null
        private set

    @Volatile
    var playerId: Int? = null
        private set

    /** Existing command handlers use this stable ID after account binding. */
    val userId: Int
        get() = playerId ?: wireUserId

    /** 服务端下发时的初始 cmdIndex, 客户端后续沿用并递增。 */
    val cmdIndex = AtomicInteger(0)

    @Volatile
    var lastRecvTime: Long = System.currentTimeMillis()

    fun bind(accountKey: String, playerId: Int) {
        require(accountKey.isNotBlank()) { "accountKey 不能为空" }
        require(playerId > 0) { "playerId 必须为正数" }
        this.accountKey = accountKey
        this.playerId = playerId
    }

    companion object {
        private val secureRandom = SecureRandom()
        private val wireUserSequence = AtomicInteger(1_000_000)

        /**
         * 98888 must be emitted before the client sends platform credentials,
         * so it carries a connection-only wire ID. A caller may bind a known
         * account for tests or controlled local startup.
         */
        fun create(accountKey: String? = null): Session {
            val sid = ByteArray(32)
            secureRandom.nextBytes(sid)
            return Session(wireUserSequence.incrementAndGet(), sid).also { session ->
                accountKey?.let { key ->
                    val state = PlayerStateRepository.getOrCreate(
                        accountKey = key,
                        cityWid = GameServerConfig.CITY_WID,
                        roleName = GameServerConfig.ROLE_NAME,
                    )
                    session.bind(key, state.userId)
                }
            }
        }
    }
}
