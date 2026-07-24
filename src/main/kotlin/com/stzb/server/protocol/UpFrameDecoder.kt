package com.stzb.server.protocol

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

/**
 * 上行帧解码器。
 *
 * 处理 4B 大端长度前缀分帧 + 头字段解析 + body 解密, 产出 [UpPacket]。
 * 长度字段值 = 其后所有字节数 (不含长度字段本身), 见 DotnetBaseSocket.CreateSendNetBuff。
 *
 * 未继承 LengthFieldBasedFrameDecoder, 而是手动分帧: 因为解出长度后还要按帧内 flag
 * 决定是否 XOR 解密, 手写更直观且便于日志。
 */
class UpFrameDecoder : ByteToMessageDecoder() {

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        while (true) {
            if (input.readableBytes() < 4) return
            input.markReaderIndex()
            val len = input.readInt()                 // 大端, = 头(53) + body
            if (len < HEADER_SIZE || len > MAX_FRAME) {
                // 帧非法, 关闭连接避免错位累积
                ctx.close()
                return
            }
            if (input.readableBytes() < len) {
                input.resetReaderIndex()              // 半包, 等更多数据
                return
            }
            out.add(readFrame(input, len))
        }
    }

    private fun readFrame(buf: ByteBuf, len: Int): UpPacket {
        val serverId = buf.readInt()
        val userId = buf.readInt()
        val sid = ByteArray(SID_SIZE).also { buf.readBytes(it) }
        val cmdId = buf.readInt()
        val cmdIndex = buf.readInt()
        val checkCode = buf.readInt()
        val flag = buf.readByte()
        val bodyLen = len - HEADER_SIZE
        val body = ByteArray(bodyLen).also { buf.readBytes(it) }
        if (flag == UpFlag.XOR) {
            val key = checkCode.toByte()
            for (i in body.indices) body[i] = (body[i].toInt() xor key.toInt()).toByte()
        }
        return UpPacket(serverId, userId, sid, cmdId, cmdIndex, checkCode, flag, body)
    }

    companion object {
        const val SID_SIZE = 32
        // serverId(4)+userId(4)+sid(32)+cmdId(4)+cmdIndex(4)+checkCode(4)+flag(1) = 53
        const val HEADER_SIZE = 4 + 4 + SID_SIZE + 4 + 4 + 4 + 1
        const val MAX_FRAME = 5 * 1024 * 1024      // NetBuff.MAX_MSG_SIZE
    }
}
