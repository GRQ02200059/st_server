# Contextual Added-Command Argument Audit (9.2.4)

- Date: 2026-08-03
- Active client: `9.2.4`
- Scope: the 9 `READ_ONLY_CONTEXTUAL` commands across the two probe manifests
  (added-since-baseline and recovered-source).
- Decision: proceed under the user's standing authorization; make the
  recommended safety choice without interactive confirmation.

## Goal

Determine, from the normalized 9.2.4 decompiled source at
`/private/tmp/stzb-protocol-9.2.4.QJxxO8/decompiled`, whether any
`READ_ONLY_CONTEXTUAL` command can be safely sent during unattended capture
without guessing a live argument or changing account state. This audit sends
no command and adds no private-server response behavior.

## Commands, Send Sites, and Argument Provenance

| Cmd | Name | Send site | Wire args | Argument source | Auto-safe? |
|---:|---|---|---|---|---|
| 2634 | `NZZZ_CHECK_SHEGU_CONNECTED_WID` | `ArmyMoveUI.cs:809` | `[mWid]` | live map world ID being targeted for EXPEDITION/PILLAGE (`ArmyMoveUI.TargetPos`) | No |
| 4387 | `IO_MOD_GET_HERO_SKILL_RECOMMEND` | `SdzcOutSkillRecommendData.cs:14` | `[heroConfigId]` | caller-supplied hero config ID | No |
| 4991 | `GEAR_HERO_RECOMMEND` | `TreasureDataManager.cs:189` | `[heroIdU:Int64]` | live owned-hero unique ID | No |
| 6128 | `TEAM_INVITATIONAL_QUERY_REGION_BET` | `InviteSeasonTeamBornMapData.cs:266` | `[runServerId]` | caller-supplied invitational run-server ID | No |
| 6131 | `TEAM_INVITATIONAL_QUERY_REGION_BET_TEAMS` | `InviteSeasonTeamBornMapRight.cs:209` | `[mSelectStateId, SelectedRunServerId]` | UI-selected state + run-server (defaults to 0, sometimes hardcoded `7941`) | No |
| 6157 | `INVITATIONAL_2026_SEARCH_FAMILY` | `InviteSeasonFamilyList.cs:855` | `[1, searchText]` | non-empty user-typed search text (empty text is blocked client-side) | No |
| 3990 | `FAMILY_INVITATIONAL_GET_WORLD_EVENT` | `InviteSeasonGameMapData.cs:1749` | `[InviteID]` | current invitational season ID (`InviteID` property) | No |
| 4000 | `FAMILY_INVITATIONAL_GET_SEASON_DATA` | `InviteSeasonGameMapData.cs:1734-1767` | `[InviteID, op]` / `[InviteID, op, eventId]` | current invitational ID + operation code (1/2/3/5) | No |
| 5140 | `SCHOOL_INVITATIONAL_GET_SEASON_DATA` | `InviteNewPaperData.cs:168` | `[serverId, 14]` | caller-supplied server ID + operation code | No |

## Findings

Every argument is drawn from live session state, not from a static literal:

- **Live map / hero state (2634, 4387, 4991):** the world ID is the currently
  targeted land during an army operation; hero IDs are the currently selected
  or owned hero. Sending without a real target queries nothing meaningful and
  cannot be fabricated safely.
- **Invitational season identity (3990, 4000, 6128, 6131, 6157, 5140):** all
  require a live `InviteID`, run-server ID, state ID, or user search text.
  `InviteID` resolves at runtime to `0` (campus mode) or `1` (non-campus)
  from the live `mIsInviteCampus` flag (`InviteSeasonGameMapData.cs:147-158`),
  so its correct value still depends on the account's active invitational
  mode rather than being a fixed literal. `SelectedRunServerId` defaults to
  `0` and is only replaced by a UI selection (with an observed hardcoded
  fallback of `7941` at `InviteSeasonCampusTeamList.cs:103-106` that is
  season/phase-specific, not a stable literal). Operation codes are
  meaningful only paired with a live season ID.

None of these can be promoted to `READ_ONLY_STATIC` or added to the unattended
auto-probe batch. Guessing an `InviteID`, `runServerId`, `wid`, or `heroIdU`
would either return an empty/typed error shape (useless as parity evidence) or
touch season/map state we must not perturb.

## Capture-Time Prerequisites (when the client reconnects)

For each contextual command, the only safe path is to extract its argument
from passively captured traffic or live Bridge state, then send once:

- 3990 / 4000: read the active `InviteID` from a naturally captured
  invitational packet before sending; use only observed operation codes.
- 6128 / 6131: read `runServerId` / `stateId` from a naturally captured
  region-bet or born-map packet.
- 6157: only reproducible from a real user search; not auto-probeable.
- 2634 / 4387 / 4991: read the live `wid` / hero ID from a captured army-move
  or hero panel packet.
- 5140: read the live `serverId` from a captured school-invitational packet.

All of these depend on `phoneConnected=true` and naturally emitted packets.
They remain manual/contextual and are excluded from the unattended batch.

## Conclusion

The two exported auto-probe batches remain correct and unchanged:

```text
added-command batch SHA-256:
a9ac854a23fc93af25b03331e355e105d7b7ff369474b9ffe9ee92b768fa70aa
recovered-source batch SHA-256:
b6ee66e31ac96374bf744c54b1a73f2171240c8e6614f517498b69825dcb6ef5
```

No contextual command is auto-probe eligible on static evidence. Their
correct arguments must come from natural client capture.

## Non-Goals

- Sending any command while the game process is absent.
- Fabricating a live `InviteID`, run-server, world, hero, or search argument.
- Adding contextual commands to the unattended capture batch.
- Implementing private-server responses without current official captures.
