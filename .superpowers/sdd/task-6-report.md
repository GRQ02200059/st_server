# Task 6 Report: 严格效果注册表

## Status

DONE

## Scope

Implemented:

- `BattleEffectHandler`, `EffectInvocation`, `EffectExecution`, and
  `BattleStateChange` contracts for later concrete effect handlers.
- Strict and safe `BattleEffectRegistry` factories whose declarations are
  derived from an injected `SkillRuleGraph`.
- Exact separation between declared and implemented effect IDs.
- Structured unknown/unimplemented diagnostics containing current skill,
  detail, effect, trigger, and full runtime call path.
- Persistent explicit handler registration with duplicate and undeclared-ID
  rejection.
- Immutable registry ID sets, invocation call paths, and execution
  collections.
- Explicit `META_NO_OP` declaration metadata for sentinel effect `0`; it is
  deliberately still unimplemented and therefore fails in strict mode.

No concrete effect semantics or engine integration was added.

## TDD evidence

### RED

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD FAILED` at `:compileTestKotlin` with the expected unresolved
references for `BattleEffectRegistry`, `BattleEffectHandler`,
`EffectInvocation`, `EffectExecution`, `BattleStateChange`, structured
diagnostics, and `UnsupportedSkillRuleException`.

### GREEN

Focused command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL`; `BattleEffectRegistryTest` passed 6 tests with
zero failures and zero errors.

Mandated regression command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL`; 42 tests passed with zero failures and zero
errors:

- `BattleEffectRegistryTest`: 6
- `SkillRuleCatalogTest`: 8
- `BattleEffectStoreTest`: 28

## Locked semantics

- The default strict registry builds the default rule graph and declares its
  exact 112 scoped effect IDs. There is no second static effect-ID list.
- All 112 IDs begin declared but unimplemented. Declaration metadata cannot
  be mistaken for executable coverage.
- Effect `0` is explicitly classified as `META_NO_OP`, but remains absent
  from `implementedEffectIds()` and raises `UNIMPLEMENTED_EFFECT` in strict
  mode until later semantics are registered.
- An effect outside the graph raises `UNKNOWN_EFFECT`; a declared effect
  without a handler raises `UNIMPLEMENTED_EFFECT`.
- Both failure diagnostics retain current skill ID, detail ID, effect ID,
  trigger, and runtime call path. When execution is not already inside the
  runtime stack, the root/current IDs form the fallback path.
- Safe mode invokes its structured logger once for the same diagnostic and
  returns the immutable `EffectExecution.EMPTY`; it creates no damage,
  status, state change, or event.
- Registration returns a new registry. It accepts only declared IDs and
  rejects duplicate handlers, allowing later core/control/meta handler
  groups to register explicit ownership safely.
- Successful execution passes the exact rule/context to the handler and
  defensively snapshots the handler's state-change and event collections.

## Self-review

- `declaredEffectIds()` and `implementedEffectIds()` are unmodifiable to Java
  and Kotlin callers.
- The default registry reports 112 declared and zero implemented IDs.
- The original registry remains unchanged after registration.
- Existing rule graph and effect-store regressions pass.
- Existing unrelated protocol, handler, and response edits were not modified
  or staged.
- The server was not started.

## Concerns

- `BattleStateChange` is intentionally only a marker contract in this task.
  Later handler tasks must define concrete state-change records before engine
  application.
- Sentinel effect `0` has declaration metadata only; Task 9 must register its
  real no-op/meta semantics explicitly before strict execution can succeed.
