# Preparation JA Parity Milestone Implementation Plan

> **For Codex:** Execute this plan task by task with tests first.  The overall
> target is exact parity with reports under `assent/cfg/paper`; this plan is
> the first behavior-changing milestone and must leave remaining differences
> explicit.

**Goal:** Remove round-zero `223006` leakage and add the missing
`296132/296232` troop modifiers so the representative preparation `ja` stream
shrinks deterministically from 75 to 23 while preserving combat behavior.

**Architecture:** Fix lifecycle timing in `CompleteSkillEngine`, where the
invalid state mutation originates.  Expand troop-feature semantics in
`BattleFormationCalculator`, where source IDs are already resolved from the
client table.  Use the official fixture test as the cross-layer acceptance
test; do not filter encoded actions.

**Tech Stack:** Kotlin, Gradle, kotlin.test, Jackson-backed official fixture.

---

### Task 1: Lock round-zero post-hurt behavior

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngineIntegrationTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngine.kt`

1. Add a failing integration test that constructs a team containing skill
   `201006`, applies positive normal damage with a `SkillBattleContext` at
   round `0`, and asserts no active effect has skill ID `223006`.
2. Run only that test and confirm it fails because the nearby targets receive
   the derived modifiers.
3. Add `context.round <= 0` early return to `tongchouHurtResult`.
4. Run both the new test and existing
   `tongchou buffs only allies within one position after actual hurt`; require
   both to pass.

### Task 2: Implement `296132/296232` troop modifiers

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleTeamBuilderTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleFormationCalculator.kt`

1. Add a failing team-builder test using feature IDs whose repository mapping
   yields `296132` and `296232`.  Assert each source projects effects `531` and
   `533`, amount `8`, self target, and adds
   `BattleModifier.DamageDealtPercent(percent = 8)`.
2. Run the focused test and confirm the sources exist but their modifiers do
   not.
3. Extend `troopFeatureModifiers` so `296132` and `296232` produce effects
   `531/533` at amount `8`.
4. Extend `troopRuntimeModifiers` so those sources add damage dealt +8 while
   preserving `296105` damage taken -8.
5. Run `BattleTeamBuilderTest` and `BattleFormationCalculatorTest`.

### Task 3: Make the paper fixture assert the milestone

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialReportFixture.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialPreparationReportDiffTest.kt`

1. Add a strict `ja` tuple parser for the five integral parameters.
2. Add assertions for official count `25`, generated count `23`, no generated
   source `223006`, and exact presence of the four `296132/296232` tuples.
3. Run the fixture test.  If the count differs, inspect the complete tuple
   multiset and fix the engine source; do not relax the assertion or suppress
   projection.
4. Assert deterministic repeated resolution still produces the identical
   tuple list.

### Task 4: Verify and record the remaining exact gap

**Files:**
- Modify: `docs/正版战报战前动作差距审计-2026-07-30.md`
- Modify: `docs/正式战报战前准备缺口-2026-07-30.md`

1. Run focused tests:

   `./gradlew test --tests '*CompleteSkillEngineIntegrationTest' --tests '*BattleTeamBuilderTest' --tests '*BattleFormationCalculatorTest' --tests '*OfficialPreparationReportDiffTest'`

2. Run the full suite with `./gradlew test` and distinguish new failures from
   the known `CompleteSkillCoverageTest` baseline gap.
3. Update both audit documents with before/after counts, removed and added
   source/effect groups, test evidence, and the remaining
   `200198/200204/200773` tuple differences.
4. Inspect `git diff --check` and `git diff --stat`.  Do not commit the user's
   overlapping dirty implementation files automatically.
