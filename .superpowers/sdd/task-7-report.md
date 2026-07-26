# Task 7 Report: Core configured battle effects

## Status

DONE

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

## Final completion

Commit `3016f9b7` removes the remaining execution heuristic and completes the
effect intent contracts:

- `TypedBattlePotency` preserves `FLAT`, `PERCENT`, and `RATE` values; ambiguous
  encodings become `Deferred` with a precise diagnostic instead of being
  guessed from magnitude.
- Real rows `20095701`, `20002301`, `29500101`, and `20000712` lock the typed
  behavior. In particular `500000` is never decoded as a flat `5000`, and the
  15% row remains percentage-valued.
- `PersistentEffectSpec` contains source/root/current skill identity, kind/raw
  type, detail/effect, conflict/replacement/binding/stack fields, delay and
  round/hit lifecycles, clear-per-hit, start boundary, and typed potency.
- Zero configured duration remains explicit and is not coerced to one round.
- `PersistentEffectSpec.toActiveSkillEffect()` constructs the store model
  without a repository lookup.
- Scheduled recovery stores uncapped potency and calculates every tick from
  live troops, capacity, wounded troops, and live effect 207. It returns one
  recovery change and one matching wounded-consumption change, preventing
  double consumption.

Final forced regression command covered `CoreEffectHandlersTest`,
`BattleActionResolverTest`, `BattleEnginePlayableTest`,
`BattleEffectRegistryTest`, `BattleEffectStoreTest`,
`BattleConfigRepositoryTest`, `SkillRuleCatalogTest`, and
`BattleEffectStateTest`.

Result: `BUILD SUCCESSFUL`; 94 tests passed with zero failures, errors, or
skips. `CoreEffectHandlersTest` contains 17 focused tests.

The remaining orchestration work is intentionally assigned to Tasks 9 and 12:
they apply these complete intents to the live battle state and effect store.

## Final P1 closure

The last two review findings are closed with test-first coverage:

- One strict damage-origin mapper validates `SkillKind` together with its raw
  type and preserves `ACTIVE`, `PURSUIT`, `COMMAND`, and `PASSIVE` across
  physical, strategy, direct, and ongoing damage. Ongoing remains an
  orthogonal tag. Unsupported `UNKNOWN`/raw type `14` fails in strict mode and
  is diagnosed/skipped in safe mode, with no active fallback.
- Real pursuit detail `20002612` and synthetic strategy details prove the
  emitted origin and modifier axis. Pursuit, command, passive, active,
  ongoing, and fire classifications remain independently selectable.
- Scheduled damage now carries `PersistentEffectSpec`, uncapped typed potency,
  damage school/origin/tags/status, coefficient metadata, and the complete
  round/hit/delay/clear/bind/conflict/replacement/stack/start lifecycle.
- Real command burn detail `20002012` preserves delay round `2`, duration `8`,
  and full skill/detail/effect identity. Every tick recalculates from live
  source/target troops, stats, and modifiers before applying the current target
  troop cap; pursuit ongoing damage retains pursuit origin plus ongoing tag.

The RED focused run failed at test compilation on the absent scheduled-damage
`spec`, typed potency, and `tick` contract. The final focused run passed all 21
`CoreEffectHandlersTest` tests.

The final forced eight-suite command used `--rerun-tasks`, `--no-daemon`,
`-Dkotlin.compiler.execution.strategy=in-process`, and
`-Pkotlin.incremental=false`.

Result: `BUILD SUCCESSFUL`; 98 tests passed with zero failures, errors, or
skips. No server was started, and `git diff --check` passes.
