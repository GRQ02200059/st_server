# Task 11 report

## Review follow-up

- Added typed `SkillTimingEvent.PreparationCompleted` with source, root/current
  skill identity, start/ready/completion rounds, trigger, and locked/reselected
  target information.
- Completion is emitted exactly once, immediately before accepted execution.
  It is absent before readiness and after cancellation or duplicate round calls.
- `CompleteTimingCoordinator` is strict by default. Invalid negative, backward,
  zero-boundary, and overflowing due timing throws
  `InvalidSkillTimingException` with skill/detail/root/trigger/specification
  context before queue mutation.
- Added explicit `CompleteTimingCoordinator.safe(...)`; it emits
  `INVALID_TIMING` and skips invalid work without partially persisting it.

## Verification

Forced with `--rerun-tasks`:

- `SkillTimingTest`
- `BattleSkillRuntimeTest`
- `SkillRuleInterpreterTest`
- `CoreEffectHandlersTest`
- `ControlEffectHandlersTest`
- `BattleEffectStoreTest`

Result: 125 tests, 0 failures, 0 errors, 0 skipped.

## Final strictness follow-up

- Unsupported/non-schedulable `BattleStateChange` enqueue requests now use the
  strict/safe invalid-timing path with the original change as specification.
  Strict mode throws `InvalidSkillTimingException`; safe mode diagnoses and
  skips, with the delayed queue unchanged.
- `attempt` now validates positive rounds and calculates/checks `readyRound`
  using `Long` before attempt counting, probability evaluation, success
  counting, or preparation mutation. Strict failures are atomic; safe failures
  diagnose and skip atomically.

Forced again with `--rerun-tasks`:

- `SkillTimingTest`
- `BattleSkillRuntimeTest`
- `SkillRuleInterpreterTest`
- `CoreEffectHandlersTest`
- `ControlEffectHandlersTest`
- `BattleEffectStoreTest`

Result: 128 tests, 0 failures, 0 errors, 0 skipped.
