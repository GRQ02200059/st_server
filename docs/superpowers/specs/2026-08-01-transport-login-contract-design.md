# Transport and Login Contract Design

## Goal

Promote the 9.2.2 transport and login command group from provisional behavior
to explicit server contracts. The normal unmodified client must complete
handshake, platform authentication, server discovery, pre-server verification,
game login, reconnect validation, initial table load, and subsequent `90005`
updates without relying on a client-supplied account identity.

## Scope

This specification covers:

- `98888` server-to-client SID notification;
- `90003`, `90008`, and `90009` transport keepalive, reconnect, and ACK flow;
- `99992` platform login check;
- `20003` normal server-list query;
- `98702` classic-and-youth server-list query;
- `99994` pre-server token check;
- `99991` game login and initial snapshot;
- `90005` post-login table notifications;
- `90007` displaced-session notification.

It does not implement payment, real-name verification, official SDK traffic,
or seasonal game-server routing. The private server provides local, client
readable results for those external branches without contacting official
services.

## Client Evidence

The normal client flow is explicit in the decompiled source:

1. `DotnetCmdMgr.ProcessPackage` consumes `98888`, replacing its wire user id,
   command index, and 32-byte SID before subsequent packets.
2. `SdkManager.RequestPlatformLoginCheckAfterCheck` sends
   `99992` as `[sauthJson, clientPackage, country, isMiniGame]`.
3. The `99992` callback requires `response[0] == 1`, takes its game-login
   token from `response[2]`, and takes the login-server user id from
   `response[3]`. A `null` `response[1]` is a valid minimal private-server
   path.
4. `LoginData.DelayRequest` sends `20003`; its response is
   `[status, payload]`. The payload contains the server-list table and
   indexed metadata.
5. `LoginData.RequestRefreshServerList` sends `98702`; its response is a
   two-element array `[classicServerList, youthServerList]`, with each
   element using the server-list table format.
6. `LoginData.OrganizationPreCheckParam` sends `99994` with the `99992`
   token at index `2`.
7. `LoginData.RequestLogin` sends `99991` with the same token at index `2`.
   The client parses `LoginPacket.Json[4][0]` as `UserInitTable` only when
   `Json[0] == 1` and `Json[2]` is `1` or `2`.
8. `LoginPacket.ReadEnterGameResult` delegates the snapshot to
   `DbNotify.ReadFromReader`; schema failure leaves `UserInitTable` null and
   prevents login completion.
9. On a subsequent connection, `DotnetCmdMgr` sends `90008` before resending
   a cached login command. A `90008` complex response is intentionally not
   dispatched to gameplay code.
10. `90005` is deserialized as raw bytes and applied by
    `DbNotify.UpdateDataNew`; every update row must retain its notify type,
    table name, and sparse field payload.

## Session Model

The server separates two identities and three connection phases:

- **Wire session**: generated at channel activation and represented by
  `wireUserId`, `sid`, and starting `cmdIndex` in `98888`.
- **Authenticated account**: derived only from a successful `99992` request
  and represented by a private `ServerSession` token plus a durable player
  account key.
- **Game-online session**: an authenticated account whose `99991` snapshot
  was built successfully and whose channel is registered for gameplay pushes.

`Session` advances only through:

```text
CONNECTED
  -> PLATFORM_AUTHENTICATED
  -> GAME_LOGGED_IN
```

`99992` may create or restore durable player state so it can return the
stable player id, but it only binds the connection to
`PLATFORM_AUTHENTICATED`. It does not register the channel in
`OnlineSessionRegistry`, broadcast world updates to it, or evict an existing
game-online channel. `99991` is the only command that enters
`GAME_LOGGED_IN`; it does so after the token is verified and the complete
login snapshot is serialized. Only then can it atomically replace an existing
game-online channel for the same account.

The transport verifier runs before every normal client frame reaches command
dispatch. It verifies:

- `checkCode`;
- equality of the supplied SID and this channel's 32-byte `Session.sid`;
- equality of the supplied user id and this channel's `wireUserId`;
- a nondecreasing command index, allowing a same-index retransmission but
  rejecting an older frame after a newer index was accepted.

The verifier never treats the packet `userId` as the persistent player id.
The wire user id remains valid after platform and game login; game state,
notifications, and ownership use the authenticated stable player id.

`ServerSessionRegistry` stores opaque, expiring tokens. It exposes:

```kotlin
fun issue(identity: AccountIdentity): String
fun resolve(token: String): AccountIdentity?
fun resolveRequired(token: String): AccountIdentity?
```

`resolveRequired` is a semantic distinction used by game-login handlers: an
empty, unknown, or expired token is rejected rather than falling back to a
passport string supplied in the request body.

The server may retain a token until expiry so a normal reconnect can repeat
`99994` and `99991`. It does not consume the token on first use. Expiry and
session displacement remain server-authoritative.

## Command Contracts

### 98888 SID Notification

At channel activation the server sends the special binary packet:

```text
[length=52][cmd=98888][hash=0][wireUserId][cmdIndex][sid:32 bytes][param]
```

The packet has no ordinary `dataType` byte. The client reads its final
`param` as an integer and uses its decimal representation only for the
transport event callback. The server emits it before any login command.

### 90003, 90008, and 90009

- `90003` is a client heartbeat. The server records activity but need not
  fabricate a gameplay response.
- `90008` is a reconnect check. It is accepted only when the frame has a
  valid current-channel checksum, SID, wire user identity, and command index.
  The response is an empty complex `90008` packet, not a JSON packet. It
  confirms the fresh `98888` assignment; it does not reattach stale gameplay
  state by itself.
- `90009` acknowledges nonzero down-packet hashes. It is accepted only as an
  acknowledgement and has no direct response.

An invalid checksum, SID, wire user id, or stale command index receives
`90007` and the channel closes. The check does not trust `msg.userId` as a
player identity after the account has been bound.

### 99992 Platform Authentication

The server parses only the outer element `0` when it is JSON text or a JSON
object. `sdkuid` is preferred; `userid` is the fallback. On success:

```json
[1, null, "<opaque server session>", 12345]
```

The token maps to the derived account identity and player id. On parse or
authentication failure:

```json
[0, null, "", 0]
```

The client-required `null` second slot prevents local SDK-specific AAS and
real-name dictionary parsing in private-server operation.

If a connection has already entered `PLATFORM_AUTHENTICATED`, a second
`99992` must resolve to the same account. A different account is rejected
with the failure tuple and cannot replace the connection's pending identity.

### 20003 and 98702 Server Lists

`20003` returns:

```text
[0, [serverTable, announcements, loggedServerIds, isGm, loginServerTime, isoCode]]
```

`serverTable` is:

```text
[[columnNames...], [rowValues...], ...]
```

Rows expose only client `ServerInfo` fields that the server owns. Every value
uses an integer, long, or string JSON type matching the field reader.

`98702` returns:

```text
[classicServerTable, youthServerTable]
```

Both tables use the same column and row format as `20003`; each contains the
private server's selectable row. It must not reuse the `20003` envelope.

### 99994 Pre-Server Check

The request carries the `ServerSession` token at index `2`. The server
extracts exactly that textual slot and resolves it without changing player
state, connection phase, or online registration. A valid token returns `[0]`,
which is the client continuation path. An invalid or expired token returns
`[1]`; the client remains in its pre-server failure flow and does not receive
a game-login snapshot. Tokens found in any other request slot do not count.

### 99991 Game Login

For every game-login attempt, the server extracts the token only from request
index `2` and resolves it via `ServerSessionRegistry.resolveRequired`. It
does not derive identity from the passport, device, or any other request
field if this slot is absent or invalid.

For a `PLATFORM_AUTHENTICATED` channel, the resolved token must map to that
same account. For a fresh game channel, it establishes the authenticated
identity. In either case, a token resolving to another account is rejected
and cannot replace the connection identity.

The server restores player state and generates the complete response before
calling `OnlineSessionRegistry.bind`. Snapshot serialization failure therefore
does not register the new channel or displace an existing game-online
channel. Once serialization succeeds, the channel becomes
`GAME_LOGGED_IN`; any replaced game-online channel receives `90007` and is
closed.

Success uses:

```text
[
  1,
  [0, serverTimeSec, 0, baseTimeDiff],
  1,
  cfgDataIndex,
  [UserInitTable, loginNotice, unionMarks, unionRelations,
   nationalTechs, unionCalendar, ...]
]
```

The `UserInitTable` schema and all table rows are generated by the shared
snapshot projector. The response is sent only after account binding and state
restoration complete. Failure is `[0]`; it never contains a partial table.

### 90005 Database Notification

Each post-login state change is a raw JSON byte sequence carried in a normal
down packet. Rows retain:

```text
[notifyType, tableName, sparseFieldsOrDeleteKey]
```

Snapshot and notification projectors share the same player state. Any
successful direct response that changes state sends its prescribed `90005`
after durable save.

## Failure Handling

- Invalid platform payload: `99992 -> [0,null,"",0]`.
- Missing, expired, or mismatched game token: `99991 -> [0]`, no player
  binding and no snapshot.
- Invalid pre-server token: `99994 -> [1]`, no state mutation.
- Invalid reconnect wire identity: send `90007`, then close.
- Snapshot serialization failure: log an error and return login failure rather
  than send a malformed `99991`.
- A second channel for the same authenticated account receives the existing
  displacement flow: old channel gets `90007`, then closes.

## Verification

The implementation must add:

1. Binary packet tests for the `98888` length, field order, and SID length.
2. Transport tests covering valid and invalid checksum, SID, wire user, and
   stale command-index paths, including the complex `90008` response.
3. Platform login tests covering valid identity, malformed payload, token
   expiry, stable player id, and no online-session activation before `99991`.
4. Contract tests proving `99994` and `99991` reject absent, off-slot,
   expired, and cross-account tokens without a passport fallback.
5. Login activation tests proving snapshot construction succeeds before
   duplicate-login eviction and that only a successful `99991` registers an
   online channel.
6. Shape tests showing `20003` and `98702` have their distinct client
   envelopes.
7. Login snapshot tests that assert `LoginPacket`-required outer slots and
   schema-bearing `UserInitTable`.
8. `90005` packet-order tests proving durable mutation precedes direct
   response and notification.
9. A normal-device login and reconnect trace recording `98888`, `99992`,
   `20003`, `99994`, `99991`, and `5025/5026` in order.

The domain is `EXACT` only when these tests and the normal-device trace pass.
