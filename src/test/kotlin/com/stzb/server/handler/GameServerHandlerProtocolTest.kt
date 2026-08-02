package com.stzb.server.handler

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.CardBorderCatalog
import com.stzb.server.game.FilePlayerRepository
import com.stzb.server.game.FileUnionRepository
import com.stzb.server.game.GameResponses
import com.stzb.server.game.InventoryCatalog
import com.stzb.server.game.PlayerRepository
import com.stzb.server.game.RevenueCollection
import com.stzb.server.game.RevenueGift
import com.stzb.server.game.RevenueService
import com.stzb.server.game.PlayerState
import com.stzb.server.game.PlayerStateRepository
import com.stzb.server.game.UnionStateRepository
import com.stzb.server.game.WorldStateRepository
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.protocol.Cmd
import com.stzb.server.protocol.DownPacket
import com.stzb.server.protocol.DownType
import com.stzb.server.protocol.GameServerConfig
import com.stzb.server.protocol.UpFlag
import com.stzb.server.protocol.UpPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
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
        UnionStateRepository.configure(repositoryRoot)
        WorldStateRepository.configure(repositoryRoot)
    }

    @AfterTest
    fun tearDown() {
        GameServerHandler.resetRuntimeForTests()
        PlayerStateRepository.reset()
        UnionStateRepository.reset()
        WorldStateRepository.reset()
        repositoryRoot.toFile().deleteRecursively()
    }

    @Test
    fun `revenue success persists grants and emits db update before scalar response`() {
        val channel = newChannel()
        val accountKey = "revenue-success"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Revenue Success User",
        )
        state.resources.money = 0
        state.resources.moneyAccumulated = 0
        state.addHero(heroId = 100_101, nowSec = 10)
        UnionStateRepository.create(state, "Revenue Success Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_020, nowSec = 1))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val tracking = CountingPlayerRepository(FilePlayerRepository(repositoryRoot))
        PlayerStateRepository.configure(tracking)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        GameServerHandler.setRevenueEpochSecondsForTests(100)

        channel.writeInbound(upPacket(Cmd.REVENUE, "[0]", userId = 987_654))

        val ordinaryNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, ordinaryNotify.cmd)
        assertEquals(DownType.PLAIN, ordinaryNotify.dataType)
        val ordinaryState = requireNotNull(PlayerStateRepository.findExisting(accountKey))
        assertEquals(
            mapper.readTree(GameResponses.ordinaryRevenueUpdateNotify(ordinaryState)),
            mapper.readTree(ordinaryNotify.body),
        )
        val ordinaryResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.REVENUE, ordinaryResponse.cmd)
        assertEquals(DownType.PLAIN, ordinaryResponse.dataType)
        assertEquals("0", ordinaryResponse.body.toString(Charsets.UTF_8))
        assertNull(channel.readOutbound<Any>())
        assertFalse(ordinaryNotify.body.toString(Charsets.UTF_8).contains("987654"))

        channel.writeInbound(upPacket(Cmd.REVENUE_DOUBLE, "[0]", userId = 987_654))

        val doubleNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, doubleNotify.cmd)
        assertEquals(DownType.PLAIN, doubleNotify.dataType)
        val doubleState = requireNotNull(PlayerStateRepository.findExisting(accountKey))
        assertEquals(
            mapper.readTree(GameResponses.doubleRevenueUpdateNotify(doubleState)),
            mapper.readTree(doubleNotify.body),
        )
        val doubleResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.REVENUE_DOUBLE, doubleResponse.cmd)
        assertEquals(DownType.PLAIN, doubleResponse.dataType)
        assertEquals("6500", doubleResponse.body.toString(Charsets.UTF_8))
        assertNull(channel.readOutbound<Any>())
        assertFalse(doubleNotify.body.toString(Charsets.UTF_8).contains("987654"))
        assertEquals(2, tracking.saveCount)

        val persisted = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        assertEquals(RevenueService.REVENUE_AMOUNT * 2, persisted.resources.money)
        assertEquals(RevenueService.REVENUE_AMOUNT * 2, persisted.resources.moneyAccumulated)
        assertEquals(listOf(RevenueCollection(100, RevenueService.REVENUE_AMOUNT)), persisted.revenue.collections)
        assertEquals(listOf(RevenueGift(RevenueService.REVENUE_AMOUNT, claimed = true)), persisted.revenue.gifts)
        assertEquals(
            playerBefore.copy(resources = persisted.resources, revenue = persisted.revenue),
            persisted,
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `ordinary revenue cooldown and invalid requests return only zero without save or mutation`() {
        val channel = newChannel()
        val accountKey = "revenue-rejections"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Revenue Rejection User",
        )
        state.resources.money = 6500
        state.resources.moneyAccumulated = 6500
        state.revenue.collections += RevenueCollection(100, 6500)
        state.revenue.gifts += RevenueGift(6500)
        state.revenue.revenueTime = 100
        state.revenue.nextRefreshTime = 100 + RevenueService.RESET_WINDOW_SECONDS
        PlayerStateRepository.save(state)
        val before = state.toSnapshot()
        val tracking = CountingPlayerRepository(FilePlayerRepository(repositoryRoot))
        PlayerStateRepository.configure(tracking)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        GameServerHandler.setRevenueEpochSecondsForTests(101)
        val requests = listOf(
            "[0]",
            "[1]",
            "not-json",
            "[0] []",
            "{}",
            "0",
            "[]",
            "[0,0]",
            """["0"]""",
            "[0.0]",
        )

        requests.forEach { request ->
            channel.writeInbound(upPacket(Cmd.REVENUE, request, userId = state.userId))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.REVENUE, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("0", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        assertEquals(0, tracking.saveCount)
        assertEquals(before, requireNotNull(PlayerStateRepository.findExisting(accountKey)).toSnapshot())
        assertEquals(
            before,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        channel.finishAndReleaseAll()
    }

    @Test
    fun `double revenue replay and invalid requests return only zero without save or mutation`() {
        val channel = newChannel()
        val accountKey = "double-revenue-rejections"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Double Revenue Rejection User",
        )
        state.resources.money = 6500
        state.resources.moneyAccumulated = 6500
        state.revenue.gifts += RevenueGift(6500, claimed = true)
        PlayerStateRepository.save(state)
        val before = state.toSnapshot()
        val tracking = CountingPlayerRepository(FilePlayerRepository(repositoryRoot))
        PlayerStateRepository.configure(tracking)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val requests = listOf(
            "[0]",
            "[1]",
            "[-1]",
            "not-json",
            "[0] []",
            "{}",
            "0",
            "[]",
            "[0,0]",
            """["0"]""",
            "[0.0]",
            "[2147483648]",
        )

        requests.forEach { request ->
            channel.writeInbound(upPacket(Cmd.REVENUE_DOUBLE, request, userId = state.userId))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.REVENUE_DOUBLE, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("0", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        assertEquals(0, tracking.saveCount)
        assertEquals(before, requireNotNull(PlayerStateRepository.findExisting(accountKey)).toSnapshot())
        assertEquals(
            before,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        channel.finishAndReleaseAll()
    }

    @Test
    fun `revenue commands reject unbound and missing accounts without creating state`() {
        val channel = newChannel()
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val tracking = CountingPlayerRepository(FilePlayerRepository(repositoryRoot))
        PlayerStateRepository.configure(tracking)

        listOf(Cmd.REVENUE, Cmd.REVENUE_DOUBLE).forEach { cmd ->
            channel.writeInbound(upPacket(cmd, "[0]", userId = session.wireUserId))
            val response = assertIs<DownPacket>(channel.readOutbound<Any>())
            assertEquals(cmd, response.cmd)
            assertEquals("0", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>())
        }
        assertNull(FilePlayerRepository(repositoryRoot).findByAccount("legacy-user-${session.wireUserId}"))

        val missingAccount = "missing-revenue-account"
        session.bind(missingAccount, 77_778)
        listOf(Cmd.REVENUE, Cmd.REVENUE_DOUBLE).forEach { cmd ->
            channel.writeInbound(upPacket(cmd, "[0]", userId = 987_654))
            val response = assertIs<DownPacket>(channel.readOutbound<Any>())
            assertEquals(cmd, response.cmd)
            assertEquals("0", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>())
        }
        assertEquals(0, tracking.saveCount)
        assertNull(FilePlayerRepository(repositoryRoot).findByAccount(missingAccount))
        channel.finishAndReleaseAll()
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
    fun `log acknowledgements ignore arbitrary payloads without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "log-ack-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Log Ack Snapshot User",
        )
        UnionStateRepository.create(state, "Log Ack Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val expectedBodies = mapOf(
            Cmd.LOG_FPS to "null",
            Cmd.SEND_ACSDK_CHEAT_INFO to "true",
            Cmd.USER_CLOSE_UI to "null",
            Cmd.USER_OPEN_UI to "null",
            Cmd.LOG_MUSIC_OPEN to "true",
            Cmd.RESFILE_LOG_HUB_RECORD to "null",
            Cmd.DAILY_REPORT_LOG to "null",
            Cmd.HELP_GUIDE_TIPS_LOG to "200",
            Cmd.UPDATE_GUIDE_RECORD to "200",
        )
        val syntheticPayloads = listOf(
            """["synthetic-alpha",{"opaque":17}]""",
            """{"synthetic":"beta","values":[false,42]}""",
        )

        expectedBodies.forEach { (cmd, expectedBody) ->
            syntheticPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "cmd=$cmd")
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
                assertNull(channel.readOutbound<Any>(), "cmd=$cmd emitted an extra packet")
            }
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `telemetry null acknowledgements are body blind and repository free`() {
        val channel = newChannel()
        val accountKey = "telemetry-null-ack"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Telemetry Null Ack User",
        )
        state.resources.money = 7_654_321
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Telemetry Null Ack Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_021, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val commands = listOf(2_524, 3_402, 3_604, 4_019, 5_202, 5_242, 8_040)
        val requestBodies = listOf(
            "null",
            "[]",
            "[17]",
            """[17,{"opaque":42},false]""",
            "{}",
            """{"synthetic":"private-marker","values":[false,42]}""",
            "0",
            "true",
            """"synthetic private-marker"""",
            "not-json synthetic private-marker",
            "[] {}",
        )
        val expectedBody = "null".toByteArray()

        commands.forEach { commandId ->
            requestBodies.forEach { request ->
                channel.writeInbound(upPacket(commandId, request, userId = state.userId + 10_000))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$commandId request=$request",
                )
                assertEquals(commandId, response.cmd, "cmd=$commandId request=$request")
                assertEquals(DownType.PLAIN, response.dataType, "cmd=$commandId request=$request")
                assertTrue(
                    expectedBody.contentEquals(response.body),
                    "cmd=$commandId request=$request did not emit exact wire null",
                )
                assertTrue(
                    mapper.readTree(response.body).isNull,
                    "cmd=$commandId request=$request did not emit JSON null",
                )
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$commandId request=$request emitted an extra packet",
                )
            }
        }

        assertEquals(playerBefore, state.toSnapshot())
        assertTrue(
            persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
            "persisted player bytes changed",
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `external identity and channel rejections are exact body blind and repository free`() {
        val channel = newChannel()
        val accountKey = "external-identity-rejections"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "External Rejection User",
        )
        state.resources.money = 8_765_432
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "External Rejection Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_022, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val expectedBodies = linkedMapOf(
            331 to """"false"""",
            332 to """"false"""",
            336 to "2",
            9_010 to "2",
            29_003 to "[0]",
            40_006 to "null",
            40_007 to "{}",
            40_014 to "{}",
        )
        val requestBodies = listOf(
            "null",
            "false",
            "2",
            "[0]",
            "{}",
            """{"synthetic":"credential-canary","opaque":[17,false]}""",
            """"synthetic-sdk-canary"""",
            "not-json synthetic-credential-canary",
            "[] {}",
        )

        assertAll(
            "external identity and channel commands",
            expectedBodies.map { (commandId, expectedBody) ->
                Executable {
                    requestBodies.forEach { request ->
                        channel.writeInbound(upPacket(commandId, request, userId = state.userId + 10_000))

                        val response = assertIs<DownPacket>(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId request=$request",
                        )
                        assertEquals(commandId, response.cmd, "cmd=$commandId request=$request")
                        assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, response.cmd, "cmd=$commandId request=$request")
                        assertEquals(DownType.PLAIN, response.dataType, "cmd=$commandId request=$request")
                        assertTrue(
                            expectedBody.toByteArray().contentEquals(response.body),
                            "cmd=$commandId request=$request emitted wrong wire bytes",
                        )
                        val parsed = mapper.readTree(response.body)
                        when (commandId) {
                            331, 332 -> {
                                assertTrue(parsed.isTextual, "cmd=$commandId must emit a JSON string")
                                assertFalse(parsed.isBoolean, "cmd=$commandId must not emit a JSON boolean")
                                assertEquals("false", parsed.textValue(), "cmd=$commandId")
                            }

                            336, 9_010 -> {
                                assertTrue(parsed.isInt, "cmd=$commandId must emit a JSON integer")
                                assertEquals(2, parsed.intValue(), "cmd=$commandId")
                            }

                            29_003 -> {
                                assertTrue(parsed.isArray, "cmd=$commandId must emit a JSON array")
                                assertEquals(1, parsed.size(), "cmd=$commandId")
                                assertTrue(parsed[0].isInt, "cmd=$commandId slot 0 must be a JSON integer")
                                assertEquals(0, parsed[0].intValue(), "cmd=$commandId")
                            }

                            40_006 -> assertTrue(parsed.isNull, "cmd=$commandId must emit JSON null")
                            40_007, 40_014 -> {
                                assertTrue(parsed.isObject, "cmd=$commandId must emit a JSON object")
                                assertTrue(parsed.isEmpty, "cmd=$commandId must emit an empty JSON object")
                            }
                        }
                        assertNull(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId request=$request emitted an extra packet",
                        )
                    }
                }
            },
        )

        assertEquals(playerBefore, state.toSnapshot())
        assertTrue(
            persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
            "persisted player bytes changed",
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `read only empty projections are exact body blind and repository free`() {
        val channel = newChannel()
        val accountKey = "read-only-empty-projections"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Read Only Empty Projection User",
        )
        state.resources.money = 6_543_210
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Read Only Empty Projection Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_023, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val expectedBodies = linkedMapOf(
            3_739 to "[0]",
            4_092 to "[]",
            4_112 to "[]",
            4_114 to "[]",
            5_096 to "[]",
            5_218 to "{}",
        )
        val requestBodies = listOf(
            "not-json opaque text",
            "[] {}",
            "null",
            "17",
            "false",
            """"opaque string"""",
            "{}",
            "[]",
            """{"opaque":[17,false]}""",
            """[17,{"opaque":42},false]""",
        )

        assertAll(
            "read only empty projection commands",
            expectedBodies.map { (commandId, expectedBody) ->
                Executable {
                    requestBodies.forEach { request ->
                        channel.writeInbound(upPacket(commandId, request, userId = state.userId + 10_000))

                        val response = assertIs<DownPacket>(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId request=$request",
                        )
                        assertEquals(commandId, response.cmd, "cmd=$commandId request=$request")
                        assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, response.cmd, "cmd=$commandId request=$request")
                        assertEquals(DownType.PLAIN, response.dataType, "cmd=$commandId request=$request")
                        assertTrue(
                            expectedBody.toByteArray().contentEquals(response.body),
                            "cmd=$commandId request=$request emitted wrong wire bytes",
                        )
                        val parsed = mapper.readTree(response.body)
                        when (commandId) {
                            3_739 -> {
                                assertTrue(parsed.isArray, "cmd=$commandId must emit a JSON array")
                                assertEquals(1, parsed.size(), "cmd=$commandId")
                                assertTrue(parsed[0].isInt, "cmd=$commandId slot 0 must be a JSON integer")
                                assertEquals(0, parsed[0].intValue(), "cmd=$commandId")
                            }

                            4_092, 4_112, 4_114, 5_096 -> {
                                assertTrue(parsed.isArray, "cmd=$commandId must emit a JSON array")
                                assertTrue(parsed.isEmpty, "cmd=$commandId must emit an empty JSON array")
                            }

                            5_218 -> {
                                assertTrue(parsed.isObject, "cmd=$commandId must emit a JSON object")
                                assertTrue(parsed.isEmpty, "cmd=$commandId must emit an empty JSON object")
                            }
                        }
                        assertNull(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId request=$request emitted an extra packet",
                        )
                    }
                }
            },
        )

        assertEquals(playerBefore, state.toSnapshot())
        assertTrue(
            persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
            "persisted player bytes changed",
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `strict empty query projections reject every other shape and remain repository free`() {
        val accountKey = "strict-empty-query-projections"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Strict Empty Query User",
        )
        state.resources.money = 4_321_000
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Strict Empty Query Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_025, nowSec = 1))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val expectedBodies = linkedMapOf(
            3_845 to "[]",
            4_102 to "{}",
            6_089 to "[0,0]",
        )
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "number scalar" to "17",
            "string scalar" to """"synthetic-private-canary"""",
            "boolean scalar" to "false",
            "object" to """{"synthetic":"private-canary"}""",
            "nested array" to "[[]]",
            "non-empty array" to "[0]",
            "malformed JSON" to "[",
            "empty text" to "",
            "trailing token" to "[] {}",
        )

        assertAll(
            "strict empty query projection commands",
            expectedBodies.map { (commandId, expectedBody) ->
                Executable {
                    val channel = newChannel()
                    try {
                        invalidRequests.forEach { (case, request) ->
                            channel.writeInbound(
                                upPacket(commandId, request, userId = state.userId + 10_000),
                            )
                            assertNull(
                                channel.readOutbound<Any>(),
                                "cmd=$commandId case=$case emitted an outbound packet",
                            )
                        }

                        channel.writeInbound(
                            upPacket(commandId, "[]", userId = state.userId + 10_000),
                        )

                        val packet = assertIs<DownPacket>(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId valid [] request emitted no response",
                        )
                        assertEquals(commandId, packet.cmd, "cmd=$commandId")
                        assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd, "cmd=$commandId")
                        assertEquals(DownType.PLAIN, packet.dataType, "cmd=$commandId")
                        assertTrue(
                            expectedBody.toByteArray().contentEquals(packet.body),
                            "cmd=$commandId emitted wrong wire bytes",
                        )
                        val parsed = mapper.readTree(packet.body)
                        when (commandId) {
                            3_845 -> {
                                assertTrue(parsed.isArray, "cmd=$commandId")
                                assertTrue(parsed.isEmpty, "cmd=$commandId")
                            }

                            4_102 -> {
                                assertTrue(parsed.isObject, "cmd=$commandId")
                                assertTrue(parsed.isEmpty, "cmd=$commandId")
                            }

                            6_089 -> {
                                assertTrue(parsed.isArray, "cmd=$commandId")
                                assertEquals(2, parsed.size(), "cmd=$commandId")
                                assertTrue(parsed[0].isIntegralNumber, "cmd=$commandId slot=0")
                                assertEquals(0, parsed[0].asInt(), "cmd=$commandId slot=0")
                                assertTrue(parsed[1].isIntegralNumber, "cmd=$commandId slot=1")
                                assertEquals(0, parsed[1].asInt(), "cmd=$commandId slot=1")
                            }
                        }
                        assertNull(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId valid [] request emitted an extra packet",
                        )
                    } finally {
                        channel.finishAndReleaseAll()
                    }
                }
            },
        )

        assertEquals(playerBefore, state.toSnapshot())
        assertTrue(
            persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
            "persisted player bytes changed",
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
    }

    @Test
    fun `union letter query accepts only modes zero and one and remains repository free`() {
        val commandId = 9_015
        val accountKey = "union-letter-query-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Union Letter Query User",
        )
        state.resources.money = 9_015_000
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Union Letter Query Union", nowSec = 5)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_029, nowSec = 5))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "empty text" to "",
            "malformed JSON" to "[",
            "number scalar" to "0",
            "string scalar" to """"synthetic-letter-canary"""",
            "boolean scalar" to "false",
            "top-level object" to """{"synthetic":"letter-canary"}""",
            "empty array" to "[]",
            "nested array" to "[[]]",
            "more than one slot" to "[0,1]",
            "null slot" to "[null]",
            "string slot" to """["synthetic-letter-canary"]""",
            "boolean slot" to "[true]",
            "object slot" to """[{"synthetic":"letter-canary"}]""",
            "mode below range" to "[-1]",
            "mode above range" to "[2]",
            "positive Int32 overflow" to "[2147483648]",
            "negative Int32 overflow" to "[-2147483649]",
            "floating point" to "[0.0]",
            "exponential" to "[1e0]",
            "trailing token" to "[0] []",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        fun validModeExecutable(mode: Int) = Executable {
            val channel = newChannel()
            try {
                channel.writeInbound(
                    upPacket(commandId, "[$mode]", userId = state.userId + 10_000),
                )

                val packet = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "mode=$mode emitted no response",
                )
                assertEquals(commandId, packet.cmd, "mode=$mode")
                assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd, "mode=$mode")
                assertEquals(DownType.PLAIN, packet.dataType, "mode=$mode")
                assertTrue(
                    """["",""]""".toByteArray().contentEquals(packet.body),
                    "mode=$mode emitted wrong wire bytes",
                )
                val parsed = mapper.readTree(packet.body)
                assertTrue(parsed.isArray, "mode=$mode response is not an array")
                assertEquals(2, parsed.size(), "mode=$mode response arity")
                assertTrue(parsed[0].isTextual, "mode=$mode slot=0 is not a JSON string")
                assertEquals("", parsed[0].textValue(), "mode=$mode slot=0")
                assertTrue(parsed[1].isTextual, "mode=$mode slot=1 is not a JSON string")
                assertEquals("", parsed[1].textValue(), "mode=$mode slot=1")
                assertNull(
                    channel.readOutbound<Any>(),
                    "mode=$mode emitted an extra packet",
                )
                assertStateUnchanged("mode=$mode")
            } finally {
                channel.finishAndReleaseAll()
            }
        }

        assertAll(
            "union letter query",
            Executable {
                val channel = newChannel()
                try {
                    invalidRequests.forEach { (case, request) ->
                        channel.writeInbound(
                            upPacket(commandId, request, userId = state.userId + 10_000),
                        )
                        assertNull(
                            channel.readOutbound<Any>(),
                            "case=$case emitted an outbound packet",
                        )
                    }
                    assertStateUnchanged("invalid requests")
                } finally {
                    channel.finishAndReleaseAll()
                }
            },
            validModeExecutable(0),
            validModeExecutable(1),
        )
    }

    @Test
    fun `summer farm record queries enforce exact keys and remain repository free`() {
        val accountKey = "summer-farm-record-query-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Summer Farm Record Query User",
        )
        state.resources.money = 5_120_512
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Summer Farm Record Query Union", nowSec = 4)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_028, nowSec = 4))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val commands = listOf(5_120, 5_121)
        val pairedCommands = mapOf(
            5_120 to 5_121,
            5_121 to 5_120,
        )
        val validRequests = listOf(
            "[0]",
            """["synthetic-role-key"]""",
            "[-9223372036854775808]",
            "[9223372036854775807]",
        )
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "number scalar" to "5120",
            "string scalar" to """"synthetic-record-canary"""",
            "boolean scalar" to "false",
            "top-level object" to """{"synthetic":"record-canary"}""",
            "empty text" to "",
            "malformed JSON" to "[",
            "empty array" to "[]",
            "nested array" to "[[]]",
            "more than one slot" to "[0,1]",
            "null slot" to "[null]",
            "boolean slot" to "[true]",
            "object slot" to """[{"synthetic":"record-canary"}]""",
            "empty string slot" to """[""]""",
            "floating point" to "[17.0]",
            "exponential" to "[1e3]",
            "positive out of range" to "[9223372036854775808]",
            "negative out of range" to "[-9223372036854775809]",
            "trailing token" to "[0] []",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val invalidRequestExecutables = commands.map { commandId ->
            Executable {
                val channel = newChannel()
                try {
                    invalidRequests.forEach { (case, request) ->
                        channel.writeInbound(
                            upPacket(commandId, request, userId = state.userId + 10_000),
                        )
                        assertNull(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId case=$case emitted an outbound packet",
                        )
                    }
                    assertStateUnchanged("cmd=$commandId invalid requests")
                } finally {
                    channel.finishAndReleaseAll()
                }
            }
        }
        val validRequestExecutables = commands.flatMap { commandId ->
            validRequests.map { request ->
                Executable {
                    val channel = newChannel()
                    try {
                        channel.writeInbound(
                            upPacket(commandId, request, userId = state.userId + 10_000),
                        )

                        val packet = assertIs<DownPacket>(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId valid request=$request emitted no response",
                        )
                        assertEquals(commandId, packet.cmd, "cmd=$commandId request=$request")
                        assertNotEquals(
                            Cmd.SYS_NOTIFY_DB_UPDATE,
                            packet.cmd,
                            "cmd=$commandId request=$request",
                        )
                        assertNotEquals(
                            pairedCommands.getValue(commandId),
                            packet.cmd,
                            "cmd=$commandId request=$request emitted paired command",
                        )
                        assertEquals(
                            DownType.PLAIN,
                            packet.dataType,
                            "cmd=$commandId request=$request",
                        )
                        assertTrue(
                            "[]".toByteArray().contentEquals(packet.body),
                            "cmd=$commandId request=$request emitted wrong wire bytes",
                        )
                        val parsed = mapper.readTree(packet.body)
                        assertTrue(parsed.isArray, "cmd=$commandId request=$request")
                        assertTrue(parsed.isEmpty, "cmd=$commandId request=$request")
                        assertNull(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId request=$request emitted an extra or paired packet",
                        )
                        assertStateUnchanged("cmd=$commandId valid request=$request")
                    } finally {
                        channel.finishAndReleaseAll()
                    }
                }
            }
        }

        assertAll(
            "summer farm record query commands",
            invalidRequestExecutables + validRequestExecutables,
        )
    }

    @Test
    fun `summer farm user list echoes valid channels and rejects every other shape repository free`() {
        val commandId = 5_109
        val accountKey = "summer-farm-user-list-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Summer Farm User List User",
        )
        state.resources.money = 5_109_003
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Summer Farm User List Union", nowSec = 5)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_029, nowSec = 5))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "empty text" to "",
            "malformed JSON" to "[",
            "number scalar" to "1",
            "string scalar" to """"synthetic-channel-canary"""",
            "boolean scalar" to "true",
            "top-level object" to """{"synthetic":"channel-canary"}""",
            "empty array" to "[]",
            "nested array" to "[[1]]",
            "extra slot" to "[1,2]",
            "null slot" to "[null]",
            "string slot" to """["1"]""",
            "boolean slot" to "[false]",
            "object slot" to """[{"synthetic":"channel-canary"}]""",
            "array slot" to "[[]]",
            "channel zero" to "[0]",
            "channel four" to "[4]",
            "negative channel" to "[-1]",
            "positive Int32 overflow" to "[2147483648]",
            "negative Int32 overflow" to "[-2147483649]",
            "floating point" to "[1.0]",
            "exponential" to "[1e0]",
            "trailing token" to "[1] []",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val invalidRequestsExecutable = Executable {
            val channel = newChannel()
            try {
                invalidRequests.forEach { (case, request) ->
                    channel.writeInbound(
                        upPacket(commandId, request, userId = state.userId + 10_000),
                    )
                    assertNull(
                        channel.readOutbound<Any>(),
                        "case=$case emitted an outbound packet",
                    )
                }
                assertStateUnchanged("invalid requests")
            } finally {
                channel.finishAndReleaseAll()
            }
        }
        val validChannelExecutables = (1..3).map { requestedChannel ->
            Executable {
                val channel = newChannel()
                try {
                    channel.writeInbound(
                        upPacket(commandId, "[$requestedChannel]", userId = state.userId + 10_000),
                    )

                    val packet = assertIs<DownPacket>(
                        channel.readOutbound<Any>(),
                        "channel=$requestedChannel emitted no response",
                    )
                    assertEquals(commandId, packet.cmd, "channel=$requestedChannel")
                    assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd, "channel=$requestedChannel")
                    assertNotEquals(5_125, packet.cmd, "channel=$requestedChannel emitted cmd 5125")
                    assertEquals(DownType.PLAIN, packet.dataType, "channel=$requestedChannel")
                    val expectedBody = "[$requestedChannel,[]]"
                    assertTrue(
                        expectedBody.toByteArray().contentEquals(packet.body),
                        "channel=$requestedChannel emitted wrong wire bytes",
                    )
                    val parsed = mapper.readTree(packet.body)
                    assertTrue(parsed.isArray, "channel=$requestedChannel response is not an array")
                    assertEquals(2, parsed.size(), "channel=$requestedChannel response arity")
                    assertTrue(parsed[0].isIntegralNumber, "channel=$requestedChannel slot 0 type")
                    assertEquals(requestedChannel, parsed[0].intValue(), "channel=$requestedChannel slot 0")
                    assertTrue(parsed[1].isArray, "channel=$requestedChannel slot 1 type")
                    assertTrue(parsed[1].isEmpty, "channel=$requestedChannel slot 1 rows")
                    assertNull(
                        channel.readOutbound<Any>(),
                        "channel=$requestedChannel emitted an extra, cmd 90005, or cmd 5125 packet",
                    )
                    assertStateUnchanged("valid channel=$requestedChannel")
                } finally {
                    channel.finishAndReleaseAll()
                }
            }
        }

        assertAll(
            "summer farm user list command",
            listOf(invalidRequestsExecutable) + validChannelExecutables,
        )
    }

    @Test
    fun `backflow empty lists enforce exact requests and remain repository free`() {
        val accountKey = "backflow-empty-list-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Backflow Empty List User",
        )
        state.resources.money = 2_576_577
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Backflow Empty List Union", nowSec = 3)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_027, nowSec = 3))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val commonInvalidRequests = linkedMapOf(
            "number scalar" to "2576",
            "string scalar" to """"synthetic-backflow-canary"""",
            "boolean scalar" to "false",
            "object" to """{"synthetic":"backflow-canary"}""",
            "nested array" to "[[]]",
            "non-empty array" to "[0]",
            "malformed JSON" to "[",
            "empty text" to "",
            "trailing token" to "[] {}",
        )
        val invalidRequestsByCommand = linkedMapOf(
            2_576 to commonInvalidRequests,
            2_577 to linkedMapOf("JSON null" to "null").apply {
                putAll(commonInvalidRequests)
            },
        )
        val validRequestsByCommand = linkedMapOf(
            2_576 to listOf("null", "[]"),
            2_577 to listOf("[]"),
        )
        val pairedCommands = mapOf(
            2_576 to 2_577,
            2_577 to 2_576,
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val invalidRequestExecutables = invalidRequestsByCommand.map { (commandId, invalidRequests) ->
            Executable {
                val channel = newChannel()
                try {
                    invalidRequests.forEach { (case, request) ->
                        channel.writeInbound(
                            upPacket(commandId, request, userId = state.userId + 10_000),
                        )
                        assertNull(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId case=$case emitted an outbound packet",
                        )
                    }
                    assertStateUnchanged("cmd=$commandId invalid requests")
                } finally {
                    channel.finishAndReleaseAll()
                }
            }
        }
        val validRequestExecutables = validRequestsByCommand.flatMap { (commandId, validRequests) ->
            validRequests.map { request ->
                Executable {
                    val channel = newChannel()
                    try {
                        channel.writeInbound(
                            upPacket(commandId, request, userId = state.userId + 10_000),
                        )

                        val packet = assertIs<DownPacket>(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId valid request=$request emitted no response",
                        )
                        assertEquals(commandId, packet.cmd, "cmd=$commandId request=$request")
                        assertNotEquals(
                            Cmd.SYS_NOTIFY_DB_UPDATE,
                            packet.cmd,
                            "cmd=$commandId request=$request",
                        )
                        assertNotEquals(
                            pairedCommands.getValue(commandId),
                            packet.cmd,
                            "cmd=$commandId request=$request emitted paired command",
                        )
                        assertEquals(
                            DownType.PLAIN,
                            packet.dataType,
                            "cmd=$commandId request=$request",
                        )
                        assertTrue(
                            "[]".toByteArray().contentEquals(packet.body),
                            "cmd=$commandId request=$request emitted wrong wire bytes",
                        )
                        val parsed = mapper.readTree(packet.body)
                        assertTrue(parsed.isArray, "cmd=$commandId request=$request")
                        assertTrue(parsed.isEmpty, "cmd=$commandId request=$request")
                        assertNull(
                            channel.readOutbound<Any>(),
                            "cmd=$commandId request=$request emitted an extra or paired packet",
                        )
                        assertStateUnchanged("cmd=$commandId valid request=$request")
                    } finally {
                        channel.finishAndReleaseAll()
                    }
                }
            }
        }

        assertAll(
            "backflow empty list commands",
            invalidRequestExecutables + validRequestExecutables,
        )
    }

    @Test
    fun `nearby clan list accepts only exact empty array and remains repository free`() {
        val commandId = 2_701
        val accountKey = "nearby-clan-list-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Nearby Clan Boundary User",
        )
        state.resources.money = 2_701_000
        UnionStateRepository.create(state, "Nearby Clan Boundary Union", nowSec = 2)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_026, nowSec = 2))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "number scalar" to "2701",
            "string scalar" to """"synthetic-nearby-clan-canary"""",
            "boolean scalar" to "true",
            "object" to """{"synthetic":"nearby-clan-canary"}""",
            "nested array" to "[[]]",
            "non-empty array" to "[0]",
            "malformed JSON" to "[",
            "empty text" to "",
            "trailing token" to "[] {}",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val channel = newChannel()
        try {
            invalidRequests.forEach { (case, request) ->
                channel.writeInbound(
                    upPacket(commandId, request, userId = state.userId + 10_000),
                )
                assertNull(
                    channel.readOutbound<Any>(),
                    "case=$case emitted an outbound packet",
                )
            }
            assertStateUnchanged("invalid requests")

            channel.writeInbound(
                upPacket(commandId, "[]", userId = state.userId + 10_000),
            )

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "valid [] request emitted no response",
            )
            assertEquals(commandId, packet.cmd)
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            assertTrue(
                "[]".toByteArray().contentEquals(packet.body),
                "valid [] request emitted wrong wire bytes",
            )
            val parsed = mapper.readTree(packet.body)
            assertTrue(parsed.isArray)
            assertTrue(parsed.isEmpty)
            assertNull(channel.readOutbound<Any>(), "valid [] request emitted an extra packet")
            assertStateUnchanged("valid request")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `clan contribution list accepts only exact empty array and remains repository free`() {
        val commandId = 2_711
        val accountKey = "clan-contribution-list-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Clan Contribution Boundary User",
        )
        state.resources.money = 2_711_000
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Clan Contribution Boundary Union", nowSec = 3)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_026, nowSec = 3))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "empty text" to "",
            "malformed JSON" to "[",
            "number scalar" to "2711",
            "string scalar" to """"synthetic-contribution-canary"""",
            "boolean scalar" to "true",
            "top-level object" to """{"synthetic":"contribution-canary"}""",
            "nested array" to "[[]]",
            "non-empty array" to "[0]",
            "trailing token" to "[] {}",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val channel = newChannel()
        try {
            invalidRequests.forEach { (case, request) ->
                channel.writeInbound(
                    upPacket(commandId, request, userId = state.userId + 10_000),
                )
                assertNull(
                    channel.readOutbound<Any>(),
                    "case=$case emitted an outbound packet",
                )
            }
            assertStateUnchanged("invalid requests")

            channel.writeInbound(
                upPacket(commandId, "[]", userId = state.userId + 10_000),
            )

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "valid [] request emitted no response",
            )
            assertEquals(commandId, packet.cmd)
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            assertTrue(
                "[]".toByteArray().contentEquals(packet.body),
                "valid [] request emitted wrong wire bytes",
            )
            val parsed = mapper.readTree(packet.body)
            assertTrue(parsed.isArray)
            assertTrue(parsed.isEmpty)
            assertNull(
                channel.readOutbound<Any>(),
                "valid [] request emitted an unsolicited, cmd 90005, or extra packet",
            )
            assertStateUnchanged("valid request")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `clan npc city list accepts only exact empty array and remains repository free`() {
        val commandId = 2_709
        val accountKey = "clan-npc-city-list-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Clan NPC City Boundary User",
        )
        state.resources.money = 2_709_000
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Clan NPC City Boundary Union", nowSec = 3)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_028, nowSec = 3))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "empty text" to "",
            "malformed JSON" to "[",
            "number scalar" to "2709",
            "string scalar" to """"synthetic-npc-city-canary"""",
            "boolean scalar" to "true",
            "top-level object" to """{"synthetic":"npc-city-canary"}""",
            "nested array" to "[[]]",
            "non-empty array" to "[0]",
            "trailing token" to "[] {}",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val channel = newChannel()
        try {
            invalidRequests.forEach { (case, request) ->
                channel.writeInbound(
                    upPacket(commandId, request, userId = state.userId + 10_000),
                )
                assertNull(
                    channel.readOutbound<Any>(),
                    "case=$case emitted an outbound packet",
                )
            }
            assertStateUnchanged("invalid requests")

            channel.writeInbound(
                upPacket(commandId, "[]", userId = state.userId + 10_000),
            )

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "valid [] request emitted no response",
            )
            assertEquals(commandId, packet.cmd)
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            assertTrue(
                "[]".toByteArray().contentEquals(packet.body),
                "valid [] request emitted wrong wire bytes",
            )
            val parsed = mapper.readTree(packet.body)
            assertTrue(parsed.isArray)
            assertTrue(parsed.isEmpty)
            assertNull(
                channel.readOutbound<Any>(),
                "valid [] request emitted an unsolicited, cmd 90005, or extra packet",
            )
            assertStateUnchanged("valid request")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `clan junxian list accepts only exact empty array and remains repository free`() {
        val commandId = 2_712
        val accountKey = "clan-junxian-list-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Clan Junxian Boundary User",
        )
        state.resources.money = 2_712_000
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Clan Junxian Boundary Union", nowSec = 3)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_028, nowSec = 3))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "empty text" to "",
            "malformed JSON" to "[",
            "number scalar" to "2712",
            "string scalar" to """"synthetic-junxian-canary"""",
            "boolean scalar" to "true",
            "top-level object" to """{"synthetic":"junxian-canary"}""",
            "nested array" to "[[]]",
            "non-empty array" to "[0]",
            "trailing token" to "[] {}",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val channel = newChannel()
        try {
            invalidRequests.forEach { (case, request) ->
                channel.writeInbound(
                    upPacket(commandId, request, userId = state.userId + 10_000),
                )
                assertNull(
                    channel.readOutbound<Any>(),
                    "case=$case emitted an outbound packet",
                )
            }
            assertStateUnchanged("invalid requests")

            channel.writeInbound(
                upPacket(commandId, "[]", userId = state.userId + 10_000),
            )

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "valid [] request emitted no response",
            )
            assertEquals(commandId, packet.cmd)
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            assertTrue(
                "[]".toByteArray().contentEquals(packet.body),
                "valid [] request emitted wrong wire bytes",
            )
            val parsed = mapper.readTree(packet.body)
            assertTrue(parsed.isArray)
            assertTrue(parsed.isEmpty)
            assertNull(
                channel.readOutbound<Any>(),
                "valid [] request emitted an unsolicited, cmd 90005, or extra packet",
            )
            assertStateUnchanged("valid request")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `clan supreme list accepts only exact empty array and remains repository free`() {
        val commandId = 2_714
        val accountKey = "clan-supreme-list-boundary"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Clan Supreme Boundary User",
        )
        state.resources.money = 2_714_000
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Clan Supreme Boundary Union", nowSec = 3)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_027, nowSec = 3))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val invalidRequests = linkedMapOf(
            "JSON null" to "null",
            "empty text" to "",
            "malformed JSON" to "[",
            "number scalar" to "2714",
            "string scalar" to """"synthetic-supreme-canary"""",
            "boolean scalar" to "true",
            "top-level object" to """{"synthetic":"supreme-canary"}""",
            "nested array" to "[[]]",
            "non-empty array" to "[0]",
            "trailing token" to "[] {}",
        )

        fun assertStateUnchanged(stage: String) {
            assertEquals(playerBefore, state.toSnapshot(), "$stage player state changed")
            assertTrue(
                persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
                "$stage persisted player bytes changed",
            )
            assertEquals(worldBefore, WorldStateRepository.projection(), "$stage world state changed")
            assertEquals(unionsBefore, UnionStateRepository.all(), "$stage union state changed")
        }

        val channel = newChannel()
        try {
            invalidRequests.forEach { (case, request) ->
                channel.writeInbound(
                    upPacket(commandId, request, userId = state.userId + 10_000),
                )
                assertNull(
                    channel.readOutbound<Any>(),
                    "case=$case emitted an outbound packet",
                )
            }
            assertStateUnchanged("invalid requests")

            channel.writeInbound(
                upPacket(commandId, "[]", userId = state.userId + 10_000),
            )

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "valid [] request emitted no response",
            )
            assertEquals(commandId, packet.cmd)
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            assertTrue(
                "[]".toByteArray().contentEquals(packet.body),
                "valid [] request emitted wrong wire bytes",
            )
            val parsed = mapper.readTree(packet.body)
            assertTrue(parsed.isArray)
            assertTrue(parsed.isEmpty)
            assertNull(
                channel.readOutbound<Any>(),
                "valid [] request emitted an unsolicited, cmd 90005, or extra packet",
            )
            assertStateUnchanged("valid request")
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `sampled empty list queries ignore arbitrary payloads without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "sampled-empty-list-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Sampled Empty List Snapshot User",
        )
        UnionStateRepository.create(state, "Sampled Empty List Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_004, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val commands = listOf(
            Cmd.GET_UNION_BATTLE_REPORT,
            Cmd.MAIL_OUTBOX,
            Cmd.GET_BLACK_LIST,
            Cmd.NOTICE_LIST,
            Cmd.FRIEND_GROUP_GET_HISTORY_CHAT,
            Cmd.QUERY_WANTED_TO_REPOTR,
            Cmd.STRATEGY_HELP_GET,
            Cmd.COMMAND_PLAN_GET_UNION_TEMP_GROUP,
            Cmd.UNION_STATION_PLAYER_DANMU_LIST_GET,
        )
        val syntheticPayloads = listOf(
            """["synthetic-alpha",{"opaque":17}]""",
            """{"synthetic":"beta","values":[false,42]}""",
            "not-json synthetic payload",
        )

        commands.forEach { cmd ->
            syntheticPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals("[]", response.body.toString(Charsets.UTF_8))
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `union nearby player list ignores every request body and returns one empty list without mutation`() {
        val channel = newChannel()
        val accountKey = "union-nearby-player-list"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Union Nearby Player List User",
        )
        state.resources.money = 1_234_567
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Union Nearby Player List Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_018, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val requestBodies = listOf(
            "null",
            "[]",
            """[17,{"opaque":42}]""",
            """{"synthetic":"metadata","ids":[17,42]}""",
            "not-json synthetic payload",
            "[] {}",
        )

        requestBodies.forEach { request ->
            channel.writeInbound(
                upPacket(Cmd.UNION_NEARBY_PLAYER_LIST, request, userId = state.userId),
            )

            val response = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "request=$request",
            )
            assertEquals(Cmd.UNION_NEARBY_PLAYER_LIST, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("[]", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }

        assertEquals(playerBefore, state.toSnapshot())
        assertEquals(
            persistedBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `union social empty queries ignore every request body and return one empty list without mutation`() {
        val channel = newChannel()
        val accountKey = "union-social-empty-queries"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Union Social Empty Query User",
        )
        state.resources.money = 7_654_321
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Union Social Empty Query Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_019, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val commands = listOf(104, 736, 741, 3_410, 3_411)
        val requestBodies = listOf(
            "[]",
            "null",
            "{}",
            "0",
            """["wrong-slot-type"]""",
            """[17,{"opaque":42},false]""",
            "not-json synthetic payload",
            "[] {}",
        )

        commands.forEach { cmd ->
            requestBodies.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals("[]", response.body.toString(Charsets.UTF_8))
                val body = mapper.readTree(response.body)
                assertTrue(body.isArray, "cmd=$cmd request=$request did not return an array")
                assertEquals(0, body.size(), "cmd=$cmd request=$request returned a synthetic row")
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }

        assertEquals(playerBefore, state.toSnapshot())
        assertTrue(
            persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
            "persisted player bytes changed",
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `season history queries ignore arbitrary payloads without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "season-history-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Season History Snapshot User",
        )
        UnionStateRepository.create(state, "Season History Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_005, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val syntheticPayloads = listOf(
            """[987654321,{"mode":"synthetic-alpha","h5":"local-only"}]""",
            """{"roleId":"synthetic-beta","modeFlags":[false,42]}""",
            "not-json synthetic payload",
        )
        val responseBodies = mutableMapOf<Int, String>()

        listOf(
            Cmd.GET_USER_SEASON_RECORD,
            Cmd.GET_SEASON_HISTROY_PARAMS,
        ).forEach { cmd ->
            syntheticPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                val body = response.body.toString(Charsets.UTF_8)
                assertEquals(responseBodies.putIfAbsent(cmd, body) ?: body, body)
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }

        val seasonRecordOuter = mapper.readTree(responseBodies.getValue(Cmd.GET_USER_SEASON_RECORD))
        assertTrue(seasonRecordOuter.isTextual)
        val seasonRecordInner = mapper.readTree(seasonRecordOuter.textValue())
        assertTrue(seasonRecordInner.isObject)
        assertEquals(
            setOf(
                "data",
                "success",
                "succeed",
                "sourceHost",
                "reqTiming",
                "status",
                "statusCode",
                "reqId",
            ),
            seasonRecordInner.fieldNames().asSequence().toSet(),
        )
        assertTrue(seasonRecordInner["data"].isArray)
        assertEquals(0, seasonRecordInner["data"].size())
        assertTrue(seasonRecordInner["success"].isBoolean)
        assertTrue(seasonRecordInner["success"].asBoolean())
        assertTrue(seasonRecordInner["succeed"].isBoolean)
        assertTrue(seasonRecordInner["succeed"].asBoolean())
        assertTrue(seasonRecordInner["sourceHost"].isTextual)
        assertEquals("", seasonRecordInner["sourceHost"].asText())
        assertTrue(seasonRecordInner["reqTiming"].isNull)
        assertTrue(seasonRecordInner["status"].isIntegralNumber)
        assertEquals(200, seasonRecordInner["status"].asInt())
        assertTrue(seasonRecordInner["statusCode"].isIntegralNumber)
        assertEquals(200, seasonRecordInner["statusCode"].asInt())
        assertTrue(seasonRecordInner["reqId"].isTextual)
        assertEquals("", seasonRecordInner["reqId"].asText())

        val seasonHistoryParams = mapper.readTree(
            responseBodies.getValue(Cmd.GET_SEASON_HISTROY_PARAMS),
        )
        assertTrue(seasonHistoryParams.isObject)
        assertEquals(0, seasonHistoryParams.size())

        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `card record projects deterministic ordinary hero acquisition history without mutation`() {
        val channel = newChannel()
        val accountKey = "card-record-local"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Card Record Local User",
        )
        state.addHero(heroId = 100_202, nowSec = 1_700_000_200)
        state.addHero(heroId = 100_101, nowSec = 1_700_000_300)
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        state.addHero(heroId = 100_303, nowSec = 1_699_999_999, isAdvanceMaterial = true)
        state.addHero(heroId = 100_202, nowSec = 1_600_000_000, isAdvanceMaterial = true)
        UnionStateRepository.create(state, "Card Record Local Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_006, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val requests = listOf(
            "null",
            "[]",
            "not-json synthetic payload",
            """["opaque-a","opaque-b","opaque-c",{"opaque":"opaque-d"}]""",
        )
        val expectedText = "100101,1700000000;100202,1700000200"

        requests.forEach { request ->
            channel.writeInbound(upPacket(Cmd.CARD_RECORD, request, userId = state.userId))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.CARD_RECORD, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            val outer = mapper.readTree(response.body)
            assertTrue(outer.isTextual)
            assertEquals(expectedText, outer.textValue())
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }

        assertEquals(playerBefore, state.toSnapshot())
        PlayerStateRepository.configure(FilePlayerRepository(repositoryRoot))
        channel.writeInbound(upPacket(Cmd.CARD_RECORD, "null", userId = state.userId))
        val reloadedResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.CARD_RECORD, reloadedResponse.cmd)
        assertEquals(DownType.PLAIN, reloadedResponse.dataType)
        assertEquals(expectedText, mapper.readTree(reloadedResponse.body).textValue())
        assertNull(channel.readOutbound<Any>())

        assertEquals(
            playerBefore,
            requireNotNull(PlayerStateRepository.findExisting(accountKey)).toSnapshot(),
        )
        assertEquals(
            persistedBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `card record returns empty string for unbound and missing accounts without creating state`() {
        val channel = newChannel()
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()

        channel.writeInbound(upPacket(Cmd.CARD_RECORD, "null", userId = session.wireUserId))
        val unboundResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.CARD_RECORD, unboundResponse.cmd)
        assertEquals(DownType.PLAIN, unboundResponse.dataType)
        assertEquals("", mapper.readTree(unboundResponse.body).textValue())
        assertNull(channel.readOutbound<Any>())
        assertNull(
            FilePlayerRepository(repositoryRoot).findByAccount("legacy-user-${session.wireUserId}"),
        )

        val missingAccount = "missing-card-record"
        session.bind(missingAccount, 77_777)
        channel.writeInbound(upPacket(Cmd.CARD_RECORD, "[]", userId = session.userId))
        val missingResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.CARD_RECORD, missingResponse.cmd)
        assertEquals(DownType.PLAIN, missingResponse.dataType)
        assertEquals("", mapper.readTree(missingResponse.body).textValue())
        assertNull(channel.readOutbound<Any>())
        assertNull(FilePlayerRepository(repositoryRoot).findByAccount(missingAccount))
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `union join eligibility projects only bound local membership without mutation`() {
        val channel = newChannel()
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val accountKey = "union-join-eligibility"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Union Join Eligibility User",
        )
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_008, nowSec = 1))
        PlayerStateRepository.save(state)
        val playerWithoutUnion = state.toSnapshot()
        val persistedWithoutUnion = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldWithoutUnion = WorldStateRepository.projection()
        val unionsWithoutUnion = UnionStateRepository.all()

        listOf("null", "[]").forEach { request ->
            channel.writeInbound(
                upPacket(
                    Cmd.CHECK_HAVE_UNION_TO_JOIN,
                    request,
                    userId = 987_654_321,
                ),
            )

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "unbound request=$request")
            assertEquals(Cmd.CHECK_HAVE_UNION_TO_JOIN, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("true", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "unbound request=$request emitted an extra packet")
        }
        assertNull(
            FilePlayerRepository(repositoryRoot).findByAccount("legacy-user-${session.wireUserId}"),
        )
        assertEquals(playerWithoutUnion, state.toSnapshot())
        assertEquals(
            persistedWithoutUnion,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldWithoutUnion, WorldStateRepository.projection())
        assertEquals(unionsWithoutUnion, UnionStateRepository.all())

        session.bind(accountKey, state.userId)
        listOf("null", "[]").forEach { request ->
            channel.writeInbound(
                upPacket(
                    Cmd.CHECK_HAVE_UNION_TO_JOIN,
                    request,
                    userId = 987_654_321,
                ),
            )

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "bound request=$request")
            assertEquals(Cmd.CHECK_HAVE_UNION_TO_JOIN, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("true", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "bound request=$request emitted an extra packet")
        }
        assertEquals(playerWithoutUnion, state.toSnapshot())
        assertEquals(
            persistedWithoutUnion,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldWithoutUnion, WorldStateRepository.projection())
        assertEquals(unionsWithoutUnion, UnionStateRepository.all())

        UnionStateRepository.create(state, "Union Join Eligibility Local Union", nowSec = 2)
        val playerWithUnion = state.toSnapshot()
        val persistedWithUnion = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldWithUnion = WorldStateRepository.projection()
        val unionsWithUnion = UnionStateRepository.all()
        val ignoredPayloads = listOf(
            "null",
            "[]",
            "not-json synthetic payload",
            """[123456,654321,{"claimedUnionId":0}]""",
            """{"userId":987654321,"unionId":0,"eligible":true}""",
        )

        ignoredPayloads.forEach { request ->
            channel.writeInbound(
                upPacket(
                    Cmd.CHECK_HAVE_UNION_TO_JOIN,
                    request,
                    userId = 987_654_321,
                ),
            )

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.CHECK_HAVE_UNION_TO_JOIN, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("false", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        assertEquals(playerWithUnion, state.toSnapshot())
        assertEquals(
            persistedWithUnion,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldWithUnion, WorldStateRepository.projection())
        assertEquals(unionsWithUnion, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `channel certification always rejects claims without repository access or mutation`() {
        val channel = newChannel()
        val accountKey = "channel-certification-rejection"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Channel Certification Rejection User",
        )
        UnionStateRepository.create(state, "Channel Certification Local Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_009, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val requests = listOf(
            "[-1]",
            "[0]",
            "[1]",
            "not-json synthetic payload",
            """{"sdkStatus":1,"channel":"synthetic","certified":true}""",
            """[1,{"device":"synthetic","platformUserId":"opaque"}]""",
        )

        requests.forEach { request ->
            channel.writeInbound(
                upPacket(
                    Cmd.SET_CHANNEL_CERTIFICATION,
                    request,
                    userId = 987_654_321,
                ),
            )

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.SET_CHANNEL_CERTIFICATION, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("false", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        assertEquals(playerBefore, state.toSnapshot())
        assertEquals(
            persistedBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `real name logout flushes one boolean response then closes only its channel without mutation`() {
        val observer = newChannel()
        val accountKey = "real-name-logout-local"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Real Name Logout Local User",
        )
        UnionStateRepository.create(state, "Real Name Logout Local Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_010, nowSec = 1))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val requests = listOf(
            "null",
            "[]",
            "not-json synthetic payload",
            """{"device":"synthetic","platform":"local","realNameStatus":"opaque"}""",
        )

        requests.forEach { request ->
            val channel = newChannel()
            val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
            session.bind(accountKey, state.userId)

            channel.writeInbound(
                upPacket(
                    Cmd.REALNAME_LOGOUT,
                    request,
                    userId = state.userId,
                ),
            )

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.REALNAME_LOGOUT, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("true", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
            channel.runPendingTasks()
            channel.runScheduledPendingTasks()
            assertFalse(channel.isActive, "request=$request left the channel active")
            assertFalse(channel.isOpen, "request=$request left the channel open")
            assertTrue(observer.isActive, "request=$request closed another channel")
            assertTrue(observer.isOpen, "request=$request closed another channel")
            channel.finishAndReleaseAll()
        }

        assertEquals(playerBefore, state.toSnapshot())
        assertEquals(
            persistedBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        observer.finishAndReleaseAll()
    }

    @Test
    fun `customer service token pre request always rejects locally without repository access`() {
        val channel = newChannel()
        val accountKey = "customer-service-local"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Customer Service Local User",
        )
        UnionStateRepository.create(state, "Customer Service Local Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_007, nowSec = 1))
        PlayerStateRepository.save(state)
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val requests = listOf(
            "null",
            "[]",
            "not-json synthetic payload",
            """["opaque-a","opaque-b","opaque-c",{"opaque":"opaque-d"}]""",
        )

        requests.forEach { request ->
            channel.writeInbound(
                upPacket(
                    Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE,
                    request,
                    userId = state.userId,
                ),
            )

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            val outer = mapper.readTree(response.body)
            assertTrue(outer.isTextual)
            assertEquals("", outer.textValue())
            assertTrue(outer.textValue().isEmpty())
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }

        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `pre server user operation query echoes only a valid integer user id`() {
        val channel = newChannel()
        val validRequests = listOf(
            "[17]" to "[0,17,[]]",
            "[-42]" to "[0,-42,[]]",
            "[2147483647]" to "[0,2147483647,[]]",
            "[-2147483648]" to "[0,-2147483648,[]]",
            """[23,{"ignored":"slot"}]""" to "[0,23,[]]",
        )
        val invalidRequests = listOf(
            "",
            "not-json",
            "{}",
            "0",
            "[]",
            "[null]",
            """["17"]""",
            "[1.5]",
            "[2147483648]",
            "[-2147483649]",
            "[17] true",
        )

        validRequests.forEach { (request, expectedBody) ->
            channel.writeInbound(upPacket(Cmd.PRE_SERVER_QUERY_USER_OP, request))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.PRE_SERVER_QUERY_USER_OP, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        invalidRequests.forEach { request ->
            channel.writeInbound(upPacket(Cmd.PRE_SERVER_QUERY_USER_OP, request))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.PRE_SERVER_QUERY_USER_OP, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals("[0,0,[]]", response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `pre login external responses ignore payloads and leave repositories unchanged`() {
        val channel = newChannel()
        val accountKey = "pre-login-external-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Pre Login Snapshot User",
        )
        UnionStateRepository.create(state, "Pre Login Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_003, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val sensitiveShapedPayload =
            """[{"token":"local-placeholder","account":"local-account","session":"local-session","ips":["192.0.2.1"],"device":"local-device"}]"""
        val cases = listOf(
            Triple(Cmd.PRE_SERVER_QUERY_USER_OP, "[31]", "[0,31,[]]"),
            Triple(Cmd.PRE_SERVER_GEN_H5_SIGN, sensitiveShapedPayload, "\"\""),
            Triple(Cmd.QUERY_NEW_COMMUNITY_INFO, sensitiveShapedPayload, """[0,"",{},[],""]"""),
            Triple(Cmd.QUERY_SIMULATE_TOKEN, sensitiveShapedPayload, "[0]"),
            Triple(Cmd.IP_USER_COUNT_PRE, sensitiveShapedPayload, "[0,0]"),
        )

        cases.forEach { (cmd, request, expectedBody) ->
            channel.writeInbound(upPacket(cmd, request, userId = state.userId))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "cmd=$cmd")
            assertEquals(cmd, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "cmd=$cmd emitted an extra packet")
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `external service rejections and local login flags ignore arbitrary payloads without mutation`() {
        val channel = newChannel()
        val accountKey = "external-rejection-login-flags"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "External Rejection Login Flags User",
        )
        UnionStateRepository.create(state, "External Rejection Login Flags Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_012, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val expectedBodies = mapOf(
            Cmd.FILE_PICKER_GET_TOKEN_DEFAULT to """["",""]""",
            Cmd.CHECK_ADD_WEIXIN to "[false,[]]",
            Cmd.YOUTH_INK_MAP_TIPS to "[0,0]",
        )
        val arbitraryPayloads = listOf(
            "null",
            "[]",
            """{"synthetic":"metadata","device":"local-device"}""",
            """[{"opaque":true}] trailing""",
            "not-json synthetic payload",
        )

        expectedBodies.forEach { (cmd, expectedBody) ->
            arbitraryPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `unknown command is logged without fabricated success response`() {
        val channel = newChannel()

        channel.writeInbound(upPacket(45_678, "[]"))

        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `read only empty queries ignore arbitrary payloads without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "read-only-empty-query-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Read Only Empty Query User",
        )
        UnionStateRepository.create(state, "Read Only Empty Query Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_011, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val commands = listOf(
            Cmd.SWITCH_ROLE_QUERY_ROLE_LIST,
            Cmd.MAIL_INBOX,
            Cmd.MAIL_GET_CONTACTS,
            Cmd.USER_GET_SEASON_COURSE_LIST,
            Cmd.CHAT_GET_ZHAO_XIAN_MSG,
            Cmd.PROGRESS_GET_INFO,
            Cmd.MAIL_NOTIFY_GET_ALL,
        )
        val arbitraryPayloads = listOf(
            "null",
            "[]",
            """{"synthetic":"metadata","ids":[17,42]}""",
            """[{"opaque":true}] trailing""",
            "not-json synthetic payload",
        )

        commands.forEach { cmd ->
            arbitraryPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals("[]", response.body.toString(Charsets.UTF_8))
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `read only union army and station queries preserve shapes without mutation`() {
        val channel = newChannel()
        val accountKey = "read-only-union-army-station"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Read Only Union Army Station User",
        )
        UnionStateRepository.create(state, "Read Only Union Army Station Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_014, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val expectedBodies = mapOf(
            Cmd.UNION_NPC_CITY_LIST to """[[],{},{},{},{}]""",
            Cmd.CHAT_UNION_PLAN_HISTORY_ID to "{}",
            Cmd.COMMAND_PLAN_GEL_UNION_TEMP_GROUP_MEMBER to "{}",
            Cmd.ARMY_REINFORCE_STAY_CHECK to "{}",
            Cmd.UNION_STATION_GET_DATA to "[[]]",
            Cmd.UNION_STATION_ALL_RECORDS to "[[]]",
        )
        val arbitraryPayloads = listOf(
            "null",
            "[]",
            """{"synthetic":"metadata","ids":[17,42]}""",
            """[{"opaque":true}] trailing""",
            "not-json synthetic payload",
        )

        expectedBodies.forEach { (cmd, expectedBody) ->
            arbitraryPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `optional social and world queries preserve local shapes without mutation`() {
        val channel = newChannel()
        val accountKey = "optional-social-world"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Optional Social World User",
        )
        UnionStateRepository.create(state, "Optional Social World Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_015, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val expectedBodies = mapOf(
            Cmd.CCLIVE_GET_FOLLOW_LIST to "[0,0,[]]",
            Cmd.FIRST_STATE_COOUPY_MSG to "null",
            Cmd.UNION_RELATION_FULL_REQUEST to "null",
        )
        val arbitraryPayloads = listOf(
            "null",
            "[]",
            """{"synthetic":"metadata","ids":[17,42]}""",
            """[{"opaque":true}] trailing""",
            "not-json synthetic payload",
        )

        expectedBodies.forEach { (cmd, expectedBody) ->
            arbitraryPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `multiplexed 6242 routes exact requests without mutation`() {
        val channel = newChannel()
        val accountKey = "multiplexed-6242"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Multiplexed 6242 User",
        )
        UnionStateRepository.create(state, "Multiplexed 6242 Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_016, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val cases = linkedMapOf(
            "[]" to "[]",
            "[1]" to "[0]",
            "[2]" to "[0]",
            "null" to "[]",
            "{}" to "[]",
            "not-json synthetic payload" to "[]",
            "[] trailing" to "[]",
            "[1,2]" to "[]",
            """["1"]""" to "[]",
            "[true]" to "[]",
            "[1.0]" to "[]",
            "[2147483648]" to "[]",
            "[0]" to "[]",
            "[3]" to "[]",
        )

        cases.forEach { (request, expectedBody) ->
            channel.writeInbound(
                upPacket(Cmd.UNION_STATION_ENTER_SCENE, request, userId = state.userId),
            )

            val response = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "request=$request",
            )
            assertEquals(Cmd.UNION_STATION_ENTER_SCENE, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
            assertNull(
                channel.readOutbound<Any>(),
                "request=$request emitted an extra packet",
            )
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `black market and patrol rejections preserve shapes without mutation`() {
        val channel = newChannel()
        val accountKey = "black-market-patrol-rejections"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Black Market Patrol Rejection User",
        )
        state.resources.money = 1_234_567
        state.resources.yuanBao = 7_654
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Black Market Patrol Rejection Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_017, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val expectedBodies = mapOf(
            Cmd.BLACK_MARKET_REFRESH_AUTO to "null",
            Cmd.PATORL_GET to "null",
            Cmd.PATORL_HANDLE to "null",
            Cmd.PATORL_REWARD_GET to "[0,[]]",
        )
        val arbitraryPayloads = listOf(
            "null",
            """[17,{"opaque":42}]""",
            """{"synthetic":"metadata","ids":[17,42]}""",
            "not-json synthetic payload",
            """[{"opaque":true}] trailing""",
        )

        expectedBodies.forEach { (cmd, expectedBody) ->
            arbitraryPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }
        assertEquals(playerBefore, state.toSnapshot())
        assertEquals(
            persistedBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `message acknowledgements and gift rejection ignore payloads without mutation`() {
        val channel = newChannel()
        val accountKey = "message-ack-gift-rejection"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Message Ack Gift Rejection User",
        )
        state.resources.money = 1_234_567
        state.resources.yuanBao = 7_654
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Message Ack Gift Rejection Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_013, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val expectedBodies = mapOf(
            Cmd.XUANFUQIU_RECEIVED_MSG to "null",
            Cmd.GAME_CHENGXIANGGE_RECEIVED to "null",
            Cmd.SOLDIER_GIFT_ACTIVATE to "[]",
        )
        val arbitraryPayloads = listOf(
            "null",
            "[]",
            """{"synthetic":"metadata","giftIds":[17,42]}""",
            """[[17,42],3] trailing""",
            "not-json synthetic payload",
        )

        expectedBodies.forEach { (cmd, expectedBody) ->
            arbitraryPayloads.forEach { request ->
                channel.writeInbound(upPacket(cmd, request, userId = state.userId))

                val response = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request",
                )
                assertEquals(cmd, response.cmd)
                assertEquals(DownType.PLAIN, response.dataType)
                assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$cmd request=$request emitted an extra packet",
                )
            }
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `nobility officer record query echoes exact signed 64 bit timestamps with an empty record list`() {
        val commandId = 5_212
        val channel = newChannel()
        val cases = listOf(
            "[0]" to 0L,
            "[1700000123]" to 1_700_000_123L,
            "[-42]" to -42L,
            "[-9223372036854775808]" to Long.MIN_VALUE,
            "[9223372036854775807]" to Long.MAX_VALUE,
        )

        cases.forEach { (request, expectedTimestamp) ->
            channel.writeInbound(upPacket(commandId, request))

            val packet = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(commandId, packet.cmd, "request=$request")
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd, "request=$request")
            assertEquals(DownType.PLAIN, packet.dataType, "request=$request")
            assertTrue(
                "[$expectedTimestamp,[]]".toByteArray().contentEquals(packet.body),
                "request=$request emitted wrong wire bytes",
            )
            val response = mapper.readTree(packet.body)
            assertTrue(response.isArray, "request=$request")
            assertEquals(2, response.size(), "request=$request")
            assertTrue(response[0].isIntegralNumber, "request=$request slot=0")
            assertTrue(response[0].canConvertToLong(), "request=$request slot=0")
            assertEquals(expectedTimestamp, response[0].longValue(), "request=$request slot=0")
            assertTrue(response[1].isArray, "request=$request slot=1")
            assertTrue(response[1].isEmpty, "request=$request slot=1")
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `nobility officer record query maps every invalid request category to the safe fallback`() {
        val commandId = 5_212
        val channel = newChannel()
        val invalidRequests = listOf(
            "malformed JSON" to "[17",
            "trailing token" to "[17] []",
            "non-array number" to "17",
            "non-array object" to "{}",
            "empty array" to "[]",
            "wrong arity" to "[17,18]",
            "null" to "[null]",
            "boolean" to "[true]",
            "string" to """["17"]""",
            "floating point" to "[17.0]",
            "exponential" to "[1e3]",
            "object slot" to "[{}]",
            "nested array" to "[[17]]",
            "positive out of range" to "[9223372036854775808]",
            "negative out of range" to "[-9223372036854775809]",
        )

        invalidRequests.forEach { (case, request) ->
            channel.writeInbound(upPacket(commandId, request))

            val packet = assertIs<DownPacket>(channel.readOutbound<Any>(), case)
            assertEquals(commandId, packet.cmd, case)
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd, case)
            assertEquals(DownType.PLAIN, packet.dataType, case)
            assertTrue(
                "[0,[]]".toByteArray().contentEquals(packet.body),
                "$case emitted wrong wire bytes",
            )
            val response = mapper.readTree(packet.body)
            assertTrue(response.isArray, case)
            assertEquals(2, response.size(), case)
            assertTrue(response[0].isIntegralNumber, "$case slot=0")
            assertEquals(0L, response[0].longValue(), "$case slot=0")
            assertTrue(response[1].isArray, "$case slot=1")
            assertTrue(response[1].isEmpty, "$case slot=1")
            assertNull(channel.readOutbound<Any>(), "$case emitted an extra packet")
        }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `nobility officer record query does not access or mutate repositories`() {
        val commandId = 5_212
        val channel = newChannel()
        val accountKey = "nobility-officer-record-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Nobility Officer Record User",
        )
        state.resources.money = 7_654_321
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "Nobility Officer Record Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_024, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val requests = listOf(
            "[1700000456]",
            "[-9223372036854775808]",
            "[9223372036854775808]",
            "[17] []",
            """["private-timestamp"]""",
        )

        requests.forEach { request ->
            channel.writeInbound(upPacket(commandId, request, userId = state.userId + 10_000))

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "request=$request",
            )
            assertEquals(commandId, packet.cmd, "request=$request")
            assertNotEquals(Cmd.SYS_NOTIFY_DB_UPDATE, packet.cmd, "request=$request")
            assertEquals(DownType.PLAIN, packet.dataType, "request=$request")
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        assertEquals(playerBefore, state.toSnapshot())
        assertTrue(
            persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
            "persisted player bytes changed",
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `explicit request aware queries project valid integers without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "request-aware-query-valid-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Request Query Snapshot User",
        )
        UnionStateRepository.create(state, "Request Query Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val cases = listOf(
            Triple(Cmd.UNION_GET_GROUP_LIST, """{"synthetic":"ignored"} trailing""", "[]"),
            Triple(Cmd.DAILY_REPORT_GET_DETAIL, "[1700000000]", """[[],0,"","",1700000000]"""),
            Triple(Cmd.DAILY_REPORT_GET_DETAIL, "[-42]", """[[],0,"","",-42]"""),
            Triple(Cmd.GET_HERO_RECOMMEND_2, "[100521]", "[100521]"),
            Triple(Cmd.GET_HERO_RECOMMEND_2, "[-17]", "[-17]"),
            Triple(Cmd.GET_UDS_GUESS_SEASON, "[321,7]", "[7,[]]"),
            Triple(Cmd.GET_UDS_GUESS_SEASON, "[-8,-9]", "[-9,[]]"),
        )

        cases.forEach { (cmd, request, expectedBody) ->
            channel.writeInbound(upPacket(cmd, request, userId = state.userId))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "cmd=$cmd")
            assertEquals(cmd, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "cmd=$cmd emitted an extra packet")
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `explicit request aware queries project malformed required slots to zero without mutation`() {
        val channel = newChannel()
        val accountKey = "request-aware-query-invalid-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Invalid Request Query Snapshot User",
        )
        UnionStateRepository.create(state, "Invalid Request Query Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_003, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val singleSlotInvalidRequests = listOf(
            "",
            "not-json",
            "0",
            "{}",
            "[]",
            "[null]",
            """["17"]""",
            "[1.5]",
            "[2147483648]",
            "[-2147483649]",
            "[17] true",
        )
        val cases = buildList {
            singleSlotInvalidRequests.forEach { request ->
                add(Triple(Cmd.DAILY_REPORT_GET_DETAIL, request, """[[],0,"","",0]"""))
                add(Triple(Cmd.GET_HERO_RECOMMEND_2, request, "[0]"))
            }
            listOf(
                "",
                "not-json",
                "0",
                "{}",
                "[]",
                "[1]",
                "[null,7]",
                "[1,null]",
                """["1",7]""",
                """[1,"7"]""",
                "[1.5,7]",
                "[1,7.5]",
                "[2147483648,7]",
                "[1,-2147483649]",
                "[1,7] true",
            ).forEach { request ->
                add(Triple(Cmd.GET_UDS_GUESS_SEASON, request, "[0,[]]"))
            }
        }

        cases.forEach { (cmd, request, expectedBody) ->
            channel.writeInbound(upPacket(cmd, request, userId = state.userId))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "cmd=$cmd request=$request")
            assertEquals(cmd, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "cmd=$cmd request=$request emitted an extra packet")
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `rank list type zero uses authenticated identity and local world without mutation`() {
        val alice = newChannel()
        val bob = newChannel()
        val aliceId = platformLogin(alice, "rank-alice")
        val bobId = platformLogin(bob, "rank-bob")
        val aliceSession = alice.attr(GameServerHandler.SESSION).get() ?: error("missing Alice session")
        val aliceState = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(aliceSession.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Alice Local",
        )
        assertTrue(WorldStateRepository.claimLand(aliceState, wid = 10_002, nowSec = 1))
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()

        alice.writeInbound(upPacket(Cmd.RANK_LIST, "[0,3,0]", userId = bobId))

        val packet = assertIs<DownPacket>(alice.readOutbound<Any>())
        assertEquals(Cmd.RANK_LIST, packet.cmd)
        assertEquals(DownType.PLAIN, packet.dataType)
        val response = mapper.readTree(packet.body)
        assertEquals(7, response.size())
        assertEquals(aliceId, response[2]["user_id"].asInt())
        assertEquals(1, response[2]["land_count"].asInt())
        assertEquals(
            setOf(aliceId, bobId),
            response[4].map { row -> row[1]["user_id"].asInt() }.toSet(),
        )
        assertNull(alice.readOutbound<Any>())
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        alice.finishAndReleaseAll()
        bob.finishAndReleaseAll()
    }

    @Test
    fun `rank list type one reads local union and leaves repository snapshots unchanged`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "rank-union-member")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val unionId = UnionStateRepository.create(state, "Local Rank Union", nowSec = 1)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()

        channel.writeInbound(upPacket(Cmd.RANK_LIST, "[0,3,1]", userId = playerId + 10_000))

        val packet = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.RANK_LIST, packet.cmd)
        assertEquals(DownType.PLAIN, packet.dataType)
        val response = mapper.readTree(packet.body)
        assertEquals(6, response.size())
        assertEquals(0, response[1].asInt())
        assertEquals(unionId, response[2]["union_id"].asInt())
        assertEquals("Local Rank Union", response[2]["name"].asText())
        assertNull(channel.readOutbound<Any>())
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `world boss top three rank ignores every body and returns one local projection without mutation`() {
        val commandId = Cmd.WORLD_BOSS_TOP_THREE_RANK
        val channel = newChannel()
        val accountKey = "world-boss-top-three-rank"
        val roleName = "World Boss Local User"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = roleName,
        )
        WorldStateRepository.registerOrRestorePlayer(state)
        state.resources.money = 1_234_567
        state.addHero(heroId = 100_101, nowSec = 1_700_000_000)
        UnionStateRepository.create(state, "World Boss Local Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_019, nowSec = 1))
        PlayerStateRepository.save(state)
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        session.bind(accountKey, state.userId)
        val playerBefore = state.toSnapshot()
        val persistedBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val requestBodies = listOf(
            "null",
            "[0,3,51]",
            """[17,{"opaque":42}]""",
            """{"synthetic":"metadata","ids":[17,42]}""",
            "not-json synthetic payload",
            "[0,3,51] true",
        )
        val expectedSelfKeys = setOf(
            "defend_strength",
            "kill_count",
            "lose_count",
            "refresh_time",
            "user_id",
            "user_info",
        )
        val expectedUserInfo = mapper.writeValueAsString(listOf(roleName, "0", "0,0"))

        requestBodies.forEach { request ->
            channel.writeInbound(
                upPacket(commandId, request, userId = state.userId + 10_000),
            )

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "request=$request",
            )
            assertEquals(commandId, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            val response = mapper.readTree(packet.body)
            assertTrue(response.isArray, "request=$request")
            assertEquals(6, response.size(), "request=$request")
            assertTrue(response[0].isIntegralNumber, "request=$request slot=0")
            assertEquals(51, response[0].asInt(), "request=$request slot=0")
            assertTrue(response[1].isIntegralNumber, "request=$request slot=1")
            assertEquals(-1, response[1].asInt(), "request=$request slot=1")
            assertTrue(response[2].isObject, "request=$request slot=2")
            assertEquals(
                expectedSelfKeys,
                response[2].fieldNames().asSequence().toSet(),
                "request=$request slot=2",
            )
            listOf(
                "defend_strength",
                "kill_count",
                "lose_count",
                "refresh_time",
            ).forEach { key ->
                assertTrue(response[2][key].isIntegralNumber, "request=$request key=$key")
                assertEquals(0, response[2][key].asInt(), "request=$request key=$key")
            }
            assertTrue(response[2]["user_id"].isIntegralNumber, "request=$request key=user_id")
            assertEquals(state.userId, response[2]["user_id"].asInt(), "request=$request")
            assertTrue(response[2]["user_info"].isTextual, "request=$request key=user_info")
            assertEquals(expectedUserInfo, response[2]["user_info"].asText(), "request=$request")
            assertTrue(response[3].isIntegralNumber, "request=$request slot=3")
            assertEquals(0, response[3].asInt(), "request=$request slot=3")
            assertTrue(response[4].isArray, "request=$request slot=4")
            assertTrue(response[4].isEmpty, "request=$request slot=4")
            assertTrue(response[5].isNull, "request=$request slot=5")
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }

        assertEquals(playerBefore, state.toSnapshot())
        assertEquals(
            persistedBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `npc occupation queries echo only an exact validated state id`() {
        val channel = newChannel()
        val validRequests = listOf(
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO to 17,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX to -42,
        )

        validRequests.forEach { (commandId, stateId) ->
            channel.writeInbound(upPacket(commandId, "[$stateId]"))

            val packet = assertIs<DownPacket>(channel.readOutbound<Any>(), "cmd=$commandId")
            assertEquals(commandId, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            val response = mapper.readTree(packet.body)
            assertTrue(response.isArray, "cmd=$commandId")
            assertEquals(2, response.size(), "cmd=$commandId")
            assertTrue(response[0].isIntegralNumber, "cmd=$commandId slot=0")
            assertEquals(stateId, response[0].asInt(), "cmd=$commandId slot=0")
            assertTrue(response[1].isObject, "cmd=$commandId slot=1")
            assertTrue(response[1].isEmpty, "cmd=$commandId slot=1")
            assertNull(channel.readOutbound<Any>(), "cmd=$commandId emitted an extra packet")
        }

        val invalidRequests = listOf(
            "malformed" to "not-json",
            "trailing text" to "[17] trailing",
            "trailing token" to "[17] []",
            "non-array object" to "{}",
            "non-array scalar" to "17",
            "empty array" to "[]",
            "two slots" to "[17,18]",
            "fractional" to "[17.5]",
            "string" to """["17"]""",
            "boolean" to "[true]",
            "null" to "[null]",
            "positive out-of-range" to "[2147483648]",
            "negative out-of-range" to "[-2147483649]",
        )
        listOf(
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX,
        ).forEach { commandId ->
            invalidRequests.forEach { (case, request) ->
                channel.writeInbound(upPacket(commandId, request))

                val packet = assertIs<DownPacket>(
                    channel.readOutbound<Any>(),
                    "cmd=$commandId case=$case",
                )
                assertEquals(commandId, packet.cmd, "cmd=$commandId case=$case")
                assertEquals(DownType.PLAIN, packet.dataType, "cmd=$commandId case=$case")
                val response = mapper.readTree(packet.body)
                assertTrue(response.isArray, "cmd=$commandId case=$case")
                assertEquals(2, response.size(), "cmd=$commandId case=$case")
                assertTrue(response[0].isIntegralNumber, "cmd=$commandId case=$case slot=0")
                assertEquals(0, response[0].asInt(), "cmd=$commandId case=$case slot=0")
                assertTrue(response[1].isObject, "cmd=$commandId case=$case slot=1")
                assertTrue(response[1].isEmpty, "cmd=$commandId case=$case slot=1")
                assertNull(
                    channel.readOutbound<Any>(),
                    "cmd=$commandId case=$case emitted an extra packet",
                )
            }
        }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `own rank ignores every request body and returns the no-rank integer sentinel`() {
        val channel = newChannel()
        val requestBodies = listOf(
            "",
            "not-json",
            "[28]",
            "[28,42]",
            """{"opaque":true}""",
            "[28] trailing",
        )

        requestBodies.forEach { request ->
            channel.writeInbound(upPacket(Cmd.OWN_RANK, request))

            val packet = assertIs<DownPacket>(channel.readOutbound<Any>(), "request=$request")
            assertEquals(Cmd.OWN_RANK, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            val response = mapper.readTree(packet.body)
            assertTrue(response.isIntegralNumber, "request=$request")
            assertEquals(-1, response.asInt(), "request=$request")
            assertNull(channel.readOutbound<Any>(), "request=$request emitted an extra packet")
        }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `domestic salary status echoes only an exact validated level`() {
        val channel = newChannel()

        channel.writeInbound(upPacket(Cmd.FENGLU_LEVEL_STATUS, "[9]"))

        val validPacket = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.FENGLU_LEVEL_STATUS, validPacket.cmd)
        assertEquals(DownType.PLAIN, validPacket.dataType)
        val validResponse = mapper.readTree(validPacket.body)
        assertTrue(validResponse.isObject)
        assertEquals(
            setOf("level", "contributions", "officers"),
            validResponse.fieldNames().asSequence().toSet(),
        )
        assertTrue(validResponse["level"].isIntegralNumber)
        assertEquals(9, validResponse["level"].asInt())
        assertTrue(validResponse["contributions"].isArray)
        assertTrue(validResponse["contributions"].isEmpty)
        assertTrue(validResponse["officers"].isArray)
        assertTrue(validResponse["officers"].isEmpty)
        assertNull(channel.readOutbound<Any>())

        val invalidRequests = listOf(
            "malformed" to "not-json",
            "trailing text" to "[9] trailing",
            "trailing token" to "[9] []",
            "non-array object" to "{}",
            "non-array scalar" to "9",
            "empty array" to "[]",
            "two slots" to "[9,10]",
            "fractional" to "[9.5]",
            "string" to """["9"]""",
            "boolean" to "[true]",
            "null" to "[null]",
            "positive out-of-range" to "[2147483648]",
            "negative out-of-range" to "[-2147483649]",
        )
        invalidRequests.forEach { (case, request) ->
            channel.writeInbound(upPacket(Cmd.FENGLU_LEVEL_STATUS, request))

            val packet = assertIs<DownPacket>(channel.readOutbound<Any>(), case)
            assertEquals(Cmd.FENGLU_LEVEL_STATUS, packet.cmd, case)
            assertEquals(DownType.PLAIN, packet.dataType, case)
            val response = mapper.readTree(packet.body)
            assertTrue(response.isObject, case)
            assertTrue(response.isEmpty, case)
            assertNull(channel.readOutbound<Any>(), "$case emitted an extra packet")
        }
        channel.finishAndReleaseAll()
    }

    @Test
    fun `world rank and domestic status queries do not access or mutate repositories`() {
        val channel = newChannel()
        val accountKey = "world-rank-domestic-status-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Read Only Query User",
        )
        state.resources.money = 1_234
        state.addHero(heroId = 100_101, nowSec = 1)
        UnionStateRepository.create(state, "Read Only Query Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_020, nowSec = 1))
        PlayerStateRepository.save(state)
        val playerBefore = state.toSnapshot()
        val persistedPath = repositoryRoot.resolve("accounts").resolve("$accountKey.json")
        val persistedBytesBefore = Files.readAllBytes(persistedPath)
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        PlayerStateRepository.configure(RejectingPlayerRepository)
        val requests = listOf(
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO to "[17]",
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO to "not-json",
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX to "[-42]",
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX to "[2147483648]",
            Cmd.OWN_RANK to "not-json trailing",
            Cmd.FENGLU_LEVEL_STATUS to "[9]",
            Cmd.FENGLU_LEVEL_STATUS to "[9] []",
        )

        requests.forEach { (commandId, request) ->
            channel.writeInbound(upPacket(commandId, request, userId = state.userId + 10_000))

            val packet = assertIs<DownPacket>(
                channel.readOutbound<Any>(),
                "cmd=$commandId request=$request",
            )
            assertEquals(commandId, packet.cmd, "cmd=$commandId request=$request")
            assertEquals(DownType.PLAIN, packet.dataType, "cmd=$commandId request=$request")
            assertNull(
                channel.readOutbound<Any>(),
                "cmd=$commandId request=$request emitted an extra packet",
            )
        }
        assertEquals(playerBefore, state.toSnapshot())
        assertTrue(
            persistedBytesBefore.contentEquals(Files.readAllBytes(persistedPath)),
            "persisted player bytes changed",
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `user head icons preserve requested order and duplicates without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "head-icon-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Local Snapshot User",
        )
        UnionStateRepository.create(state, "Local Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()

        channel.writeInbound(
            upPacket(
                Cmd.USER_GET_USERS_HEADICON,
                "[42,7,42,-3]",
                userId = state.userId + 10_000,
            ),
        )

        val packet = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.USER_GET_USERS_HEADICON, packet.cmd)
        assertEquals(DownType.PLAIN, packet.dataType)
        val response = mapper.readTree(packet.body)
        assertEquals(8, response.size())
        assertEquals(listOf(42, 7, 42, -3), response.filterIndexed { index, _ ->
            index % 2 == 0
        }.map { it.intValue() })
        response.filterIndexed { index, _ -> index % 2 == 1 }.forEach { tuple ->
            assertEquals(mapper.readTree("""[301,"0,0",0,""]"""), tuple)
        }
        assertNull(channel.readOutbound<Any>())
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `mail info emits one plain request aware response without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "mail-info-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Mail Snapshot User",
        )
        UnionStateRepository.create(state, "Mail Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()

        channel.writeInbound(upPacket(Cmd.MAIL_INFO, "[677829,1,42]", userId = state.userId))

        val packet = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.MAIL_INFO, packet.cmd)
        assertEquals(DownType.PLAIN, packet.dataType)
        assertEquals(
            mapper.readTree("""[677829,"","","","","",42,0,0,"","","","",0,0,""]"""),
            mapper.readTree(packet.body),
        )
        assertNull(channel.readOutbound<Any>())
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `mail brief info emits one plain local response without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "mail-brief-info-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Mail Brief Snapshot User",
        )
        UnionStateRepository.create(state, "Mail Brief Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()

        channel.writeInbound(
            upPacket(Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID, "[677829,0]", userId = state.userId),
        )

        val packet = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID, packet.cmd)
        assertEquals(DownType.PLAIN, packet.dataType)
        assertEquals(
            mapper.readTree(
                """[0,["","",0,1,677829,0,0,1,0,0,0,0,0,0,677829,0,"",0,"",0,0,0]]""",
            ),
            mapper.readTree(packet.body),
        )
        assertNull(channel.readOutbound<Any>())
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `prebook info and community token rejection emit one plain response without mutation`() {
        val channel = newChannel()
        val accountKey = "prebook-community-snapshot"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Protocol Snapshot User",
        )
        UnionStateRepository.create(state, "Protocol Snapshot Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val cases = listOf(
            Triple(
                Cmd.GET_PREBOOK_SERVER_INFO,
                """["11"]""",
                """{"prebook_info":[],"del_prebook":[],"prebook_list":[]}""",
            ),
            Triple(
                Cmd.COMMUNITY_GET_USER_TOKEN,
                "[]",
                """[0,"",""]""",
            ),
        )

        cases.forEach { (cmd, request, expectedBody) ->
            channel.writeInbound(upPacket(cmd, request, userId = state.userId))

            val packet = assertIs<DownPacket>(channel.readOutbound<Any>(), "cmd=$cmd")
            assertEquals(cmd, packet.cmd)
            assertEquals(DownType.PLAIN, packet.dataType)
            assertEquals(expectedBody, packet.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "cmd=$cmd emitted an extra packet")
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `world chat uses official 2100 shape and is returned by history`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "alice")

        channel.writeInbound(
            upPacket(
                cmdId = Cmd.CHAT,
                json = """[0,0,"你好",[[]],0,0,"","",0,"",""]""",
                userId = playerId,
            ),
        )

        val acknowledgement = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.CHAT, acknowledgement.cmd)
        assertEquals("[false,0]", acknowledgement.body.toString(Charsets.UTF_8))

        val notification = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.NOTIFY_CHAT_MSG, notification.cmd)
        assertEquals(DownType.XOR, notification.dataType)
        val message = mapper.readTree(notification.body)
        assertEquals(48, message.size())
        assertEquals(0, message[1].asInt())
        assertEquals(0, message[2].asInt())
        assertEquals(playerId, message[3].asInt())
        assertEquals(GameServerConfig.ROLE_NAME, message[4].asText())
        assertEquals("你好", message[5].asText())
        assertTrue(message[19].isIntegralNumber)
        assertTrue(message[20].isTextual)
        assertEquals("role_$playerId", message[21].asText())
        assertTrue(message[23].isIntegralNumber)
        assertTrue(message[24].isTextual)
        assertTrue(message[35].isIntegralNumber)
        assertTrue(message[39].isIntegralNumber)
        assertTrue(message[40].isTextual)
        assertEquals("role_$playerId", message[45].asText())
        assertTrue(message[46].isIntegralNumber)
        assertTrue(message[47].isIntegralNumber)

        channel.writeInbound(upPacket(711, "[]", playerId))

        val history = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(711, history.cmd)
        assertEquals(DownType.ZLIB, history.dataType)
        val slots = mapper.readTree(history.body)
        assertEquals(18, slots.size())
        assertEquals(1, slots[0].size())
        assertEquals(message[0].asInt(), slots[0][0][0].asInt())
        assertEquals(message[1], slots[0][0][1][0])
        assertEquals(message[45], slots[0][0][1][44])
        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `world chat broadcasts the same canonical record to every online session`() {
        val alice = newChannel()
        val bob = newChannel()
        val aliceId = platformLogin(alice, "alice")
        platformLogin(bob, "bob")

        alice.writeInbound(
            upPacket(
                Cmd.CHAT,
                """[0,0,"全服可见",[[]],0,0,"","",0,"",""]""",
                aliceId,
            ),
        )

        assertIs<DownPacket>(alice.readOutbound<Any>())
        val aliceNotification = assertIs<DownPacket>(alice.readOutbound<Any>())
        val bobNotification = assertIs<DownPacket>(bob.readOutbound<Any>())

        assertEquals(Cmd.NOTIFY_CHAT_MSG, aliceNotification.cmd)
        assertEquals(Cmd.NOTIFY_CHAT_MSG, bobNotification.cmd)
        assertEquals(DownType.XOR, aliceNotification.dataType)
        assertEquals(
            mapper.readTree(aliceNotification.body),
            mapper.readTree(bobNotification.body),
        )
        assertEquals("全服可见", mapper.readTree(bobNotification.body)[5].asText())

        alice.finishAndReleaseAll()
        bob.finishAndReleaseAll()
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

        channel.writeInbound(upPacket(4329, "[15061504]"))
        val mapGuard = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(4329, mapGuard.cmd)
        assertEquals(UpFlag.XOR, mapGuard.dataType)
        assertEquals("""[15061504,"305"]""", mapGuard.body.toString(Charsets.UTF_8))

        channel.writeInbound(upPacket(4331, "[15061504]"))
        val detail = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(4331, detail.cmd)
        assertEquals(UpFlag.XOR, detail.dataType)
        assertEquals("""[15061504,"305"]""", detail.body.toString(Charsets.UTF_8))
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
    fun `creating a union makes its detail and user membership immediately available`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "union-creator")
        val unionName = "洛阳同盟"

        channel.writeInbound(
            upPacket(
                cmdId = Cmd.UNION_CREATE,
                json = """["$unionName"]""",
                userId = playerId,
            ),
        )

        val created = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.UNION_CREATE, created.cmd)
        val unionId = mapper.readTree(created.body).asInt()
        assertTrue(unionId > 0)

        val userUpdate = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, userUpdate.cmd)
        assertEquals(
            listOf("0", playerId.toString(), "10", unionId.toString(), "11", unionName),
            mapper.readTree(userUpdate.body)[0][2].map { it.asText() },
        )

        channel.writeInbound(
            upPacket(
                cmdId = Cmd.UNION_INFO,
                json = "[$unionId,0]",
                userId = playerId,
            ),
        )

        val detail = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)
        assertEquals(0, detail[0].asInt())
        assertEquals(unionName, detail[1][4]["name"].asText())
        assertEquals(playerId, detail[1][4]["leader_id"].asInt())

        channel.writeInbound(upPacket(cmdId = Cmd.UNION_MEMBER, json = "[]", userId = playerId))
        val members = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)
        assertEquals(1, members.size())
        assertEquals(playerId, members[0][0].asInt())
        assertEquals(1, members[0][3].asInt())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `union official list reads the requested local union without mutating repositories`() {
        val channel = newChannel()
        val accountKey = "union-official-list"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Local Union Leader",
        )
        val unionId = UnionStateRepository.create(state, "Official List Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()

        channel.writeInbound(
            upPacket(Cmd.UNION_OFFICIAL_LIST, "[$unionId]", userId = state.userId + 10_000),
        )

        val packet = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.UNION_OFFICIAL_LIST, packet.cmd)
        assertEquals(DownType.PLAIN, packet.dataType)
        val rows = mapper.readTree(packet.body)
        assertEquals(17, rows.size())
        assertEquals(
            mapper.readTree("""[1,0,${state.userId},"Local Union Leader",301,0,0,0]"""),
            rows[0],
        )
        assertNull(channel.readOutbound<Any>())
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `invalid union official list requests return one plain empty array without mutation`() {
        val channel = newChannel()
        val accountKey = "union-official-list-invalid"
        val state = PlayerStateRepository.getOrCreate(
            accountKey = accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = "Local Invalid Request Leader",
        )
        UnionStateRepository.create(state, "Invalid Request Union", nowSec = 1)
        assertTrue(WorldStateRepository.claimLand(state, wid = 10_002, nowSec = 1))
        val playerBefore = requireNotNull(
            FilePlayerRepository(repositoryRoot).findByAccount(accountKey),
        ).toSnapshot()
        val worldBefore = WorldStateRepository.projection()
        val unionsBefore = UnionStateRepository.all()
        val invalidRequests = listOf(
            "malformed" to "not-json",
            "non-array" to "{}",
            "missing" to "[]",
            "fractional" to "[1.5]",
            "string" to """["1001"]""",
            "boolean" to "[true]",
            "positive out-of-range" to "[2147483648]",
            "negative out-of-range" to "[-2147483649]",
            "trailing text" to "[1001] trailing",
            "trailing token" to "[1001] []",
        )

        invalidRequests.forEach { (case, request) ->
            channel.writeInbound(
                upPacket(Cmd.UNION_OFFICIAL_LIST, request, userId = state.userId),
            )

            val packet = assertIs<DownPacket>(channel.readOutbound<Any>(), case)
            assertEquals(Cmd.UNION_OFFICIAL_LIST, packet.cmd, case)
            assertEquals(DownType.PLAIN, packet.dataType, case)
            assertEquals("[]", packet.body.toString(Charsets.UTF_8), case)
            assertNull(channel.readOutbound<Any>(), "$case emitted an extra packet")
        }
        assertEquals(
            playerBefore,
            requireNotNull(FilePlayerRepository(repositoryRoot).findByAccount(accountKey)).toSnapshot(),
        )
        assertEquals(worldBefore, WorldStateRepository.projection())
        assertEquals(unionsBefore, UnionStateRepository.all())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `union chat members use authenticated identity and return empty without a union`() {
        val foreignChannel = newChannel()
        val foreignUserId = platformLogin(foreignChannel, "union-chat-foreign")
        foreignChannel.writeInbound(
            upPacket(Cmd.UNION_CREATE, """["Foreign Union"]""", userId = foreignUserId),
        )
        drainOutbound(foreignChannel)

        val channel = newChannel()
        platformLogin(channel, "union-chat-no-union")
        val repository = FileUnionRepository(repositoryRoot)
        val snapshotBefore = repository.load()

        channel.writeInbound(
            upPacket(
                Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT,
                "[]",
                userId = foreignUserId,
            ),
        )

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT, response.cmd)
        assertEquals(DownType.PLAIN, response.dataType)
        assertEquals("[]", response.body.toString(Charsets.UTF_8))
        assertNull(channel.readOutbound<Any>())
        assertEquals(snapshotBefore, repository.load())
        channel.finishAndReleaseAll()
        foreignChannel.finishAndReleaseAll()
    }

    @Test
    fun `union chat members return every local member as sorted unassigned rows`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "union-chat-member")
        channel.writeInbound(
            upPacket(Cmd.UNION_CREATE, """["Local Union"]""", userId = playerId),
        )
        val unionId = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body).asInt()
        assertIs<DownPacket>(channel.readOutbound<Any>())

        val repository = FileUnionRepository(repositoryRoot)
        val memberUserIds = linkedSetOf(playerId + 20, playerId, playerId + 10)
        val snapshot = repository.load()
        repository.save(
            snapshot.copy(
                unions = snapshot.unions.map { union ->
                    if (union.unionId == unionId) union.copy(memberUserIds = memberUserIds) else union
                },
            ),
        )
        UnionStateRepository.configure(repositoryRoot)
        val snapshotBefore = repository.load()

        channel.writeInbound(
            upPacket(
                Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT,
                "[]",
                userId = playerId + 30,
            ),
        )

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT, response.cmd)
        assertEquals(DownType.PLAIN, response.dataType)
        val rows = mapper.readTree(response.body)
        assertEquals(memberUserIds.sorted(), rows.map { row -> row[0].asInt() })
        assertTrue(
            rows.all { row ->
                row.size() == 3 &&
                    row[1].asInt() == 0 &&
                    row[2].isTextual &&
                    row[2].asText().isEmpty()
            },
        )
        assertNull(channel.readOutbound<Any>())
        assertEquals(snapshotBefore, repository.load())
        channel.finishAndReleaseAll()
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
        val targetWid = 10_002

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
    fun `applying a city facade layout persists and republishes the world scene`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "city-facade-owner")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val customView = com.stzb.server.game.FacadeCatalog.DEFAULT_CITY_CUSTOM_VIEW
            .replace("1122050,100010", "3433080,100010")

        channel.writeInbound(
            upPacket(
                cmdId = 3945,
                json = """[${state.cityWid},"$customView",0,""]""",
                userId = playerId,
            ),
        )

        assertEquals(3945, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        val scene = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SEND_WORLD_SCENCE_FULL_INFO, scene.cmd)
        assertEquals(
            customView,
            mapper.readTree(scene.body)[14][state.cityWid.toString()]["4"][0].asText(),
        )
        channel.finishAndReleaseAll()
    }

    @Test
    fun `invalid city facade scheme acknowledges without changing or broadcasting`() {
        val channel = newChannel()
        val playerId = platformLogin(channel, "city-facade-invalid")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val previousView = WorldStateRepository.projection()
            .cities
            .single { it.userId == state.userId }
            .customView

        channel.writeInbound(
            upPacket(
                cmdId = 3945,
                json = """[${state.cityWid},"9999990,100010;",0,""]""",
                userId = playerId,
            ),
        )

        assertEquals(3945, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        assertNull(channel.readOutbound<Any>())
        assertEquals(
            previousView,
            WorldStateRepository.projection()
                .cities
                .single { it.userId == state.userId }
                .customView,
        )
        channel.finishAndReleaseAll()
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
    fun `active card border persists and sends sparse hero update`() {
        val channel = newChannel()
        platformLogin(channel, "alice")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val hero = state.addHero(100017, nowSec = 1_700_000_000)
        PlayerStateRepository.save(state)

        channel.writeInbound(
            upPacket(
                Cmd.HERO_ACTIVE_CARD_BORDER,
                """[${hero.heroUid},110997]""",
                userId = session.userId,
            ),
        )

        val response = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.HERO_ACTIVE_CARD_BORDER, response.cmd)
        assertEquals("[]", response.body.toString(Charsets.UTF_8))
        val notify = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, notify.cmd)
        assertEquals(
            listOf(0, hero.heroUid, 42, 110997),
            mapper.readTree(notify.body)[0][2].map { it.asInt() },
        )
        assertEquals(110997, state.hero(hero.heroUid)?.cardBorder)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `invalid card border and rotate requests acknowledge without mutation`() {
        val channel = newChannel()
        platformLogin(channel, "alice")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val hero = state.addHero(100017)

        channel.writeInbound(
            upPacket(Cmd.HERO_ACTIVE_CARD_BORDER, """[${hero.heroUid},777777]""", session.userId),
        )
        val invalid = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.HERO_ACTIVE_CARD_BORDER, invalid.cmd)
        assertEquals(CardBorderCatalog.DEFAULT_ID, hero.cardBorder)
        assertNull(channel.readOutbound<Any>())

        listOf(Cmd.HERO_USE_CARD_BORDER, Cmd.ROTATE_CARD_BORDER_ADD, Cmd.ROTATE_CARD_BORDER_REMOVE)
            .forEach { cmd ->
                channel.writeInbound(upPacket(cmd, """[${hero.heroUid},110997]""", session.userId))
                val response = assertIs<DownPacket>(channel.readOutbound<Any>())
                assertEquals(cmd, response.cmd)
                assertEquals("[]", response.body.toString(Charsets.UTF_8))
            }
        assertEquals(CardBorderCatalog.DEFAULT_ID, hero.cardBorder)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `gear equip transfer forget and invalid requests keep client tables synchronized`() {
        val channel = newChannel()
        platformLogin(channel, "gear-owner")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val firstHero = state.addHero(100017)
        val secondHero = state.addHero(100021)
        val gearUid = InventoryCatalog.normalWeapons().first().uid
        PlayerStateRepository.save(state)

        channel.writeInbound(
            upPacket(Cmd.GEAR_EQUIP, "[${firstHero.heroUid},$gearUid]", userId = session.userId),
        )
        val equipResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.GEAR_EQUIP, equipResponse.cmd)
        assertEquals("[]", equipResponse.body.toString(Charsets.UTF_8))
        val equipNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, equipNotify.cmd)
        val equipChanges = mapper.readTree(equipNotify.body)
        assertEquals(listOf(0, firstHero.heroUid, 23, gearUid), equipChanges[0][2].map { it.asInt() })
        assertEquals(listOf(0, gearUid, 9, firstHero.heroUid), equipChanges[1][2].map { it.asInt() })

        channel.writeInbound(
            upPacket(Cmd.GEAR_EQUIP, "[${secondHero.heroUid},$gearUid]", userId = session.userId),
        )
        assertEquals(Cmd.GEAR_EQUIP, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        val transferNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
        val transferChanges = mapper.readTree(transferNotify.body)
        assertEquals(listOf(0, firstHero.heroUid, 23, 0), transferChanges[0][2].map { it.asInt() })
        assertEquals(listOf(0, secondHero.heroUid, 23, gearUid), transferChanges[1][2].map { it.asInt() })
        assertEquals(listOf(0, gearUid, 9, secondHero.heroUid), transferChanges[2][2].map { it.asInt() })

        channel.writeInbound(
            upPacket(Cmd.GEAR_FORGET, "[${secondHero.heroUid},$gearUid]", userId = session.userId),
        )
        assertEquals(Cmd.GEAR_FORGET, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        val forgetNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
        val forgetChanges = mapper.readTree(forgetNotify.body)
        assertEquals(listOf(0, secondHero.heroUid, 23, 0), forgetChanges[0][2].map { it.asInt() })
        assertEquals(listOf(0, gearUid, 9, 0), forgetChanges[1][2].map { it.asInt() })

        channel.writeInbound(upPacket(Cmd.GEAR_EQUIP, "[${firstHero.heroUid}]", userId = session.userId))
        val invalidResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.GEAR_EQUIP, invalidResponse.cmd)
        assertEquals("[]", invalidResponse.body.toString(Charsets.UTF_8))
        assertNull(channel.readOutbound<Any>())
        assertEquals(0, state.equippedGearUid(firstHero.heroUid))
        assertEquals(0, state.equippedGearUid(secondHero.heroUid))
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

    private class CountingPlayerRepository(
        private val delegate: PlayerRepository,
    ) : PlayerRepository {
        var saveCount: Int = 0
            private set

        override fun findByAccount(accountKey: String): PlayerState? =
            delegate.findByAccount(accountKey)

        override fun findByAccountReadOnly(accountKey: String): PlayerState? =
            delegate.findByAccountReadOnly(accountKey)

        override fun getOrCreate(accountKey: String, cityWid: Int, roleName: String): PlayerState =
            delegate.getOrCreate(accountKey, cityWid, roleName)

        override fun save(state: PlayerState) {
            saveCount += 1
            delegate.save(state)
        }
    }

    private object RejectingPlayerRepository : PlayerRepository {
        override fun findByAccount(accountKey: String): PlayerState =
            error("repository-free handler must not read player state")

        override fun findByAccountReadOnly(accountKey: String): PlayerState =
            error("repository-free handler must not read player state")

        override fun getOrCreate(accountKey: String, cityWid: Int, roleName: String): PlayerState =
            error("repository-free handler must not create player state")

        override fun save(state: PlayerState) {
            error("repository-free handler must not save player state")
        }
    }
}
