# Task 8 Report: Control, immunity, and action effects

## Status

DONE

## Scope

Implemented and reviewed the exact set of 35 unique effect IDs:

`501, 502, 503, 504, 505, 506, 511, 512, 513, 514, 515, 542, 544, 545, 546,
551, 552, 571, 581, 594, 701, 702, 703, 711, 712, 713, 714, 744, 752, 761,
771, 901, 902, 903, 952`.

The registry and focused test assert this literal set and count. No claim of 37
effects is made.

## TDD evidence

### RED

The focused command was run after adding the review regression tests:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.ControlEffectHandlersTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayProtocolTest
```

It failed at `:compileTestKotlin` on the deliberately requested missing
contracts:

- public predicate/category-scoped `BattleEffectStore.clearMatching`;
- `ScheduledEffectActivationChange`;
- resolved berserk allegiance and target pool;
- `DamageRedirectionEffectChange`.

After those APIs compiled, the regression suite also exposed the stale replay
adapter expectation `515` for evade, proving the protocol correction was
observable at its consumer boundary.

### GREEN

The forced review matrix was run with cache/configuration reuse disabled:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.ControlEffectHandlersTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --tests com.stzb.server.game.battle.BattleEngineSkillTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest \
  --tests com.stzb.server.game.battle.BattleSkillRuntimeTest \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayProtocolTest \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest \
  --no-build-cache --no-configuration-cache
```

Result: `BUILD SUCCESSFUL`; 122 tests passed with zero failures.

## Locked review semantics

- Preparation cancellation is requested only by confusion
  `501/701/901` and hesitation `502/702/902`. It targets the exact affected
  `BattleHeroRef`. Delayed `701/702` cancel only at activation. Berserk,
  taunt, and disarm do not cancel an already prepared active skill.
- 镇静 `513/713` removes only `HARMFUL`; 看破 `512/712` removes only
  `BENEFICIAL`. Predicate-scoped store clearing is atomic, and bound expansion
  cannot cross the requested category. Mixed-category and mixed-source bound
  groups are covered.
- Every delayed 7xx handler emits `ScheduledEffectActivationChange` carrying
  its complete `PersistentEffectSpec`. It emits no immediate
  `BattleEvent.StatusApplied`; the status event is produced by activation.
  Immediate equivalents still emit immediately.
- Berserk uses only the injected `BattleRandom`, exactly once per permission
  resolution. `ActionPermission` carries the explicit `resolvedAllegiance`
  and stable, position-ordered `resolvedTargetPool`; fixed random values cover
  both sides.
- Effect `506` is distinct from ordinary guard `504`. It emits a typed
  damage-redirection intent containing the selected protected targets and the
  allied base bearer. An `attackMax=2` fixture proves both selected targets are
  retained and no generic source-based guard redirect leaks into permission.
- Replay mapping is exact: `EVADE=514`, `IGNORE_EVADE=515`, with protocol and
  adapter consumer coverage.

## Self-review

- No server was started.
- Existing unrelated response/protocol worktree edits were left untouched and
  unstaged.
- `git diff --check` passes.
