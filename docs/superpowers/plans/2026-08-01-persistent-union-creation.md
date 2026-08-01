# 基础持久同盟创建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让玩家通过 `cmd 102` 创建可跨重启恢复的同盟，并能通过 `cmd 100` 打开同盟主页。

**Architecture:** 新增独立全局同盟仓库，保存同盟记录和成员归属；它与世界状态使用同一数据目录，但不耦合城池或土地逻辑。处理器直接接管创建和详情协议，登录快照与资料页从该仓库读取成员归属。

**Tech Stack:** Kotlin 1.9、Jackson、Netty、JUnit 5/Kotlin Test。

## Global Constraints

- 只实现创建、详情读取、创建人作为盟主和重启恢复。
- 同盟名 trim 后唯一；玩家只能拥有一个同盟。
- `cmd 102` 成功必须返回裸整数 ID，失败返回 `0`。
- `cmd 100` 未找到时保持 `[1,[]]`；找到时必须满足客户端 `UnionData.SetUnionInfo` 的无保护类型读取。
- 不改变申请、邀请、审批、退出、解散或地图同盟标记。
- 不提交当前工作区，因为其中存在用户的无关未提交改动。

---

### Task 1: 持久化同盟状态

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/UnionState.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/WorldState.kt`
- Test: `src/test/kotlin/com/stzb/server/game/UnionStateTest.kt`

**Interfaces:**
- Produces `data class PlayerUnion(val unionId: Int, val name: String, val leaderUserId: Int, val leaderRoleName: String, val createdAtSec: Int, val memberUserIds: Set<Int>)`.
- Produces `object UnionStateRepository` with `configure(root: Path)`, `reset()`, `create(state: PlayerState, name: String, nowSec: Int): Int`, `forUser(userId: Int): PlayerUnion?`, and `find(unionId: Int): PlayerUnion?`.
- `WorldStateRepository.configure(root)` invokes `UnionStateRepository.configure(root)`; `reset()` invokes `UnionStateRepository.reset()`.

- [ ] **Step 1: Write failing persistence tests**

```kotlin
@Test
fun `created union survives repository reconfiguration`() {
    val root = createTempDirectory("stzb-union")
    try {
        UnionStateRepository.configure(root)
        val leader = PlayerState(userId = 10001, cityWid = 15061506, roleName = "盟主")

        val unionId = UnionStateRepository.create(leader, "洛阳同盟", nowSec = 1_700_000_000)

        UnionStateRepository.configure(root)
        val restored = requireNotNull(UnionStateRepository.find(unionId))
        assertEquals("洛阳同盟", restored.name)
        assertEquals(leader.userId, restored.leaderUserId)
        assertEquals(setOf(leader.userId), restored.memberUserIds)
        assertEquals(unionId, UnionStateRepository.forUser(leader.userId)?.unionId)
    } finally {
        UnionStateRepository.reset()
        root.toFile().deleteRecursively()
    }
}

@Test
fun `union name is unique and a member create request is idempotent`() {
    val first = PlayerState(userId = 10001, cityWid = 1, roleName = "甲")
    val second = PlayerState(userId = 10002, cityWid = 2, roleName = "乙")

    val unionId = UnionStateRepository.create(first, "唯一名称", nowSec = 1)
    assertEquals(unionId, UnionStateRepository.create(first, "另一名称", nowSec = 2))
    assertEquals(0, UnionStateRepository.create(second, "唯一名称", nowSec = 3))
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test --tests 'com.stzb.server.game.UnionStateTest'
```

Expected: compilation failure because `UnionStateRepository` does not exist.

- [ ] **Step 3: Implement the repository and service**

```kotlin
data class UnionStateSnapshot(
    val nextUnionId: Int = 1000,
    val unions: List<PlayerUnion> = emptyList(),
)

class UnionService(private val repository: UnionRepository) {
    fun create(state: PlayerState, name: String, nowSec: Int): Int
    fun forUser(userId: Int): PlayerUnion?
    fun find(unionId: Int): PlayerUnion?
}
```

Use the same temporary-file, `FileChannel.force(true)`, and atomic move strategy as `FileWorldRepository`. Keep a lock-protected `unionId -> PlayerUnion` index and `userId -> unionId` index. Clamp a valid name to 16 characters and reject blank names.

- [ ] **Step 4: Configure it with world test state**

```kotlin
@Synchronized
fun configure(root: Path) {
    service = WorldService(FileWorldRepository(root), PlayerStateRepository::save)
    UnionStateRepository.configure(root)
}

@Synchronized
fun reset() {
    service = defaultService()
    UnionStateRepository.reset()
}
```

- [ ] **Step 5: Run the persistence tests and verify they pass**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test --tests 'com.stzb.server.game.UnionStateTest'
```

Expected: PASS.

### Task 2: 创建、详情与客户端状态同步

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Modify: `src/main/kotlin/com/stzb/server/protocol/NetworkResponsePolicy.kt`
- Test: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- `Cmd.UNION_INFO = 100` and `Cmd.UNION_CREATE = 102`.
- `GameResponses.unionInfo(union: PlayerUnion): String` returns `[0, unionInfo]`.
- `GameResponses.userUnionUpdateNotify(userId: Int, union: PlayerUnion): String` returns a sparse `Tb_user` `90005` update for fields `10` and `11`.

- [ ] **Step 1: Write the failing protocol regression test**

```kotlin
@Test
fun `creating a union makes its detail immediately available`() {
    val channel = newChannel()
    val playerId = platformLogin(channel, "union-creator")

    channel.writeInbound(upPacket(cmdId = Cmd.UNION_CREATE, json = """["洛阳同盟"]""", userId = playerId))
    val created = assertIs<DownPacket>(channel.readOutbound<Any>())
    val unionId = mapper.readTree(created.body).asInt()
    assertTrue(unionId > 0)

    channel.writeInbound(upPacket(cmdId = Cmd.UNION_INFO, json = "[$unionId,0]", userId = playerId))
    val detail = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)
    assertEquals(0, detail[0].asInt())
    assertEquals("洛阳同盟", detail[1][4]["name"].asText())
    assertEquals(playerId, detail[1][4]["leader_id"].asInt())
}
```

- [ ] **Step 2: Run the protocol test and verify the old behavior fails**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test --tests 'com.stzb.server.handler.GameServerHandlerProtocolTest.creating a union makes its detail immediately available'
```

Expected: failure because the existing `cmd 100` fallback returns `[1,[]]`.

- [ ] **Step 3: Implement exact command handlers**

```kotlin
private fun sendCreateUnion(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
    val state = playerState(session, session?.userId ?: msg.userId, GameServerConfig.CITY_WID)
    val name = runCatching { mapper.readTree(msg.body).get(0).asText() }.getOrDefault("")
    val unionId = UnionStateRepository.create(state, name, (System.currentTimeMillis() / 1_000L).toInt())
    ctx.writeAndFlush(DownPacket.json(Cmd.UNION_CREATE, unionId.toString(), dataType = DownType.PLAIN))
    UnionStateRepository.find(unionId)?.let { union ->
        ctx.writeAndFlush(DownPacket.json(Cmd.SYS_NOTIFY_DB_UPDATE, GameResponses.userUnionUpdateNotify(state.userId, union), dataType = DownType.PLAIN))
    }
}

private fun sendUnionInfo(ctx: ChannelHandlerContext, msg: UpPacket) {
    val unionId = runCatching { mapper.readTree(msg.body).get(0).asInt() }.getOrDefault(0)
    val body = UnionStateRepository.find(unionId)?.let(GameResponses::unionInfo) ?: "[1,[]]"
    ctx.writeAndFlush(DownPacket.json(Cmd.UNION_INFO, body, dataType = DownType.PLAIN))
}
```

The detail dictionary must include all non-null values read by `UnionData.SetUnionInfo`, including identity, leader, member count, time, region, technology, nation, and activity fields. Use zeroes, empty strings, and empty lists for unsupported features.

- [ ] **Step 4: Remove stale `100` and `102` fallback behavior**

```kotlin
// NetworkResponsePolicy retains only a safe fallback for calls that reach it
// without a GameServerHandler session.
cmdId == Cmd.UNION_INFO -> GenericGameResponses.unionInfoUnavailable()
cmdId == Cmd.UNION_CREATE -> "0"
```

- [ ] **Step 5: Run the protocol regression test and verify it passes**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test --tests 'com.stzb.server.handler.GameServerHandlerProtocolTest.creating a union makes its detail immediately available'
```

Expected: PASS.

### Task 3: 登录快照与资料页归属展示

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/ProfileResponses.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Test: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/ProfileResponsesTest.kt`

**Interfaces:**
- `tbUser(state: PlayerState, union: PlayerUnion?)` writes `union_id` at index `10` and `union_name` at index `11`.
- `ProfileResponses.homepageInfo(userId: Int, roleName: String, union: PlayerUnion?)` writes union ID/name into its 14-element union list.
- `cmd 3686` is handled with the authenticated session rather than the global fallback.

- [ ] **Step 1: Write failing snapshot and profile tests**

```kotlin
assertEquals(unionId, tables.getValue("Tb_user")[1][0][10].asInt())
assertEquals("洛阳同盟", tables.getValue("Tb_user")[1][0][11].asText())

val profile = mapper.readTree(ProfileResponses.homepageInfo(playerId, "盟主", union))
assertEquals(unionId, profile[1]["union"][2].asInt())
assertEquals("洛阳同盟", profile[1]["union"][3].asText())
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test --tests 'com.stzb.server.game.UserInitTableBuilderTest' --tests 'com.stzb.server.game.ProfileResponsesTest'
```

Expected: failure because the snapshot currently has default union fields and the profile response has hard-coded zero values.

- [ ] **Step 3: Implement snapshot and profile reads**

```kotlin
val union = UnionStateRepository.forUser(state.userId)
root.add(table("Tb_user", tbUser(state, union)))
```

Use the same `PlayerUnion?` in `ProfileResponses` and add a dedicated `cmd 3686` handler that derives `userId`, `roleName`, and union state from the authenticated session.

- [ ] **Step 4: Run the snapshot and profile tests and verify they pass**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test --tests 'com.stzb.server.game.UserInitTableBuilderTest' --tests 'com.stzb.server.game.ProfileResponsesTest'
```

Expected: PASS.

### Task 4: End-to-end persistence regression

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/UnionStateTest.kt`

**Interfaces:**
- Reuses `UnionStateRepository.configure(root)` and the existing temporary repository setup.

- [ ] **Step 1: Write the failing reconfiguration protocol test**

```kotlin
@Test
fun `created union is returned after state repository reconfiguration`() {
    val state = PlayerStateRepository.getOrCreate("union-owner", 15061506, "盟主")
    val unionId = UnionStateRepository.create(state, "重启同盟", nowSec = 1)

    UnionStateRepository.configure(repositoryRoot)

    assertEquals("重启同盟", UnionStateRepository.find(unionId)?.name)
    assertEquals(unionId, UnionStateRepository.forUser(state.userId)?.unionId)
}
```

- [ ] **Step 2: Run the test and verify it passes**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test --tests 'com.stzb.server.game.UnionStateTest'
```

Expected: PASS.

- [ ] **Step 3: Run production compilation and diff validation**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process compileKotlin
git diff --check
```

Expected: `BUILD SUCCESSFUL` and no whitespace diagnostics.

## Plan Self-Review

- Spec coverage: Task 1 implements global persistence and uniqueness; Task 2 implements `102`, `100`, and `90005`; Task 3 covers login and profile visibility; Task 4 covers restart recovery and compilation.
- Placeholder scan: no implementation steps defer work or omit concrete behavior.
- Type consistency: `PlayerUnion`, `UnionStateRepository`, `GameResponses.unionInfo`, and `GameResponses.userUnionUpdateNotify` are defined before consuming tasks.
