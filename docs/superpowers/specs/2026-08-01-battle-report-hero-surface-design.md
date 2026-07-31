# 战报武将卡框与动态画像服务端设计

- 日期：2026-08-01
- 状态：已确认，待实施计划
- 范围：Kotlin 服务端

## 目标

让部署后的新 PVE 战报在客户端正常显示参战武将的卡框和动态画像：

- 默认装备成就御龙卡框 `101260`。
- 解锁普通蟠龙卡框与成就御龙卡框。
- 卡框和动态画像在出征时冻结，后续更换外观不影响已经生成的战报。
- 战报列表与战报详情都使用同一份冻结的外观数据。

## 约束

- 只修改 `server/` 内 Kotlin 服务端与其测试。
- 不修改客户端资源、客户端 DLL、热更新包或客户端逻辑。
- 不注入客户端，不通过 ADB 推送或部署客户端文件。
- 不回填已生成的历史战报。只有实施后新创建的战报有完整外观数据。
- NPC 守军不伪造玩家卡框或动态画像，保持外观字段为 `0`。

## 现状与根因

当前玩家外观状态只有 `PlayerHero.dynamicIcon`。登录快照也没有写入
`Tb_hero.card_border`（字段索引 `42`）。

`ClientBattleReportStore` 生成战报摘要时：

- `attack_all_surface` / `defend_all_surface` 固定把动态画像写为 `0`。
- `attacker_surface` / `defender_surface` 固定写四行 `0,0,0`。

客户端据此不会显示卡框与动态画像。真实战报使用的字段格式为：

```text
attacker_surface / defender_surface:
card_border,dynamic_icon,active_feature_id;
card_border,dynamic_icon,active_feature_id;
card_border,dynamic_icon,active_feature_id;
card_border,dynamic_icon,active_feature_id

attack_all_surface / defend_all_surface:
hero_id,dynamic_icon;
hero_id,dynamic_icon;
hero_id,dynamic_icon
```

其中 `attacker_surface` 的第一行是基础位，其余三行对应参战武将站位。

## 设计

### 账号武将状态

`PlayerHero` 新增 `cardBorder`：

- 新建武将和旧存档缺失字段默认使用 `101260`。
- `PlayerHeroSnapshot` 同步保存该字段，保证重启后不丢失。
- `PlayerState` 提供卡框选择操作，只允许 `0`、`101260`、`110997`、
  `110998`、`110999`。
- 默认卡框为 `101260`；显式选择 `0` 时允许取消装备。

卡框定义集中在一个小型服务端目录对象中，避免在 handler、快照与战报代码中散落
magic number。

### 登录快照与解锁

`UserInitTableBuilder` 登录快照：

- 对每个 `Tb_hero` 下发字段 `42 = cardBorder`。
- 保留字段 `43 = dynamicIcon`。
- 下发普通蟠龙框所需的服务端持有记录。
- 下发 `Tb_hero_achieve` 记录，将御龙框对应成就的 `finish_reward` 设为
  `2`，使客户端判定为可使用。

表行主键必须稳定，由 `userId` 和目录顺序导出；重连不会产生重复的客户端记录。

### 卡框命令

新增命令常量和 handler 分支：

- `673`：服务端确认卡框已解锁。
- `675`：按请求武将与卡框 ID 选择/取消当前卡框，成功后持久化。
- `1673`、`1674`：安全确认客户端轮换请求，不改变已解锁的永久卡框库存。

选择成功时：

1. 返回该业务命令的成功空响应。
2. 写入 `PlayerStateRepository`。
3. 发送 `90005`，以 `Tb_hero` 稀疏更新字段 `42`。

非法武将 UID 或不支持的卡框 ID 返回成功形状但不改状态，避免客户端界面卡死。

### 战报视觉快照

视觉状态不进入数值战斗模型。新增独立的不可变值对象：

```kotlin
data class BattleHeroSurface(
    val heroId: Int,
    val position: Int,
    val cardBorder: Int,
    val dynamicIcon: Int,
    val activeFeatureId: Int = 0,
)
```

数据流：

1. `PlayerBattleService.launchPveBattle` 从实际出征的 `PlayerHero` 复制
   `heroId`、站位、卡框、动态画像到 `PlayerMarchHero`。
2. `PlayerMarch` 已持久化，因而行军期间断线或重启不会丢失这份快照。
3. `settlePveBattle` 从 `PlayerMarchHero` 构建攻击方 `BattleHeroSurface` 列表，
   与数值结算的 `BattleHeroSpec` 分离。
4. `ClientBattleReportStore.record` 接受攻击方与防守方的视觉快照，写入
   `ClientBattleReport`。
5. `ClientBattleReport.toProfileNode` 只读取已记录的视觉快照，不再查询当前
   `PlayerState`。

PVE 守军的防守方快照为空，序列化结果为零值行。

### 战报字段序列化

`ClientBattleReportStore` 使用 `BattleHeroSurface` 输出：

- `attack_all_surface` / `defend_all_surface`：三个按位置排列的
  `heroId,dynamicIcon` 行。
- `attacker_surface` / `defender_surface`：一行基础位 `0,0,0` 加三行按
  位置排列的 `cardBorder,dynamicIcon,activeFeatureId`。

缺位或没有快照的武将输出零值，确保固定行数和字段数。历史战报不新增兼容回填路径，
继续保持原有零值数据。

## 错误处理与兼容

- 旧 `PlayerStateSnapshot`、`PlayerMarchSnapshot` 缺失新增字段时，由 Kotlin
  默认值兼容。
- 旧内存战报不存在 `BattleHeroSurface` 时输出零值，不查当前外观，也不改变历史。
- 卡框选择不会影响正在行军的部队外观；新选择仅作用于下一次出征。
- 战斗数值结算、兵力、技能和随机种子不依赖视觉快照，避免功能性回归。

## 测试策略

以测试优先实现，至少覆盖：

1. 新武将默认获得并持久化御龙卡框 `101260`。
2. 登录快照的 `Tb_hero[42]` 为当前卡框，且包含御龙成就可用记录与普通卡框持有记录。
3. `675` 成功后发送 `90005 Tb_hero[42]` 并跨存档恢复。
4. 不支持的卡框 ID 不修改状态。
5. 出征后修改玩家卡框或动态画像，已完成战报仍显示出征时的原始外观。
6. 新 PVE 战报的四行 `attacker_surface` 和三行 `attack_all_surface` 包含正确的
   御龙框与动态画像。
7. NPC 守军的防守方 surface 仍保持全零。
8. 相关协议、状态持久化、战报序列化和 handler 测试均通过。

## 非目标

- 不回填历史战报。
- 不添加 PvP 对手外观同步。
- 不实现卡框轮换活动的服务端库存或时间规则。
- 不改变动态画像原有解锁/切换逻辑。
- 不修改任何客户端文件或运行时行为。
