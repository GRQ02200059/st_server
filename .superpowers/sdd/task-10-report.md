# Task 10 Report

## Status

DONE — strict compile gate only; conditioned-row execution remains pending.

## Scope

- Added an immutable, compile-once `SkillConditionInterpreter` with typed
  round, troop-ratio, hero, status, effect, and trigger-count predicates.
- Added exact `(skill ID, field, code)` ownership for every scoped nonzero
  condition. The independent test fixture
  `src/test/resources/skill-condition-plugin-owners.csv` contains the audited
  281-key literal; tests require exact set equality with the production
  catalog and 471 occurrences in the scoped graph.
- All 471 real conditioned rows intentionally remain explicit
  `SpecialConditionRequirement`s because authoritative decoding is
  unavailable. Each requirement carries its exact owner, field, and code and
  throws at runtime until that owner supplies tested semantics. This is not a
  claim of executable condition completion. Task 13 must implement exact
  plugin semantics; the final Task 15 coverage gate must enumerate owners and
  forbid every unresolved requirement. No unknown value defaults to true.
- The default rule interpreter now uses the graph-aware condition interpreter.
  Strict mode throws unresolved-condition errors; safe mode emits
  `UNSUPPORTED_CONDITION` and skips only the affected branch.
- Added defaulted active-effect view access and a side/trigger-isolated runtime
  counter contract needed by typed predicates. Task 10 does not integrate
  counters into `BattleEngine`: Task 12 must call
  `recordBattleTriggerOccurrence` only at `NORMAL_ATTACK_AFTER`,
  `DAMAGE_AFTER`, and `HURT_AFTER`. Successful active and pursuit executions
  are already counted after probability resolution by
  `SkillRuleInterpreter.recordSuccessfulExecution`; Task 12 must not record
  those triggers again.
  `recordTrigger` remains only as a deprecated compatibility alias.

## Inventory

- Default scope: 308 roots, 668 execution nodes, 1,935 details.
- `cast_condition`: 138 values, 298 rows.
- `precondition`: 25 values, 110 rows.
- `condition`: 34 values, 63 rows.
- Exact ownership catalog: 281 `(skill ID, field, code)` keys across 471 rows.
- Scoped unknown codes after compilation: 0.

## TDD Evidence

The focused RED failed in `compileTestKotlin` because
`SkillConditionInterpreter`, typed condition contracts, runtime trigger
history, and active-effect view capability did not exist.

After implementing the strict compile gate, predicates, ownership catalog,
and interpreter integration, the focused test passed. The gate proves that
every code is explicit and cannot fall through to `true`; it does not prove
that the 471 pending requirements are executable.

## Verification

Forced matrix:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.SkillConditionInterpreterTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleInterpreterTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --tests com.stzb.server.game.battle.skill.SkillRuntimeStateTest \
  --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --tests com.stzb.server.game.battle.skill.ControlEffectHandlersTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false \
  --no-build-cache --no-configuration-cache
```

Result: `BUILD SUCCESSFUL`; 119 tests, 0 failures, 0 errors, 0 skipped.
`git diff --check` also passed.

No server was started. Existing unrelated response/protocol/handler changes
were left untouched and excluded from the task commit.

## P2 Ownership and Cross-Task Contract Follow-up

- Checked in the independently audited 281-row owner fixture and asserted
  exact equality against both the production catalog and the 471 scoped graph
  occurrences.
- Renamed the explicit caller-facing event API to
  `recordBattleTriggerOccurrence`; the old name is a deprecated compatibility
  alias. `BattleEngine` remains unchanged.
- Forced Condition + Interpreter + Runtime verification passed: 56 tests,
  0 failures, 0 errors, 0 skipped. No server was started.
