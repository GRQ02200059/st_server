# Task 7 Report: Core configured battle effects

## Status

DONE

## Scope

Implemented 37 configured effect IDs:

- Stats: `101-106`, `201-207`
- Direct and ongoing damage: `301-307`
- Normal/active/pursuit modifiers: `321`, `322`, `325`, `331`, `332`,
  `335`, `342`, `351`, `352`, `355`
- Recovery: `401`, `402`
- Physical/strategy modifiers: `521-524`, `531-534`

Handlers emit concrete state-change intents for stat changes (including siege
and range), troop damage/recovery, wounded-pool consumption, active effect
application, scheduled ongoing damage/recovery, blocked recovery, and
orthogonal damage modifiers. They do not mutate `BattleEngine`.

`BattleValueCalculator` is the unified numeric entry. It reads structured
constant/intelligence/calculation-type fields, uses live source/target state,
and delegates all physical/strategy/ongoing curves to
`BattleDamageCalculator`. Runtime behavior does not inspect description text.

## TDD evidence

### RED

Command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD FAILED` at `:compileTestKotlin` with the expected unresolved
core state changes, value calculator, registration, wounded-pool state, and
blocked-effect intent.

### GREEN

Focused result: `BUILD SUCCESSFUL`; `CoreEffectHandlersTest` passed 9 tests.

Mandated regression command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --tests com.stzb.server.game.battle.BattleActionResolverTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL`; 64 tests passed with zero failures/errors:

- `CoreEffectHandlersTest`: 9
- `BattleActionResolverTest`: 4
- `BattleEnginePlayableTest`: 14
- `BattleEffectRegistryTest`: 9
- `BattleEffectStoreTest`: 28

## Evidence and locked semantics

- `skill_effect_table.csv` establishes exact ID ownership and buff/value type.
- `skill_detail_table.csv` supplies targeting, constants, intelligence
  coefficients, duration, and calculation-type lists.
- The bundled reference simulator supplies physical, strategy, ongoing, and
  recovery curves.
- `303` schedules physical shake damage; `304-306` schedule strategy panic,
  burn, and hex damage. `307` is direct fire/strategy damage.
- Recovery is capped by calculated value, wounded troops, and troop capacity.
  Effect `207` produces a blocked intent for both `401` and `402`.
- Normal, active, pursuit, physical, strategy, ongoing, and fire
  classifications remain separate.
- Registrations use `ImplementedBattleEffectHandler`, one explicit semantic
  owner per requested ID.

## Self-review

- No server was started.
- Unrelated protocol/response changes were not modified or staged.
- `git diff --check` passes.

## Concerns

- These handlers intentionally produce state-change intents. A later
  interpreter/orchestrator task must apply them to the live battle state and
  `BattleEffectStore`.
- The default entry snapshot has no historical wounded pool, so it defaults
  to zero; the live battle view must supply current wounded troops.
