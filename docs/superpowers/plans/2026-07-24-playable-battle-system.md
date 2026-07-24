# 可玩闭环战斗系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有基础战斗内核上完成可玩闭环：所有已有英雄、战法、武器/装备都能接入战斗，常见效果生效，未知复杂效果以 fallback 事件记录且不阻塞战斗。

**Architecture:** 使用统一效果管线。配置仓库负责加载英雄、战法、装备和组合；Builder 负责把英雄规格合成可战斗单位；Modifier/Skill Runtime 负责统一应用属性、伤害、状态、准备、冷却、目标选择和 fallback 事件；BattleEngine 只编排初始化、回合循环、行动和结算。

**Tech Stack:** Kotlin/JVM 17, Gradle, Jackson Kotlin module, `kotlin.test`.

## Global Constraints

- 验收口径是“可玩闭环”，不是一次性 100% 精确还原商业服全部战法文本分支。
- 所有已有英雄 ID 必须可通过 builder 接入战斗。
- 所有已有战法 ID 必须可进入战法运行时；已支持效果生效，未知效果有 fallback 事件。
- 所有已有装备 ID 必须可挂载到英雄；已支持属性/效果生效，未知效果有 fallback 事件。
- 战斗不得因为单个未知战法或装备效果崩溃。
- 新行为必须先写失败测试，再写实现。
- 当前目录没有 `.git`，计划中的提交步骤跳过。

---

## File Structure

- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`: add equipment IDs, level, runtime modifiers, fallback/status/recovery events.
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt`: expose all hero IDs and all skill IDs; keep existing CSV/JSON loading behavior.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleEquipmentRepository.kt`: load `gear_id.json` and `gear_feature_extra.json`.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleModifier.kt`: represent stat bonuses, damage bonuses, damage reductions, probability bonuses, defense ignore, unsupported effects.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleModifierParser.kt`: parse common Chinese config descriptions into modifiers.
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleTeamBuilder.kt`: accept hero level and equipment IDs; apply hero growth and equipment modifiers.
- Create `src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt`: handle skill state, prepare rounds, cooldown, probability, target selection, recovery/status/fallback.
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleSkillInterpreter.kt`: delegate to `BattleSkillRuntime` or share effect execution helpers.
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`: initialize runtime state and emit richer events.
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt`: serialize new events.
- Add tests under `src/test/kotlin/com/stzb/server/game/battle/`.

## Task 1: Runtime Model and Fallback Events

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleModelRuntimeTest.kt`

**Interfaces:**
- Produces: `BattleModifier`, `BattleHero.level`, `BattleHero.equipmentIds`, `BattleEvent.UnsupportedSkillEffect`, `BattleEvent.UnsupportedEquipmentEffect`, `BattleEvent.StatusApplied`, `BattleEvent.Recovery`

- [ ] Write a failing test that constructs a `BattleHero` with `level = 20`, `equipmentIds = listOf(1024)`, and asserts new fallback/status/recovery events can be created and inspected.
- [ ] Run: `./gradlew test --tests com.stzb.server.game.battle.BattleModelRuntimeTest`
- [ ] Implement the model additions with default values so existing tests keep compiling.
- [ ] Re-run the targeted test and `./gradlew test --tests 'com.stzb.server.game.battle.*'`.

## Task 2: Equipment Repository

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleEquipmentRepository.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEquipmentRepositoryTest.kt`

**Interfaces:**
- Produces: `EquipmentConfig`, `EquipmentFeatureConfig`, `BattleEquipmentRepository.loadDefault()`, `equipment(id: Int): EquipmentConfig?`, `features(groupId: Int): List<EquipmentFeatureConfig>`, `allEquipmentIds(): Set<Int>`

- [ ] Write failing tests for equipment `1024` from `gear_id.json`: name `戚`, quality `稀世`, feature group `1`, and skill description containing attack/defense/damage modifiers.
- [ ] Write failing tests that feature group `2` contains `破敌` or `英勇` from `gear_feature_extra.json`.
- [ ] Run targeted test and verify unresolved references.
- [ ] Implement repository loading with Jackson and safe empty behavior if files are absent.
- [ ] Re-run targeted test.

## Task 3: Modifier Parser and Equipment Application

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleModifier.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleModifierParser.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleTeamBuilder.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEquipmentApplierTest.kt`

**Interfaces:**
- Produces: `BattleModifierParser.parseEquipment(config: EquipmentConfig, features: List<EquipmentFeatureConfig>): List<BattleModifier>`, `BattleHero.modifiers`

- [ ] Write failing tests that equipment `1024` adds attack +2, defense +3, and physical damage bonus +8%.
- [ ] Write failing tests that an unknown equipment phrase becomes `BattleModifier.Unsupported`.
- [ ] Run targeted test and verify failure.
- [ ] Implement parser for direct attributes, damage up/down, active tactic damage up/down, normal attack damage up/down, defense ignore, tactic probability bonus.
- [ ] Update `BattleTeamBuilder` to attach equipment modifiers and apply flat stat modifiers during build.
- [ ] Re-run equipment applier and team builder tests.

## Task 4: Hero Growth and All-Hero Buildability

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleTeamBuilder.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleHeroGrowthTest.kt`

**Interfaces:**
- Produces: `HeroBattleConfig.growth`, `BattleHeroSpec.level`, `BattleConfigRepository.allHeroIds(): Set<Int>`

- [ ] Write failing tests that a level 20 hero has higher stats than level 1 using `attack_grow`, `defence_grow`, `intel_grow`, `speed_grow`, `destroy_grow`.
- [ ] Write failing tests that the first 50 configured hero IDs can be built into single-hero teams without throwing.
- [ ] Run targeted test and verify failure.
- [ ] Add growth fields to hero config and builder level scaling.
- [ ] Re-run targeted test and existing builder tests.

## Task 5: Skill Runtime Prepare, Cooldown, Targeting, Recovery, Status

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillInterpreter.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleSkillRuntimeTest.kt`

**Interfaces:**
- Produces: `SkillRuntimeState`, `BattleSkillRuntime.tryAct(...)`

- [ ] Write failing tests that a prepared skill does not damage on the first prepare round and can damage after preparation.
- [ ] Write failing tests that a skill with cooldown cannot cast twice in consecutive actions.
- [ ] Write failing tests for target count using `attackMax`, status effects `501/502/552`, and recovery effect `401/402`.
- [ ] Run targeted test and verify failure.
- [ ] Implement state map keyed by source hero + skill ID.
- [ ] Implement supported effects and fallback for unsupported detail IDs.
- [ ] Re-run skill runtime and interpreter tests.

## Task 6: Engine Integration with Modifiers and Runtime State

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt`

**Interfaces:**
- Produces: engine behavior where modifiers affect normal and skill damage, runtime state persists across rounds, and fallback events are reported.

- [ ] Write failing tests that equipment physical damage bonus increases `SkillDamage`.
- [ ] Write failing tests that `DISARM` blocks normal attacks but not strategy skills, and `HESITATION` blocks active skills.
- [ ] Write failing tests that unsupported skill/equipment effects appear in report JSON.
- [ ] Run targeted test and verify failure.
- [ ] Integrate modifier lookup into damage calculation.
- [ ] Integrate `BattleSkillRuntime` state into round loop.
- [ ] Extend report codec for new event types.
- [ ] Re-run engine playable tests and full battle package tests.

## Task 7: Full Playable Integration Matrix

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt`
- Create: `src/test/kotlin/com/stzb/server/game/battle/BattleCoverageSmokeTest.kt`

**Interfaces:**
- Consumes all previous modules.

- [ ] Write an integration test using real heroes, real extra skills, and real equipment IDs that completes an 8-round seeded battle and emits a compressed report.
- [ ] Write a smoke test that samples all equipment IDs and all skill IDs through parser/runtime entrypoints without throwing.
- [ ] Run targeted integration tests.
- [ ] Fix discovered parse/runtime issues with fallback events, not broad hard-coded skips.
- [ ] Run final verification: `./gradlew clean test`.

## Completion Checklist

- [ ] 设计文档覆盖用户批准的“可玩闭环”范围。
- [ ] 所有新增行为都经历红灯到绿灯。
- [ ] 所有英雄可构建。
- [ ] 所有战法可进入运行时，未知效果有 fallback。
- [ ] 所有装备可挂载，未知效果有 fallback。
- [ ] 完整 `./gradlew clean test` 通过。
