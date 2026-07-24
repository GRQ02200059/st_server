# 客户端协议契约审计（第一阶段）

## 范围与方法

本审计以 `stzb 9.2.2` 反编译 C# 客户端为主证据，覆盖当前服务端已实现且直接影响
玩家持久状态的城建、卡牌、部队和征兵命令。

每个命令从以下五个位置反推服务端契约：

1. 客户端命令常量。
2. 发包参数构造。
3. 响应 observer 或一次性 callback。
4. 响应中的强制类型转换。
5. `90005` 数据表变化及 UI 监听。

仅在源码证据完整时标记为“已证实”。未找到完整消费链的行为必须保留为待实机日志验证，
不能因为现有服务端实现可以运行就视为客户端契约。

客户端源码根目录：

```text
stzb_9.2.2_out_branch_9.1.1776213/assets/decompiled
```

## 已证实契约

### cmd=30：武将上阵

请求固定为：

```text
[cityWid, heroUid, armyId, pos]
```

证据：

- `Game.Data.GamePlay/Tenth.Data/ArmyOpRequest.cs:1691-1699`

直接响应必须是可枚举的 `List<object>`，且每一项必须能直接强制转换为 `int`。客户端把
结果解释为受影响的 army id 列表：

- `Game.Data.GamePlay/Tenth.Data/ArmyOpRequest.cs:1741-1776`

因此服务端返回 `[armyId]` 是兼容形状；返回标量、对象或包含浮点数的数组都会破坏客户端
强制转换。

直接响应本身不修改客户端 `Tb_army` 或 `Tb_hero`。实际状态必须通过 `90005` 同步：

- `Tb_army` 保存部队三个槽位。
- `Tb_hero.armyid` 保存武将所属部队。

两个表必须来自同一次服务端状态提交，否则客户端可能短暂或永久看到互相矛盾的状态。

### cmd=32：武将换位

请求固定为：

```text
[cityWid, armyId1, pos1, armyId2, pos2]
```

证据：

- `Game.Data.GamePlay/Tenth.Data/ArmyOpRequest.cs:1626-1635`

客户端允许两个不同 `armyId`，所以协议本身支持跨队交换，服务端不能只实现单队内部换位。

直接响应与 cmd=30 相同：必须是所有元素均为 `int` 的列表。客户端逐项执行 `(int)current`：

- `Game.Data.GamePlay/Tenth.Data/ArmyOpRequest.cs:1650-1673`

响应应列出受影响的 army id，去重是安全的。状态闭环仍需 `90005 Tb_army + Tb_hero`。

### 部队位置语义

请求中的客户端位置是：

- `1 = base_heroid_u`（大营）
- `2 = middle_heroid_u`（中军）
- `3 = front_heroid_u`（前锋）

`Tb_army` 字段顺序本身是 `front`、`middle`、`base`，不能把字段顺序误当客户端位置：

- `Game.Data.Tb/Tenth.Data/Tb_army.cs:292-306`

服务端领域模型应使用显式枚举，不应继续用裸数组下标表达位置。

### cmd=37：普通批量征兵

请求固定为：

```text
[conscriptType, [[heroUid, count], ...]]
```

证据：

- `Game.Data.GamePlay/Tenth.Data/ConscriptOpRequest.cs:77-87`

直接响应必须是一个可被 `Convert.ToInt32(packet)` 转换的标量 army id：

- `Game.Data.GamePlay/Tenth.Data/ConscriptOpRequest.cs:108-115`

返回 `[armyId]` 不符合客户端契约。

请求没有直接携带 army id。服务端必须通过所有 `heroUid` 的当前 `Tb_hero.armyid` 反查。
如果武将不属于同一部队，不能猜默认队伍。

### cmd=38：立即征兵

请求固定为：

```text
[[[heroUid, count], ...], conscriptType]
```

证据：

- `Game.Data.GamePlay/Tenth.Data/ConscriptOpRequest.cs:118-129`

响应同样必须是标量 army id，并且这里没有 null 保护：

- `Game.Data.GamePlay/Tenth.Data/ConscriptOpRequest.cs:131-136`

错误形状会直接导致转换异常。成功后需要同步 `Tb_hero` 兵力相关字段和资源表。

### cmd=13 / cmd=14：建造与升级

两者请求均为六个整数：

```text
[cityWid, buildId, isFree, targetLevel, 0, needQuickBuild]
```

证据：

- `Game.Data.GamePlay/Tenth.Data/PoliticsData.cs:1465-1497`
- `Game.Data.GamePlay/Tenth.Data/PoliticsData.cs:1509-1519`

客户端发包时没有注册一次性响应 callback。可见状态依赖数据库表事件：

- `Tb_user_build.EventUpdate`
- `Tb_user_res.EventUpdate`
- `Tb_build_effect_city` 的派生属性

UI 消费证据包括：

- `Game.UI.GamePlay.Systems/Tenth.UI/BuildTreeUI.cs:1226-1228`
- `Game.Map/Tenth.Map.Map3d/CityMsgUI.cs:65-77`
- `Game.Map/Tenth.Map.Map2d/MapCountDownMgr.cs:111-146`

因此精确实现必须同时更新建筑、建筑效果和资源；仅返回 `[]` 只能结束网络请求，不能形成
客户端状态闭环。

### cmd=301：普通招募

请求固定为九个位置：

```text
[
  summonCfgId,
  summonUid,
  summonOpType,
  transferParam,
  resourceType,
  childCfgId,
  summonState,
  autoAdvance,
  excludeFourStarTransfer
]
```

证据：

- `Game.UI.GamePlay.Heros/Tenth.Data/CardOpRequest.cs:830-841`

客户端常驻 observer：

- `Game.UI.GamePlay.Heros/Tenth.Data/CardOpRequest.cs:80-93`

响应至少需要五个槽位：

- `[0]`：整数结果/招募标识。
- `[1]`：可转换为 `int[][]` 的卡牌结果。
- `[2]`：可转 `UInt16`。
- `[3]`：卡池配置 id，直接强制转换为 `int`。
- `[4]`：可转 `UInt16`。

证据：

- `Game.UI.GamePlay.Heros/Tenth.Data/CardOpRequest.cs:1024-1084`

每张卡至少提供 `[heroUid, heroId]`；客户端会读取 `[0]` 和 `[1]`。双祈愿路径还可能读取
索引 `[5]`，因此卡牌行长度和语义必须由现有已验证响应继续保持。

招募结果动画与真实卡牌状态是两条链：

- 直接响应驱动招募结果 UI。
- `90005 Tb_hero` 插入驱动卡库和其他 UI。

`Tb_hero` 更新必须在客户端需要查询实例前到达；当前“结果中 heroUid 使用 0，随后插入真实
Tb_hero”的做法属于已联调策略，但仍应通过实机日志和动画链继续验证。

### cmd=304：快速招募

请求固定为七个整数：

```text
[summonUid, transferParam, resourceType, quickOpType, quickCount, autoAdvance, excludeFourStarTransfer]
```

证据：

- `Game.UI.GamePlay.Heros/Tenth.Data/CardOpRequest.cs:885-929`

客户端响应至少需要八个槽位，并具有嵌套强转：

- 整体必须为 `List<object>`。
- `[5]` 必须为整数列表，客户端会求和并访问 `[3]`、`[4]`。
- `[7]` 必须可转换为 `int[][]`。
- `[0]`、`[4]` 必须可转换为整数。

证据：

- `Game.UI.GamePlay.Heros/Tenth.Data/CardOpRequest.cs:1211-1246`

该命令绝不能由通用 `[]` 兜底。

## 客户端状态表是持久化设计依据

客户端数据访问不是只发生在命令回调中，而是广泛通过表事件驱动 UI：

- `Tb_army.EventInsert/EventUpdate/EventRemove`
  - `Game.Data.Tb/Tenth.Data/Tb_army.cs:76-145`
- `Tb_hero.EventInsert/EventUpdate/EventRemove`
  - `Game.Data.Tb/Tenth.Data/Tb_hero.cs:76-145`
- 卡牌、城建和资源界面均注册相应表事件。

`Tb_hero` 的关键状态字段包括：

- `heroid_u`
- `heroid`
- `userid`
- `armyid`
- `level`
- `energy`
- `hp`
- `hp_max`
- `recruit_time`

证据：

- `Game.Data.Tb/Tenth.Data/Tb_hero.cs:292-364`

`Tb_army` 不只是三个武将槽位，还包含驻扎位置、状态、行军和征兵等字段：

- `Game.Data.Tb/Tenth.Data/Tb_army.cs:292-482`

第一阶段 SQLite 不必保存所有当前未使用字段，但 schema 和领域模型必须允许按客户端表语义
扩展，不能把部队永久简化为一个三元素列表。

## 对服务端设计的约束

### 命令契约注册表

注册表中的 `EXACT` 不能只是“有一个 handler”。每项还应记录：

- 请求形状。
- 直接响应形状。
- 成功时必须更新的客户端表。
- 已知的响应与 `90005` 顺序。
- 客户端源码证据。
- 验证等级：`SOURCE_PROVEN` 或 `RUNTIME_CONFIRMED`。

源码未证实且未实机确认的命令不能标记为精确实现。

### 模块 seam

模块 seam 应是“完成一个客户端状态闭环”，不是单个响应函数。例如部队模块一次处理需要
产出：

```text
cmd=30/32 direct response
+ Tb_army 90005
+ Tb_hero 90005
```

这些输出必须来自同一次持久化事务提交后的状态。

### SQLite 模型

SQLite 是服务端事实来源，但应能无歧义投影为客户端表：

- `player_armies` 对应 `Tb_army` 的持久字段。
- `player_army_slots` 显式保存 `BASE/MIDDLE/FRONT`。
- `player_heroes.army_id` 与 slots 保持数据库级一致性。
- 建筑、资源和武将状态必须同时支持登录全量快照与在线增量 `90005`。

领域模型可以隐藏客户端数组槽位，但协议 adapter 必须集中维护槽位映射，禁止各模块手写
不同版本的数组。

## 尚待审计

- 登录 99991 全量快照所有表的加载顺序和最小字段集合。
- cmd=301 招募结果与 `90005 Tb_hero` 的严格先后时序。
- cmd=13/14 直接响应是否存在全局 observer。
- 卡牌属性命令 80/81/82/185/186/300/308 的精确 `Tb_hero` 或其他表变化。
- 土地 21、世界场景 5025/5026 的所有 UI 消费槽位。
- 当前 `NetworkResponsePolicy` 中每个兜底命令的安全响应形状。

这些项目完成前，不应把对应命令从“待验证”提升为“已精确实现”。
