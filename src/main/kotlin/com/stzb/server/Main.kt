package com.stzb.server

import com.stzb.server.handler.GameServerHandler
import com.stzb.server.protocol.DownFrameEncoder
import com.stzb.server.protocol.UpFrameDecoder
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import org.slf4j.LoggerFactory

/**
 * 率土之滨 私服 P0 协议骨架入口。
 * 监听 TCP, 装配 上行解码器 + 下行编码器 + 业务处理器。
 * 客户端 TcpNoDelay=true, 这里同样开启。
 */
object Main

private val log = LoggerFactory.getLogger(Main::class.java)

fun main(args: Array<String>) {
    val port = (System.getenv("STZB_PORT") ?: args.firstOrNull() ?: "59979").toInt()

    val boss = NioEventLoopGroup(1)
    val worker = NioEventLoopGroup()
    try {
        val b = ServerBootstrap()
            .group(boss, worker)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast("decoder", UpFrameDecoder())
                        .addLast("encoder", DownFrameEncoder())
                        .addLast("handler", GameServerHandler())
                }
            })

        val ch = b.bind(port).sync().channel()
        log.info("=== stzb-server (P0) 监听 :$port ===")
        ch.closeFuture().sync()
    } finally {
        boss.shutdownGracefully()
        worker.shutdownGracefully()
    }
}
