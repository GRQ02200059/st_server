package com.stzb.server.protocol

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * 下行帧编码器。
 *
 * 普通包线格式: [4B len][cmd(4)][hashCode(4)][dataType(1)][body]
 *   len = cmd 起到 body 末尾的总字节数。
 *   dataType=5: body 用固定 0x98 XOR;=3: zlib(前置 4B 原始长度);=1: 明文。
 *
 * 98888/96666 特殊二进制包不走这里, 由 [buildNotifySid] 等直接构造 ByteBuf 写出。
 */
class DownFrameEncoder : MessageToByteEncoder<DownPacket>() {

    override fun encode(ctx: ChannelHandlerContext, msg: DownPacket, out: ByteBuf) {
        val payload: ByteArray = when (msg.dataType) {
            DownType.XOR -> msg.body.copyOf().also {
                for (i in it.indices) it[i] = (it[i].toInt() xor DownType.XOR_KEY).toByte()
            }
            DownType.ZLIB -> zlib(msg.body)
            else -> msg.body
        }
        // len = cmd(4) + hashCode(4) + dataType(1) + payload
        val bodyPart = 4 + 4 + 1 + payload.size
        out.writeInt(bodyPart)
        out.writeInt(msg.cmd)
        out.writeInt(msg.hashCode)
        out.writeByte(msg.dataType.toInt())
        out.writeBytes(payload)
    }

    /** zlib 压缩, 返回 [4B 原始长度大端][deflate 数据], 匹配客户端 DataType==3 分支。 */
    private fun zlib(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(raw); deflater.finish()
        val buf = ByteArray(4096)
        val bos = ByteArrayOutputStream()
        // 前置 4B 原始长度 (大端)
        bos.write((raw.size ushr 24) and 0xFF); bos.write((raw.size ushr 16) and 0xFF)
        bos.write((raw.size ushr 8) and 0xFF); bos.write(raw.size and 0xFF)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf); bos.write(buf, 0, n)
        }
        deflater.end()
        return bos.toByteArray()
    }
}
