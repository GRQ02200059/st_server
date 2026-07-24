# Client-Driven Server Modularization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依据 `stzb 9.2.2` 反编译客户端契约，将服务端改造成显式命令策略、客户端状态闭环模块、多城多队模型和 SQLite 持久化架构。

**Architecture:** 先把客户端请求、响应强转和 `90005` 表变化登记为可测试的 `CommandContract`，再建立登录快照与在线通知共用的 `ClientTableProjector`。业务模块在 `PlayerStateRepository.update` 事务中修改状态，提交成功后一次性返回直接响应和表通知；运行时使用 SQLite adapter，测试使用内存 adapter。

**Tech Stack:** Kotlin/JVM 17、Netty 4.1.109、Jackson 2.17、SQLite JDBC、Gradle、`kotlin.test`。

## Global Constraints

- 客户端反编译源码是协议设计的主证据；现有服务端行为不能单独证明客户端契约。
- 客户端版本固定为 `stzb 9.2.2`。
- 部队位置固定为 `1=大营(BASE)`、`2=中军(MIDDLE)`、`3=前锋(FRONT)`。
- `cmd=30/32` 直接响应必须是整数 army id 列表。
- `cmd=37/38` 成功响应必须是可 `Convert.ToInt32` 的标量 army id。
- 登录快照和在线 `90005` 必须共用同一组客户端表 projector。
- SQLite 默认路径为 `server/data/stzb.db`，环境变量 `STZB_DB_PATH` 可覆盖。
- SQLite 启用 foreign keys、WAL 和 busy timeout。
- SQLite 第一阶段持久化资源、建筑、武将、多城多队；不持久化 SID、连接会话和战报。
- `FORBIDDEN` 和未知命令只记录，不回包，不关闭连接。
- 保存失败必须回滚，且不得发送成功响应或 `90005`。
- 运行 Gradle 时使用 `--no-daemon -Dkotlin.compiler.execution.strategy=in-process`。
- 每项行为先写失败测试，再写最小实现。

---

## File Structure

### 命令契约与路由

- Create `src/main/kotlin/com/stzb/server/protocol/CommandContract.kt`
  - 定义命令分类、证据等级、请求/响应形状和客户端表变化。
- Create `src/main/kotlin/com/stzb/server/protocol/CommandRegistry.kt`
  - 集中登记命令并校验重复、缺失 handler 和危险兜底。
- Replace `src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt`
  - 只负责已登记的 `FALLBACK` 响应，不再兜底所有 `1..99999`。
- Create `src/test/kotlin/com/stzb/server/protocol/CommandRegistryTest.kt`

### 客户端表投影

- Create `src/main/kotlin/com/stzb/server/clienttable/ClientTableProjector.kt`
  - 唯一维护 `Tb_army`、`Tb_hero`、`Tb_user_res`、`Tb_user_build`、
    `Tb_build_effect_city` 行槽位。
- Create `src/main/kotlin/com/stzb/server/game/PlayerArmy.kt`
  - 先定义 projector 所需的 `ArmyPosition` 和 `PlayerArmy`；下一任务再接入 `PlayerState`。
- Modify `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
  - 使用 projector 构造登录快照。
- Modify `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
  - 使用 projector 构造在线 `90005`。
- Create `src/test/kotlin/com/stzb/server/clienttable/ClientTableProjectorTest.kt`

### 玩家领域与持久化

- Modify `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
  - 单队伍改为多城多队。
- Modify `src/main/kotlin/com/stzb/server/game/PlayerArmy.kt`
  - 将已定义的显式位置模型接入多城多队不变量。
- Create `src/main/kotlin/com/stzb/server/persistence/PlayerStateRepository.kt`
  - 仓库 seam 和事务结果。
- Create `src/main/kotlin/com/stzb/server/persistence/InMemoryPlayerStateRepository.kt`
- Create `src/main/kotlin/com/stzb/server/persistence/SQLitePlayerStateRepository.kt`
- Create `src/main/kotlin/com/stzb/server/persistence/SQLiteSchema.kt`
- Create `src/main/resources/db/migration/V1__player_state.sql`
- Modify `src/main/kotlin/com/stzb/server/Main.kt`
  - 组装 SQLite adapter。
- Modify `build.gradle.kts`
  - 增加 SQLite JDBC。

### 业务模块

- Create `src/main/kotlin/com/stzb/server/handler/CommandModule.kt`
- Create `src/main/kotlin/com/stzb/server/handler/CommandDispatcher.kt`
- Create `src/main/kotlin/com/stzb/server/handler/modules/LoginModule.kt`
- Create `src/main/kotlin/com/stzb/server/handler/modules/BuildingModule.kt`
- Create `src/main/kotlin/com/stzb/server/handler/modules/CardModule.kt`
- Create `src/main/kotlin/com/stzb/server/handler/modules/ArmyModule.kt`
- Create `src/main/kotlin/com/stzb/server/handler/modules/BattleModule.kt`
- Create `src/main/kotlin/com/stzb/server/handler/modules/CommonModule.kt`
- Modify `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
  - 最终只保留连接、会话、保活和 dispatcher 调用。

---

### Task 1: Evidence-Backed Command Registry

**Files:**
- Create: `src/main/kotlin/com/stzb/server/protocol/CommandContract.kt`
- Create: `src/main/kotlin/com/stzb/server/protocol/CommandRegistry.kt`
- Create: `src/test/kotlin/com/stzb/server/protocol/CommandRegistryTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/protocol/NetworkResponsePolicyTest.kt`
- Reference: `docs/client-contract-audit-phase1.md`
- Reference: `stzb_9.2.2_out_branch_9.1.1776213/assets/decompiled/Game.Network/Tenth.Network/NetCommandDef.cs`

**Interfaces:**
- Produces:
  - `enum class CommandDisposition { EXACT, PROVISIONAL, FALLBACK, FORBIDDEN }`
  - `enum class ContractEvidence { SOURCE_PROVEN, RUNTIME_CONFIRMED, PROVISIONAL }`
  - `data class CommandContract(...)`
  - `class CommandRegistry`
  - `CommandRegistry.contract(cmdId: Int): CommandContract?`

- [ ] **Step 1: Write registry validation tests**

```kotlin
class CommandRegistryTest {
    @Test
    fun `duplicate command registration is rejected`() {
        val c = CommandContract.sourceProven(
            cmdId = 30,
            requestShape = "[int,int,int,int]",
            responseShape = "List<Int>",
            changedTables = setOf("Tb_army", "Tb_hero"),
            source = "ArmyOpRequest.cs:1691-1776",
        )
        assertFailsWith<IllegalArgumentException> {
            CommandRegistry(listOf(c, c))
        }
    }

    @Test
    fun `audited army and conscript contracts retain client types`() {
        val registry = CommandRegistry.default()
        assertEquals("List<Int>", registry.contract(30)?.responseShape)
        assertEquals("List<Int>", registry.contract(32)?.responseShape)
        assertEquals("Int", registry.contract(37)?.responseShape)
        assertEquals("Int", registry.contract(38)?.responseShape)
        assertEquals(setOf("Tb_army", "Tb_hero"), registry.contract(30)?.changedTables)
    }

    @Test
    fun `unknown command is not silently converted to fallback`() {
        assertNull(CommandRegistry.default().contract(65432))
    }
}
```

- [ ] **Step 2: Run the focused test and verify red**

Run:

```bash
./gradlew test --tests com.stzb.server.protocol.CommandRegistryTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: compilation fails because `CommandContract` and `CommandRegistry` do not exist.

- [ ] **Step 3: Implement the contract types and startup validation**

```kotlin
data class CommandContract(
    val cmdId: Int,
    val disposition: CommandDisposition,
    val evidence: ContractEvidence,
    val requestShape: String,
    val responseShape: String?,
    val changedTables: Set<String>,
    val source: String,
    val fallbackBody: String? = null,
) {
    init {
        require(cmdId > 0)
        if (disposition == CommandDisposition.FALLBACK) {
            requireNotNull(fallbackBody)
        }
        if (disposition == CommandDisposition.EXACT) {
            require(evidence != ContractEvidence.PROVISIONAL)
            require(source.isNotBlank())
        }
    }
}

class CommandRegistry(contracts: List<CommandContract>) {
    private val byId = contracts.associateBy { it.cmdId }

    init {
        require(byId.size == contracts.size) { "命令号重复登记" }
    }

    fun contract(cmdId: Int): CommandContract? = byId[cmdId]
}
```

Populate source-proven entries for `13, 14, 30, 32, 37, 38, 301, 304` using
`docs/client-contract-audit-phase1.md`. Register other currently implemented commands as
`PROVISIONAL` until their client chains are audited.

- [ ] **Step 4: Run protocol tests**

```bash
./gradlew test --tests 'com.stzb.server.protocol.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all protocol tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/protocol/CommandContract.kt \
  src/main/kotlin/com/stzb/server/protocol/CommandRegistry.kt \
  src/test/kotlin/com/stzb/server/protocol/CommandRegistryTest.kt \
  src/test/kotlin/com/stzb/server/protocol/NetworkResponsePolicyTest.kt
git commit -m "feat: add evidence-backed command registry"
```

### Task 2: Shared Client Table Projector

**Files:**
- Create: `src/main/kotlin/com/stzb/server/clienttable/ClientTableProjector.kt`
- Create: `src/main/kotlin/com/stzb/server/game/PlayerArmy.kt`
- Create: `src/test/kotlin/com/stzb/server/clienttable/ClientTableProjectorTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

**Interfaces:**
- Produces:
  - `ClientTableProjector.tbArmy(army: PlayerArmy): ArrayNode`
  - `ClientTableProjector.tbHero(userId: Int, hero: PlayerHero): ArrayNode`
  - `ClientTableProjector.tbUserRes(userId: Int, resources: PlayerResources): ArrayNode`
  - `ClientTableProjector.tbUserBuild(...): ArrayNode`
  - `ClientTableProjector.tbBuildEffectCity(...): ArrayNode`

- [ ] **Step 1: Add parity tests for login and `90005` rows**

```kotlin
@Test
fun `army projector preserves client base middle front slots`() {
    val army = PlayerArmy(
        armyId = 1000011,
        cityWid = 100001,
        slots = mutableMapOf(
            ArmyPosition.BASE to 11,
            ArmyPosition.MIDDLE to 22,
            ArmyPosition.FRONT to 33,
        ),
    )
    val row = ClientTableProjector.tbArmy(userId = 7, army = army)
    assertEquals(33, row[5].asInt())
    assertEquals(22, row[6].asInt())
    assertEquals(11, row[7].asInt())
}

@Test
fun `login and notify use identical hero row`() {
    val hero = PlayerHero(101, 100017, 1_700_000_000, armyId = 1000011)
    val loginRow = ClientTableProjector.tbHero(7, hero)
    val notifyRow = ClientTableProjector.tbHero(7, hero)
    assertEquals(loginRow, notifyRow)
}
```

- [ ] **Step 2: Verify tests fail before extraction**

Run:

```bash
./gradlew test --tests com.stzb.server.clienttable.ClientTableProjectorTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: unresolved `ClientTableProjector` and `PlayerArmy`.

- [ ] **Step 3: Extract row construction without changing slots**

Move the existing row-building bodies from `UserInitTableBuilder` and `GameResponses` into
`ClientTableProjector`. Preserve every current array index. Do not introduce a generic reflection mapper;
use named projector functions so client slots remain reviewable.

- [ ] **Step 4: Route both login and online notifications through projector**

Define `ArmyPosition` and `PlayerArmy` in this task so the projector never accepts a positional
`List<Int>`. `UserInitTableBuilder` must wrap projected rows into login tables. `GameResponses` must wrap the same
projected rows into the current `90005` operation envelope. Delete duplicate private `tbArmy`, `tbHero`,
`tbUserRes`, `tbUserBuild`, and `tbBuildEffectCity` implementations after parity tests pass.

- [ ] **Step 5: Run table and response tests**

```bash
./gradlew test \
  --tests com.stzb.server.clienttable.ClientTableProjectorTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all pass and existing slot assertions remain unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/stzb/server/clienttable \
  src/main/kotlin/com/stzb/server/game/PlayerArmy.kt \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/main/kotlin/com/stzb/server/game/GameResponses.kt \
  src/test/kotlin/com/stzb/server/clienttable \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt \
  src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt
git commit -m "refactor: unify client table projections"
```

### Task 3: Multi-City Multi-Army Domain Model

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerArmy.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerConscriptService.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/TeamRequestParser.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerStateRepositoryTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerConscriptServiceTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`

**Interfaces:**
- Produces:
  - `enum class ArmyPosition(val clientValue: Int)`
  - `data class PlayerArmy`
  - `PlayerState.armies(): List<PlayerArmy>`
  - `PlayerState.assignHero(cityWid, armyId, heroUid, position)`
  - `PlayerState.switchHeroes(armyId1, position1, armyId2, position2)`
  - `PlayerState.findArmyForHeroes(heroUids): PlayerArmy?`

- [ ] **Step 1: Write multi-army invariant tests**

```kotlin
@Test
fun `one player can own armies in multiple cities`() {
    val state = PlayerState(7, 100001, "主公")
    state.ensureArmy(1000011, 100001)
    state.ensureArmy(2000011, 200001)
    assertEquals(setOf(100001, 200001), state.armies().map { it.cityWid }.toSet())
}

@Test
fun `moving hero to another army clears original slot`() {
    val state = PlayerState(7, 100001, "主公")
    val hero = state.addHero(100017)
    state.assignHero(100001, 1000011, hero.heroUid, ArmyPosition.BASE)
    state.assignHero(200001, 2000011, hero.heroUid, ArmyPosition.FRONT)
    assertEquals(0, state.army(1000011)!!.heroAt(ArmyPosition.BASE))
    assertEquals(hero.heroUid, state.army(2000011)!!.heroAt(ArmyPosition.FRONT))
    assertEquals(2000011, state.hero(hero.heroUid)!!.armyId)
}

@Test
fun `cross army switch updates both hero ownerships`() {
    // Arrange two heroes in different armies, swap BASE and FRONT,
    // then assert both slots and both PlayerHero.armyId values.
}
```

- [ ] **Step 2: Run player tests and verify red**

```bash
./gradlew test --tests com.stzb.server.game.PlayerStateRepositoryTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: unresolved multi-army interfaces.

- [ ] **Step 3: Implement explicit positions and invariants**

```kotlin
enum class ArmyPosition(val clientValue: Int) {
    BASE(1), MIDDLE(2), FRONT(3);

    companion object {
        fun fromClient(value: Int): ArmyPosition =
            entries.firstOrNull { it.clientValue == value }
                ?: throw IllegalArgumentException("非法部队位置: $value")
    }
}

data class PlayerArmy(
    val armyId: Int,
    val cityWid: Int,
    private val slots: MutableMap<ArmyPosition, Int> = mutableMapOf(),
) {
    fun heroAt(position: ArmyPosition): Int = slots[position] ?: 0
    fun put(position: ArmyPosition, heroUid: Int): Int = slots.put(position, heroUid) ?: 0
    fun clear(position: ArmyPosition): Int = slots.remove(position) ?: 0
}
```

`PlayerState` must create the default `cityWid * 10 + 1` army but store all armies in
`LinkedHashMap<Int, PlayerArmy>`. When moving a hero, clear every old slot before assigning the new slot.

- [ ] **Step 4: Change conscription lookup to hero ownership**

`PlayerConscriptService` must obtain the army from all requested heroes:

```kotlin
val armyIds = request.items.mapNotNull { state.hero(it.heroUid)?.armyId.takeIf { id -> id > 0 } }.toSet()
require(armyIds.size == 1) { "征兵武将未唯一归属同一部队" }
val armyId = armyIds.single()
```

Do not fall back to `primaryArmyId()`.

- [ ] **Step 5: Make login snapshot emit every army**

Update `UserInitTableBuilder` to call the projector for `state.armies()` and assert two different cities
produce two `Tb_army` rows.

- [ ] **Step 6: Run all game-state tests**

```bash
./gradlew test \
  --tests com.stzb.server.game.PlayerStateRepositoryTest \
  --tests com.stzb.server.game.PlayerConscriptServiceTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/PlayerArmy.kt \
  src/main/kotlin/com/stzb/server/game/PlayerState.kt \
  src/main/kotlin/com/stzb/server/game/PlayerConscriptService.kt \
  src/main/kotlin/com/stzb/server/game/TeamRequestParser.kt \
  src/test/kotlin/com/stzb/server/game
git commit -m "feat: support multiple armies across cities"
```

### Task 4: Transactional Repository Seam and In-Memory Adapter

**Files:**
- Create: `src/main/kotlin/com/stzb/server/persistence/PlayerStateRepository.kt`
- Create: `src/main/kotlin/com/stzb/server/persistence/InMemoryPlayerStateRepository.kt`
- Create: `src/test/kotlin/com/stzb/server/persistence/InMemoryPlayerStateRepositoryTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Modify: existing tests that call the global `PlayerStateRepository` object.

**Interfaces:**
- Produces:

```kotlin
interface PlayerStateRepository : AutoCloseable {
    fun getOrCreate(userId: Int, defaults: NewPlayerDefaults): PlayerState
    fun <T> update(
        userId: Int,
        defaults: NewPlayerDefaults,
        block: (PlayerState) -> T,
    ): T
}
```

- [ ] **Step 1: Write commit and rollback tests**

```kotlin
@Test
fun `successful update commits copied state`() {
    val repo = InMemoryPlayerStateRepository()
    repo.update(7, defaults) { it.resources.wood -= 100 }
    assertEquals(99_900, repo.getOrCreate(7, defaults).resources.wood)
}

@Test
fun `failed update leaves original state unchanged`() {
    val repo = InMemoryPlayerStateRepository()
    assertFails {
        repo.update(7, defaults) {
            it.resources.wood -= 100
            error("fail")
        }
    }
    assertEquals(100_000, repo.getOrCreate(7, defaults).resources.wood)
}
```

- [ ] **Step 2: Verify rollback test fails against mutable global storage**

Run the focused test. Expected: unresolved new repository types.

- [ ] **Step 3: Add deep-copy support and repository adapter**

`PlayerState.deepCopy()` must copy resources, buildings, heroes, armies, slots and hero sequence. The
in-memory adapter synchronizes per user, applies `block` to a copy, validates invariants, then replaces the
stored state only on success.

- [ ] **Step 4: Replace test use of the global repository**

Tests instantiate `InMemoryPlayerStateRepository`; production wiring is deferred to Task 6. Remove
`object PlayerStateRepository` only after all callers accept the interface through constructors.

- [ ] **Step 5: Run state tests**

```bash
./gradlew test --tests 'com.stzb.server.persistence.*' --tests 'com.stzb.server.game.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/stzb/server/persistence \
  src/main/kotlin/com/stzb/server/game/PlayerState.kt \
  src/test/kotlin/com/stzb/server/persistence \
  src/test/kotlin/com/stzb/server/game
git commit -m "refactor: add transactional player repository seam"
```

### Task 5: SQLite Schema, Migration, and Snapshot Persistence

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/resources/db/migration/V1__player_state.sql`
- Create: `src/main/kotlin/com/stzb/server/persistence/SQLiteSchema.kt`
- Create: `src/main/kotlin/com/stzb/server/persistence/SQLitePlayerStateRepository.kt`
- Create: `src/test/kotlin/com/stzb/server/persistence/SQLitePlayerStateRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `SQLitePlayerStateRepository(dbPath: Path)`
  - `SQLiteSchema.migrate(connection: Connection)`

- [ ] **Step 1: Add SQLite restart and atomicity tests**

```kotlin
@Test
fun `state survives repository restart`() {
    val db = tempDir.resolve("stzb.db")
    SQLitePlayerStateRepository(db).use { repo ->
        repo.update(7, defaults) { state ->
            val hero = state.addHero(100017)
            state.assignHero(100001, 1000011, hero.heroUid, ArmyPosition.BASE)
            state.resources.food -= 123
        }
    }
    SQLitePlayerStateRepository(db).use { repo ->
        val state = repo.getOrCreate(7, defaults)
        assertEquals(99_877, state.resources.food)
        assertEquals(1, state.allHeroes().size)
        assertEquals(state.allHeroes().single().heroUid,
            state.army(1000011)!!.heroAt(ArmyPosition.BASE))
    }
}

@Test
fun `same hero cannot occupy two persisted slots`() {
    // Construct invalid state through a test hook or direct SQL and assert commit fails.
}
```

- [ ] **Step 2: Add the JDBC dependency and verify tests reach missing implementation**

```kotlin
implementation("org.xerial:sqlite-jdbc:3.46.0.0")
```

Run the focused test. Expected: unresolved repository class.

- [ ] **Step 3: Add V1 schema**

The migration must create:

```sql
CREATE TABLE schema_version (
  version INTEGER PRIMARY KEY,
  applied_at INTEGER NOT NULL
);
CREATE TABLE players (
  user_id INTEGER PRIMARY KEY,
  role_name TEXT NOT NULL,
  primary_city_wid INTEGER NOT NULL
);
CREATE TABLE player_resources (
  user_id INTEGER PRIMARY KEY REFERENCES players(user_id) ON DELETE CASCADE,
  money INTEGER NOT NULL, wood INTEGER NOT NULL, stone INTEGER NOT NULL,
  iron INTEGER NOT NULL, food INTEGER NOT NULL, yuan_bao INTEGER NOT NULL,
  hufu INTEGER NOT NULL
);
CREATE TABLE player_buildings (
  user_id INTEGER NOT NULL REFERENCES players(user_id) ON DELETE CASCADE,
  city_wid INTEGER NOT NULL, build_id INTEGER NOT NULL, level INTEGER NOT NULL,
  PRIMARY KEY (user_id, city_wid, build_id)
);
CREATE TABLE player_heroes (
  hero_uid INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES players(user_id) ON DELETE CASCADE,
  hero_id INTEGER NOT NULL, created_at_sec INTEGER NOT NULL,
  army_id INTEGER NOT NULL DEFAULT 0, troops INTEGER NOT NULL,
  stamina INTEGER NOT NULL, level INTEGER NOT NULL
);
CREATE TABLE player_armies (
  user_id INTEGER NOT NULL REFERENCES players(user_id) ON DELETE CASCADE,
  army_id INTEGER NOT NULL, city_wid INTEGER NOT NULL,
  PRIMARY KEY (user_id, army_id)
);
CREATE TABLE player_army_slots (
  user_id INTEGER NOT NULL, army_id INTEGER NOT NULL,
  position INTEGER NOT NULL CHECK(position BETWEEN 1 AND 3),
  hero_uid INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, army_id, position),
  FOREIGN KEY (user_id, army_id)
    REFERENCES player_armies(user_id, army_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_player_army_slots_hero
ON player_army_slots(hero_uid) WHERE hero_uid <> 0;
```

- [ ] **Step 4: Implement migration and connection pragmas**

Every opened connection executes:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
```

`SQLiteSchema.migrate` reads V1 from resources, applies it once in a transaction, and records version 1.

- [ ] **Step 5: Implement full snapshot load/save transaction**

Within `update`:

1. Load or create state.
2. Deep-copy it.
3. Apply and validate mutation.
4. Begin SQL transaction.
5. Upsert player and resources.
6. Replace that player's buildings, heroes, armies and slots.
7. Commit.
8. Publish committed copy to cache.

On any exception: rollback and preserve the previous cached state.

- [ ] **Step 6: Run SQLite and full state tests**

```bash
./gradlew test --tests 'com.stzb.server.persistence.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: restart, uniqueness, migration idempotence and rollback tests pass.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts src/main/resources/db \
  src/main/kotlin/com/stzb/server/persistence \
  src/test/kotlin/com/stzb/server/persistence
git commit -m "feat: persist player state in sqlite"
```

### Task 6: Command Module and Dispatcher Framework

**Files:**
- Create: `src/main/kotlin/com/stzb/server/handler/CommandModule.kt`
- Create: `src/main/kotlin/com/stzb/server/handler/CommandDispatcher.kt`
- Create: `src/test/kotlin/com/stzb/server/handler/CommandDispatcherTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt`

**Interfaces:**
- Produces:

```kotlin
data class CommandContext(
    val channel: Channel,
    val session: Session?,
    val players: PlayerStateRepository,
    val nowMillis: () -> Long,
)

data class CommandResult(val packets: List<DownPacket>)

interface CommandModule {
    val commands: Set<Int>
    fun handle(context: CommandContext, packet: UpPacket): CommandResult
}
```

- [ ] **Step 1: Test exact, fallback, forbidden and unknown dispatch**

Use Netty `EmbeddedChannel` and a fake module. Assert:

- `EXACT` invokes exactly one module and writes its packets in order.
- `FALLBACK` writes the registered body and logs classification.
- `FORBIDDEN` writes nothing and leaves channel active.
- unknown writes nothing and leaves channel active.
- duplicate module ownership fails dispatcher construction.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew test --tests com.stzb.server.handler.CommandDispatcherTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 3: Implement dispatcher validation**

At construction, compare module command ownership with registry entries. An `EXACT` or `PROVISIONAL`
contract must have exactly one owner. A `FALLBACK` or `FORBIDDEN` contract must have none.

- [ ] **Step 4: Remove broad fallback**

Delete:

```kotlin
cmdId in 1..99999 -> emptyArray()
```

`NetworkResponsePolicy` may only answer commands explicitly registered as `FALLBACK`.

- [ ] **Step 5: Run handler and protocol tests**

```bash
./gradlew test --tests 'com.stzb.server.handler.*' --tests 'com.stzb.server.protocol.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/stzb/server/handler/CommandModule.kt \
  src/main/kotlin/com/stzb/server/handler/CommandDispatcher.kt \
  src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt \
  src/test/kotlin/com/stzb/server/handler \
  src/test/kotlin/com/stzb/server/protocol
git commit -m "feat: dispatch commands by explicit policy"
```

### Task 7: Army State-Closure Module

**Files:**
- Create: `src/main/kotlin/com/stzb/server/handler/modules/ArmyModule.kt`
- Create: `src/test/kotlin/com/stzb/server/handler/modules/ArmyModuleTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/ConscriptRequestParser.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerConscriptService.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`

**Interfaces:**
- Consumes: `CommandModule`, `PlayerStateRepository`, `ClientTableProjector`
- Produces exact handling for `30, 31, 32, 37, 38, 8005, 8011, 9026, 9029`.

- [ ] **Step 1: Write client-shape and persistence-failure tests**

For cmd 30:

- request `[cityWid, heroUid, armyId, pos]`;
- direct response JSON is `[armyId]`;
- following packets are `90005 Tb_army` and `90005 Tb_hero`;
- BASE maps to `Tb_army[7]`.

For cmd 32:

- cross-army request updates and notifies both armies;
- direct response contains distinct integer army ids.

For cmd 37/38:

- response JSON is a scalar integer, not `[integer]`;
- ambiguous hero ownership produces no false success;
- repository failure produces no outbound packets.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew test --tests com.stzb.server.handler.modules.ArmyModuleTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 3: Implement each command as one transaction-to-packets closure**

Example pattern:

```kotlin
val sync = context.players.update(userId, defaults) { state ->
    state.assignHero(cityWid, armyId, heroUid, ArmyPosition.fromClient(pos))
    ArmySync(
        responseArmyIds = listOf(armyId),
        armies = listOf(state.army(armyId)!!),
        heroes = listOf(state.hero(heroUid)!!),
    )
}
return CommandResult(
    listOf(DownPacket.json(cmd, mapper.writeValueAsString(sync.responseArmyIds))) +
        sync.armies.map { notify(projector.tbArmy(userId, it)) } +
        sync.heroes.map { notify(projector.tbHero(userId, it)) },
)
```

Build packets only after `update` returns successfully.

- [ ] **Step 4: Remove migrated army methods from handler**

Delete `sendAddHeroToArmy`, `sendRemoveHeroFromArmy`, `sendSwitchHeroInArmy`, `sendConscript`,
`saveTeamConfig`, `sendHeroTeamLibrary`, and `sendNormalTeamComposition` from `GameServerHandler`.

- [ ] **Step 5: Run army, state and snapshot tests**

```bash
./gradlew test \
  --tests 'com.stzb.server.handler.modules.ArmyModuleTest' \
  --tests 'com.stzb.server.game.*' \
  --tests 'com.stzb.server.persistence.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/stzb/server/handler/modules/ArmyModule.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/main/kotlin/com/stzb/server/game/ConscriptRequestParser.kt \
  src/main/kotlin/com/stzb/server/game/PlayerConscriptService.kt \
  src/test/kotlin/com/stzb/server/handler/modules/ArmyModuleTest.kt
git commit -m "refactor: move army state closure into module"
```

### Task 8: Building and Card State-Closure Modules

**Files:**
- Create: `src/main/kotlin/com/stzb/server/handler/modules/BuildingModule.kt`
- Create: `src/main/kotlin/com/stzb/server/handler/modules/CardModule.kt`
- Create: `src/test/kotlin/com/stzb/server/handler/modules/BuildingModuleTest.kt`
- Create: `src/test/kotlin/com/stzb/server/handler/modules/CardModuleTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`

**Interfaces:**
- Produces exact handling for:
  - Building: `13, 14`
  - Card: `301, 304, 302, 80, 81, 82, 185, 186, 300, 308`

- [ ] **Step 1: Test building request slots and table closure**

For `[cityWid, buildId, isFree, targetLevel, 0, needQuickBuild]`, assert successful operation emits:

1. direct response with existing compatible shape;
2. `Tb_user_build`;
3. `Tb_build_effect_city`;
4. `Tb_user_res`.

Assert SQLite failure emits none. Assert a restart preserves the new level and resources.

- [ ] **Step 2: Test card 301/304 nested response shapes**

For cmd 301 assert at least five response slots and `response[1]` is convertible to `int[][]`.
For cmd 304 assert at least eight slots, `[5]` has at least five integers and `[7]` is card rows.
Assert real recruited heroes are committed before their `Tb_hero` notifications are constructed.

- [ ] **Step 3: Run tests and verify red**

```bash
./gradlew test \
  --tests com.stzb.server.handler.modules.BuildingModuleTest \
  --tests com.stzb.server.handler.modules.CardModuleTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 4: Implement modules using repository transactions**

Do not duplicate row arrays. Use `ClientTableProjector` for every state table. Preserve the currently
verified card response builders, but move state creation and notification selection into `CardModule`.

Commands `80/81/82/185/186/300/308` remain `PROVISIONAL` unless their client state chains are audited
during this task. A provisional handler must log that evidence is incomplete.

- [ ] **Step 5: Remove migrated methods from handler and run tests**

```bash
./gradlew test \
  --tests 'com.stzb.server.handler.modules.*' \
  --tests 'com.stzb.server.game.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/stzb/server/handler/modules/BuildingModule.kt \
  src/main/kotlin/com/stzb/server/handler/modules/CardModule.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/handler/modules
git commit -m "refactor: isolate building and card state closures"
```

### Task 9: Login, Common, and Battle Modules

**Files:**
- Create: `src/main/kotlin/com/stzb/server/handler/modules/LoginModule.kt`
- Create: `src/main/kotlin/com/stzb/server/handler/modules/CommonModule.kt`
- Create: `src/main/kotlin/com/stzb/server/handler/modules/BattleModule.kt`
- Create: `src/test/kotlin/com/stzb/server/handler/modules/LoginModuleTest.kt`
- Create: `src/test/kotlin/com/stzb/server/handler/modules/CommonModuleTest.kt`
- Create: `src/test/kotlin/com/stzb/server/handler/modules/BattleModuleTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`

**Interfaces:**
- Login module: `99992, 99994, 20003, 98702, 99991, 511, 2`
- Battle module: `6, 10, 11, 6231`
- Common module: remaining explicitly implemented time, land, world scene, ping, rename and UI-state commands.

- [ ] **Step 1: Audit login response consumers before migration**

Add findings with source paths and lines to `docs/client-contract-audit-phase1.md`, covering:

- 99992 server session non-empty condition;
- 20003 host/port table fields;
- 99991 login result slots and `UserInitTable`;
- 5025 to 5026 command asymmetry.

Do not promote a command to `EXACT` without this evidence.

- [ ] **Step 2: Write module regression tests from current response fixtures**

Use `EmbeddedChannel` or direct module invocation. Compare parsed JSON structure rather than raw object
identity. Login test must assert a persisted multi-army player logs back in with all armies and heroes.

- [ ] **Step 3: Implement modules without changing battle internals**

`BattleModule` wraps existing `PlayerBattleService` and `ClientBattleReportStore`; reports remain in memory.
This task only moves the seam and injects dependencies.

- [ ] **Step 4: Reduce `GameServerHandler` to network responsibilities**

After migration it contains only:

- `channelActive`
- `channelInactive`
- keepalive scheduling/cancellation
- session timestamp update
- `dispatcher.dispatch`
- `exceptionCaught`

No command-specific JSON parsing remains.

- [ ] **Step 5: Run all module and existing battle tests**

```bash
./gradlew test \
  --tests 'com.stzb.server.handler.*' \
  --tests 'com.stzb.server.game.battle.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

- [ ] **Step 6: Commit**

```bash
git add docs/client-contract-audit-phase1.md \
  src/main/kotlin/com/stzb/server/handler \
  src/test/kotlin/com/stzb/server/handler
git commit -m "refactor: complete command module extraction"
```

### Task 10: Runtime SQLite Wiring and Strict Policy Verification

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/Main.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/main/kotlin/com/stzb/server/protocol/CommandRegistry.kt`
- Create: `src/test/kotlin/com/stzb/server/ServerCompositionTest.kt`
- Modify: `.gitignore`
- Modify: `docs/ai_handoff_context.md`

**Interfaces:**
- Runtime uses `SQLitePlayerStateRepository`.
- Tests can compose server with `InMemoryPlayerStateRepository`.

- [ ] **Step 1: Write runtime composition test**

Assert:

- default database resolves to `<project>/data/stzb.db`;
- `STZB_DB_PATH` override is honored through a pure path resolver;
- all `EXACT/PROVISIONAL` commands have one module;
- all `FALLBACK/FORBIDDEN` commands have no module;
- unknown command sends no packet and channel stays active.

- [ ] **Step 2: Wire repository and dispatcher in `Main`**

Resolve configuration once:

```kotlin
val dbPath = ServerConfig.resolveDbPath(System.getenv("STZB_DB_PATH"))
val players = SQLitePlayerStateRepository(dbPath)
val registry = CommandRegistry.default()
val dispatcher = CommandDispatcher(registry, modules(players))
```

Pass a handler factory into the Netty initializer. Close the repository during graceful shutdown.

- [ ] **Step 3: Ignore runtime database files**

Add:

```gitignore
data/*.db
data/*.db-shm
data/*.db-wal
```

- [ ] **Step 4: Update handoff documentation**

Document:

- `STZB_DB_PATH`;
- schema version 1;
- reset procedure using a specific database file path;
- command evidence classifications;
- requirement to re-login after login snapshot changes.

- [ ] **Step 5: Run full automated verification**

```bash
./gradlew clean test installDist \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: build succeeds and all old plus new tests pass.

- [ ] **Step 6: Run protocol smoke client**

Start:

```bash
STZB_DB_PATH=/tmp/stzb-plan-smoke.db \
./gradlew run --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

In another terminal:

```bash
python3 test_client.py 59979
```

Expected: handshake, 99992, 20003 and 99991 checks pass. Stop the server, restart with the same database,
run the client again, and verify the player snapshot is restored.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/stzb/server/Main.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/main/kotlin/com/stzb/server/protocol/CommandRegistry.kt \
  src/test/kotlin/com/stzb/server/ServerCompositionTest.kt \
  .gitignore docs/ai_handoff_context.md
git commit -m "feat: run modular server with sqlite state"
```

## Completion Checklist

- [ ] `GameServerHandler` only manages network lifecycle, sessions, keepalive and dispatch.
- [ ] Login, building, card, army, battle and common behavior live in deep modules.
- [ ] Every routed command has exactly one explicit classification.
- [ ] No numeric-range generic empty-array fallback remains.
- [ ] Forbidden and unknown commands do not reply and do not close the connection.
- [ ] Source-proven contracts cite client paths and lines.
- [ ] Login and `90005` use the same client table projector.
- [ ] One player can persist multiple armies across multiple cities.
- [ ] A hero cannot occupy multiple army slots.
- [ ] cmd 30/32 return integer lists; cmd 37/38 return integer scalars.
- [ ] Player state survives repository and server restart.
- [ ] Failed state transactions emit no success packets.
- [ ] Existing battle behavior and in-memory report storage remain unchanged.
- [ ] Full test, install distribution and protocol smoke checks pass.
