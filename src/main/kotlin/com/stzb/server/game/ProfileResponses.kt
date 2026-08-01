package com.stzb.server.game

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.protocol.GameServerConfig

/**
 * cmd 3686 GET_HOMEPAGE_INFO（点开自己"个人资料"主页）的兜底响应。
 *
 * 客户端 UserMainView.ResponseData 只有 `val[0] != 0` 时才调 UpdateData(val[1])，
 * 而 UpdateData 对 val[1] 字典的多数键是 **无守卫强转**（缺键 KeyNotFound、
 * 定长子列表越界都会崩），所以空 `{}` 或 `[200,{}]` 会让客户端直接抛异常。
 *
 * 这里按 UserMainView.UpdateData（decompiled UserMainView.cs:4264-4447）逐字段
 * 精确构造一份"自己主页"的最小完整快照。返回顶层 `[200, dict]`：
 *   - 200 = 非 0 状态码，触发 UpdateData
 *   - dict = 下面 homepageDict() 构造的完整字典
 *
 * personal 子列表下标 0..21 全部无守卫强转，必须齐备；union(14)/server(4)/
 * zanAndvistor(3) 同为定长强转；history_choice/fashion/populartiy 可空串；
 * area_rank_title 走 `.ToString()`，键值不能是 JSON null（给数字 0）。
 */
object ProfileResponses {
    private val mapper = jacksonObjectMapper()
    private val nf: JsonNodeFactory = mapper.nodeFactory

    /** cmd 3686：自己主页信息。roleName/userId 用会话默认值（私服单人）。 */
    fun homepageInfo(
        userId: Int = 10001,
        roleName: String = GameServerConfig.ROLE_NAME,
        playerUnion: PlayerUnion? = null,
    ): String =
        mapper.writeValueAsString(
            nf.arrayNode()
                .add(200)                          // [0] 状态码，必须 != 0
                .add(homepageDict(userId, roleName, playerUnion)), // [1] 主页字典
        )

    private fun homepageDict(userId: Int, roleName: String, playerUnion: PlayerUnion?): ObjectNode =
        nf.objectNode().apply {
            set<ArrayNode>("personal", personal(userId, roleName))
            set<ArrayNode>("union", union(playerUnion))
            set<ArrayNode>("server", server())
            set<ArrayNode>("history", nf.arrayNode())        // 战报/历史列表（可空）
            set<ArrayNode>("zanAndvistor", zanAndVisitor())
            put("show_type", 0)
            put("history_choice", "")
            put("fashion", "")
            put("populartiy", "")
            set<ArrayNode>("city_card", nf.arrayNode())       // 城池名片列表（可空）
            put("area_rank_title", 0)                         // .ToString() 读取，不可为 null
        }

    /**
     * personal 子列表：UpdateData 无守卫段是 val[0]..val[21]（共 22 元，必须齐备），
     * val[22] 起才有 `val.Count > N` 守卫。类型严格对齐源码强转。
     */
    private fun personal(userId: Int, roleName: String): ArrayNode =
        nf.arrayNode().apply {
            add(roleName)   // [0]  role_name          string
            add("")         // [1]  introduction       string
            add(0)          // [2]  zan_count          int
            add(100)        // [3]  player_bg_id       int（/100 用作城池皮肤，给合法值）
            add("")         // [4]  help_id            string
            add(0)          // [5]  head_id            int
            add("")         // [6]  frame 串           string（配合 head 算 frame_id）
            add(0)          // [7]  force_type         int（0 = 正式军）
            add(0)          // [8]  show_title_id      int
            add(nf.arrayNode()) // [9] facade 装扮列表  List（空 => facadeInfo=null）
            add(0)          // [10] label_id           int
            add("")         // [11] labels             string
            add(false)      // [12] is_friend          bool
            add(0)          // [13] （占位，源码跳过）
            add(0)          // [14] power_value        int（战力）
            add(0)          // [15] wuxun_value        int
            add(-1)         // [16] wuxun_rank_value   int（-1 = 未上榜）
            add(0)          // [17] nobility           int
            add(0)          // [18] （占位，源码跳过）
            add("role_$userId") // [19] RoleID          string（community_roleid 默认取它）
            add(nf.arrayNode()) // [20] mLanternInfoStr List（强转，必需非 null）
            add(nf.objectNode()) // [21] mFuData        Dictionary（强转，必需非 null）
        }

    /** union 子列表：val2[0..13]，全部定长强转。无同盟时给零值/空串。 */
    private fun union(union: PlayerUnion?): ArrayNode =
        nf.arrayNode().apply {
            add(0)   // [0]  clan_id             int
            add("")  // [1]  clan_name           string
            add(union?.unionId ?: 0)   // [2]  union_id            int
            add(union?.name ?: "")     // [3]  union_name          string
            add("")  // [4]  group_name          string
            add("")  // [5]  npc_city_name       string
            add(0)   // [6]  official_id         int
            add(0)   // [7]  clan_official_id    int
            add(0)   // [8]  clan_feature_id     int
            add(0)   // [9]  superior_union_id   int
            add("")  // [10] superior_union_name string
            add(0)   // [11] superior_union_force int
            add(0)   // [12] npc_city_wid        int
            add(0)   // [13] is_xianling(==1?)   int
        }

    /** server 子列表：val3[0..3]。season_name/area/server_id/season。 */
    private fun server(): ArrayNode =
        nf.arrayNode().apply {
            add(GameServerConfig.SERVER_NAME)   // [0] season_name  string
            add(0)                              // [1] area         int
            add(GameServerConfig.RUN_SERVER_ID) // [2] server_id    int（跨服判定用）
            add(0)                              // [3] season       int
        }

    /** zanAndvistor 子列表：val4[0..2]。访客数/是否已赞/访客列表。 */
    private fun zanAndVisitor(): ArrayNode =
        nf.arrayNode().apply {
            add(0)               // [0] visitor_count int
            add(false)           // [1] has_zan       bool
            add(nf.arrayNode())  // [2] visit_list    List
        }
}
