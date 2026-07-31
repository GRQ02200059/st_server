# Paper Preparation JA Exact Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all 25 preparation `ja` five-field tuples generated from the representative paper fixture exactly equal to the official tuples.

**Architecture:** Preserve the official level/strategy inputs at the configuration and invocation boundaries, add an optional validated target-decision replay at the selector boundary, and keep signed runtime modifiers separate from positive-magnitude client encoding. The official fixture supplies precise entry stats and recorded decisions; ordinary production resolution retains existing random targeting.

**Tech Stack:** Kotlin, Gradle 8.7, kotlin.test/JUnit 5, Jackson fixture parsing, existing battle skill engine.

## Global Constraints

- Do not hardcode `200198`, `200204`, or `200773` amounts or target positions in production code.
- Production callers that omit a decision source must retain random-without-replacement behavior.
- Runtime damage modifier percentages remain signed; only preparation `ja` emits absolute magnitude.
- Exact acceptance is multiset equality of all 25 `(sourcePosition, sourceId, targetPosition, effectId, amount)` tuples.
- Preserve the user's overlapping dirty-worktree changes and do not commit implementation files automatically.

---

### Task 1: Preserve level scaling and calculate official damage-modifier potency

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/CoreEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/ControlEffectHandlers.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleConfigRepositoryTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/skill/CoreEffectHandlersTest.kt`

**Interfaces:**
- Produces: `SkillDetailConfig.initEffectRatio: Int`.
- Produces: `EffectInvocation.rootSkillLevel(source: BattleHero): Int`.
- Produces: `BattleValueCalculator.effectValue(rule, source, skillLevel)` with a default level of `1`.

- [ ] **Step 1: Write failing configuration and calculator tests**

Add a real-row assertion:

```kotlin
assertEquals(50, detail(20019801).initEffectRatio)
```

Add calculator assertions using real `20019801` and precise strategies:

```kotlin
assertEquals(
    TypedBattlePotency.rate(53),
    calculator.effectValue(graph.detail(20019801), heroWithStrategy(236.4), skillLevel = 10),
)
assertEquals(
    TypedBattlePotency.rate(56),
    calculator.effectValue(graph.detail(20019801), heroWithStrategy(330.0), skillLevel = 7),
)
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
./gradlew test --tests '*BattleConfigRepositoryTest' --tests '*CoreEffectHandlersTest.value calculation*'
```

Expected: compilation fails because `initEffectRatio` and the `skillLevel` argument do not exist.

- [ ] **Step 3: Load `init_effect_ratio` losslessly**

Add to `SkillDetailConfig`:

```kotlin
val initEffectRatio: Int = 100,
```

and to `loadSkillDetail`:

```kotlin
initEffectRatio = row.int("init_effect_ratio"),
```

- [ ] **Step 4: Add root skill-level resolution**

Add beside `EffectInvocation`:

```kotlin
internal fun EffectInvocation.rootSkillLevel(source: BattleHero): Int {
    val index = source.skillIds.indexOf(context.rootSkillId)
    return source.skillLevels.getOrElse(index) { 1 }.coerceIn(1, 10)
}
```

- [ ] **Step 5: Implement the verified modifier formula**

Change the calculator interface to:

```kotlin
fun effectValue(
    rule: SkillEffectRule,
    source: BattleHero,
    skillLevel: Int = 1,
): TypedBattlePotency
```

Before the existing generic calculation, handle `521..534` RATE effects:

```kotlin
if (rule.effectId in 521..534 && configured.unit == BattleEffectValueUnit.RATE) {
    val level = skillLevel.coerceIn(1, 10)
    val ratio = rule.raw.initEffectRatio +
        (level - 1) * (100 - rule.raw.initEffectRatio) / 9.0
    val raw = configured.rawConstant +
        configured.rawCoefficient * source.stats.precise(BattleStat.STRATEGY) / 200.0
    return TypedBattlePotency.rate((ratio * raw / 100.0).roundToInt())
}
```

Pass `invocation.rootSkillLevel(sourceHero)` from core, meta, and control invocation-based callers. Keep rule-only special-plugin calls on the default level until they have an invocation.

- [ ] **Step 6: Run configuration and calculator tests**

Run:

```bash
./gradlew test --tests '*BattleConfigRepositoryTest' --tests '*CoreEffectHandlersTest'
```

Expected: PASS, including level 1/7/10 cases and existing configured-value tests.

---

### Task 2: Add validated target-decision replay without changing production randomness

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleTargetDecisionSource.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillBattleContext.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillTargetSelector.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillTargetSelectorTest.kt`

**Interfaces:**
- Produces: `BattleTargetDecisionRequest(rule, context, candidates, limit)`.
- Produces: `fun interface BattleTargetDecisionSource` returning `List<BattleHeroRef>?`.
- Produces: optional `targetDecisions` on `SkillBattleContext` and `BattleEngine.resolve`.

- [ ] **Step 1: Write failing selector tests**

Add three tests:

```kotlin
@Test fun `recorded decision replaces random selection`() { /* expect requested target order */ }
@Test fun `missing recorded decision uses battle random`() { /* FixedBattleRandom(0) keeps old result */ }
@Test fun `recorded decision rejects duplicate or foreign targets`() { /* assertFailsWith */ }
```

Use a rule with `selectType == SELECT_RANDOM`, three filtered candidates, and limit two.

- [ ] **Step 2: Run selector tests and verify compilation failure**

Run:

```bash
./gradlew test --tests '*SkillTargetSelectorTest'
```

Expected: compilation fails because the decision-source API is absent.

- [ ] **Step 3: Create the decision-source types**

Create:

```kotlin
data class BattleTargetDecisionRequest(
    val rule: SkillEffectRule,
    val context: SkillBattleContext,
    val candidates: List<BattleHeroRef>,
    val limit: Int,
)

fun interface BattleTargetDecisionSource {
    fun select(request: BattleTargetDecisionRequest): List<BattleHeroRef>?

    companion object {
        val NONE = BattleTargetDecisionSource { null }
    }
}
```

- [ ] **Step 4: Thread the source through engine contexts**

Add to `SkillBattleContext`:

```kotlin
val targetDecisions: BattleTargetDecisionSource = BattleTargetDecisionSource.NONE,
```

Extend the complete-engine overload:

```kotlin
fun resolve(
    request: BattleRequest,
    config: BattleConfigRepository,
    random: BattleRandom = SeededBattleRandom(0),
    targetDecisions: BattleTargetDecisionSource = BattleTargetDecisionSource.NONE,
): BattleResult
```

Set the same source on every root context; `copy` propagation handles nested invocations.

- [ ] **Step 5: Validate and apply only `SELECT_RANDOM` overrides**

At the `SELECT_RANDOM` branch, call a new helper that validates:

```kotlin
require(selected.distinct().size == selected.size)
require(selected.size <= limit)
require(selected.all { it in candidates })
```

Include `detailId`, `context.source`, candidates, and selected values in failure messages. If the source returns `null`, call the existing `randomWithoutReplacement` unchanged. Other selection modes do not consult replay.

- [ ] **Step 6: Run selector and engine tests**

Run:

```bash
./gradlew test --tests '*SkillTargetSelectorTest' --tests '*BattleIntegrationTest'
```

Expected: PASS; fallback random tests retain their previous target lists.

---

### Task 3: Reconstruct paper entry stats and recorded modifier targets

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialReportFixture.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialPreparationReportDiffTest.kt`
- Create: `src/test/kotlin/com/stzb/server/game/battle/OfficialReportFixtureTest.kt`

**Interfaces:**
- Produces: `OfficialReportFixture.targetDecisions(actions): BattleTargetDecisionSource`.
- Changes: `reconstructBattleRequest` returns teams with exact paper attack/defense/strategy/speed hundredths.

- [ ] **Step 1: Write failing fixture tests for precise stats**

Using compact action text, assert the last pre-`hr` value wins and decimal parsing is exact:

```kotlin
assertEquals(236.4, hero.stats.precise(BattleStat.STRATEGY), 0.001)
```

Include both six-parameter `1b` and five-parameter `0x` forms, and verify values after `hr` are ignored.

- [ ] **Step 2: Write failing fixture tests for target replay**

Build official `ja` actions for `200198/531`, create the replay source, and request selection from three candidate refs. Assert the recorded client positions are returned in report order and a second occurrence consumes the next queued group.

- [ ] **Step 3: Run fixture tests and verify failure**

Run:

```bash
./gradlew test --tests '*OfficialReportFixtureTest'
```

Expected: compilation fails because precise reconstruction and `targetDecisions` are absent.

- [ ] **Step 4: Implement precise stat reconstruction**

Map action IDs as follows:

```kotlin
"19", "0v" -> BattleStat.ATTACK
"1a", "0w" -> BattleStat.DEFENSE
"1b", "0x" -> BattleStat.STRATEGY
"1c", "0y" -> BattleStat.SPEED
```

Stop at `hr`. For `19..1c`, read target at parameter 2 and `valueAfter` at 5; for `0v..0y`, read target at 2 and `valueAfter` at 4. Convert with:

```kotlin
value.toBigDecimal().movePointRight(2).intValueExact()
```

Replace only recorded stats through `BattleStats.fromHundredths`; preserve builder siege and hit range.

- [ ] **Step 5: Implement stateful official target replay**

Group contiguous skill `ja` actions by:

```kotlin
data class DecisionKey(val sourcePosition: Int, val skillId: Int, val effectId: Int)
```

Store an `ArrayDeque<List<Int>>` for each key. On selection, convert `context.source` and candidates to client positions, consume the first matching target-position list, and return matching refs. Return `null` when no key exists so unrelated selection remains random.

- [ ] **Step 6: Run fixture tests**

Run:

```bash
./gradlew test --tests '*OfficialReportFixtureTest'
```

Expected: PASS for decimal precision, `hr` boundary, position conversion, queue consumption, and fallback.

---

### Task 4: Encode JA magnitude and require exact 25-tuple parity

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjector.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjectorTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialPreparationReportDiffTest.kt`
- Modify: `docs/正版战报战前动作差距审计-2026-07-30.md`
- Modify: `docs/正式战报战前准备缺口-2026-07-30.md`

**Interfaces:**
- Consumes: precise reconstructed request and `OfficialReportFixture.targetDecisions`.
- Produces: positive-magnitude `ja` and exact official/generated multiset equality.

- [ ] **Step 1: Write a failing reduction-magnitude projector test**

Project `BattleEvent.ModifierApplied(amount = -37, effectId = 522)` and require:

```kotlin
assertEquals("ja4,200773,4,522,37", action.encode())
```

- [ ] **Step 2: Run the projector test and verify failure**

Run:

```bash
./gradlew test --tests '*ClientBattlePreparationEventProjectorTest'
```

Expected: FAIL with generated amount `-37`.

- [ ] **Step 3: Encode modifier magnitude**

Change only the preparation modifier projection parameter to:

```kotlin
kotlin.math.abs(event.amount)
```

Do not change `BattleEvent.ModifierApplied` or runtime state.

- [ ] **Step 4: Upgrade the official fixture acceptance test**

Resolve with both inputs:

```kotlin
val decisions = OfficialReportFixture.targetDecisions(officialPreparation)
val result = BattleEngine.resolve(request, config, FixedBattleRandom(0), decisions)
```

Sort tuples by all five fields and assert:

```kotlin
assertEquals(25, generatedJa.size)
assertEquals(officialJa.sortedWith(ordering), generatedJa.sortedWith(ordering))
```

Create a fresh decision source for the second resolution because replay queues are consumable.

- [ ] **Step 5: Run the cross-layer test and fix only diagnosed source-layer defects**

Run:

```bash
./gradlew test --tests '*OfficialPreparationReportDiffTest'
```

Expected: PASS at exact `25/25`. If it fails, inspect the complete five-field difference; do not weaken equality, truncate actions, or hardcode skill outputs.

- [ ] **Step 6: Run focused and full verification**

Run:

```bash
./gradlew test --tests '*BattleConfigRepositoryTest' --tests '*CoreEffectHandlersTest' --tests '*SkillTargetSelectorTest' --tests '*OfficialReportFixtureTest' --tests '*ClientBattlePreparationEventProjectorTest' --tests '*OfficialPreparationReportDiffTest'
./gradlew test
git diff --check
```

Expected: focused suite PASS. Full suite may retain only the known `CompleteSkillCoverageTest` failure with the same 18 condition codes; the set must not grow.

- [ ] **Step 7: Update audit documentation**

Record:

- `ja` progression `75 -> 23 -> 25`;
- verified level/strategy formula and local client evidence;
- replayed historical target decisions versus normal production randomness;
- exact five-field equality result and test commands;
- any unchanged full-suite baseline failure.

Do not claim all preparation action families are complete; this milestone is exact `ja` parity for the representative fixture.
