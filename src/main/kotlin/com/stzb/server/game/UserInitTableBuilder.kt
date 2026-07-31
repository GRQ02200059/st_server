package com.stzb.server.game

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.protocol.GameServerConfig

/*
 * 构造 99991 登录响应里的 UserInitTable (数据库初始快照)。
 *
 * 客户端解析逻辑 (DbNotify.ReadFromReader / Tb.ReadFromReader):
 *   UserInitTable 是一个 数组:
 *     [ "<schema 巨串>", ["Tb_user",[[行值...],...]], ["Tb_user_res",[[...]]], ... ]
 *   - 第 0 元素是 schema 字符串, 必须与客户端硬编码 (357 表 6215 字段) 逐字节相等,
 *     否则客户端走 DumpTbFieldsDiff 分支, 结果 result=null => InitTableFailed => 登录失败。
 *   - 其后每个元素是二元数组 "表名" + 行数组集合。
 *   - 每一行是纯值数组 (无字段名), 客户端按 SetValueAt(index) 顺序赋值;
 *     行数组可比字段总数短, 只填前 N 个字段, 其余走默认值。
 *   - 每个值的 JSON 类型必须匹配取值器: int/long->整数 / string->字符串 / double->浮点 / bool->true|false。
 *     因此中间跳过的字段 (padding) 也必须按其真实类型填默认值, 否则 Utf8JsonReader 抛异常。
 *
 * P0 只需能进主城的最小五表 (其余表客户端多有 ?. 保护, 降级默认值):
 *   Tb_user       - 账号主记录 (userid/city_wid/force/name/role_id/newbie_guide 等)
 *   Tb_user_res   - 资源
 *   Tb_user_city  - 主城 (key=city_wid)
 *   Tb_world_city - 世界地图上的主城格子 (key=wid == city_wid, end_time 无 ?. 保护)
 *   Tb_user_stuff - 杂项 (occupy_land_level / protected_popup 无 ?. 保护)
 *
 * 三键对齐: 所有表 userid 一致; Tb_user.city_wid == Tb_user_city.city_wid == Tb_world_city.wid。
 */
object UserInitTableBuilder {

    private val nf: JsonNodeFactory = JsonNodeFactory.instance
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val allSkillIds: List<Int> = SkillInventoryCatalog.allSkillIds()

    /** 客户端硬编码 schema 巨串 (98766 字节, 与 db_schema.txt 逐字节一致)。 */
    private val schema: String by lazy {
        UserInitTableBuilder::class.java.getResourceAsStream("/db_schema.txt")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("db_schema.txt 未找到 (classpath:/db_schema.txt)")
    }

    /** 各表字段类型清单 (从 Tb_*.cs 的 SetValueAt 提取): 表名 -> [ [字段名, 类型], ... ]。
     *  类型 ∈ {int,long,double,string,bool}。用于 padding 时按真实类型填默认值。 */
    private val fieldTypes: Map<String, List<String>> by lazy {
        val stream = UserInitTableBuilder::class.java.getResourceAsStream("/tb_field_types.json")
            ?: error("tb_field_types.json 未找到 (classpath:/tb_field_types.json)")
        val raw: Map<String, List<List<String?>>> = mapper.readValue(
            stream,
            mapper.typeFactory.constructMapType(
                LinkedHashMap::class.java,
                mapper.typeFactory.constructType(String::class.java),
                mapper.typeFactory.constructCollectionType(
                    List::class.java,
                    mapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
                ),
            ),
        )
        // 每张表取每个字段的类型 (第二列), 空洞 (字段名为 null) 也保留其类型。
        raw.mapValues { (_, cols) -> cols.map { it.getOrNull(1) ?: "int" } }
    }

    /**
     * 构造 UserInitTable 数组。
     *
     * @param userId  账号 id (= Tb_user.userid, 全表对齐)
     * @param cityWid 主城 wid (= Tb_user.city_wid = Tb_user_city.city_wid = Tb_world_city.wid)
     * @param roleName 角色显示名
     * @param serverOpenTime 服务器开服秒级时间戳 (用于 end_time 等 "已过期" 判定)
     */
    fun build(
        userId: Int,
        cityWid: Int,
        roleName: String,
        serverOpenTime: Long,
        accountKey: String? = null,
        world: WorldProjection = WorldProjection.EMPTY,
    ): ArrayNode {
        val state = accountKey?.let {
            PlayerStateRepository.getOrCreate(it, cityWid, roleName)
        } ?: PlayerStateRepository.getOrCreate(userId, cityWid, roleName)
        state.ensureAdvanceMaterials()
        val playerId = state.userId
        val playerCityWid = state.cityWid
        val worldProjection = world.withPlayer(state)
        val root = nf.arrayNode()
        root.add(schema)                                   // [0] schema
        root.add(table("Tb_user", tbUser(state)))
        root.add(table("Tb_user_res", tbUserRes(state)))
        root.add(table("Tb_user_city", tbUserCity(playerId, playerCityWid, serverOpenTime)))
        root.add(table("Tb_world_city", *tbWorldCities(worldProjection).toTypedArray()))
        root.add(
            table(
                "Tb_user_build",
                tbUserBuild(playerId, playerCityWid, 10, 1),
                tbUserBuild(playerId, playerCityWid, 30, PlayerState.maxBuildLevel(30)),
            ),
        )
        root.add(table("Tb_build_effect_city", tbBuildEffectCity(playerId, playerCityWid)))
        root.add(table("Tb_user_inner_city", tbUserInnerCity(playerId)))
        // 客户端会用内城配置补齐缺省建筑，暂不伪造不存在的 wid。
        root.add(table("Tb_user_inner_city_building"))
        root.add(table("Tb_user_inner_city_task"))
        root.add(table("Tb_army", tbArmy(state)))
        root.add(table("Tb_activity", tbActivity(playerId)))
        root.add(
            table(
                "Tb_sys_param",
                tbSysParam(12, "4"),
                tbSysParam(26, GameServerConfig.CFG_DB_ID.toString()),
            ),
        )
        root.add(
            table(
                "Tb_user_card_extract",
                *ClientCardPackCatalog.allPacks().map { pack ->
                    tbUserCardExtract(
                        userId = playerId,
                        extractId = ClientCardPackCatalog.summonUid(playerId, pack.packId),
                        refreshWayId = pack.packId,
                        serverOpenTime = serverOpenTime,
                        isNew = !state.cardPacksSeen,
                    )
                }.toTypedArray(),
            ),
        )
        root.add(table("Tb_hero", *state.allHeroes().map { tbHero(it, playerId, state.primaryArmyId()) }.toTypedArray()))
        root.add(
            table(
                "Tb_user_skill",
                *allSkillIds.mapIndexed { index, skillId ->
                    tbUserSkill(playerId, index, skillId)
                }.toTypedArray(),
            ),
        )
        root.add(
            table(
                "Tb_user_facade_card",
                *HeroFacadeCatalog.all().mapIndexed { index, facade ->
                    tbUserFacadeCard(playerId, index, facade)
                }.toTypedArray(),
            ),
        )
        root.add(
            table(
                "Tb_user_army_facade_card",
                *FacadeCatalog.armyFacadeIds.map { facadeId ->
                    tbUserArmyFacadeCard(playerId, facadeId)
                }.toTypedArray(),
            ),
        )
        root.add(
            table(
                "Tb_user_build_facade",
                *FacadeCatalog.cityFacadeIds.map { facadeId ->
                    tbUserBuildFacade(playerId, facadeId)
                }.toTypedArray(),
            ),
        )
        root.add(table("Tb_user_stuff", tbUserStuff(playerId)))
        root.add(table("Tb_user_stuff_ex", tbUserStuffEx(playerId)))
        root.add(table("Tb_user_stuff_one", tbUserStuffOne(playerId)))
        root.add(table("Tb_user_stuff_temp", tbUserStuffTemp(playerId)))
        root.add(table("Tb_user_stuff_temp_ex", tbUserStuffTempEx(playerId)))
        root.add(table("Tb_user_stuff_temp_one", tbUserStuffTempOne(playerId)))
        addEmptyTables(
            root,
            "Tb_hero_temp",
            "Tb_hero_identity",
            "Tb_gear",
            "Tb_battle_report_attack",
            "Tb_battle_report_defend",
            "Tb_battle_report_exersice",
            "Tb_mail_receive",
            "Tb_mail_send",
            "Tb_army_slot",
            "Tb_army_policy",
            "Tb_user_city_control",
            "Tb_build_effect_user",
            "Tb_task",
            "Tb_task_completed",
            "Tb_user_sys_policy",
            "Tb_user_temp_policy",
            "Tb_user_policy_mark",
            "Tb_world_mark",
        )
        return root
    }

    /** 组装 ["表名", [ 行, 行, ... ]]。 */
    private fun table(name: String, vararg rows: ArrayNode): ArrayNode {
        val entry = nf.arrayNode()
        entry.add(name)
        val rowsNode = nf.arrayNode()
        rows.forEach { rowsNode.add(it) }
        entry.add(rowsNode)
        return entry
    }

    private fun addEmptyTables(root: ArrayNode, vararg names: String) {
        names.forEach { root.add(table(it)) }
    }

    // -- 行构造器 --------------------------------------------------------------
    // 每张表按 SetValueAt(index) 顺序填值, 用 Row 累加器保证 index 连续,
    // 只填到 "最后一个我们关心的字段" 为止, 其余字段客户端用默认值补齐。
    // 中间跳过 (padding) 的字段按 fieldTypes 里的真实类型填默认值。

    /** 按 index 顺序追加值的累加器, 未显式设置的空洞按真实字段类型填默认值。 */
    private fun row(tableName: String): Row =
        Row(nf, fieldTypes[tableName] ?: error("未知表字段类型: $tableName"))

    /** Tb_user: 0=userid,1=passport,2=aid,3=login_server_userid,4=help_id,5=role_id,6=name,
     *  7=role_name,8=state,17=city_wid,38=power,39=newbie_guide,55=force,60=country,61=time_zone。 */
    private fun tbUser(state: PlayerState): ArrayNode =
        row("Tb_user")
            .i(0, state.userId)
            .s(1, "passport_${state.userId}")
            .i(2, state.userId)                 // aid
            .i(3, state.userId)                 // login_server_userid
            .s(4, "help_${state.userId}")       // help_id
            .s(5, "role_${state.userId}")       // role_id
            .s(6, state.roleName)               // name
            .s(7, state.roleName)               // role_name
            .i(8, 1)                      // state = 正常
            .i(17, state.cityWid)         // city_wid (== MainPos)
            .i(19, state.resources.yuanBao) // yuan_bao_cur
            .i(20, state.resources.hufu)  // hufu_cur
            .i(22, state.resources.freeYuanBao) // free_yuan_bao_cur
            .i(38, 1000)                  // power
            .i(39, 1)                     // newbie_guide (已过新手引导)
            .i(55, 0)                     // force = UserForceType.NORMAL(0) 正式军 (1=BANDIT 流浪军)
            .arr

    /** Tb_user_res: 0=userid, 2=money_cur, 3~6=木石铁粮 cur, 9~12=木石铁粮 max。 */
    private fun tbUserRes(state: PlayerState): ArrayNode =
        row("Tb_user_res")
            .i(0, state.userId)
            .i(2, state.resources.money)  // money_cur
            .i(3, state.resources.wood).i(4, state.resources.stone).i(5, state.resources.iron).i(6, state.resources.food)
            .i(9, PlayerResources.UNLIMITED_AMOUNT)
            .i(10, PlayerResources.UNLIMITED_AMOUNT)
            .i(11, PlayerResources.UNLIMITED_AMOUNT)
            .i(12, PlayerResources.UNLIMITED_AMOUNT) // *_max
            .arr

    /** Tb_user_inner_city: 初始内城资源和新手状态。 */
    private fun tbUserInnerCity(userId: Int): ArrayNode =
        row("Tb_user_inner_city")
            .i(0, userId)
            .i(1, 1)                      // inner_city_map_version
            .s(2, "1")                    // area 1 默认解锁
            .i(3, 100000).i(4, 100000).i(5, 100000).i(6, 100000)
            .i(7, 1000000).i(8, 1000000).i(9, 1000000).i(10, 1000000)
            .i(15, 0)                     // repair_degree
            .s(26, "").s(27, "")          // waiting/accepted ids
            .i(28, 0).i(29, 100000)       // soldiers
            .s(30, "").s(31, "")          // own/submitted items
            .i(32, 0)                     // first_battle
            .s(33, "")                    // guide_logged_ids
            .arr

    /** Tb_user_card_extract: 激活客户端所有赛季配置中的卡包。 */
    private fun tbUserCardExtract(
        userId: Int,
        extractId: Int,
        refreshWayId: Int,
        serverOpenTime: Long,
        isNew: Boolean,
    ): ArrayNode {
        val nowSec = serverOpenTime.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        return row("Tb_user_card_extract")
            .i(0, extractId)
            .i(1, userId)
            .i(2, refreshWayId)
            .i(3, nowSec)                  // got_time
            .i(4, 0)                       // end_time
            .i(5, nowSec)                  // free_time
            .i(6, 0)                       // used_time
            .i(7, if (isNew) 1 else 0)    // is_new
            .i(8, 0).i(9, 0).i(10, 0)
            .i(23, 0)                     // total_count
            .i(31, 10)                    // free_count
            .i(32, 0).i(33, 0)
            .arr
    }

    /** Tb_activity 875: 抽卡聚合页无条件读取四组任务完成量。 */
    private fun tbActivity(userId: Int): ArrayNode =
        row("Tb_activity")
            .i(0, 875)                    // activity_id_u
            .i(1, 875)                    // activity_id
            .i(2, userId)
            .s(3, "362,0;363,0;364,0;365,0;")
            .s(4, "0,0,0,0")              // finish_info
            .s(5, "0,0,0,0")              // award_info
            .i(6, 0)
            .s(7, "")
            .s(8, "")
            .s(9, "")
            .s(10, "")
            .i(11, 0)
            .i(12, 0)
            .i(13, 0)
            .i(14, 0)
            .s(15, "")
            .i(16, 0)
            .i(17, 0)
            .i(18, 0)
            .i(19, 0)
            .arr

    /** 参数 12=4 将客户端当前赛季声明为征服（XP）赛季。 */
    private fun tbSysParam(paramId: Int, value: String): ArrayNode =
        row("Tb_sys_param")
            .i(0, paramId)
            .s(1, value)
            .arr

    /** Tb_user_city: 0=city_wid,1=userid,2=garrison(string),3=forces_cur,4=forces_max,
     *  9=state,10=end_time。end_time 设 0 表示无建造中状态。 */
    private fun tbUserCity(userId: Int, cityWid: Int, serverOpenTime: Long): ArrayNode =
        row("Tb_user_city")
            .i(0, cityWid)
            .i(1, userId)
            .s(2, "")                     // garrison
            .i(3, 0)                      // forces_cur
            .i(4, 100000)                 // forces_max
            .i(9, 0)                      // state
            .i(10, 0)                     // end_time (0 => 无建造)
            .arr

    /**
     * 主城中心加八个 PLAYER_SUBURB 副格必须同时存在于登录快照中。世界地图
     * 还会在 5026 中重发同一份归属信息，客户端才能正确渲染完整的自有九格。
     */
    private fun tbWorldCities(world: WorldProjection): List<ArrayNode> =
        LinkedHashMap<Int, ArrayNode>().apply {
            world.cities.forEach { city ->
                put(
                    city.cityWid,
                    tbWorldCity(
                        userId = city.userId,
                        wid = city.cityWid,
                        cityType = 1,
                        roleName = city.roleName,
                        belongCity = 0,
                    ),
                )
                HomeCity.suburbWids(city.cityWid).forEach { suburbWid ->
                    put(
                        suburbWid,
                        tbWorldCity(
                            userId = city.userId,
                            wid = suburbWid,
                            cityType = 5,
                            roleName = "",
                            belongCity = city.cityWid,
                        ),
                    )
                }
            }
            world.lands.forEach { claim ->
                putIfAbsent(
                    claim.wid,
                    tbWorldCity(
                        userId = claim.userId,
                        wid = claim.wid,
                        cityType = 2,
                        roleName = "",
                        belongCity = claim.belongCity,
                    ),
                )
            }
        }.values.toList()

    /** Tb_world_city: 0=wid,1=city_type,5=name(string),6=userid,11=force_type,
     *  12=durability_cur,13=durability_max,19=end_time,21=belong_city,22=state。 */
    private fun tbWorldCity(
        userId: Int,
        wid: Int,
        cityType: Int,
        roleName: String,
        belongCity: Int,
    ): ArrayNode =
        row("Tb_world_city")
            .i(0, wid)
            .i(1, cityType)
            .s(5, roleName)               // name
            .i(6, userId)                 // userid
            .i(11, 0)                     // force_type = UserForceType.NORMAL
            .i(12, 10000)                 // durability_cur
            .i(13, 10000)                 // durability_max
            .i(19, 0)                     // end_time = 0
            .i(21, belongCity)
            .i(22, 0)                     // state
            .arr

    /** Tb_user_build: 主城建筑。build_id=10 是城主府，升级 UI 通过 Tb_user_build 读取等级。 */
    private fun tbUserBuild(userId: Int, cityWid: Int, buildId: Int, level: Int): ArrayNode =
        row("Tb_user_build")
            .i(0, HomeCity.userBuildId(cityWid, buildId))
            .i(1, cityWid)
            .i(2, buildId)
            .i(3, userId)
            .i(4, level.coerceAtLeast(1))
            .i(5, 0)                      // state
            .i(6, 0)                      // queue
            .i(7, 0)                      // effect_state
            .i(8, 0)                      // end_time
            .i(9, userId)                 // build_userid
            .i(10, 1)                     // build_completed
            .i(11, 0)                     // build_count
            .s(12, "")                    // upgrade_help_level
            .arr

    /** Tb_build_effect_city: 城市建筑效果汇总。队伍 UI 直接读取 army_cost_max 且无 null 保护。 */
    private fun tbBuildEffectCity(userId: Int, cityWid: Int): ArrayNode =
        row("Tb_build_effect_city")
            .i(0, cityWid)
            .i(1, userId)
            .i(2, 10_000)                 // durability_max
            .i(3, 5)                      // reside_max
            .i(4, PlayerHero.MAX_TROOPS - PlayerHero.DEFAULT_LEVEL * 100) // barracks adds 5,000
            .i(5, 0)                      // recruit_time
            .s(6, "")
            .i(7, 0)
            .s(8, "")
            .i(9, 0)
            .s(10, "")
            .i(11, 0)
            .s(12, "")
            .i(13, 0)
            .s(14, "")
            .i(15, 0)
            .s(16, "")
            .i(17, 0)
            .i(18, 10)
            .i(19, 0)
            .i(20, 0)
            .i(21, 0)
            .i(22, 0)
            .i(23, 5)                     // army_max
            .i(24, 0)                     // army_pos_counsellor
            .i(25, 1)                     // army_pos_front
            .i(26, 100)                   // army_cost_max => 10.0 cost
            .i(27, 0).i(28, 0).i(29, 0).i(30, 0)
            .i(31, 1_000_000)             // res_max
            .arr

    /** Tb_army: 主城空队伍容器，队伍/武将相关 UI 会枚举本表。 */
    private fun tbArmy(state: PlayerState): ArrayNode {
        val team = state.teamHeroes()
        val march = state.activeMarch()
        return row("Tb_army")
            .i(0, state.primaryArmyId())
            .i(1, state.userId)
            .i(2, march?.fromWid ?: state.cityWid) // reside_wid
            .i(3, 0)
            .i(4, march?.fromWid ?: state.cityWid) // last_reside_wid
            .i(5, team.getOrElse(2) { 0 })      // front_heroid_u
            .i(6, team.getOrElse(1) { 0 })      // middle_heroid_u
            .i(7, team.getOrElse(0) { 0 })      // base_heroid_u
            .i(8, 0)                            // counsellor_heroid_u
            .i(9, 0)                            // army_formation_id
            .s(10, "")                         // army_formation_effect
            .i(11, if (march == null) 0 else 1) // state: IN_EXPEDITION
            .i(12, 0)                           // wait_count
            .i(13, march?.targetWid ?: 0)       // target_wid
            .i(14, if (march == null) state.cityWid else 0) // stay_wid
            .i(15, march?.beginSec ?: 0)        // reside_time
            .i(16, march?.beginSec ?: 0)        // begin_time
            .i(17, march?.endSec ?: 0)          // end_time
            .i(18, 100)
            .arr
    }

    private fun tbHero(hero: PlayerHero, userId: Int, primaryArmyId: Int): ArrayNode =
        row("Tb_hero")
            .i(0, hero.heroUid)
            .i(1, hero.heroId)
            .i(2, userId)
            .i(3, hero.armyId.takeIf { it == primaryArmyId } ?: 0) // legacy login exposes one army
            .i(4, 0)                      // hurt_end_time
            .i(5, 0)                      // state
            .i(6, hero.level)             // level
            .i(7, PlayerHero.MAX_STAMINA) // infinite stamina
            .i(8, 0)
            .i(9, hero.createdAtSec)
            .i(10, 0)
            .i(11, hero.troops)           // troops
            .i(12, 0)
            .i(13, 0)
            .i(14, 0)
            .i(15, 0)
            .i(16, 0)
            .i(17, 0)
            .i(18, 0)
            .i(19, 0)
            .i(20, 0)
            .i(21, 0)
            .s(22, hero.skillString())       // skill: persistent three-slot state
            .i(24, 1)                        // awake_state: default awakened
            .i(29, hero.advanceNum)           // advance_num: 卡面进阶星数
            .i(32, hero.heroType)          // hero_type
            .s(33, "")
            .s(34, "")
            .s(35, "")
            .i(36, 0)
            .s(37, "")
            .i(43, hero.dynamicIcon)        // dynamic_icon
            .arr

    /**
     * Real 99991 packet uses:
     * [skill_id_u, skill_id, userid, learned_hero, learned_num,
     *  researched_num, research_progress, skill_type, skill_state,
     *  awake_state, season_skill, season_researched, researched_num_from_type].
     */
    private fun tbUserSkill(userId: Int, index: Int, skillId: Int): ArrayNode =
        row("Tb_user_skill")
            .i(0, userId * 10_000 + index + 1)
            .i(1, skillId)
            .i(2, userId)
            .s(3, "")
            .i(4, 0)
            .i(5, UNLIMITED_SKILL_COPIES)
            .i(6, 100)
            .i(7, 1)
            .i(8, 0)
            .i(9, 0)
            .i(10, 0)
            .i(11, 0)
            .i(12, 0)
            .arr

    private fun tbUserFacadeCard(
        userId: Int,
        index: Int,
        facade: HeroFacadeDefinition,
    ): ArrayNode =
        row("Tb_user_facade_card")
            .i(0, userId * 10_000 + index + 1)
            .i(1, userId)
            .i(2, facade.baseHeroIds.first())
            .i(3, facade.facadeHeroId)
            .i(4, 0) // surface_tips
            .i(5, 0) // first_use_time
            .i(6, 0) // permanent
            .i(7, 0) // not a gifted facade
            .s(8, "").s(9, "")
            .i(10, 0)
            .s(11, "").s(12, "")
            .i(13, 1) // already read
            .arr

    /** 行军外观: facade_heroid>0 且 cfg_hero_id=0 表示永久通用持有。 */
    private fun tbUserArmyFacadeCard(userId: Int, facadeId: Int): ArrayNode =
        row("Tb_user_army_facade_card")
            .i(0, facadeId)
            .i(1, userId)
            .i(2, facadeId)
            .i(3, 0)
            .i(4, 0)
            .i(5, 0)
            .i(6, 0)
            .i(7, 0)
            .arr

    /** 主城外观: end_time=0 为永久，active_wid=0 表示已拥有但尚未装备。 */
    private fun tbUserBuildFacade(userId: Int, facadeId: Int): ArrayNode =
        row("Tb_user_build_facade")
            .i(0, facadeId)
            .i(1, facadeId)
            .i(2, userId)
            .i(3, 0)
            .i(4, 0)
            .i(5, 0)
            .i(6, 0)
            .i(7, 0)
            .i(8, 0)
            .i(9, 0)
            .i(10, 0)
            .arr

    /** Tb_user_stuff: 0=userid,3=protected_popup,62=occupy_land_level(string)。
     *  occupy_land_level 无 ?. 保护 (JustEnterFlowManager:1063), 必须为非 null 字符串。 */
    private fun tbUserStuff(userId: Int): ArrayNode =
        row("Tb_user_stuff")
            .i(0, userId)
            .i(3, 0)                      // protected_popup
            .s(62, "1,1,1,1,1,1,1,1,1,") // occupy_land_level: levels 1-9 already occupied
            .i(63, PlayerResources.UNLIMITED_AMOUNT) // hero_card_max
            .arr

    /** Tb_user_stuff_ex: 0=userid,175=tr_back_2_wait_chance_end_time。
     *  CbgReturnTransferOptionItem 直接读取本表行; 0 表示入口隐藏。 */
    private fun tbUserStuffEx(userId: Int): ArrayNode =
        row("Tb_user_stuff_ex")
            .i(0, userId)
            .i(175, 0)                    // tr_back_2_wait_chance_end_time
            .arr

    /** Tb_user_stuff_one: 0=userid,48=help_guide_record。
     *  HelpGuideData.InitActionRecord 无 ?. 保护, 必须提供本用户行。 */
    private fun tbUserStuffOne(userId: Int): ArrayNode =
        row("Tb_user_stuff_one")
            .i(0, userId)
            .s(48, "")                    // help_guide_record
            .arr

    /** Tb_user_stuff_temp: 回流活动状态字段被多个主界面入口直接读取。
     *  全部置 0 表示活动关闭, 避免初始化回流 UI 时空表 NRE。 */
    private fun tbUserStuffTemp(userId: Int): ArrayNode =
        row("Tb_user_stuff_temp")
            .i(0, userId)
            .i(111, 0)                    // callback_ceremony_state
            .i(112, 0)                    // callback_ceremony_begin_time
            .i(113, 0)                    // task_state
            .i(114, 0)                    // season_hero_state
            .i(115, 0)                    // callback_tribute_state
            .arr

    /** Tb_user_stuff_temp_ex: 0=userid,82~84/117~119=社区红点相关。
     *  CommunityData.NeedRedTips 直接读取本表行; 全部置 0 表示无红点。 */
    private fun tbUserStuffTempEx(userId: Int): ArrayNode =
        row("Tb_user_stuff_temp_ex")
            .i(0, userId)
            .i(82, 0)                     // community_blink_begin_time
            .i(83, 0)                     // community_blink_time_out
            .i(84, 0)                     // community_blink_type
            .s(116, "1,1,1,1,1,1,1,1,1,") // occupy_land_level_season
            .s(117, "")                   // community_tips
            .i(118, 0)                    // community_red_dot
            .i(119, 0)                    // community_red_dot_time
            .arr

    /** Tb_user_stuff_temp_one: 0=userid,78=callback_first_login_role,196=conquered_level。 */
    private fun tbUserStuffTempOne(userId: Int): ArrayNode =
        row("Tb_user_stuff_temp_one")
            .i(0, userId)
            .i(78, 0)                     // callback_first_login_role
            .i(196, 9)                    // conquered_level: unlock S1 land levels 1-9
            .arr

    private const val UNLIMITED_SKILL_COPIES = 99
}

/**
 * 按 index 顺序追加值的行累加器, 未显式设置的空洞按真实字段类型填默认值。
 * types = 该表每个字段的类型 (int/long/double/string/bool), 顺序与字段 index 对应。
 */
private class Row(private val nf: JsonNodeFactory, private val types: List<String>) {
    val arr: ArrayNode = nf.arrayNode()
    private var idx = 0

    fun i(index: Int, v: Int): Row { pad(index); arr.add(v); idx = index + 1; return this }
    fun l(index: Int, v: Long): Row { pad(index); arr.add(v); idx = index + 1; return this }
    fun s(index: Int, v: String): Row { pad(index); arr.add(v); idx = index + 1; return this }
    fun f(index: Int, v: Double): Row { pad(index); arr.add(v); idx = index + 1; return this }
    fun b(index: Int, v: Boolean): Row { pad(index); arr.add(v); idx = index + 1; return this }

    /** 填补 idx..index 之间未指定的字段, 每个按其真实类型填默认值。 */
    private fun pad(index: Int) {
        require(index >= idx) { "index 必须递增: $index < $idx" }
        while (idx < index) {
            when (types.getOrElse(idx) { "int" }) {
                "string" -> arr.add("")
                "double" -> arr.add(0.0)
                "bool" -> arr.add(false)
                else -> arr.add(0)          // int / long
            }
            idx++
        }
    }
}
