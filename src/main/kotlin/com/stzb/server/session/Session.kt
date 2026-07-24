package com.stzb.server.session

import java.util.concurrent.atomic.AtomicInteger

/**
 * 单条连接的会话状态。
 * userId/sid 由服务端在握手 (98888) 时分配, 之后校验上行帧。
 */
class Session(val userId: Int, val sid: ByteArray) {
    /** 服务端下发时的初始 cmdIndex, 客户端后续沿用并递增。 */
    val cmdIndex = AtomicInteger(0)

    @Volatile
    var lastRecvTime: Long = System.currentTimeMillis()

    companion object {
        private val userIdSeq = AtomicInteger(10000)

        /** 生成一个新会话: 递增 userId + 32 字节随机 sid。 */
        fun create(): Session {
            val uid = userIdSeq.incrementAndGet()
            val sid = ByteArray(32)
            java.util.Random(uid.toLong()).nextBytes(sid)
            return Session(uid, sid)
        }
    }
}
