# Multi-User Persistent World Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support independent login accounts in one persistent world, including duplicate-login eviction, non-overlapping city spawns, exclusive land ownership, and cross-user map visibility.

**Architecture:** Keep `PlayerState` as the account-private aggregate and add a `WorldState` aggregate as the authoritative owner index for player cities and claimed lands. Bind a connection to a resolved account at `99992`, then let all later requests use its stable player ID. Project the shared world into both the login snapshot and the 5026 world-scene packet.

**Tech Stack:** Kotlin 1.9, Netty, Jackson, JUnit 5/Kotlin test, file-backed atomic JSON persistence.

## Global Constraints

- Preserve the client protocol contract: `99992[3]` is the client `LoginServerUserId`; `99991` and `5026` must use the same stable player ID.
- Never use a packet's supplied `userId` as authority after a session is bound.
- New accounts spawn in a non-overlapping 3x3 empty area near Luoyang `(1501,1501)`.
- One world tile has at most one player owner.
- New login for an already-online account invalidates and closes the old connection.
- Continue persisting individual accounts under `data/accounts/`; persist shared world data separately under `data/world.json`.
- Keep existing player state files readable and migrate the legacy fixed city on first load.
- Do not modify unrelated battle-engine work already present in the worktree.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/stzb/server/auth/AccountIdentityResolver.kt` | Parse 99992/99991 identities and derive canonical account keys. |
| `src/main/kotlin/com/stzb/server/auth/ServerSessionRegistry.kt` | Map short server-session tokens to a resolved account. |
| `src/main/kotlin/com/stzb/server/session/Session.kt` | Hold connection wire identity and late-bound stable player identity/account key. |
| `src/main/kotlin/com/stzb/server/session/OnlineSessionRegistry.kt` | One active Netty channel per account; evict previous connections. |
| `src/main/kotlin/com/stzb/server/game/WorldState.kt` | World snapshot models, file repository, spawn allocation, claim/reconciliation service. |
| `src/main/kotlin/com/stzb/server/game/WorldProjection.kt` | Immutable world rows consumed by snapshots and 5026. |
| `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt` | Include projected world cities/lands in login tables. |
| `src/main/kotlin/com/stzb/server/game/GameResponses.kt` | Serialize projected world users and city chunks in 5026. |
| `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt` | Stop directly claiming a land; report victory target to the handler. |
| `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt` | Resolve account at login, bind/evict session, use world claim service, broadcast 5026. |

## Task 1: Account Identity And Login Token

**Files:**
- Create: `src/main/kotlin/com/stzb/server/auth/AccountIdentityResolver.kt`
- Create: `src/main/kotlin/com/stzb/server/auth/ServerSessionRegistry.kt`
- Test: `src/test/kotlin/com/stzb/server/auth/AccountIdentityResolverTest.kt`
- Test: `src/test/kotlin/com/stzb/server/auth/ServerSessionRegistryTest.kt`

**Interfaces:**

```kotlin
data class AccountIdentity(val accountKey: String, val displayId: String)

object AccountIdentityResolver {
    fun fromPlatformLoginRequest(bodyText: String): AccountIdentity?
    fun fromGameLoginRequest(bodyText: String): AccountIdentity?
}

class ServerSessionRegistry(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun issue(identity: AccountIdentity): String
    fun resolve(token: String): AccountIdentity?
    fun removeExpired()
}
```

- [ ] **Step 1: Write failing identity resolver tests**

```kotlin
@Test
fun `platform identity prefers sdkuid over userid`() {
    val identity = AccountIdentityResolver.fromPlatformLoginRequest(
        """["{\"sdkuid\":\"alice\",\"userid\":\"fallback\"}",0,"",0]""",
    )

    assertEquals("sdkuid:alice", identity?.displayId)
    assertEquals(identity, AccountIdentityResolver.fromPlatformLoginRequest(
        """["{\"sdkuid\":\"alice\"}",0,"",0]""",
    ))
}

@Test
fun `identity falls back to game login passport and rejects blank values`() {
    assertEquals(
        "passport:beta",
        AccountIdentityResolver.fromGameLoginRequest("""["beta","token",1]""")?.displayId,
    )
    assertNull(AccountIdentityResolver.fromPlatformLoginRequest("""["{}",0,"",0]"""))
    assertNull(AccountIdentityResolver.fromGameLoginRequest("""[" ","",1]"""))
}
```

- [ ] **Step 2: Run the resolver test**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.auth.AccountIdentityResolverTest
```

Expected: compilation failure because the resolver does not exist.

- [ ] **Step 3: Implement canonical identity derivation**

Implement these rules:

```kotlin
private fun canonical(source: String, value: String): AccountIdentity {
    val normalized = value.trim()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$source:$normalized".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return AccountIdentity(accountKey = "$source-$digest", displayId = "$source:$normalized")
}
```

For `99992`, parse outer JSON array element `0` as JSON object and choose
nonblank `sdkuid`, then nonblank `userid`. For `99991`, use element `0` as
the passport fallback. Do not use arbitrary packet headers as an account key.

- [ ] **Step 4: Write failing token registry tests**

```kotlin
@Test
fun `issued token resolves exactly once before expiry`() {
    val registry = ServerSessionRegistry(clockMillis = { 1_000L })
    val identity = AccountIdentity("sdkuid-hash", "sdkuid:alice")
    val token = registry.issue(identity)

    assertEquals(identity, registry.resolve(token))
    assertEquals(identity, registry.resolve(token))
    assertNull(registry.resolve("unknown"))
}
```

- [ ] **Step 5: Implement token registry and rerun both test classes**

Use `ConcurrentHashMap<String, TokenEntry>` and a 10-minute expiry. Tokens
must be random 32-byte URL-safe Base64 strings. `resolve` returns `null` for
unknown or expired tokens.

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.auth.AccountIdentityResolverTest \
       --tests com.stzb.server.auth.ServerSessionRegistryTest
```

Expected: PASS.

## Task 2: Shared World State, Spawn Allocation, And Atomic Claims

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/WorldState.kt`
- Test: `src/test/kotlin/com/stzb/server/game/WorldStateRepositoryTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/WorldServiceTest.kt`

**Interfaces:**

```kotlin
data class WorldCity(
    val cityWid: Int,
    val userId: Int,
    val roleName: String,
)

data class LandClaim(
    val wid: Int,
    val userId: Int,
    val belongCity: Int,
    val claimedAtSec: Int,
)

data class WorldStateSnapshot(
    val version: Int = 1,
    val cities: List<WorldCity> = emptyList(),
    val lands: List<LandClaim> = emptyList(),
)

class WorldService(
    private val repository: WorldRepository,
    private val playerRepository: PlayerRepository,
) {
    fun registerOrRestorePlayer(state: PlayerState): PlayerState
    fun claimLand(state: PlayerState, wid: Int, nowSec: Int): Boolean
    fun projection(): WorldProjection
}
```

- [ ] **Step 1: Write failing spawn and claim tests**

```kotlin
@Test
fun `new players receive non-overlapping three by three cities near Luoyang`() {
    val world = newWorldService()
    val first = world.registerOrRestorePlayer(player(10001, "alice"))
    val second = world.registerOrRestorePlayer(player(10002, "bob"))

    assertNotEquals(first.cityWid, second.cityWid)
    assertTrue(HomeCity.suburbWids(first.cityWid).plus(first.cityWid).none(
        HomeCity.suburbWids(second.cityWid).plus(second.cityWid)::contains,
    ))
    assertTrue(kotlin.math.abs(first.cityWid / 10_000 - 1501) >= 5)
}

@Test
fun `first player wins a contested land claim`() {
    val world = newWorldService()
    val first = world.registerOrRestorePlayer(player(10001, "alice"))
    val second = world.registerOrRestorePlayer(player(10002, "bob"))

    assertTrue(world.claimLand(first, 15081508, 100))
    assertFalse(world.claimLand(second, 15081508, 101))
    assertEquals(setOf(15081508), first.occupiedLands())
    assertTrue(second.occupiedLands().isEmpty())
}
```

- [ ] **Step 2: Run the world tests**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.WorldStateRepositoryTest \
       --tests com.stzb.server.game.WorldServiceTest
```

Expected: compilation failure because world state types do not exist.

- [ ] **Step 3: Implement world repository and lock discipline**

`FileWorldRepository` reads/writes `world.json` under the same root supplied
to `FilePlayerRepository`. Use a single `ReentrantReadWriteLock` inside
`WorldService`.

`registerOrRestorePlayer`:

1. Return the stored city for an existing `userId`.
2. For a new user, scan candidate centers in increasing square rings around
   `(1501,1501)`, beginning at radius five.
3. Reject candidates whose 3x3 intersects an existing world city/land or
   `StaticCityCatalog` rows.
4. Update `state.cityWid`, record `WorldCity`, save world, then save player.

`claimLand`:

1. Reject the request if another `LandClaim` owns the wid.
2. Treat a repeated claim by the same user as success.
3. Insert a claim and call `state.occupyLand(wid)`.
4. Persist both aggregates while holding the world write lock.

- [ ] **Step 4: Add restart reconciliation test**

```kotlin
@Test
fun `world ownership restores a missing account land index after restart`() {
    val world = newWorldService()
    val state = world.registerOrRestorePlayer(player(10001, "alice"))
    assertTrue(world.claimLand(state, 15081508, 100))

    val restored = PlayerState.fromSnapshot(state.toSnapshot().copy(occupiedLands = emptySet()))
    val afterRestart = reloadWorldService().registerOrRestorePlayer(restored)

    assertEquals(setOf(15081508), afterRestart.occupiedLands())
}
```

- [ ] **Step 5: Run the world tests**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.WorldStateRepositoryTest \
       --tests com.stzb.server.game.WorldServiceTest
```

Expected: PASS.

## Task 3: Session Binding And Duplicate-Login Eviction

**Files:**
- Create: `src/main/kotlin/com/stzb/server/session/OnlineSessionRegistry.kt`
- Modify: `src/main/kotlin/com/stzb/server/session/Session.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Test: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`
- Test: `src/test/kotlin/com/stzb/server/session/OnlineSessionRegistryTest.kt`

**Interfaces:**

```kotlin
class Session(
    val wireUserId: Int,
    val sid: ByteArray,
) {
    var accountKey: String? = null
        private set
    var playerId: Int? = null
        private set
    fun bind(accountKey: String, playerId: Int)
}

class OnlineSessionRegistry {
    fun bind(accountKey: String, channel: Channel): Channel?
    fun remove(accountKey: String, channel: Channel)
    fun current(accountKey: String): Channel?
    fun allChannels(): List<Channel>
}
```

- [ ] **Step 1: Write a failing duplicate-login registry test**

```kotlin
@Test
fun `binding a second channel returns the old channel and preserves the new channel`() {
    val registry = OnlineSessionRegistry()
    val first = EmbeddedChannel()
    val second = EmbeddedChannel()

    assertNull(registry.bind("sdkuid-alice", first))
    assertSame(first, registry.bind("sdkuid-alice", second))
    registry.remove("sdkuid-alice", first)

    assertSame(second, registry.current("sdkuid-alice"))
}
```

- [ ] **Step 2: Run the registry test and implement it**

Use `ConcurrentHashMap<String, Channel>`. `remove` must use
`remove(accountKey, channel)` so a stale channel cannot delete a newer login.

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.session.OnlineSessionRegistryTest
```

Expected: PASS.

- [ ] **Step 3: Write failing protocol tests for account resolution and eviction**

Extend the handler test helper to retain the special 98888 packet rather than
discarding it. Add a helper which sends `99992` with a supplied `sdkuid`.

```kotlin
@Test
fun `different platform identities receive different persistent player ids`() {
    val alice = loginPlatform("alice")
    val bob = loginPlatform("bob")

    assertNotEquals(alice.playerId, bob.playerId)
}

@Test
fun `new login invalidates the old channel for the same platform identity`() {
    val old = loginPlatform("alice")
    val newer = loginPlatform("alice")

    assertEquals(Cmd.SYS_SID_INVALID, old.channel.readOutbound<DownPacket>().cmd)
    assertFalse(old.channel.isOpen)
    assertTrue(newer.channel.isOpen)
}
```

- [ ] **Step 4: Bind the account in `sendPlatformLoginCheck`**

1. Parse the 99992 request identity.
2. Load/create its `PlayerState` through `PlayerStateRepository`.
3. Bind the session with the resulting stable player ID.
4. Issue a server session token and return `playerId` at `99992[3]`.
5. Register the Netty channel; for a replaced channel write
   `DownPacket.json(Cmd.SYS_SID_INVALID, "[]")`, then close it.
6. Change every handler use of `session.userId` to `session.playerId` after
   binding. Before binding, only platform/server-list requests are allowed.
7. In `channelInactive`, unregister the channel only if it is still current.

- [ ] **Step 5: Run session and handler protocol tests**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.session.OnlineSessionRegistryTest \
       --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: PASS.

## Task 4: Project Shared World Into Login And 5026

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/WorldProjection.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Test: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

**Interfaces:**

```kotlin
data class WorldProjection(
    val cities: List<WorldCity>,
    val lands: List<LandClaim>,
)

fun UserInitTableBuilder.build(
    state: PlayerState,
    serverOpenTime: Long,
    world: WorldProjection,
): ArrayNode

fun GameResponses.worldSceneFullInfo(
    state: PlayerState,
    world: WorldProjection,
    serverOrderId: Int = 1,
    removedArmyId: Int? = null,
): String
```

- [ ] **Step 1: Write failing login snapshot projection test**

```kotlin
@Test
fun `login snapshot includes other player city and claimed land`() {
    val world = WorldProjection(
        cities = listOf(
            WorldCity(15061506, 10, "Alice"),
            WorldCity(14961496, 11, "Bob"),
        ),
        lands = listOf(LandClaim(14971496, 11, 14961496, 100)),
    )

    val tables = UserInitTableBuilder.build(aliceState, 100L, world)
        .drop(1).associateBy { it[0].asText() }
    val rows = tables.getValue("Tb_world_city")[1]

    assertTrue(rows.any { it[0].asInt() == 14961496 && it[6].asInt() == 11 })
    assertTrue(rows.any { it[0].asInt() == 14971496 && it[21].asInt() == 14961496 })
}
```

- [ ] **Step 2: Implement snapshot and 5026 projection**

Build `Tb_world_city` and `worldCityChunk` by iterating `WorldProjection`:

- Main city: type `1`, owner `userId`, `belong_city=0`.
- Each suburb: type `5`, same owner, `belong_city=main city`.
- Claimed land: type `2`, owner, `belong_city=claim city`.
- `worldMapUsers` contains all projected city owners, each with their own main
  city wid.

Use `LinkedHashMap<Int, Row>` or `distinctBy(wid)` so self rows are emitted
once even though the caller is also in the shared projection.

- [ ] **Step 3: Write failing 5026 projection test**

```kotlin
@Test
fun `world scene uses each projected owners user id`() {
    val response = mapper.readTree(GameResponses.worldSceneFullInfo(alice, world))

    assertEquals("Bob", response[1]["11"][0].asText())
    assertEquals(14961496, response[1]["11"][1].asInt())
    assertEquals(11, response[14]["14971496"]["0"][2].asInt())
    assertEquals(14961496, response[14]["14971496"]["0"][7].asInt())
}
```

- [ ] **Step 4: Run snapshot and response tests**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.UserInitTableBuilderTest \
       --tests com.stzb.server.game.GameResponsesTest
```

Expected: PASS.

## Task 5: Route PVE Occupation Through The World Service And Broadcast

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt`
- Test: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**

```kotlin
data class PlayerBattleLaunchResult(
    val battleId: Int,
    val targetWid: Int,
    val outcome: BattleOutcome? = null,
    val mayClaimLand: Boolean = false,
)
```

- [ ] **Step 1: Write failing contested-settlement test**

```kotlin
@Test
fun `winning battle does not add a land before the world claim succeeds`() {
    val state = preparedWinningState()
    val result = service.settlePveBattle(state, nowSec = dueTime)

    assertTrue(result?.mayClaimLand == true)
    assertTrue(state.occupiedLands().isEmpty())
}
```

- [ ] **Step 2: Change battle service to report eligibility only**

Remove direct `state.occupyLand(march.targetWid)` from
`PlayerBattleService.settlePveBattle`. Set `mayClaimLand` only when the final
battle outcome is `ATTACKER_WIN`.

- [ ] **Step 3: Claim and broadcast in the handler**

At scheduled settlement:

1. Persist troop/report changes.
2. When `result.mayClaimLand`, call `worldService.claimLand`.
3. Send `occupiedLandUpsertNotify` only to the winner when the claim succeeds.
4. Call `broadcastWorldScene()` after every successful claim. This writes a
   full 5026 packet to every current channel from `OnlineSessionRegistry`.
5. If the claim loses a race, leave the battle report but omit land ownership
   update and log the existing owner.

- [ ] **Step 4: Add protocol broadcast test**

```kotlin
@Test
fun `successful claim refreshes the world scene for another online account`() {
    val alice = loginPlatform("alice")
    val bob = loginPlatform("bob")
    settleWinningBattle(alice)

    val bobScene = nextPacket(bob.channel, Cmd.SEND_WORLD_SCENCE_FULL_INFO)
    assertEquals(alice.playerId, mapper.readTree(bobScene.body)[14][claimedWid.toString()]["0"][2].asInt())
}
```

- [ ] **Step 5: Run battle service and handler tests**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.PlayerBattleServiceTest \
       --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: PASS.

## Task 6: Integrated Persistence Verification

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerStateRepositoryTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

- [ ] **Step 1: Add end-to-end persistence test**

```kotlin
@Test
fun `two accounts keep independent resources and world land after repository restart`() {
    val alice = loginPlatform("alice")
    val bob = loginPlatform("bob")
    setFood(alice, 111)
    setFood(bob, 222)
    claim(alice, 15081508)
    restartRepositories()

    assertEquals(111, stateFor("alice").resources.food)
    assertEquals(222, stateFor("bob").resources.food)
    assertEquals(setOf(15081508), stateFor("alice").occupiedLands())
    assertTrue(stateFor("bob").occupiedLands().isEmpty())
}
```

- [ ] **Step 2: Run focused multi-user suite**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.auth.AccountIdentityResolverTest \
       --tests com.stzb.server.auth.ServerSessionRegistryTest \
       --tests com.stzb.server.session.OnlineSessionRegistryTest \
       --tests com.stzb.server.game.WorldStateRepositoryTest \
       --tests com.stzb.server.game.WorldServiceTest \
       --tests com.stzb.server.game.PlayerStateRepositoryTest \
       --tests com.stzb.server.handler.GameServerHandlerProtocolTest \
       --tests com.stzb.server.game.UserInitTableBuilderTest \
       --tests com.stzb.server.game.GameResponsesTest
```

Expected: PASS.

- [ ] **Step 3: Run build and full regression**

Run:

```bash
STZB_DATA_DIR=/tmp/stzb-multiuser-test \
  ./gradlew -Dkotlin.compiler.execution.strategy=in-process test installDist
```

Expected: all multi-user tests pass; report any pre-existing unrelated
full-suite failures separately, with their exact class names.

- [ ] **Step 4: Smoke test the installed distribution**

Run the installed server on a non-production local port:

```bash
STZB_PORT=59981 STZB_DATA_DIR=/tmp/stzb-multiuser-smoke \
  ./build/install/stzb-server/bin/stzb-server
```

In another terminal, run two synthetic login flows with distinct `sdkuid`
values. Verify that the two login snapshots return different `Tb_user.userid`
and city wids, then request 5026 from both and verify each contains both city
owners.
