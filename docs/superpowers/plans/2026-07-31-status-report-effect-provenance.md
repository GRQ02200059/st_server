# Status Report Effect Provenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve configured effect IDs on applied-status events and emit successful round-zero applications as official `0s` actions.

**Architecture:** Extend the existing `StatusApplied` event with optional provenance instead of adding an adapter-side configuration lookup. Complete-skill producers propagate IDs they already own; JSON and client-report projections consume the event field with a legacy fallback.

**Tech Stack:** Kotlin, JUnit/kotlin.test, Gradle, Jackson

## Global Constraints

- Do not hardcode mappings from `skillId` to report effect ID.
- Existing `StatusApplied` constructors remain source compatible through a nullable default.
- A successful `StatusApplied` emits `0s`; this plan does not define or emit `0t`.
- Battle resolution behavior must remain unchanged.
- Preserve the user's existing dirty-worktree changes and do not mix them into commits.

---

### Task 1: Extend the event and structured report

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleReportCodecTest.kt`

**Interfaces:**
- Produces: `BattleEvent.StatusApplied.effectId: Int?`
- Produces: JSON field `"effectId"` for `StatusApplied`

- [ ] **Step 1: Write the failing codec test**

Add `effectId = 752` to the existing attributed `StatusApplied` fixture and assert:

```kotlin
assertTrue(json.contains("\"effectId\":752"))
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.BattleReportCodecTest
```

Expected: compilation fails because `StatusApplied` has no `effectId` parameter.

- [ ] **Step 3: Add optional event provenance and serialize it**

Append this property after `skillId`:

```kotlin
val effectId: Int? = null,
```

Add this entry to the `StatusApplied` report map:

```kotlin
"effectId" to effectId,
```

- [ ] **Step 4: Run the codec test and verify GREEN**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.BattleReportCodecTest
```

Expected: PASS.

---

### Task 2: Propagate configured IDs from complete-skill producers

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngine.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/ControlEffectHandlers.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/OfficialPreparationReportDiffTest.kt`

**Interfaces:**
- Consumes: `BattleEvent.StatusApplied.effectId: Int?`
- Produces: configured IDs on scheduled, control, and stat-associated status events

- [ ] **Step 1: Write the failing complete-engine assertion**

Resolve the representative fixture, collect round-zero applied status IDs, and require:

```kotlin
val appliedEffectIds = firstResult.events
    .filterIsInstance<BattleEvent.StatusApplied>()
    .filter { it.round == 0 }
    .mapNotNull(BattleEvent.StatusApplied::effectId)
    .toSet()

assertTrue(702 in appliedEffectIds)
assertTrue(752 in appliedEffectIds)
```

- [ ] **Step 2: Run the official fixture test and verify RED**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.OfficialPreparationReportDiffTest
```

Expected: FAIL because round-zero `HESITATION`/`DISARM` events have no configured effect ID.

- [ ] **Step 3: Propagate IDs at every complete-skill construction site**

For `ScheduledDamageEffectChange`, pass:

```kotlin
effectId = change.effectId,
```

For `BattleStateOutput.StatChanged`, pass:

```kotlin
effectId = change.effectId,
```

For `ScheduledEffectActivationChange.activationEvent`, pass:

```kotlin
effectId = spec.effectId,
```

For direct control-handler status events, pass:

```kotlin
effectId = ownedEffectId,
```

- [ ] **Step 4: Run the official fixture test and verify GREEN**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.OfficialPreparationReportDiffTest
```

Expected: PASS for effect-provenance assertions; client-action assertions are unchanged until Task 3.

---

### Task 3: Project successful status applications as `0s`

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjector.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattlePreparationEventProjectorTest.kt`

**Interfaces:**
- Consumes: `BattleEvent.StatusApplied.effectId`
- Produces: `0s<target>,<configured-effect-id>` for successful applications

- [ ] **Step 1: Write the failing projector test**

Project:

```kotlin
BattleEvent.StatusApplied(
    round = 0,
    source = source,
    target = target,
    status = BattleStatus.DISARM,
    durationRounds = 2,
    skillId = 200648,
    effectId = 752,
)
```

Assert:

```kotlin
assertEquals(
    listOf("0s6,752"),
    actions.map(ClientReportAction::encode),
)
assertTrue(actions.none { it.id == "0t".toInt(36) })
```

- [ ] **Step 2: Run the projector test and verify RED**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.ClientBattlePreparationEventProjectorTest
```

Expected: FAIL with generated `0t6,552`.

- [ ] **Step 3: Replace polarity naming with lifecycle naming**

In the protocol use:

```kotlin
const val PREPARATION_STATUS_APPLIED = 28
const val PREPARATION_STATUS_UNRESOLVED = 29
```

Remove the status-polarity selector. In both applied-status projections use:

```kotlin
ClientReportAction(
    ClientBattleTextReplayProtocol.PREPARATION_STATUS_APPLIED,
    listOf(
        ClientBattleTextReplayProtocol.position(event.target),
        event.effectId ?: ClientBattleTextReplayProtocol.effectId(event.status),
    ),
)
```

- [ ] **Step 4: Run projector and adapter tests and verify GREEN**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.ClientBattlePreparationEventProjectorTest \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest
```

Expected: PASS.

---

### Task 4: Lock the official sample difference

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialPreparationReportDiffTest.kt`

**Interfaces:**
- Consumes: generated preparation actions and configured status effect IDs
- Produces: regression coverage for removal of the generated-only `0t` family

- [ ] **Step 1: Add the official-action assertions**

Keep the resolved `firstResult`, then assert:

```kotlin
assertTrue(
    generatedPreparation.none { it.id == "0t".toInt(36) },
    "successful applied statuses must not be projected as 0t",
)
assertTrue(
    generatedPreparation.any { it.raw.endsWith(",702") },
    "hesitation must retain configured effect 702",
)
assertTrue(
    generatedPreparation.any { it.raw.endsWith(",752") },
    "disarm must retain configured effect 752",
)
```

- [ ] **Step 2: Temporarily remove one propagation assignment and verify RED**

Temporarily remove `effectId = ownedEffectId` from the direct control status producer.

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.OfficialPreparationReportDiffTest
```

Expected: FAIL on the missing configured effect assertion.

Restore the propagation assignment immediately.

- [ ] **Step 3: Run the official fixture twice and verify GREEN**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.OfficialPreparationReportDiffTest --rerun-tasks
./gradlew test --tests com.stzb.server.game.battle.OfficialPreparationReportDiffTest --rerun-tasks
```

Expected: PASS twice with identical fixed-random report text.

---

### Task 5: Refresh audit and run verification

**Files:**
- Modify: `docs/正式战报战前准备缺口-2026-07-30.md`
- Modify: `docs/正版战报战前动作差距审计-2026-07-30.md`

**Interfaces:**
- Consumes: fresh representative-fixture metrics and test output
- Produces: current status/effect-provenance audit

- [ ] **Step 1: Refresh the documented result**

Record that:

- generated `0t` is removed from the representative sample;
- `702/752` now come from event provenance;
- the main sample has no generated-only action family;
- `0t` lifecycle semantics remain unresolved and out of scope.

- [ ] **Step 2: Run focused verification**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.BattleReportCodecTest \
  --tests com.stzb.server.game.battle.ClientBattlePreparationEventProjectorTest \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest \
  --tests com.stzb.server.game.battle.OfficialPreparationReportDiffTest \
  --rerun-tasks
```

Expected: PASS.

- [ ] **Step 3: Run full verification**

Run:

```bash
./gradlew test --rerun-tasks
git diff --check
```

Expected: the battle-report changes pass. If the baseline
`CompleteSkillCoverageTest` still reports the same 18 unknown condition codes,
report it separately and do not claim the complete suite is green.

- [ ] **Step 4: Inspect the final workspace**

Run:

```bash
git status --short
git diff --stat
```

Expected: only scoped changes plus the user's pre-existing dirty changes;
no temporary diagnostic output or intentionally broken propagation remains.
