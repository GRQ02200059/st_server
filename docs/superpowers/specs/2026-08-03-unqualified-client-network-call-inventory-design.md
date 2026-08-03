# Unqualified Client Network Call Inventory Design

- Date: 2026-08-03
- Active client: `9.2.4`
- Decompiled assembly version: `1836157`
- Decision: use the recommended option under the user's standing
  authorization to proceed without interactive confirmation.

## Goal

Correct the 9.2.4 command inventory scanner so it records game network calls
written as line-start `Send(...)` and `AddObserver(...)`, not only calls with
an explicit receiver such as `base.Send(...)` or
`NetObserver.GetInstance().AddObserver(...)`.

This work improves command enumeration and direction evidence only. It does
not send a command, claim a response shape, or add private-server behavior.

## Evidence

The current scanner requires a dot before `Send` and `AddObserver`. Static
review of the installed 9.2.4 client found:

- 27 missing request references across 18 command IDs.
- 56 missing receive references across 49 command IDs.
- Ten commands currently inferred as `SERVER_PUSH` that have literal client
  request constructors, including cmds 2628, 3674, 3801, 3864, 3990, 4203,
  4204, 6156, 6260, and 6291.
- Thirty-eight current `CLIENT_REQUEST` commands that also have line-start
  observer registrations and should therefore be `DUPLEX`.
- Non-game `Safaia` code also contains line-start `Send(16, ...)` and
  `Send(24, ...)`; a global relaxed pattern would contaminate game evidence.
- Dynamic wrapper calls such as `Send(cmd, ...)` do not identify a concrete
  command. Existing interface method declarations already account for those
  wrappers in `unresolvedRequestSources`; the new rule must not duplicate
  them.

No runtime client or official-server command was used to obtain this
evidence.

## Options

### Selected: game-path-gated line-start patterns

Keep the existing receiver-qualified patterns. Add separate multiline
patterns for line-start `Send(...)` and `AddObserver(...)`, and apply them
only when the first decompiled path component is `Game` or begins with
`Game.`.

Only numeric IDs and command constants that resolve through
`NetCommandDef.cs` are recorded. Unknown first arguments are ignored by the
new patterns because they are delegation wrappers, not command evidence.

This captures inherited helper calls in UI and game-data classes while
excluding `Safaia` and avoiding method declarations such as
`void ISettingUI.Send(...)`.

### Rejected: globally accept unqualified calls

This would add unrelated `Safaia` transport messages to game command IDs 16
and 24 and would weaken inventory provenance.

### Rejected: introduce a complete C# parser

The required syntax is limited and deterministic. A parser dependency would
add installation, compatibility, and traversal complexity without improving
the concrete line-start evidence in this client build.

## Scanner Design

Add two patterns:

```python
UNQUALIFIED_SEND_RE = re.compile(
    r"^[ \t]*Send(?:<[^\r\n]+?>)?[ \t]*\([ \t]*"
    r"(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
    re.MULTILINE,
)
UNQUALIFIED_OBSERVER_RE = re.compile(
    r"^[ \t]*AddObserver[ \t]*\([ \t]*"
    r"(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
    re.MULTILINE,
)
```

Add:

```python
def _is_game_source(root, path):
    top = path.relative_to(root).parts[0]
    return top == "Game" or top.startswith("Game.")
```

For each C# file:

1. Run all existing request and receive patterns unchanged.
2. If `_is_game_source` is true, run the unqualified patterns.
3. Resolve the first argument against numeric literals or the constant map.
4. Add a source reference only when resolution succeeds.
5. Do not add unresolved entries for the new unqualified patterns.
6. Continue storing references in sets and sorting them, so duplicate paths
   cannot change deterministic output.

## Versioned Resource

Regenerate only:

`src/main/resources/protocol/client-9.2.4-command-inventory.json`

from:

`/private/tmp/stzb-protocol-9.2.4.QJxxO8/decompiled`

without a capture index. Preserve the 9.2.2 resource byte-for-byte.

The expected 9.2.4 evidence delta is:

```text
request refs added: 27 across 18 IDs
receive refs added: 56 across 49 IDs
unresolved request sources: 96
command IDs: 2655
capture send/receive counts: all zero
```

The expected raw inferred directions are:

```text
CLIENT_REQUEST=1751
DUPLEX=345
SERVER_PUSH=559
```

The expected regenerated 9.2.4 resource SHA-256 is:

```text
797fc9c4cf0dcf13afcaeccfb8618bdc5f859e1beccf427bf09c1687a09c3c26
```

The 9.2.2 SHA-256 remains:

```text
b18cd8aa81a0023d847eb18cb086e47b54e7ad32c064b077e654aed6df15af59
```

## Runtime Impact

The command ID set, names, capture counts, and contract statuses do not
change. Inventory-derived directions change only when an explicit contract
override does not already own the direction.

Cmd 6156 becomes a source-proven `CLIENT_REQUEST`, matching the existing
read-only probe manifest. Cmd 6128 gains a receive source and becomes
`DUPLEX`. Existing explicit contracts remain authoritative.

Coverage status counts remain:

```text
OBSERVED_SHAPE=41
PROVISIONAL=183
REJECTED=22
UNIMPLEMENTED=2409
```

## Tests

Python scanner tests must prove:

- line-start numeric and symbolic `Send` calls under `Game.*` are requests;
- line-start numeric and symbolic `AddObserver` calls under `Game.*` are
  receives;
- nested generic line-start sends remain supported;
- the same syntax under `Safaia` is ignored;
- method declarations and dynamic `Send(cmd, ...)` wrappers do not create
  resolved or duplicate unresolved evidence;
- existing receiver-qualified, same-line, multiline, raw, and unresolved
  behavior remains intact;
- both direct and module test entrypoints pass.

Kotlin registry tests must prove active cmd 6156 is `CLIENT_REQUEST` and cmd
6128 is `DUPLEX`, while the active inventory still contains 2,655 IDs.

## Non-Goals

- Rewriting the scanner as a C# parser.
- Changing the historical 9.2.2 inventory.
- Treating a dynamic wrapper argument as a concrete command.
- Capturing official responses while the game process is absent.
- Adding or changing private-server command responses.
