# 纯服务端武器装配设计

## 目标

让客户端能够将登录快照赠送的武器装配到武将，并支持换装、把武器转给另一
武将、卸下与重登恢复。实现只改 Kotlin 服务端，不改客户端代码或运行时数据。

## 已确认的协议事实

- `1226 GEAR_EQUIP` 的请求体是 `[heroUid, gearUid]`。
- `1227 GEAR_FORGET` 的请求体也是 `[heroUid, gearUid]`。
- 客户端以 `Tb_hero[23] gearid_u` 和 `Tb_gear[9] heroid_u` 建立双向关系。
- `90005` 的更新操作使用 `NotifyType.Update=2` 和稀疏字段对。
- 当前服务端尚未分发 `1226`/`1227`，登录快照固定下发两端关联为 `0`，
  因此客户端无法完成装配。

## 范围

本次实现：

- `1226` 装配、换装及跨武将转移。
- `1227` 卸下。
- 玩家 JSON 存档保存装备关系。
- 99991 登录快照和 90005 增量同步装备关系。

本次不实现：

- 武器升级、进阶、洗练、拆解或词条变更命令。
- 装备冷却、体力消耗和战斗结算读取玩家装备。
- 客户端本地的装备条件判定。客户端已经在发送请求前进行该判定。

## 状态模型

`PlayerHero` 增加 `gearUid`，默认 `0`。`PlayerState` 以该字段作为装备关系
的唯一持久化来源，并提供：

- `equippedGearUid(heroUid): Int`
- `equipGrantedGear(heroUid, gearUid): GearEquipResult?`
- `forgetGrantedGear(heroUid, gearUid): GearEquipResult?`

武器的可用集合由 `InventoryCatalog.normalWeapons()` 和
`InventoryCatalog.hongjiCopies()` 的稳定 UID 共同决定；不接受客户端提交的任意
武器 UID。

`GearEquipResult` 包含所有受影响武将与武器的最终关联值，用于直接生成 90005
更新。关系始终满足：

- 一个武将至多一把武器。
- 一把武器至多归属一个武将。
- 武器 UID 仅在服务端赠送库藏中有效。

装配时先解除目标武将已有武器，再解除目标武器原属武将，最后建立新关联。
跨武将移动武器时，原武将自动卸下。对同一关联重复装配视为无变化。

卸下仅在请求中的 `heroUid` 和 `gearUid` 当前互相关联时成功；卸下后两端都置为
`0`。

## 持久化与登录

`PlayerStateSnapshot` 通过每个 `PlayerHeroSnapshot.gearUid` 保存关系。恢复旧
存档时缺失字段默认 `0`。

恢复时清理无效关系：

- 已不存在的武将自然没有可恢复目标。
- 不属于当前固定赠送库藏的武器 UID 清为 `0`。
- 多个武将引用同一把武器时，保留 `heroUid` 最小者，其余清为 `0`。

`UserInitTableBuilder` 从玩家状态生成：

- `Tb_hero[23] = hero.gearUid`。
- `Tb_gear[9] = equippedHeroUid`。

因此重登后的双向字段一致，并且任何调用 `GameResponses.heroUpsertNotify` 的后续
全量武将同步也保留 `gearUid`。

## 命令处理与同步

`Cmd` 增加 `GEAR_EQUIP=1226` 和 `GEAR_FORGET=1227`。`GameServerHandler`
解析两个正整数后调用 `PlayerState`。

当操作产生状态变化：

1. 先用原命令号返回 `[]`。
2. 保存 `PlayerState`。
3. 下发一条 `90005`，含全部受影响对象的稀疏更新：
   - `Tb_hero`: `[0, heroUid, 23, gearUid]`
   - `Tb_gear`: `[0, gearUid, 9, heroUid]`

换装中解除的旧武器、被转移武器的旧主人，以及目标武将都包含在同一通知中。

无效或无变化操作仍返回原命令的 `[]`，但不保存且不发送 `90005`。服务端记录
警告日志，避免恶意或过期客户端请求污染状态。

## 错误处理

以下请求不改变状态：

- JSON 不是至少含两个整数的数组。
- `heroUid` 或 `gearUid` 非正数。
- `heroUid` 不属于当前玩家。
- `gearUid` 不属于服务端固定赠送库藏。
- `1227` 请求的两端并非当前双向关联。
- 对已经正确关联的武器重复执行 `1226`。

不向客户端发送自定义错误码，因为当前服务端没有已验证的武器错误码协议；
保持同类命令的空成功回包约定，客户端状态以 90005 为准。

## 测试

测试先行，分四层覆盖：

1. `PlayerStatePersistenceTest`
   - 装配、换装、跨武将转移、卸下。
   - 不存在武将、未赠送武器和错误卸下不会改变状态。
   - 存档往返保留装备；旧存档和冲突存档按恢复规则归一化。
2. `UserInitTableBuilderTest`
   - 已装备时 `Tb_hero[23]` 和 `Tb_gear[9]` 互相一致。
3. `GameResponsesTest`
   - 装备变更的 90005 精确使用稀疏更新，覆盖新关联、旧关联清理和卸下。
   - `heroUpsertNotify` 的完整 `Tb_hero` 行保留 `gearid_u`。
4. `GameServerHandlerProtocolTest`
   - `1226` 和 `1227` 返回原命令 `[]` 后发送正确的 90005。
   - 换装和转移产生完整清理集合。
   - 非法请求无 90005，且重登快照仍为原状态。

最终验证运行相关单测、完整 Gradle 测试与 `installDist`。若存在既有无关基线失败，
单独报告失败名称与原因。发行包部署到 `59979` 后，使用真实客户端验证：
装配、换装、卸下、重登恢复。
