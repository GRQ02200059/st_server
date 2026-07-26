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
- Semantic preflight now covers hero identity, potency/lifecycle validity, and
  immediate-versus-delayed boundaries before any battle or runtime mutation.
- Persistent stat, modifier, ongoing, status, and redirection behavior is
  keyed to the exact accepted store identity and removed on replacement,
  cleanse, or expiry.
- Actual troop loss creates wounded troops; paired recovery consumes only the
  amount actually restored under the live troop cap.
- Live-view capabilities are fail-closed and injectable for metadata/history;
  active statuses derive from accepted effects, including ongoing damage.
- Damage modifiers flow into live heroes and shared damage calculation;
  delayed activation has an explicit due-boundary entry point.
- Round hooks are idempotent per round and reject backward movement.

## TDD Evidence

The review RED first failed compilation on the missing delayed-activation
entry point. Subsequent focused REDs covered semantic atomicity, store/cache
synchronization, wound accounting, fail-closed live data, damage modifiers,
and round idempotency. The focused applier suite passes 18 tests.

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
