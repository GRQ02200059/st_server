# 全量战法与官方 Paper 战报校准 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全全部战法运行时语义，并以 28 份可读官方 paper 战报持续校准阵容、行动回合、技能触发、伤害、恢复、兵损和胜负。

**Architecture:** `MetaEffectHandlers` 只负责把客户端配置解码为强类型状态变化；`SkillRuleInterpreter` 消费引用与幻化语义；`CompleteSkillEngine` 消费目标决策、共享次数和跨回合触发；`BattleStateChangeApplier` 负责伤害、恢复和玉玺累计等状态变更。测试侧扩展现有 `OfficialReportFixture`，将完整官方动作流归一化为战斗摘要，并用固定种子集合进行统计差分，避免依赖未知的官方随机种子。

**Tech Stack:** Kotlin 1.9.23、JVM 17、Gradle 8.7、Kotlin Test/JUnit 5、Jackson 2.17、`assent/cfg/paper` 官方战报。

## Global Constraints

- 禁止执行 `git reset --hard`、`git checkout -- <file>`、`git clean -fd`。
- 不提交、不推送、不回滚用户文件。
- 不触碰聊天、协议、地图和主城等非战斗改动。
- 不得通过扩大 `SkillCoverageReport.RUNTIME_CONSUMED_META_EFFECT_IDS` 伪造覆盖。
- 每个 effect 必须先有因缺少真实行为而失败的测试，再写生产实现。
- `availableRounds` 严格使用客户端配置值，不额外加一。
- effect 81 概率未命中不消费次数；成功强制目标后才消费。
- 未知官方随机种子时，不要求单次随机伤害逐点一致；时序、目标阵营、状态类型和运行时消费必须有确定证据。
- paper 数值验收使用固定种子集合的分布区间，不挑选最接近官方结果的单个种子。

## File Map

| File | Responsibility |
| --- | --- |
| `src/test/kotlin/com/stzb/server/game/battle/OfficialReportFixture.kt` | 读取 paper、重建阵容、解析完整战斗摘要。 |
| `src/test/kotlin/com/stzb/server/game/battle/OfficialFullBattleReportDiffTest.kt` | 28 份完整战报的结构与统计差分门禁。 |
| `src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt` | 7 个 meta effect 的强类型 intent。 |
| `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreter.kt` | 引用 detail 覆盖、傀儡模板和幻化施法。 |
| `src/main/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngine.kt` | 强制目标、共享次数、跨回合累计和 intent 编排。 |
| `src/main/kotlin/com/stzb/server/game/battle/skill/BattleStateChangeApplier.kt` | 玉玺吸收、伤害累计和状态生命周期。 |
| `src/main/kotlin/com/stzb/server/game/battle/skill/SkillCoverageReport.kt` | 仅在真实消费和行为测试完成后登记 effect。 |
| `src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreterTest.kt` | 111/112/125/199 的解释器行为。 |
| `src/test/kotlin/com/stzb/server/game/battle/skill/BattleStateChangeApplierTest.kt` | 407 的伤害吸收与跨回合累计。 |
| `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngineIntegrationTest.kt` | 81/88、玉玺、完整 8 回合和战法集成。 |

---

### Task 1: 建立 28 份完整 Paper 战斗摘要基线

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialReportFixture.kt`
- Create: `src/test/kotlin/com/stzb/server/game/battle/OfficialFullBattleReportDiffTest.kt`

**Interfaces:**
- Produces: `OfficialReportFixture.fullBattleSummary(actions): FullBattleSummary`
- Produces: `OfficialReportFixture.readableReports(): List<Path>`
- `FullBattleSummary` 包含 `rounds`、`actionRoundsByPosition`、`skillTriggers`、`damageBySide`、`recoveryBySide`、`finalTroopsByPosition`、`outcome`。

- [ ] **Step 1: 写完整 paper 语料失败测试**

```kotlin
@Test
fun `all readable paper reports expose complete battle summaries`() {
    val reports = OfficialReportFixture.readableReports()
    assertEquals(28, reports.size)
    reports.forEach { report ->
        val summary = OfficialReportFixture.fullBattleSummary(
            OfficialReportFixture.read(report),
        )
        assertTrue(summary.rounds in 0..8, report.toString())
        if (summary.rounds == 0) {
            assertTrue(summary.actionRoundsByPosition.isEmpty(), report.toString())
        }
        assertTrue(summary.finalTroopsByPosition.keys.all { it in 1..6 })
        assertTrue(summary.actionRoundsByPosition.values.flatten().all { it in 1..8 })
    }
}
```

- [ ] **Step 2: 运行并确认 RED**

Run:

```bash
./gradlew --no-daemon --rerun-tasks \
  -Dkotlin.compiler.execution.strategy=in-process \
  --init-script .tmp/codex-battle-20260801.init.gradle \
  test --tests '*OfficialFullBattleReportDiffTest.all readable paper reports expose complete battle summaries'
```

Expected: FAIL，因为 `readableReports` 和 `fullBattleSummary` 尚不存在。

- [ ] **Step 3: 实现动作流归一化**

解析现有协议常量：

```kotlin
data class FullBattleSummary(
    val rounds: Int,
    val actionRoundsByPosition: Map<Int, List<Int>>,
    val skillTriggers: Map<Int, Int>,
    val damageBySide: Map<Side, Int>,
    val recoveryBySide: Map<Side, Int>,
    val finalTroopsByPosition: Map<Int, Int>,
    val outcome: BattleOutcome,
)
```

`ROUND` 更新当前回合；`HERO_ACTION_START` 记录行动；`NORMAL_DAMAGE`、`SKILL_DAMAGE`、`ONGOING_DAMAGE` 汇总伤害；`RECOVERY` 汇总恢复；`FINAL_TROOPS` 读取最终兵力；`ATTACKER_WIN/DRAW/DEFENDER_WIN` 映射结局。

- [ ] **Step 4: 运行摘要测试转绿**

Run Task 1 Step 2 的同一命令。Expected: PASS，正好读取 28 份报告；允许无交战方时按正式动作流直接 0 回合结算。

---

### Task 2: 建立完整战斗统计差分门禁

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialFullBattleReportDiffTest.kt`

**Interfaces:**
- Consumes: `OfficialReportFixture.reconstructBattleRequest`
- Consumes: `BattleEngine.resolve`
- Produces: 每份 paper 的固定 32 种子模拟摘要和聚合误差。

- [ ] **Step 1: 写当前实现必然失败的差分测试**

固定使用 `0 until 32` 的 `SeededBattleRandom`，不从结果中挑种子。每份战报断言：

```kotlin
assertTrue(official.rounds in simulatedRoundRange)
assertTrue(official.damageBySide.getValue(Side.ATTACKER) in attackerDamageInterval)
assertTrue(official.damageBySide.getValue(Side.DEFENDER) in defenderDamageInterval)
assertTrue(official.outcome in simulatedOutcomes)
```

伤害和恢复区间取 32 次结果的 `min..max`；另记录中位数相对误差，初始总门禁设为双方平均误差不超过 35%，后续每修复一个语义只允许收紧或保持，不允许放宽。

- [ ] **Step 2: 运行并记录 RED 基线**

Run:

```bash
./gradlew --no-daemon --rerun-tasks \
  -Dkotlin.compiler.execution.strategy=in-process \
  --init-script .tmp/codex-battle-20260801.init.gradle \
  test --tests '*OfficialFullBattleReportDiffTest'
```

Expected: FAIL，并按报告路径列出回合、结局、兵损区间和中位数误差。

- [ ] **Step 3: 保留基线，不通过放宽阈值转绿**

差分测试保持红色，作为后续 effect 与公式校准的总验收门禁。单个报告若无法完整重建，必须在 fixture 中报告缺失字段，不能静默跳过。

---

### Task 3: 实现 effect 199 奇门遁甲幻化

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreter.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillCoverageReport.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreterTest.kt`

**Interfaces:**
- Produces: `TransformAndCastRandomActiveSkillChange`
- 消费方从存活敌我武将收集主动战法，排除施法者自身拥有的战法和根战法 `200279`，去重排序后用 `context.random` 选择。

- [ ] **Step 1: 写失败行为测试**

断言候选稳定排序、排除自身技能、施法者保持为左慈、候选主动战法强制成功、内部 detail 概率仍生效、准备回合被跳过。

- [ ] **Step 2: 运行 RED**

```bash
./gradlew --no-daemon --rerun-tasks \
  -Dkotlin.compiler.execution.strategy=in-process \
  --init-script .tmp/codex-battle-20260801.init.gradle \
  test --tests '*SkillRuleInterpreterTest*transformation*'
```

Expected: FAIL，当前 effect 199 仅产生未消费的 `MetaEffectChange`。

- [ ] **Step 3: 最小实现并转绿**

复用 `executeSkill(..., probabilityOwnership = FORCED_SUCCESS)`，新增 `skipPreparation` 的内部执行选项，不修改候选战法的 detail 概率。

- [ ] **Step 4: 刷新覆盖门禁**

Expected 剩余集合从 7 项减少为 `[111, 112, 407, 125, 81, 88]`，再将 `199` 加入真实消费集合。

---

### Task 4: 实现 effect 81 强制目标与 effect 88 共享次数

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngine.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleStateChangeApplier.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillCoverageReport.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngineIntegrationTest.kt`

**Interfaces:**
- Produces: `ForcedTargetEffectChange`
- Produces: `SharedEffectUseGroupChange`
- 强制目标在 `SkillTargetSelector.selectRandom()` 前提供实时覆盖；只对首次符合 `attackType` 的主动伤害或普通攻击生效并无视距离。

- [ ] **Step 1: 写 effect 81 RED 测试**

覆盖概率未命中不消费、成功后消费、第二次不再强制、超出攻击距离仍可选择配置目标。

- [ ] **Step 2: 写 effect 88 RED 测试**

注册 `21129311/21129312` 共享组，任一成员真实消费后同步扣减；注册时不扣减。

- [ ] **Step 3: 最小实现并运行定军山集成测试**

断言 81、88 与现有 131 发动率提高共同工作，且没有复用连击 effect 200/544。

- [ ] **Step 4: 刷新覆盖门禁**

Expected 剩余集合为 `[111, 112, 407, 125]`，再登记 81、88。

---

### Task 5: 实现 effect 111/112/125 引用执行覆盖

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreter.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillCoverageReport.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreterTest.kt`

**Interfaces:**
- Produces: `ReferencedDetailExecutionOverride`
- `EffectInvocation` 新增不可变 `executionOverride`；不得修改 `SkillEffectRule` 或全局 CSV 图。

```kotlin
data class ReferencedDetailExecutionOverride(
    val referencedDetailId: Int,
    val valueDelta: Int? = null,
    val valueReplacement: TypedBattlePotency.Resolved? = null,
    val extraParameters: Map<Int, Int> = emptyMap(),
    val targetOverride: List<BattleHeroRef>? = null,
    val lifecycleOverride: EffectLifecycleOverride? = null,
)
```

- [ ] **Step 1: 写 111 参数注入 RED 测试**

覆盖 `calc_pos=953/954/991`，断言参数只进入被引用 detail 的本次 invocation，不污染下一次执行。

- [ ] **Step 2: 写 112 数值变化 RED 测试**

分别覆盖 `calc_pos=32` 的逐回合增量、`calc_pos=0` 的正数/负数变化和大整数编码；每种语义通过正式描述和引用 detail 的 `configuredValue` 锁定，禁止统一按百分比。

- [ ] **Step 3: 写 125 傀儡 RED 测试**

断言模板 detail 由傀儡 detail 拥有目标、概率、数值和生命周期，模板保留 effect 类型、冲突和 buff 分类；覆盖 20028212→21028202→21028211 的嵌套调用。

- [ ] **Step 4: 最小实现并运行引用族测试**

所有引用执行都复用 `executeDetail`，通过 override 合并，不复制 handler 逻辑。

- [ ] **Step 5: 刷新覆盖门禁**

Expected 剩余集合为 `[407]`，再登记 111、112、125。

---

### Task 6: 实现 effect 407/408 玉玺跨回合累计

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleStateChangeApplier.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngine.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillCoverageReport.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/BattleStateChangeApplierTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngineIntegrationTest.kt`

**Interfaces:**
- Produces: `DamageAbsorptionAccumulatorEffectChange`
- 累计器保存 `owner/source/currentRoundAbsorbed/previousRoundAbsorbed/absorbPercent`。

- [ ] **Step 1: 写伤害吸收 RED 测试**

断言我军目标实伤按吸收比例减少，减少部分进入当前回合累计；不是护盾，不产生额外生命值。

- [ ] **Step 2: 写回合滚动与释放 RED 测试**

第二回合开始前把 current 滚入 previous 并清零 current；408 只读取 previous；袁术初始承受 50%，之后每回合由 112 增加 10%。

- [ ] **Step 3: 实现并验证多次伤害累计**

同回合物理、策略、持续伤害均累计；玉玺释放自身不再次被玉玺吸收，避免递归。

- [ ] **Step 4: 清空覆盖门禁**

只有行为测试转绿后登记 407。Expected: `report.unconsumedMetaEffects == emptySet()`。

---

### Task 7: 全量消费者审计

**Files:**
- Modify as required under: `src/main/kotlin/com/stzb/server/game/battle/skill/`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillCoverageTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngineIntegrationTest.kt`

- [ ] **Step 1: 枚举全部 112 effect 的 handler 输出类型**

新增门禁：任何 `MetaEffectChange` operation 必须在解释器、引擎或 applier 中有显式消费者；禁止 `else -> Unit`。

- [ ] **Step 2: 枚举 `CompleteSkillEngine.apply()` 的所有 `is XxxChange -> Unit`**

逐项证明由解释器提前展开；无法证明的类型增加失败行为测试并实现消费。

- [ ] **Step 3: 检查 8 回合行动与控制生命周期**

对所有存活武将断言每回合有 `HeroActionStart/End`；只有明确的混乱、犹豫、怯战等状态限制相应动作，并验证施加/结束边界。

- [ ] **Step 4: 运行覆盖与引擎回归**

```bash
./gradlew --no-daemon --rerun-tasks \
  -Dkotlin.compiler.execution.strategy=in-process \
  --init-script .tmp/codex-battle-20260801.init.gradle \
  test \
  --tests '*CompleteSkillCoverageTest' \
  --tests '*CompleteSkillEngineIntegrationTest'
```

Expected: PASS，且无未消费 meta effect。

---

### Task 8: 依据 Paper 差异逐项校准战斗公式

**Files:**
- Modify only evidence-backed battle files.
- Modify: `src/test/kotlin/com/stzb/server/game/battle/OfficialFullBattleReportDiffTest.kt`

- [ ] **Step 1: 按差异类型排序**

优先级固定为：行动回合缺失 > 状态/目标阵营错误 > 胜负错误 > 伤害/恢复方向错误 > 数值误差。

- [ ] **Step 2: 每个差异建立最小行为 RED 测试**

从 paper 抽取阵容、触发回合和相关动作，先在对应 handler/applier 测试中复现，再修改公式；禁止直接在差分测试中硬编码某场战报结果。

- [ ] **Step 3: 重跑 32 种子统计差分**

每次修复记录 28 份报告的：

```text
outcome_coverage
round_coverage
action_round_mismatch_count
attacker_damage_median_relative_error
defender_damage_median_relative_error
recovery_median_relative_error
```

阈值只允许收紧。最终要求官方回合数和双方兵损均落入模拟区间，全部官方结局出现在对应模拟 outcome 集合，平均兵损中位数误差不超过 20%。

---

### Task 9: 最终验证

**Files:**
- No new production files.

- [ ] **Step 1: 战斗专项回归**

```bash
./gradlew --no-daemon --rerun-tasks \
  -Dkotlin.compiler.execution.strategy=in-process \
  --init-script .tmp/codex-battle-20260801.init.gradle \
  test \
  --tests 'com.stzb.server.game.battle.OfficialFullBattleReportDiffTest' \
  --tests 'com.stzb.server.game.battle.OfficialPreparationReportDiffTest' \
  --tests 'com.stzb.server.game.battle.skill.SkillRuleInterpreterTest' \
  --tests 'com.stzb.server.game.battle.skill.CoreEffectHandlersTest' \
  --tests 'com.stzb.server.game.battle.skill.ControlEffectHandlersTest' \
  --tests 'com.stzb.server.game.battle.skill.BattleStateChangeApplierTest' \
  --tests 'com.stzb.server.game.battle.skill.CompleteSkillEngineIntegrationTest' \
  --tests 'com.stzb.server.game.battle.skill.CompleteSkillCoverageTest'
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 全量测试与构建**

```bash
./gradlew --no-daemon --rerun-tasks \
  -Dkotlin.compiler.execution.strategy=in-process \
  test installDist
```

Expected: 除事先记录且与本任务无关的历史失败外无新增失败；最终交付前历史失败也必须重新核对。

- [ ] **Step 3: 工作树检查**

```bash
git diff --check
git status --short
```

Expected: `git diff --check` 无输出；仅报告战斗相关改动和原有用户改动，不提交、不推送。
