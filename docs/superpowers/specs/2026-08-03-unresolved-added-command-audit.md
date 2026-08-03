# Unresolved Added-Command Static Audit (9.2.4)

- Date: 2026-08-03
- Active client: `9.2.4`
- Baseline client: `9.2.2`
- Scope: the 9 IDs that 9.2.4 added since 9.2.2 and that the command
  inventory still classifies as `UNRESOLVED`.
- Decision: proceed under the user's standing authorization; make the
  recommended safety choice without interactive confirmation.

## Goal

Establish, from the normalized 9.2.4 decompiled source at
`/private/tmp/stzb-protocol-9.2.4.QJxxO8/decompiled`, whether any of the 9
`UNRESOLVED` added commands can be safely reclassified to a literal read-only
probe. This audit does not send a command, import an old response as 9.2.4
evidence, or add private-server response behavior.

## Commands Audited

```text
4359 IO_MOD_INVITE_TEAMMATE
4360 IO_MOD_REPLY_INVITE
4361 IO_MOD_TEAM_MANAGE
4362 IO_MOD_START_MATCH
4389 IO_MOD_CLAIM_NEWBIE_REWARD
6153 INVITATIONAL_2026_QUERY_RANK_LEADERBOARD
6154 INVITATIONAL_2026_GET_FAMILY_GROUP_LIST
8050 PSC_TASK_REWARD
8057 PSC_USE_ITEM
```

## Findings

### Group A — `SdzcOuterCmd` outer commands (4359, 4360, 4361, 4362)

- Declared only in `Game.Data.GamePlay/Tenth.Data/SdzcOuterCmd.cs:5-11` as
  outer-protocol sub-command constants.
- No `Send(4359..4362, ...)` call and no symbolic `SdzcOuterCmd.IO_MOD_*`
  reference exists anywhere outside that declaration file. The scanner
  therefore cannot resolve a request constructor, which is why they are
  `UNRESOLVED`.
- The outer-command family is dispatched through `SdzcNetUtil`
  (`Game.Data.GamePlay/Tenth.Data/SdzcNetUtil.cs`), whose entire surface is
  mutating or session-control (`SendMarch`, `SendRecruit`, `SendUseItem`,
  `SendShopBuy`, `SendExpedition`, `SendSelectSpawn`, etc.). By name the four
  IDs are invite / reply-invite / team-manage / start-match — team formation
  and matchmaking control, not read-only queries.
- Classification stays: 4359/4360/4361 `MUTATING`, 4362 `SESSION_CONTROL`.
  None is auto-probe eligible.

### Group B — constants with no code reference (4389, 6153, 6154, 8050, 8057)

- Each exists only as a `NetCommandDef` constant
  (`Game.Network/Tenth.Network/NetCommandDef.cs:3465/4511/4519/5311/5317`).
- No request constructor, no `Send`, and no symbolic use appears in any `.cs`
  file. There is no static basis for a payload shape or a side-effect
  guarantee.
- Names are decisive against auto-probing:
  - `4389 IO_MOD_CLAIM_NEWBIE_REWARD` grants a reward (mutating).
  - `8050 PSC_TASK_REWARD` grants a task reward (mutating).
  - `8057 PSC_USE_ITEM` consumes an item (mutating).
  - `6153 INVITATIONAL_2026_QUERY_RANK_LEADERBOARD` and
    `6154 INVITATIONAL_2026_GET_FAMILY_GROUP_LIST` read data but have no
    resolved request shape, so their arguments are unknown and cannot be
    fabricated.
- Classification stays `UNRESOLVED` for all five. None is auto-probe
  eligible.

## Conclusion

No `UNRESOLVED` added command can be promoted to `READ_ONLY_STATIC` or added
to an unattended probe batch on current static evidence. The reward/use/claim
commands are mutating; the outer commands are mutating or session control;
the two 2026 invitational reads have no resolvable request payload. Their
correct request shapes and responses must come from natural client capture,
not static inference.

The two exported safe batches remain unchanged:

```text
added-command batch SHA-256:
a9ac854a23fc93af25b03331e355e105d7b7ff369474b9ffe9ee92b768fa70aa
recovered-source batch SHA-256:
b6ee66e31ac96374bf744c54b1a73f2171240c8e6614f517498b69825dcb6ef5
```

## Non-Goals

- Sending any command while the game process is absent.
- Fabricating request payloads for unresolved reads.
- Auto-probing reward, item, claim, invite, or matchmaking commands.
- Implementing private-server responses without current official captures.
