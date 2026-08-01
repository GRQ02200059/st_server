# 客户端与服务端地图对齐设计

## 目标

让客户端显示的土地等级、服务端守军等级及 `4329/4331` 回包使用同一张地图，消除守军错位。

## 已验证事实

- 当前登录 `cfgDataIndex` 和 `Tb_sys_param[26]` 均为 `5`。
- PipeBridge 调用客户端 `CfgLoader.ReadMapData` 导出的 `map 5/resources_in_map` 明文为 `3001 x 3001`。
- 客户端导出明文与服务端 `src/main/resources/map/5/resources_in_map.mbd` 解压内容逐字节一致，SHA-256 均为 `f2b7949f37c8aa8ad147a3eebac66ee9b8b94d3edd3267a5f3adeaffc29e5e74`。
- 服务端守军当前通过独立的 `RESOURCE_MAP_CFG_ID=984` 读取另一张地图，这是静态地图错位的直接原因。
- 客户端 `MapResCommon.GetRealResLevel()` 会优先读取 `Tb_developed_land`、`Tb_land_reclamation` 和 `Tb_store_house` 等动态覆盖。
- 当前登录快照没有声明上述三个表，旧客户端数据可能覆盖正确的静态地图等级。

## 方案

### 单一地图源

`LandMapRepository.loadDefault()` 直接使用 `GameServerConfig.CFG_DB_ID`。删除独立的守军地图选择配置，防止登录地图和守军地图再次漂移。

当前 `CFG_DB_ID=5`，因此 `15061504` 的静态等级统一为 6，规范守军为 `611,612`。

### 清理动态覆盖

登录快照显式包含以下空表：

- `Tb_developed_land`
- `Tb_land_reclamation`
- `Tb_store_house`

客户端全量初始化时据此清除旧数据，使 `GetRealResLevel()` 在服务端尚未实现土地开发、垦殖和仓库玩法时回落到 map 5 静态等级。

后续若服务端实现这些玩法，应由服务端持久化并下发真实表数据，而不是重新拆分地图源。

## 数据流

1. 登录响应下发 `cfgDataIndex=5`。
2. 登录快照下发 `Tb_sys_param[26]=5`，并清空三个动态土地表。
3. 客户端加载 map 5，并以其 `resources_in_map` 计算地块等级。
4. 服务端 `LandDefenderFactory` 通过默认 `LandMapRepository` 读取同一 map 5。
5. `4329`、`4331` 和 PvE 战斗结算共用该守军选择结果。

## 错误处理

- 地图资源缺失、解压后长度非平方数或坐标越界时继续快速失败，不回退到其他地图。
- 不允许守军地图静默使用与 `CFG_DB_ID` 不同的配置。
- 本次不启动或重启游戏服务端，由用户自行控制服务进程。

## 测试

1. 新增回归测试，证明默认地图与 `CFG_DB_ID` 地图在多个哨兵坐标上结果一致；修改前该测试应失败。
2. 更新 `15061504` 断言为 6 级和 `611,612`。
3. 新增登录快照测试，确认三个动态土地表存在且为空；修改前该测试应失败。
4. 运行地图守军、登录快照及协议定向测试。
5. 构建后用运行包验证 `4329`、`4331` 对 `15061504` 均返回 `[15061504,"611,612"]`。

## 非目标

- 不修改或注入客户端地图逻辑。
- 不实现土地开发、垦殖或仓库玩法。
- 不改变卡包、战法和其他赛季配置。
- 不自动启动或重启服务端。
