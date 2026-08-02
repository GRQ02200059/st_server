package com.stzb.server.handler

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.CardBorderCatalog
import com.stzb.server.game.FilePlayerRepository
import com.stzb.server.game.FileUnionRepository
import com.stzb.server.game.InventoryCatalog
import com.stzb.server.game.PlayerRepository
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
    fun `unknown command is logged without fabricated success response`() {
        val channel = newChannel()

        channel.writeInbound(upPacket(45_678, "[]"))

        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `remaining first captured response batch uses the default observed shape route`() {
        val channel = newChannel()
        val cases = listOf(
            Triple(202, "[]", "[]"),
            Triple(727, "[]", "[]"),
            Triple(3758, "[]", "[]"),
            Triple(6030, "[]", "[]"),
        )

        cases.forEach { (cmd, request, expectedBody) ->
            channel.writeInbound(upPacket(cmd, request))

            val response = assertIs<DownPacket>(channel.readOutbound<Any>(), "cmd=$cmd")
            assertEquals(cmd, response.cmd)
            assertEquals(DownType.PLAIN, response.dataType)
            assertEquals(expectedBody, response.body.toString(Charsets.UTF_8))
            assertNull(channel.readOutbound<Any>(), "cmd=$cmd emitted an extra packet")
        }
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
