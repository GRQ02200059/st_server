# Task 5 Report: 效果冲突、叠加与生命周期

## Status

DONE

The initial implementation was recorded as `DONE_WITH_CONCERNS` before client
behavior was calibrated. That historical status is superseded by the review
and final-fix evidence below.

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

- Conflict identity is exact target + client effect category + explicit
  nonzero client conflict group. Raw policies 0 through 2 additionally require
  the same normalized raw skill type; raw policy 3 conflicts across skill
  types. It never collapses effects by `BattleStatus`.
- A zero conflict group is unset. Its fallback identity additionally contains
  detail ID, effect ID, and exact origin so unrelated effects remain isolated.
- Exact origin is source hero + root skill ID + current skill ID + normalized
  skill kind + raw source skill type. Detail and effect IDs do not participate
  in exact origin, so the same origin can stack or refresh across those IDs
  when the explicit conflict group is nonzero.
- `replaceType=0`: add one stack up to `maxStacks`; adopt the incoming
  lifecycle at the cap, but only for the exact origin.
- `replaceType=1`: keep the existing conflicting effect and reject incoming.
- `replaceType=2`: stronger replaces, equal adopts the incoming lifecycle,
  weaker is rejected; equal refreshes only for the exact origin.
- `replaceType=3`: keep the existing conflict and reject incoming across
  normalized skill types.
- Raw skill types 1 through 4 normalize to passive, command, active, and
  pursuit respectively. Unknown raw types and mismatched kind/raw pairs cannot
  create active effects; raw type 14 therefore remains repository metadata
  until it gains an explicit supported kind.
- Aggregate stack strength is store-owned. Callers construct effects from
  per-effect strength and stack count but cannot inject an arbitrary aggregate;
  detached snapshots preserve the store-computed aggregate.
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

- The store intentionally remains independent of the engine. A later task
  must decide precisely when engine events call round ticking, hit
  consumption, and clearing.

## Review fixes

### Raw evidence

- `stzbBattleSimulator-main/src/battle/battleHero.js` around lines 976-1028
  defines state conflict limits as `1 = same-type non-stacking`,
  `2 = strongest value wins`, and `3 = any-type non-stacking`. Its stack
  branch requires the exact source skill object and exact source hero, then
  adds the incoming value.
- The client `skill_effect_table.csv` contains every raw `replace_type` from
  0 through 3. Type 3 is now rejection/non-stacking rather than unconditional
  replacement.
- `skill_table.csv` contains raw `skill_type=14` and the scoped graph reaches
  it. `rawSkillType` is now preserved by `SkillBattleConfig`, `SkillRule`, and
  repository/rule metadata. `ActiveSkillEffect` accepts only normalized raw
  types 1 through 4 whose supplied kind matches that normalization.
- Every one of the 12,694 rows in `skill_detail_table.csv` has
  `buff_type=0`. Zero is therefore treated as unset: its fallback conflict
  identity includes exact detail/effect and origin rather than forming one
  global conflict group.

### Review TDD RED

Lifecycle command:

```bash
./gradlew test --rerun-tasks \
  --tests 'com.stzb.server.game.battle.skill.BattleEffectStoreTest.invalid negative or zero lifecycle values fail fast' \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: expected assertion failure because zero rounds/hits were accepted by
model construction.

Store regression command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: expected compile failure for missing `sourceSkillType` and
`effectiveStrength`.

Raw type command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: expected compile failure for missing `rawSkillType` on config and
rule models.

### Review GREEN

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --tests com.stzb.server.game.battle.BattleEffectStateTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL`; 45 tests passed with zero failures:

- `BattleEffectStoreTest`: 25
- `BattleEffectStateTest`: 3
- `SkillRuleCatalogTest`: 8
- `BattleConfigRepositoryTest`: 9

The review regressions cover the full stronger/equal/weaker policy matrix,
same versus different hero/skill/type origins, aggregate stack strength,
source-qualified clear and hit consumption after replacement, non-global
zero conflict groups, lossless raw types, `UNKNOWN` rejection, and
construction-time rejection of zero lifecycle values.

## Updated status

DONE

## Final fix wave TDD

### RED

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD FAILED` with four expected regression failures:

- same nonzero-conflict origin did not stack across detail/effect IDs;
- same nonzero-conflict origin did not refresh across detail/effect IDs;
- mismatched known and unsupported raw skill types were accepted;
- aggregate strength was exposed as a public constructor parameter.

### GREEN

The same forced command completed with `BUILD SUCCESSFUL`; all 28
`BattleEffectStoreTest` tests passed. The final mandated four-suite result is
`BUILD SUCCESSFUL`; 48 tests passed with zero failures:

- `BattleEffectStoreTest`: 28
- `BattleEffectStateTest`: 3
- `SkillRuleCatalogTest`: 8
- `BattleConfigRepositoryTest`: 9
