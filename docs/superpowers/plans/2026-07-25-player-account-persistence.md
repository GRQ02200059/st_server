# 玩家账号数据持久化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用本地 JSON 文件保存并恢复账号、玩家状态、武将、资源、建筑、队伍和进行中的行军。

**Architecture:** `PlayerState` 保持运行时模型，新增可序列化快照模型；`FilePlayerRepository` 负责原子 JSON 文件读写，`PlayerStateRepository` 作为兼容入口按账号键管理内存缓存和持久化。Netty 处理器在连接登录时绑定账号，在每次状态变更后显式保存。

**Tech Stack:** Kotlin/JVM 17、Jackson Kotlin、`java.nio.file`、JUnit 5/kotlin.test。

## Global Constraints

- 存储目录默认使用当前工作目录下的 `data`，可由环境变量 `STZB_DATA_DIR` 覆盖。
- 不引入数据库和新的运行时依赖。
- 不回退工作区中已有的战斗相关修改。
- 所有新增行为先写失败测试，再写生产代码。
- JSON 写入使用临时文件、`FileChannel.force(true)` 和原子移动。
- 损坏存档必须先备份为 `.corrupt.<timestamp>`，不得直接覆盖。

### Task 1: 定义持久化快照与可恢复 PlayerState

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Create: `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt`

**Interfaces:**
- Produce `PlayerStateSnapshot`、`PlayerHeroSnapshot`、`PlayerMarchSnapshot`。
- Produce `PlayerState.toSnapshot()` 和 `PlayerState.fromSnapshot(snapshot)`。
- `PlayerState` 新增稳定 `accountKey`，保留现有 `userId` 调用兼容性。

- [ ] **Step 1: Write the failing test**

测试创建玩家、招募武将、修改兵力/体力/等级、保存队伍、升级建筑和创建行军，然后通过快照恢复并断言全部字段一致。

```kotlin
@Test
fun `snapshot restores account heroes resources buildings team and march`() {
    val state = PlayerState(userId = 101, accountKey = "acct-a", cityWid = 100101, roleName = "测试主公")
    val hero = state.addHero(100017, nowSec = 1_700_000_000)
    hero.troops = 777
    hero.stamina = 555_000
    hero.level = 8
    state.saveTeam(listOf(hero.heroUid))
    state.upgradeBuild(10, 4)
    state.resources.food = 123456
    state.startMarch(targetWid = 100102, nowSec = 1_700_000_010)

    val restored = PlayerState.fromSnapshot(state.toSnapshot())

    assertEquals("acct-a", restored.accountKey)
    assertEquals(101, restored.userId)
    assertEquals(777, restored.hero(hero.heroUid)?.troops)
    assertEquals(555_000, restored.hero(hero.heroUid)?.stamina)
    assertEquals(8, restored.hero(hero.heroUid)?.level)
    assertEquals(listOf(hero.heroUid, 0, 0), restored.teamHeroes())
    assertEquals(4, restored.buildLevel(10))
    assertEquals(123456, restored.resources.food)
    assertEquals(100102, restored.activeMarch()?.targetWid)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.stzb.server.game.PlayerStatePersistenceTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process`

Expected: FAIL because the snapshot types and restore API do not exist.

- [ ] **Step 3: Implement the minimal snapshot API**

Add snapshot data classes and make `PlayerState` reconstruct private collections, hero sequence, resources, building levels, team slots and march. Ensure the next `addHero` UID is greater than every restored hero sequence.

- [ ] **Step 4: Run test to verify it passes**

Run the same focused Gradle command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/PlayerState.kt src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt
git commit -m "feat: add player state snapshots"
```

### Task 2: Implement JSON file repository with atomic writes

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/FilePlayerRepository.kt`
- Create: `src/test/kotlin/com/stzb/server/game/FilePlayerRepositoryTest.kt`

**Interfaces:**
- `interface PlayerRepository`
- `class FilePlayerRepository(root: Path, mapper: ObjectMapper = jacksonObjectMapper())`
- `findByAccount(accountKey: String): PlayerState?`
- `getOrCreate(accountKey: String, cityWid: Int, roleName: String): PlayerState`
- `save(state: PlayerState)`

- [ ] **Step 1: Write the failing tests**

Cover round-trip persistence, safe filename mapping, and corrupt-file backup.

```kotlin
@Test
fun `file repository round trips player state`() {
    val root = tempDir.resolve("data")
    val first = FilePlayerRepository(root)
    val state = first.getOrCreate("acct/a", 100001, "主公")
    val hero = state.addHero(100017, 1_700_000_000)
    hero.troops = 321
    first.save(state)

    val restored = FilePlayerRepository(root).findByAccount("acct/a")!!

    assertEquals(state.userId, restored.userId)
    assertEquals(321, restored.hero(hero.heroUid)?.troops)
}

@Test
fun `corrupt file is backed up and replaced by a new account`() {
    val root = tempDir.resolve("data")
    val repository = FilePlayerRepository(root)
    val path = repository.accountPathForTest("acct-b")
    Files.createDirectories(path.parent)
    Files.writeString(path, "{broken")

    val state = repository.getOrCreate("acct-b", 100001, "主公")

    assertTrue(Files.exists(path.resolveSibling("${path.fileName}.corrupt")))
    assertEquals("acct-b", state.accountKey)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.stzb.server.game.FilePlayerRepositoryTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process`

Expected: FAIL because the repository does not exist.

- [ ] **Step 3: Implement repository**

Serialize a top-level `PlayerStateSnapshot` with Jackson. Use sanitized account names or SHA-256 names, create `accounts/`, write `<name>.tmp-<pid>`, call `force(true)`, then `ATOMIC_MOVE` with `REPLACE_EXISTING`, falling back to a normal replace if the filesystem does not support atomic moves. On parse failure, move the original to a timestamped `.corrupt.<epochMillis>` file and return null.

- [ ] **Step 4: Run tests to verify they pass**

Run the focused repository tests. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/FilePlayerRepository.kt src/test/kotlin/com/stzb/server/game/FilePlayerRepositoryTest.kt
git commit -m "feat: persist player states as atomic json files"
```

### Task 3: Replace the in-memory player registry with persistent account registry

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/FilePlayerRepository.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerStateRepositoryTest.kt`

**Interfaces:**
- `PlayerStateRepository.configure(repository: PlayerRepository)`
- `PlayerStateRepository.getOrCreate(accountKey: String, cityWid: Int, roleName: String): PlayerState`
- Existing `getOrCreate(userId, cityWid, roleName)` remains as a test/backward-compatible overload.
- `PlayerStateRepository.save(state: PlayerState)`.

- [ ] **Step 1: Write the failing test**

Construct two repository instances over the same temporary directory and assert the account gets the same user ID and hero UID after the second instance loads.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.stzb.server.game.PlayerStateRepositoryTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process`

Expected: FAIL because the current registry is process-local and has no account-key API.

- [ ] **Step 3: Implement the registry**

Use a configured repository rooted at `Path.of(System.getenv("STZB_DATA_DIR") ?: "data")`. Cache loaded states by account key. Allocate new user IDs from a persisted global sequence file or a collision-free timestamp/atomic sequence initialized from existing account snapshots. The compatibility `userId` overload maps to `legacy-user-<userId>`.

- [ ] **Step 4: Run focused and existing state tests**

Run:

```bash
./gradlew test --tests com.stzb.server.game.PlayerStateRepositoryTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/PlayerState.kt src/main/kotlin/com/stzb/server/game/FilePlayerRepository.kt src/test/kotlin/com/stzb/server/game/PlayerStateRepositoryTest.kt
git commit -m "feat: bind player state to persistent accounts"
```

### Task 4: Save state at all existing mutation points

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameStateStore.kt`
- Modify: focused service tests under `src/test/kotlin/com/stzb/server/game/`

**Interfaces:**
- Every handler mutation obtains state by account key and calls `PlayerStateRepository.save(state)` after successful mutation.
- `UserInitTableBuilder.build` reads the already-loaded account state and never creates a different user record.

- [ ] **Step 1: Write failing regression tests**

Add tests that mutate a state through recruitment, team assignment, conscription and building upgrade, then construct a new file repository and assert the values are present.

- [ ] **Step 2: Run tests to verify they fail**

Run the affected focused tests. Expected: state changes disappear because handlers/services currently only mutate in-memory objects.

- [ ] **Step 3: Add save calls**

Save after `addHero`, `upgradeBuild`, team operations, conscription, `launchPveBattle`, settlement, and role-name changes. Keep response ordering unchanged: send command response, then DB update, then persist.

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew test --tests com.stzb.server.game.GameResponsesTest --tests com.stzb.server.game.PlayerConscriptServiceTest --tests com.stzb.server.game.PlayerStateRepositoryTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt src/main/kotlin/com/stzb/server/game/GameStateStore.kt src/test/kotlin/com/stzb/server/game
git commit -m "feat: persist player mutations"
```

### Task 5: Bind login and reconnect to a stable account key

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/session/Session.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Create: `src/test/kotlin/com/stzb/server/session/SessionTest.kt`

**Interfaces:**
- `Session.create(accountKey: String = DEFAULT_ACCOUNT_KEY): Session`
- `Session.accountKey` is stable for the connection.
- `Session` loads the persisted player identity before `98888`.

- [ ] **Step 1: Write the failing tests**

Test that two sessions created with the same account key share the same persisted `userId`, while different account keys do not. Test login snapshot uses the restored role name and heroes.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.stzb.server.session.SessionTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process`

Expected: FAIL because `Session.create` currently always increments a new ID and has no account key.

- [ ] **Step 3: Implement stable account binding**

Extract the account key from the 99992/99991 request body when present; otherwise use `local-dev-account`. Resolve the account state before writing 98888. Keep SID random per connection, but keep user ID and player state stable. Ensure `sendLoginSuccess` uses the session’s persisted user ID and role.

- [ ] **Step 4: Run login/session tests**

Run the focused session tests and `GameResponsesTest`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/session/Session.kt src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt src/main/kotlin/com/stzb/server/game/GameResponses.kt src/test/kotlin/com/stzb/server/session/SessionTest.kt
git commit -m "feat: restore stable account identity on login"
```

### Task 6: Full verification and deployment documentation

**Files:**
- Modify: `README.md`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`

- [ ] **Step 1: Add end-to-end persistence assertions**

Verify that a restored account generates a `99991` snapshot containing the same hero UID, troop count, resource value, building level and team slots.

- [ ] **Step 2: Run all tests**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all tests pass with zero failures.

- [ ] **Step 3: Build distribution**

Run:

```bash
./gradlew installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: exit code 0 and a runnable distribution under `build/install/stzb-server`.

- [ ] **Step 4: Document runtime data directory**

Document `STZB_DATA_DIR`, backup behavior, and the fact that deleting `data/accounts` resets the local development account.

- [ ] **Step 5: Commit**

```bash
git add README.md src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt
git commit -m "docs: document persistent player data"
```
