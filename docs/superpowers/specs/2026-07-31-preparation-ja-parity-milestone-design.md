# Preparation JA Parity Milestone Design

## Goal

Move the representative generated report toward exact parity with the real
report under `assent/cfg/paper`, using the complete five-field `ja` tuple as
the acceptance unit.  The final goal remains exact paper parity; this
milestone removes the two differences whose engine semantics are already
verified.

The representative fixture is:

`assent/cfg/paper/11/cap_20260312014510506_0000000b_zlib.json`

Its preparation section contains 25 `ja` actions.  The current deterministic
generation contains 75.

## Verified Root Causes

### Round-zero `201006` listener leakage

Fifty-six generated-only actions use derived source `223006`: fourteen each
for effects `522`, `524`, `531`, and `533`.  They are produced when
`tongchouHurtResult` handles damage-like state outputs during preparation.

`201006` is a post-hurt listener.  Official reports initialize its skill in
preparation but do not emit `223006` modifiers until combat.  Round zero must
therefore not execute this listener.  The guard belongs in the skill engine,
not in the report projector, so preparation cannot pollute combat state.

### Missing troop feature modifiers

The reconstructed formation already contains troop feature sources `296132`
and `296232`, but `BattleFormationCalculator` only projects modifier semantics
for `296105`.  The official fixture contains:

- `296132`: effects `531` and `533`, amount `8`;
- `296232`: effects `531` and `533`, amount `8`.

Both sources apply to their owning hero.  They are damage-dealt modifiers and
must be represented both in preparation projection and runtime modifiers.

## Expected Milestone

After removing 56 invalid `223006` actions and adding the four missing troop
feature actions, deterministic generation contains 23 preparation `ja`
actions:

- no generated preparation action has source `223006`;
- all four official `296132/296232` tuples exist exactly;
- the existing matching `296105`, `400026`, and `400049` tuples remain;
- no report-projector filtering or count truncation is introduced.

The remaining two-count gap and tuple differences are intentionally visible:

- `200198`: target selection and amount differ;
- `200204`: target selection and amount differ;
- `200773`: one target per effect is missing and amount sign/value differs.

These remaining skill semantics form the next parity milestone.  Reaching 23
is not completion of the overall paper-parity goal.

## Tests

1. Add an integration regression showing real combat hurt at round one still
   applies `223006` (the existing test remains authoritative).
2. Add a round-zero regression showing identical damage application does not
   create `223006` effects or modifier events.
3. Add a team-builder test for troop sources `296132/296232`, requiring
   effects `531/533`, amount `8`, self target, and a runtime
   `DamageDealtPercent(+8)` modifier.
4. Extend the official fixture test to parse `ja` tuples and require official
   count `25`, generated count `23`, no `223006`, and exact presence of the
   four troop-feature tuples.
5. Run focused tests, the representative fixture test, then the full suite.

## Compatibility

- Client action shape is unchanged.
- Round-one and later `201006` behavior is unchanged.
- Only verified troop feature source IDs gain runtime and preparation
  modifiers.
- Existing dirty-worktree changes remain uncommitted unless they are part of
  the user's current implementation work.
