# Task 13 Report

## Status

DONE for execution-plugin migration; conditioned-row semantics remain explicit Task 15 debt.

## Implemented

- Added immutable `SkillExecutionPlugin` and `SpecialSkillPluginRegistry` contracts with
  duplicate execution-owner rejection.
- Migrated `200036 辅王抑寇` from the narrow solo fallback to a real configured plugin:
  - front and middle receive two matching active-skill damage reductions;
  - allied successful active skills add 11 attack and 13 strategy, capped at five layers;
  - enemy successful active skills reduce defense and speed, capped at five layers;
  - immediate and prepared active completion paths both dispatch the response;
  - defeated plugin owners no longer react.
- Removed both `200001` and `200036` compatibility fallbacks from
  `DefaultCompleteSkillEngine`. `200001` now works through the general beneficial
  other-ally selector, which selects the source only when no teammate exists.
- Removed `LegacySkillCatalog` precedence from `BattleSkillRuntime`; configured
  battle execution remains authoritative. The legacy catalog is retained only as
  reference documentation and is not called by configured battle orchestration.
- Added hit-limited damage modifier lifecycle support so the two-hit reduction
  expires after exactly two matching active-skill hits.

## Review Fixes

- Prepared active skills now record a successful execution only when preparation
  completes. Starting or cancelling preparation does not consume an execution
  count; immediate active skills still record at execution time. This also makes
  `200036` layers correspond to completed active casts only.
- `200036` effect `352` now reuses `DefaultBattleValueCalculator` with the exact
  configured details `20003625/20003636`: `constant=16`, `intel_param=15`,
  strategy baseline `80`. Strategy `80` yields `16%`; strategy `180` yields `18%`.
- The beneficial `attack_type=11` solo fallback is restricted to the confirmed
  `20000101/20000102` details. Other beneficial other-ally selectors no longer
  silently target the caster.
- Execution coverage now uses an explicit non-declarative ownership catalog and
  each plugin declares `replacesConfiguredExecution`. The engine routing and
  report share that policy: replacement plugins bypass configured execution;
  non-replacement plugins expose a duplicate path and are reported as both
  missing required replacement ownership and duplicate execution.

## Coverage Truth

- Required execution plugin IDs: `1` (`200036`).
- Missing execution plugins: `0`.
- Duplicate execution owners: `0`.
- Explicit unresolved condition codes: `281`.
- Unresolved condition owner skills: `169`.

The 281 condition codes are still fail-closed `SpecialConditionRequirement`s.
They are reported separately and are not claimed as executable plugin coverage.
Task 15 must supply authoritative semantics or keep the completion gate red.

## TDD Evidence

- Initial plugin tests failed in `compileTestKotlin` because the registry,
  invocation, configured plugin and execution-plugin types did not exist.
- Engine integration then failed because `200036` had no plugin dispatch.
- Coverage reporting failed because no coverage type existed.
- The declarative `200001` regression produced a target-selector RED before
  beneficial other-ally fallback was implemented.

## Verification

Focused plugin / engine / legacy suite: 20 tests, 0 failures.

Battle and skill matrix:

```text
265 tests, 0 failures, 0 errors, 0 skipped
```

Forced full suite:

```text
474 tests, 0 failures, 0 errors, 0 skipped
BUILD SUCCESSFUL
```

`git diff --check` passed. The game server was not started.
