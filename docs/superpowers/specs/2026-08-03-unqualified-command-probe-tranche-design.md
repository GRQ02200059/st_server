# Unqualified Command Probe Tranche Design

- Date: 2026-08-03
- Active client: `9.2.4`
- Scope: the 18 command IDs whose request evidence changed in the
  unqualified-call scanner correction
- Decision: use the recommended option under the user's standing
  authorization to proceed without interactive confirmation.

## Goal

Create a complete risk audit for the 18 commands that gained request evidence
from line-start `Send(...)` calls, and export only the new literal read-only
requests for future official-server capture.

This work prepares capture. It does not send a command, import an old response
as 9.2.4 evidence, or add private-server response behavior.

## Evidence Boundary

The corrected inventory added request sources for:

```text
671, 885, 2628, 3611, 3674, 3801, 3864, 3990, 4000,
4091, 4203, 4204, 5140, 6130, 6156, 6220, 6260, 6291
```

Static source review establishes:

- Nine literal read-only queries.
- Three read-only queries that require current server, invitational, event, or
  operation arguments.
- Six writes that log telemetry, draw a reward, pray, edit a message, delete
  a message, or collect warehouse resources.

Cmd 671 has one historical send sample with payload `[]`. It may guide the
request payload only; its old response is not current 9.2.4 evidence. Cmd 6156
is already present in the added-command safe batch and must not be exported
twice.

## Options

### Selected: explicit tranche manifest using shared safety validation

Add a second manifest that covers all 18 IDs. Extend the existing validator
with an explicit-scope entrypoint that verifies each row against the active
inventory and reuses the same classification, evidence, payload, ordering,
and auto-probe invariants.

The existing added-since-baseline manifest remains unchanged. The CLI chooses
delta validation when `--baseline-inventory` is supplied and explicit
validation when it is omitted.

This preserves provenance and makes the exported eight-command batch
deterministic without pretending the other ten commands are safe to send.

### Rejected: unvalidated hand-written batch

This is smaller but bypasses name matching, classification, evidence,
explicit-null, and unsafe-auto-probe gates.

### Rejected: classify all 2,655 commands in one change

The final goal needs full coverage, but current evidence does not support
reliable request payloads and safety decisions for all commands in one
reviewable unit. The tranche is independently complete for the newly
recovered source boundary.

## Manifest

Create:

`tools/protocol/client-9.2.4-unqualified-command-probes.json`

Top-level shape:

```json
{
  "clientVersion": "9.2.4",
  "scope": "EXPLICIT",
  "commands": []
}
```

The exact classifications are:

```text
READ_ONLY_STATIC:
  671, 2628, 3611, 3801, 3864, 6130, 6156, 6220, 6291

READ_ONLY_CONTEXTUAL:
  3990, 4000, 5140

MUTATING:
  885, 3674, 4091, 4203, 4204, 6260
```

The automatic payloads are:

```json
[
  {"cmd": 671, "payload": []},
  {"cmd": 2628, "payload": ""},
  {"cmd": 3611, "payload": null},
  {"cmd": 3801, "payload": null},
  {"cmd": 3864, "payload": null},
  {"cmd": 6130, "payload": []},
  {"cmd": 6220, "payload": null},
  {"cmd": 6291, "payload": null}
]
```

Cmd 6156 is `READ_ONLY_STATIC`, carries literal payload `null`, and sets
`autoProbe=false` because the earlier batch already exports it.

The expected batch SHA-256 is:

```text
b6ee66e31ac96374bf744c54b1a73f2171240c8e6614f517498b69825dcb6ef5
```

## Validator

Add:

```python
def validate_explicit_probe_plan(manifest, current_inventory):
    ...
```

It must:

1. Require `scope == "EXPLICIT"`.
2. Require manifest `clientVersion` to match the active inventory.
3. Require sorted, unique integer IDs.
4. Require every ID to exist in the active inventory.
5. Require names to match the active inventory exactly.
6. Apply the existing classification, request-shape, evidence, reason,
   boolean, and payload invariants.
7. Return rows in manifest order.

Refactor shared row checks into one private helper so delta and explicit modes
cannot diverge.

The existing `validate_probe_plan` retains exact
`current IDs - baseline IDs` semantics.

## CLI

`--baseline-inventory` becomes optional:

- Present: run existing added-since-baseline validation.
- Absent: run `validate_explicit_probe_plan`.

The current added-command invocation remains backward compatible. Explicit
mode still requires `--current-inventory`, `--manifest`, and `--output`.

The CLI performs file validation and deterministic JSON export only. It has
no ADB, HTTP, WebSocket, or send dependency.

## Runtime Use

When the client reconnects naturally:

1. Keep passive autosave active.
2. Export both the original seven-command batch and this eight-command batch.
3. Send each command once, sequentially, with the original seven-command
   batch first.
4. Do not resend cmd 6156.
5. Stop on disconnect, Bridge error, timeout, or unexpected account-state
   mutation evidence.
6. Archive send and receive packets under 9.2.4 provenance before
   implementing responses.

No command is sent while `phoneConnected=false`.

## Tests

Python tests must prove:

- valid explicit manifests preserve explicit JSON null and export only
  `autoProbe=true` rows;
- wrong scope, version mismatch, unknown ID, name mismatch, duplicate ID,
  unsorted ID, invalid classification, and unsafe payload/auto combinations
  raise `ProbePlanError`;
- existing delta-manifest behavior remains unchanged;
- CLI delta mode still works with a baseline;
- CLI explicit mode works without a baseline;
- the real manifest covers exactly 18 IDs with counts
  `READ_ONLY_STATIC=9`, `READ_ONLY_CONTEXTUAL=3`, `MUTATING=6`;
- the real explicit batch contains the exact eight IDs and SHA;
- direct and module test entrypoints both pass.

No Kotlin test is required because this tranche changes no inventory or
server runtime behavior.

## Non-Goals

- Sending the batch before the game process reconnects.
- Adding contextual or mutating commands to unattended capture.
- Replacing the complete added-command manifest.
- Treating historical cmd 671 response data as current evidence.
- Implementing private-server responses without current official captures.
