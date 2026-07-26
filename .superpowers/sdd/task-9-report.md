# Task 9 Review Fix Report

## Status

DONE

## Scope

- The brief's literal expands to exactly **38 unique effect IDs**, not 40:
  `0, 77, 81, 82, 83, 88, 111, 112, 113, 114, 118, 121, 122, 123,
  125, 127, 129, 130, 131, 141, 149, 151, 152, 153, 161, 171, 181,
  199, 200, 210, 231, 261, 281, 313, 404, 407, 408, 409`.
  Tests assert this independent literal, its count, the handler bindings, and
  its intersection with the real scoped graph.
- Referenced details now execute in guarded `(skillId, detailId)` frames with
  a maximum depth, complete attempted paths, and `finally` unwinding.
  Effects `151`, `153`, and `408` retain the invoker's selected targets;
  `153` also propagates its typed attribute-scaled value through referenced
  child wrappers. `152` clears the exact referenced detail/effect pair.
- Every non-special meta effect emits a typed operation intent with a
  repository-free `MetaEffectParameters` snapshot containing all raw
  `SkillDetailConfig` fields plus derived rule/binding fields. Effect `113`
  uses typed intelligence scaling. Real rows `21091503` and `21229401`
  assert exact mapping and scaling.
- Retriggers explicitly own child probability as
  `CONFIGURED_CHILD`: each uncapped child attempt rolls once, only successful
  executions increment counters, and a capped skill does not skip later skills.
- Effect `313` consumes by exact target, effect, source, and detail through the
  atomic detail-qualified `BattleEffectStore.consumeHit` API.
- Strict interpreter mode still throws. Safe mode catches recoverable
  rule/selector/condition/reference/recursion failures, appends and emits a
  `SkillExecutionDiagnostic` with skill/detail/effect/trigger and full
  reference or skill dependency path, skips that branch, and continues.
  Fatal `Error` values are not swallowed.
- Tests cover root/source/targets/order, skill and detail stack unwinding,
  reference cycles, `151`/`153` wrapper propagation, scaling differences,
  probability/counters, detail-specific consumption, exact binding set, real
  row mapping, safe continuation, and complete diagnostic paths.

## TDD Evidence

The first focused RED failed in `compileTestKotlin` on the deliberately missing
detail-qualified store API, guarded detail frames, reference mode/scaling
fields, exact referenced-effect metadata, and lossless typed parameters.

Subsequent focused REDs demonstrated:

- `153` through a referenced child wrapper produced one unscaled target instead
  of two scaled preselected targets.
- ordinary `151` wrapper propagation dropped the invoker's targets;
- safe reference- and child-cycle diagnostics lost their complete paths.

Each regression passed after its corresponding minimal implementation.

## Final Verification

Forced command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.SkillRuleInterpreterTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest \
  --tests com.stzb.server.game.battle.skill.SkillRuntimeStateTest \
  --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --tests com.stzb.server.game.battle.skill.ControlEffectHandlersTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false \
  --no-build-cache --no-configuration-cache
```

Result: `BUILD SUCCESSFUL`; **144 tests**, 0 failures, 0 errors, 0 skipped:

- `SkillRuleInterpreterTest`: 30
- `SkillRuleCatalogTest`: 9
- `BattleEffectRegistryTest`: 9
- `SkillRuntimeStateTest`: 10
- `SkillTargetSelectorTest`: 19
- `CoreEffectHandlersTest`: 21
- `ControlEffectHandlersTest`: 16
- `BattleEffectStoreTest`: 30

Scoped `git diff --check` also passed.

## Self-review

- No server was started.
- Existing unrelated response/protocol/handler worktree edits were left
  untouched and excluded from the scoped commit.
- Existing compiler warnings and the Gradle 9 deprecation notice are unchanged
  and outside Task 9.
