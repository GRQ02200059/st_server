# Transport and Login Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the 9.2.2 handshake and login sequence server-authoritative, token-gated, reconnect-safe, and registered as `EXACT` command contracts.

**Architecture:** `Session` owns the connection-only wire identity, frame monotonicity, and progression from `CONNECTED` through `PLATFORM_AUTHENTICATED` to `GAME_LOGGED_IN`. `ServerSessionRegistry` remains the short-lived account-token authority. `GameServerHandler` validates each inbound frame before dispatch, separates platform binding from game-online activation, and only evicts an existing online channel after serializing a successful `99991` snapshot.

**Tech Stack:** Kotlin 1.9.23, JDK 17, Netty 4.1.109, Jackson 2.17, `kotlin.test`, Gradle.

## Global Constraints

- Modify only the Kotlin server and server-side tests; do not change client DLLs, assets, injection, or runtime client configuration.
- Work in an isolated git worktree; the current checkout contains unrelated uncommitted gameplay changes.
- Every inbound frame must validate against the current channel's checksum, 32-byte SID, wire user id, and nondecreasing command index before handler dispatch.
- The `99991` token is only textual request element `2`; no passport or other-field identity fallback is allowed.
- `99992` must not register or evict gameplay-online channels. Only a serialized successful `99991` activates a channel.
- `20003` remains `[status,payload]`; `98702` is exactly `[classicServerTable,youthServerTable]`.
- Keep the `98888` and `90008` packets as special binary/complex packets, not normal JSON `DownPacket`s.
- Mark only `98888`, `90003`, `90005`, `90007`, `90008`, `90009`, `99992`, `20003`, `98702`, `99994`, and `99991` as `EXACT`; leave unrelated commands unchanged.
- Run Kotlin compilation and tests with `--no-daemon -Pkotlin.incremental=false -Dkotlin.compiler.execution.strategy=in-process`.
- Stage and commit only files named in each task.

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/stzb/server/session/Session.kt` | Wire identity validation, command-index tracking, authentication identity, and connection phase transitions. |
| `src/main/kotlin/com/stzb/server/auth/ServerSessionRegistry.kt` | Opaque-token expiry handling and strict required-token lookup. |
| `src/main/kotlin/com/stzb/server/game/GameResponses.kt` | Pre-server pass/fail tuple and distinct normal versus classic/youth server-list envelopes. |
| `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt` | Inbound transport gate, phase-aware `99992`/`99994`/`99991`, delayed online activation, and separate `98702` routing. |
| `src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt` | Audited transport/login `EXACT` registrations and supporting source/test evidence. |
| `src/test/kotlin/com/stzb/server/session/SessionTest.kt` | Session phase and wire-frame validity regression tests. |
| `src/test/kotlin/com/stzb/server/auth/ServerSessionRegistryTest.kt` | Required-token and expiry tests. |
| `src/test/kotlin/com/stzb/server/protocol/SysPacketsTest.kt` | Raw `98888` packet field-order regression. |
| `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt` | `99994`, `20003`, and `98702` body-shape tests. |
| `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt` | End-to-end Netty transport, authentication, login, eviction, and snapshot tests. |
| `src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt` | Exact status/domain/evidence regressions. |

---

### Task 1: Isolate the Worktree and Establish a Transport Baseline

**Files:**
- Create: an ignored worktree outside the dirty checkout.
- Verify: no source edits in the current checkout.

**Interfaces:**
- Consumes: commit `19008dd8` containing the approved design.
- Produces: an isolated branch that can compile and run focused tests without unrelated gameplay edits.

- [ ] **Step 1: Detect worktree state and create an isolated branch**

Run:

```bash
git -C /Users/bytedance/stzb/server rev-parse --git-dir
git -C /Users/bytedance/stzb/server rev-parse --git-common-dir
git -C /Users/bytedance/stzb/server status --short
git -C /Users/bytedance/stzb/server worktree add \
  /private/tmp/stzb-transport-login \
  -b feat/transport-login-contracts \
  19008dd8
```

Expected: `/private/tmp/stzb-transport-login` is a clean worktree at `19008dd8`; the original checkout remains dirty but untouched.

- [ ] **Step 2: Compile the isolated baseline**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  compileKotlin
```

Expected: `BUILD SUCCESSFUL`. If this fails, record the exact baseline error and do not attribute it to this feature.

- [ ] **Step 3: Run the focused baseline tests**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.session.SessionTest \
  --tests com.stzb.server.auth.ServerSessionRegistryTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest \
  --tests com.stzb.server.protocol.CommandContractRegistryTest
```

Expected: establish the clean-worktree baseline. Capture any pre-existing failures before writing a production change.

### Task 2: Add Wire-Session Phases and Frame Validation

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/session/Session.kt`
- Modify: `src/test/kotlin/com/stzb/server/session/SessionTest.kt`
- Create: `src/test/kotlin/com/stzb/server/protocol/SysPacketsTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class SessionPhase { CONNECTED, PLATFORM_AUTHENTICATED, GAME_LOGGED_IN }

enum class FrameValidationFailure { CHECK_CODE, SID, WIRE_USER_ID, STALE_COMMAND_INDEX }

sealed interface FrameValidation {
    data object Accepted : FrameValidation
    data class Rejected(val reason: FrameValidationFailure) : FrameValidation
}

fun Session.validateIncoming(packet: UpPacket): FrameValidation
fun Session.authenticate(accountKey: String, playerId: Int): Boolean
fun Session.activateGameLogin(): Boolean
```

- [ ] **Step 1: Write failing session and binary-packet tests**

Add to `SessionTest.kt`:

```kotlin
@Test
fun `session accepts a valid wire frame and rejects invalid identity fields`() {
    val session = Session.create()
    fun packet(
        userId: Int = session.wireUserId,
        sid: ByteArray = session.sid.copyOf(),
        cmdIndex: Int = 1,
        checkCode: Int = (cmdIndex * 13) xor Cmd.SYS_CHECK_SID xor userId,
    ) = UpPacket(1001, userId, sid, Cmd.SYS_CHECK_SID, cmdIndex, checkCode, UpFlag.PLAIN, ByteArray(0))

    assertEquals(FrameValidation.Accepted, session.validateIncoming(packet()))
    assertEquals(
        FrameValidation.Rejected(FrameValidationFailure.CHECK_CODE),
        session.validateIncoming(packet(cmdIndex = 2, checkCode = 0)),
    )
    assertEquals(
        FrameValidation.Rejected(FrameValidationFailure.SID),
        session.validateIncoming(packet(cmdIndex = 2, sid = ByteArray(32))),
    )
    assertEquals(
        FrameValidation.Rejected(FrameValidationFailure.WIRE_USER_ID),
        session.validateIncoming(packet(cmdIndex = 2, userId = session.wireUserId + 1)),
    )
}

@Test
fun `session allows same-index retransmission but rejects older frames`() {
    val session = Session.create()
    fun packet(index: Int) = UpPacket(
        1001, session.wireUserId, session.sid.copyOf(), Cmd.SYS_HEART_BEAT, index,
        (index * 13) xor Cmd.SYS_HEART_BEAT xor session.wireUserId, UpFlag.PLAIN, ByteArray(0),
    )

    assertEquals(FrameValidation.Accepted, session.validateIncoming(packet(3)))
    assertEquals(FrameValidation.Accepted, session.validateIncoming(packet(3)))
    assertEquals(
        FrameValidation.Rejected(FrameValidationFailure.STALE_COMMAND_INDEX),
        session.validateIncoming(packet(2)),
    )
}

@Test
fun `session cannot replace an authenticated account and only activates after authentication`() {
    val session = Session.create()

    assertFalse(session.activateGameLogin())
    assertTrue(session.authenticate("account-a", 101))
    assertEquals(SessionPhase.PLATFORM_AUTHENTICATED, session.phase)
    assertFalse(session.authenticate("account-b", 202))
    assertTrue(session.activateGameLogin())
    assertEquals(SessionPhase.GAME_LOGGED_IN, session.phase)
}
```

Create `SysPacketsTest.kt`:

```kotlin
@Test
fun `notify sid packet has the exact 52 byte payload layout`() {
    val channel = EmbeddedChannel()
    val sid = ByteArray(32) { it.toByte() }

    SysPackets.writeNotifySid(channel, userId = 123, cmdIndex = 7, sid = sid, param = 9)

    val packet = assertIs<ByteBuf>(channel.readOutbound<Any>())
    assertEquals(52, packet.readInt())
    assertEquals(Cmd.SYS_NOTIFY_SID, packet.readInt())
    assertEquals(0, packet.readInt())
    assertEquals(123, packet.readInt())
    assertEquals(7, packet.readInt())
    assertEquals(sid.toList(), ByteArray(32).also(packet::readBytes).toList())
    assertEquals(9, packet.readInt())
    assertEquals(0, packet.readableBytes())
    ReferenceCountUtil.release(packet)
    channel.finishAndReleaseAll()
}
```

- [ ] **Step 2: Run the new tests and verify RED**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.session.SessionTest \
  --tests com.stzb.server.protocol.SysPacketsTest
```

Expected: the new `Session` API tests fail to compile or fail because phases and frame validation do not exist; the existing binary packet behavior test may already pass.

- [ ] **Step 3: Implement the minimal session state machine**

In `Session.kt`, add the enums above and keep `wireUserId` independent from the durable `playerId`. Add a `phase` initialized to `CONNECTED` and a `lastAcceptedCmdIndex` initialized from the handshake `cmdIndex`.

Implement the transport and phase methods as:

```kotlin
@Volatile
var phase: SessionPhase = SessionPhase.CONNECTED
    private set

@Volatile
private var lastAcceptedCmdIndex: Int = cmdIndex.get()

fun validateIncoming(packet: UpPacket): FrameValidation {
    if (!packet.checkOk) return FrameValidation.Rejected(FrameValidationFailure.CHECK_CODE)
    if (!packet.sid.contentEquals(sid)) return FrameValidation.Rejected(FrameValidationFailure.SID)
    if (packet.userId != wireUserId) return FrameValidation.Rejected(FrameValidationFailure.WIRE_USER_ID)
    if (packet.cmdIndex < lastAcceptedCmdIndex) {
        return FrameValidation.Rejected(FrameValidationFailure.STALE_COMMAND_INDEX)
    }
    lastAcceptedCmdIndex = packet.cmdIndex
    return FrameValidation.Accepted
}

fun authenticate(accountKey: String, playerId: Int): Boolean {
    if (phase == SessionPhase.GAME_LOGGED_IN) return this.accountKey == accountKey
    if (this.accountKey != null && this.accountKey != accountKey) return false
    bind(accountKey, playerId)
    phase = SessionPhase.PLATFORM_AUTHENTICATED
    return true
}

fun activateGameLogin(): Boolean {
    return when (phase) {
        SessionPhase.CONNECTED -> false
        SessionPhase.PLATFORM_AUTHENTICATED -> {
            phase = SessionPhase.GAME_LOGGED_IN
            true
        }
        SessionPhase.GAME_LOGGED_IN -> true
    }
}
```

Keep `Session.create(accountKey)` behavior for existing persistence tests by calling `authenticate(key, state.userId)` and then `activateGameLogin()`.

- [ ] **Step 4: Run session and packet tests and verify GREEN**

Run the Step 2 command again.

Expected: all `SessionTest` and `SysPacketsTest` tests pass.

- [ ] **Step 5: Commit the isolated session change**

Run:

```bash
git add \
  src/main/kotlin/com/stzb/server/session/Session.kt \
  src/test/kotlin/com/stzb/server/session/SessionTest.kt \
  src/test/kotlin/com/stzb/server/protocol/SysPacketsTest.kt
git commit -m "feat: validate wire session frames"
```

### Task 3: Make Server Tokens and Server-List Envelopes Explicit

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/auth/ServerSessionRegistry.kt`
- Modify: `src/test/kotlin/com/stzb/server/auth/ServerSessionRegistryTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

**Interfaces:**
- Produces:

```kotlin
fun ServerSessionRegistry.resolveRequired(token: String?): AccountIdentity?
fun GameResponses.preServerTokenCheck(accepted: Boolean): String
fun GameResponses.classicAndYouthServerList(
    serverId: Int, serverName: String, host: String, port: Int,
    runServerId: Int, cfgDbId: Int, openTime: Long,
): String
```

- [ ] **Step 1: Write failing token and shape tests**

Add to `ServerSessionRegistryTest.kt`:

```kotlin
@Test
fun `required resolution rejects missing blank unknown and expired tokens`() {
    var now = 1_000L
    val registry = ServerSessionRegistry(clockMillis = { now }, ttlMillis = 1)
    val token = registry.issue(AccountIdentity("key", "sdkuid:alice"))

    assertNull(registry.resolveRequired(null))
    assertNull(registry.resolveRequired(" "))
    assertNull(registry.resolveRequired("unknown"))
    assertEquals("sdkuid:alice", registry.resolveRequired(token)?.displayId)
    now += 1
    assertNull(registry.resolveRequired(token))
}
```

Add to `GameResponsesTest.kt`:

```kotlin
@Test
fun `pre server token response distinguishes accepted and rejected tokens`() {
    assertEquals(0, mapper.readTree(GameResponses.preServerTokenCheck(true))[0].asInt())
    assertEquals(1, mapper.readTree(GameResponses.preServerTokenCheck(false))[0].asInt())
}

@Test
fun `normal server list keeps client envelope`() {
    val response = mapper.readTree(GameResponses.serverList(1, "私服", "127.0.0.1", 59979, 1, 5, 1L))

    assertEquals(2, response.size())
    assertEquals(0, response[0].asInt())
    assertTrue(response[1][0].isArray)
    assertTrue(response[1][0][0].isArray)
    assertTrue(response[1][0][1].isArray)
    assertEquals("1", response[1][2].asText())
}

@Test
fun `classic youth response is two server tables not a 20003 envelope`() {
    val normal = mapper.readTree(GameResponses.serverList(1, "私服", "127.0.0.1", 59979, 1, 5, 1L))
    val refreshed = mapper.readTree(
        GameResponses.classicAndYouthServerList(1, "私服", "127.0.0.1", 59979, 1, 5, 1L),
    )

    assertEquals(2, normal.size())
    assertEquals(2, refreshed.size())
    assertTrue(refreshed.all { table -> table.isArray && table[0].isArray && table[1].isArray })
    assertEquals(normal[1][0], refreshed[0])
    assertEquals(normal[1][0], refreshed[1])
}
```

Change the existing `pre server token response allows login flow to continue`
test to call `GameResponses.preServerTokenCheck(true)`.

- [ ] **Step 2: Run the new token and response tests and verify RED**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.auth.ServerSessionRegistryTest \
  --tests com.stzb.server.game.GameResponsesTest
```

Expected: compilation fails for `resolveRequired`, the boolean pre-server function, and the classic/youth response builder.

- [ ] **Step 3: Implement strict lookup and shared server-table projection**

In `ServerSessionRegistry.kt`:

```kotlin
fun resolveRequired(token: String?): AccountIdentity? =
    token?.trim()?.takeIf(String::isNotEmpty)?.let(::resolve)
```

In `GameResponses.kt`, replace the hard-coded `serverListNode` local with the
following private `serverTable(serverId, serverName, host, port, runServerId, cfgDbId, openTime) : ArrayNode`. Use it in both public
response methods:

```kotlin
private fun serverTable(
    serverId: Int,
    serverName: String,
    host: String,
    port: Int,
    runServerId: Int,
    cfgDbId: Int,
    openTime: Long,
): ArrayNode =
    nf.arrayNode().apply {
        add(nf.arrayNode().apply {
            listOf(
                "server_id", "entryid", "name", "host", "port",
                "server_port", "run_server_id", "cfg_db_id", "open_time",
            ).forEach(::add)
        })
        add(nf.arrayNode().apply {
            add(serverId)
            add(serverId)
            add(serverName)
            add(host)
            add(port)
            add(port)
            add(runServerId)
            add(cfgDbId)
            add(openTime)
        })
    }

fun preServerTokenCheck(accepted: Boolean): String =
    mapper.writeValueAsString(nf.arrayNode().add(if (accepted) 0 else 1))

fun classicAndYouthServerList(
    serverId: Int,
    serverName: String,
    host: String,
    port: Int,
    runServerId: Int,
    cfgDbId: Int,
    openTime: Long,
): String =
    mapper.writeValueAsString(
        nf.arrayNode().apply {
            add(serverTable(serverId, serverName, host, port, runServerId, cfgDbId, openTime))
            add(serverTable(serverId, serverName, host, port, runServerId, cfgDbId, openTime))
        },
    )
```

Keep `serverList` exactly `[0,[table,[],runServerId,0,openTime,"CN"]]`; do not change its table columns or JSON value types.

- [ ] **Step 4: Run response and token tests and verify GREEN**

Run the Step 2 command again.

Expected: all targeted tests pass, including the existing server-response tests.

- [ ] **Step 5: Commit the token and envelope change**

Run:

```bash
git add \
  src/main/kotlin/com/stzb/server/auth/ServerSessionRegistry.kt \
  src/test/kotlin/com/stzb/server/auth/ServerSessionRegistryTest.kt \
  src/main/kotlin/com/stzb/server/game/GameResponses.kt \
  src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt
git commit -m "feat: enforce login tokens and server list envelopes"
```

### Task 4: Gate the Handler and Activate Gameplay Only After `99991`

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- Consumes: `Session.validateIncoming`, `Session.authenticate`, `Session.activateGameLogin`, `ServerSessionRegistry.resolveRequired`, and `GameResponses.classicAndYouthServerList`.
- Produces:

```kotlin
private fun validateTransport(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket): Boolean
private fun serverSessionTokenAtIndexTwo(msg: UpPacket): String?
private fun resolveLoginIdentity(session: Session, msg: UpPacket): AccountIdentity?
private fun activateOnlineSession(ctx: ChannelHandlerContext, session: Session, identity: AccountIdentity)
```

- [ ] **Step 1: Convert the handler test fixture to issue valid wire frames**

Replace the old `upPacket(cmdId, json, userId)` helper with:

```kotlin
private fun upPacket(
    channel: EmbeddedChannel,
    cmdId: Int,
    json: String,
    cmdIndex: Int = 1,
    userId: Int? = null,
    sid: ByteArray? = null,
    checkCode: Int? = null,
): UpPacket {
    val session = requireNotNull(channel.attr(GameServerHandler.SESSION).get())
    val effectiveUserId = userId ?: session.wireUserId
    return UpPacket(
        serverId = 1001,
        userId = effectiveUserId,
        sid = sid ?: session.sid.copyOf(),
        cmdId = cmdId,
        cmdIndex = cmdIndex,
        checkCode = checkCode ?: ((cmdIndex * 13) xor cmdId xor effectiveUserId),
        flag = UpFlag.PLAIN,
        body = json.toByteArray(),
    )
}
```

Update every existing handler test invocation to pass its `EmbeddedChannel`; remove uses of a stable player id as the packet header user id.

Add a private platform helper that returns both the stable id and token:

```kotlin
private data class PlatformLogin(val playerId: Int, val token: String)

private fun platformAuthenticate(channel: EmbeddedChannel, sdkUid: String): PlatformLogin {
    channel.writeInbound(
        upPacket(
            channel,
            Cmd.SYS_PLATFORM_LOGIN_CHECK,
            """["{\"sdkuid\":\"$sdkUid\"}",0,"",0]""",
        ),
    )
    val response = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.SYS_PLATFORM_LOGIN_CHECK, response.cmd)
    val body = mapper.readTree(response.body)
    return PlatformLogin(playerId = body[3].asInt(), token = body[2].asText())
}

private fun gameLogin(channel: EmbeddedChannel, token: String): DownPacket =
    channel.writeInbound(upPacket(channel, Cmd.SYS_LOGIN, """["passport",0,"$token"]"""))
        .let { assertIs<DownPacket>(channel.readOutbound<Any>()) }

private fun platformLogin(channel: EmbeddedChannel, sdkUid: String): Int =
    platformAuthenticate(channel, sdkUid).let { login ->
        assertEquals(1, mapper.readTree(gameLogin(channel, login.token).body)[0].asInt())
        login.playerId
    }
```

Keep `platformLogin(channel, sdkUid): Int` only as a convenience wrapper that calls both helpers, so existing gameplay tests remain game-online.

Add failing protocol tests:

```kotlin
@Test
fun `valid reconnect frame returns an empty complex packet`() {
    val channel = newChannel()
    channel.writeInbound(upPacket(channel, Cmd.SYS_CHECK_SID, "[]"))

    val packet = assertIs<ByteBuf>(channel.readOutbound<Any>())
    assertEquals(8, packet.readInt())
    assertEquals(Cmd.SYS_CHECK_SID, packet.readInt())
    assertEquals(0, packet.readInt())
    ReferenceCountUtil.release(packet)
    channel.finishAndReleaseAll()
}

@Test
fun `invalid wire frame receives sid invalid and closes`() {
    val channel = newChannel()
    channel.writeInbound(upPacket(channel, Cmd.SYS_CHECK_SID, "[]", sid = ByteArray(32)))

    assertEquals(Cmd.SYS_SID_INVALID, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    assertFalse(channel.isOpen)
}

@Test
fun `pre server and game login require the index two platform token`() {
    val channel = newChannel()
    val login = platformAuthenticate(channel, "alice")

    channel.writeInbound(upPacket(channel, Cmd.SYS_PRE_SERVER_TOKEN_CHECK, """[0,"ignored","$login.token"]"""))
    assertEquals("[0]", assertIs<DownPacket>(channel.readOutbound<Any>()).body.toString(Charsets.UTF_8))

    channel.writeInbound(upPacket(channel, Cmd.SYS_PRE_SERVER_TOKEN_CHECK, """[0,"$login.token","wrong"]"""))
    assertEquals("[1]", assertIs<DownPacket>(channel.readOutbound<Any>()).body.toString(Charsets.UTF_8))

    channel.writeInbound(upPacket(channel, Cmd.SYS_LOGIN, """["passport","$login.token","wrong"]""", cmdIndex = 2))
    assertEquals("[0]", assertIs<DownPacket>(channel.readOutbound<Any>()).body.toString(Charsets.UTF_8))
    assertEquals(SessionPhase.PLATFORM_AUTHENTICATED, channel.attr(GameServerHandler.SESSION).get()?.phase)
}

@Test
fun `game login rejects a token from another authenticated account`() {
    val alice = newChannel()
    val bob = newChannel()
    val aliceLogin = platformAuthenticate(alice, "alice")
    platformAuthenticate(bob, "bob")

    bob.writeInbound(upPacket(bob, Cmd.SYS_LOGIN, """["passport",0,"${aliceLogin.token}"]"""))
    assertEquals("[0]", assertIs<DownPacket>(bob.readOutbound<Any>()).body.toString(Charsets.UTF_8))
    assertEquals(SessionPhase.PLATFORM_AUTHENTICATED, bob.attr(GameServerHandler.SESSION).get()?.phase)
}

@Test
fun `fresh game channel accepts only the issued platform token`() {
    val issuer = newChannel()
    val gameChannel = newChannel()
    val login = platformAuthenticate(issuer, "alice")

    val response = gameLogin(gameChannel, login.token)

    assertEquals(Cmd.SYS_LOGIN, response.cmd)
    assertEquals(1, mapper.readTree(response.body)[0].asInt())
    assertEquals(SessionPhase.GAME_LOGGED_IN, gameChannel.attr(GameServerHandler.SESSION).get()?.phase)
}

@Test
fun `platform authentication does not evict until a successful game login`() {
    val oldChannel = newChannel()
    val newChannel = newChannel()
    val first = platformAuthenticate(oldChannel, "alice")
    val second = platformAuthenticate(newChannel, "alice")

    assertTrue(oldChannel.isOpen)
    assertTrue(newChannel.isOpen)
    gameLogin(oldChannel, first.token)
    gameLogin(newChannel, second.token)

    assertEquals(Cmd.SYS_SID_INVALID, assertIs<DownPacket>(oldChannel.readOutbound<Any>()).cmd)
    assertFalse(oldChannel.isOpen)
}

@Test
fun `98702 returns separate classic and youth tables`() {
    val channel = newChannel()
    channel.writeInbound(upPacket(channel, Cmd.GET_CLASSIC_AND_YOUTH_SERVER_LIST, """["9.2.2"]"""))

    val response = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)
    assertEquals(2, response.size())
    assertTrue(response.all { it[0].isArray && it[1].isArray })
}
```

- [ ] **Step 2: Run the handler test class and verify RED**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: the new protocol tests fail because current `99994` accepts all bodies, `99991` scans any text/passport, `99992` prematurely evicts, and `98702` uses the normal envelope.

- [ ] **Step 3: Install the single inbound transport gate**

At the start of `channelRead0`, before updating `lastRecvTime` or entering `when`, call:

```kotlin
val session = ctx.channel().attr(SESSION).get()
if (!validateTransport(ctx, session, msg)) return
session?.lastRecvTime = System.currentTimeMillis()
```

Implement:

```kotlin
private fun validateTransport(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket): Boolean {
    val validation = session?.validateIncoming(msg)
        ?: FrameValidation.Rejected(FrameValidationFailure.SID)
    if (validation == FrameValidation.Accepted) return true

    log.warn("invalid transport frame cmd=${msg.cmdId} reason=$validation")
    ctx.writeAndFlush(
        DownPacket.json(Cmd.SYS_SID_INVALID, GameResponses.emptyArray(), dataType = DownType.PLAIN),
    ).addListener { ctx.close() }
    return false
}
```

Keep the valid `90008` branch as `SysPackets.writeComplex(ctx.channel(), Cmd.SYS_CHECK_SID)`; it now executes only after the gate succeeds.

- [ ] **Step 4: Separate platform binding from online activation**

Replace the current `bindAccount` behavior with three operations:

```kotlin
private fun bindPlatformAccount(session: Session, identity: AccountIdentity): PlayerState? {
    if (session.accountKey != null && session.accountKey != identity.accountKey) return null
    val state = PlayerStateRepository.getOrCreate(
        accountKey = identity.accountKey,
        cityWid = GameServerConfig.CITY_WID,
        roleName = GameServerConfig.ROLE_NAME,
    )
    val worldState = WorldStateRepository.registerOrRestorePlayer(state)
    return state.takeIf { session.authenticate(identity.accountKey, worldState.userId) }
}

private fun activateOnlineSession(ctx: ChannelHandlerContext, session: Session, identity: AccountIdentity) {
    check(session.activateGameLogin())
    val previous = onlineSessions.bind(identity.accountKey, ctx.channel())
    if (previous != null && previous !== ctx.channel()) {
        previous.writeAndFlush(
            DownPacket.json(Cmd.SYS_SID_INVALID, GameResponses.emptyArray(), dataType = DownType.PLAIN),
        )
        previous.close()
    }
}
```

`sendPlatformLoginCheck` must call `bindPlatformAccount`, issue the token, and return its tuple without touching `OnlineSessionRegistry`.

`channelInactive` must call `onlineSessions.remove` only when
`session.phase == SessionPhase.GAME_LOGGED_IN`.

- [ ] **Step 5: Enforce index-two tokens**

Implement the one extraction function:

```kotlin
private fun serverSessionTokenAtIndexTwo(msg: UpPacket): String? =
    runCatching { mapper.readTree(msg.body) }.getOrNull()
        ?.takeIf { it.isArray && it.size() > 2 }
        ?.get(2)
        ?.takeIf { it.isTextual }
        ?.asText()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
```

Use it in both handlers:

```kotlin
private fun sendPreServerTokenCheck(ctx: ChannelHandlerContext, msg: UpPacket) {
    val accepted = serverSessions.resolveRequired(serverSessionTokenAtIndexTwo(msg)) != null
    ctx.writeAndFlush(
        DownPacket.json(
            Cmd.SYS_PRE_SERVER_TOKEN_CHECK,
            GameResponses.preServerTokenCheck(accepted),
            dataType = DownType.PLAIN,
        ),
    )
}
```

Implement the identity comparison as:

```kotlin
private fun resolveLoginIdentity(session: Session, msg: UpPacket): AccountIdentity? {
    val identity = serverSessions.resolveRequired(serverSessionTokenAtIndexTwo(msg)) ?: return null
    return identity.takeIf { session.accountKey == null || session.accountKey == it.accountKey }
}
```

For `99991`, resolve only this token. If a phase-bound session has an
`accountKey`, require equality with the resolved token identity. Do not call
`AccountIdentityResolver.fromGameLoginRequest`. A fresh `CONNECTED` game
channel calls `bindPlatformAccount(session, tokenIdentity)`; a
`PLATFORM_AUTHENTICATED` channel uses its already-bound account only after
the equality check. Build `GameResponses.loginSuccess` inside `runCatching`;
on any failure write `[0]` and return. Only after the JSON string is available
call `activateOnlineSession`, then send the `99991` packet.

- [ ] **Step 6: Route `98702` independently**

Split the combined branch:

```kotlin
Cmd.GET_ALL_SERVER_INFO_NEW -> sendServerList(ctx, Cmd.GET_ALL_SERVER_INFO_NEW)
Cmd.GET_CLASSIC_AND_YOUTH_SERVER_LIST -> sendClassicAndYouthServerList(ctx)
```

Use the same endpoint values in both methods; `sendClassicAndYouthServerList`
calls:

```kotlin
GameResponses.classicAndYouthServerList(
    serverId = GameServerConfig.SERVER_ID,
    serverName = GameServerConfig.SERVER_NAME,
    host = GameServerConfig.advertisedHost(),
    port = localPort,
    runServerId = GameServerConfig.RUN_SERVER_ID,
    cfgDbId = GameServerConfig.CFG_DB_ID,
    openTime = GameServerConfig.OPEN_TIME_SEC,
)
```

- [ ] **Step 7: Run the complete handler test class and verify GREEN**

Run the Step 2 command again.

Expected: all handler tests pass with only valid wire header values, valid token index-two login, late duplicate-login eviction, and distinct `98702` shape.

- [ ] **Step 8: Commit the handler behavior**

Run:

```bash
git add \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git commit -m "feat: enforce transport login session flow"
```

### Task 5: Promote the Audited Commands to Exact Contracts

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt`
- Modify: `src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/protocol/CommandCoverageReportTest.kt`

**Interfaces:**
- Produces `EXACT` contracts for:

```text
98888, 90003, 90005, 90007, 90008, 90009,
99992, 20003, 98702, 99994, 99991
```

- [ ] **Step 1: Write failing contract tests**

Add to `CommandContractRegistryTest.kt`:

```kotlin
@Test
fun `audited transport login commands are exact with evidence`() {
    val registry = CommandContractCatalog.registry
    val ids = setOf(98888, 90003, 90005, 90007, 90008, 90009, 99992, 20003, 98702, 99994, 99991)

    ids.map { id -> requireNotNull(registry.contract(id)) }.forEach { contract ->
        assertEquals(CommandStatus.EXACT, contract.status, "cmd=${contract.id}")
        assertTrue(contract.domain in setOf(CommandDomain.TRANSPORT, CommandDomain.LOGIN))
        assertTrue(contract.responseSequence.isNotEmpty())
        assertTrue(contract.stateProjection.isNotEmpty())
        assertTrue(contract.evidence.any { it.kind == "SOURCE" })
        assertTrue(contract.evidence.any { it.kind == "SERVER_TEST" })
    }
}
```

Change the existing provisional test to assert that these audited ids are no
longer `PROVISIONAL`, while leaving `5025`, `5026`, `710`, and gameplay
requests provisional.

Add to `CommandCoverageReportTest.kt`:

```kotlin
assertTrue(report.contains("| 99991 |"))
assertTrue(report.contains("EXACT"))
```

- [ ] **Step 2: Run contract tests and verify RED**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.protocol.CommandContractRegistryTest \
  --tests com.stzb.server.protocol.CommandCoverageReportTest
```

Expected: audited command assertions fail because every transport/login
command is still `PROVISIONAL`.

- [ ] **Step 3: Replace provisional registrations with precise contracts**

In `CommandContractCatalog.kt`, add a private builder:

```kotlin
private fun exact(
    id: Int,
    direction: CommandDirection,
    domain: CommandDomain,
    requestShape: String?,
    responses: List<ResponseStep>,
    projection: List<String>,
    owner: String = "GameServerHandler",
    source: String,
    test: String,
) = CommandContract(
    id = id,
    names = emptyList(),
    direction = direction,
    domain = domain,
    status = CommandStatus.EXACT,
    owner = owner,
    requestShape = requestShape,
    responseSequence = responses,
    stateProjection = projection,
    evidence = listOf(ContractEvidence("SOURCE", source), ContractEvidence("SERVER_TEST", test)),
)
```

Add the following function and invoke
`exactTransportLoginContracts().forEach { byId[it.id] = it }` in
`mergedOverrides()` after rejected contracts and before provisional contracts:

```kotlin
private fun exactTransportLoginContracts(): List<CommandContract> = listOf(
    exact(
        98888, CommandDirection.SERVER_PUSH, CommandDomain.TRANSPORT, null,
        listOf(ResponseStep(98888, "binary SID packet")),
        listOf("Session wireUserId, cmdIndex and sid"), owner = "SysPackets",
        source = "Game.Network/Tenth.Network/DotnetCmdMgr.cs:239-274",
        test = "SysPacketsTest.notify sid packet has the exact 52 byte payload layout",
    ),
    exact(
        90003, CommandDirection.CLIENT_REQUEST, CommandDomain.TRANSPORT, "empty heartbeat body",
        listOf(ResponseStep(90003, "no direct packet")),
        listOf("Session.lastRecvTime"),
        source = "Game.Network/Tenth.Network/DotnetCmdMgr.cs:232-236",
        test = "GameServerHandlerProtocolTest.valid wire heartbeat remains open",
    ),
    exact(
        90005, CommandDirection.SERVER_PUSH, CommandDomain.TRANSPORT, null,
        listOf(ResponseStep(90005, "raw database notification")),
        listOf("domain-owned durable table projection"),
        source = "Game/Tenth/GlobalNotify.cs:142-148 and Game.Data/Tenth.Data/DbNotify.cs:535-575",
        test = "GameServerHandlerProtocolTest.hero advance consumes same name material and notifies advance count",
    ),
    exact(
        90007, CommandDirection.SERVER_PUSH, CommandDomain.TRANSPORT, null,
        listOf(ResponseStep(90007, "session invalidation notification")),
        listOf("client channel closes or reconnects"),
        source = "Game.Network/Tenth.Network/DotnetCmdMgr.cs:249-257",
        test = "GameServerHandlerProtocolTest.platform authentication does not evict until a successful game login",
    ),
    exact(
        90008, CommandDirection.DUPLEX, CommandDomain.TRANSPORT, "wire frame with current SID",
        listOf(ResponseStep(90008, "complex acknowledgement")),
        listOf("Session.lastAcceptedCmdIndex"),
        source = "Game.Network/Tenth.Network/DotnetCmdMgr.cs:406-409 and 239-274",
        test = "GameServerHandlerProtocolTest.valid reconnect frame returns an empty complex packet",
    ),
    exact(
        90009, CommandDirection.CLIENT_REQUEST, CommandDomain.TRANSPORT, "down-packet hash acknowledgement",
        listOf(ResponseStep(90009, "no direct packet")),
        listOf("acknowledgement consumed"),
        source = "Game.Network/Tenth.Network/DotnetCmdMgr.cs:295-297",
        test = "GameServerHandlerProtocolTest.valid wire acknowledgement remains open",
    ),
    exact(
        99992, CommandDirection.DUPLEX, CommandDomain.LOGIN, "[sauthJson,clientPackage,country,isMiniGame]",
        listOf(ResponseStep(99992, "[1,null,serverSession,userId] or failure tuple")),
        listOf("Session PLATFORM_AUTHENTICATED identity and ServerSessionRegistry token"),
        source = "Game.ConfigAndSdk/Tenth.Sdk/SdkManager.cs:535-615",
        test = "GameServerHandlerProtocolTest.different platform identities receive different persistent player ids",
    ),
    exact(
        20003, CommandDirection.DUPLEX, CommandDomain.LOGIN, "client server-list request",
        listOf(ResponseStep(20003, "[status,payload] server-list envelope")),
        listOf("read-only advertised private-server endpoint"),
        source = "Game.Data.GamePlay/Tenth.Data/LoginData.cs:1019-1024",
        test = "GameResponsesTest.normal server list keeps client envelope",
    ),
    exact(
        98702, CommandDirection.DUPLEX, CommandDomain.LOGIN, "[clientVersion]",
        listOf(ResponseStep(98702, "[classicServerTable,youthServerTable]")),
        listOf("read-only advertised private-server endpoint"),
        source = "Game.Data.GamePlay/Tenth.Data/LoginData.cs:1141-1171",
        test = "GameServerHandlerProtocolTest.98702 returns separate classic and youth tables",
    ),
    exact(
        99994, CommandDirection.DUPLEX, CommandDomain.LOGIN,
        "[mode,uid,serverSession,pushToken,language,country,clientVersion]",
        listOf(ResponseStep(99994, "[0] or [1]")),
        listOf("read-only ServerSessionRegistry validation"),
        source = "Game.Data.GamePlay/Tenth.Data/LoginData.cs:5157-5245",
        test = "GameServerHandlerProtocolTest.pre server and game login require the index two platform token",
    ),
    exact(
        99991, CommandDirection.DUPLEX, CommandDomain.LOGIN, "[passport,clientMetadata,serverSession]",
        listOf(ResponseStep(99991, "login tuple with UserInitTable")),
        listOf("Session GAME_LOGGED_IN and OnlineSessionRegistry after snapshot serialization"),
        source = "Game.Data.GamePlay/Tenth.Data/LoginData.cs:3635-3855 and Game.Network/Tenth.Data/LoginPacket.cs:60-145",
        test = "GameServerHandlerProtocolTest.fresh game channel accepts only the issued platform token",
    ),
)
```

Add the two named no-response tests to
`GameServerHandlerProtocolTest.kt` before promoting the catalog:

```kotlin
@Test
fun `valid wire heartbeat remains open`() {
    val channel = newChannel()
    channel.writeInbound(upPacket(channel, Cmd.SYS_HEART_BEAT, "[]"))
    assertTrue(channel.isOpen)
    assertNull(channel.readOutbound<Any>())
}

@Test
fun `valid wire acknowledgement remains open`() {
    val channel = newChannel()
    channel.writeInbound(upPacket(channel, Cmd.SYS_ACKNOWLEDGE, "[0]"))
    assertTrue(channel.isOpen)
    assertNull(channel.readOutbound<Any>())
}
```

Remove only these ids from `provisionalHandlerContracts` and
`provisionalPushContracts`.

- [ ] **Step 4: Run contract tests and generate coverage report**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.protocol.CommandContractRegistryTest \
  --tests com.stzb.server.protocol.CommandCoverageReportTest
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  protocolCoverageReport
rg -n '\\| (98888|90003|90005|90007|90008|90009|99992|20003|98702|99994|99991) \\|.*EXACT' \
  build/reports/protocol/command-coverage.md
```

Expected: all contract tests pass and every listed command appears with
`EXACT` in the generated report.

- [ ] **Step 5: Commit the contract promotion**

Run:

```bash
git add \
  src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt \
  src/test/kotlin/com/stzb/server/protocol/CommandContractRegistryTest.kt \
  src/test/kotlin/com/stzb/server/protocol/CommandCoverageReportTest.kt
git commit -m "feat: audit transport login command contracts"
```

### Task 6: Verify the Release and Run the Device Trace

**Files:**
- Verify only; do not modify unrelated files.

**Interfaces:**
- Consumes: all commits from Tasks 2 through 5.
- Produces: focused test evidence, coverage report, installable JAR hash, and a device login/reconnect trace.

- [ ] **Step 1: Run all transport/login focused tests**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.session.SessionTest \
  --tests com.stzb.server.auth.ServerSessionRegistryTest \
  --tests com.stzb.server.protocol.SysPacketsTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest \
  --tests com.stzb.server.protocol.CommandContractRegistryTest \
  --tests com.stzb.server.protocol.CommandCoverageReportTest
```

Expected: `BUILD SUCCESSFUL` with no focused-test failures.

- [ ] **Step 2: Build, report, and hash the distributable server**

Run:

```bash
./gradlew --no-daemon -Pkotlin.incremental=false \
  -Dkotlin.compiler.execution.strategy=in-process \
  protocolCoverageReport installDist
shasum -a 256 build/install/stzb-server/lib/stzb-server-0.1.0.jar
git status --short
```

Expected: `BUILD SUCCESSFUL`, a generated protocol coverage report, a JAR hash,
and no unstaged changes beyond generated build outputs.

- [ ] **Step 3: Run the normal device login and reconnect trace**

Start only the just-built JAR on the configured local port:

```bash
STZB_PORT=59979 \
STZB_DATA_DIR=/private/tmp/stzb-transport-login-data \
java -jar build/install/stzb-server/lib/stzb-server-0.1.0.jar \
  > /private/tmp/stzb-transport-login.log 2>&1 &
SERVER_PID=$!
adb reverse tcp:59979 tcp:59979
```

Open the unmodified 9.2.2 client, complete normal platform login, select the
private server, enter the game, then force a reconnect using the client UI.
After the trace:

```bash
rg -n 'cmd=(98888|99992|20003|98702|99994|99991|90008|5025|5026)' \
  /private/tmp/stzb-transport-login.log
kill "$SERVER_PID"
```

Expected: the log shows `98888 -> 99992 -> 20003 -> 99994 -> 99991 -> 5025/5026`
for normal entry, and a later valid `90008` followed by `99991` for reconnect.
There must be no `99991 登录拒绝`, invalid transport-frame warning, client
deserialization error, or unexpected `90007` in the normal path.

- [ ] **Step 4: Commit final verification documentation only if a durable trace artifact is added**

Do not commit `/private/tmp` logs or generated build outputs. If a permanent
trace summary is needed, add a new focused Markdown file under
`docs/superpowers/verification/`, verify it contains no account token or
device identifier, then commit it separately.

## Plan Self-Review

- Spec coverage: Tasks 2 and 4 cover `98888`, `90003`, `90008`, `90009`,
  `99992`, `99994`, `99991`, and `90007`; Task 3 covers `20003` and `98702`;
  Task 5 covers `90005` and all `EXACT` registrations; Task 6 supplies the
  required clean build and device trace.
- Placeholder scan: all implementation steps name exact files, methods,
  packet shapes, test names, commands, and expected results.
- Type consistency: `SessionPhase`, `FrameValidation`, `resolveRequired`,
  `preServerTokenCheck(Boolean)`, and
  `classicAndYouthServerList` are defined before their consuming handler
  and contract tasks.
