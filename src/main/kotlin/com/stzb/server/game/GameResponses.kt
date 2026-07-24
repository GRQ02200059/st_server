package com.stzb.server.game

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.BattleOutcome
import java.util.concurrent.atomic.AtomicInteger

/**
 * 构造 20003 / 99991 的下行 JSON 响应文本。
 *
 * 说明: 下行响应匹配靠 cmd (NetObserver.Post(cmd, obj)), hashCode 客户端不校验, 回 0 即可。
 * body 编码用明文 (dataType=1) 便于抓包核对; 客户端 default 分支对 dataType 1/3/5 自适应。
 */
object GameResponses {

    private val nf: JsonNodeFactory = JsonNodeFactory.instance
    private val mapper = jacksonObjectMapper()
    private val recruitSeq = AtomicInteger(0)

    /**
     * 99992 (平台校验 RequestPlatformLoginCheck) 响应。
     *
     * 客户端 OnPlatformLoginCheckResponse (SdkManager.cs:585) 期望 List<object>:
     *   val[0] = 校验结果 (1=成功; 非 1 触发 TriggerAutoLoginFailed)
     *   val[1] = 账号信息字典 (null 时走最干净分支: Uid = LoginServerUserId, 跳过全部实名/aas 解析)
     *   val[2] = ServerSession (进游戏硬凭证, 登录/连游戏服前会检查其非空)
     *   val[3] = LoginServerUserId (int)
     * val[5]/val[6] 可选 (封禁状态/角色数), 这里不下发 => Count=4, 客户端有 Count 保护。
     *
     * 最小成功响应 = [1, null, "<session>", userId]。
     */
    fun platformLoginCheck(userId: Int, serverSession: String): String {
        val root = nf.arrayNode()
        root.add(1)                                 // [0] 成功
        root.add(nf.nullNode())                     // [1] null -> 跳过实名/aas, Uid=LoginServerUserId
        root.add(serverSession)                     // [2] ServerSession
        root.add(userId)                            // [3] LoginServerUserId
        return mapper.writeValueAsString(root)
    }

    /** 99994 (PreServerCheckLoginToken) 响应。0 表示预登录校验通过并跳过附加查询。 */
    fun preServerTokenCheck(): String =
        mapper.writeValueAsString(nf.arrayNode().add(0))

    /**
     * 20003 (GetAllServerInfoNew) 响应。
     *
     * 顶层 = [status, payload]; 客户端只消费 packet[1] (=payload)。
     * payload 无条件访问 [0..5]:
     *   [0] 服务器列表 (表格式)  [1] 公告列表  [2] 已登录服列表
     *   [3] (int)IsGmAccount    [4] (int)登录服时间  [5] (string)isoCode
     *
     * 服务器列表表格式 (ParseServerList): [ [列名数组], [值行], [值行], ... ]
     *   - serverList[0] = 列名字符串数组 (必须是 ServerInfo 的真实字段名, 反射 SetValue)
     *   - serverList[1..] = 各服一行值数组, 顺序与列名对应, 支持 int/long/string
     *   - serverList.Count<=1 时客户端安全返回空列表。
     */
    fun serverList(
        serverId: Int,
        serverName: String,
        host: String,
        port: Int,
        runServerId: Int,
        cfgDbId: Int,
        openTime: Long,
    ): String {
        // 列名 (ServerInfo 字段名) 与 值行 一一对应。
        // 注意: ParseServerList 只认 int/long/string 三种列; has_role 是 bool, 由客户端
        // 依据 mLoggedServerDict 自行回填 (LoginData.cs:2166), 不能出现在列名里, 否则报
        // "转换失败字段: has_role" 并跳过该行赋值。
        val columns = listOf(
            "server_id", "entryid", "name", "host", "port",
            "server_port", "run_server_id", "cfg_db_id", "open_time",
        )
        val serverListNode = nf.arrayNode()
        // [0] 列名数组
        serverListNode.add(nf.arrayNode().apply { columns.forEach { add(it) } })
        // [1] 一行服务器数据
        serverListNode.add(
            nf.arrayNode().apply {
                add(serverId)          // server_id  (int)
                add(serverId)          // entryid    (int)
                add(serverName)        // name       (string)
                add(host)              // host       (string)
                add(port)              // port       (int)
                add(port)              // server_port(int)
                add(runServerId)       // run_server_id (int)
                add(cfgDbId)           // cfg_db_id  (int)
                add(openTime)          // open_time  (long)
            },
        )

        val payload = nf.arrayNode()
        payload.add(serverListNode)                 // [0] 服务器列表
        payload.add(nf.arrayNode())                 // [1] 公告列表 (空)
        // [2] 已登录服列表: 客户端 SaveLoggedInServerList 用 ((string)packet).Split(',')
        // 解析, 必须是逗号分隔的服务器 ID 字符串 (不是 JSON 数组), 否则 InvalidCastException。
        payload.add(runServerId.toString())         // [2] 已登录服列表 (逗号分隔字符串)
        payload.add(0)                              // [3] IsGmAccount
        payload.add((openTime).toInt())             // [4] 登录服时间
        payload.add("CN")                           // [5] isoCode

        val root = nf.arrayNode()
        root.add(0)                                 // [0] status
        root.add(payload)                           // [1] payload
        return mapper.writeValueAsString(root)
    }

    /**
     * 99991 (登录) 成功响应, 携带最小存档 (进主城)。
     *
     * Json 数组:
     *   [0] LoginState      = 1 (LOGIN_STATE_SUCCESS)
     *   [1] 时间同步数组    = [0, serverTimeSec, 0, 0]  (客户端无条件访问 [1] 和 [3])
     *   [2] LoginUserType   = 1 (老用户 -> 走 OnLoginSuccess 进城; 必须 1 或 2 才解析 Json[4])
     *   [3] cfgDataIndex    = 2001 (对应 APK 内置 map_game_data/2001)
     *   [4] EnterGameResult = [UserInitTable, login_notice, union_marks,
     *                          union_relations, national_techs, union_calendar]  (≥6 元素)
     *       其中 [0] 是 UserInitTable 数组, [1..5] 客户端仅 PutData, 空数组安全。
     */
    fun loginSuccess(
        userId: Int,
        cityWid: Int,
        roleName: String,
        serverTimeSec: Long,
        serverOpenTime: Long,
        cfgDataIndex: Int,
    ): String {
        val json = nf.arrayNode()
        json.add(1)                                             // [0] LoginState = SUCCESS

        val timeSync = nf.arrayNode()                           // [1] 时间同步
        timeSync.add(0)
        timeSync.add(serverTimeSec)                             // val[1] -> DoSyncServerTime
        timeSync.add(0)
        timeSync.add(0)                                         // val[3] -> BaseTimeDiff
        json.add(timeSync)

        json.add(1)                                             // [2] LoginUserType = 老用户
        json.add(cfgDataIndex)                                  // [3] cfgDataIndex

        json.add(enterGame(userId, cityWid, roleName, serverOpenTime)) // [4] EnterGameResult

        return mapper.writeValueAsString(json)
    }

    fun randomRoleName(): String = mapper.writeValueAsString("测试主公")

    /** 25 (GET_SERVER_TIME) 响应。客户端只读取数组第 0 项作为秒级 Unix 时间。 */
    fun serverTime(epochSeconds: Long): String =
        mapper.writeValueAsString(nf.arrayNode().add(epochSeconds))

    /**
     * 90006 (SYS_PING) 响应。客户端将该值视为服务器处理时长（纳秒），
     * 因此本地空实现返回 0，避免把单调时钟绝对值误当成延迟。
     */
    fun devicePing(serverProcessingNanos: Long): String =
        mapper.writeValueAsString(nf.arrayNode().add(serverProcessingNanos))

    fun serverTimeMillis(epochMillis: Long): String =
        mapper.writeValueAsString(nf.arrayNode().add(epochMillis))

    fun armyRelatedFort(): String =
        mapper.writeValueAsString(nf.objectNode().apply { putArray("4") })

    fun landInfo(wid: Int): String {
        val root = nf.arrayNode()
        repeat(54) { index ->
            when (index) {
                0 -> root.add(wid.coerceAtLeast(0))
                1, 2, 3, 9, 18, 50, 53 -> root.add("")
                4, 5, 7 -> root.add(100)
                8 -> root.addNull()
                14 -> root.add(1)
                else -> root.add(0)
            }
        }
        return mapper.writeValueAsString(root)
    }

    /**
     * 5026 (SEND_WORLD_SCENCE_FULL_INFO) 全量世界视野通知。
     *
     * MapDataCommon.ReceiveMapData 无条件读取 [0..29] 中的多个槽位；
     * 空视野也必须保留完整位置。第 18 槽为服务端序号，正值表示本帧已完成，
     * 客户端据此解除 5025 请求等待态。
     */
    fun worldSceneFullInfo(
        userId: Int,
        cityWid: Int,
        roleName: String,
        serverOrderId: Int = 1,
        march: PlayerMarch? = null,
        removedArmyId: Int? = null,
    ): String {
        val root = nf.arrayNode()
        root.add(nf.objectNode()) // 0: visual field
        root.add(worldMapUsers(userId, cityWid, roleName)) // 1: map users
        root.add(nf.arrayNode())  // 2: reserved
        root.add(nf.objectNode()) // 3: unions
        root.add(nf.objectNode()) // 4: strategies
        root.add(nf.objectNode()) // 5: nation strategies
        root.add(worldMapArmies(userId, cityWid, march, removedArmyId)) // 6: armies
        root.add(nf.arrayNode())  // 7: reserved
        root.add(nf.objectNode()) // 8: assist armies
        root.add(nf.arrayNode())  // 9: reserved
        root.add(nf.objectNode()) // 10: short messages
        root.add(nf.objectNode()) // 11: reserved
        root.add(nf.objectNode()) // 12: ext garrison
        root.add(nf.objectNode()) // 13: manor family
        root.add(worldCityChunk(userId, cityWid, roleName)) // 14: world chunks
        root.add(nf.arrayNode())  // 15: reserved
        root.add(nf.objectNode()) // 16: ext garrison changes
        root.add(nf.arrayNode())  // 17: reserved
        root.add(serverOrderId.coerceAtLeast(1)) // 18: server order id
        root.add(nf.objectNode()) // 19: manor family changes
        root.add(nf.objectNode()) // 20: block info
        root.add(nf.objectNode()) // 21: block armies
        root.add(nf.objectNode()) // 22: block ships
        root.add(nf.objectNode()) // 23: block assist armies
        root.add(nf.objectNode()) // 24: career support add
        root.add(nf.arrayNode())  // 25: career support remove
        root.add(nf.arrayNode())  // 26: reserved
        root.add(nf.arrayNode())  // 27: reserved
        root.add(nf.arrayNode())  // 28: reserved
        root.add(nf.objectNode()) // 29: real march
        return mapper.writeValueAsString(root)
    }

    /**
     * 5026[6] uses a compact map-army tuple. Indices 0..31 match
     * MapDataCommon.ReceiveNewArmyDataParam in the client.
     */
    private fun worldMapArmies(
        userId: Int,
        cityWid: Int,
        march: PlayerMarch?,
        removedArmyId: Int?,
    ) =
        nf.objectNode().apply {
            if (march == null) {
                removedArmyId?.let { putArray(it.toString()).add(0) }
                return@apply
            }
            putArray(march.armyId.toString()).apply {
                add(1) // 0 state: IN_EXPEDITION
                add(userId) // 1 user id
                add(march.fromWid) // 2 from wid
                add(march.targetWid) // 3 target wid
                add(march.beginSec) // 4 begin time
                add(march.endSec) // 5 end time
                add(0) // 6 army group id
                add(0) // 7 center wid
                add(0) // 8 shop cancel move
                add(0) // 9 target type
                add(cityWid) // 10 reside wid
                add(0) // 11 stay wid
                add(0) // 12 tech jianjun
                add(0) // 13 tech quanxiang
                add(0) // 14 invited user id
                add("") // 15 facade ids
                add("") // 16 army hero type
                add("") // 17 emotion
                add("") // 18 battle effect
                addNull() // 19 facade data; an empty array makes the client read index 0.
                add(nf.objectNode()) // 20 facade data by type
                add(0) // 21 serious injury time
                add(0) // 22 fort army group
                add(0) // 23 reside time
                add(0) // 24 siege camp next attack time
                add(0) // 25 attack-heart shiqi down
                add(0) // 26 countdown facade
                add(0) // 27 shiqi
                add(0) // 28 real march id
                add("") // 29 buffs
                add(0) // 30 lu jiao wid
                add("") // 31 battle show
            }
        }

    /** MapDataCommon.ReceiveNewUserDataParam 读取的 24 槽玩家信息。 */
    private fun worldMapUsers(userId: Int, cityWid: Int, roleName: String) =
        nf.objectNode().apply {
            putArray(userId.toString()).apply {
                add(roleName) // 0: name
                add(cityWid)  // 1: main wid
                repeat(9) { add(0) } // 2..10: union/official metadata
                add(0)        // 11: is_ai
                addNull()     // 12: union detail
                addNull()     // 13: affiliated union detail
                addNull()     // 14: clan detail
                repeat(6) { add(0) } // 15..20: seasonal metadata
                add("")       // 21: kite styles
                add("")       // 22: io title city
                add(0)        // 23: protect item end time
            }
        }

    /** 5026[14][wid]["0"]: ChunkMsgType.WORLD_CITY 的最小主城条目。 */
    private fun worldCityChunk(userId: Int, cityWid: Int, roleName: String) =
        nf.objectNode().apply {
            putObject(cityWid.toString()).putArray("0").apply {
                add(1)        // 0: CityType.PLAYER_MAIN_CITY
                add(0)        // 1: city_param
                add(userId)   // 2: owner user id
                add(0)        // 3: union id
                add(0)        // 4: protect_end_time
                add("")       // 5: facade
                add(roleName) // 6: name
                add(0)        // 7: belong city
                add(0)        // 8: state
                add(0)        // 9: guard_end_time
                add(0)        // 10: begin time
                add(0)        // 11: end time
                add(1)        // 12: force type
                add("")       // 13: city build data
                repeat(7) { add(0) } // 14..20: clan/link/view metadata
            }
        }

    /**
     * 301 (CARD_RECRUIT) 响应。
     *
     * 客户端 CardOpRequest.RespondCardSummon 固定读取:
     *   [0] summonUid, [1] cardList, [2] giveTechNums, [3] childCfgId, [4] technicNums/fireworkCount
     * cardList 每项至少 4 个 int: [heroUid, heroId, technicValue, unknown]。
     */
    fun cardRecruit(
        userId: Int,
        summonUid: Int,
        summonCfgId: Int,
        childCfgId: Int,
        summonOpType: Int = 0,
    ): String {
        val count = if (summonOpType == 0) 1 else 5
        val effectivePackId = childCfgId.takeIf { it > 0 } ?: summonCfgId
        val cards = recruitCards(effectivePackId, count)

        val root = nf.arrayNode()
        root.add(summonUid)
        root.add(cards)
        root.add(0)
        root.add(childCfgId)
        root.add(0)
        return mapper.writeValueAsString(root)
    }

    fun cardRecruit(userId: Int, summonUid: Int, childCfgId: Int, summonOpType: Int = 0): String =
        cardRecruit(userId, summonUid, summonCfgId = 801, childCfgId, summonOpType)

    fun quickCardRecruit(summonUid: Int, packId: Int, quickCount: Int): String {
        val cards = recruitCards(packId, quickCount.coerceIn(1, 10))
        val root = nf.arrayNode()
        root.add(summonUid)       // [0] summon uid
        root.add(0)               // [1] reserved
        root.add(0)               // [2] reserved
        root.add(0)               // [3] reserved
        root.add(0)               // [4] technic/firework
        root.add(nf.arrayNode().add(cards.size()).add(0).add(0).add(0).add(0)) // [5] result counts
        root.add(nf.arrayNode())  // [6] reserved
        root.add(cards)           // [7] card list
        return mapper.writeValueAsString(root)
    }

    fun quickCardRecruit(summonUid: Int, quickCount: Int): String =
        quickCardRecruit(summonUid, packId = 801, quickCount)

    private fun recruitCards(packId: Int, count: Int): ArrayNode {
        val heroPool = HeroCatalog.fiveStarHeroIdsForCardPack(packId)
        val start = recruitSeq.getAndAdd(count)
        val cards = nf.arrayNode()
        repeat(count) { offset ->
            val heroId = heroPool[(start + offset).mod(heroPool.size)]
            cards.add(
                nf.arrayNode()
                    .add(0)
                    .add(heroId)
                    .add(0)
                    .add(0),
            )
        }
        return cards
    }

    fun heroInsertNotify(userId: Int, heroUid: Int, heroId: Int, nowSec: Int): String =
        heroInsertNotify(userId, listOf(heroUid to heroId), nowSec)

    fun heroInsertNotify(userId: Int, heroes: List<Pair<Int, Int>>, nowSec: Int): String {
        val root = nf.arrayNode()
        heroes.forEach { (heroUid, heroId) ->
            root.add(
                nf.arrayNode()
                    .add(1) // NotifyType.Insert
                    .add("Tb_hero")
                    .add(tbHero(PlayerHero(heroUid = heroUid, heroId = heroId, createdAtSec = nowSec), userId)),
            )
        }
        return mapper.writeValueAsString(root)
    }

    fun heroUpsertNotify(userId: Int, heroes: List<PlayerHero>): String {
        val root = nf.arrayNode()
        heroes.forEach { hero ->
            root.add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_hero")
                    .add(tbHero(hero, userId)),
            )
        }
        return mapper.writeValueAsString(root)
    }

    fun userResourceUpsertNotify(userId: Int, resources: PlayerResources): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_user_res")
                    .add(tbUserRes(userId, resources)),
            ),
        )

    fun armyUpsertNotify(state: PlayerState): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_army")
                    .add(tbArmy(state)),
            ),
        )

    fun battleReportAttackInsertNotify(
        userId: Int,
        battleId: Int,
        armyId: Int,
        targetWid: Int,
        outcome: BattleOutcome,
        heroIds: List<Int>,
    ): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_battle_report_attack")
                    .add(
                        nf.arrayNode()
                            .add(battleId)
                            .add(userId)
                            .add(0)
                            .add(armyId)
                            .add(0)
                            .add(0)
                            .add(outcome.toArmyBattleResult())
                            .add(targetWid)
                            .add(0)
                            .add(0)
                            .add(heroIds.joinToString(","))
                            .add("")
                            .add(0)
                            .add(0),
                    ),
            ),
        )

    fun userBuildUpsertNotify(
        userId: Int,
        cityWid: Int,
        buildId: Int,
        level: Int,
        resources: PlayerResources? = null,
    ): String {
        val root = nf.arrayNode()
        root.add(
            nf.arrayNode()
                .add(1) // Insert path uses AddOrUpdateTb; same id overwrites existing row.
                .add("Tb_user_build")
                .add(tbUserBuild(userId, cityWid, buildId, level)),
        )
        root.add(
            nf.arrayNode()
                .add(1)
                .add("Tb_build_effect_city")
                .add(tbBuildEffectCity(userId, cityWid)),
        )
        if (resources != null) {
            root.add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_user_res")
                    .add(tbUserRes(userId, resources)),
            )
        }
        return mapper.writeValueAsString(root)
    }

    fun heroTeamLibrary(heroIds: List<Int>): String {
        val root = nf.objectNode()
        val res = root.putArray("res")
        res.add(nf.objectNode().set<ArrayNode>("rec", nf.arrayNode()))
        res.add(nf.objectNode().set<ArrayNode>("rec", nf.arrayNode().apply { heroIds.forEach { add(it) } }))
        return mapper.writeValueAsString(root)
    }

    fun normalTeamComposition(heroId: Int): String {
        val team = nf.objectNode()
        team.putArray("res").add(nf.objectNode().set<ArrayNode>("rec", nf.arrayNode()))
        return mapper.writeValueAsString(
            nf.arrayNode()
                .add(heroId)
                .add("")
                .add(mapper.writeValueAsString(team)),
        )
    }

    fun emptyArray(): String =
        mapper.writeValueAsString(nf.arrayNode())

    private fun tbUserRes(userId: Int, resources: PlayerResources): ArrayNode =
        nf.arrayNode().apply {
            add(userId)           // 0 userid
            add(0)                // 1 reserved
            add(resources.money)  // 2 money_cur
            add(resources.wood)   // 3 wood_cur
            add(resources.stone)  // 4 stone_cur
            add(resources.iron)   // 5 iron_cur
            add(resources.food)   // 6 food_cur
            add(0)
            add(0)
            add(PlayerResources.UNLIMITED_AMOUNT) // 9 wood_max
            add(PlayerResources.UNLIMITED_AMOUNT) // 10 stone_max
            add(PlayerResources.UNLIMITED_AMOUNT) // 11 iron_max
            add(PlayerResources.UNLIMITED_AMOUNT) // 12 food_max
        }

    private fun BattleOutcome.toArmyBattleResult(): Int =
        when (this) {
            BattleOutcome.ATTACKER_WIN -> 1
            BattleOutcome.DEFENDER_WIN -> 2
            BattleOutcome.DRAW -> 3
        }

    private fun tbHero(hero: PlayerHero, userId: Int): ArrayNode =
        nf.arrayNode().apply {
            add(hero.heroUid) // 0 heroid_u
            add(hero.heroId)  // 1 heroid
            add(userId)      // 2 userid
            add(hero.armyId) // 3 armyid
            add(0)           // 4 hurt_end_time
            add(0)           // 5 state
            add(hero.level)   // 6 level
            add(hero.stamina) // 7 energy
            add(0)           // 8 energy_add
            add(hero.createdAtSec) // 9 energy_time
            add(0)           // 10 exp
            add(hero.troops)  // 11 hp
            add(0)           // 12 hp_adding
            add(0)           // 13 hp_end_time
            add(0)           // 14 hp_balance_time
            add(0)           // 15 point_left
            add(0)           // 16 clean_point_time
            add(0)           // 17 attack_add
            add(0)           // 18 defence_add
            add(0)           // 19 intel_add
            add(0)           // 20 speed_add
            add(0)           // 21 destroy_add
            add("")          // 22 skill
            repeat(9) { add(0) } // 23..31 gear/state fields
            add(hero.heroType) // 32 hero_type
        }

    private fun tbArmy(state: PlayerState): ArrayNode {
        val team = state.teamHeroes()
        val march = state.activeMarch()
        return nf.arrayNode().apply {
            add(state.primaryArmyId())       // 0 armyid
            add(state.userId)                // 1 userid
            add(march?.fromWid ?: state.cityWid) // 2 reside_wid
            add(0)                           // 3 city_type
            add(march?.fromWid ?: state.cityWid) // 4 last_reside_wid
            add(team.getOrElse(2) { 0 })     // 5 front_heroid_u
            add(team.getOrElse(1) { 0 })     // 6 middle_heroid_u
            add(team.getOrElse(0) { 0 })     // 7 base_heroid_u
            add(0)                           // 8 counsellor_heroid_u
            add(0)                           // 9 army_formation_id
            add("")                          // 10 army_formation_effect
            add(if (march == null) 0 else 1) // 11 state: IN_EXPEDITION
            add(0)                           // 12 wait_count
            add(march?.targetWid ?: 0)       // 13 target_wid
            add(if (march == null) state.cityWid else 0) // 14 stay_wid
            add(march?.beginSec ?: 0)        // 15 reside_time
            add(march?.beginSec ?: 0)        // 16 begin_time
            add(march?.endSec ?: 0)          // 17 end_time
            add(100)
        }
    }

    private fun tbUserBuild(userId: Int, cityWid: Int, buildId: Int, level: Int): ArrayNode =
        nf.arrayNode().apply {
            add(cityWid * 1000 + buildId) // 0 id
            add(cityWid)                  // 1 city_wid
            add(buildId)                  // 2 build_id
            add(userId)                   // 3 userid
            add(level.coerceAtLeast(1))   // 4 level
            add(0)                        // 5 state
            add(0)                        // 6 queue
            add(0)                        // 7 effect_state
            add(0)                        // 8 end_time
            add(userId)                   // 9 build_userid
            add(1)                        // 10 build_completed
            add(0)                        // 11 build_count
            add("")                       // 12 upgrade_help_level
        }

    private fun tbBuildEffectCity(userId: Int, cityWid: Int): ArrayNode =
        nf.arrayNode().apply {
            add(cityWid)       // 0 city_wid
            add(userId)        // 1 userid
            add(10_000)        // 2 durability_max
            add(5)             // 3 reside_max
            add(100)           // 4 hp_max
            add(0)             // 5 recruit_time
            add("")            // 6 country_add_han
            add(0)
            add("")
            add(0)
            add("")
            add(0)
            add("")
            add(0)
            add("")
            add(0)
            add("")
            add(0)
            add(10)            // 18 recruit_redif_max
            add(0)
            add(0)
            add(0)
            add(0)
            add(5)             // 23 army_max
            add(0)             // 24 army_pos_counsellor
            add(1)             // 25 army_pos_front
            add(100)           // 26 army_cost_max => 10.0 cost
            repeat(4) { add(0) }
            add(1_000_000)     // 31 res_max
        }

    fun createRoleSuccess(
        userId: Int,
        cityWid: Int,
        roleName: String,
        serverOpenTime: Long,
    ): String {
        val json = nf.arrayNode()
        json.add(1) // [0] success; CreateRolePacket 用 [1] 解析 EnterGameResult
        json.add(enterGame(userId, cityWid, roleName, serverOpenTime))
        return mapper.writeValueAsString(json)
    }

    private fun enterGame(
        userId: Int,
        cityWid: Int,
        roleName: String,
        serverOpenTime: Long,
    ): ArrayNode {
        val enterGame: ArrayNode = nf.arrayNode()
        enterGame.add(UserInitTableBuilder.build(userId, cityWid, roleName, serverOpenTime)) // [0] UserInitTable
        enterGame.add(nf.arrayNode())                           // [1] login_notice
        enterGame.add(nf.arrayNode())                           // [2] union_marks
        enterGame.add(nf.arrayNode())                           // [3] union_relations
        enterGame.add(nf.arrayNode())                           // [4] national_techs
        enterGame.add(nf.arrayNode())                           // [5] union_calendar
        return enterGame
    }
}
