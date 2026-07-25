# 五星武将与 S/A 战法完整引擎 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 配置驱动地完整执行 308 个五星武将自带与可学习 S/A 主战法、其 668 个依赖节点和 112 种效果，并保持出征、结算、占地和客户端战报兼容。

**Architecture:** 在 `battle/skill` 包中建立规则目录、目标与条件解释器、效果注册表、运行状态和特例插件，对 `BattleEngine` 仅暴露 `prepareBattle` 与 `trigger` 两个入口。所有通用规则来自客户端 CSV，只有无法由配置字段表达的机制进入插件；覆盖报告是完成度的权威来源。

**Tech Stack:** Kotlin/JVM 17、Jackson 2.17、Kotlin Test/JUnit 5、Gradle 8.7、现有客户端 CSV 与 `server/assent/cfg/paper` 真包。

## Global Constraints

- 验收范围固定为 308 个主战法、668 个依赖节点、112 种效果和 1935 条明细。
- 五星定义使用 `hero_table.csv` 的 `quality_name=五星`；六星不因 `quality=5` 被误纳入。
- 可学习 S/A 战法取 `SkillInventoryCatalog.allSkillIds()` 与 `skill_quality_level in {S,A}` 的交集。
- 目标战法依赖的低级、NPC 或内部子战法必须作为执行节点实现，但不作为独立主战法展示。
- 不实现动画，不根据战法文字描述猜测规则。
- 所有随机选择只使用注入的 `BattleRandom`。
- 开发和测试使用严格模式；正式运行使用安全模式并记录完整诊断。
- 目标范围内 `UnsupportedSkillEffect`、未知条件、未知选择器、断开依赖和无事件主战法最终均为 0。
- 每个生产代码变化之前必须有针对该行为的失败测试。
- 不启动游戏服务；用户负责启动和客户端实测。
- 工作区已有大量未提交修改；每次提交只暂存本任务列出的文件，不回退或覆盖其他改动。
- 构建使用 `./gradlew ... --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false`，避免已出现的 Kotlin 增量缓存损坏。

## File Structure

新目录 `src/main/kotlin/com/stzb/server/game/battle/skill`：

- `SkillScopeCatalog.kt`：生成 308 个主战法验收集合。
- `SkillRuleModel.kt`：规范化后的战法、明细、依赖和诊断类型。
- `SkillRuleCatalog.kt`：构建 668 节点规则图并做完整性校验。
- `SkillBattleContext.kt`：战法模块统一运行上下文和触发类型。
- `SkillRuntimeState.kt`：准备、延迟、计数、调用栈和战法级状态。
- `SkillTargetSelector.kt`：解释目标阵营、范围、数量、排序与随机选择。
- `SkillConditionInterpreter.kt`：解释施放、前置和效果条件。
- `BattleEffectStore.kt`：效果覆盖、替换、叠加、持续、次数和清除。
- `BattleEffectRegistry.kt`：112 种效果处理器的注册与严格覆盖检查。
- `CoreEffectHandlers.kt`：属性、伤害、恢复、持续伤害和增减伤。
- `ControlEffectHandlers.kt`：控制、免疫、规避、连击、先手和行动修正。
- `MetaEffectHandlers.kt`：子战法、延迟、触发、替换、次数和特殊元效果。
- `SkillRuleInterpreter.kt`：执行规则、条件、目标和子战法。
- `SpecialSkillPlugin.kt`：特殊战法插件接口与注册表。
- `CompleteSkillEngine.kt`：对战斗引擎暴露 `prepareBattle` 和 `trigger`。
- `SkillCoverageReport.kt`：输出主战法、依赖、效果、条件、选择器与插件覆盖情况。

对应测试置于 `src/test/kotlin/com/stzb/server/game/battle/skill`。现有 `BattleSkillRuntime.kt` 和 `LegacySkillCatalog.kt` 在迁移完成前保留，集成后不再是权威执行路径。

---

### Task 1: 锁定目标战法清单

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillScopeCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillScopeCatalogTest.kt`

**Interfaces:**
- Consumes: `SkillInventoryCatalog.allSkillIds(): List<Int>`、客户端英雄与战法 CSV。
- Produces: `SkillScopeCatalog.loadDefault(): SkillScope`；`SkillScope.mainSkillIds: Set<Int>`；`SkillScope.fiveStarInitialSkillIds: Set<Int>`；`SkillScope.learnableSaSkillIds: Set<Int>`。

- [ ] **Step 1: 写失败测试，锁定 308 个主战法**

```kotlin
class SkillScopeCatalogTest {
    @Test
    fun `scope contains five star initial and learnable SA skills only`() {
        val scope = SkillScopeCatalog.loadDefault()

        assertEquals(234, scope.fiveStarInitialSkillIds.size)
        assertEquals(83, scope.learnableSaSkillIds.size)
        assertEquals(308, scope.mainSkillIds.size)
        assertTrue(200017 in scope.mainSkillIds)
        assertTrue(200235 in scope.mainSkillIds)
        assertFalse(200100 in scope.learnableSaSkillIds)
    }
}
```

- [ ] **Step 2: 运行测试并确认因类型不存在而失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillScopeCatalogTest
```

Expected: `Unresolved reference: SkillScopeCatalog`。

- [ ] **Step 3: 扩展配置仓库并实现清单**

```kotlin
data class HeroBattleConfig(
    val id: Int,
    val name: String,
    val cost: Double,
    val hitRange: Int,
    val stats: BattleStats,
    val growth: BattleStats,
    val initialSkillId: Int,
    val qualityName: String,
)

data class SkillBattleConfig(
    val id: Int,
    val name: String,
    val kind: SkillKind,
    val hitRange: Int?,
    val prepareRounds: Int,
    val probabilityInit: Int,
    val probabilityMax: Int,
    val mainDetailId: Int,
    val mainDetail: SkillDetailConfig?,
    val mainEffect: SkillEffectConfig?,
    val qualityLevel: String,
)

data class SkillScope(
    val fiveStarInitialSkillIds: Set<Int>,
    val learnableSaSkillIds: Set<Int>,
) {
    val mainSkillIds: Set<Int> = fiveStarInitialSkillIds + learnableSaSkillIds
}

object SkillScopeCatalog {
    fun loadDefault(): SkillScope {
        val config = BattleConfigRepository.loadDefault()
        val fiveStar = config.allHeroes()
            .filter { it.qualityName == "五星" }
            .map { it.initialSkillId }
            .filter { it > 0 }
            .toSet()
        val learnableSa = SkillInventoryCatalog.allSkillIds()
            .filter { config.skill(it)?.qualityLevel in setOf("S", "A") }
            .toSet()
        return SkillScope(fiveStar, learnableSa)
    }
}
```

`BattleConfigRepository` 同时增加 `fun allHeroes(): Collection<HeroBattleConfig> = heroes.values`，并分别从
`hero_table.csv.quality_name` 与 `skill_table.csv.skill_quality_level` 填充上述字段；不得使用数值
`quality == 5` 判断五星。

- [ ] **Step 4: 运行清单与配置仓库测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.SkillScopeCatalogTest \
  --tests com.stzb.server.game.battle.BattleConfigRepositoryTest
```

Expected: PASS，清单为 `234 / 83 / 308`。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/SkillScopeCatalog.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillScopeCatalogTest.kt
git commit -m "test: lock complete skill scope"
```

---

### Task 2: 构建规则图与依赖闭包

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleModel.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuleCatalogTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleConfigRepositoryTest.kt`

**Interfaces:**
- Consumes: `SkillScope.mainSkillIds`、`BattleConfigRepository.skill()`、`skillDetails()`、`skillEffect()`。
- Produces: `SkillRuleCatalog.build(scope, config): SkillRuleGraph`；`SkillRuleGraph.rule(skillId)`；`executionNodeIds`；`effectIds`；`validate(): List<SkillDiagnostic>`。

- [ ] **Step 1: 写失败测试，锁定依赖闭包和循环校验**

```kotlin
@Test
fun `target skills expand to complete validated rule graph`() {
    val graph = SkillRuleCatalog.build(
        SkillScopeCatalog.loadDefault(),
        BattleConfigRepository.loadDefault(),
    )

    assertEquals(668, graph.executionNodeIds.size)
    assertEquals(112, graph.effectIds.size)
    assertEquals(1935, graph.details.size)
    assertTrue(graph.validate().isEmpty())
}

@Test
fun `recursive child skill path reports exact cycle`() {
    val graph = fakeGraph(1 to listOf(2), 2 to listOf(1))
    assertEquals("1 -> 2 -> 1", graph.validate().single().dependencyPath)
}

@Test
fun `repository preserves every field consumed by rule interpreters`() {
    val detail = BattleConfigRepository.loadDefault().skillDetails(200001).first()
    assertEquals(0, detail.effectParam)
    assertEquals(0, detail.selectSkillParam)
    assertEquals(2, detail.availableRounds)
    assertEquals(0, detail.delayRound)
    assertEquals(0, detail.delayHit)
    assertEquals(0, detail.castCondition)
    assertEquals(0, detail.precondition)
    assertEquals(0, detail.condition)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest
```

Expected: 编译失败，规则图类型不存在。

- [ ] **Step 3: 定义规范化规则类型**

```kotlin
data class SkillRule(
    val skillId: Int,
    val kind: SkillKind,
    val probability: Int,
    val prepareRounds: Int,
    val hitRange: Int?,
    val details: List<SkillEffectRule>,
)

data class SkillEffectRule(
    val detailId: Int,
    val effectId: Int,
    val childSkillIds: Set<Int>,
    val raw: SkillDetailConfig,
)

data class SkillDiagnostic(
    val skillId: Int,
    val detailId: Int?,
    val effectId: Int?,
    val code: String,
    val dependencyPath: String,
)
```

- [ ] **Step 4: 补齐原始配置字段并实现依赖提取、DFS 闭包和校验**

`SkillDetailConfig` 必须原样保存后续解释器使用的字段：

```kotlin
data class SkillDetailConfig(
    val detailId: Int,
    val effectId: Int,
    val effectParam: Int,
    val calcPos: Int,
    val calcParam: Int,
    val attackType: Int,
    val selectSkillParam: Int,
    val targetType: Int,
    val selectType: Int,
    val availableHit: Int,
    val intelParam: Int,
    val constantParam: Int,
    val probabilityInit: Int,
    val probabilityMax: Int,
    val bindFlag: Int,
    val castCondition: Int,
    val precondition: Int,
    val condition: Int,
    val addCountMax: Int,
    val buffType: Int,
    val attackMax: Int,
    val delayRound: Int,
    val delayHit: Int,
    val availableRounds: Int,
    val clearPerHit: Boolean,
    val selectFlag: Int,
    val inherent: Int,
    val moraleAffected: Boolean,
    val calculationType: Int,
    val effectName: String,
)

data class SkillEffectConfig(
    val effectId: Int,
    val name: String,
    val buffType: Int,
    val replaceType: Int,
    val valueType: Int,
)
```

子战法只从字段值确实存在于 `skill_table.csv` 的 `constant_param`、`effect_param` 和 `select_skill_param` 提取；值不是合法战法 ID 时保留为普通参数。

```kotlin
private fun childSkillIds(detail: SkillDetailConfig): Set<Int> =
    setOf(detail.constantParam, detail.effectParam, detail.selectSkillParam)
        .filter { config.skill(it) != null }
        .toSet()
```

- [ ] **Step 5: 运行规则图测试**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest
```

Expected: PASS，输出 `668 / 112 / 1935`，无断链或循环。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleModel.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleCatalog.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleConfigRepository.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuleCatalogTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleConfigRepositoryTest.kt
git commit -m "feat: build complete skill rule graph"
```

---

### Task 3: 建立统一触发上下文和运行状态

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillBattleContext.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuntimeState.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuntimeStateTest.kt`

**Interfaces:**
- Produces: `BattleTrigger` 枚举；`SkillBattleContext`；`PreparedSkill`；`DelayedEffect`；`SkillRuntimeState.count()`、`increment()`、`prepare()`、`interruptPreparations()`、`schedule()`、`dueEffects()`。

- [ ] **Step 1: 写失败测试覆盖准备、计数、延迟和双方隔离**

```kotlin
@Test
fun `runtime keys include side position hero and skill`() {
    val state = SkillRuntimeState()
    val attack = ref(Side.ATTACKER, 0, 100017)
    val defend = ref(Side.DEFENDER, 0, 100017)

    state.increment(attack, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017)

    assertEquals(1, state.count(attack, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017))
    assertEquals(0, state.count(defend, BattleTrigger.ACTIVE_SKILL_ATTEMPT, 200017))
}

@Test
fun `interrupt removes only matching hero preparations`() {
    val state = SkillRuntimeState()
    state.prepare(PreparedSkill(refA, 200031, readyRound = 2))
    state.prepare(PreparedSkill(refB, 200235, readyRound = 2))

    state.interruptPreparations(refA)

    assertEquals(listOf(200235), state.preparedSkills().map { it.skillId })
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillRuntimeStateTest
```

Expected: 运行状态类型不存在。

- [ ] **Step 3: 实现触发点和状态**

`BattleTrigger` 必须包含：

```kotlin
enum class BattleTrigger {
    BATTLE_PASSIVE, BATTLE_COMMAND, ROUND_START, ACTION_BEFORE,
    ACTIVE_SKILL_ATTEMPT, NORMAL_ATTACK_BEFORE, NORMAL_ATTACK_AFTER,
    DAMAGE_BEFORE, DAMAGE_AFTER, HURT_AFTER, PURSUIT_ATTEMPT,
    ACTION_AFTER, ROUND_END, BASE_HERO_DEFEATED,
}
```

计数键必须包含完整 `BattleHeroRef`、触发点和战法 ID；调用栈使用 `ArrayDeque<Int>` 并提供最大深度常量 `MAX_CHILD_DEPTH = 16`。

- [ ] **Step 4: 运行状态测试**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillRuntimeStateTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/SkillBattleContext.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuntimeState.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuntimeStateTest.kt
git commit -m "feat: add complete skill runtime context"
```

---

### Task 4: 实现配置驱动目标选择

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillTargetSelector.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillTargetSelectorTest.kt`

**Interfaces:**
- Consumes: `SkillEffectRule`、`SkillBattleContext`、`BattleRandom`。
- Produces: `select(rule, context): List<BattleHeroRef>`。

- [ ] **Step 1: 写失败测试覆盖目标全集**

参数化测试必须覆盖当前范围中的选择器：

```kotlin
@Test
fun `selector supports every target and select code in target scope`() {
    assertEquals(
        setOf(-30, -10, 0, 10, 20, 30, 42, 52, 53),
        graph.details.map { it.raw.targetType }.toSet(),
    )
    assertEquals(
        setOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 11, 33, 34, 900, 901, 907, 908, 3002),
        graph.details.map { it.raw.selectType }.toSet(),
    )
    graph.details.forEach { rule ->
        assertDoesNotThrow { selector.compile(rule) }
    }
}
```

另写确定性语义测试：自己、友军、敌军、全体、前中后排、最低/最高兵力与四维属性、状态筛选、范围外排除、随机不重复。

- [ ] **Step 2: 运行测试确认所有未支持代码被列出**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest
```

Expected: FAIL，消息列出尚未实现的 `target_type` 或 `select_type`。

- [ ] **Step 3: 实现编译后的选择器**

```kotlin
fun interface CompiledTargetSelector {
    fun select(context: SkillBattleContext): List<BattleHeroRef>
}

class SkillTargetSelector {
    fun compile(rule: SkillEffectRule): CompiledTargetSelector
}
```

随机候选先按客户端位置稳定排序，再使用 `BattleRandom.nextInt(size)` 抽取并移除，保证种子可复现。

- [ ] **Step 4: 运行目标选择测试**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillTargetSelectorTest
```

Expected: PASS，范围内未知目标和选择器为 0。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/SkillTargetSelector.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillTargetSelectorTest.kt
git commit -m "feat: interpret complete skill target selection"
```

---

### Task 5: 实现效果冲突、叠加与生命周期

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectStore.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/BattleEffectStoreTest.kt`

**Interfaces:**
- Produces: `ActiveSkillEffect`；`EffectApplyResult`；`apply()`、`consumeHit()`、`tick()`、`clear()`、`effectsFor()`。

- [ ] **Step 1: 写失败测试覆盖替换、叠加、回合、次数和绑定清除**

```kotlin
@Test
fun `stronger same category effect replaces weaker effect`() {
    val store = BattleEffectStore()
    assertEquals(APPLIED, store.apply(effect(strength = 10, replaceType = 2)))
    assertEquals(REPLACED, store.apply(effect(strength = 20, replaceType = 2)))
    assertEquals(listOf(20), store.effectsFor(target).map { it.strength })
}

@Test
fun `hit based effect expires after configured uses`() {
    val store = BattleEffectStore()
    store.apply(effect(remainingHits = 2, remainingRounds = null))
    store.consumeHit(target, effectId)
    store.consumeHit(target, effectId)
    assertTrue(store.effectsFor(target).isEmpty())
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest
```

Expected: 效果仓库类型不存在。

- [ ] **Step 3: 实现完整效果实例**

```kotlin
data class ActiveSkillEffect(
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val rootSkillId: Int,
    val skillId: Int,
    val skillKind: SkillKind,
    val detailId: Int,
    val effectId: Int,
    val category: EffectCategory,
    val strength: Int,
    val replaceType: Int,
    val bindFlag: Int,
    val maxStacks: Int,
    var stacks: Int,
    var remainingRounds: Int?,
    var remainingHits: Int?,
    val clearPerHit: Boolean,
)
```

同类判定必须包含目标、效果冲突组和来源战法类型；不能只按 `BattleStatus` 合并。

- [ ] **Step 4: 运行效果仓库测试与旧状态测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.BattleEffectStoreTest \
  --tests com.stzb.server.game.battle.BattleEffectStateTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectStore.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/BattleEffectStoreTest.kt
git commit -m "feat: model skill effect conflicts and lifecycle"
```

---

### Task 6: 建立严格效果注册表

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistryTest.kt`

**Interfaces:**
- Produces: `BattleEffectHandler`；`EffectExecution`；`BattleEffectRegistry.strict()`；`safe(logger)`；`execute(rule, context)`。

- [ ] **Step 1: 写失败测试锁定 112 个效果处理入口**

```kotlin
@Test
fun `strict registry covers every scoped effect id`() {
    val registry = BattleEffectRegistry.strict()
    assertEquals(graph.effectIds, registry.declaredEffectIds())
    assertEquals(emptySet(), registry.implementedEffectIds())
}

@Test
fun `strict mode reports full unknown effect context`() {
    val error = assertFailsWith<UnsupportedSkillRuleException> {
        registry.execute(fakeRule(skillId = 1, detailId = 101, effectId = 999), context)
    }
    assertTrue(error.message!!.contains("skill=1 detail=101 effect=999"))
}
```

- [ ] **Step 2: 运行测试确认 112 个效果全部缺失**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest
```

Expected: FAIL，缺失集合等于规则图效果集合。

- [ ] **Step 3: 定义处理器接口并注册显式占位处理器**

此任务只建立接口和“明确未实现”处理器；`declaredEffectIds()` 表示已建立显式入口，
`implementedEffectIds()` 只允许返回已有真实语义的处理器，二者不能混用。不能用无效果成功掩盖缺口。

```kotlin
fun interface BattleEffectHandler {
    fun execute(invocation: EffectInvocation): EffectExecution
}

data class EffectExecution(
    val stateChanges: List<BattleStateChange>,
    val events: List<BattleEvent>,
)
```

严格注册表对未实现处理器抛错；安全注册表记录诊断并返回 `EffectExecution.EMPTY`。

- [ ] **Step 4: 运行诊断测试**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.BattleEffectRegistryTest
```

Expected: 声明 ID 集合完整、实现 ID 集合为空；标记为未实现的处理器在严格模式仍失败。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistryTest.kt
git commit -m "feat: add strict battle effect registry"
```

---

### Task 7: 实现属性、伤害、恢复和增减伤效果

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/CoreEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleDamageCalculator.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/CoreEffectHandlersTest.kt`

**Interfaces:**
- Consumes: `BattleEffectHandler`、`BattleEffectStore`、`BattleDamageCalculator`。
- Produces: 已实现效果 ID：
  `101-106, 201-207, 301-307, 321, 322, 325, 331, 332, 335, 342, 351, 352, 355, 401, 402, 521-524, 531-534`。

- [ ] **Step 1: 写参数化失败测试**

```kotlin
@Test
fun `core effect family produces meaningful state or event`() {
    coreEffectIds.forEach { effectId ->
        val result = registry.execute(rule(effectId), context())
        assertTrue(
            result.events.isNotEmpty() || result.stateChanges.isNotEmpty(),
            "effect $effectId produced no behavior",
        )
    }
}
```

补充精确测试：攻击/防御/谋略/速度/攻城/距离增减；物理/策略/火攻/四类持续伤害；急救与休整只恢复伤兵；普通/主动/追击与物理/策略增减伤分类。

- [ ] **Step 2: 运行测试确认这些处理器失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest
```

Expected: 严格注册表报告第一个未实现效果 ID。

- [ ] **Step 3: 实现核心处理器**

统一数值入口：

```kotlin
interface BattleValueCalculator {
    fun effectValue(rule: SkillEffectRule, source: BattleHero): Int
    fun physicalDamage(invocation: EffectInvocation): Int
    fun strategyDamage(invocation: EffectInvocation, ongoing: Boolean): Int
    fun recovery(invocation: EffectInvocation): Int
}
```

恢复量取 `min(计算恢复, 伤兵, 兵力上限 - 当前兵力)`；不可恢复状态 207 存在时返回 0 并生成受阻事件。

- [ ] **Step 4: 运行核心效果与伤害回归**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.CoreEffectHandlersTest \
  --tests com.stzb.server.game.battle.BattleActionResolverTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/CoreEffectHandlers.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleDamageCalculator.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/CoreEffectHandlersTest.kt
git commit -m "feat: implement core configured battle effects"
```

---

### Task 8: 实现控制、免疫和行动修正效果

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/ControlEffectHandlers.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/ControlEffectHandlersTest.kt`

**Interfaces:**
- Produces: 已实现效果 ID：
  `501-506, 511-515, 542, 544-546, 551, 552, 571, 581, 594, 701-703, 711-714, 744, 752, 761, 771, 901-903, 952`。

- [ ] **Step 1: 写失败测试覆盖控制矩阵**

```kotlin
@Test
fun `control immunity matrix follows client effects`() {
    assertFalse(apply(501, targetWith(511)).applied) // 洞察免混乱
    assertFalse(apply(552, targetWith(594)).applied) // 免疫怯战
    assertTrue(apply(514, normalTarget()).applied)   // 规避
    assertTrue(apply(515, source()).events.any { it is BattleEvent.StatusApplied })
}
```

补充暴走随机阵营、援护/挑衅改目标、镇静清除、连击两次普攻、分兵、反击、先手、预备犹豫/怯战和不能普攻测试。

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.ControlEffectHandlersTest
```

Expected: 严格注册表报告未实现控制效果。

- [ ] **Step 3: 实现状态与行动决策**

```kotlin
data class ActionPermission(
    val canAct: Boolean,
    val canCastActive: Boolean,
    val canNormalAttack: Boolean,
    val redirectTarget: BattleHeroRef? = null,
)
```

`CompleteSkillEngine.permissionFor(actor, context)` 聚合控制和免疫；`BattleEngine` 不再直接检查多个 `BattleStatus`。

- [ ] **Step 4: 运行控制和旧战斗测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.ControlEffectHandlersTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --tests com.stzb.server.game.battle.BattleEngineSkillTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/ControlEffectHandlers.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/ControlEffectHandlersTest.kt
git commit -m "feat: implement configured control and action effects"
```

---

### Task 9: 实现元效果和子战法递归

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreter.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreterTest.kt`

**Interfaces:**
- Produces: 已实现效果 ID：
  `0, 77, 81-83, 88, 111-114, 118, 121-123, 125, 127, 129-131, 141, 149, 151-153, 161, 171, 181, 199, 200, 210, 231, 261, 281, 313, 404, 407-409`。
- Produces: `SkillRuleInterpreter.execute(skillId, trigger, context): SkillExecutionResult`。

- [ ] **Step 1: 写失败测试覆盖递归、替换和循环保护**

```kotlin
@Test
fun `child skill inherits root source and emits child effects`() {
    val result = interpreter.execute(200017, BattleTrigger.ACTIVE_SKILL_ATTEMPT, context)

    assertTrue(result.executedSkillIds.contains(210017))
    assertTrue(result.events.all { it.rootSkillId == 200017 })
}

@Test
fun `recursive child call fails with dependency path`() {
    val error = assertFailsWith<SkillRecursionException> {
        interpreter.execute(1, BattleTrigger.ACTIVE_SKILL_ATTEMPT, cyclicContext)
    }
    assertTrue(error.message!!.contains("1 -> 2 -> 1"))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillRuleInterpreterTest
```

Expected: 解释器不存在或子战法未执行。

- [ ] **Step 3: 实现规则执行顺序**

```kotlin
fun execute(skillId: Int, trigger: BattleTrigger, context: SkillBattleContext): SkillExecutionResult {
    context.runtime.enter(skillId)
    try {
        return graph.rule(skillId).details
            .filter { conditionInterpreter.matches(it, trigger, context) }
            .fold(SkillExecutionResult.EMPTY) { result, rule ->
                result + executeDetail(rule, context)
            }
    } finally {
        context.runtime.exit(skillId)
    }
}
```

效果 122/123 递归调用其子战法；129/130 重新发动时仍受调用深度和次数限制；151-153 触发指定效果；152 清除目标效果。

- [ ] **Step 4: 运行元效果和规则图测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.SkillRuleInterpreterTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleCatalogTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/MetaEffectHandlers.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreter.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/BattleEffectRegistry.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreterTest.kt
git commit -m "feat: execute child and meta skill effects"
```

---

### Task 10: 实现条件解释器

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillConditionInterpreter.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillConditionInterpreterTest.kt`

**Interfaces:**
- Consumes: `castCondition`、`precondition`、`condition` 和 `SkillBattleContext`。
- Produces: `compile(rule): CompiledSkillCondition`；`matches(rule, trigger, context): Boolean`。

- [ ] **Step 1: 写覆盖测试，要求范围内条件码全部可编译**

```kotlin
@Test
fun `every scoped condition code has explicit semantics`() {
    graph.details.forEach { detail ->
        assertDoesNotThrow { interpreter.compile(detail) }
    }
    assertEquals(emptySet(), interpreter.unknownCodes())
}
```

另写语义测试覆盖回合、兵力比例、英雄 ID、状态、发动次数、受伤次数、普攻次数、主动/追击发动和正负条件。

- [ ] **Step 2: 运行测试取得未知条件码全集**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillConditionInterpreterTest
```

Expected: FAIL，诊断按字段输出 `cast_condition`、`precondition`、`condition` 未支持值。

- [ ] **Step 3: 实现条件解码**

条件码按客户端字段编码拆解为结构化条件，不把未知值当作 `true`：

```kotlin
sealed interface SkillCondition {
    data class RoundRange(val first: Int, val last: Int) : SkillCondition
    data class TroopRatio(val side: Subject, val comparison: Comparison, val percent: Int) : SkillCondition
    data class HasEffect(val subject: Subject, val effectId: Int, val negated: Boolean) : SkillCondition
    data class TriggerCount(val trigger: BattleTrigger, val comparison: Comparison, val value: Int) : SkillCondition
    data class HeroId(val subject: Subject, val heroId: Int, val negated: Boolean) : SkillCondition
}
```

无法从公开字段编码确认语义的码进入 `SpecialSkillPlugin` 清单，且必须由具体战法测试证明。

- [ ] **Step 4: 运行条件和解释器测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.SkillConditionInterpreterTest \
  --tests com.stzb.server.game.battle.skill.SkillRuleInterpreterTest
```

Expected: PASS，目标范围未知条件码为 0。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/SkillConditionInterpreter.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillConditionInterpreterTest.kt
git commit -m "feat: interpret scoped skill conditions"
```

---

### Task 11: 实现准备、延迟和次数队列

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuntimeState.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreter.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillTimingTest.kt`

**Interfaces:**
- Produces: 准备开始/取消/完成；`delay_round`、`delay_hit`、`available_hit`、`clear_per_hit`；同战法本回合只尝试一次。

- [ ] **Step 1: 写失败测试覆盖所有时间语义**

```kotlin
@Test
fun `prepared skill rolls once and completes on ready round`() {
    val first = engine.trigger(ACTIVE_SKILL_ATTEMPT, context(round = 1, skill = 200031))
    val second = engine.trigger(ACTIVE_SKILL_ATTEMPT, context(round = 2, skill = 200031))

    assertEquals(1, random.callsForProbability)
    assertTrue(first.events.any { it is BattleEvent.SkillPreparationStarted })
    assertTrue(second.events.any { it is BattleEvent.SkillDamage })
}

@Test
fun `confusion and hesitation cancel prepared skill`() {
    state.prepare(prepared(200031))
    engine.permissionFor(actorWith(CONFUSION), context)
    assertTrue(state.preparedSkills().isEmpty())
}
```

补充延迟回合、延迟命中、使用次数耗尽、每次命中清除、同技能不可重复准备测试。

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SkillTimingTest
```

Expected: 延迟或次数语义断言失败。

- [ ] **Step 3: 实现时间队列**

准备项保存 `SkillExecutionSnapshot`，包含来源、根战法、当前战法、触发点和开始回合；完成时重新选择目标但不重新投发动率。延迟项按 `(dueRound, dueHit)` 稳定排序。

- [ ] **Step 4: 运行时间和旧准备测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.SkillTimingTest \
  --tests com.stzb.server.game.battle.BattleSkillRuntimeTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuntimeState.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/SkillRuleInterpreter.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillTimingTest.kt
git commit -m "feat: execute prepared and delayed skill rules"
```

---

### Task 12: 建立完整战法引擎接口并接入战斗阶段

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngine.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleActionResolver.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngineIntegrationTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEngineSkillTest.kt`

**Interfaces:**
- Produces:

```kotlin
interface CompleteSkillEngine {
    fun prepareBattle(context: SkillBattleContext): List<BattleEvent>
    fun trigger(trigger: BattleTrigger, context: SkillBattleContext): List<BattleEvent>
    fun permissionFor(actor: BattleHeroRef, context: SkillBattleContext): ActionPermission
}
```

- [ ] **Step 1: 写失败集成测试锁定阶段顺序**

```kotlin
@Test
fun `battle executes passive command active normal pursuit and end hooks in order`() {
    val result = resolve(teamWithAllSkillKinds(), opponent(), FixedBattleRandom(0))
    val phases = result.events.filterIsInstance<BattleEvent.SkillTriggered>().map { it.trigger }

    assertTrue(phases.indexOf(BATTLE_PASSIVE) < phases.indexOf(BATTLE_COMMAND))
    assertTrue(phases.indexOf(ACTIVE_SKILL_ATTEMPT) < result.firstNormalAttackIndex())
    assertTrue(result.firstNormalAttackIndex() < phases.indexOf(PURSUIT_ATTEMPT))
}
```

- [ ] **Step 2: 运行测试确认现有引擎没有统一阶段事件**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.CompleteSkillEngineIntegrationTest
```

Expected: FAIL。

- [ ] **Step 3: 接入两个入口**

`BattleEngine.resolve(config, random)` 创建一次 `CompleteSkillEngine` 和一次 `SkillRuntimeState`。所有阶段调用 `trigger`；普攻前后、伤害前后和大营阵亡都发触发点。删除 `BattleEngine` 中与新模块重复的控制、持续伤害和状态追加分支。

- [ ] **Step 4: 运行战斗阶段回归**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.CompleteSkillEngineIntegrationTest \
  --tests com.stzb.server.game.battle.BattleEngineSkillTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --tests com.stzb.server.game.battle.BattleEngineTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngine.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleActionResolver.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillEngineIntegrationTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleEngineSkillTest.kt
git commit -m "feat: integrate complete skill engine phases"
```

---

### Task 13: 迁移特殊战法插件并移除双重执行

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SpecialSkillPlugin.kt`
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/ConfiguredSpecialSkillPlugins.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SpecialSkillPluginTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/LegacySkillCatalogTest.kt`

**Interfaces:**
- Produces: `SpecialSkillPlugin.supports(skillId)`；`execute(invocation)`；`SpecialSkillPluginRegistry`。

- [ ] **Step 1: 写覆盖报告失败测试，列出必须插件化的战法**

```kotlin
@Test
fun `every non declarative scoped skill has exactly one plugin`() {
    val report = SkillCoverageReport.generate(graph, registry, conditionInterpreter, plugins)

    assertEquals(emptySet(), report.missingPluginSkillIds)
    assertEquals(emptySet(), report.duplicateExecutionSkillIds)
}
```

- [ ] **Step 2: 运行测试获得实际插件清单**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.SpecialSkillPluginTest
```

Expected: FAIL，输出无法由已实现字段语义解释的具体战法 ID。

- [ ] **Step 3: 按覆盖报告逐个实现非声明式战法**

覆盖报告中的每个 `missingPluginSkillId` 都必须对应一个以真实主战法 ID 命名的插件。插件只通过统一解释器重新选择目标、执行子战法或调整运行状态；没有专属逻辑的战法不得创建空插件。注册和调用接口固定为：

```kotlin
interface SpecialSkillPlugin {
    val skillIds: Set<Int>
    fun execute(invocation: SkillInvocation): SkillExecutionResult
}

class SpecialSkillPluginRegistry(plugins: List<SpecialSkillPlugin>) {
    private val bySkillId = plugins
        .flatMap { plugin -> plugin.skillIds.map { skillId -> skillId to plugin } }
        .groupBy({ it.first }, { it.second })

    fun pluginFor(skillId: Int): SpecialSkillPlugin? {
        val matches = bySkillId[skillId].orEmpty()
        require(matches.size <= 1) { "duplicate special skill plugin: $skillId" }
        return matches.singleOrNull()
    }
}
```

每个报告出的战法先增加一个失败的精确语义测试，再实现对应插件，直到
`missingPluginSkillIds` 和 `duplicateExecutionSkillIds` 同时为空。不得复制通用伤害、目标或状态计算。
能用解释器表达的现有 32 个 `LegacySkillCatalog` 定义删除手写分支；真正特殊的迁入插件。
`BattleSkillRuntime` 不再先查 `LegacySkillCatalog`。

- [ ] **Step 4: 运行插件、旧 32 战法和完整引擎测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.SpecialSkillPluginTest \
  --tests com.stzb.server.game.battle.LegacySkillCatalogTest \
  --tests com.stzb.server.game.battle.skill.CompleteSkillEngineIntegrationTest
```

Expected: PASS；每个目标战法只有一条执行路径。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/SpecialSkillPlugin.kt \
  src/main/kotlin/com/stzb/server/game/battle/skill/ConfiguredSpecialSkillPlugins.kt \
  src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SpecialSkillPluginTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/LegacySkillCatalogTest.kt
git commit -m "feat: migrate special skills to plugins"
```

---

### Task 14: 完整战报事件与客户端编码

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/skill/SkillReportCoverageTest.kt`

**Interfaces:**
- Produces: 结构化事件 `SkillTriggered`、`SkillPreparationCancelled`、`SkillPreparationCompleted`、`StatusRemoved`、`EffectBlocked`、`EffectExpired`，以及对应客户端动作。

- [ ] **Step 1: 写失败测试要求每类执行事件可编码**

```kotlin
@Test
fun `every scoped effect event is encodable without unsupported projection`() {
    graph.effectIds.forEach { effectId ->
        val result = fixture.executeEffect(effectId)
        val actions = ClientBattleTextReplayAdapter.adapt(result)
        assertTrue(actions.isNotEmpty(), "effect $effectId produced no client action")
        assertTrue(result.events.none { it is BattleEvent.UnsupportedSkillEffect })
    }
}
```

另写准备取消/完成、状态消失、免疫阻挡和技能类型发动动作顺序测试。

- [ ] **Step 2: 运行测试确认缺失动作**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.SkillReportCoverageTest \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest
```

Expected: FAIL，列出无客户端动作的事件类型。

- [ ] **Step 3: 增加事件和适配映射**

适配层只能读取事件字段并编码；不能根据效果 ID 重新计算目标、伤害或状态。未知事件在严格测试中失败，正式编码记录并忽略。

- [ ] **Step 4: 运行战报、压缩与存储测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.SkillReportCoverageTest \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest \
  --tests com.stzb.server.game.battle.BattleReportCodecTest \
  --tests com.stzb.server.game.battle.ClientBattleReportStoreTest
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/SkillReportCoverageTest.kt
git commit -m "feat: encode complete skill battle events"
```

---

### Task 15: 建立 308 主战法硬性覆盖门

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/skill/SkillCoverageReport.kt`
- Create: `src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillCoverageTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleCoverageSmokeTest.kt`

**Interfaces:**
- Produces: `SkillCoverageReport.generate(...)`，字段包含 `mainSkills`、`executionNodes`、`effectIds`、`unsupportedEffects`、`unknownConditions`、`unknownSelectors`、`brokenDependencies`、`noBehaviorSkills`。

- [ ] **Step 1: 写失败覆盖门**

```kotlin
@Test
fun `all scoped skills execute meaningful deterministic battles`() {
    val report = SkillCoverageReport.generateDefault()

    assertEquals(308, report.mainSkills)
    assertEquals(668, report.executionNodes)
    assertEquals(112, report.effectIds)
    assertEquals(emptySet(), report.unsupportedEffects)
    assertEquals(emptySet(), report.unknownConditions)
    assertEquals(emptySet(), report.unknownSelectors)
    assertEquals(emptySet(), report.brokenDependencies)
    assertEquals(emptySet(), report.noBehaviorSkills)
}
```

每个主战法的参数化战斗至少断言一种可观察结果：伤害、恢复、状态、属性、准备、目标变化、触发计数或战报事件。

- [ ] **Step 2: 运行覆盖门并保存首次报告**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.CompleteSkillCoverageTest
```

Expected: 若前置任务仍有遗漏，FAIL 并列出精确战法/效果；不得通过修改期望集合绕过。

- [ ] **Step 3: 补齐报告发现的具体缺口**

缺口只能按类别修到对应模块：

- 效果 ID → `CoreEffectHandlers`、`ControlEffectHandlers` 或 `MetaEffectHandlers`。
- 条件码 → `SkillConditionInterpreter`。
- 目标码 → `SkillTargetSelector`。
- 非声明式机制 → `ConfiguredSpecialSkillPlugins`。
- 断链 → `SkillRuleCatalog` 配置解析。

- [ ] **Step 4: 重跑覆盖门直至严格为零**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.CompleteSkillCoverageTest
```

Expected: PASS，`308 / 668 / 112 / 1935` 且所有缺口集合为空。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/skill/SkillCoverageReport.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/CompleteSkillCoverageTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleCoverageSmokeTest.kt
git commit -m "test: enforce complete scoped skill coverage"
```

---

### Task 16: 真包黄金回归

**Files:**
- Create: `src/test/kotlin/com/stzb/server/game/battle/skill/PaperBattleFixtureLoader.kt`
- Create: `src/test/kotlin/com/stzb/server/game/battle/skill/PaperBattleGoldenTest.kt`
- Create: `src/test/resources/battle-golden/paper-samples.json`

**Interfaces:**
- Consumes: `server/assent/cfg/paper/92/*_zlib.json` 战报包。
- Produces: 稳定、脱敏、体积受控的黄金样本；不在测试运行时扫描 4 万个真包文件。

- [ ] **Step 1: 选取覆盖不同机制的真包样本**

选择至少包含以下组合的报文：主动准备、追击、指挥控制、持续伤害、恢复、连击、洞察/规避、双方大营未死平局。抽取字段：

```json
{
  "source_file": "cap_20260312034508594_0000005c_zlib.json",
  "heroes": [100016, 100792, 100449, 100770, 100993, 100252],
  "skills": [200016, 200784, 200766, 200201, 200249, 200252],
  "expected": {
    "outcome": "DRAW",
    "required_skill_ids": [200016, 200201],
    "required_status_effect_ids": [501],
    "attacker_damage_positive": true,
    "defender_damage_positive": true
  }
}
```

- [ ] **Step 2: 写失败黄金测试**

```kotlin
@Test
fun `paper samples preserve phases targets statuses losses and outcome`() {
    fixtures.forEach { fixture ->
        val result = fixture.run()
        assertEquals(fixture.expected.outcome, result.outcome)
        assertTrue(result.skillIds().containsAll(fixture.expected.requiredSkillIds))
        assertTrue(result.effectIds().containsAll(fixture.expected.requiredStatusEffectIds))
        assertTrue(result.hasDamageFromBothSides())
    }
}
```

- [ ] **Step 3: 运行测试并确认实际差异**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.PaperBattleGoldenTest
```

Expected: 首次运行明确列出阶段、目标、状态、兵损方向或胜负差异。

- [ ] **Step 4: 只按真包证据校准规则**

数值随机上下文未知时断言方向和合理区间；阶段、目标阵营、状态类型、持续时间和胜负必须精确一致。任何校准必须在相应效果或插件测试中增加最小回归用例。

- [ ] **Step 5: 运行黄金与完整覆盖测试**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.battle.skill.PaperBattleGoldenTest \
  --tests com.stzb.server.game.battle.skill.CompleteSkillCoverageTest
```

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/test/kotlin/com/stzb/server/game/battle/skill/PaperBattleFixtureLoader.kt \
  src/test/kotlin/com/stzb/server/game/battle/skill/PaperBattleGoldenTest.kt \
  src/test/resources/battle-golden/paper-samples.json
git commit -m "test: calibrate complete skills from paper battles"
```

---

### Task 17: 全量集成、清理旧路径与最终验证

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillInterpreter.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt`

**Interfaces:**
- Consumes: `CompleteSkillEngine`。
- Produces: PVE 出征、连续守军、占地和战报只使用完整战法引擎。

- [ ] **Step 1: 写失败端到端测试**

```kotlin
@Test
fun `PVE settlement executes all equipped scoped skills and occupies only on real win`() {
    val settlement = fixture.withThreeHeroesAndSaSkills().settle()
    val report = fixture.report(settlement.battleId)

    assertTrue(report.result.events.any { it.skillId == 200017 })
    assertTrue(report.result.events.any { it.skillId == 200235 })
    assertEquals(report.result.outcome == ATTACKER_WIN, fixture.state.ownsLand(fixture.targetWid))
    assertTrue(report.result.events.none { it is BattleEvent.UnsupportedSkillEffect })
}
```

- [ ] **Step 2: 运行端到端测试确认旧路径仍被调用**

Run:

```bash
./gradlew test \
  --tests com.stzb.server.game.PlayerBattleServiceTest \
  --tests com.stzb.server.game.battle.BattleIntegrationTest
```

Expected: 新断言失败或发现旧运行时路径。

- [ ] **Step 3: 删除权威路径重复**

`PlayerBattleService` 只通过 `BattleEngine.resolve(request, config, random)` 进入完整引擎。旧 `BattleSkillInterpreter`、`BattleSkillRuntime` 和 `LegacySkillCatalog` 若无调用则删除；若保留兼容类型，内部必须委托 `CompleteSkillEngine`，不得保留另一套效果计算。

- [ ] **Step 4: 运行全部战斗测试**

Run:

```bash
./gradlew test --tests 'com.stzb.server.game.battle.*' \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

Expected: PASS。

- [ ] **Step 5: 运行全量测试与发行构建**

Run:

```bash
./gradlew clean test installDist \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

Expected: `BUILD SUCCESSFUL`，测试失败和错误均为 0。

- [ ] **Step 6: 验证覆盖报告和工作区**

Run:

```bash
./gradlew test --tests com.stzb.server.game.battle.skill.CompleteSkillCoverageTest \
  --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
git diff --check
git status --short
```

Expected: 覆盖测试 PASS；`git diff --check` 无输出；只保留用户原有改动和本计划相关改动。

- [ ] **Step 7: 提交最终集成**

```bash
git add src/main/kotlin/com/stzb/server/game/battle/BattleSkillInterpreter.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt \
  src/main/kotlin/com/stzb/server/game/battle/LegacySkillCatalog.kt \
  src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt \
  src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt
git commit -m "feat: complete five star and SA skill engine"
```

## Final Acceptance Checklist

- [ ] `SkillScopeCatalogTest` 证明主战法为 308。
- [ ] `SkillRuleCatalogTest` 证明执行节点为 668、效果为 112、明细为 1935。
- [ ] `CompleteSkillCoverageTest` 中所有缺口集合为空。
- [ ] 308 个主战法均产生有意义的确定性结果。
- [ ] 112 种效果均有显式处理器和语义测试。
- [ ] 目标范围内不存在 `UnsupportedSkillEffect`。
- [ ] 准备、延迟、次数、条件、目标和子战法递归均有组合测试。
- [ ] 真包黄金样本的阶段、目标、状态、兵损方向和胜负一致。
- [ ] 出征、多队守军、胜负、0 兵结算、占地和客户端战报测试通过。
- [ ] `clean test installDist` 成功。
- [ ] 未启动游戏服务。
