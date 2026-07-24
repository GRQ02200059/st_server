package com.stzb.server.protocol

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel

/**
 * 特殊二进制系统包构造 (不走 DownFrameEncoder, 直接写 ByteBuf)。
 */
object SysPackets {

    /**
     * 98888 握手包: 服务端下发 userId/cmdIndex/sid, 客户端据此填充后续上行帧头。
     * 线格式: [4B len][cmd=98888][hashCode][userId][cmdIndex][sid 32B][param]
     * 见 DotnetBaseSocket.ProcessBytesReceived case 98888。
     */
    fun writeNotifySid(ch: Channel, userId: Int, cmdIndex: Int, sid: ByteArray, param: Int = 0) {
        require(sid.size == 32) { "sid 必须 32 字节" }
        val buf: ByteBuf = ch.alloc().buffer()
        // len = cmd(4)+hashCode(4)+userId(4)+cmdIndex(4)+sid(32)+param(4) = 52
        val len = 4 + 4 + 4 + 4 + 32 + 4
        buf.writeInt(len)
        buf.writeInt(Cmd.SYS_NOTIFY_SID)
        buf.writeInt(0)              // hashCode: 握手包无需 ACK
        buf.writeInt(userId)
        buf.writeInt(cmdIndex)
        buf.writeBytes(sid)
        buf.writeInt(param)
        ch.writeAndFlush(buf)
    }

    /**
     * 极简 "空包" (PackageReceivedComplex): 线格式恰为 [4B len=8][cmd][hashCode], 之后无任何字节。
     *
     * 客户端 ProcessBytesReceived 读完 cmd/hashCode 后 netBuff.IsEnd()==true, 走
     * PackageReceivedComplex 分支 —— 不做反序列化, 不触发 DevMode 抛异常; 且每收到任意下行包
     * 都会 ResetLastSendTime()->0, 从而喂饱客户端的 3s recv-timeout 看门狗。
     *
     * 注意: 普通 DownPacket 会多写 1B dataType, 会被当成 GameData 去反序列化空 body, 不能用作喂狗包。
     * hashCode 传 0 可避免客户端回 90009 ACK 噪声。
     */
    fun writeComplex(ch: Channel, cmd: Int, hashCode: Int = 0) {
        val buf: ByteBuf = ch.alloc().buffer(12)
        buf.writeInt(4 + 4)          // len = cmd(4)+hashCode(4)
        buf.writeInt(cmd)
        buf.writeInt(hashCode)
        ch.writeAndFlush(buf)
    }
}
