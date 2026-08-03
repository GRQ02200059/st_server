package com.stzb.server.handler

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.auth.AccountIdentity
import com.stzb.server.auth.AccountIdentityResolver
import com.stzb.server.auth.ServerSessionRegistry
import com.stzb.server.game.ArmyBattleRequestParser
import com.stzb.server.game.ArmyFacadeOperationRequestParser
import com.stzb.server.game.ConscriptRequestParser
import com.stzb.server.game.ClientCardPackCatalog
import com.stzb.server.game.CityFacadeOperationRequestParser
import com.stzb.server.game.GearOperationRequestParser
import com.stzb.server.game.GameResponses
import com.stzb.server.game.MailBriefInfoResponses
import com.stzb.server.game.MailInfoResponses
import com.stzb.server.game.PlayerBattleService
import com.stzb.server.game.PlayerConscriptService
import com.stzb.server.game.PlayerStateRepository
import com.stzb.server.game.ProfileResponses
import com.stzb.server.game.RankListResponses
import com.stzb.server.game.RecruitResultParser
import com.stzb.server.game.RevenueService
import com.stzb.server.game.SkillOperationRequestParser
import com.stzb.server.game.TeamRequestParser
import com.stzb.server.game.UnionOfficialListResponses
import com.stzb.server.game.UnionStateRepository
import com.stzb.server.game.UserHeadIconResponses
import com.stzb.server.game.WorldChatRecord
import com.stzb.server.game.WorldChatStore
import com.stzb.server.game.WorldProjection
import com.stzb.server.game.WorldStateRepository
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.protocol.Cmd
import com.stzb.server.protocol.CommandContractCatalog
import com.stzb.server.protocol.CommandStatus
import com.stzb.server.protocol.DownPacket
import com.stzb.server.protocol.DownType
import com.stzb.server.protocol.GameServerConfig
import com.stzb.server.protocol.NetworkResponsePolicy
import com.stzb.server.protocol.SysPackets
import com.stzb.server.protocol.UpPacket
import com.stzb.server.session.Session
import com.stzb.server.session.OnlineSessionRegistry
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.AttributeKey
import io.netty.util.concurrent.ScheduledFuture
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1 核心处理器 (离线推演路线):
 *  - 连接建立: 分配会话并下发 98888 握手
 *  - 20003 / 98702: 下发服务器列表 (让客户端选服并回连本服)
 *  - 99991: 下发登录成功响应 + 最小存档 UserInitTable, 让客户端进主城
 *  - 心跳 90003 / ACK 90009 / 校验 90008: 保持连接
 *  - 其它 cmd: 仅记录 (待后续实现)
 */
class GameServerHandler : SimpleChannelInboundHandler<UpPacket>() {

    override fun channelActive(ctx: ChannelHandlerContext) {
        val session = Session.create()
        ctx.channel().attr(SESSION).set(session)
        log.info("[+] 连接建立 ${ctx.channel().remoteAddress()}, 分配 wireUserId=${session.wireUserId}, 下发 98888 握手")
        SysPackets.writeNotifySid(ctx.channel(), session.wireUserId, session.cmdIndex.get(), session.sid)
        armKeepAlive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        cancelKeepAlive(ctx)
        ctx.channel().attr(SESSION).get()?.accountKey?.let { accountKey ->
            onlineSessions.remove(accountKey, ctx.channel())
        }
        log.info("[-] 连接断开 ${ctx.channel().remoteAddress()}")
    }

    /**
     * 客户端 socket 层有 3s "recv-timeout" 看门狗 (MAX_RECEIVE_DATA_TIMEOUT):
     * 连上后若 3s 内没收到任何下行包就主动关连接重连 (见 DotnetBaseSocket.CheckRecvDataTimeout)。
     * 而握手用的 98888 会重新 "武装" 看门狗 (SetLastSendTimeWhenZero), 只有其它下行包能真正解除。
     * 因此这里在连接期内每 KEEP_ALIVE_MS 补发一个空包 (PackageReceivedComplex) 喂狗,
     * 让 socket 存活足够久, 登录 FSM (Youth->Classic->99992) 才能推进。
     */
    private fun armKeepAlive(ctx: ChannelHandlerContext) {
        val task = ctx.channel().eventLoop().scheduleAtFixedRate({
            if (ctx.channel().isActive) {
                SysPackets.writeComplex(ctx.channel(), Cmd.SYS_HEART_BEAT)
            }
        }, KEEP_ALIVE_MS, KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
        ctx.channel().attr(KEEP_ALIVE).set(task)
    }

    private fun cancelKeepAlive(ctx: ChannelHandlerContext) {
        ctx.channel().attr(KEEP_ALIVE).getAndSet(null)?.cancel(false)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: UpPacket) {
        val session = ctx.channel().attr(SESSION).get()
        session?.lastRecvTime = System.currentTimeMillis()

        when (msg.cmdId) {
            Cmd.SYS_HEART_BEAT -> log.debug("♥ 心跳 90003 (uid=${msg.userId})")
            Cmd.SYS_ACKNOWLEDGE -> log.debug("✓ ACK 90009")
            Cmd.SYS_CHECK_SID -> {
                log.info("↻ 校验 SID 90008 (uid=${msg.userId})")
                SysPackets.writeComplex(ctx.channel(), Cmd.SYS_CHECK_SID)
            }

            Cmd.SYS_PLATFORM_LOGIN_CHECK -> {
                logIn(msg)
                sendPlatformLoginCheck(ctx, session, msg)
            }

            Cmd.GET_ALL_SERVER_INFO_NEW,
            Cmd.GET_CLASSIC_AND_YOUTH_SERVER_LIST -> {
                logIn(msg)
                sendServerList(ctx, msg.cmdId)
            }

            Cmd.SYS_PRE_SERVER_TOKEN_CHECK -> {
                logIn(msg)
                sendPreServerTokenCheck(ctx)
            }

            Cmd.SYS_LOGIN -> {
                logIn(msg)
                sendLoginSuccess(ctx, session, msg)
            }

            Cmd.RANDOM_ROLE_NAME -> {
                logIn(msg)
                sendRandomRoleName(ctx)
            }

            Cmd.CREATE_ROLE -> {
                logIn(msg)
                sendCreateRoleSuccess(ctx, session, msg)
            }

            Cmd.GET_SERVER_TIME -> {
                logIn(msg)
                sendServerTime(ctx)
            }

            Cmd.SYNC_SERVER_TIME -> {
                logIn(msg)
                sendServerTimeMillis(ctx)
            }

            Cmd.LOG_FPS -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.LOG_FPS, "null", dataType = DownType.PLAIN))
            }

            Cmd.SEND_ACSDK_CHEAT_INFO -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.SEND_ACSDK_CHEAT_INFO, "true", dataType = DownType.PLAIN))
            }

            Cmd.USER_CLOSE_UI -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.USER_CLOSE_UI, "null", dataType = DownType.PLAIN))
            }

            Cmd.USER_OPEN_UI -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.USER_OPEN_UI, "null", dataType = DownType.PLAIN))
            }

            Cmd.LOG_MUSIC_OPEN -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.LOG_MUSIC_OPEN, "true", dataType = DownType.PLAIN))
            }

            Cmd.RESFILE_LOG_HUB_RECORD -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.RESFILE_LOG_HUB_RECORD, "null", dataType = DownType.PLAIN))
            }

            Cmd.DAILY_REPORT_LOG -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.DAILY_REPORT_LOG, "null", dataType = DownType.PLAIN))
            }

            Cmd.CCLIVE_MAIN_BTN_OPEN_LOG,
            Cmd.LOG_SHIELD_WORDS,
            Cmd.FEED_CLICKED_LOG,
            Cmd.ACTIVITY_SCENE_DIALOG_LOG,
            Cmd.ANNIVERSARY_COMPETITION_FOR_LOG,
            Cmd.TRIAL_SAND_TABLE_LOG,
            Cmd.REPORT_NEWBIE_GUIDE -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "null", dataType = DownType.PLAIN))
            }

            Cmd.HELP_GUIDE_TIPS_LOG -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.HELP_GUIDE_TIPS_LOG, "200", dataType = DownType.PLAIN))
            }

            Cmd.UPDATE_GUIDE_RECORD -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.UPDATE_GUIDE_RECORD, "200", dataType = DownType.PLAIN))
            }

            Cmd.CHECK_HAVE_UNION_TO_JOIN -> {
                sendUnionJoinEligibility(ctx, session)
            }

            Cmd.SET_CHANNEL_CERTIFICATION -> {
                sendChannelCertificationRejection(ctx)
            }

            Cmd.PHONE_BIND_SEND_VERIFY_CODE,
            Cmd.PHONE_BIND_CHECK_VERIFY_CODE -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, """"false"""", dataType = DownType.PLAIN))
            }

            Cmd.PHONE_UNBIND,
            Cmd.GET_WHICH_CHANNEL_SERVER -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "2", dataType = DownType.PLAIN))
            }

            Cmd.DMM_ACCOUNT_CHECK -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "[0]", dataType = DownType.PLAIN))
            }

            Cmd.PRE_SERVER_QUERY_S2_RETRUN_ROLE_INFO -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "null", dataType = DownType.PLAIN))
            }

            Cmd.PRE_SERVER_QUERY_ADVERTISEMENT_SIGN,
            Cmd.GET_CHANNEL_TRANSFER_TOKEN -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "{}", dataType = DownType.PLAIN))
            }

            Cmd.REALNAME_LOGOUT -> {
                sendRealNameLogout(ctx)
            }

            Cmd.FILE_PICKER_GET_TOKEN_DEFAULT,
            Cmd.CHECK_ADD_WEIXIN,
            Cmd.YOUTH_INK_MAP_TIPS -> {
                sendExternalServiceAndLoginFlagsResponse(ctx, msg.cmdId)
            }

            Cmd.XUANFUQIU_RECEIVED_MSG,
            Cmd.GAME_CHENGXIANGGE_RECEIVED -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "null", dataType = DownType.PLAIN))
            }

            Cmd.SOLDIER_GIFT_ACTIVATE -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "[]", dataType = DownType.PLAIN))
            }

            Cmd.BLACK_MARKET_REFRESH_AUTO,
            Cmd.PATORL_GET,
            Cmd.PATORL_HANDLE -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "null", dataType = DownType.PLAIN))
            }

            Cmd.PATORL_REWARD_GET -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "[0,[]]", dataType = DownType.PLAIN))
            }

            Cmd.CCLIVE_GET_FOLLOW_LIST -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "[0,0,[]]", dataType = DownType.PLAIN))
            }

            Cmd.FIRST_STATE_COOUPY_MSG,
            Cmd.UNION_RELATION_FULL_REQUEST -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "null", dataType = DownType.PLAIN))
            }

            Cmd.UNION_STATION_ENTER_SCENE -> {
                sendMultiplexedUnionStationResponse(ctx, msg.bodyText)
            }

            Cmd.SWITCH_ROLE_QUERY_ROLE_LIST,
            Cmd.MAIL_INBOX,
            Cmd.MAIL_GET_CONTACTS,
            Cmd.USER_GET_SEASON_COURSE_LIST,
            Cmd.CHAT_GET_ZHAO_XIAN_MSG,
            Cmd.PROGRESS_GET_INFO,
            Cmd.MAIL_NOTIFY_GET_ALL -> {
                sendReadOnlyEmptyList(ctx, msg.cmdId)
            }

            Cmd.UNION_APPLICANT_LIST,
            Cmd.CHAT_GET_SAND_TABLE_ROOM_MSG,
            Cmd.FRIEND_SEARCH,
            Cmd.UNION_SEARCH_UNION_LIST,
            Cmd.UNION_SEARCH_PLAYER_LIST -> {
                sendReadOnlyEmptyList(ctx, msg.cmdId)
            }

            Cmd.SEARCH_USER -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "[0]", dataType = DownType.PLAIN))
            }

            Cmd.FAMILY_PRAY_RESULT_LIST,
            Cmd.FAMILY_MINI_GAME_GET_SCORE_LIST,
            Cmd.FAMILY_MINI_GAME_GET_ROOM_LIST,
            Cmd.QUERY_OTHER_REGION_CLAN_LIST -> {
                sendReadOnlyEmptyList(ctx, msg.cmdId)
            }

            Cmd.GET_USER_RES_WID_LEVEL_MAP -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "{}", dataType = DownType.PLAIN))
            }

            Cmd.CLAN_SEARCH_CLAN_LIST -> {
                sendClanSearchClanList(ctx, msg)
            }

            Cmd.CLAN_LOG_GET -> {
                sendClanLogGet(ctx, msg)
            }

            Cmd.CHAT_GET_FIGHT_AREA_CHAT -> {
                sendBattlefieldChatHistory(ctx, msg)
            }

            Cmd.CHAT_UNION_PLAN_HISTORY -> {
                sendUnionPlanChatHistory(ctx, msg)
            }

            Cmd.INVITATIONAL_QUERY_LOG -> {
                sendInvitationalTeamLog(ctx, msg)
            }

            Cmd.UNION_MEMBER_CLAN_LIST,
            Cmd.GET_INVITE_LIST,
            Cmd.GET_ZHAOHUI_LIST,
            Cmd.CHAT_GET_CITY_HISTORY,
            Cmd.UNION_LEADER_CLAN_CITY_LIST,
            Cmd.CLAN_APPLICANT_LIST,
            Cmd.CLAN_NEARBY_NEARBY_PLAYER_LIST,
            Cmd.CLAN_NEARBY_CLAN_LIST,
            Cmd.CLAN_NPC_CITY_LIST,
            Cmd.CLAN_GET_CONTRIBUTION_LIST,
            Cmd.CLAN_GET_JUNXIAN_LIST,
            Cmd.CLAN_SUPREME_LIST,
            Cmd.GET_NZ_EFFECT_LAND_LIST,
            Cmd.GET_FIELD_RES_TOTAL_STORE,
            Cmd.QUERY_USER_MARKET_SCORE -> {
                sendStrictEmptyQueryProjection(ctx, msg)
            }

            Cmd.SUMMER_FARM_GET_USER_LIST -> {
                sendSummerFarmUserList(ctx, msg)
            }

            Cmd.SUMMER_FARM_MESSAGE_RECORD,
            Cmd.SUMMER_FARM_VISIT_RECORD -> {
                sendSummerFarmRecordQuery(ctx, msg)
            }

            Cmd.GET_UNION_LETTER -> {
                sendUnionLetterQuery(ctx, msg)
            }

            Cmd.NOBILITY_TITLE_QUERY_EIGHT_OFFICER_RECORD -> {
                sendNobilityOfficerRecord(ctx, msg)
            }

            Cmd.UNION_NPC_CITY_LIST,
            Cmd.CHAT_UNION_PLAN_HISTORY_ID,
            Cmd.COMMAND_PLAN_GEL_UNION_TEMP_GROUP_MEMBER,
            Cmd.ARMY_REINFORCE_STAY_CHECK,
            Cmd.UNION_STATION_GET_DATA,
            Cmd.UNION_STATION_ALL_RECORDS -> {
                sendReadOnlyUnionArmyStationResponse(ctx, msg.cmdId)
            }

            Cmd.GET_UNION_BATTLE_REPORT,
            Cmd.MAIL_OUTBOX,
            Cmd.GET_BLACK_LIST,
            Cmd.NOTICE_LIST,
            Cmd.FRIEND_GROUP_GET_HISTORY_CHAT,
            Cmd.QUERY_WANTED_TO_REPOTR,
            Cmd.STRATEGY_HELP_GET,
            Cmd.COMMAND_PLAN_GET_UNION_TEMP_GROUP,
            Cmd.UNION_STATION_PLAYER_DANMU_LIST_GET -> {
                ctx.writeAndFlush(DownPacket.json(msg.cmdId, "[]", dataType = DownType.PLAIN))
            }

            Cmd.GET_USER_SEASON_RECORD -> {
                sendUserSeasonRecord(ctx)
            }

            Cmd.GET_SEASON_HISTROY_PARAMS -> {
                sendSeasonHistoryParams(ctx)
            }

            Cmd.CARD_RECORD -> {
                sendCardRecord(ctx, session)
            }

            Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE -> {
                sendCustomerServiceTokenRejection(ctx)
            }

            Cmd.PRE_SERVER_QUERY_USER_OP -> {
                sendPreServerQueryUserOp(ctx, msg)
            }

            Cmd.PRE_SERVER_GEN_H5_SIGN -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.PRE_SERVER_GEN_H5_SIGN, "\"\"", dataType = DownType.PLAIN))
            }

            Cmd.QUERY_NEW_COMMUNITY_INFO -> {
                ctx.writeAndFlush(
                    DownPacket.json(Cmd.QUERY_NEW_COMMUNITY_INFO, """[0,"",{},[],""]""", dataType = DownType.PLAIN),
                )
            }

            Cmd.QUERY_SIMULATE_TOKEN -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.QUERY_SIMULATE_TOKEN, "[0]", dataType = DownType.PLAIN))
            }

            Cmd.IP_USER_COUNT_PRE -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.IP_USER_COUNT_PRE, "[0,0]", dataType = DownType.PLAIN))
            }

            Cmd.BATTLE_REPORT_PROFILE -> {
                logIn(msg)
                sendBattleReportProfile(ctx, session, msg)
            }

            Cmd.MAIL_INFO -> {
                logIn(msg)
                sendMailInfo(ctx, msg)
            }

            Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID -> {
                logIn(msg)
                sendMailBriefInfo(ctx, msg)
            }

            Cmd.GET_PREBOOK_SERVER_INFO -> {
                logIn(msg)
                sendPrebookServerInfo(ctx)
            }

            Cmd.COMMUNITY_GET_USER_TOKEN -> {
                logIn(msg)
                sendCommunityUserTokenRejection(ctx)
            }

            Cmd.UNION_CREATE -> {
                logIn(msg)
                sendCreateUnion(ctx, session, msg)
            }

            Cmd.UNION_INFO -> {
                logIn(msg)
                sendUnionInfo(ctx, msg)
            }

            Cmd.UNION_MEMBER -> {
                logIn(msg)
                sendUnionMembers(ctx, session, msg)
            }

            Cmd.UNION_OFFICIAL_LIST -> {
                logIn(msg)
                sendUnionOfficialList(ctx, msg)
            }

            Cmd.UNION_NEARBY_PLAYER_LIST -> {
                ctx.writeAndFlush(DownPacket.json(Cmd.UNION_NEARBY_PLAYER_LIST, "[]", dataType = DownType.PLAIN))
            }

            Cmd.UNION_GET_ALL_MEMBER_LIST_FOR_CHAT -> {
                logIn(msg)
                sendUnionChatMembers(ctx, session, msg)
            }

            Cmd.UNION_GET_GROUP_LIST -> {
                sendUnionGroupList(ctx)
            }

            Cmd.DAILY_REPORT_GET_DETAIL -> {
                sendDailyReportDetail(ctx, msg)
            }

            Cmd.GET_HERO_RECOMMEND_2 -> {
                sendHeroRecommendation(ctx, msg)
            }

            Cmd.GET_UDS_GUESS_SEASON -> {
                sendSeasonRecommendations(ctx, msg)
            }

            Cmd.RANK_LIST -> {
                logIn(msg)
                sendRankList(ctx, session, msg)
            }

            Cmd.OWN_RANK,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX,
            Cmd.FENGLU_LEVEL_STATUS -> {
                sendWorldRankDomesticStatus(ctx, msg)
            }

            Cmd.REVENUE -> {
                sendRevenue(ctx, session, msg)
            }

            Cmd.REVENUE_DOUBLE -> {
                sendDoubleRevenue(ctx, session, msg)
            }

            Cmd.WORLD_BOSS_TOP_THREE_RANK -> {
                sendWorldBossTopThreeRank(ctx, session)
            }

            Cmd.USER_GET_USERS_HEADICON -> {
                logIn(msg)
                sendUserHeadIcons(ctx, msg)
            }

            Cmd.GET_HOMEPAGE_INFO -> {
                logIn(msg)
                sendHomepageInfo(ctx, session, msg)
            }

            Cmd.CHAT -> {
                logIn(msg)
                sendChat(ctx, session, msg)
            }

            Cmd.CHAT_HISTORY -> {
                logIn(msg)
                sendChatHistory(ctx)
            }

            Cmd.ARMY_BATTLE -> {
                logIn(msg)
                sendArmyBattle(ctx, session, msg)
            }

            Cmd.BATTLE_REPORT_DETAIL,
            Cmd.BATTLE_REPORT_SHORT_DETAIL -> {
                logIn(msg)
                sendBattleReportDetail(ctx, session, msg)
            }

            Cmd.BUILD_BUILDING,
            Cmd.UPGRADE_BUILDING -> {
                logIn(msg)
                sendBuildingUpgrade(ctx, session, msg)
            }

            Cmd.LAND_INFO -> {
                logIn(msg)
                sendLandInfo(ctx, msg)
            }

            Cmd.GET_USER_NPC_ARMY -> {
                logIn(msg)
                sendUserNpcArmy(ctx, msg)
            }

            Cmd.GET_LAND_NPC_ARMY -> {
                logIn(msg)
                sendLandNpcArmy(ctx, msg)
            }

            Cmd.GET_LAND_DEFEND_ARMY -> {
                logIn(msg)
                sendLandDefenderArmy(ctx, msg)
            }

            Cmd.ADD_HERO_TO_ARMY -> {
                logIn(msg)
                sendAddHeroToArmy(ctx, session, msg)
            }

            Cmd.REMOVE_HERO_FROM_ARMY -> {
                logIn(msg)
                sendRemoveHeroFromArmy(ctx, session, msg)
            }

            Cmd.SWITCH_HERO_IN_ARMY -> {
                logIn(msg)
                sendSwitchHeroInArmy(ctx, session, msg)
            }

            Cmd.CONSCRIPT,
            Cmd.CONSCRIPT_IMMEDIATELY -> {
                logIn(msg)
                sendConscript(ctx, session, msg)
            }

            Cmd.LEARN_HERO_SKILL,
            Cmd.REPLACE_HERO_SKILL -> {
                logIn(msg)
                sendLearnHeroSkill(ctx, session, msg)
            }

            Cmd.FORGET_HERO_SKILL -> {
                logIn(msg)
                sendForgetHeroSkill(ctx, session, msg)
            }

            Cmd.REMOVE_USER_SKILL -> {
                logIn(msg)
                sendNoOpSuccess(ctx, msg)
            }

            Cmd.CARD_RECRUIT -> {
                logIn(msg)
                sendCardRecruit(ctx, session, msg)
            }

            Cmd.CARD_QUICK_RECRUIT -> {
                logIn(msg)
                sendQuickCardRecruit(ctx, session, msg)
            }

            Cmd.CARD_SET_ALL_NOT_NEW -> {
                logIn(msg)
                sendCardSetAllNotNew(ctx, session, msg)
            }

            Cmd.HERO_SELECT_FACADE -> {
                logIn(msg)
                sendSelectHeroFacade(ctx, session, msg)
            }

            Cmd.HERO_USE_CARD_BORDER,
            Cmd.ROTATE_CARD_BORDER_ADD,
            Cmd.ROTATE_CARD_BORDER_REMOVE -> {
                logIn(msg)
                sendNoOpSuccess(ctx, msg)
            }

            Cmd.HERO_ACTIVE_CARD_BORDER -> {
                logIn(msg)
                sendSelectHeroCardBorder(ctx, session, msg)
            }

            Cmd.BUILD_FACADE_APPLY_BUILD_SCHEME -> {
                logIn(msg)
                sendApplyCityFacadeScheme(ctx, session, msg)
            }

            Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
            Cmd.UNLOCK_TROOP_FACADE_CARD,
            Cmd.USE_TROOP_FACADE_CARD,
            Cmd.HERO_ACTIVE_FACADE -> {
                logIn(msg)
                sendArmyFacadeOperation(ctx, session, msg)
            }

            Cmd.HERO_ADVANCE -> {
                logIn(msg)
                sendHeroAdvance(ctx, session, msg)
            }

            Cmd.GEAR_EQUIP,
            Cmd.GEAR_FORGET -> {
                logIn(msg)
                sendGearOperation(ctx, session, msg)
            }

            Cmd.CARD_ADD_POINT,
            Cmd.CARD_WASH_POINT,
            Cmd.CARD_PROTECT,
            Cmd.CARD_SAVE_POINT_PLAN,
            Cmd.CARD_CHANGE_POINT_PLAN,
            Cmd.CARD_EXTRACT_SWITCH,
            Cmd.CARD_SELECT_HERO -> {
                logIn(msg)
                sendCardOperationSuccess(ctx, msg)
            }

            Cmd.GET_WORLD_SCENCE_INFO -> {
                logIn(msg)
                sendRecordedAcknowledgement(ctx, msg)
                sendWorldSceneFullInfo(ctx, session)
            }

            Cmd.SYS_PING -> {
                logIn(msg)
                sendDevicePing(ctx)
            }

            Cmd.QUERY_ARMY_RELATED_FORT -> {
                logIn(msg)
                sendArmyRelatedFort(ctx)
            }

            Cmd.SET_CLIENT_RED_DOT_DATA -> {
                logIn(msg)
                sendRecordedAcknowledgement(ctx, msg)
            }

            Cmd.SET_FRONT_UNLOCK_ANIM -> {
                logIn(msg)
                sendNoOpSuccess(ctx, msg)
            }

            Cmd.USER_CHANGE_NAME -> {
                logIn(msg)
                sendChangeNameSuccess(ctx, session, msg)
            }

            Cmd.HERO_TEAM_LIBRARY -> {
                logIn(msg)
                sendHeroTeamLibrary(ctx)
            }

            Cmd.NORMAL_TEAM_COMPOSITION -> {
                logIn(msg)
                sendNormalTeamComposition(ctx, msg)
            }

            Cmd.WORLD_BOSS_SAVE_TEAM,
            Cmd.EXERCISE_DAILY_SAVE_TEAM -> {
                logIn(msg)
                saveTeamConfig(ctx, session, msg)
            }

            else -> {
                logUnhandledOrFallback(ctx, msg)
            }
        }
    }

    private fun sendUserSeasonRecord(ctx: ChannelHandlerContext) {
        val innerResponse = mapper.writeValueAsString(
            linkedMapOf<String, Any?>(
                "data" to emptyList<Any>(),
                "success" to true,
                "succeed" to true,
                "sourceHost" to "",
                "reqTiming" to null,
                "status" to 200,
                "statusCode" to 200,
                "reqId" to "",
            ),
        )
        val response = mapper.writeValueAsString(innerResponse)
        ctx.writeAndFlush(
            DownPacket.json(Cmd.GET_USER_SEASON_RECORD, response, dataType = DownType.PLAIN),
        )
    }

    private fun sendSeasonHistoryParams(ctx: ChannelHandlerContext) {
        val response = mapper.writeValueAsString(emptyMap<String, Any?>())
        ctx.writeAndFlush(
            DownPacket.json(Cmd.GET_SEASON_HISTROY_PARAMS, response, dataType = DownType.PLAIN),
        )
    }

    private fun sendCardRecord(ctx: ChannelHandlerContext, session: Session?) {
        val playerId = session?.playerId
        val recordText = session?.accountKey
            ?.takeIf { playerId != null }
            ?.let(PlayerStateRepository::findExisting)
            ?.takeIf { state -> state.userId == playerId }
            ?.allHeroes()
            ?.asSequence()
            ?.filterNot { hero -> hero.isAdvanceMaterial }
            ?.groupBy { hero -> hero.heroId }
            ?.mapValues { (_, heroes) -> heroes.minOf { hero -> hero.createdAtSec } }
            ?.toSortedMap()
            ?.entries
            ?.joinToString(separator = ";") { (heroId, createdAtSec) ->
                "$heroId,$createdAtSec"
            }
            .orEmpty()
        val response = mapper.writeValueAsString(recordText)
        ctx.writeAndFlush(
            DownPacket.json(Cmd.CARD_RECORD, response, dataType = DownType.PLAIN),
        )
    }

    private fun sendRevenue(
        ctx: ChannelHandlerContext,
        session: Session?,
        msg: UpPacket,
    ) {
        val validRequest = (
            runCatching { strictRequestMapper.readTree(msg.bodyText) }
                .getOrNull()
                ?.takeIf { it.isArray && it.size() == 1 }
                ?.get(0)
                ?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() == 0 }
        ) != null
        val state = if (validRequest) boundExistingPlayer(session) else null
        val amount = state?.let { RevenueService.collectOrdinary(it, revenueEpochSeconds()) }
        if (state == null || amount == null) {
            writeRevenueScalar(ctx, Cmd.REVENUE, 0)
            log.info(">> cmd=${Cmd.REVENUE} 本地税收请求已拒绝")
            return
        }

        PlayerStateRepository.save(state)
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.ordinaryRevenueUpdateNotify(state),
                dataType = DownType.PLAIN,
            ),
        )
        writeRevenueScalar(ctx, Cmd.REVENUE, 0)
        log.info(">> cmd=${Cmd.REVENUE} 本地税收已领取")
    }

    private fun sendDoubleRevenue(
        ctx: ChannelHandlerContext,
        session: Session?,
        msg: UpPacket,
    ) {
        val giftIndex = runCatching { strictRequestMapper.readTree(msg.bodyText) }
            .getOrNull()
            ?.takeIf { it.isArray && it.size() == 1 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
        val state = if (giftIndex != null) boundExistingPlayer(session) else null
        val amount = if (state != null && giftIndex != null) {
            RevenueService.claimDouble(state, giftIndex)
        } else {
            null
        }
        if (state == null || amount == null) {
            writeRevenueScalar(ctx, Cmd.REVENUE_DOUBLE, 0)
            log.info(">> cmd=${Cmd.REVENUE_DOUBLE} 本地双倍税收请求已拒绝")
            return
        }

        PlayerStateRepository.save(state)
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.doubleRevenueUpdateNotify(state),
                dataType = DownType.PLAIN,
            ),
        )
        writeRevenueScalar(ctx, Cmd.REVENUE_DOUBLE, amount)
        log.info(">> cmd=${Cmd.REVENUE_DOUBLE} 本地双倍税收已领取")
    }

    private fun boundExistingPlayer(session: Session?): com.stzb.server.game.PlayerState? {
        val accountKey = session?.accountKey ?: return null
        val playerId = session.playerId ?: return null
        return PlayerStateRepository.findExisting(accountKey)
            ?.takeIf { state -> state.userId == playerId }
    }

    private fun writeRevenueScalar(
        ctx: ChannelHandlerContext,
        cmd: Int,
        amount: Int,
    ) {
        ctx.writeAndFlush(
            DownPacket.json(cmd, amount.toString(), dataType = DownType.PLAIN),
        )
    }

    private fun sendCustomerServiceTokenRejection(ctx: ChannelHandlerContext) {
        val response = mapper.writeValueAsString("")
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.USER_GET_CUSTOMER_SERVICE_TOKEN_PRE,
                response,
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendUnionJoinEligibility(ctx: ChannelHandlerContext, session: Session?) {
        val isEligible = session?.playerId
            ?.let { playerId -> UnionStateRepository.forUser(playerId) == null }
            ?: true
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.CHECK_HAVE_UNION_TO_JOIN,
                isEligible.toString(),
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendChannelCertificationRejection(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SET_CHANNEL_CERTIFICATION,
                "false",
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendRealNameLogout(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.REALNAME_LOGOUT,
                "true",
                dataType = DownType.PLAIN,
            ),
        ).addListener(ChannelFutureListener.CLOSE)
    }

    private fun sendExternalServiceAndLoginFlagsResponse(
        ctx: ChannelHandlerContext,
        cmdId: Int,
    ) {
        val response: Any = when (cmdId) {
            Cmd.FILE_PICKER_GET_TOKEN_DEFAULT -> listOf("", "")
            Cmd.CHECK_ADD_WEIXIN -> listOf(false, emptyList<Any>())
            Cmd.YOUTH_INK_MAP_TIPS -> listOf(0, 0)
            else -> error("unsupported external/login response cmd: $cmdId")
        }
        ctx.writeAndFlush(
            DownPacket.json(
                cmdId,
                mapper.writeValueAsString(response),
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendReadOnlyUnionArmyStationResponse(
        ctx: ChannelHandlerContext,
        cmdId: Int,
    ) {
        val response = when (cmdId) {
            Cmd.UNION_NPC_CITY_LIST -> "[[],{},{},{},{}]"
            Cmd.CHAT_UNION_PLAN_HISTORY_ID,
            Cmd.COMMAND_PLAN_GEL_UNION_TEMP_GROUP_MEMBER,
            Cmd.ARMY_REINFORCE_STAY_CHECK -> "{}"
            Cmd.UNION_STATION_GET_DATA,
            Cmd.UNION_STATION_ALL_RECORDS -> "[[]]"
            else -> error("unsupported read-only union/army/station cmd: $cmdId")
        }
        ctx.writeAndFlush(DownPacket.json(cmdId, response, dataType = DownType.PLAIN))
    }

    private fun sendMultiplexedUnionStationResponse(
        ctx: ChannelHandlerContext,
        requestBody: String,
    ) {
        val request = runCatching { strictRequestMapper.readTree(requestBody) }.getOrNull()
        val isSceneControl = request
            ?.takeIf { it.isArray && it.size() == 1 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?.let { it == 1 || it == 2 }
            ?: false
        val response = if (isSceneControl) "[0]" else "[]"
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.UNION_STATION_ENTER_SCENE,
                response,
                dataType = DownType.PLAIN,
            ),
        )
    }

    /**
     * 99992 establishes the stable player identity before the client opens its
     * game-server session. 98888's user id remains wire-only.
     */
    private fun sendPlatformLoginCheck(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val identity = AccountIdentityResolver.fromPlatformLoginRequest(msg.bodyText) ?: run {
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_PLATFORM_LOGIN_CHECK,
                    GameResponses.platformLoginFailure(),
                    dataType = DownType.PLAIN,
                ),
            )
            log.warn(">> cmd=99992 平台校验拒绝: 缺少 sdkuid/userid")
            return
        }
        val state = bindAccount(ctx, session, identity)
        if (state == null) {
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_PLATFORM_LOGIN_CHECK,
                    GameResponses.platformLoginFailure(),
                    dataType = DownType.PLAIN,
                ),
            )
            log.warn(">> cmd=99992 平台校验拒绝: 缺少 sdkuid/userid")
            return
        }

        val serverSession = serverSessions.issue(identity)
        val json = GameResponses.platformLoginCheck(state.userId, serverSession)
        ctx.writeAndFlush(DownPacket.json(Cmd.SYS_PLATFORM_LOGIN_CHECK, json, dataType = DownType.PLAIN))
        log.info(">> cmd=99992 平台校验已下发 (uid=${state.userId}, account=${identity.displayId}, ServerSession=$serverSession)")
    }

    private fun sendPreServerTokenCheck(ctx: ChannelHandlerContext) {
        val json = GameResponses.preServerTokenCheck()
        ctx.writeAndFlush(DownPacket.json(Cmd.SYS_PRE_SERVER_TOKEN_CHECK, json, dataType = DownType.PLAIN))
        log.info(">> cmd=99994 预登录校验已下发")
    }

    private fun sendPreServerQueryUserOp(ctx: ChannelHandlerContext, msg: UpPacket) {
        val requestedUserId = runCatching { strictRequestMapper.readTree(msg.bodyText) }
            .getOrNull()
            ?.takeIf { it.isArray && it.size() > 0 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?: 0
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.PRE_SERVER_QUERY_USER_OP,
                "[0,$requestedUserId,[]]",
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendPrebookServerInfo(ctx: ChannelHandlerContext) {
        val json = GameResponses.prebookServerInfo()
        ctx.writeAndFlush(DownPacket.json(Cmd.GET_PREBOOK_SERVER_INFO, json, dataType = DownType.PLAIN))
        log.info(">> cmd=40008 预预约服务器信息已下发")
    }

    private fun sendCommunityUserTokenRejection(ctx: ChannelHandlerContext) {
        val json = GameResponses.communityUserTokenRejection()
        ctx.writeAndFlush(DownPacket.json(Cmd.COMMUNITY_GET_USER_TOKEN, json, dataType = DownType.PLAIN))
        log.info(">> cmd=1436 社区凭证请求已本地拒绝")
    }

    /** 下发 20003/98702 服务器列表; 广播的 host:port 即本进程监听地址。 */
    private fun sendServerList(ctx: ChannelHandlerContext, cmd: Int) {
        val localPort = (ctx.channel().localAddress() as? InetSocketAddress)?.port ?: 59979
        val advertisedHost = GameServerConfig.advertisedHost()
        val json = GameResponses.serverList(
            serverId = GameServerConfig.SERVER_ID,
            serverName = GameServerConfig.SERVER_NAME,
            host = advertisedHost,
            port = localPort,
            runServerId = GameServerConfig.RUN_SERVER_ID,
            cfgDbId = GameServerConfig.CFG_DB_ID,
            openTime = GameServerConfig.OPEN_TIME_SEC,
        )
        ctx.writeAndFlush(DownPacket.json(cmd, json, dataType = DownType.PLAIN))
        log.info(">> cmd=$cmd 服务器列表已下发 (host=$advertisedHost:$localPort, ${json.length}B)")
    }

    /** 下发 99991 登录成功 + 最小存档, 让客户端进主城。 */
    private fun sendLoginSuccess(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val state = ensureBoundState(ctx, session, msg) ?: run {
            ctx.writeAndFlush(DownPacket.json(Cmd.SYS_LOGIN, "[0]", dataType = DownType.PLAIN))
            log.warn(">> cmd=99991 登录拒绝: 会话未绑定账号")
            return
        }
        val userId = state.userId
        if (state.ensureAdvanceMaterials().isNotEmpty()) {
            PlayerStateRepository.save(state)
        }
        val accountKey = session?.accountKey
        val nowSec = System.currentTimeMillis() / 1000
        val json = GameResponses.loginSuccess(
            userId = userId,
            cityWid = state.cityWid,
            roleName = state.roleName,
            serverTimeSec = nowSec,
            serverOpenTime = GameServerConfig.OPEN_TIME_SEC,
            cfgDataIndex = GameServerConfig.CFG_DB_ID,
            accountKey = accountKey,
            world = WorldStateRepository.projection(),
        )
        // #region debug-point C:login-npc-cache
        System.getenv("DEBUG_SERVER_URL")?.takeIf { it.isNotBlank() }?.let { debugUrl ->
            java.net.http.HttpClient.newHttpClient().sendAsync(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(debugUrl))
                    .header("Content-Type", "application/json")
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(
                                mapOf(
                                    "sessionId" to (System.getenv("DEBUG_SESSION_ID") ?: "map-defender-mismatch"),
                                    "runId" to "post-fix",
                                    "hypothesisId" to "C",
                                    "ts" to System.currentTimeMillis(),
                                    "location" to "GameServerHandler.sendLoginSuccess",
                                    "msg" to "[DEBUG] login snapshot defender-cache table",
                                    "data" to mapOf(
                                        "userId" to userId,
                                        "npcArmyTablePresent" to json.contains("\"Tb_user_npc_army\",[]"),
                                        "cfgDataIndex" to GameServerConfig.CFG_DB_ID,
                                        "resourceMapId" to GameServerConfig.CFG_DB_ID,
                                    ),
                                ),
                            ),
                        ),
                    )
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding(),
            )
        }
        // #endregion
        ctx.writeAndFlush(DownPacket.json(Cmd.SYS_LOGIN, json, dataType = DownType.PLAIN))
        log.info(">> cmd=99991 登录成功已下发 (uid=$userId, cityWid=${state.cityWid}, ${json.length}B)")
    }

    private fun sendRandomRoleName(ctx: ChannelHandlerContext) {
        val json = GameResponses.randomRoleName()
        ctx.writeAndFlush(DownPacket.json(Cmd.RANDOM_ROLE_NAME, json, dataType = DownType.PLAIN))
        log.info(">> cmd=511 随机角色名已下发")
    }

    private fun sendCreateRoleSuccess(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val roleName = requestedRoleName(msg) ?: GameServerConfig.ROLE_NAME
        val state = playerState(session, userId, GameServerConfig.CITY_WID, roleName)
        state.roleName = roleName
        state.ensureAdvanceMaterials()
        WorldStateRepository.registerOrRestorePlayer(state)
        val json = GameResponses.createRoleSuccess(
            userId = userId,
            cityWid = state.cityWid,
            roleName = roleName,
            serverOpenTime = GameServerConfig.OPEN_TIME_SEC,
            accountKey = session?.accountKey,
            world = WorldStateRepository.projection(),
        )
        ctx.writeAndFlush(DownPacket.json(Cmd.CREATE_ROLE, json, dataType = DownType.PLAIN))
        log.info(">> cmd=2 创角成功已下发 (uid=$userId, roleName=$roleName, ${json.length}B)")
    }

    private fun sendCardRecruit(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val summonCfgId = body?.get(0)?.asInt() ?: 0
        val summonUid = body?.get(1)?.asInt()?.takeIf { it > 0 } ?: 0
        val summonOpType = body?.get(2)?.asInt() ?: 0
        val childCfgId = body?.get(5)?.asInt() ?: 0
        val json = GameResponses.cardRecruit(
            userId = userId,
            summonUid = summonUid,
            summonCfgId = summonCfgId,
            childCfgId = childCfgId,
            summonOpType = summonOpType,
        )
        ctx.writeAndFlush(DownPacket.json(Cmd.CARD_RECRUIT, json, dataType = DownType.PLAIN))
        sendRecruitHeroInsertNotify(ctx, session, userId, Cmd.CARD_RECRUIT, json)
        log.info(">> cmd=301 招募结果已下发 (summonCfgId=$summonCfgId, summonUid=$summonUid, summonOpType=$summonOpType, childCfgId=$childCfgId, ${json.length}B)")
    }

    private fun sendLearnHeroSkill(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val request = SkillOperationRequestParser.parseLearn(msg.bodyText)
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val changed = request?.let {
            state.learnHeroSkill(it.heroUid, it.skillId, it.slotIndex)
        } == true
        ctx.writeAndFlush(
            DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN),
        )
        if (changed) {
            PlayerStateRepository.save(state)
            val hero = state.hero(request!!.heroUid)!!
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.heroSkillUpdateNotify(hero),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(
            ">> cmd=${msg.cmdId} 武将学习战法已处理 " +
                "(heroUid=${request?.heroUid ?: 0}, skillId=${request?.skillId ?: 0}, " +
                "slot=${request?.slotIndex ?: 0}, changed=$changed)",
        )
    }

    private fun sendForgetHeroSkill(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val request = SkillOperationRequestParser.parseForget(msg.bodyText)
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val changed = request?.let {
            state.forgetHeroSkill(it.heroUid, it.skillId)
        } == true
        ctx.writeAndFlush(
            DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN),
        )
        if (changed) {
            PlayerStateRepository.save(state)
            val hero = state.hero(request!!.heroUid)!!
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.heroSkillUpdateNotify(hero),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(
            ">> cmd=${msg.cmdId} 武将遗忘战法已处理 " +
                "(heroUid=${request?.heroUid ?: 0}, skillId=${request?.skillId ?: 0}, changed=$changed)",
        )
    }

    private fun sendBuildingUpgrade(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val cityWid = body?.get(0)?.asInt()?.takeIf { it > 0 } ?: GameServerConfig.CITY_WID
        val buildId = body?.get(1)?.asInt()?.takeIf { it > 0 } ?: 10
        val targetLevel = body?.get(3)?.asInt() ?: 0
        val state = playerState(session, userId, cityWid)
        val level = state.upgradeBuild(buildId, targetLevel)
        PlayerStateRepository.save(state)
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
        val json = GameResponses.userBuildUpsertNotify(
            state = state,
            buildId = buildId,
            level = level,
            resources = state.resources,
        )
        ctx.writeAndFlush(DownPacket.json(Cmd.SYS_NOTIFY_DB_UPDATE, json, dataType = DownType.PLAIN))
        log.info(">> cmd=${msg.cmdId} 建筑升级已处理 (cityWid=$cityWid, buildId=$buildId, level=$level)")
    }

    private fun sendLandInfo(ctx: ChannelHandlerContext, msg: UpPacket) {
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val coorX = body?.get(0)?.asInt() ?: 0
        val coorY = body?.get(1)?.asInt() ?: 0
        val wid = if (coorX > 0 && coorY > 0) coorX * 10000 + coorY else 0
        ctx.writeAndFlush(DownPacket.json(Cmd.LAND_INFO, GameResponses.landInfo(wid), dataType = DownType.XOR))
        log.info(">> cmd=21 土地详情已应答 (wid=$wid)")
    }

    private fun sendLandNpcArmy(ctx: ChannelHandlerContext, msg: UpPacket) {
        val wid = requestedLandWid(msg)
        val json = GameResponses.landNpcArmy(wid)
        ctx.writeAndFlush(
            DownPacket.json(Cmd.GET_LAND_NPC_ARMY, json, dataType = DownType.XOR),
        )
        log.info(">> cmd=4330 土地守军恢复信息已下发 (wid=$wid)")
    }

    private fun sendUserNpcArmy(ctx: ChannelHandlerContext, msg: UpPacket) {
        val wid = requestedLandWid(msg)
        val json = GameResponses.userNpcArmy(wid)
        // #region debug-point A:map-guard-response
        System.getenv("DEBUG_SERVER_URL")?.takeIf { it.isNotBlank() }?.let { debugUrl ->
            java.net.http.HttpClient.newHttpClient().sendAsync(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(debugUrl))
                    .header("Content-Type", "application/json")
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(
                                mapOf(
                                    "sessionId" to (System.getenv("DEBUG_SESSION_ID") ?: "map-defender-mismatch"),
                                    "runId" to "post-fix",
                                    "hypothesisId" to "A",
                                    "ts" to System.currentTimeMillis(),
                                    "traceId" to "${msg.userId}:${msg.cmdIndex}",
                                    "location" to "GameServerHandler.sendUserNpcArmy",
                                    "msg" to "[DEBUG] map guard request and response",
                                    "data" to mapOf("wid" to wid, "request" to msg.bodyText, "response" to json),
                                ),
                            ),
                        ),
                    )
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding(),
            )
        }
        // #endregion
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.GET_USER_NPC_ARMY,
                json,
                dataType = DownType.XOR,
            ),
        )
        log.info(">> cmd=4329 土地守军编队已下发 (wid=$wid)")
    }

    private fun sendLandDefenderArmy(ctx: ChannelHandlerContext, msg: UpPacket) {
        val wid = requestedLandWid(msg)
        val json = GameResponses.landDefenderArmy(wid)
        // #region debug-point B:defender-detail-response
        System.getenv("DEBUG_SERVER_URL")?.takeIf { it.isNotBlank() }?.let { debugUrl ->
            java.net.http.HttpClient.newHttpClient().sendAsync(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(debugUrl))
                    .header("Content-Type", "application/json")
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(
                                mapOf(
                                    "sessionId" to (System.getenv("DEBUG_SESSION_ID") ?: "map-defender-mismatch"),
                                    "runId" to "post-fix",
                                    "hypothesisId" to "B",
                                    "ts" to System.currentTimeMillis(),
                                    "traceId" to "${msg.userId}:${msg.cmdIndex}",
                                    "location" to "GameServerHandler.sendLandDefenderArmy",
                                    "msg" to "[DEBUG] defender detail request and response",
                                    "data" to mapOf("wid" to wid, "request" to msg.bodyText, "response" to json),
                                ),
                            ),
                        ),
                    )
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding(),
            )
        }
        // #endregion
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.GET_LAND_DEFEND_ARMY,
                json,
                dataType = DownType.XOR,
            ),
        )
        log.info(">> cmd=4331 土地守军详情已下发 (wid=$wid)")
    }

    private fun requestedLandWid(msg: UpPacket): Int {
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        return body?.get(0)?.asInt()?.coerceAtLeast(0) ?: 0
    }

    private fun sendAddHeroToArmy(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val cityWid = body?.get(0)?.asInt()?.takeIf { it > 0 } ?: GameServerConfig.CITY_WID
        val heroUid = body?.get(1)?.asInt() ?: 0
        val requestedArmyId = body?.get(2)?.asInt()?.takeIf { it > 0 }
        val pos = body?.get(3)?.asInt() ?: 1
        val state = playerState(session, userId, cityWid)
        val armyId = requestedArmyId ?: state.primaryArmyId()

        val previousArmyId = state.hero(heroUid)?.armyId?.takeIf { it > 0 }
        state.assignTeamHero(heroUid = heroUid, pos = pos, armyId = armyId)
        PlayerStateRepository.save(state)
        ctx.writeAndFlush(DownPacket.json(Cmd.ADD_HERO_TO_ARMY, mapper.writeValueAsString(listOf(armyId)), dataType = DownType.PLAIN))
        state.hero(heroUid)?.let { hero ->
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.armyAndHeroesUpsertNotify(
                        state,
                        listOf(hero),
                        listOfNotNull(previousArmyId, armyId),
                    ),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(">> cmd=30 武将上阵已处理 (uid=$userId, cityWid=$cityWid, armyId=$armyId, pos=$pos, heroUid=$heroUid)")
    }

    private fun sendRemoveHeroFromArmy(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val cityWid = body?.get(0)?.asInt()?.takeIf { it > 0 } ?: GameServerConfig.CITY_WID
        val armyId = body?.get(1)?.asInt()?.takeIf { it > 0 } ?: cityWid * 10 + 1
        val pos = body?.get(2)?.asInt() ?: 1
        val state = playerState(session, userId, cityWid)
        val removedHero = state.removeTeamHero(pos, armyId).takeIf { it > 0 }?.let(state::hero)
        PlayerStateRepository.save(state)

        ctx.writeAndFlush(DownPacket.json(Cmd.REMOVE_HERO_FROM_ARMY, mapper.writeValueAsString(listOf(armyId)), dataType = DownType.PLAIN))
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.armyAndHeroesUpsertNotify(state, listOfNotNull(removedHero), listOf(armyId)),
                dataType = DownType.PLAIN,
            ),
        )
        log.info(">> cmd=31 武将下阵已处理 (uid=$userId, cityWid=$cityWid, armyId=$armyId, pos=$pos, heroUid=${removedHero?.heroUid ?: 0})")
    }

    private fun sendSwitchHeroInArmy(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val cityWid = body?.get(0)?.asInt()?.takeIf { it > 0 } ?: GameServerConfig.CITY_WID
        val armyId1 = body?.get(1)?.asInt()?.takeIf { it > 0 } ?: (cityWid * 10 + 1)
        val pos1 = body?.get(2)?.asInt() ?: 1
        val armyId2 = body?.get(3)?.asInt()?.takeIf { it > 0 } ?: armyId1
        val pos2 = body?.get(4)?.asInt() ?: 1
        val state = playerState(session, userId, cityWid)
        val affectedHeroes = state.switchTeamHeroes(pos1, pos2, armyId1, armyId2).mapNotNull { state.hero(it) }
        val responseArmyIds = listOf(armyId1, armyId2).distinct()
        PlayerStateRepository.save(state)

        ctx.writeAndFlush(DownPacket.json(Cmd.SWITCH_HERO_IN_ARMY, mapper.writeValueAsString(responseArmyIds), dataType = DownType.PLAIN))
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.armyAndHeroesUpsertNotify(state, affectedHeroes, responseArmyIds),
                dataType = DownType.PLAIN,
            ),
        )
        log.info(">> cmd=32 武将换位已处理 (uid=$userId, cityWid=$cityWid, armyId1=$armyId1, pos1=$pos1, armyId2=$armyId2, pos2=$pos2)")
    }

    private fun sendQuickCardRecruit(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val summonUid = body?.get(0)?.asInt()?.takeIf { it > 0 } ?: 0
        val quickCount = body?.get(4)?.asInt()?.takeIf { it > 0 } ?: 1
        val packId = summonPackId(userId, summonUid)
        val json = GameResponses.quickCardRecruit(summonUid = summonUid, packId = packId, quickCount = quickCount)
        ctx.writeAndFlush(DownPacket.json(Cmd.CARD_QUICK_RECRUIT, json, dataType = DownType.PLAIN))
        sendRecruitHeroInsertNotify(ctx, session, userId, Cmd.CARD_QUICK_RECRUIT, json)
        log.info(">> cmd=304 快速招募结果已下发 (summonUid=$summonUid, packId=$packId, quickCount=$quickCount, ${json.length}B)")
    }

    private fun summonPackId(userId: Int, summonUid: Int): Int =
        ClientCardPackCatalog.packIdForSummonUid(userId, summonUid) ?: 0

    private fun sendCardOperationSuccess(ctx: ChannelHandlerContext, msg: UpPacket) {
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val first = body?.get(0)?.asInt() ?: 0
        val json = when (msg.cmdId) {
            Cmd.CARD_CHANGE_POINT_PLAN -> mapper.writeValueAsString(listOf(first))
            Cmd.CARD_SELECT_HERO -> mapper.writeValueAsString(first)
            else -> GameResponses.emptyArray()
        }
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, json, dataType = DownType.PLAIN))
        log.info(">> cmd=${msg.cmdId} 卡牌操作已应答")
    }

    private fun sendRecruitHeroInsertNotify(
        ctx: ChannelHandlerContext,
        session: Session?,
        userId: Int,
        cmdId: Int,
        recruitJson: String,
    ) {
        val heroIds = RecruitResultParser.heroIdsFrom(cmdId, recruitJson)
        if (heroIds.isEmpty()) return
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val cards = heroIds.flatMap { heroId ->
            listOf(state.addHero(heroId), state.addAdvanceMaterial(heroId))
        }
        PlayerStateRepository.save(state)
        val json = GameResponses.heroUpsertNotify(userId = userId, heroes = cards)
        ctx.writeAndFlush(DownPacket.json(Cmd.SYS_NOTIFY_DB_UPDATE, json, dataType = DownType.PLAIN))
        log.info(">> cmd=90005 招募武将入库已下发 (count=${cards.size}, ${json.length}B)")
    }

    private fun sendHeroAdvance(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val targetHeroUid = body?.get(0)?.asInt() ?: 0
        val materialHeroUids = body?.get(1)
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.asInt().takeIf { heroUid -> heroUid > 0 } }
            .orEmpty()
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val result = state.advanceHero(targetHeroUid, materialHeroUids)

        ctx.writeAndFlush(
            DownPacket.json(Cmd.HERO_ADVANCE, GameResponses.emptyArray(), dataType = DownType.PLAIN),
        )
        if (result != null) {
            PlayerStateRepository.save(state)
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.heroAdvanceNotify(
                        heroUid = result.hero.heroUid,
                        advanceNum = result.hero.advanceNum,
                        consumedMaterialUids = result.consumedMaterialUids,
                    ),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(
            ">> cmd=83 武将进阶已处理 " +
                "(uid=$userId, target=$targetHeroUid, materials=$materialHeroUids, changed=${result != null})",
        )
    }

    private fun sendGearOperation(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val request = GearOperationRequestParser.parse(msg.bodyText)
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val result = request?.let { operation ->
            when (msg.cmdId) {
                Cmd.GEAR_EQUIP -> state.equipGrantedGear(operation.heroUid, operation.gearUid)
                Cmd.GEAR_FORGET -> state.forgetGrantedGear(operation.heroUid, operation.gearUid)
                else -> null
            }
        }

        ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
        if (result != null) {
            PlayerStateRepository.save(state)
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.gearEquipNotify(result),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(
            ">> cmd=${msg.cmdId} 武器操作已处理 " +
                "(uid=$userId, heroUid=${request?.heroUid ?: 0}, gearUid=${request?.gearUid ?: 0}, " +
                "changed=${result != null})",
        )
    }

    private fun sendArmyFacadeOperation(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val mutation = when (msg.cmdId) {
            Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD ->
                ArmyFacadeOperationRequestParser.parseBatch(msg.bodyText)
                    ?.let { request -> state.bindArmyFacadeCards(request.facadeId, request.heroUids) }
            Cmd.UNLOCK_TROOP_FACADE_CARD ->
                ArmyFacadeOperationRequestParser.parseSingle(msg.bodyText)
                    ?.let { request -> state.bindArmyFacadeCards(request.facadeId, request.heroUids) }
            Cmd.USE_TROOP_FACADE_CARD ->
                ArmyFacadeOperationRequestParser.parseUse(msg.bodyText)
                    ?.let { request -> state.useArmyFacade(request.heroUid, request.facadeId) }
            Cmd.HERO_ACTIVE_FACADE ->
                ArmyFacadeOperationRequestParser.parseSpecialState(msg.bodyText)
                    ?.let { request -> state.setSpecialArmyFacadeState(request.specialCardUid, request.state) }
            else -> null
        }

        ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
        if (mutation != null) {
            PlayerStateRepository.save(state)
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.armyFacadeNotify(state, mutation),
                    dataType = DownType.PLAIN,
                ),
            )
            if (mutation.affectedArmyIds.any { armyId -> state.activeMarch(armyId) != null }) {
                sendWorldSceneFullInfo(ctx, session)
            }
        }
        log.info(
            ">> cmd=${msg.cmdId} 行军外观操作已处理 " +
                "(uid=$userId, changed=${mutation != null}, body=${msg.bodyText})",
        )
    }

    private fun sendApplyCityFacadeScheme(
        ctx: ChannelHandlerContext,
        session: Session?,
        msg: UpPacket,
    ) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val changed = CityFacadeOperationRequestParser.parseApplyScheme(msg.bodyText)
            ?.let { request ->
                WorldStateRepository.updateCityCustomView(state, request.cityWid, request.customView)
            } == true

        ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
        if (changed) {
            broadcastWorldScene(
                removedArmyUserId = 0,
                removedArmyId = 0,
            )
        }
        log.info(">> cmd=${msg.cmdId} 主城布局已处理 (uid=$userId, changed=$changed)")
    }

    private fun sendCardSetAllNotNew(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        state.markCardPacksSeen()
        PlayerStateRepository.save(state)
        ctx.writeAndFlush(DownPacket.json(Cmd.CARD_SET_ALL_NOT_NEW, "[]", dataType = DownType.PLAIN))
        val summonUids = ClientCardPackCatalog.allPacks().map { pack ->
            ClientCardPackCatalog.summonUid(userId, pack.packId)
        }
        val notify = GameResponses.cardPacksSeenNotify(summonUids)
        ctx.writeAndFlush(
            DownPacket.json(Cmd.SYS_NOTIFY_DB_UPDATE, notify, dataType = DownType.PLAIN),
        )
        log.info(">> cmd=302 武将新卡标记清理已应答并同步客户端 (count=${summonUids.size})")
    }

    private fun sendSelectHeroFacade(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val heroUid = body?.get(0)?.asInt() ?: 0
        val facadeHeroId = body?.get(1)?.asInt() ?: 0
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val changed = state.selectHeroFacade(heroUid, facadeHeroId)
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.HERO_SELECT_FACADE,
                GameResponses.emptyArray(),
                dataType = DownType.PLAIN,
            ),
        )
        if (changed) {
            PlayerStateRepository.save(state)
            state.hero(heroUid)?.let { hero ->
                ctx.writeAndFlush(
                    DownPacket.json(
                        Cmd.SYS_NOTIFY_DB_UPDATE,
                        GameResponses.heroUpsertNotify(userId, listOf(hero)),
                        dataType = DownType.PLAIN,
                    ),
                )
            }
        }
        log.info(">> cmd=674 武将画像切换 (uid=$userId, heroUid=$heroUid, facade=$facadeHeroId, changed=$changed)")
    }

    private fun sendSelectHeroCardBorder(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val heroUid = body?.get(0)?.asInt() ?: 0
        val cardBorder = body?.get(1)?.asInt() ?: 0
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val changed = state.selectHeroCardBorder(heroUid, cardBorder)

        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.HERO_ACTIVE_CARD_BORDER,
                GameResponses.emptyArray(),
                dataType = DownType.PLAIN,
            ),
        )
        if (changed) {
            PlayerStateRepository.save(state)
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.heroCardBorderUpdateNotify(heroUid, cardBorder),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(
            ">> cmd=675 武将卡框切换 " +
                "(uid=$userId, heroUid=$heroUid, cardBorder=$cardBorder, changed=$changed)",
        )
    }

    private fun sendHeroTeamLibrary(ctx: ChannelHandlerContext) {
        val json = GameResponses.heroTeamLibrary(defaultHeroIds)
        ctx.writeAndFlush(DownPacket.json(Cmd.HERO_TEAM_LIBRARY, json, dataType = DownType.PLAIN))
        log.info(">> cmd=9029 武将配队库已下发 (${json.length}B)")
    }

    private fun sendNormalTeamComposition(ctx: ChannelHandlerContext, msg: UpPacket) {
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val heroId = body?.get(0)?.asInt() ?: 0
        val json = GameResponses.normalTeamComposition(heroId)
        ctx.writeAndFlush(DownPacket.json(Cmd.NORMAL_TEAM_COMPOSITION, json, dataType = DownType.PLAIN))
        log.info(">> cmd=9026 普通配队推荐已应答 (heroId=$heroId)")
    }

    private fun sendBattleReportProfile(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val battleIds = body?.get(0)?.takeIf { it.isArray }?.mapNotNull { it.asInt().takeIf { id -> id > 0 } }.orEmpty()
        val serverId = body?.get(2)?.asInt() ?: 0
        val json = ClientBattleReportStore.global().profileResponse(userId, battleIds, serverId)
        ctx.writeAndFlush(DownPacket.json(Cmd.BATTLE_REPORT_PROFILE, json, dataType = DownType.ZLIB))
        log.info(">> cmd=10 战报摘要已下发 (battleIds=$battleIds, serverId=$serverId, ${json.length}B)")
    }

    private fun sendCreateUnion(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val name = runCatching { mapper.readTree(msg.body).get(0).asText() }.getOrDefault("")
        val unionId = UnionStateRepository.create(
            state = state,
            requestedName = name,
            nowSec = (System.currentTimeMillis() / 1_000L).toInt(),
        )

        ctx.writeAndFlush(
            DownPacket.json(Cmd.UNION_CREATE, unionId.toString(), dataType = DownType.PLAIN),
        )
        UnionStateRepository.find(unionId)?.let { union ->
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.userUnionUpdateNotify(state.userId, union),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(">> cmd=102 同盟创建已处理 (uid=$userId, unionId=$unionId, name=${name.trim()})")
    }

    private fun sendUnionInfo(ctx: ChannelHandlerContext, msg: UpPacket) {
        val unionId = runCatching { mapper.readTree(msg.body).get(0).asInt() }.getOrDefault(0)
        val json = UnionStateRepository.find(unionId)
            ?.let(GameResponses::unionInfo)
            ?: "[1,[]]"
        ctx.writeAndFlush(DownPacket.json(Cmd.UNION_INFO, json, dataType = DownType.PLAIN))
        log.info(">> cmd=100 同盟详情已下发 (unionId=$unionId, found=${unionId > 0 && UnionStateRepository.find(unionId) != null})")
    }

    private fun sendUnionMembers(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val json = UnionStateRepository.forUser(userId)
            ?.let(GameResponses::unionMembers)
            ?: GameResponses.emptyArray()
        ctx.writeAndFlush(DownPacket.json(Cmd.UNION_MEMBER, json, dataType = DownType.PLAIN))
        log.info(">> cmd=103 同盟成员已下发 (uid=$userId, hasUnion=${UnionStateRepository.forUser(userId) != null})")
    }

    private fun sendUnionOfficialList(ctx: ChannelHandlerContext, msg: UpPacket) {
        val unionId = requestedUnionId(msg)
        val union = UnionStateRepository.find(unionId)
        val json = UnionOfficialListResponses.response(union)
        ctx.writeAndFlush(DownPacket.json(Cmd.UNION_OFFICIAL_LIST, json, dataType = DownType.PLAIN))
        log.info(">> cmd=110 同盟官员列表已下发 (unionId=$unionId, found=${union != null})")
    }

    private fun requestedUnionId(msg: UpPacket): Int =
        runCatching { strictRequestMapper.readTree(msg.bodyText) }
            .getOrNull()
            ?.takeIf { it.isArray && it.size() > 0 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.intValue()
            ?: 0

    private fun sendUnionChatMembers(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val union = UnionStateRepository.forUser(userId)
        val json = union
            ?.let(GameResponses::unionChatMembers)
            ?: GameResponses.emptyArray()
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, json, dataType = DownType.PLAIN))
        log.info(">> cmd=143 同盟分组聊天成员已下发 (uid=$userId, hasUnion=${union != null})")
    }

    private fun sendUnionGroupList(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(DownPacket.json(Cmd.UNION_GET_GROUP_LIST, "[]", dataType = DownType.PLAIN))
    }

    private fun sendDailyReportDetail(ctx: ChannelHandlerContext, msg: UpPacket) {
        val timestamp = requestInts(msg.bodyText, requiredSlots = 1)?.get(0) ?: 0
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.DAILY_REPORT_GET_DETAIL,
                """[[],0,"","",$timestamp]""",
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendHeroRecommendation(ctx: ChannelHandlerContext, msg: UpPacket) {
        val heroId = requestInts(msg.bodyText, requiredSlots = 1)?.get(0) ?: 0
        ctx.writeAndFlush(
            DownPacket.json(Cmd.GET_HERO_RECOMMEND_2, "[$heroId]", dataType = DownType.PLAIN),
        )
    }

    private fun sendSeasonRecommendations(ctx: ChannelHandlerContext, msg: UpPacket) {
        val recType = requestInts(msg.bodyText, requiredSlots = 2)?.get(1) ?: 0
        ctx.writeAndFlush(
            DownPacket.json(Cmd.GET_UDS_GUESS_SEASON, "[$recType,[]]", dataType = DownType.PLAIN),
        )
    }

    private fun requestInts(body: String, requiredSlots: Int): List<Int>? {
        val request = runCatching { strictRequestMapper.readTree(body) }.getOrNull() ?: return null
        if (!request.isArray || request.size() < requiredSlots) return null

        val values = ArrayList<Int>(requiredSlots)
        for (index in 0 until requiredSlots) {
            val value = request[index]
            if (!value.isIntegralNumber || !value.canConvertToInt()) return null
            values += value.asInt()
        }
        return values
    }

    private fun sendRankList(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.playerId ?: msg.userId
        val json = RankListResponses.response(
            requestBody = msg.bodyText,
            userId = userId,
            world = WorldStateRepository.projection(),
            unions = UnionStateRepository.all(),
        )
        ctx.writeAndFlush(DownPacket.json(Cmd.RANK_LIST, json, dataType = DownType.PLAIN))
        log.info(">> cmd=700 排行榜已下发 (uid=$userId)")
    }

    private fun sendWorldRankDomesticStatus(ctx: ChannelHandlerContext, msg: UpPacket) {
        val response = when (msg.cmdId) {
            Cmd.OWN_RANK -> "-1"
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO,
            Cmd.PROGRESS_GET_NPC_OCCUPY_INFO_ZFJX -> {
                val stateId = exactSingleInt(msg.bodyText) ?: 0
                "[$stateId,{}]"
            }
            Cmd.FENGLU_LEVEL_STATUS -> {
                exactSingleInt(msg.bodyText)
                    ?.let { level ->
                        """{"level":$level,"contributions":[],"officers":[]}"""
                    }
                    ?: "{}"
            }
            else -> error("unsupported world/rank/domestic-status cmd: ${msg.cmdId}")
        }
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, response, dataType = DownType.PLAIN))
    }

    private fun exactSingleInt(body: String): Int? =
        runCatching { strictRequestMapper.readTree(body) }
            .getOrNull()
            ?.takeIf { it.isArray && it.size() == 1 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()

    private fun sendBattlefieldChatHistory(ctx: ChannelHandlerContext, msg: UpPacket) {
        exactSingleInt(msg.bodyText) ?: return
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.CHAT_GET_FIGHT_AREA_CHAT,
                "[]",
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendUnionPlanChatHistory(ctx: ChannelHandlerContext, msg: UpPacket) {
        val planId = exactSingleInt(msg.bodyText) ?: return
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.CHAT_UNION_PLAN_HISTORY,
                "[$planId,[]]",
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendInvitationalTeamLog(ctx: ChannelHandlerContext, msg: UpPacket) {
        val request = runCatching { strictRequestMapper.readTree(msg.bodyText) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 }
            ?: return
        val start = request[0]
            .takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?: return
        val limit = request[1]
            .takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?: return
        if (start != 0 || limit != 20_000) return

        ctx.writeAndFlush(
            DownPacket.json(Cmd.INVITATIONAL_QUERY_LOG, "[]", dataType = DownType.PLAIN),
        )
    }

    private fun sendNobilityOfficerRecord(ctx: ChannelHandlerContext, msg: UpPacket) {
        val requestedTimeStamp = exactSingleLong(msg.bodyText) ?: 0L
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.NOBILITY_TITLE_QUERY_EIGHT_OFFICER_RECORD,
                "[$requestedTimeStamp,[]]",
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun exactSingleLong(body: String): Long? =
        runCatching { strictRequestMapper.readTree(body) }
            .getOrNull()
            ?.takeIf { it.isArray && it.size() == 1 }
            ?.get(0)
            ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.longValue()

    private fun sendSummerFarmUserList(ctx: ChannelHandlerContext, msg: UpPacket) {
        val channelId = exactSingleInt(msg.bodyText)?.takeIf { it in 1..3 } ?: return
        ctx.writeAndFlush(
            DownPacket.json(msg.cmdId, "[$channelId,[]]", dataType = DownType.PLAIN),
        )
    }

    private fun sendSummerFarmRecordQuery(ctx: ChannelHandlerContext, msg: UpPacket) {
        val request = runCatching { strictRequestMapper.readTree(msg.bodyText) }.getOrNull()
        val key = request?.takeIf { it.isArray && it.size() == 1 }?.get(0) ?: return
        val validKey = (key.isIntegralNumber && key.canConvertToLong()) ||
            (key.isTextual && key.textValue().isNotEmpty())
        if (!validKey) return

        ctx.writeAndFlush(DownPacket.json(msg.cmdId, "[]", dataType = DownType.PLAIN))
    }

    private fun sendUnionLetterQuery(ctx: ChannelHandlerContext, msg: UpPacket) {
        val validRequest = (
            runCatching { strictRequestMapper.readTree(msg.bodyText) }
                .getOrNull()
                ?.takeIf { it.isArray && it.size() == 1 }
                ?.get(0)
                ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
                ?.intValue()
                ?.takeIf { it == 0 || it == 1 }
        ) != null
        if (!validRequest) return

        ctx.writeAndFlush(DownPacket.json(msg.cmdId, """["",""]""", dataType = DownType.PLAIN))
    }

    private fun sendWorldBossTopThreeRank(ctx: ChannelHandlerContext, session: Session?) {
        val userId = requireNotNull(session).userId
        val json = RankListResponses.response(
            requestBody = "[0,3,51]",
            userId = userId,
            world = WorldStateRepository.projection(),
            unions = UnionStateRepository.all(),
        )
        ctx.writeAndFlush(
            DownPacket.json(Cmd.WORLD_BOSS_TOP_THREE_RANK, json, dataType = DownType.PLAIN),
        )
    }

    private fun sendUserHeadIcons(ctx: ChannelHandlerContext, msg: UpPacket) {
        val json = UserHeadIconResponses.response(msg.bodyText)
        ctx.writeAndFlush(
            DownPacket.json(Cmd.USER_GET_USERS_HEADICON, json, dataType = DownType.PLAIN),
        )
        log.info(">> cmd=514 用户头像已下发")
    }

    private fun sendMailInfo(ctx: ChannelHandlerContext, msg: UpPacket) {
        val json = MailInfoResponses.response(msg.bodyText)
        ctx.writeAndFlush(DownPacket.json(Cmd.MAIL_INFO, json, dataType = DownType.PLAIN))
        log.info(">> cmd=204 邮件详情已下发")
    }

    private fun sendMailBriefInfo(ctx: ChannelHandlerContext, msg: UpPacket) {
        val json = MailBriefInfoResponses.response(msg.bodyText)
        ctx.writeAndFlush(
            DownPacket.json(Cmd.MAIL_BRIEF_INFO_BY_MAIL_ID, json, dataType = DownType.PLAIN),
        )
        log.info(">> cmd=209 邮件概要已下发")
    }

    private fun sendHomepageInfo(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val json = ProfileResponses.homepageInfo(
            userId = state.userId,
            roleName = state.roleName,
            playerUnion = UnionStateRepository.forUser(state.userId),
        )
        ctx.writeAndFlush(DownPacket.json(Cmd.GET_HOMEPAGE_INFO, json, dataType = DownType.PLAIN))
        log.info(">> cmd=3686 个人资料已下发 (uid=$userId)")
    }

    private fun sendBattleReportDetail(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val battleId = body?.get(0)?.asInt()?.takeIf { it > 0 } ?: 0
        val serverId = body?.get(2)?.asInt() ?: 0
        val json = ClientBattleReportStore.global().detailResponse(
            ownerUserId = userId,
            battleId = battleId,
            serverId = serverId,
            compressed = msg.cmdId != Cmd.BATTLE_REPORT_SHORT_DETAIL,
        )
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, json, dataType = DownType.ZLIB))
        log.info(">> cmd=${msg.cmdId} 战报详情已下发 (battleId=$battleId, serverId=$serverId, ${json.length}B)")
    }

    private fun sendArmyBattle(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val request = ArmyBattleRequestParser.parse(msg.bodyText)
        val targetWid = request?.targetWid ?: (GameServerConfig.CITY_WID + 1)
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val armyId = request?.armyId ?: state.primaryArmyId()
        val battleService = PlayerBattleService(ClientBattleReportStore.global())
        val result = battleService.launchPveBattle(
            state = state,
            targetWid = targetWid,
            armyId = armyId,
        )
        ctx.writeAndFlush(DownPacket.json(Cmd.ARMY_BATTLE, "null", dataType = DownType.PLAIN))
        if (result != null) {
            PlayerStateRepository.save(state)
            sendArmyStateNotify(ctx, userId, state, armyId)
            sendWorldSceneFullInfo(ctx, session)
            schedulePveBattleSettlement(ctx, session, userId, state, battleService, armyId)
            log.info(">> cmd=6 出征已开始 (uid=$userId, targetWid=$targetWid, armyId=$armyId)")
        } else {
            log.info(">> cmd=6 出征战斗未执行: 队伍无可战斗武将 (uid=$userId, targetWid=$targetWid)")
        }
    }

    private fun schedulePveBattleSettlement(
        ctx: ChannelHandlerContext,
        session: Session?,
        userId: Int,
        state: com.stzb.server.game.PlayerState,
        battleService: PlayerBattleService,
        armyId: Int,
    ) {
        val march = state.activeMarch(armyId) ?: return
        val delayMillis = (march.endSec * 1_000L - System.currentTimeMillis()).coerceAtLeast(0L)
        ctx.channel().eventLoop().schedule({
            if (!ctx.channel().isActive) return@schedule
            val result = battleService.settlePveBattle(state, armyId) ?: return@schedule
            PlayerStateRepository.save(state)
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.battleReportAttackInsertNotify(
                        userId = userId,
                        battleId = result.battleId,
                        armyId = armyId,
                        targetWid = result.targetWid,
                        outcome = result.outcome ?: return@schedule,
                        heroIds = state.teamHeroes(armyId)
                            .mapNotNull { state.hero(it)?.heroId }
                            .filter { it > 0 },
                    ),
                    dataType = DownType.PLAIN,
                ),
            )
            val claimed = result.mayClaimLand && WorldStateRepository.claimLand(
                state = state,
                wid = result.targetWid,
                nowSec = (System.currentTimeMillis() / 1_000L).toInt(),
            )
            if (claimed) {
                ctx.writeAndFlush(
                    DownPacket.json(
                        Cmd.SYS_NOTIFY_DB_UPDATE,
                        GameResponses.occupiedLandUpsertNotify(
                            userId = userId,
                            cityWid = state.cityWid,
                            landWid = result.targetWid,
                        ),
                        dataType = DownType.PLAIN,
                    ),
                )
            } else if (result.mayClaimLand) {
                log.info(
                    ">> 出征胜利但土地已归属其他玩家 " +
                        "(uid=$userId, targetWid=${result.targetWid}, " +
                        "owner=${WorldStateRepository.ownerOf(result.targetWid)?.userId})",
                )
            }
            sendArmyStateNotify(ctx, userId, state, armyId)
            if (claimed) {
                broadcastWorldScene(
                    removedArmyUserId = userId,
                    removedArmyId = armyId,
                )
            } else {
                sendWorldSceneFullInfo(ctx, session, removedArmyId = armyId)
            }
            log.info(">> 出征抵达并结算 (uid=$userId, targetWid=${result.targetWid}, battleId=${result.battleId})")
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun sendArmyStateNotify(
        ctx: ChannelHandlerContext,
        userId: Int,
        state: com.stzb.server.game.PlayerState,
        armyId: Int,
    ) {
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.armyUpsertNotify(state, armyId),
                dataType = DownType.PLAIN,
            ),
        )
        val heroes = state.teamHeroes(armyId).mapNotNull { state.hero(it) }
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.heroUpsertNotify(userId = userId, heroes = heroes),
                dataType = DownType.PLAIN,
            ),
        )
    }

    private fun sendConscript(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val request = ConscriptRequestParser.parse(msg.cmdId, msg.bodyText)
        if (request == null) {
            ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
            log.info(">> cmd=${msg.cmdId} 征兵请求为空，已安全应答 (uid=$userId)")
            return
        }

        val result = PlayerConscriptService().conscript(state, request)
        PlayerStateRepository.save(state)
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, result.armyId.toString(), dataType = DownType.PLAIN))
        if (result.updatedHeroes.isNotEmpty()) {
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.heroUpsertNotify(userId = userId, heroes = result.updatedHeroes),
                    dataType = DownType.PLAIN,
                ),
            )
            ctx.writeAndFlush(
                DownPacket.json(
                    Cmd.SYS_NOTIFY_DB_UPDATE,
                    GameResponses.userResourceUpsertNotify(userId = userId, resources = state.resources),
                    dataType = DownType.PLAIN,
                ),
            )
        }
        log.info(">> cmd=${msg.cmdId} 征兵已处理 (uid=$userId, armyId=${result.armyId}, heroes=${result.updatedHeroes.map { it.heroUid }})")
    }

    private fun saveTeamConfig(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val heroes = TeamRequestParser.parseSavedTeam(msg.cmdId, msg.bodyText)
        state.saveTeam(heroes)
        PlayerStateRepository.save(state)
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
        log.info(">> cmd=${msg.cmdId} 队伍配置已保存 (uid=$userId, heroes=${state.teamHeroes()})")
    }

    private fun logUnhandledOrFallback(ctx: ChannelHandlerContext, msg: UpPacket) {
        val contract = CommandContractCatalog.registry.contract(msg.cmdId)
        val response = contract
            ?.takeIf { it.status == CommandStatus.OBSERVED_SHAPE }
            ?.let { NetworkResponsePolicy.observedShapeBody(msg.cmdId, msg.bodyText) }

        if (response == null) {
            log.warn(
                "unhandled command cmd=${msg.cmdId} status=${contract?.status ?: "UNKNOWN"} " +
                    "idx=${msg.cmdIndex} uid=${msg.userId} checkOk=${msg.checkOk}",
            )
            return
        }

        log.warn("shape-only command cmd=${msg.cmdId} status=${contract.status}")
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, response, dataType = DownType.PLAIN))
    }

    private fun responseShape(json: String): String =
        when {
            json == "null" -> "null"
            json == "true" || json == "false" -> "boolean"
            json.startsWith("[") -> "array"
            json.startsWith("{") -> "object"
            else -> "scalar"
        }

    private fun sendServerTime(ctx: ChannelHandlerContext) {
        val json = GameResponses.serverTime(System.currentTimeMillis() / 1000)
        ctx.writeAndFlush(DownPacket.json(Cmd.GET_SERVER_TIME, json, dataType = DownType.PLAIN))
        log.info(">> cmd=25 服务器时间已下发")
    }

    private fun sendServerTimeMillis(ctx: ChannelHandlerContext) {
        val json = GameResponses.serverTimeMillis(System.currentTimeMillis())
        ctx.writeAndFlush(DownPacket.json(Cmd.SYNC_SERVER_TIME, json, dataType = DownType.XOR))
        log.info(">> cmd=694 服务器毫秒时间已下发")
    }

    private fun sendChat(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
        val channelId = body?.get(0)?.asInt() ?: 0
        val subType = body?.get(1)?.asInt() ?: 0
        val content = body?.get(2)?.asText() ?: ""
        val params = body?.get(3) ?: mapper.createArrayNode()
        val channelIdIndeed = body?.get(5)?.asInt() ?: 0
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val chatId = nextChatId.getAndIncrement()
        val nowSec = System.currentTimeMillis() / 1000
        val roleId = "role_$userId"
        val notification = WorldChatRecord(
            listOf(
                // ChatData.ConvertChatDataVo consumes this positional 48-field contract.
                chatId, channelId, subType, userId, state.roleName, content, nowSec,
                0, "", 0, params, 0, channelIdIndeed, GameServerConfig.SERVER_ID,
                0, "", 0, 0, "", CHAT_DEFAULT_HEAD_ICON_ID, "", roleId,
                0, 0, "", 0, 0, 0, 0, "", 0, "", 0, 0, -1,
                0, "", "", 0, 0, "", state.cityWid, 0, 0, 0, roleId, 0, 0,
            ),
        )
        if (channelId == WORLD_CHAT_CHANNEL_ID) {
            WorldChatStore.append(notification)
        }
        val notificationJson = mapper.writeValueAsString(notification.fields)
        ctx.writeAndFlush(DownPacket.json(Cmd.CHAT, "[false,0]", dataType = DownType.PLAIN))

        val recipients = (onlineSessions.allChannels() + ctx.channel())
            .distinct()
            .filter { channel -> channel.isActive }
        recipients.forEach { channel ->
            channel.writeAndFlush(
                DownPacket.json(Cmd.NOTIFY_CHAT_MSG, notificationJson, dataType = DownType.XOR),
            )
        }
        log.info(
            ">> cmd=710 聊天已发送 " +
                "(uid=$userId, channel=$channelId, subType=$subType, chatId=$chatId, recipients=${recipients.size})",
        )
    }

    private fun sendChatHistory(ctx: ChannelHandlerContext) {
        val slots = MutableList<Any?>(CHAT_HISTORY_SLOT_COUNT) { emptyList<Any?>() }
        slots[WORLD_CHAT_HISTORY_SLOT] = WorldChatStore.snapshot().map(WorldChatRecord::historyEntry)
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.CHAT_HISTORY,
                mapper.writeValueAsString(slots),
                dataType = DownType.ZLIB,
            ),
        )
        log.info(">> cmd=711 世界聊天历史已下发 (count=${WorldChatStore.snapshot().size})")
    }

    private fun sendWorldSceneFullInfo(
        ctx: ChannelHandlerContext,
        session: Session?,
        removedArmyId: Int? = null,
    ) {
        val packet = worldScenePacket(session, removedArmyId = removedArmyId)
        ctx.writeAndFlush(packet)
        log.info(
            ">> cmd=5026 世界全量视野已下发 " +
                "(uid=${session?.userId ?: 10001}, ${packet.body.size}B)",
        )
    }

    private fun broadcastWorldScene(
        removedArmyUserId: Int,
        removedArmyId: Int,
    ) {
        val world = WorldStateRepository.projection()
        onlineSessions.allChannels().forEach { channel ->
            val session = channel.attr(SESSION).get() ?: return@forEach
            if (!channel.isActive) return@forEach
            val removedArmyForSession =
                removedArmyId.takeIf { session.userId == removedArmyUserId }
            channel.writeAndFlush(
                worldScenePacket(
                    session = session,
                    removedArmyId = removedArmyForSession,
                    world = world,
                ),
            )
        }
        log.info(
            ">> cmd=5026 世界全量视野已广播 " +
                "(online=${onlineSessions.allChannels().size}, worldCities=${world.cities.size}, worldLands=${world.lands.size})",
        )
    }

    private fun worldScenePacket(
        session: Session?,
        removedArmyId: Int? = null,
        world: WorldProjection = WorldStateRepository.projection(),
    ): DownPacket {
        val userId = session?.userId ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val json = GameResponses.worldSceneFullInfo(
            userId = userId,
            cityWid = state.cityWid,
            roleName = state.roleName,
            marches = state.activeMarches(),
            removedArmyId = removedArmyId,
            occupiedLands = state.occupiedLands(),
            world = world,
        )
        return DownPacket.json(Cmd.SEND_WORLD_SCENCE_FULL_INFO, json, dataType = DownType.PLAIN)
    }

    private fun sendDevicePing(ctx: ChannelHandlerContext) {
        val json = GameResponses.devicePing(serverProcessingNanos = 0)
        ctx.writeAndFlush(DownPacket.json(Cmd.SYS_PING, json, dataType = DownType.XOR))
        log.debug(">> cmd=90006 ping 响应已下发")
    }

    private fun sendArmyRelatedFort(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.QUERY_ARMY_RELATED_FORT,
                GameResponses.armyRelatedFort(),
                dataType = DownType.XOR,
            ),
        )
        log.info(">> cmd=4159 部队关联要塞已应答")
    }

    private fun sendNoOpSuccess(ctx: ChannelHandlerContext, msg: UpPacket) {
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
        log.info(">> cmd=${msg.cmdId} 记录类请求已应答")
    }

    private fun sendReadOnlyEmptyList(ctx: ChannelHandlerContext, cmdId: Int) {
        ctx.writeAndFlush(DownPacket.json(cmdId, "[]", dataType = DownType.PLAIN))
    }

    private fun sendClanSearchClanList(ctx: ChannelHandlerContext, msg: UpPacket) {
        val request = runCatching { strictRequestMapper.readTree(msg.bodyText) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 }
            ?: return
        if (!request[0].isTextual || request[0].textValue().isEmpty()) return
        val mode = request[1]
        if (!mode.isIntegralNumber || !mode.canConvertToInt() || mode.asInt() != 0) return

        ctx.writeAndFlush(
            DownPacket.json(Cmd.CLAN_SEARCH_CLAN_LIST, "[]", dataType = DownType.PLAIN),
        )
    }

    private fun sendClanLogGet(ctx: ChannelHandlerContext, msg: UpPacket) {
        val request = runCatching { strictRequestMapper.readTree(msg.bodyText) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 }
            ?: return
        val logIdCur = request[0]
            .takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?: return
        val logNum = request[1]
            .takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
            ?: return
        if (logIdCur != 0 || logNum != 20_000) return

        ctx.writeAndFlush(
            DownPacket.json(Cmd.CLAN_LOG_GET, "[]", dataType = DownType.PLAIN),
        )
    }

    private fun sendStrictEmptyQueryProjection(ctx: ChannelHandlerContext, msg: UpPacket) {
        val request = runCatching { strictRequestMapper.readTree(msg.bodyText) }.getOrNull()
        val acceptsNull = msg.cmdId == Cmd.GET_INVITE_LIST && request?.isNull == true
        val acceptsEmptyArray = request?.isArray == true && request.isEmpty
        if (!acceptsNull && !acceptsEmptyArray) return

        val response = when (msg.cmdId) {
            Cmd.UNION_MEMBER_CLAN_LIST,
            Cmd.GET_INVITE_LIST,
            Cmd.GET_ZHAOHUI_LIST,
            Cmd.CHAT_GET_CITY_HISTORY,
            Cmd.UNION_LEADER_CLAN_CITY_LIST,
            Cmd.CLAN_APPLICANT_LIST,
            Cmd.CLAN_NEARBY_NEARBY_PLAYER_LIST,
            Cmd.CLAN_NEARBY_CLAN_LIST,
            Cmd.CLAN_NPC_CITY_LIST,
            Cmd.CLAN_GET_CONTRIBUTION_LIST,
            Cmd.CLAN_GET_JUNXIAN_LIST,
            Cmd.CLAN_SUPREME_LIST,
            Cmd.GET_NZ_EFFECT_LAND_LIST -> "[]"
            Cmd.GET_FIELD_RES_TOTAL_STORE -> "{}"
            Cmd.QUERY_USER_MARKET_SCORE -> "[0,0]"
            else -> error("unsupported strict empty query projection cmd: ${msg.cmdId}")
        }
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, response, dataType = DownType.PLAIN))
    }

    private fun sendRecordedAcknowledgement(ctx: ChannelHandlerContext, msg: UpPacket) {
        val response = requireNotNull(
            NetworkResponsePolicy.observedShapeBody(msg.cmdId, msg.bodyText),
        ) {
            "missing recorded acknowledgement shape for ${msg.cmdId}"
        }
        ctx.writeAndFlush(DownPacket.json(msg.cmdId, response, dataType = DownType.PLAIN))
        log.info(">> cmd=${msg.cmdId} 协议确认已应答 (${responseShape(response)})")
    }

    private fun requestedRoleName(msg: UpPacket): String? =
        runCatching {
            mapper.readTree(msg.body).takeIf { it.isArray && it.size() > 2 }
                ?.get(2)
                ?.asText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun sendChangeNameSuccess(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val name = requestedNameAt(msg, 0) ?: GameServerConfig.ROLE_NAME
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val state = playerState(session, userId, GameServerConfig.CITY_WID, name)
        state.roleName = name
        WorldStateRepository.registerOrRestorePlayer(state)
        ctx.writeAndFlush(DownPacket.json(Cmd.USER_CHANGE_NAME, "1", dataType = DownType.PLAIN))
        log.info(">> cmd=507 势力改名成功已下发 (name=$name)")
    }

    private fun requestedNameAt(msg: UpPacket, index: Int): String? =
        runCatching {
            mapper.readTree(msg.body).takeIf { it.isArray && it.size() > index }
                ?.get(index)
                ?.asText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun playerState(
        session: Session?,
        userId: Int,
        cityWid: Int,
        roleName: String = GameServerConfig.ROLE_NAME,
    ) = session?.let { currentSession ->
        val accountKey = currentSession.accountKey
        val playerId = currentSession.playerId
        if (accountKey != null && playerId != null) {
            PlayerStateRepository.getOrCreateForSession(
                accountKey = accountKey,
                userId = playerId,
                cityWid = cityWid,
                roleName = roleName,
            )
        } else {
            PlayerStateRepository.getOrCreate(userId, cityWid, roleName)
        }
    } ?: PlayerStateRepository.getOrCreate(userId, cityWid, roleName)

    private fun ensureBoundState(
        ctx: ChannelHandlerContext,
        session: Session?,
        msg: UpPacket,
    ): com.stzb.server.game.PlayerState? {
        if (session == null) return null
        val boundAccountKey = session.accountKey
        val boundPlayerId = session.playerId
        if (boundAccountKey != null && boundPlayerId != null) {
            return playerState(session, boundPlayerId, GameServerConfig.CITY_WID)
        }

        val identity = resolveGameLoginIdentity(msg) ?: return null
        return bindAccount(ctx, session, identity)
    }

    private fun resolveGameLoginIdentity(msg: UpPacket): AccountIdentity? {
        val tokenIdentity = runCatching { mapper.readTree(msg.body) }
            .getOrNull()
            ?.takeIf { it.isArray }
            ?.asSequence()
            ?.filter { it.isTextual }
            ?.map { it.asText() }
            ?.mapNotNull(serverSessions::resolve)
            ?.firstOrNull()
        return tokenIdentity ?: AccountIdentityResolver.fromGameLoginRequest(msg.bodyText)
    }

    private fun bindAccount(
        ctx: ChannelHandlerContext,
        session: Session?,
        identity: AccountIdentity,
    ): com.stzb.server.game.PlayerState? {
        if (session == null) return null
        val state = PlayerStateRepository.getOrCreate(
            accountKey = identity.accountKey,
            cityWid = GameServerConfig.CITY_WID,
            roleName = GameServerConfig.ROLE_NAME,
        )
        val worldState = WorldStateRepository.registerOrRestorePlayer(state)
        session.bind(identity.accountKey, worldState.userId)
        val previous = onlineSessions.bind(identity.accountKey, ctx.channel())
        if (previous != null && previous !== ctx.channel()) {
            previous.writeAndFlush(
                DownPacket.json(Cmd.SYS_SID_INVALID, GameResponses.emptyArray(), dataType = DownType.PLAIN),
            )
            previous.close()
        }
        return state
    }

    private fun logIn(msg: UpPacket) {
        log.info("<< cmd=${msg.cmdId} idx=${msg.cmdIndex} uid=${msg.userId} flag=${msg.flag} checkOk=${msg.checkOk}")
        if (msg.body.isNotEmpty()) log.info("   body: ${msg.bodyText}")
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.warn("连接异常 ${ctx.channel().remoteAddress()}: ${cause.message}", cause)
        ctx.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(GameServerHandler::class.java)
        private val mapper = jacksonObjectMapper()
        private val strictRequestMapper = jacksonObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        private val defaultHeroIds = listOf(100003, 100004, 100005, 100011, 100013, 100015, 100016, 100017)
        private val serverSessions = ServerSessionRegistry()
        private val onlineSessions = OnlineSessionRegistry()
        private val nextChatId = AtomicInteger(1)
        @Volatile
        private var revenueEpochSeconds: () -> Int = ::systemEpochSeconds
        val SESSION: AttributeKey<Session> = AttributeKey.valueOf("stzb.session")
        val KEEP_ALIVE: AttributeKey<ScheduledFuture<*>> = AttributeKey.valueOf("stzb.keepAlive")

        @Synchronized
        fun resetRuntimeForTests() {
            onlineSessions.allChannels().forEach { channel -> channel.close() }
            serverSessions.clear()
            nextChatId.set(1)
            WorldChatStore.reset()
            revenueEpochSeconds = ::systemEpochSeconds
        }

        fun setRevenueEpochSecondsForTests(epochSeconds: Int) {
            revenueEpochSeconds = { epochSeconds }
        }

        private fun systemEpochSeconds(): Int =
            (System.currentTimeMillis() / 1_000L)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()

        private const val WORLD_CHAT_CHANNEL_ID = 0
        private const val WORLD_CHAT_HISTORY_SLOT = 0
        private const val CHAT_HISTORY_SLOT_COUNT = 18
        private const val CHAT_DEFAULT_HEAD_ICON_ID = 301

        /** 喂狗周期; 必须显著小于客户端 3s recv-timeout, 留足抖动余量。 */
        private const val KEEP_ALIVE_MS = 1500L
    }
}
