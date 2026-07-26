# Task 5 Report: 效果冲突、叠加与生命周期

## Status

DONE_WITH_CONCERNS

## Scope

Implemented:

- `ActiveSkillEffect` with source/target, root/current skill, source skill
  kind, detail/effect IDs, client effect category and conflict group,
  strength, replacement policy, binding, stack and lifecycle state.
- `BattleEffectStore` with structured `apply`, `consumeHit`, `tick`, `clear`,
  and immutable `effectsFor` snapshots.
- Structured apply and lifecycle results that preserve replaced, expired, and
  explicitly removed effects for later battle-report projection.
- Validation that rejects unsupported replacement types, invalid stack state,
  and negative round/hit durations.

No handler or engine integration was added.

## TDD evidence

### RED

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD FAILED` at `:compileTestKotlin`, with the expected unresolved
references for `ActiveSkillEffect`, `EffectCategory`, `BattleEffectStore`,
`EffectApplyOutcome`, and `EffectTickBoundary`.

### GREEN

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --tests com.stzb.server.game.battle.BattleEffectStateTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL`; 17 tests passed with zero failures:

- `BattleEffectStoreTest`: 14
- `BattleEffectStateTest`: 3

### Self-review lifecycle RED/GREEN

An additional regression test changed an equal-strength effect from hit-based
to round-based duration.

RED command:

```bash
./gradlew test --rerun-tasks \
  --tests 'com.stzb.server.game.battle.skill.BattleEffectStoreTest.refresh adopts incoming lifecycle without creating accidental permanence' \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: one expected assertion failure because the first implementation
treated a missing incoming lifecycle dimension as permanent.

GREEN is included in the final 17-test command above. Refresh now adopts both
incoming lifecycle dimensions exactly.

## Locked semantics

- Conflict identity is exact target + client effect category + explicit client
  conflict group + source `SkillKind`. It never collapses effects by
  `BattleStatus`.
- `replaceType=0`: add one stack up to `maxStacks`; adopt the incoming
  lifecycle at the cap.
- `replaceType=1`: keep the existing conflicting effect and reject incoming.
- `replaceType=2`: stronger replaces, equal adopts the incoming lifecycle,
  weaker is rejected.
- `replaceType=3`: incoming always replaces the existing conflict.
- Round duration decrements only at the explicit `ROUND_END` boundary.
- Hit duration decrements only for the exact target/effect and optional exact
  source. `clearPerHit` expires the match on its first consumed hit.
- Clearing a bound effect also clears clearable siblings sharing exact target,
  source, and nonzero `bindFlag`; it does not cross target or source.
- Inputs are copied on apply. Query, apply, and lifecycle collections are
  unmodifiable, and all contained effects are detached copies.

## Self-review

- Existing `BattleEffectState` is unchanged and its regression suite passes.
- Zero-duration effects are rejected by `apply`; negative lifecycle values
  fail during model construction rather than becoming permanent.
- Expired/replaced/cleared effects are returned before internal removal loses
  their report metadata.
- Existing unrelated protocol, handler, and response edits were not modified
  or staged.
- `git diff --check` passes.
- The server was not started.

## Concerns

- The client CSV exposes raw `replace_type` values but does not document their
  official names. The four behaviors above are explicit and test-locked;
  paper-golden calibration may require remapping the raw values later.
- The store intentionally remains independent of the engine. A later task
  must decide precisely when engine events call round ticking, hit
  consumption, and clearing.
