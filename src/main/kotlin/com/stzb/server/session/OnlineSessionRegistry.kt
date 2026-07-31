package com.stzb.server.session

import io.netty.channel.Channel
import java.util.concurrent.ConcurrentHashMap

class OnlineSessionRegistry {
    private val channels = ConcurrentHashMap<String, Channel>()

    fun bind(accountKey: String, channel: Channel): Channel? =
        channels.put(accountKey, channel)

    fun remove(accountKey: String, channel: Channel) {
        channels.remove(accountKey, channel)
    }

    fun current(accountKey: String): Channel? = channels[accountKey]

    fun allChannels(): List<Channel> =
        channels.values.distinct()
}
