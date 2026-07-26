# Task 12 Report

## Implemented

- Routed configured battles exclusively through `DefaultCompleteSkillEngine`.
- Integrated passive, command, round, actor, active, normal, pursuit, damage, and
  defeat trigger points into battle orchestration.
- Applied controls, stat changes, scheduled effects, recovery, damage, and effect
  lifecycle changes through one live `SkillBattleState`.
- Completed prepared skills at their actor's `ACTION_BEFORE` boundary, allowing a
  faster control skill to cancel a slower actor's pending preparation.
- Preserved application-time source troops/stats for ongoing damage while using the
  target's live state at every tick.
- Projected stat events only after the effect store accepts the corresponding state
  change and retained the originating skill ID.
- Decoded the exact CSV percent encodings used by details `20000101` and
  `20002301..20002304`; unrelated `calc_pos=0` values remain deferred.
- Added narrow compatibility fallbacks for solo fixtures of `200001` and `200036`.
  The `200036` fallback uses the legacy `constantParam / 10` flat-stat rule and is
  explicit Task 13 plugin migration debt.

## Verification

Focused regressions:

```bash
./gradlew test \
  --tests 'com.stzb.server.game.battle.BattleEnginePlayableTest.damage over time ticks each round and expires' \
  --tests 'com.stzb.server.game.battle.BattleEnginePlayableTest.control statuses expire and allow later normal attacks' \
  --tests 'com.stzb.server.game.battle.BattleEnginePlayableTest.stat buff from skill increases damage on subsequent actions' \
  --tests 'com.stzb.server.game.battle.BattleEnginePlayableTest.dot and stat change events preserve their originating skill id' \
  --tests 'com.stzb.server.game.battle.BattleEngineSkillTest.command skills apply real effects during the preparation stage' \
  --tests 'com.stzb.server.game.battle.BattleEngineSkillTest.confusion cancels a prepared skill instead of letting it fire after control expires'
```

Result: 6 tests, 0 failures.

Battle/skill matrix:

```bash
./gradlew test \
  --tests 'com.stzb.server.game.battle.skill.CompleteSkillEngineIntegrationTest' \
  --tests 'com.stzb.server.game.battle.BattleEngineSkillTest' \
  --tests 'com.stzb.server.game.battle.BattleEnginePlayableTest' \
  --tests 'com.stzb.server.game.battle.BattleEngineTest' \
  --tests 'com.stzb.server.game.battle.skill.BattleStateChangeApplierTest' \
  --tests 'com.stzb.server.game.battle.skill.SkillTimingTest' \
  --tests 'com.stzb.server.game.battle.skill.SkillRuleInterpreterTest' \
  --tests 'com.stzb.server.game.battle.skill.CoreEffectHandlersTest' \
  --tests 'com.stzb.server.game.battle.skill.ControlEffectHandlersTest'
```

Result: 139 tests, 0 failures.

Full suite:

```bash
./gradlew test
```

Result: `BUILD SUCCESSFUL`; XML summary: 457 tests, 0 failures, 0 errors.

Final hygiene:

```bash
git diff --check
```

Result: clean.

## Scope

The Task 12 commit excludes pre-existing changes under game responses, handlers,
network response policy, `task-10-report.md`, and the client audit document.
