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
    private val landDefenders: LandDefenderFactory by lazy(::LandDefenderFactory)

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

    fun platformLoginFailure(): String =
        mapper.writeValueAsString(nf.arrayNode().add(0).addNull().add("").add(0))

    /** 99994 (PreServerCheckLoginToken) 响应。0 表示预登录校验通过并跳过附加查询。 */
    fun preServerTokenCheck(): String =
        mapper.writeValueAsString(nf.arrayNode().add(0))

    fun prebookServerInfo(): String =
        mapper.writeValueAsString(
            nf.objectNode().apply {
                putArray("prebook_info")
                putArray("del_prebook")
                putArray("prebook_list")
            },
        )

    fun communityUserTokenRejection(): String =
        mapper.writeValueAsString(nf.arrayNode().add(0).add("").add(""))

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
     *   [3] cfgDataIndex    = 2001 (当前客户端内置且可登录的配置)
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
        accountKey: String? = null,
        world: WorldProjection = WorldProjection.EMPTY,
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

        json.add(enterGame(userId, cityWid, roleName, serverOpenTime, accountKey, world)) // [4] EnterGameResult

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
        mapper.writeValueAsString(
            nf.objectNode().apply {
                putArray("33")
                putArray("4")
            },
        )

    fun landInfo(wid: Int): String {
        val npcArmyCount = runCatching {
            landDefenders.teamCountForLevel(landDefenders.levelForWid(wid))
        }.getOrDefault(0)
        val root = nf.arrayNode()
        repeat(54) { index ->
            when (index) {
                0 -> root.add(wid.coerceAtLeast(0))
                1, 2, 3, 9, 18, 50, 53 -> root.add("")
                4, 5, 7 -> root.add(100)
                8 -> root.addNull()
                10, 11 -> root.add(npcArmyCount)
                14 -> root.add(1)
                else -> root.add(0)
            }
        }
        return mapper.writeValueAsString(root)
    }

    /**
     * LandDetailMainPanel.RespondDefendersRecovery only accepts a two-element
     * array and reads index 1 as ArmyRecoverTimestamp.
     */
    fun landNpcArmy(wid: Int): String =
        mapper.writeValueAsString(listOf(wid.coerceAtLeast(0), 0L))

    /**
     * GET_USER_NPC_ARMY (4329): the map guard renderer expects the selected
     * Tcfg_army ids as a comma-separated string at index 1.
     */
    fun userNpcArmy(wid: Int): String =
        mapper.writeValueAsString(
            listOf(
                wid.coerceAtLeast(0),
                defenderArmyIds(wid).joinToString(","),
            ),
        )

    /**
     * GET_LAND_DEFEND_ARMY (4331): the defender detail panel reads the same
     * army-id string that the map guard renderer uses.
     */
    fun landDefenderArmy(wid: Int): String =
        userNpcArmy(wid)

    private fun defenderArmyIds(wid: Int): List<Int> =
        runCatching { landDefenders.armyIdsForWid(wid) }.getOrDefault(emptyList())

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
        marches: Collection<PlayerMarch> = march?.let(::listOf).orEmpty(),
        removedArmyId: Int? = null,
        occupiedLands: Set<Int> = emptySet(),
        world: WorldProjection = WorldProjection.EMPTY,
    ): String {
        val worldProjection = world.withPlayer(userId, cityWid, roleName, occupiedLands)
        val root = nf.arrayNode()
        root.add(nf.objectNode()) // 0: visual field
        root.add(worldMapUsers(worldProjection)) // 1: map users
        root.add(nf.objectNode()) // 2: reserved
        root.add(nf.objectNode()) // 3: unions
        root.add(nf.objectNode()) // 4: strategies
        root.add(nf.objectNode()) // 5: nation strategies
        root.add(worldMapArmies(userId, cityWid, marches, removedArmyId)) // 6: armies
        root.add(nf.arrayNode())  // 7: reserved
        root.add(nf.objectNode()) // 8: assist armies
        root.add(nf.arrayNode())  // 9: reserved
        root.add(nf.objectNode()) // 10: short messages
        root.add(nf.objectNode()) // 11: reserved
        root.add(nf.objectNode()) // 12: ext garrison
        root.add(nf.objectNode()) // 13: manor family
        root.add(worldCityChunk(worldProjection)) // 14: world chunks
        root.add(nf.arrayNode())  // 15: reserved
        root.add(nf.objectNode()) // 16: ext garrison changes
        root.addNull()            // 17: reserved
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
        root.addNull()            // 30: reserved (present in 9.2.2 captures)
        return mapper.writeValueAsString(root)
    }

    /**
     * 5026[6] uses a compact map-army tuple. Indices 0..31 match
     * MapDataCommon.ReceiveNewArmyDataParam in the client.
     */
    private fun worldMapArmies(
        userId: Int,
        cityWid: Int,
        marches: Collection<PlayerMarch>,
        removedArmyId: Int?,
    ) =
        nf.objectNode().apply {
            if (marches.isEmpty()) {
                removedArmyId?.let { putArray(it.toString()).add(0) }
                return@apply
            }
            marches.forEach { march ->
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
                add(march.facadeIds()) // 15 facade ids
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
            removedArmyId?.takeIf { removedId -> marches.none { it.armyId == removedId } }
                ?.let { putArray(it.toString()).add(0) }
        }

    /** MapDataCommon.ReceiveNewUserDataParam 读取的 24 槽玩家信息。 */
    private fun worldMapUsers(world: WorldProjection) =
        nf.objectNode().apply {
            world.cities.forEach { city ->
                putArray(city.userId.toString()).apply {
                    add(city.roleName) // 0: name
                    add(city.cityWid)  // 1: main wid
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
        }

    /** 5026[14][wid]["0"]: ChunkMsgType.WORLD_CITY 的最小主城条目。 */
    private fun worldCityChunk(world: WorldProjection) =
        nf.objectNode().apply {
            world.cities.forEach { city ->
                putWorldCity(
                    wid = city.cityWid,
                    cityType = 1,
                    userId = city.userId,
                    name = city.roleName,
                    belongCity = 0,
                    customView = city.customView,
                )
                HomeCity.suburbWids(city.cityWid).forEach { suburbWid ->
                    putWorldCity(
                        wid = suburbWid,
                        cityType = 5,
                        userId = city.userId,
                        name = "",
                        belongCity = city.cityWid,
                    )
                }
            }
            world.lands.forEach { claim ->
                if (!has(claim.wid.toString())) {
                    putWorldCity(
                        wid = claim.wid,
                        cityType = 2,
                        userId = claim.userId,
                        name = "",
                        belongCity = claim.belongCity,
                    )
                }
            }
        }

    private fun com.fasterxml.jackson.databind.node.ObjectNode.putWorldCity(
        wid: Int,
        cityType: Int,
        userId: Int,
        name: String,
        belongCity: Int,
        customView: String? = null,
    ) {
        val isMainCity = cityType == 1
        val cityChunk = putObject(wid.toString())
        cityChunk.putArray("0").apply {
            add(cityType)
            add(0)        // 1: city_param
            add(userId)   // 2: owner user id
            add(0)        // 3: union id
            add(0)        // 4: protect_end_time
            add(if (isMainCity) FacadeCatalog.DEFAULT_CITY_MAP_FACADE else "") // 5: facade
            add(name)     // 6: name
            add(belongCity) // 7: belong city
            repeat(4) { add(0) } // 8..11: state/times
            add(0)        // 12: UserForceType.NORMAL
            add(if (isMainCity) FacadeCatalog.DEFAULT_CITY_BUILD_DATA else "") // 13: city build data
            repeat(7) { add(0) } // 14..20: clan/link/view metadata
        }
        if (isMainCity) {
            cityChunk.putArray("4")
                .add(customView ?: FacadeCatalog.DEFAULT_CITY_CUSTOM_VIEW)
                .add("")
        }
    }

    /**
     * 301 (CARD_RECRUIT) 响应。
     *
     * 客户端 CardOpRequest.RespondCardSummon 固定读取:
     *   [0] summonUid, [1] cardList, [2] giveTechNums, [3] childCfgId, [4] technicNums/fireworkCount
     * cardList 每项至少 5 个 int:
     *   [heroUid, heroId, technicValue, unknown, hasAdvanced]。
     * CardSummonResultPage 对缺少 hasAdvanced 的旧四列格式默认取 1，
     * 因而必须显式下发 0 才不会显示“已自动进阶”。
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
        if (heroPool.isEmpty()) return nf.arrayNode()
        val start = recruitSeq.getAndAdd(count)
        val cards = nf.arrayNode()
        repeat(count) { offset ->
            val heroId = heroPool[(start + offset).mod(heroPool.size)]
            cards.add(
                nf.arrayNode()
                    .add(0)
                    .add(heroId)
                    .add(0)
                    .add(0)
                    .add(0), // hasAdvanced
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

    /**
     * Real cmd=90005 update packets use NotifyType.Update (2) and sparse
     * index/value pairs. Tb_hero's primary key is field 0.
     */
    fun heroSkillUpdateNotify(hero: PlayerHero): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(2)
                    .add("Tb_hero")
                    .add(
                        nf.arrayNode()
                            .add(0).add(hero.heroUid)
                            .add(22).add(hero.skillString())
                            .add(24).add(1),
                    ),
            ),
        )

    fun gearEquipNotify(result: GearEquipResult): String =
        mapper.writeValueAsString(
            nf.arrayNode().apply {
                result.heroGearUids.toSortedMap().forEach { (heroUid, gearUid) ->
                    add(
                        nf.arrayNode()
                            .add(2)
                            .add("Tb_hero")
                            .add(nf.arrayNode().add(0).add(heroUid).add(23).add(gearUid)),
                    )
                }
                result.gearHeroUids.toSortedMap().forEach { (gearUid, heroUid) ->
                    add(
                        nf.arrayNode()
                            .add(2)
                            .add("Tb_gear")
                            .add(nf.arrayNode().add(0).add(gearUid).add(9).add(heroUid)),
                    )
                }
            },
        )

    fun armyFacadeNotify(state: PlayerState, mutation: ArmyFacadeMutation): String =
        mapper.writeValueAsString(
            nf.arrayNode().apply {
                mutation.cardCfgHeroIds.toSortedMap().forEach { (cardId, cfgHeroId) ->
                    add(
                        nf.arrayNode()
                            .add(2)
                            .add("Tb_user_army_facade_card")
                            .add(nf.arrayNode().add(0).add(cardId).add(5).add(cfgHeroId)),
                    )
                }
                mutation.heroFacadeIds.toSortedMap().forEach { (heroUid, facadeId) ->
                    add(
                        nf.arrayNode()
                            .add(2)
                            .add("Tb_hero")
                            .add(nf.arrayNode().add(0).add(heroUid).add(72).add(facadeId)),
                    )
                }
                mutation.specialCardStates.toSortedMap().forEach { (cardUid, stateValue) ->
                    add(
                        nf.arrayNode()
                            .add(2)
                            .add("Tb_hero")
                            .add(nf.arrayNode().add(0).add(cardUid).add(5).add(stateValue)),
                    )
                }
                mutation.affectedArmyIds.sorted().forEach { armyId ->
                    add(
                        nf.arrayNode()
                            .add(2)
                            .add("Tb_army")
                            .add(nf.arrayNode().add(0).add(armyId).add(61).add(state.armyFacadeIds(armyId))),
                    )
                }
            },
        )

    fun heroCardBorderUpdateNotify(heroUid: Int, cardBorder: Int): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(2)
                    .add("Tb_hero")
                    .add(nf.arrayNode().add(0).add(heroUid).add(42).add(cardBorder)),
            ),
        )

    fun ordinaryRevenueUpdateNotify(state: PlayerState): String =
        mapper.writeValueAsString(
            nf.arrayNode()
                .add(revenueResourceUpdate(state))
                .add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_user_revenue")
                        .add(
                            nf.arrayNode()
                                .add(0).add(state.userId)
                                .add(1).add(RevenueService.revenueInfo(state.revenue))
                                .add(2).add(state.revenue.revenueTime)
                                .add(3).add(state.revenue.nextRefreshTime)
                                .add(6).add(RevenueService.lastRevenueInfo(state.revenue))
                                .add(7).add(""),
                        ),
                ),
        )

    fun doubleRevenueUpdateNotify(state: PlayerState): String =
        mapper.writeValueAsString(
            nf.arrayNode()
                .add(revenueResourceUpdate(state))
                .add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_user_revenue")
                        .add(
                            nf.arrayNode()
                                .add(0).add(state.userId)
                                .add(6).add(RevenueService.lastRevenueInfo(state.revenue)),
                        ),
                ),
        )

    private fun revenueResourceUpdate(state: PlayerState): ArrayNode =
        nf.arrayNode()
            .add(2)
            .add("Tb_user_res")
            .add(
                nf.arrayNode()
                    .add(0).add(state.userId)
                    .add(1).add(state.resources.moneyAccumulated)
                    .add(2).add(state.resources.money),
            )

    /**
     * cmd 83 itself is body-agnostic in the client. The visible card update is
     * driven by this 90005 packet: update advance_num, then remove each
     * consumed same-name material card by primary key.
     */
    fun heroAdvanceNotify(
        heroUid: Int,
        advanceNum: Int,
        consumedMaterialUids: Collection<Int>,
    ): String =
        mapper.writeValueAsString(
            nf.arrayNode().apply {
                add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_hero")
                        .add(nf.arrayNode().add(0).add(heroUid).add(29).add(advanceNum)),
                )
                consumedMaterialUids.distinct().forEach { materialUid ->
                    add(nf.arrayNode().add(3).add("Tb_hero").add(materialUid))
                }
            },
        )

    fun cardPacksSeenNotify(summonUids: Collection<Int>): String {
        val root = nf.arrayNode()
        summonUids.distinct().forEach { summonUid ->
            root.add(
                nf.arrayNode()
                    .add(2)
                    .add("Tb_user_card_extract")
                    .add(
                        nf.arrayNode()
                            .add(0).add(summonUid)
                            .add(7).add(0),
                    ),
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

    fun unionInfo(union: PlayerUnion): String {
        val fields = nf.objectNode().apply {
            put("union_id", union.unionId)
            put("name", union.name)
            put("leader_id", union.leaderUserId)
            put("vice_leader_id", 0)
            put("create_time", union.createdAtSec.toLong())
            put("level", 1)
            put("total_member", union.memberUserIds.size)
            put("clan_lst", "")
            put("tip", "")
            put("notice", "")
            put("apply_state", 1)
            put("power", 0)
            put("nation_strategy_count", 0)
            put("tech_point", 0)
            put("tech_layer", 1)
            put("tech_point_add", 0)
            put("region_spread", "")
            put("region", 0)
            put("area", 0)
            put("npc_city_member_add", 0)
            put("next_establish_nation_time", 0L)
            put("next_move_capital_time", 0L)
            put("force", 0)
            put("nation_last_denunciation_send_time", 0L)
            put("disintegrate_info", "")
            put("joined_union_id", 0)
            put("quit_nation_time", 0L)
            put("zhaoxian_count", 0)
            put("last_zhaoxian_count_reset_time", 0L)
            put("merge_union_state", 0)
            put("last_merge_union_time", 0L)
            put("active_score", 0)
            put("auto_join_apply", 0)
            put("zi_li_end_time", 0L)
            put("zhou_fu_count", 0)
            put("auto_join_review_switch", 0)
            put("auto_join_review_switch_power_condition", 0)
            put("bandit_free_denunciation_send_count", 0)
        }
        val detail = nf.arrayNode().apply {
            add(0)                         // applyed
            add(union.leaderRoleName)
            add(0)                         // area_number
            add(0)                         // under_number
            add(fields)
            add(0L)                        // demise_end_time
            add(0)                         // demise_target_uid
            add(nf.arrayNode())            // strategy_list
            add(0)                         // invite_enter_state
            add(0).add(0).add(0)
            add(0)                         // certification_channel
            add(0).add(0).add(0).add(0)
            add("role_${union.leaderUserId}")
            add(0)                         // applyed_standby
            add(0)                         // exp
        }
        return mapper.writeValueAsString(nf.arrayNode().add(0).add(detail))
    }

    fun userUnionUpdateNotify(userId: Int, union: PlayerUnion): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(2)
                    .add("Tb_user")
                    .add(
                        nf.arrayNode()
                            .add(0).add(userId)
                            .add(10).add(union.unionId)
                            .add(11).add(union.name),
                    ),
            ),
        )

    fun unionMembers(union: PlayerUnion): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(union.leaderUserId) // user_id
                    .add(union.leaderRoleName)
                    .add(0)                  // donate
                    .add(1)                  // position = leader
                    .add(0)                  // is_demise
                    .add(0)                  // is_affiliated
                    .add(0)                  // wid
                    .add(0)                  // donate_weekly
                    .add(0)                  // role_force
                    .add(0)                  // official_wid
                    .add(0)                  // val_wuxun
                    .add(0)
                    .add(0)                  // group_id
                    .add("")                 // group_name
                    .add(0)                  // own_city_id
                    .add(0)                  // ranger_total_wuxun
                    .add(0)                  // head_id
                    .add("")                 // head_frame
                    .add(0),                 // clan_position
            ),
        )

    fun unionChatMembers(union: PlayerUnion): String {
        val rows = nf.arrayNode()
        union.memberUserIds.sorted().forEach { memberUserId ->
            rows.add(
                nf.arrayNode()
                    .add(memberUserId)
                    .add(0)
                    .add(""),
            )
        }
        return mapper.writeValueAsString(rows)
    }

    fun armyUpsertNotify(state: PlayerState, armyId: Int = state.primaryArmyId()): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_army")
                    .add(tbArmy(state, armyId)),
            ),
        )

    /**
     * The client dispatches table events after every 90005 packet. Keep hero
     * rows and their referencing army row in one packet, with heroes first, so
     * an army refresh can never observe an unknown hero uid.
     */
    fun armyAndHeroesUpsertNotify(
        state: PlayerState,
        heroes: List<PlayerHero>,
        armyIds: Collection<Int> = listOf(state.primaryArmyId()),
    ): String {
        val root = nf.arrayNode()
        heroes.distinctBy { it.heroUid }.forEach { hero ->
            root.add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_hero")
                    .add(tbHero(hero, state.userId)),
            )
        }
        armyIds.distinct().forEach { armyId ->
            root.add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_army")
                    .add(tbArmy(state, armyId)),
            )
        }
        return mapper.writeValueAsString(root)
    }

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

    fun occupiedLandUpsertNotify(
        userId: Int,
        cityWid: Int,
        landWid: Int,
    ): String =
        mapper.writeValueAsString(
            nf.arrayNode().add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_world_city")
                    .add(
                        nf.arrayNode().apply {
                            add(landWid)  // 0 wid
                            add(2)        // 1 city_type = player land
                            add(0)        // 2 param
                            add("")       // 3 facade
                            add("")       // 4 facade3d
                            add("")       // 5 name
                            add(userId)   // 6 userid
                            add(0)        // 7 farmer_userid
                            add(0)        // 8 union_id
                            add(0)        // 9 clan_id
                            add(userId)   // 10 op_userid
                            add(1)        // 11 force_type = normal
                            add(100)      // 12 durability_cur
                            add(100)      // 13 durability_max
                            add(0)        // 14 durability_time
                            add(0)        // 15 durability_add_ratio
                            add(0)        // 16 protect_end_time
                            add(0)        // 17 flied_protect_end_time
                            add(0)        // 18 begin_time
                            add(0)        // 19 end_time
                            add(0)        // 20 first_end_time
                            add(cityWid)  // 21 belong_city
                            add(0)        // 22 state
                        },
                    ),
            ),
        )

    fun userBuildUpsertNotify(
        state: PlayerState,
        buildId: Int,
        level: Int,
        resources: PlayerResources? = null,
    ): String {
        val userId = state.userId
        val cityWid = state.cityWid
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
        state.armyIds().forEach { armyId ->
            root.add(
                nf.arrayNode()
                    .add(1)
                    .add("Tb_army")
                    .add(tbArmy(state, armyId)),
            )
        }
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
            BattleOutcome.DEFENDER_WIN -> 0
            BattleOutcome.DRAW -> 6
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
            add(PlayerHero.MAX_STAMINA) // 7 energy: infinite stamina
            add(0)           // 8 energy_add
            add(hero.createdAtSec) // 9 energy_time
            add(0)           // 10 exp
            add(hero.troops)  // 11 hp
            add(0)           // 12 hp_adding
            add(0)           // 13 hp_end_time
            add(0)           // 14 hp_balance_time
            add(0)           // 15 point_left
            add(0)           // 16 clean_point_time
            add(hero.attributePoints.attack)   // 17 attack_add
            add(hero.attributePoints.defense)  // 18 defence_add
            add(hero.attributePoints.strategy) // 19 intel_add
            add(hero.attributePoints.speed)    // 20 speed_add
            add(hero.attributePoints.siege)    // 21 destroy_add
            add(hero.skillString()) // 22 skill
            add(hero.gearUid) // 23 gearid_u
            add(1) // 24 awake_state
            add(0) // 25 lock_state
            add(0) // 26 wounded_soldier
            add(0) // 27 dead_soldier
            add(0) // 28 hide_hp
            add(hero.advanceNum) // 29 advance_num
            add(0) // 30 second_skill_effect
            add(0) // 31 hero_type_effect
            add(hero.heroType) // 32 hero_type
            add("") // 33 hero_type_ext
            add("") // 34 hero_type_availible
            add("") // 35 hero_type_feature
            add(0) // 36 hero_type_advance
            add(hero.heroFeaturesString()) // 37 hero_features: feature_id, enabled
            repeat(4) { add(0) } // 38..41 feature fields
            add(hero.cardBorder) // 42 card_border
            add(hero.dynamicIcon) // 43 dynamic_icon
            repeat(25) { add(0) } // 44..68
            add("") // 69 recurit_unit_res_cost
            add(0) // 70 read_time
            add(0) // 71 get_time
            add(hero.armyFacadeCardId) // 72 army_facade_card_id
        }

    private fun tbArmy(state: PlayerState, armyId: Int): ArrayNode {
        val team = state.teamHeroes(armyId)
        val march = state.activeMarch(armyId)
        return nf.arrayNode().apply {
            add(armyId)                      // 0 armyid
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
            add(HomeCity.userBuildId(cityWid, buildId)) // 0 id
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
            add(PlayerHero.MAX_TROOPS - PlayerHero.DEFAULT_LEVEL * 100) // 4 hp_max
            add(0)             // 5 recruit_time
            add("295010")      // 6 country_add_han
            add(PlayerState.MAX_COUNTRY_BUILD_LEVEL)
            add("295020")
            add(PlayerState.MAX_COUNTRY_BUILD_LEVEL)
            add("295030")
            add(PlayerState.MAX_COUNTRY_BUILD_LEVEL)
            add("295040")
            add(PlayerState.MAX_COUNTRY_BUILD_LEVEL)
            add("295050")
            add(PlayerState.MAX_COUNTRY_BUILD_LEVEL)
            add("295140")
            add(PlayerState.MAX_COUNTRY_BUILD_LEVEL)
            add(10)            // 18 recruit_redif_max
            add(0)
            add(0)
            add(0)
            add(0)
            add(5)             // 23 army_max
            add(0)             // 24 army_pos_counsellor
            add(5)             // 25 army_pos_front
            add(100)           // 26 army_cost_max => 10.0 cost
            repeat(4) { add(0) }
            add(1_000_000)     // 31 res_max
        }

    fun createRoleSuccess(
        userId: Int,
        cityWid: Int,
        roleName: String,
        serverOpenTime: Long,
        accountKey: String? = null,
        world: WorldProjection = WorldProjection.EMPTY,
    ): String {
        val json = nf.arrayNode()
        json.add(1) // [0] success; CreateRolePacket 用 [1] 解析 EnterGameResult
        json.add(enterGame(userId, cityWid, roleName, serverOpenTime, accountKey, world))
        return mapper.writeValueAsString(json)
    }

    private fun enterGame(
        userId: Int,
        cityWid: Int,
        roleName: String,
        serverOpenTime: Long,
        accountKey: String? = null,
        world: WorldProjection = WorldProjection.EMPTY,
    ): ArrayNode {
        val enterGame: ArrayNode = nf.arrayNode()
        enterGame.add(UserInitTableBuilder.build(userId, cityWid, roleName, serverOpenTime, accountKey, world)) // [0] UserInitTable
        enterGame.add(nf.arrayNode())                           // [1] login_notice
        enterGame.add(nf.arrayNode())                           // [2] union_marks
        enterGame.add(nf.arrayNode())                           // [3] union_relations
        enterGame.add(nf.arrayNode())                           // [4] national_techs
        enterGame.add(nf.arrayNode())                           // [5] union_calendar
        return enterGame
    }
}
