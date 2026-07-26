# Task 4 Report: 配置驱动目标选择

## Status

DONE_WITH_CONCERNS

Commit: `9b383499` — `feat: interpret complete skill target selection`

## Scope

Implemented:

- `SkillTargetSelector` and compiled selectors.
- A `BattleEngine`-independent `SkillBattleView` injected through
  `SkillBattleContext`.
- Live hero state, metadata, accumulated damage, morale, attack range, and
  linked/current/previous target accessors.
- Lossless loading for `target_country`, `select_attri`, and
  `custom_select_flag`.
- Strict compile-time rejection of unknown target, select, attack, and
  attribute codes.

The entry-snapshot battle view is retained only as a source-compatible default.
The complete engine must inject a live implementation before executing
stateful selectors or metadata filters.

## TDD evidence

### RED

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD FAILED` at `:compileTestKotlin`, with expected unresolved
references for `SkillTargetSelector`, `SkillBattleView`, live hero
state/metadata, `battleView`, and the three newly required raw fields.

### GREEN

Final command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --tests com.stzb.server.game.battle.skill.SkillRuntimeStateTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL in 19s`; 37 tests passed with zero failures:

- `SkillTargetSelectorTest`: 13
- `BattleConfigRepositoryTest`: 7
- `SkillRuntimeStateTest`: 10
- `SkillRuleCatalogTest`: 7

## Implemented semantics

Ordinary behavior:

- `attack_type` controls self, ally, own-side, enemy, prior target, linked
  target, and current target candidate scopes.
- Enemy range uses live attack range and the existing server formula
  `5 - source.position - target.position <= range`.
- `select_type=1/9` uses live minimum/maximum attack, defense, strategy,
  speed, or troops.
- `3/4/5/6/7/8/33/34` implement farthest, base/middle/front, gender,
  random one-or-two, and all.
- `target_type` implements archer/infantry/cavalry combinations and
  rattan-armour/barbarian/elephant metadata filters.
- Candidates use stable client report-position order before every random draw,
  and draws remove selected entries.
- Defeated heroes are excluded unless the live view explicitly marks them
  targetable for a special effect.

Approved provisional stateful mappings, locked by exact tests:

- `11`: linked/triggering target.
- `900`: enemy with greatest accumulated damage dealt.
- `901`: ally with highest current morale.
- `907`: enemy inside live current attack range.
- `908`: enemies outside live current attack range.
- `3002`: triggering attack target plus adjacent enemy positions.

These mappings are explicit and never fall back to random selection. They must
be calibrated later against paper golden tests.

## Self-review

- No description strings are read at runtime.
- Missing metadata fails explicitly for metadata-dependent filters.
- Unknown selector families fail during `compile`.
- Live-view interfaces do not depend on `BattleEngine` or mutable engine maps.
- Existing unrelated protocol and handler edits were not modified or staged.
- `git diff --check` passes.
- The server was not started.

## Concerns

- The six approved special mappings are provisional until paper golden tests
  confirm exact official behavior.
- `canReceiveEffectsWhenDefeated` is an explicit live-view policy input because
  the CSV scope does not encode a sufficiently authoritative generic dead
  target rule.
- `target_country` is loaded and applied; the country-code-to-metadata mapping
  remains the responsibility of the future live battle-view adapter.

## Review-fix evidence

Review findings from `review-6532555a..9b383499.diff` were fixed with a
second RED/GREEN cycle.

### RED

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD FAILED` at `:compileTestKotlin` with the expected unresolved
contracts for `SkillTargetStateFilter`, `SkillBattleViewCapability`,
`MissingLiveBattleViewData`, `SkillEffectRule.skillHitRange`, strict live-view
accessors, and the raw detail loader.

### GREEN

Focused command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL in 21s`; 27 tests passed.

Mandated regression command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --tests com.stzb.server.game.battle.skill.SkillRuntimeStateTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL in 17s`; 44 tests passed with zero failures:

- `SkillTargetSelectorTest`: 19
- `BattleConfigRepositoryTest`: 8
- `SkillRuntimeStateTest`: 10
- `SkillRuleCatalogTest`: 7

### Corrected semantics

- Client `SkillTargetTypeShowView` structure locks `attack_type=21` to the
  complete own side including self; `11/13/23` exclude self; `24` includes
  self. Parameterized tests lock every supported attack code.
- `attack_type=113` selects across both sides excluding self and preserves
  configured group cardinality.
- `select_flag=1/2/3/99` maps to distinct typed live-view filter inputs.
  The client exposes the raw field but not an authoritative battle-state
  meaning, so no value is guessed, ignored, or defaulted true.
- Parent skill `hit_range` is threaded into every `SkillEffectRule` and drives
  ordinary enemy range. Provisional `907/908` explicitly use live normal
  attack range.
- The entry-snapshot view advertises only roster and entry-state capabilities.
  It exposes a separate `entryState` snapshot accessor; live `state`, metadata,
  history, state-filter, live morale, and live normal-range access throws
  `MissingLiveBattleViewData`.
- Special selector seeds `11/3002` pass the shared targetability, metadata,
  country, state, and range pipeline. `3002` uses configured `attack_max`.
- `select_type=34` returns the complete filtered scope independent of
  `attack_max`.
- A raw-loader fixture proves nonzero `custom_select_flag` is preserved.
- Client-position ordering controls ties and deterministic random/group draws.
