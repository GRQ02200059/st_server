# Multi-User Persistent World Design

## Goal

Turn the local Kotlin/Netty server into a small persistent multiplayer world:

- A login account owns one stable player identity.
- Different accounts have isolated resources, heroes, armies, marches, and owned land.
- The world has one authoritative owner for every player city and land tile.
- A new login for an already-online account disconnects the old session.
- State survives a server restart.

This design intentionally does not add PvP battles, alliances, visibility fog, or
distributed storage. An already-owned tile is simply not claimable by a second
player.

## Current State

The server already persists a `PlayerStateSnapshot` per `accountKey` in
`data/accounts/*.json`, including `resources`, `heroes`, `marches`, and
`occupiedLands`. It is not yet a multiplayer model because:

1. `Session.create()` always uses `local-dev-account`.
2. `Tb_world_city` and 5026 are built from the requesting player's local
   `occupiedLands` only.
3. No global owner index prevents two account files from claiming the same wid.
4. A player city is currently a single fixed location.

The client sets `LoginData.LoginServerUserId` from response index `3` of
`99992`. The platform request includes a JSON object containing `sdkuid` (and
can include `userid`). Therefore account binding must happen before responding
to `99992`, not after the game-server handshake.

## Chosen Architecture

Use three separate state scopes:

| Scope | Authority | Persistence |
| --- | --- | --- |
| Connection | `Session` | Memory only |
| Account | `PlayerState` | `data/accounts/<account>.json` |
| World | `WorldState` | `data/world.json` |

`WorldState` is authoritative for land and city ownership. `PlayerState` keeps
its `occupiedLands` as a denormalized per-player index for existing gameplay
code and login tables. On startup, `WorldState` rebuilds that index to repair
an interrupted two-file save.

## Account And Session Binding

### Identity extraction

`AccountIdentityResolver` resolves a canonical account key in this order:

1. Parse `99992` request element `0` as JSON and use nonblank `sdkuid`.
2. Use nonblank `userid` from the same JSON when `sdkuid` is absent.
3. Use the first nonblank passport string from `99991` only when no platform
   identity was received.
4. Reject login when all identity fields are absent.

The raw identity is not used as a file name. The resolver derives a stable,
namespaced SHA-256 account key such as `sdkuid:<hash>`.

### Wire identity versus player identity

The initial `98888` packet still needs an immediate connection-scoped user ID,
so `Session` has two identities:

- `wireUserId`: temporary ID written into 98888 and expected in frame headers.
- `playerId`: stable ID of the resolved `PlayerState`.

Handlers validate the incoming frame against the session SID and
`wireUserId`, but all database rows, game notifications, and ownership records
use `playerId`. `99992[3]` returns `playerId`, which is the value the client
uses as `LoginServerUserId`.

`99992` also creates a short-lived server-session token mapping to the resolved
account. On the later game-server `99991`, the resolver recovers that mapping;
the passport fallback remains available for the local test client.

### Duplicate login policy

`OnlineSessionRegistry` owns `accountKey -> Channel`. Binding a session:

1. Atomically replaces the old channel for the same account.
2. Sends the old channel `90007` (`SYS_SID_INVALID`) where possible.
3. Closes the old channel.
4. Registers the new session.

`channelInactive` unregisters only when it is still the registry's current
channel, so an old channel cannot remove a newer login.

## World Ownership

### Persistent representation

`WorldStateSnapshot` contains:

- Version number.
- `cities`: stable player city records (`cityWid`, `playerId`, `roleName`).
- `lands`: `wid -> LandClaim(playerId, cityWid, claimedAtSec)`.

The repository writes `data/world.json` with the existing temp-file plus atomic
replace pattern. A process-local read/write lock serializes world changes.

### Spawn allocation

New accounts receive a unique 3x3 city centered near Luoyang
`(1501,1501)`. `SpawnAllocator` enumerates square rings starting at radius
five and accepts the first center that:

- Is inside the cfg-5 map bounds.
- Has no static world-city tile in its 3x3 area.
- Does not overlap any existing dynamic city or claimed land.

The chosen center is persisted in `WorldState.cities`; existing accounts retain
their current city coordinate. This replaces the single global `CITY_WID` as
the new-account default only. `CITY_WID` remains the legacy migration source.

### Claiming land

`WorldService.claimLand(player, wid)` is the sole claim operation:

1. Acquire the world write lock, then the player's account lock.
2. Reject a wid owned by a different player.
3. Write the world claim.
4. Add the wid to the player's `occupiedLands`.
5. Persist the player state and world state.
6. Broadcast an updated world-scene packet to online users.

The world state is authoritative after a crash. Startup reconciliation removes
player-side land entries that are absent from `WorldState` and adds claims
owned by that player but absent from its account file.

PVE victory calls this operation instead of directly calling
`PlayerState.occupyLand`. If the tile was claimed by another user after the
battle began, battle completion remains valid but no new land is awarded.

## World Serialization And Synchronization

`WorldSnapshotView` exposes all dynamic player cities, suburbs, and claimed
lands with their owner IDs. It feeds both:

- `UserInitTableBuilder`: `Tb_world_city` includes the logged-in player plus
  known dynamic world rows, preserving the current center/suburb fields.
- `GameResponses.worldSceneFullInfo`: 5026 `WORLD_CITY` chunks contain player
  cities and claimed lands from the world snapshot.

For the first implementation, every online player receives a refreshed 5026
after a world change. The number of local test users is small, and this avoids
incorrect partial visibility. A later scale-up can limit broadcasts by map
region without changing ownership semantics.

Resources remain private `PlayerState` data. Resource/hero/build `90005`
notifications go only to that account's current session.

## State And Concurrency Rules

- `AccountOperationCoordinator` provides a per-account lock for mutable
  `PlayerState` operations.
- World claims acquire locks in a documented order: world lock, then account
  lock. No code may acquire them in the inverse order.
- Account creation reserves the city in `WorldState` before creating the
  account snapshot.
- The existing file repository remains the account store; no player data is
  shared between account files.
- Scheduled PVE settlement resolves the session's stable `playerId` and calls
  `WorldService.claimLand`, not a stale connection-local land set.

## Errors And Compatibility

- Invalid or missing login identity: reject the login response rather than
  falling back to a shared default account.
- Second login: old session receives SID invalidation and disconnects.
- Claim conflict: the requester receives normal battle completion but no
  `Tb_world_city` ownership update; the server logs the winning owner.
- Existing `local-dev-account` remains usable. Its legacy city migration runs
  once and seeds a matching city record in `WorldState`.
- Existing account files are backward-compatible because new world data is
  stored separately and `PlayerStateSnapshot` already has default values.

## Test Plan

1. Account identity parsing prefers `sdkuid`, falls back to `userid`, then
   passport, and rejects blank identity.
2. Repeated login for an account returns the same stable `playerId`; two
   account identities return different IDs and separate resource values.
3. A new account spawn is a non-overlapping 3x3 area near Luoyang.
4. Two concurrent claims for one wid produce exactly one owner.
5. World and account state survive repository reconfiguration; reconciliation
   restores each owner's land index.
6. A duplicate login invalidates only the old channel for the same account.
7. Login snapshot and 5026 for a second user include the first user's dynamic
   city and land with the first user's owner ID.
8. Existing single-account protocol tests remain valid with a resolved local
   test identity.
