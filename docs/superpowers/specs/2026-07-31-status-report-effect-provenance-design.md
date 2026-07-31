# Status Report Effect Provenance Design

## Goal

Preserve the configured effect identity when a battle status is applied so the
client report can emit the same status action and effect ID as an official
report. The representative official report must encode the command-phase
`DISARM` and `HESITATION` applications as `0s...,752` and `0s...,702`, rather
than deriving `0t...,552` and `0t...,502` from the coarse status enum.

## Evidence

Across the 28 readable reports under `assent/cfg/paper`, effect IDs overlap
between `0s` and `0t`. Examples include `702`, `752`, `714`, `515` and the
damage-modifier IDs. Therefore neither effect polarity nor effect ID alone can
select between `0s` and `0t`.

`BattleEvent.StatusApplied` records a successful state transition. It should
project to `0s`. The exact meaning of `0t` remains a separate lifecycle
question and must not be guessed from an applied-status event.

## Event Model

Add an optional `effectId` to `BattleEvent.StatusApplied`.

- `effectId` is the configured battle effect identity, such as `702` or `752`.
- It is distinct from `status`, which is the normalized engine behavior such
  as `HESITATION` or `DISARM`.
- It defaults to `null` so legacy and hand-authored event producers remain
  source compatible.
- Complete-skill producers that already own a `PersistentEffectSpec`,
  `BattleStateChange`, or scheduled effect must populate it.

The field is also included in the structured JSON battle report so downstream
consumers do not lose the provenance after engine resolution.

## Client Projection

For a round-zero `StatusApplied`:

1. Ignore stat-change statuses already represented by a full `StatChanged`
   action.
2. Emit action `0s` because the event represents a successful application.
3. Use `event.effectId` as the second parameter.
4. If a legacy event has no configured effect ID, fall back to the existing
   normalized status mapping.

`0t` is not emitted by `StatusApplied`. A future change may map verified
blocked, resisted, conflicted, or otherwise ineffective lifecycle events to
`0t`; that work is outside this design.

## Compatibility and Failure Handling

- Existing constructors continue compiling because the new field is optional.
- Battle resolution behavior is unchanged; this is provenance propagation and
  report projection only.
- An absent effect ID does not fail report generation. The fallback keeps
  synthetic tests and legacy runtime paths working.
- No skill-ID-specific lookup or adapter hardcoding is introduced.

## Verification

Tests are added before production changes:

1. A projector test requires an applied `DISARM` with `effectId=752` to encode
   as `0s...,752`, and verifies no `0t` is produced.
2. A complete-engine test verifies configured persistent effects retain their
   effect ID in `StatusApplied`.
3. A report-codec test verifies the structured event contains `effectId`.
4. The representative official-paper regression verifies generated
   preparation no longer has an extra `0t` family and contains the configured
   `702/752` applications.
5. Existing focused battle-report tests and the full test suite are rerun.

The existing unrelated `CompleteSkillCoverageTest` baseline failure remains
reported separately if its 18 unknown condition codes are still present.

## Out of Scope

- Defining the exact `0t` lifecycle semantics.
- Correcting target selection differences between local and official battle
  execution.
- Decimal attribute precision.
- Reducing the remaining `ja` count difference.
