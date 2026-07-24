# 服务端模块化、SQLite 持久化与命令策略设计

## 1. 目标与范围

本次改造解决三个问题：

1. 将 `GameServerHandler` 中的登录、城建、卡牌、部队和战斗行为拆为深模块。
2. 使用 SQLite 持久化玩家资源、建筑、武将、多城多队状态。
3. 用显式命令清单替换“所有业务命令默认返回空数组”的宽泛兜底。

改造必须保持当前 `stzb 9.2.2` 客户端已验证协议行为，包括响应类型、数组槽位、
`90005` 表通知顺序以及部队位置语义。

本次不持久化 SID、连接会话和战报，不扩展新的战斗规则，也不引入多进程部署。

所有协议与状态设计必须以反编译客户端证据为依据。第一阶段证据记录在：

```text
docs/client-contract-audit-phase1.md
```

已有服务端行为只能作为待验证样本，不能单独证明客户端契约。

## 2. 设计原则

- `GameServerHandler` 只处理连接生命周期、会话、保活和命令分派。
- 每个业务模块通过小接口封装一个完整客户端状态闭环：请求解析、状态修改、持久化、
  直接响应和 `90005` 表通知。
- 玩家可见状态只有在 SQLite 事务提交成功后才能向客户端发送成功响应和 `90005` 通知。
- 所有客户端命令必须显式归类；未知命令不得自动返回 `[]`。
- 保持客户端部队位置规则：`1=大营`、`2=中军`、`3=前锋`。
- 一个玩家可以在多个城池拥有多支部队，一个武将同一时间只能属于一支部队。
- 请求形状、响应强制类型和客户端表字段映射集中在协议 adapter 中，业务模块不得散落
  裸数组槽位。

## 3. 总体架构

```text
TCP
  -> UpFrameDecoder
  -> GameServerHandler
       -> CommandRegistry
       -> ExactCommandDispatcher
            -> LoginModule
            -> BuildingModule
            -> CardModule
            -> ArmyModule
            -> BattleModule
            -> CommonModule
       -> FallbackResponder
       -> Forbidden/Unknown logger
  -> DownFrameEncoder

业务模块
  -> PlayerStateRepository
       -> SQLitePlayerStateRepository（运行时）
       -> InMemoryPlayerStateRepository（测试）
```

`GameServerHandler` 不再了解具体请求数组槽位、数据库表通知或玩家状态修改细节。
业务模块负责完整业务行为，路由层只根据命令清单找到唯一模块。

## 4. 命令路由与模块接口

### 4.1 命令分类

每个命令在 `CommandRegistry` 中属于以下一种状态：

- `EXACT`：请求、直接响应和客户端状态变化均有源码或实机证据，且关联唯一处理模块。
- `FALLBACK`：暂时没有完整业务实现，但已确认一种安全响应形状。
- `FORBIDDEN`：当前明确不应执行，只记录日志，不回包，不关闭连接。

未登记命令按 `UNKNOWN` 处理：记录警告，不回包，不关闭连接。

注册表应是命令行为的唯一事实来源。启动时校验：

- 命令号不能重复登记。
- 每个 `EXACT` 命令必须存在且只能存在一个处理模块。
- 每个 `FALLBACK` 命令必须声明响应形状。
- 系统保留命令不得被误登记为普通空数组兜底。

每项命令契约必须记录：

- 请求形状与每个槽位的类型。
- 直接响应形状和客户端强制转换方式。
- 成功后必须变化的客户端表。
- 直接响应和 `90005` 通知的已知顺序。
- 客户端源码路径与行号。
- 证据等级：
  - `SOURCE_PROVEN`：反编译源码已证明。
  - `RUNTIME_CONFIRMED`：源码证据之外，已通过真实客户端日志确认。

只有现有 handler、没有客户端证据的命令标为 `PROVISIONAL`，不得进入 `EXACT`。它可以在
联调阶段保留专门实现，但日志必须提示仍需契约确认。

### 4.2 模块接口

模块使用统一接口：

```kotlin
interface CommandModule {
    val commands: Set<Int>
    fun handle(context: CommandContext, packet: UpPacket): CommandResult
}
```

`CommandContext` 提供当前 Netty channel、会话、时间源和玩家仓库。业务模块不自行创建
仓库或连接。

`CommandResult` 描述应发送的响应包及日志信息。对于状态修改命令，模块通过玩家仓库的
事务接口完成修改，提交成功后才返回成功结果。

`CommandResult` 保持包的先后顺序。例如建筑升级必须先返回命令响应，再依次发送所需
的 `90005` 表更新。路由层不重排结果。

状态修改模块返回的是提交后的客户端同步计划：

```kotlin
data class CommandResult(
    val packets: List<DownPacket>,
    val evidence: ContractEvidence,
)
```

`packets` 同时包含直接响应和必要的表通知。路由层不能在模块外另行生成 `90005`，避免
持久状态与在线投影来自不同版本。

### 4.3 模块划分

- `LoginModule`
  - 99992、99994、20003、98702、99991、511、2。
- `BuildingModule`
  - 13、14，以及相关 `Tb_user_build`、`Tb_build_effect_city`、`Tb_user_res` 通知。
- `CardModule`
  - 301、304、302、80、81、82、185、186、300、308。
- `ArmyModule`
  - 30、32、37、38、8005、8011、9026、9029。
- `BattleModule`
  - 6、10、11、6231；保持现有战斗和战报为内存态。
- `CommonModule`
  - 对时、土地、世界场景、改名、红点和其他已精确实现但不属于以上领域的命令。

模块划分允许内部使用现有 parser、response builder 和领域逻辑，不要求一次性重写它们。
只有完成客户端契约审计的命令才能随模块迁移提升为 `EXACT`。

## 5. 多城多队玩家模型

### 5.1 内存模型

```kotlin
data class PlayerArmy(
    val armyId: Int,
    val cityWid: Int,
    val slots: MutableMap<ArmyPosition, Int>,
)

enum class ArmyPosition(val clientValue: Int) {
    BASE(1),
    MIDDLE(2),
    FRONT(3),
}

class PlayerState {
    val armies: MutableMap<Int, PlayerArmy>
}
```

槽位值为 `heroUid`，空槽使用 `0` 或无映射，但输出客户端协议时统一转为 `0`。

必须保持以下映射：

| 客户端位置 | 含义 | `Tb_army` 索引 |
|---|---|---|
| 1 | 大营 | `base_heroid_u`，索引 7 |
| 2 | 中军 | `middle_heroid_u`，索引 6 |
| 3 | 前锋 | `front_heroid_u`，索引 5 |

### 5.2 不变量

- `armyId` 在一个玩家范围内唯一。
- 每支部队只属于一个城池。
- 一支部队的每个位置最多一个武将。
- 一个武将最多出现在一支部队的一个位置。
- 武将的 `armyId` 必须和部队槽位同步。
- 默认主城第一队编号仍为 `cityWid * 10 + 1`。

把武将加入新部队时，如果该武将原来已上阵，必须同时清除原部队槽位。目标槽位已有武将
时，被替换武将的 `armyId` 必须清零。

### 5.3 命令定位规则

- `cmd=30` 直接使用请求中的 `armyId` 和 `cityWid`。
- `cmd=30` 直接响应必须是全为整数的 army id 列表，并同步 `Tb_army + Tb_hero`。
- `cmd=32` 使用请求中的两个 `armyId`，允许同队换位和跨队交换；直接响应必须是全为整数
  的受影响 army id 列表，并同步所有受影响的 `Tb_army + Tb_hero`。
- `cmd=37/38` 如果请求未直接携带 `armyId`，通过请求涉及的武将反查所属部队。
  所有武将必须唯一指向同一部队；无法定位或涉及多支部队时不得返回伪造 army id。
  两个命令的成功响应必须是可 `Convert.ToInt32` 的标量，不能返回数组。
- 保存队伍类命令必须明确关联目标队伍；现有缺少队伍标识的协议按其已确认字段解析。

登录快照中的 `Tb_army` 输出玩家全部城池下的全部部队记录。

## 6. SQLite 持久化

### 6.1 运行配置

默认数据库路径：

```text
server/data/stzb.db
```

环境变量 `STZB_DB_PATH` 可以覆盖路径。启动时自动创建父目录、数据库和表。

实现采用 JDBC 与 SQLite JDBC 驱动，不引入 ORM。连接启用：

- `PRAGMA foreign_keys = ON`
- `PRAGMA journal_mode = WAL`
- 合理的 `busy_timeout`

### 6.2 表结构

`schema_version`

- `version INTEGER PRIMARY KEY`
- `applied_at INTEGER NOT NULL`

`players`

- `user_id INTEGER PRIMARY KEY`
- `role_name TEXT NOT NULL`
- `primary_city_wid INTEGER NOT NULL`

`player_resources`

- `user_id INTEGER PRIMARY KEY REFERENCES players(user_id) ON DELETE CASCADE`
- `money`、`wood`、`stone`、`iron`、`food`、`yuan_bao`、`hufu`

`player_buildings`

- `user_id INTEGER NOT NULL REFERENCES players(user_id) ON DELETE CASCADE`
- `city_wid INTEGER NOT NULL`
- `build_id INTEGER NOT NULL`
- `level INTEGER NOT NULL`
- 主键 `(user_id, city_wid, build_id)`

`player_heroes`

- `hero_uid INTEGER PRIMARY KEY`
- `user_id INTEGER NOT NULL REFERENCES players(user_id) ON DELETE CASCADE`
- `hero_id INTEGER NOT NULL`
- `created_at_sec INTEGER NOT NULL`
- `army_id INTEGER NOT NULL DEFAULT 0`
- `troops INTEGER NOT NULL`
- `stamina INTEGER NOT NULL`
- `level INTEGER NOT NULL`

`player_armies`

- `user_id INTEGER NOT NULL REFERENCES players(user_id) ON DELETE CASCADE`
- `army_id INTEGER NOT NULL`
- `city_wid INTEGER NOT NULL`
- 为客户端 `Tb_army` 在线与登录投影保存当前实现实际使用的驻扎位置、状态、征兵及行军字段；
  未实现字段使用显式默认值，后续按客户端审计扩展
- 主键 `(user_id, army_id)`

`player_army_slots`

- `user_id INTEGER NOT NULL`
- `army_id INTEGER NOT NULL`
- `position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 3)`
- `hero_uid INTEGER NOT NULL DEFAULT 0`
- 主键 `(user_id, army_id, position)`
- 外键 `(user_id, army_id)` 引用 `player_armies`

应用层在保存前校验武将唯一归属；数据库层为非零 `hero_uid` 建立唯一索引，防止同一武将
进入多个槽位。

SQLite schema 不机械复制客户端全部表，但每个字段必须能无歧义投影为客户端表。新增持久
字段前先确认客户端表字段语义。登录快照和在线 `90005` 必须共用同一组 table projector，
禁止分别维护两套数组生成代码。

### 6.3 仓库接口和 adapter

```kotlin
interface PlayerStateRepository {
    fun getOrCreate(userId: Int, defaults: NewPlayerDefaults): PlayerState
    fun <T> update(userId: Int, block: (PlayerState) -> StateChange<T>): T
}
```

- `SQLitePlayerStateRepository` 是运行时 adapter。
- `InMemoryPlayerStateRepository` 是测试 adapter。
- `update` 对同一玩家串行化，复制或加载状态，执行不变量校验，在一个 SQLite 事务中保存
  完整玩家快照，提交后更新内存缓存并返回结果。
- block 抛错、校验失败或 SQLite 提交失败时，内存缓存不得保留部分修改。
- 业务模块捕获可预期的领域失败并生成安全失败响应；持久化错误记录 ERROR，且不发送成功
  响应或 `90005` 通知。

第一版采用完整玩家快照事务，而不是逐字段增量 SQL，以换取一致性和实现清晰度。当前是
单机私服，数据规模允许这一选择。

### 6.4 数据迁移

当前状态只存在内存，没有旧数据库需要迁移。首次创建玩家时：

- 建立默认资源和主城。
- 建立默认主城第一队 `cityWid * 10 + 1`。
- 按现有登录快照要求生成初始建筑和其他必要状态。

后续表结构变化通过按版本顺序执行的 SQL migration 升级，禁止运行时直接删除用户表。

## 7. 登录快照与状态通知

`UserInitTableBuilder` 从 `PlayerState` 生成快照，不再依赖隐式单队伍状态：

- `Tb_army` 包含所有部队。
- `Tb_hero.army_id` 与实际槽位一致。
- 继续提供 `Tb_build_effect_city[26] army_cost_max`。
- 保留现有必须空表和客户端所需固定数组槽位。

所有状态修改模块遵循：

```text
解析并校验请求
  -> 仓库事务更新
  -> SQLite 提交
  -> 构建命令响应
  -> 构建对应 90005 通知
  -> 按协议顺序发送
```

如果保存失败，禁止发送能让客户端误认为操作成功的响应。

对客户端而言，直接响应和表通知承担不同职责。例如：

- `cmd=30/32` 的整数列表响应驱动回调，`Tb_army + Tb_hero` 才改变状态。
- `cmd=37/38` 的标量 army id 驱动征兵完成事件，`Tb_hero + Tb_user_res` 刷新兵力和资源。
- `cmd=301/304` 的结构化响应驱动招募动画，`Tb_hero` 插入驱动卡库状态。
- `cmd=13/14` 的可见结果主要通过 `Tb_user_build + Tb_build_effect_city + Tb_user_res` 驱动。

模块测试必须分别验证直接响应和客户端表投影，不能只断言 JSON 非空。

## 8. 错误处理与日志

- 协议解析失败：记录命令号、用户和安全截断后的包体，返回该精确命令约定的失败结果。
- 领域校验失败：不修改状态，返回安全失败。
- SQLite 失败：回滚事务，记录 ERROR，不发送成功响应。
- `FALLBACK`：记录 WARN，并发送清单声明的响应形状。
- `FORBIDDEN`：记录 WARN，不回包，不关闭连接。
- `UNKNOWN`：记录 WARN，不回包，不关闭连接。
- 无效帧和底层网络异常仍由现有网络层策略处理，本设计不改变其关闭连接行为。

日志必须区分 `EXACT`、`FALLBACK`、`FORBIDDEN` 和 `UNKNOWN`，便于从客户端联调日志中发现
仍需精确实现的协议。

## 9. 测试策略

### 9.1 模块与路由测试

- 每个 `EXACT` 命令恰好对应一个模块。
- 每个 `EXACT` 命令具有请求、响应、表变化和源码证据。
- 重复命令注册导致启动校验失败。
- `FALLBACK` 按声明形状返回。
- `FORBIDDEN` 和 `UNKNOWN` 均不回包、不关闭连接。
- 现有精确命令不再经过通用空数组兜底。

### 9.2 多队伍模型测试

- 同队位置交换保持 `1=大营、2=中军、3=前锋`。
- 跨队交换同时更新两支部队和两个武将。
- cmd=30/32 直接响应只能包含整数 army id。
- 武将转队会清除原槽位。
- 多城下可分别创建多支队伍。
- 征兵能通过武将唯一定位部队，歧义请求安全失败。
- cmd=37/38 成功响应是标量 army id。
- 登录快照输出全部 `Tb_army`，且 `Tb_hero.army_id` 一致。

### 9.3 SQLite 集成测试

每个测试使用临时数据库：

- 保存状态后销毁并重建仓库，完整恢复资源、建筑、武将和所有部队。
- 事务失败不产生资源已扣但建筑未升级等部分状态。
- 同一武将不能写入多个部队槽位。
- 数据库首次创建和重复启动均成功。
- 环境路径覆盖生效。

### 9.4 回归验证

必须通过：

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process
./gradlew installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

随后使用 `test_client.py` 验证握手、平台校验、服务器列表和登录快照。涉及登录快照的修改
需要重启服务并让客户端重新登录后验证。

## 10. 实施顺序

1. 从客户端发包、回调、强转、表事件和 UI 消费建立第一批命令契约。
2. 建立带证据等级的命令清单和路由测试；未经证明的现有行为标为 `PROVISIONAL`。
3. 建立统一客户端表 projector，让登录快照和在线 `90005` 共用字段映射。
4. 引入模块接口，按客户端状态闭环逐命令迁移 handler 行为。
5. 将单队伍模型升级为多城多队，并修正所有快照与通知生成。
6. 抽取玩家仓库接口，保留内存 adapter。
7. 引入 SQLite schema、migration 和运行时 adapter。
8. 将状态修改命令切换为事务更新，补重启恢复测试。
9. 删除旧的宽泛业务空数组兜底，完成全量回归和真实客户端联调。

每一步都应保持测试可运行，避免同时重写协议响应和状态模型。
