# 9.2.2 Client Protocol Parity Program Design

## Goal

Build a server-only implementation that supports every network-facing feature
exposed by the bundled 9.2.2 client. For each client command, the server must
have an explicit, auditable contract for both request and response traffic.
Playable features must complete a real client lifecycle: request, authoritative
state change, ordered response and notifications, reconnect, and state recovery.

The client binary, DLLs, local assets, and runtime configuration are not
modified to achieve protocol compatibility.

## Scope

The program covers every command discovered from the 9.2.2 decompiled client:

- request sites calling `NetManager.Send`, panel `Send`, or `SendRawObject`;
- server packets consumed through `NetObserver`, network callbacks, or
  table-event projections;
- constants declared by `Tenth.Network.NetCommandDef`;
- command ids and packet shapes observed in local capture fixtures.

`NetCommandDef` contains commands that are not all reachable from one account
or one season. The inventory therefore records direction and reachability
separately. A constant is not considered implemented merely because it has a
name, and an observed server push is not considered an unimplemented inbound
request.

External platform operations, including official payment, advertising,
real-name, community, customer-service, and account APIs, use local private
server adapters. They must never call, impersonate, or depend on official
services. Privileged or diagnostic operations are server-authorized and
audited; a normal player packet cannot acquire privilege by selecting a client
only UI path.

This is the program-level design. It intentionally spans multiple independent
implementation specifications. No individual domain milestone may claim to
complete the overall objective.

## Existing-State Constraints

- The server is Kotlin 1.9.23 on Netty and Jackson.
- The canonical server source root is `src/main/kotlin/com/stzb/server`.
- The canonical 9.2.2 source evidence is
  `/Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/decompiled`.
- Existing capture tooling and fixtures live in
  `/Users/bytedance/stzb/tools/monitor-agent`.
- Existing account persistence and currently working user behavior must remain
  compatible while commands are migrated.
- The worktree is shared and may contain unrelated uncommitted changes. Every
  protocol-parity commit stages only files owned by its task.
- Gradle verification on this macOS environment uses
  `-Dkotlin.compiler.execution.strategy=in-process`.

The earlier modularization design at
`docs/superpowers/specs/2026-07-24-server-modularization-persistence-command-policy-design.md`
remains useful for handler boundaries. This design supersedes its command
coverage policy: protocol parity is driven by a full bidirectional client
inventory, not only by the subset of commands already handled by the server.
It does not require replacing the existing persistence implementation merely
to introduce command contracts.

## Evidence Model

Each contract carries independently reviewable evidence:

1. `SOURCE`: decompiled client path, line range, and the send or receive
   behavior it proves.
2. `CAPTURE`: a local fixture path and direction with the observed JSON or
   binary packet shape.
3. `SERVER_TEST`: an automated request, response-order, and state-projection
   test.
4. `DEVICE`: a reproducible normal-client action and its server log evidence.

Evidence is cumulative. A command cannot be `EXACT` unless it has source
evidence and server tests. A command that mutates visible player state also
requires device evidence before its domain is considered complete.

## Command Contract Registry

The server gains one canonical registry for the 9.2.2 protocol. It replaces
the current split knowledge in `Cmd.kt`, `GameServerHandler`, and
`NetworkResponsePolicy`.

Each entry has these fields:

| Field | Meaning |
|---|---|
| `id` | Numeric command id. |
| `name` | Client constant name when one exists. |
| `direction` | `CLIENT_REQUEST`, `SERVER_PUSH`, or `DUPLEX`. |
| `domain` | One functional owner, such as map, army, battle, union, or activity. |
| `reachability` | Visible, conditional by season or activity, external adapter, privileged, or unknown. |
| `requestShape` | JSON or binary request schema for client requests. |
| `responseSequence` | Direct response and all ordered follow-up packets. |
| `stateProjection` | Client tables, world chunks, or cache state changed by a successful operation. |
| `handler` | The unique server handler or push projector that owns the contract. |
| `status` | `EXACT`, `OBSERVED_SHAPE`, `PROVISIONAL`, `UNIMPLEMENTED`, or `REJECTED`. |
| `evidence` | Source, capture, server-test, and device references. |

Status meanings are strict:

- `EXACT`: request parsing, response sequence, server state, and projection are
  proven and tested.
- `OBSERVED_SHAPE`: a response packet shape is proven, but its business state
  has not been implemented.
- `PROVISIONAL`: current server behavior exists but lacks enough client
  evidence to be called exact.
- `UNIMPLEMENTED`: known command with no compatible server behavior.
- `REJECTED`: a privileged, malformed, or unsupported external request has an
  explicit local rejection contract; it is not silently treated as success.

The registry must enforce:

- every known command id has exactly one classification;
- every `EXACT` client request has exactly one handler;
- every `EXACT` server push has exactly one projector;
- every response shape is associated with the client consumption evidence;
- every mutating command declares the state it commits and the notifications it
  emits after that commit;
- no generic numeric range may mark a command handled.

## Data Flow

```text
9.2.2 client request
  -> frame decoder
  -> command registry lookup
  -> request parser and authorization
  -> domain service transaction
  -> durable state save
  -> direct command response
  -> ordered projections: 90005 / 5026 / 5028 / 2100 / domain push

server-originated event
  -> command registry lookup
  -> domain projector
  -> ordered down packets
  -> client observer or table update
```

No handler may report success before its durable state change succeeds.
Projection builders for login snapshots and online notifications share the
same domain state, so a reconnect cannot undo an acknowledged client action.

## Unknown and Fallback Behavior

The current `isBusinessCommand(cmdId) -> []` behavior is removed in the
foundation milestone. It obscures missing features and makes a client callback
look successful when no server behavior exists.

Only a registry entry with `OBSERVED_SHAPE` may return a temporary shape-only
packet. It records a warning with command id, account, evidence status, and
bounded payload metadata. Unknown ids and malformed requests receive their
contractual rejection or are recorded without a fabricated successful
response. The registry report exposes these cases as unfinished work.

## Program Domains and Order

The program is executed in dependency order. Each numbered item is a separate
specification and implementation plan after the command-contract foundation.

1. **Contract foundation**: inventory generation, registry, evidence reporting,
   fallback removal, and regression harness.
2. **Transport and login**: framing, handshake, session restoration, login
   snapshot, system notifications, and account identity.
3. **World and city**: map view requests, full and delta world packets,
   city layouts, land detail, construction, resources, and map effects.
4. **Army and progression**: armies, march lifecycle, conscription, heroes,
   skills, recruiting, advancement, equipment, and facades.
5. **Combat and reports**: all client-visible battle modes, defender data,
   battle state, result projections, and report profile/detail retrieval.
6. **Social and governance**: profiles, chat, mail, friends, alliances,
   clans, offices, and their realtime notifications.
7. **Tasks, season, and economy**: tasks, achievements, shops, rewards,
   season progression, rankings, and local adapters for platform-facing UI.
8. **Conditional and remaining domains**: seasonal events, mini-games,
   cross-region modes, legacy paths, and every remaining conditional command.

The contract foundation inventories all commands before any domain is declared
complete. Domain work may proceed in priority order, but the global report
remains authoritative about what is still unfinished.

## Verification

Every milestone must provide all of the following:

1. A generated inventory report that accounts for every client-discovered
   command id and labels both directions correctly.
2. Contract tests that fail when an `EXACT` command lacks its owner, schema,
   response sequence, projection, or evidence.
3. Unit and integration tests for each mutating operation, including reload
   from persisted state.
4. Packet-order tests for direct responses followed by required pushes.
5. Fixture comparison against captures when packet evidence exists.
6. A real-device normal-client test for each visible feature in the completed
   domain.

The full program is complete only when every inventory record is either
`EXACT` with the required evidence or a documented local external adapter or
rejection contract that the normal client can parse. The report must contain
no `UNIMPLEMENTED` or `PROVISIONAL` entries.

## First Specification Boundary

The first implementation specification is the contract foundation. It will:

- generate the bidirectional 9.2.2 command inventory from client source and
  capture fixtures;
- introduce the server command-contract model and a validated registry;
- migrate currently handled commands into explicit registrations without
  changing their working behavior;
- remove the broad business-command `[]` fallback;
- publish a deterministic report that identifies every unimplemented command
  and its owning domain.

It will not claim to implement all domains. Its deliverable is the trusted
backlog and enforcement mechanism required to complete them without losing
protocol coverage.
