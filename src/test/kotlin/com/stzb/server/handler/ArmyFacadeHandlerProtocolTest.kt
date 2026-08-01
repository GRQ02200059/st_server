package com.stzb.server.handler

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.FilePlayerRepository
import com.stzb.server.game.HeroCatalog
import com.stzb.server.game.PlayerStateRepository
import com.stzb.server.game.WorldStateRepository
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ArmyFacadeHandlerProtocolTest {
    private val mapper = jacksonObjectMapper()
    private lateinit var repositoryRoot: Path

    @BeforeTest
    fun setUp() {
        GameServerHandler.resetRuntimeForTests()
        repositoryRoot = Files.createTempDirectory("stzb-army-facade-handler-")
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
    fun `army facade commands publish ordered sparse updates and reject invalid ids`() {
        val channel = newChannel()
        platformLogin(channel, "army-facade-owner")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val heroes = HeroCatalog.defaultFiveStarHeroIds().take(3).map(state::addHero)
        require(heroes.size == 3)
        PlayerStateRepository.save(state)

        channel.writeInbound(
            upPacket(
                Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
                """[101138,[${heroes[0].heroUid},${heroes[1].heroUid}]]""",
                userId = session.userId,
            ),
        )
        assertEquals(
            Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
            assertIs<DownPacket>(channel.readOutbound<Any>()).cmd,
        )
        val batchChanges = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)
        assertEquals("Tb_user_army_facade_card", batchChanges[0][1].asText())
        assertEquals("Tb_user_army_facade_card", batchChanges[1][1].asText())
        assertEquals("Tb_hero", batchChanges[2][1].asText())
        assertEquals("Tb_hero", batchChanges[3][1].asText())
        assertEquals(
            listOf(0, heroes[0].heroUid, 72, 101138),
            batchChanges[2][2].map { it.asInt() },
        )

        channel.writeInbound(
            upPacket(Cmd.USE_TROOP_FACADE_CARD, "[0,${heroes[0].heroUid}]", userId = session.userId),
        )
        assertEquals(Cmd.USE_TROOP_FACADE_CARD, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        assertEquals(
            listOf(0, heroes[0].heroUid, 72, 0),
            mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)[0][2].map { it.asInt() },
        )

        val xiyuanUid = state.specialArmyFacadeCards().single { it.facadeId == 101515 }.heroUid
        channel.writeInbound(upPacket(Cmd.HERO_ACTIVE_FACADE, "[$xiyuanUid,2]", userId = session.userId))
        assertEquals(Cmd.HERO_ACTIVE_FACADE, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        assertEquals(
            listOf(0, xiyuanUid, 5, 2),
            mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)[0][2].map { it.asInt() },
        )

        channel.writeInbound(
            upPacket(
                Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
                "[999999,[${heroes[2].heroUid}]]",
                userId = session.userId,
            ),
        )
        assertEquals(
            Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
            assertIs<DownPacket>(channel.readOutbound<Any>()).cmd,
        )
        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test
    fun `switching an active marching hero facade republishes the captured world scene`() {
        val channel = newChannel()
        platformLogin(channel, "army-facade-march-owner")
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        val state = PlayerStateRepository.getOrCreate(
            accountKey = requireNotNull(session.accountKey),
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
        requireNotNull(state.bindArmyFacadeCards(101138, listOf(hero.heroUid)))
        state.assignTeamHero(hero.heroUid, pos = 1)
        state.startMarch(
            targetWid = GameServerConfig.CITY_WID + 1,
            nowSec = 1,
            participants = listOf(
                com.stzb.server.game.PlayerMarchHero(
                    heroUid = hero.heroUid,
                    position = 0,
                    heroId = hero.heroId,
                    troops = hero.troops,
                    level = hero.level,
                    skillIds = hero.normalizedSkillIds(),
                    armyFacadeCardId = hero.armyFacadeCardId,
                ),
            ),
        )
        PlayerStateRepository.save(state)

        channel.writeInbound(
            upPacket(Cmd.USE_TROOP_FACADE_CARD, "[0,${hero.heroUid}]", userId = session.userId),
        )

        assertEquals(Cmd.USE_TROOP_FACADE_CARD, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
        val scene = assertIs<DownPacket>(channel.readOutbound<Any>())
        assertEquals(Cmd.SEND_WORLD_SCENCE_FULL_INFO, scene.cmd)
        assertEquals(
            "101138,0;",
            mapper.readTree(scene.body)[6][state.primaryArmyId().toString()][15].asText(),
        )
        channel.finishAndReleaseAll()
    }

    private fun newChannel(): EmbeddedChannel =
        EmbeddedChannel(GameServerHandler()).also { channel ->
            ReferenceCountUtil.release(assertIs<ByteBuf>(channel.readOutbound<Any>()))
        }

    private fun upPacket(cmdId: Int, json: String, userId: Int): UpPacket {
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

    private fun platformLogin(channel: EmbeddedChannel, sdkUid: String) {
        val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
        channel.writeInbound(
            upPacket(
                cmdId = Cmd.SYS_PLATFORM_LOGIN_CHECK,
                json = """["{\"sdkuid\":\"$sdkUid\"}",0,"",0]""",
                userId = session.wireUserId,
            ),
        )
        assertEquals(
            Cmd.SYS_PLATFORM_LOGIN_CHECK,
            assertIs<DownPacket>(channel.readOutbound<Any>()).cmd,
        )
    }
}
