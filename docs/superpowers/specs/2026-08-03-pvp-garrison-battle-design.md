# PvP：玩家驻守 + 抵达战斗 + 归属转移 设计

- **日期**：2026-08-03
- **状态**：设计已确认，待写实现计划
- **范围**：Kotlin 私服（`server/`）支持两个玩家之间的战斗

## 1. 目标与决策

让玩家A 行军攻打玩家B 的主城/土地，当目标有B 手动驻守的部队时，抵达后触发战斗；A 胜则土地/城池归属转移给A。

已确认的关键决策：

| 决策项 | 选择 |
|---|---|
| PvP 触发方式 | 行军攻城/土地（复用现有 cmd 6 出征链路） |
| 防守方深度 | 需玩家B 先**手动驻守**才能被打 |
| 交付方式 | 驻守 + PvP 战斗**一次做完** |
| 归属转移 | A 胜则土地/城池转归 A |
| 驻军数据 | 存 `WorldState`（wid + 部队战斗快照 + ownerUserId），跨玩家可见，不依赖B 在线 |
| B 存档同步 | 战败/被占后**直接改B 的 `PlayerState`** 并落盘；B 在线则实时推送 |

核心原则：**最大化复用现有链路**（`BattleEngine`、`ClientBattleReportStore`、march 调度、`WorldStateRepository`、`onlineSessions` 广播）。PvP 只是把"防守方来源"从 NPC 工厂换成 `WorldState` 驻军快照，并新增驻守入口与归属转移。

## 2. 客户端协议依据（只读探查结论）

来自 `stzb_9.2.2_out_branch_9.1.1776213/assets/decompiled/`：

- **客户端不做战斗判定**：行军 `endTime` 只驱动到达动画，战斗结果完全由服务端 90005 增量下发。
- **驻守发起**：`cmd 60`（`ArmyOpRequest.RequestDefend`），请求体 `[wid(目标地), armyId, needPortWid, techID(=0), isJianJun(=0), useSpeedup(0/1)]`；对应 `ArmyOp.MOVE_DEFEND=2`，到达后 `ArmyState.RESIDE=5`。
- **驻军状态下发（两条通道）**：
  - 自己的部队：90005 更新 `Tb_army.state=5`、`reside_wid=目标地`（相关 `reside_time`、`last_reside_wid`）。
  - 他人地图可见：5026/5028 世界视野 army 段，`state=5`，`reside_wid` 在对应槽（现有 `worldMapArmies` 布局 index 10=reside_wid、index 0=state）。
- **战斗结果**：走 90005 DB 增量。攻方A 收 `Tb_battle_report_attack`（`userid==自己` 才播放地图战斗表现），防方B 收 `Tb_battle_report_defend`（字段对称）。`result` 语义：`1=胜 2=败 3=平`。
- **归属转移**：90005 更新 `Tb_world_city` 的 `userid`/`union_id`/`force_type`/`state`；客户端 `CityMsgUI.OnTbWorldCity` 刷新名牌与阵营着色。

## 3. 架构与数据流

```
[驻守阶段]  玩家B 客户端 --cmd 60 [wid,armyId,...]--> 服务端
   → 校验 B 拥有 armyId、wid 可驻守
   → B 部队 state=RESIDE_GOING(2)，抵达后 state=RESIDE(5), reside_wid=wid
   → WorldState 记录 GarrisonSnapshot(wid, ownerUserId=B, armyId, 部队战斗快照)
   → 90005 更新 B 的 Tb_army(state=5) + 5026/5028 让所有人地图上看到该驻军

[攻击阶段]  玩家A --cmd 6 [targetWid,armyId]--> 服务端
   → 行军 startMarch(targetWid)，endSec 到点调度结算
   → 结算时查 WorldState.garrisonAt(targetWid):
        · 有敌方驻军 → BattleEngine.resolve(A 队伍 vs 驻军快照)   ← PvP
        · 无驻军但有 NPC → 现有 LandDefenderFactory              ← PvE（不变）
   → 出战报：A 收 Tb_battle_report_attack；B 在线收 Tb_battle_report_defend
   → A 胜：清/减 B 驻军快照；转移 Tb_world_city 归属给 A；改 B 存档落盘；广播世界视野
```

## 4. 数据模型

### GarrisonSnapshot（驻军快照，存入 WorldState）

```kotlin
data class GarrisonSnapshot(
    val wid: Int,                       // 驻守地块
    val ownerUserId: Int,               // 驻守方玩家
    val armyId: Int,                    // 驻守部队 id
    val specs: List<BattleHeroSpec>,    // 战斗快照（含兵种/技能/装备，复用现有 spec）
    val residedAtSec: Int,
)
```

- 存 `BattleHeroSpec` 而非 heroUid 引用：结算时直接喂 `BattleEngine`，战力锁定驻守当时，与 B 后续改队伍解耦。
- `specs` 构造复用现有 `PlayerBattleService` 里"从 teamHeroes 生成 spec"的同一逻辑（含已修复的装备字段 `equipmentIds/equipmentFeatureSkillIds/...`），避免两套构建。

### WorldState 扩展

新增 `garrisonsByWid: Map<Int, GarrisonSnapshot>`，配套 `garrisonAt(wid)`、`putGarrison(snapshot)`、`removeGarrison(wid)`、`garrisonsForProjection()`。与现有 `landsByWid`/`citiesByUser` 同级，共用同一把 `ReentrantReadWriteLock`。

### PlayerMarch 扩展

新增 `targetType: Int`（`EXPEDITION=1` 攻击 / `RESIDE_GOING=2` 驻守），使同一 march 调度既能结算 PvP 也能"抵达变驻守"。

## 5. 新增/改动组件

| 组件 | 类型 | 职责 |
|---|---|---|
| `Cmd.RESIDE_FIELD = 60` | 改 | 新增驻守命令常量 |
| `ResideRequestParser` | 新 | 解析 cmd 60 body `[wid,armyId,needPortWid,tech,jianjun,speedup]`（仿 `ArmyBattleRequestParser`） |
| `sendReside()` in `GameServerHandler` | 新 | 处理 cmd 60：校验 → startMarch(RESIDE_GOING) → 调度抵达 |
| `GarrisonService`（或并入 `PlayerBattleService`） | 新 | 抵达驻守：`WorldState.putGarrison` + 更新 B 的 `Tb_army.state=5` |
| `PlayerBattleService.settle*()` | 改 | 结算时先查 `garrisonAt(targetWid)`：有敌方驻军走 PvP，否则走现有 PvE |
| `PvpBattleService`（独立薄封装） | 新 | A 队伍 vs 驻军快照结算；胜则 `removeGarrison` + 转移归属 + 改 B 存档 |
| `GameResponses.worldMapArmies` | 改 | 5026/5028 army 段加入 `WorldState` 里所有驻军（state=5, reside_wid），当前仅发自己的行军 |
| `defenderReportFor(B)` | 新 | 给 B 生成 `Tb_battle_report_defend`（复用 `ClientBattleReportStore`，对称字段 + result 取反视角） |

### 关键复用点（不新造）

- 战斗结算：`BattleEngine.resolve(attacker, defender)` 原样用，defender 从驻军快照 `builder.build(snapshot.specs)`。
- 战报：`ClientBattleReportStore.record()` 原样用；防守方战报换 `Tb_battle_report_defend` 表名 + result 视角。
- 归属转移：复用 `WorldStateRepository.claimLand` + `GameResponses.occupiedLandUpsertNotify`，PvP 胜利额外调用 `removeGarrison`。
- 多人通知：复用 `onlineSessions.allChannels()` + `broadcastWorldScene`。

## 6. 错误处理与边界

| 场景 | 处理 |
|---|---|
| cmd 60 body 缺字段 / armyId 不属于 B | 解析失败或校验不过 → 回 `null`（仿 cmd 6），不崩连接，记 warn |
| 驻守目标 wid 非法（自己主城/越界/不可驻守） | 拒绝，不 startMarch |
| A 抵达时驻军已被清（B 撤军/被别人先打） | `garrisonAt` 返回 null → 回退 PvE/空城占领，不报错 |
| A 队伍无可战斗武将 | 复用现有 `participants.isEmpty()` → 返回 null |
| 驻军快照英雄兵力为 0 | 视为空驻军，A 直接占领，不结算无意义战斗 |
| A 胜但归属已被第三方抢占 | 复用现有 `claimLand` 返回 false 分支，仅记日志 |

## 7. 时序与并发

- **驻守抵达 vs 攻击抵达**：都走现有 `ctx.channel().eventLoop().schedule(...)` 定时器，按 `endSec` 触发。驻守抵达 → 写 garrison；攻击抵达 → 读 garrison 结算。
- **同一 wid 竞争**：`WorldState` 用现有 `ReentrantReadWriteLock`。`putGarrison`/`removeGarrison`/`claimLand` 走写锁，`garrisonAt`/`projection` 走读锁。结算中"读驻军 → 判定 → 清驻军 → 转归属"作为**一个写临界区**，避免中途被插入。
- **B 存档更新**：结算线程改 B `PlayerState`（丢地、驻军减员/清空）后 `PlayerStateRepository.save`；B 在线则用 `onlineSessions` 找到 B 的 channel 推 90005（战报 + Tb_army/Tb_world_city 变更）+ 世界视野；离线仅落盘，B 下次登录 `loginSuccess` 自然读到新状态。
- **战报可复现**：复用现有 `stableBattleSeed`（march 派生种子），PvP 结算确定性可测。

## 8. 测试策略（TDD，先红后绿）

单元测试（不需真实客户端，基于服务端对象与 profile JSON 断言）：

1. `WorldStateTest`：putGarrison/garrisonAt/removeGarrison + 锁下并发读写。
2. `ResideRequestParserTest`：cmd 60 body 解析（正常/缺字段/非整数）。
3. `GarrisonServiceTest`：B 驻守抵达 → WorldState 有快照 + B `Tb_army` state=5/reside_wid。
4. `PvpBattleServiceTest`（核心）：
   - A 打有驻军的 wid → 出 `Tb_battle_report_attack`，结果确定；
   - A 胜 → garrison 被清 + `Tb_world_city` 归属转 A + B 存档丢地；
   - A 负 → 归属不变、A 队伍减员、B 驻军按结算减员；
   - 目标无驻军 → 回退 PvE 路径不变（回归保护）。
5. `GameResponsesTest`：5026 army 段包含 WorldState 驻军（state=5, reside_wid 在对应槽）。
6. 防守方战报：B 的 `Tb_battle_report_defend` 字段对称、result 视角正确。

回归保护：现有 `PlayerBattleServiceTest` 全绿（PvE 路径不被 PvP 改动破坏）。

运行方式：`--no-daemon -Dkotlin.compiler.execution.strategy=in-process`（本环境已验证稳定）。

## 9. 不在本次范围（YAGNI）

- 真实行军多段路径、港口寻路（`needPortWid` 收下但忽略）
- 加速道具、集结/军团驻守（cmd 61/911 等）
- 真机双开端到端联调（留到实现后单独做）
