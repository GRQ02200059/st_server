# Task 7 Report: Core configured battle effects

## Status

DONE_WITH_CONCERNS

## Scope

Implemented 40 configured effect IDs:

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

Focused result: `BUILD SUCCESSFUL`; `CoreEffectHandlersTest` passed 11 tests.

Mandated regression command:

```bash
./gradlew test --rerun-tasks \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --tests com.stzb.server.game.battle.BattleActionResolverTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest \
  --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL`; 85 tests passed with zero failures/errors:

- `CoreEffectHandlersTest`: 11
- `BattleActionResolverTest`: 4
- `BattleEnginePlayableTest`: 14
- `BattleEffectRegistryTest`: 9
- `BattleEffectStoreTest`: 28
- `BattleConfigRepositoryTest`: 10
- `SkillRuleCatalogTest`: 9

### Review-fix RED/GREEN evidence

- Physical-curve RED: the exact 10,000-troop fixture exposed modifier
  application to troop-base damage. GREEN locks the simulator result at `643`.
- Config-value RED: repository/rule tests did not compile until typed,
  lossless configured values and coefficient-source metadata existed. GREEN
  covers real rows `20095701`, `20002301`, `29500101`, and `20000712`.
- Damage-axis RED: the focused suite failed compilation while `DamageKind`
  conflated school, origin, and tags. GREEN independently composes
  physical/strategy, normal/active/pursuit, ongoing, and fire axes.

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
- The registry contract is the literal set of 40 IDs, asserted directly.
- Registrations use `ImplementedBattleEffectHandler`, one explicit semantic
  owner per requested ID.

## Self-review

- No server was started.
- Unrelated protocol/response changes were not modified or staged.
- `git diff --check` passes.

## Concerns

- `DefaultBattleValueCalculator.effectValue` still returns a bare `Int` and
  retains the magnitude-based `>= 1_000_000` scaling heuristic. The repository
  now preserves raw unit/scaling metadata losslessly, but execution does not
  yet use a typed/deferred value and percentage stat application remains
  unresolved.
- Persistent and scheduled intents do not yet snapshot the complete skill,
  conflict, stacking, delay, hit, and lifecycle identity. Zero configured
  duration is still coerced to one round.
- Scheduled recovery stores an already capped amount. It still needs an
  uncapped-potency tick helper that caps against live troops, capacity, and
  wounded pool on every tick and consumes wounded troops once.
- `ApplyBattleEffectChange` still lacks the complete fields and conversion
  needed to construct `ActiveSkillEffect` without a repository lookup.
- Some older damage/recovery assertions remain relational rather than exact
  numeric fixtures. The physical simulator regression is exact.
- These handlers intentionally produce state-change intents. A later
  interpreter/orchestrator task must apply them to the live battle state and
  `BattleEffectStore`; the entry snapshot has no historical wounded pool.
