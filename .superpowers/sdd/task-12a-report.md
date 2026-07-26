# Task 12A Report

## Status

DONE — live skill battle state and strict state-change application are
implemented without changing `BattleEngine`.

## Scope

- Added `SkillBattleState` and a live `SkillBattleView` with entry-state
  snapshots, live troops/stats/wounded/status state, active effects, and
  accumulated damage.
- Added `BattleStateChangeApplier`, typed outputs/results/permissions, and
  `UnsupportedBattleStateChangeException`.
- Applied troop damage, recovery, wounded consumption, stat modifiers,
  scheduled damage/recovery, action effects, redirection, preparation
  cancellation, cleansing, and blocked-effect no-ops.
- Damage and recovery use current live caps. Scheduled damage delegates to
  `ScheduledDamageEffectChange.tick`; scheduled recovery delegates to
  `ScheduledRecoveryEffectChange.tick`; persistent lifecycle delegates to
  `BattleEffectStore`.
- Stat modifiers recalculate from immutable entry stats, combining typed flat
  and percent potency, and expire at their exact round boundary.
- Every batch is fully preflighted before mutation. Unknown and unsupported
  meta changes throw diagnostically and cannot partially apply prior changes.

## TDD Evidence

The binding RED was recorded before implementation. The initial production
compile failed because all Task 12A state/applier symbols were absent.
Implementation then made the complete binding suite green. Two edge tests
were added and passed for recovery max-troop capping and exact action-effect
round expiry.

## Verification

Forced matrix:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.BattleStateChangeApplierTest \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --tests com.stzb.server.game.battle.skill.ControlEffectHandlersTest \
  --tests com.stzb.server.game.battle.skill.SkillTimingTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --tests com.stzb.server.game.battle.BattleEngineSkillTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --rerun-tasks --no-daemon
```

Result: `BUILD SUCCESSFUL`. `git diff --check` passed. No server was started.
Existing unrelated response/protocol/handler changes were left untouched and
excluded from the task commit.
