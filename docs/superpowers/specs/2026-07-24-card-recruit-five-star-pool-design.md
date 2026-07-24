# Card Recruit Five-Star Pool Design

## Goal

Make the local development server return only five-star heroes from the
client-selected card pack, while keeping every recruit result compatible with
the client's fixed 1-10 card result layout.

## Client Contract

The client builds the effective pack ID as:

```text
childCfgID > 0 ? childCfgID : summonCfgID
```

For command `301`, the request fields are:

```text
[summonCfgID, summonUID, summonOpType, ..., childCfgID, ...]
```

The response card list is at index `1`. A single summon needs one card and a
non-single summon needs five cards.

For command `304`, the request includes `summonUID` at index `0` and
`quickCount` at index `4`. Its response card list is at index `7`; the result
counts array is at index `5`.

`CardSummonResultHelper` has fixed positions only for 1 through 10 cards.
The server must never return fewer than one or more than ten cards to the
quick-summon result page.

## Client Default Packs

`Tcfg_card_prob` defines the valid heroes for the packs opened by the login
snapshot:

- `281` has a direct pool of 99 heroes, including 11 five-star heroes.
- `801` is a parent pack and has no direct hero rows.
- `901` through `907` are `801` child packs. Their five-star pools contain
  8, 9, 8, 9, 8, 9, and 8 heroes respectively.

The existing `hero_table.csv` supplies each hero's `hero_type` and `quality`.
The local development-server five-star rule is exactly `quality == 4`.

## Server Change

`HeroCatalog` remains the source of valid hero troop types and gains:

- parsed hero quality;
- a fixed mapping of client pack IDs `281`, `901`-`907` to the corresponding
  client `Tcfg_card_prob` hero IDs;
- functions that return five-star candidates for a requested pack.

When no child pack is supplied for parent pack `801`, the server uses the
deduplicated five-star union of `901` through `907`. Unknown packs use the
same default union so a malformed request cannot produce an empty card list.

`GameResponses.cardRecruit` receives `summonCfgId`, chooses the effective
pack, and returns one or five candidates. `quickCardRecruit` receives the
pack resolved from the player's `summonUid`; it clamps the client-supplied
count to `1..10` and makes the result-count array sum to the returned number
of cards.

`GameServerHandler` passes `body[0]` to command `301` and resolves default
login snapshot UIDs:

```text
userId * 100 + 1 -> 801
userId * 100 + 2 -> 281
```

## Scope

This change covers only commands `301` and `304`, their catalog selection,
and regression tests. It does not change draw probabilities, resource
consumption, or battle logic.

## Verification

Tests will prove:

- each configured pack returns only its own `quality == 4` heroes;
- `801` falls back to its child-pack five-star union;
- command `301` returns one or five cards;
- command `304` returns at most ten cards and a count array matching those
  cards;
- recruited hero notifications continue to parse both response shapes.
