# JA Semantic Difference Audit Design

## Goal

Turn the representative report's `ja` count difference from an aggregate
number into a deterministic semantic diff. The audit must identify exactly
which modifier projections are missing, extra, or repeated before any battle
behavior or client projection is changed.

The representative baseline is 25 official `ja` actions and 75 generated
actions. This baseline is evidence to explain, not a target to reach through
adapter-side deletion.

## Scope

This change delivers:

- a reusable parser and multiset diff for official and generated `ja` actions;
- source-family classification for static and skill modifiers;
- lifecycle provenance on generated modifier events;
- a deterministic characterization test and updated audit documentation.

It does not alter target selection, modifier application, stacking, refresh,
replacement, or report projection policy. Those changes require a follow-up
design based on the resulting diff.

## Semantic Modifier Key

Each projected `ja` action is represented as:

```text
sourcePosition
sourceId
targetPosition
effectId
amount
```

The diff uses this complete five-field tuple as a multiset key. A set is not
sufficient because repeated identical actions are themselves part of the
problem.

The source is classified without assigning unverified business names:

- `STATIC`: source IDs in the verified preparation/config ranges
  `291000..299999`, `400000..459999`;
- `SKILL`: source IDs in `200000..289999`;
- `OTHER`: every remaining source ID.

Classification is diagnostic metadata only and does not affect equality.

## Difference Model

`OfficialModifierDiff` exposes:

- total official and generated counts;
- counts grouped by source family;
- `officialOnly`: positive multiset difference `official - generated`;
- `generatedOnly`: positive multiset difference `generated - official`;
- grouping by `(sourceId, effectId)` for concise root-cause reporting.

All collections use stable sorting by the five tuple fields. The test must be
deterministic and must not rely on console output.

The representative test stores a reviewed snapshot of grouped differences.
The snapshot is explicitly a characterization baseline, not an assertion that
the generated behavior is correct. Later fixes update it only when the
semantic diff changes intentionally.

## Modifier Lifecycle Provenance

The engine currently receives `EffectApplyOutcome` from `BattleEffectStore`
but reduces it to a Boolean before producing `BattleStateOutput.ModifierApplied`.
Preserve enough provenance to distinguish causes:

- replace that internal output with
  `BattleStateOutput.ModifierApplication(change, outcome)`, emitted for both
  accepted and rejected attempts;
- add `detailId: Int` and `applyOutcome: EffectApplyOutcome` to
  `BattleEvent.ModifierApplied`;
- populate both from `DamageModifierChange.detailId` and the actual store
  result;
- include both fields in structured JSON reports.

`ClientBattlePreparationEventProjector` continues to emit the same `ja`
shape. Lifecycle fields are diagnostic inputs and are not used to suppress
actions in this design.

## State Applier Boundary

Change `applyBehavior` to return `EffectApplyResult` rather than `Boolean`.
Callers use `outcome != REJECTED` for their current acceptance behavior.
`DamageModifierChange` always adds a `ModifierApplication` output containing
the exact outcome. `CompleteSkillEngine` converts only accepted applications
to the existing public `BattleEvent.ModifierApplied`; rejected attempts stay
internal to the state-application result. Existing stat and persistent-effect
semantics remain unchanged.

This keeps lifecycle truth at the layer that owns it and avoids reconstructing
outcomes later from active-effect snapshots.

## Tests

Tests are written before each production change:

1. A `BattleStateChangeApplierTest` requires modifier output to distinguish
   `APPLIED`, `REFRESHED`, and `REJECTED`.
2. A `BattleReportCodecTest` requires `detailId` and `applyOutcome` in JSON.
3. Existing client projection tests verify that lifecycle provenance does not
   change the five-parameter `ja` action.
4. `OfficialModifierDiffTest` reconstructs the representative official
   fixture, resolves with `FixedBattleRandom(0)`, and verifies:
   - official total 25;
   - generated total 75 at the characterization point;
   - exact stable source-family totals;
   - exact grouped official-only and generated-only differences;
   - a second resolution produces the same diff.

The first audit run is expected to reveal the exact grouped snapshot. That
snapshot is reviewed against the official action stream before being committed
to the test and documentation.

## Error Handling

- Parsing a `ja` action with a width other than five parameters fails with the
  raw action in the message.
- Non-integral fields fail parsing; all verified `ja` fields are integral.
- A rejected modifier does not produce `BattleEvent.ModifierApplied`, matching
  current behavior, but it does produce an internal
  `BattleStateOutput.ModifierApplication` with outcome `REJECTED`.
- Unknown source ranges are classified as `OTHER`; they are not dropped.

## Compatibility

- Client action encoding is unchanged.
- Battle resolution results and active modifiers are unchanged.
- Structured JSON gains `detailId` and `applyOutcome` for modifier events.
- No skill-specific hardcoding or count-based truncation is introduced.
- The user's existing dirty-worktree changes remain uncommitted and are not
  mixed into design-document commits.

## Follow-up Decision

After the characterization snapshot is available, choose the largest verified
source of surplus:

- target-selection divergence;
- repeated lifecycle reporting;
- static/config source expansion;
- conflict, replacement, or stacking semantics.

Only that root cause receives the next behavior-changing design. Multiple
independent causes are handled as separate follow-up plans.
