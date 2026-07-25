# 战斗系统一期重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成可供客户端实际出征使用的三人队战斗内核，并迁移参考项目 1001–1032 战法。

**Architecture:** 保留 `BattleEvent` 和客户端战报编码 seam；新增编队属性、统一效果状态、行动解析和显式战法目录四个深模块。`BattleEngine` 仅编排阶段。

**Tech Stack:** Kotlin/JVM、JUnit/Kotlin Test、Jackson CSV/JSON 配置、Gradle 8.7。

## Global Constraints

- 不实现动画。
- 不改变客户端战报的外层协议。
- CSV 和客户端反编译代码优先于参考项目中的猜测规则。
- 所有行为变更严格执行 RED → GREEN → REFACTOR。
- 保留工作区现有未提交修改，不提交、不回退。

---

### Task 1: 战斗输入与初始属性层

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleTeamBuilder.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleFormationCalculator.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleFormationCalculatorTest.kt`

**Interfaces:**
- Produces: `BattleFormationCalculator.calculate(specs: List<BattleHeroSpec>): BattleTeam`
- Produces: `BattleHeroSpec.attributePoints`, `advanceLevel`, `morale`

- [ ] 写失败测试：40 级成长、40 点攻击、进阶和四大营加成必须进入最终属性。
- [ ] 运行 `./gradlew test --tests '*BattleFormationCalculatorTest'`，确认属性断言失败。
- [ ] 扩充输入模型并实现 `BattleFormationCalculator`；`BattleTeamBuilder.build` 委托给它。
- [ ] 再次运行该测试，确认通过。

### Task 2: 统一效果状态

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleEffectState.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEffectStateTest.kt`

**Interfaces:**
- Produces: `BattleEffectState.apply(effect)`, `effectiveStats(hero)`, `damageFactor(...)`, `tick(phase)`

- [ ] 写失败测试：控制状态、同类强弱覆盖、不同类别增减伤叠加、持续时间递减。
- [ ] 运行目标测试并确认失败。
- [ ] 实现带 `source/skillId/category/duration/value` 的效果层。
- [ ] 运行目标测试并确认通过。

### Task 3: 阶段与行动解析

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/BattleActionResolver.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleActionResolverTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt`

**Interfaces:**
- Consumes: `BattleEffectState`
- Produces: `resolveNormalAction(sourceRef, context): List<BattleEvent>`

- [ ] 写失败测试：三人双方均按速度行动；攻击距离限制目标；连击产生两次普攻；每次普攻后可追击。
- [ ] 运行测试并确认失败。
- [ ] 将普攻、距离、连击、追击从引擎移入行动解析模块。
- [ ] 运行目标测试并确认通过。

### Task 4: 战法执行接口与 1001–1008

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/LegacySkillEffects.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/LegacySkillCatalogTest.kt`

**Interfaces:**
- Produces: `LegacySkillCatalog.find(skillId): LegacySkillDefinition?`
- Produces: `LegacySkillDefinition.execute(context): List<BattleEvent>`

- [ ] 为连战、温酒斩将、血践黄砂、方阵突击、先驱突击、钝兵挫锐、皇裔流离、其疾如风写参数化失败测试。
- [ ] 运行测试确认八个战法行为均未满足。
- [ ] 使用统一效果原语实现 1001–1008，并让运行时优先调用显式定义。
- [ ] 运行测试确认通过。

### Task 5: 战法 1009–1016

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/LegacySkillCatalogTest.kt`

- [ ] 为奋疾先登至金匮要略写参数化失败测试。
- [ ] 运行测试确认失败。
- [ ] 实现八个战法及所需行动钩子。
- [ ] 运行测试确认通过。

### Task 6: 战法 1017–1024

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/LegacySkillCatalogTest.kt`

- [ ] 为神兵天降至百战精兵写参数化失败测试。
- [ ] 运行测试确认失败。
- [ ] 实现八个战法。
- [ ] 运行测试确认通过。

### Task 7: 战法 1025–1032

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/LegacySkillCatalogTest.kt`

- [ ] 为持刀从武至深谋远虑写参数化失败测试。
- [ ] 运行测试确认失败。
- [ ] 实现八个战法。
- [ ] 运行测试确认通过。

### Task 8: 客户端战报与完整回归

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt`

- [ ] 写失败测试：准备、连击、追击、恢复、控制、持续伤害、属性和增减伤均产生客户端文本。
- [ ] 写固定种子三对三完整战斗测试，断言六名武将行动且同时存在普攻和技能。
- [ ] 补齐事件到客户端战报映射。
- [ ] 运行 `./gradlew test installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process`。
- [ ] 运行 `git diff --check`，确认无格式错误。

