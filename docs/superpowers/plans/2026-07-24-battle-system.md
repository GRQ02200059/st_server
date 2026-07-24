# Battle System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a testable server-side battle resolution loop that can construct teams from client-derived config, apply basic army bonuses and tactics, resolve deterministic rounds, and emit report-friendly events.

**Architecture:** Keep battle logic under `com.stzb.server.game.battle`. CSV tables remain the structured source for numeric hero and skill data; `server/assent/cfg` JSON is supplemental metadata for readable names, descriptions, and army-combination bonuses. The engine stays server-authoritative and deterministic under an injected random source so tests can lock behavior.

**Tech Stack:** Kotlin/JVM 17, Gradle, Jackson Kotlin module, `kotlin.test`.

## Global Constraints

- Current scope is a server battle kernel, not a complete commercial-rule clone.
- Use CSV files at project root as primary structured config: `hero_table.csv`, `skill_table.csv`, `skill_detail_table.csv`, `skill_effect_table.csv`.
- Use `server/assent/cfg/*.json` as supplemental metadata and army/group bonus input.
- Existing `BattleEngine.resolve(BattleRequest): BattleResult` must remain callable by current tests.
- New behavior must be test-first: each module gets a failing test before production code changes.
- No new runtime dependency unless the existing Gradle stack cannot parse the provided data.
- Worktree has no `.git`; commit steps are intentionally skipped in this environment.

---

## File Structure

- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`: add battle skills, statuses, event types, seeded options, and richer hero/team fields while keeping constructor defaults compatible.
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt`: expose config maps, load JSON metadata, parse army bonuses, and provide skill detail lists.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleTeamBuilder.kt`: build battle teams from hero IDs, positions, troops, initial skills, optional learned skills, and army bonuses.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleRandom.kt`: deterministic random abstraction for production and tests.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleSkillInterpreter.kt`: apply passive/command buffs and active/pursuit skill effects for the first supported effect set.
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`: use the interpreter, statuses, and injected random while preserving normal attack behavior.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt`: convert `BattleEvent` list to a stable JSON report payload and compressed `zzz` payload compatible with the client-side `CheckZip` convention.
- Create/modify tests under `src/test/kotlin/com/stzb/server/game/battle/`.

## Task 1: Config Metadata and Army Bonuses

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleConfigRepositoryTest.kt`

**Interfaces:**
- Produces: `heroExtra(heroId: Int): HeroExtraConfig?`, `skillDetails(skillId: Int): List<SkillDetailConfig>`, `armyBonusesFor(heroIds: Collection<Int>): List<ArmyBonusConfig>`

- [ ] Write failing tests for loading `hero_extra.json`, `skill_extra.json`, and parsing a known `army_extra.json` bonus.
- [ ] Run `./gradlew test --tests com.stzb.server.game.battle.BattleConfigRepositoryTest` and verify the new tests fail because the APIs do not exist.
- [ ] Implement JSON parsing with existing Jackson, parse `armyEffect` strings for attack/defense/strategy/speed/siege/hitRange bonuses, and expose all task interfaces.
- [ ] Re-run the targeted test and verify it passes.

## Task 2: Team Builder

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleTeamBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleTeamBuilderTest.kt`

**Interfaces:**
- Consumes: `BattleConfigRepository.toBattleHero`, `armyBonusesFor`
- Produces: `BattleTeamBuilder.build(specs: List<BattleHeroSpec>): BattleTeam`

- [ ] Write failing tests proving the builder attaches the initial skill, rejects duplicate positions, and applies an army hit-range/stat bonus when all required heroes are present.
- [ ] Run the targeted test and verify it fails because the builder does not exist.
- [ ] Add `BattleHero.skillIds`, `BattleHero.activeStatuses`, `BattleHeroSpec`, and `BattleTeamBuilder`.
- [ ] Re-run the targeted test and existing engine tests.

## Task 3: Deterministic Random and Skill Interpreter

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleRandom.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillInterpreter.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleSkillInterpreterTest.kt`

**Interfaces:**
- Produces: `BattleRandom`, `FixedBattleRandom`, `BattleSkillInterpreter.applyPreBattle(...)`, `BattleSkillInterpreter.tryCastActiveSkill(...)`

- [ ] Write failing tests for a guaranteed physical damage skill (`301`), strategy damage (`302`), and pre-battle attribute buff (`101`/`102`/`103`/`104`).
- [ ] Run the targeted test and verify it fails because the interpreter does not exist.
- [ ] Implement a small effect dispatcher using `SkillBattleConfig.mainDetail` and detail lists.
- [ ] Re-run the targeted test.

## Task 4: Engine Integration

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEngineSkillTest.kt`

**Interfaces:**
- Produces: `BattleEngine.resolve(request: BattleRequest, config: BattleConfigRepository, random: BattleRandom): BattleResult`

- [ ] Write failing tests proving active skills can fire before normal attacks, control statuses can skip actions, and old `resolve(request)` behavior still works.
- [ ] Run targeted tests and verify expected failures.
- [ ] Integrate pre-battle buffs, per-turn skill attempts, status checks, and unchanged fallback normal attacks.
- [ ] Re-run targeted battle tests.

## Task 5: Report Codec

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleReportCodecTest.kt`

**Interfaces:**
- Produces: `BattleReportCodec.toJson(result: BattleResult): String`, `BattleReportCodec.toCompressedClientReport(result: BattleResult): String`

- [ ] Write failing tests proving the JSON contains battle start, round start, action, damage, skill, status, and outcome records, and compressed reports start with `zzz` and round-trip through GZIP.
- [ ] Run targeted tests and verify failures.
- [ ] Implement JSON serialization and `zzz` + Base64 + GZIP compression using JDK APIs.
- [ ] Re-run targeted tests.

## Task 6: Integration Test

**Files:**
- Create: `src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt`

**Interfaces:**
- Consumes all previous modules.

- [ ] Write an integration test that loads default config, builds two real teams from known hero IDs, resolves a seeded battle, and emits a compressed report.
- [ ] Run the integration test and verify it fails if any module is incomplete.
- [ ] Fix discovered issues without broad refactors.
- [ ] Run `./gradlew test` and inspect the full output.

## Completion Checklist

- [ ] All planned module tests pass.
- [ ] Full Gradle test suite passes or any environmental failure is explicitly recorded.
- [ ] Existing `BattleEngine.resolve(BattleRequest)` tests still pass.
- [ ] New report codec output is deterministic for seeded tests.
- [ ] The implementation does not require a live Android client to verify the battle kernel.
