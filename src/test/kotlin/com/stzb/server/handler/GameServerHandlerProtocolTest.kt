package com.stzb.server.handler

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.FilePlayerRepository
import com.stzb.server.game.PlayerStateRepository
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.protocol.Cmd
import com.stzb.server.protocol.DownPacket
import com.stzb.server.protocol.UpFlag
import com.stzb.server.protocol.UpPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameServerHandlerProtocolTest {
    private val mapper = jacksonObjectMapper()
    private lateinit var repositoryRoot: Path

    @BeforeTest
    fun setUp() {
        repositoryRoot = Files.createTempDirectory("stzb-handler-protocol-")
        PlayerStateRepository.configure(FilePlayerRepository(repositoryRoot))
    }

    @AfterTest
    fun tearDown() {
        PlayerStateRepository.reset()
        repositoryRoot.toFile().deleteRecursively()
    }

    @Test
    fun `red dot telemetry returns recorded json null`() {
        val channel = newChannel()
        channel.writeInbound(upPacket(Cmd.SET_CLIENT_RED_DOT_DATA, """[0,"3295","7",0]"""))

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SET_CLIENT_RED_DOT_DATA, response.cmd)
        assertEquals("null", response.body.toString(Charsets.UTF_8))
        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `world scene request acknowledges before full scene notification`() {
        val channel = newChannel()
        channel.writeInbound(upPacket(Cmd.GET_WORLD_SCENCE_INFO, "[50,70,641,661,0,0]"))

        val acknowledgement = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.GET_WORLD_SCENCE_INFO, acknowledgement.cmd)
        assertEquals("null", acknowledgement.body.toString(Charsets.UTF_8))

        val notification = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SEND_WORLD_SCENCE_FULL_INFO, notification.cmd)
        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `short battle detail returns replay actions without inner zzz compression`() {
        val channel = newChannel()
        channel.writeInbound(upPacket(Cmd.BATTLE_REPORT_SHORT_DETAIL, "[0,0,0]"))

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.BATTLE_REPORT_SHORT_DETAIL, response.cmd)
        val report = mapper.readTree(response.body)[1]["report"].asText()
        assertFalse(report.startsWith("zzz"))
        assertTrue(report.split("#").any { it == "04" })
        assertTrue(report.split("#").any { it.startsWith("09") })
        channel.finishAndReleaseAll()
    }

    @Test
    fun `battle detail resolves reports for the authenticated session instead of packet user id`() {
        val foreignUserId = 987654
        val store = ClientBattleReportStore.global()
        val foreignDefault = store.getOrCreateDefault(foreignUserId)
        val foreignReport = store.record(
            ownerUserId = foreignUserId,
            wid = foreignDefault.wid,
            timeSec = foreignDefault.timeSec,
            result = foreignDefault.result,
        )
        val channel = newChannel()

        channel.writeInbound(
            upPacket(
                cmdId = Cmd.BATTLE_REPORT_DETAIL,
                json = "[${foreignReport.battleId},0,0]",
                userId = foreignUserId,
            ),
        )

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        val returnedBattleId = mapper.readTree(response.body)[1]["battle_id"].asInt()
        assertNotEquals(foreignReport.battleId, returnedBattleId)
        channel.finishAndReleaseAll()
    }

    private fun newChannel(): EmbeddedChannel =
        EmbeddedChannel(GameServerHandler()).also { channel ->
            ReferenceCountUtil.release(assertIs<ByteBuf>(channel.readOutbound<Any>()))
        }

    private fun upPacket(cmdId: Int, json: String, userId: Int = 10001): UpPacket {
        val cmdIndex = 1
        return UpPacket(
            serverId = 1001,
            userId = userId,
            sid = ByteArray(32),
            cmdId = cmdId,
            cmdIndex = cmdIndex,
            checkCode = (cmdIndex * 13) xor cmdId xor userId,
            flag = UpFlag.PLAIN,
            body = json.toByteArray(),
        )
    }
}
