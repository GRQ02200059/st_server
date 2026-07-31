# Paper Preparation JA Exact Parity Design

## Goal

Make the representative generated preparation `ja` multiset exactly equal to
the real report under `assent/cfg/paper`.

The acceptance fixture is:

`assent/cfg/paper/11/cap_20260312014510506_0000000b_zlib.json`

The current milestone is official `25`, generated `23`.  Exact parity means
all five fields match as a multiset:

```text
sourcePosition, sourceId, targetPosition, effectId, amount
```

This change must preserve normal production randomness.  Paper replay supplies
recorded target decisions only when explicitly requested by a comparison test
or replay caller.

## Verified Evidence

The local decompiled client implementation at
`Tenth.UI/SkillUtil.cs` calculates displayed configured effects as:

```text
levelRatio = initEffectRatio + (skillLevel - 1) * (100 - initEffectRatio) / 9
rawValue = constantParam + intelParam * currentStrategy / 200
resolved = round(levelRatio * rawValue / 100)
```

The client source uses `Floor` for display text, while paper battle values show
nearest-integer behavior at half fractions.  Battle resolution therefore uses
the project's existing `roundToInt` convention after applying the same level
and strategy formula.

Cross-report checks support the formula:

- `200198`, level 10, strategy 236.4: `18 + 30 * 236.4 / 200 = 53.46`, report `53`;
- `200198`, level 10, strategy 254.1: result `56.115`, report `56`;
- `200198`, level 7, strategy 330.0: level ratio `83.333%`, result `56.25`, report `56`;
- `200773`, level 10, strategy 263.2: `10 + 26 * 263.2 / 200 = 44.216`, report `44`.

The representative `200773` source first receives a strategy reduction from
`200023`; its modifier must use the live strategy at command execution time,
not its original or final static snapshot.

## Architecture

The implementation has four independent boundaries:

1. configuration preserves `init_effect_ratio`;
2. effect calculation receives the executing root skill level and reads the
   live source strategy;
3. paper fixtures reconstruct exact entry attributes and recorded target
   decisions;
4. client `ja` projection emits modifier magnitude, while effect ID retains
   increase/reduction direction.

No skill ID receives a hardcoded amount or target list in production code.

## Skill-Level Effect Calculation

Add `initEffectRatio` to `SkillDetailConfig` and load it losslessly from the
CSV `init_effect_ratio` column.

Extend `BattleValueCalculator.effectValue` with an explicit `skillLevel`.
Invocation-based handlers resolve the level from the live source hero by
looking up `context.rootSkillId` in `skillIds` and reading the matching
`skillLevels` entry.  Missing entries use level `1`, matching formation and
client-report defaults.  Levels are clamped to `1..10`.

For damage modifier effects `521..534`, calculate RATE potency with the
verified client formula, using `BattleStats.precise(STRATEGY)`.  The value is
calculated at invocation time, so earlier preparation stat changes affect
later command skills.  Other configured value families retain their current
special scaling rules in this milestone to avoid an unrelated global numeric
rewrite.

Both paired effects of a command skill receive the same level and live source
state.  The existing effect ID mapping still determines dealt/taken,
physical/strategy, and increase/reduction semantics.

## Exact Entry Attributes for Paper Replay

`OfficialReportFixture` reconstructs entry stats from the preparation stream
before the passive/command skill phase.  It recognizes the official stat
families:

- `19/1a/1b/1c`: attack, defense, strategy, speed percentage changes;
- `0v/0w/0x/0y`: attack, defense, strategy, speed fixed changes.

For each client position and stat, the fixture takes the last `valueAfter`
before the `hr` preparation boundary.  Decimal values are converted to
hundredths without passing through `Double`.  The reconstructed team's hero
stats are replaced with these precise values while keeping siege and attack
range from the builder.

This is test/replay input reconstruction, not a production shortcut.  Normal
battles continue to obtain attributes from `BattleFormationCalculator`.

## Recorded Target Decision Replay

Introduce a narrow `BattleTargetDecisionSource` interface:

```text
select(rule, context, candidates, limit) -> selected targets or no override
```

`SkillBattleContext` carries a source whose default returns no override.
`BattleEngine.resolve` accepts it as an optional argument and passes it to all
contexts.  `SkillTargetSelector` consults it only at the point where
`SELECT_RANDOM` would otherwise call `BattleRandom`.

An override is accepted only when:

- every selected target is in the already filtered candidate list;
- no target is duplicated;
- selected size is at most the configured limit.

Invalid replay input fails with rule, source, candidates, and selected targets
in the message.  No override falls back to the existing random-without-
replacement implementation.

`OfficialReportFixture` creates a stateful replay source from official `ja`
actions.  Decisions are queued by source client position, root skill ID, and
effect ID, preserving occurrence order.  Client positions are resolved to
live `BattleHeroRef` values.  The representative replay covers `200198`,
`200204`, and `200773`; unrelated selectors continue to use the supplied
`BattleRandom`.

This separates two facts that must not be conflated: skill target semantics
remain random in production, while comparison with one historical report must
replay that report's realized random choices.

## JA Direction Encoding

`DamageModifierChange.percent` remains signed internally because runtime
damage calculation needs direction.  Official `ja` uses the effect ID to
encode direction and writes a positive magnitude.  The preparation projector
therefore writes `abs(amount)` for `ModifierApplied`.

This is verified against:

- reduction effects `522/524` from `200773`;
- positive increase effects `521/523/531/533`;
- existing static troop and equipment modifier actions.

## Data Flow

```text
paper actions
  -> exact entry stats + target decision replay
  -> BattleEngine.resolve(request, random, decisions)
  -> command effect invocation
  -> root skill level + live precise strategy
  -> verified RATE potency
  -> signed runtime DamageModifierChange
  -> positive-magnitude preparation ja
  -> exact five-field multiset comparison
```

## Tests

1. `BattleConfigRepositoryTest` requires real rows to retain
   `init_effect_ratio`.
2. `CoreEffectHandlersTest` verifies level 1, level 7, and level 10 modifier
   calculations with precise strategy, including the cross-report examples.
3. An integration test verifies that a preparation strategy change before a
   command skill changes the command modifier potency.
4. `SkillTargetSelectorTest` verifies replay override, fallback randomness,
   and rejection of invalid/duplicate/out-of-candidate targets.
5. `OfficialReportFixtureTest` verifies decimal stat reconstruction and target
   replay parsing.
6. `ClientBattlePreparationEventProjectorTest` requires reduction modifiers
   to encode positive magnitude.
7. `OfficialPreparationReportDiffTest` upgrades the milestone assertion from
   generated count `23` to exact equality with all 25 official tuples, and
   verifies a second resolution is identical.
8. Focused tests and the full suite run afterward.  The known
   `CompleteSkillCoverageTest` set of 18 unresolved condition codes is tracked
   separately and must not grow.

## Error Handling and Compatibility

- Skill levels outside `1..10` are clamped at the calculation boundary.
- Missing root skill levels use level `1` and remain deterministic.
- Missing paper stat fields retain builder-produced values for that stat.
- Malformed stat decimals or `ja` widths fail fixture parsing with the raw
  action.
- Invalid target replay decisions fail rather than silently changing targets.
- Production callers that pass only request/config/random retain existing
  behavior and random selection.
- Structured battle state keeps signed modifiers; only client `ja` rendering
  changes to magnitude.

## Acceptance

The milestone is complete only when the representative preparation `ja`
multisets are exactly equal at 25 entries.  Count-only equality, source-family
equality, or adapter-side suppression does not satisfy acceptance.
