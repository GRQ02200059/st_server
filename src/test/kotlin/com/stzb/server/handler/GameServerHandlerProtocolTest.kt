package com.stzb.server.handler

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.FilePlayerRepository
import com.stzb.server.game.PlayerStateRepository
import com.stzb.server.game.WorldStateRepository
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.protocol.Cmd
import com.stzb.server.protocol.DownPacket
import com.stzb.server.protocol.GameServerConfig
import com.stzb.server.protocol.UpFlag
import com.stzb.server.protocol.UpPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
        GameServerHandler.resetRuntimeForTests()
        repositoryRoot = Files.createTempDirectory("stzb-handler-protocol-")
        PlayerStateRepository.configure(FilePlayerRepository(repositoryRoot))
        WorldStateRepository.configure(repositoryRoot)
    }

    @AfterTest
    fun tearDown() {
        GameServerHandler.resetRuntimeForTests()
        PlayerStateRepository.reset()
        WorldStateRepository.reset()
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
    fun `land defender queries return the canonical army id list`() {
        val channel = newChannel()

        channel.writeInbound(upPacket(4329, "[10012]"))
        val mapGuard = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(4329, mapGuard.cmd)
        assertEquals(UpFlag.XOR, mapGuard.dataType)
        assertEquals("""[10012,"101"]""", mapGuard.body.toString(Charsets.UTF_8))

        channel.writeInbound(upPacket(4331, "[10001]"))
        val detail = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(4331, detail.cmd)
        assertEquals(UpFlag.XOR, detail.dataType)
        assertEquals("""[10001,"203"]""", detail.body.toString(Charsets.UTF_8))
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

    @Test
    fun `different platform identities receive different persistent player ids`() {
        val alice = newChannel()
        val bob = newChannel()

        val aliceId = platformLogin(alice, "alice")
        val bobId = platformLogin(bob, "bob")

        assertNotEquals(aliceId, bobId)
        assertEquals(aliceId, alice.attr(GameServerHandler.SESSION).get()?.playerId)
        assertEquals(bobId, bob.attr(GameServerHandler.SESSION).get()?.playerId)
        alice.finishAndReleaseAll()
        bob.finishAndReleaseAll()
    }

    @Test
    fun `new platform login invalidates and closes the previous account channel`() {
        val oldChannel = newChannel()
        val newChannel = newChannel()
        val playerId = platformLogin(oldChannel, "alice")

        assertEquals(playerId, platformLogin(newChannel, "alice"))

        val invalid = assertIs<DownPacket>(oldChannel.readOutbound<Any>())
        assertEquals(Cmd.SYS_SID_INVALID, invalid.cmd)
        assertFalse(oldChannel.isOpen)
        assertTrue(newChannel.isOpen)
        newChannel.finishAndReleaseAll()
    }

    @Test
    fun `successful pve claim refreshes the world scene for another online account`() {
        val alice = newChannel()
        val bob = newChannel()
        val aliceId = platformLogin(alice, "alice")
        platformLogin(bob, "bob")
        val aliceSession = alice.attr(GameServerHandler.SESSION).get() ?: error("missing Alice session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(aliceSession.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val hero = state.addHero(100021).apply {
            troops = 10_000
            level = 50
        }
        state.saveTeam(listOf(hero.heroUid))
        PlayerStateRepository.save(state)
        val targetWid = 10_011

        alice.writeInbound(
            upPacket(
                cmdId = Cmd.ARMY_BATTLE,
                json = "[$targetWid,${state.primaryArmyId()}]",
                userId = aliceId,
            ),
        )
        drainOutbound(alice)
        Thread.sleep(3_100)
        alice.advanceTimeBy(4, TimeUnit.SECONDS)
        alice.runScheduledPendingTasks()

        val bobScene = drainOutbound(bob)
            .filterIsInstance<DownPacket>()
            .lastOrNull { it.cmd == Cmd.SEND_WORLD_SCENCE_FULL_INFO }
            ?: error("Bob should receive a refreshed world scene after Alice claims land")
        val cities = mapper.readTree(bobScene.body)[14]
        assertEquals(aliceId, cities[targetWid.toString()]["0"][2].asInt())
        assertEquals(state.cityWid, cities[targetWid.toString()]["0"][7].asInt())
        alice.finishAndReleaseAll()
        bob.finishAndReleaseAll()
    }

    @Test
    fun `hero advance consumes same name material and notifies advance count`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "alice")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("session should exist")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        assertEquals(playerId, state.userId)
        val target = state.addHero(100017, nowSec = 1_700_000_000)
        val material = state.ensureAdvanceMaterials(nowSec = 1_700_000_000)
            .single { it.heroId == target.heroId }
        PlayerStateRepository.save(state)

        channel.writeInbound(
            upPacket(
                Cmd.HERO_ADVANCE,
                """[${target.heroUid},[${material.heroUid}]]""",
                userId = session.userId,
            ),
        )

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.HERO_ADVANCE, response.cmd)
        assertEquals("[]", response.body.toString(Charsets.UTF_8))

        val notify = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, notify.cmd)
        val changes = mapper.readTree(notify.body)
        assertEquals(listOf(0, target.heroUid, 29, 5), changes[0][2].map { it.asInt() })
        assertEquals(3, changes[1][0].asInt())
        assertEquals(material.heroUid, changes[1][2].asInt())
        assertEquals(5, state.hero(target.heroUid)?.advanceNum)
        assertNull(state.hero(material.heroUid))
        channel.finishAndReleaseAll()
    }

    @Test
    fun `login response contains the server generated library inventory`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "alice")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")

        channel.writeInbound(
            upPacket(
                cmdId = Cmd.SYS_LOGIN,
                json = """["passport","token",$playerId]""",
                userId = session.userId,
            ),
        )

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_LOGIN, response.cmd)
        val tables = mapper.readTree(response.body)[4][0]
            .drop(1)
            .associateBy { it[0].asText() }
        val gearRows = tables.getValue("Tb_gear")[1]
        val itemRows = tables.getValue("Tb_user_item")[1]

        assertEquals(111, itemRows.size())
        assertTrue(itemRows.all { row -> row[4].asInt() == 5 && row[5].asInt() == 0 })
        assertEquals(50, gearRows.count { row -> row[0].asInt() in 840_100_001..840_100_050 })
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

    private fun platformLogin(channel: EmbeddedChannel, sdkUid: String): Int {
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        channel.writeInbound(
            upPacket(
                cmdId = Cmd.SYS_PLATFORM_LOGIN_CHECK,
                json = """["{\"sdkuid\":\"$sdkUid\"}",0,"",0]""",
                userId = session.wireUserId,
            ),
        )
        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_PLATFORM_LOGIN_CHECK, response.cmd)
        return mapper.readTree(response.body)[3].asInt()
    }

    private fun drainOutbound(channel: EmbeddedChannel): List<Any> =
        generateSequence { channel.readOutbound<Any>() }.toList()
}
