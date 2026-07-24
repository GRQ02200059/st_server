package com.stzb.server.protocol

/**
 * 已解析的上行请求 (客户端 -> 服务端)。
 *
 * 线格式 (读掉 4B 大端长度前缀后, 大端):
 *   serverId(4) userId(4) sid(32) cmdId(4) cmdIndex(4) checkCode(4) flag(1) body(N)
 *   checkCode = (cmdIndex*13) xor cmdId xor userId
 *   flag=5: body 已用 (byte)checkCode 逐字节 XOR (解码后放入 body)
 *   flag=1: body 明文
 * body 为 UTF-8 JSON (可能为空)。
 */
data class UpPacket(
    val serverId: Int,
    val userId: Int,
    val sid: ByteArray,        // 32 字节
    val cmdId: Int,
    val cmdIndex: Int,
    val checkCode: Int,
    val flag: Byte,
    val body: ByteArray,       // 已解密的 JSON 字节 (可能长度 0)
) {
    val bodyText: String get() = String(body, Charsets.UTF_8)

    /** checkCode 是否与 (cmdIndex*13)^cmdId^userId 一致。 */
    val checkOk: Boolean get() = checkCode == ((cmdIndex * 13) xor cmdId xor userId)

    override fun toString(): String =
        "UpPacket(cmd=$cmdId, idx=$cmdIndex, uid=$userId, flag=$flag, checkOk=$checkOk, body=${bodyText.take(200)})"
}

/**
 * 待发送的下行响应 (服务端 -> 客户端)。
 *
 * 普通包线格式 (长度前缀之后, 大端):
 *   cmd(4) hashCode(4) dataType(1) body(N)
 * 98888/96666 为特殊二进制包, 由专门的 build 函数生成, 不走此结构。
 */
data class DownPacket(
    val cmd: Int,
    val hashCode: Int = 0,
    val body: ByteArray = ByteArray(0),       // JSON 字节 (未编码)
    val dataType: Byte = DownType.PLAIN,
) {
    companion object {
        fun json(cmd: Int, jsonText: String, hashCode: Int = 0, dataType: Byte = DownType.PLAIN): DownPacket =
            DownPacket(cmd, hashCode, jsonText.toByteArray(Charsets.UTF_8), dataType)
    }
}
