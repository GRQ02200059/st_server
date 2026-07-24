# 客户端战斗文字回放 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `BattleEngine` 的完整事件流转换为客户端 `cmd=11` 可显示的文字战报，使准备回合后稳定显示实际执行的 1 至 8 回合、伤害、恢复、状态、兵力结算和胜负。

**Architecture:** 新增 `ClientBattleTextReplayAdapter` 和集中协议表，作为 `BattleResult` 与 `ClientReportTextEncoder` 之间的唯一转换层。适配器只生成已在真实 `cmd=11` 样本中出现且由客户端 `ReportDetailHelper` 支持的文字动作；不发送视频回放的镜头、角色移动、特效或动画分组动作。

**Tech Stack:** Kotlin/JVM 17、Gradle、`kotlin.test`、Jackson Kotlin、JDK `ZipFile`、真实抓包 `assent/cfg/paper.zip`。

## Global Constraints

- 结算真相只能来自 `BattleEngine`；协议层不得重算伤害、恢复、状态持续时间、最终兵力或胜负。
- 本轮只实现文字回放，不实现战斗视频、镜头、角色移动、特效、音效或原服专属战法动画。
- 整个动作流只能出现一个 `04`；每个 `BattleEvent.RoundStart(round)` 必须恰好输出一个 `09<round>`。
- 每条下行文字动作都必须来自真实 `paper.zip/0000000b` 样本并适配客户端 `ReportDetailHelper` 的参数形状。
- 不得继续输出通用占位动作 `0u`（base36 动作 `30`）。
- 攻击方位置固定映射为 `1..3`，防守方位置固定映射为 `4..6`。
- 未支持的效果保留在 `BattleReportCodec.toJson()` 的服务端 JSON 中；客户端文字战报不得把它伪装成伤害。
- 所有新行为必须先写失败测试，再写最小实现。
- Gradle 使用：`./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process`。
- 任务明确列出的 Kotlin 源码与测试文件可作为本次工作的版本控制基线，即使它们当前未跟踪；每个提交不得暂存任何未在当前任务文件清单中列出的未跟踪文件。

---

## File Structure

- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt`: 为恢复、状态、持续伤害和属性变化事件保留产生它们的战法 ID；让持续状态把该 ID 跨回合带入 DOT 事件。
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt`: 在所有由战法产生的 `Recovery` 和 `StatusApplied` 事件上写入当前 `skillId`。
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt`: 将战法 ID 存入 `ActiveBattleStatus`，写入 `StatChanged`，并在 DOT 时回传。
- Modify `src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt`: JSON 调试战报输出新的战法归属字段。
- Create `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`: 定义不可变的客户端文字动作记录、base36 编码、位置映射、状态/属性效果映射和经抓包验证的动作常量。
- Create `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`: 将引擎事件投影为准备阶段、回合、文字事件和结算动作。
- Modify `src/main/kotlin/com/stzb/server/game/battle/ClientReportTextEncoder.kt`: 委托给文字回放适配器，删除目前把所有事件压缩为动作 `30` 的分支。
- Modify `src/test/kotlin/com/stzb/server/game/battle/BattleSkillRuntimeTest.kt`: 验证恢复和状态事件携带准确的战法 ID。
- Modify `src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt`: 验证 DOT 和属性变化跨回合保留战法 ID。
- Modify `src/test/kotlin/com/stzb/server/game/battle/BattleReportCodecTest.kt`: 验证压缩后的文字动作流。
- Modify `src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt`: 使用真实配置验证 1 至 8 回合完整文字回放。
- Modify `src/test/kotlin/com/stzb/server/game/battle/ClientBattleReportStoreTest.kt`: 解压 `cmd=11` 详情并断言其经过新适配器。
- Create `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocolTest.kt`: 从 `paper.zip` 解码真实动作 ID，锁定允许的动作族和真实的 `04`/`09` 形状。
- Create `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`: 按构造的 `BattleResult` 精确断言回合、事件字段、结算动作和禁用 `0u`。

## Protocol Table

实现时只能使用以下记录。动作 ID 在代码中保存为十进制，写出时用两位 base36 前缀。

| 常量名 | 十进制 ID | base36 | 参数顺序 | 用途 |
| --- | ---: | --- | --- | --- |
| `HERO_NAME` | 14 | `0e` | `position,heroId` | 让 `ReportDetailHelper` 将位置解析为英雄名 |
| `PREPARE` | 4 | `04` | 无 | 建立唯一准备阶段 |
| `ROUND` | 9 | `09` | `round` | 建立后续回合栏 |
| `NORMAL_DAMAGE` | 62 | `1q` | `target,source,0,damage,targetTroopsAfter` | 普攻文字；战法 ID `0` 走客户端的特殊战法名称 |
| `SKILL_CAST` | 301 | `8d` | `source,source,skillId` | 技能、恢复、状态和 DOT 的文字归属 |
| `SKILL_DAMAGE` | 60 | `1o` | `source,skillId,target,damage,targetTroopsAfter` | 主动/追击技能伤害 |
| `ONGOING_DAMAGE` | 59 | `1n` | `source,skillId,target,damage,targetTroopsAfter,effectId` | 持续状态造成的伤害 |
| `RECOVERY` | 63 | `1r` | `source,skillId,target,amount,targetTroopsAfter` | 恢复兵力 |
| `STATUS` | 102 | `2u` | `source,target,skillId,effectId` | 状态、属性变化和规避 |
| `END` | 13 | `0d` | 无 | 进入客户端战报结算段 |
| `FINAL_TROOPS` | 224 | `68` | `position,troops,wounded` | 双方每个英雄的最终兵力 |

`wounded` 必须为 `(hero.maxTroops - hero.troops).coerceAtLeast(0)`。真实样本在 `0d` 后输出全部 `68` 记录，因此实现也必须保持 `END -> FINAL_TROOPS*` 的顺序。

## Task 1: 保留战法归属元数据

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt:150-203`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt:108-170`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt:150-225, 368-396`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt:71-129`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleSkillRuntimeTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleReportCodecTest.kt`

**Interfaces:**
- Consumes: 当前 `SkillRuntimeState`、`ActiveBattleStatus` 与所有 `BattleEvent`。
- Produces:
  - `BattleEvent.Recovery.skillId: Int = 0`
  - `BattleEvent.StatusApplied.skillId: Int = 0`
  - `BattleEvent.OngoingDamage.skillId: Int = 0`
  - `BattleEvent.StatChanged.skillId: Int = 0`
  - `ActiveBattleStatus.skillId: Int = 0`

- [ ] **Step 1: 写恢复和状态战法归属的失败测试**

在 `BattleSkillRuntimeTest` 的 `recovery status and unsupported effects are emitted` 后追加：

```kotlin
@Test
fun `recovery and status events retain the casting skill id`() {
    val sourceRef = BattleHeroRef(Side.ATTACKER, 0, BattleHeroId(100017))
    val source = hero(100017, skillIds = listOf(200001))
    val allies = BattleTeam(listOf(source.copy(troops = 500), hero(2, position = 1, troops = 400)))
    val recovery = runtime.tryAct(
        1,
        sourceRef,
        source,
        BattleTeam(listOf(hero(1, position = 0))),
        allies,
        FixedBattleRandom(0),
        SkillRuntimeState(),
    )!!.events.filterIsInstance<BattleEvent.Recovery>()

    val disorder = runtime.tryAct(
        1,
        sourceRef,
        source.copy(skillIds = listOf(200002)),
        BattleTeam(listOf(hero(1, position = 0))),
        allies,
        FixedBattleRandom(0),
        SkillRuntimeState(),
    )!!.events.filterIsInstance<BattleEvent.StatusApplied>()

    assertTrue(recovery.isNotEmpty())
    assertTrue(recovery.all { it.skillId == 200001 })
    assertTrue(disorder.isNotEmpty())
    assertTrue(disorder.all { it.skillId == 200002 })
}
```

- [ ] **Step 2: 运行测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.BattleSkillRuntimeTest
```

Expected: FAIL，因为 `Recovery` 与 `StatusApplied` 尚不存在 `skillId` 属性。

- [ ] **Step 3: 写 DOT 与属性变化归属的失败测试**

在 `BattleEnginePlayableTest` 追加：

```kotlin
@Test
fun `dot and stat change events preserve their originating skill id`() {
    val dotResult = BattleEngine.resolve(
        BattleRequest(
            attacker = BattleTeam(listOf(hero(100002, 0, skillIds = listOf(200002)))),
            defender = BattleTeam(listOf(hero(1, 0))),
            maxRounds = 3,
        ),
        repo,
        FixedBattleRandom(0),
    )
    val buffResult = BattleEngine.resolve(
        BattleRequest(
            attacker = BattleTeam(listOf(hero(100036, 0, skillIds = listOf(200036)))),
            defender = BattleTeam(listOf(hero(2, 0))),
            maxRounds = 2,
        ),
        repo,
        FixedBattleRandom(0),
    )

    assertTrue(dotResult.events.filterIsInstance<BattleEvent.OngoingDamage>().all { it.skillId == 200002 })
    assertTrue(buffResult.events.filterIsInstance<BattleEvent.StatChanged>().all { it.skillId == 200036 })
}
```

- [ ] **Step 4: 运行测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest
```

Expected: FAIL，因为 `OngoingDamage` 与 `StatChanged` 尚不存在 `skillId` 属性。

- [ ] **Step 5: 最小实现事件和状态元数据**

在 `BattleModel.kt` 为以下数据类追加末尾默认参数，避免现有构造点立即失效：

```kotlin
data class Recovery(
    val round: Int,
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val amount: Int,
    val targetTroopsAfter: Int,
    val skillId: Int = 0,
) : BattleEvent

data class StatusApplied(
    val round: Int,
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val status: BattleStatus,
    val durationRounds: Int,
    val power: Int = 0,
    val statDelta: BattleStats = BattleStats.ZERO,
    val skillId: Int = 0,
) : BattleEvent

data class OngoingDamage(
    val round: Int,
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val status: BattleStatus,
    val damage: Int,
    val targetTroopsAfter: Int,
    val skillId: Int = 0,
) : BattleEvent

data class StatChanged(
    val round: Int,
    val source: BattleHeroRef,
    val target: BattleHeroRef,
    val stat: BattleStat,
    val delta: Int,
    val durationRounds: Int,
    val skillId: Int = 0,
) : BattleEvent
```

并在 `ActiveBattleStatus` 的 `statDelta` 后增加：

```kotlin
val skillId: Int = 0,
```

在 `BattleSkillRuntime.executeDetails()` 中，所有 `Recovery(...)` 和
`StatusApplied(...)` 构造器传入 `skillId = skillId`。

在 `BattleEngine.applySkillCastResult()`：

```kotlin
skillId = event.skillId,
```

传入每个新建的 `ActiveBattleStatus`；构造 `StatChanged` 时传入：

```kotlin
skillId = skillCast.skillId,
```

在 `applyOngoingStatuses()` 的 `OngoingDamage(...)` 构造器传入：

```kotlin
skillId = active.skillId,
```

最后扩展 `BattleReportCodec.toReportMap()`，为 `Recovery`、`StatusApplied`、
`OngoingDamage` 和 `StatChanged` 各追加：

```kotlin
"skillId" to event.skillId,
```

- [ ] **Step 6: 验证绿灯并运行现有战斗包测试**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.BattleSkillRuntimeTest \
  --tests com.stzb.server.game.battle.BattleEnginePlayableTest \
  --tests com.stzb.server.game.battle.BattleReportCodecTest
```

Expected: PASS，恢复、状态、DOT、属性变化均携带实际 `skillId`，已有 JSON 测试仍通过。

- [ ] **Step 7: 提交任务**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/BattleModel.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleSkillRuntime.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleEngine.kt \
  src/main/kotlin/com/stzb/server/game/battle/BattleReportCodec.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleSkillRuntimeTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleEnginePlayableTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleReportCodecTest.kt
git commit -m "feat: retain skill ids in battle events"
```

## Task 2: 建立抓包约束的文字协议表

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocolTest.kt`

**Interfaces:**
- Produces:

```kotlin
internal data class ClientReportAction(
    val id: Int,
    val params: List<Any> = emptyList(),
) {
    fun encode(): String
}

internal object ClientBattleTextReplayProtocol {
    const val HERO_NAME = 14
    const val PREPARE = 4
    const val ROUND = 9
    const val NORMAL_DAMAGE = 62
    const val SKILL_CAST = 301
    const val SKILL_DAMAGE = 60
    const val ONGOING_DAMAGE = 59
    const val RECOVERY = 63
    const val STATUS = 102
    const val END = 13
    const val FINAL_TROOPS = 224

    fun position(side: Side, formationPosition: Int): Int
    fun position(ref: BattleHeroRef): Int = position(ref.side, ref.position)
    fun effectId(status: BattleStatus): Int
    fun effectId(stat: BattleStat, delta: Int): Int
}
```

- [ ] **Step 1: 写真实抓包动作族的失败测试**

创建 `ClientBattleTextReplayProtocolTest.kt`：

```kotlin
package com.stzb.server.game.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientBattleTextReplayProtocolTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `reference cmd 11 contains every text replay action family`() {
        val report = ZipFile(Path.of("assent/cfg/paper.zip").toFile()).use { zip ->
            val entry = zip.getEntry("0000000b/cap_20260311222842345_0000000b_zlib.json")!!
            zip.getInputStream(entry).bufferedReader().use { reader ->
                mapper.readTree(reader)[1]["report"].asText()
            }
        }
        val ids = report.split("#").filter(String::isNotBlank).map { record ->
            record.take(2).toInt(36)
        }

        assertEquals(1, ids.count { it == ClientBattleTextReplayProtocol.PREPARE })
        assertEquals(8, ids.count { it == ClientBattleTextReplayProtocol.ROUND })
        assertTrue(
            setOf(
                ClientBattleTextReplayProtocol.HERO_NAME,
                ClientBattleTextReplayProtocol.NORMAL_DAMAGE,
                ClientBattleTextReplayProtocol.SKILL_CAST,
                ClientBattleTextReplayProtocol.SKILL_DAMAGE,
                ClientBattleTextReplayProtocol.ONGOING_DAMAGE,
                ClientBattleTextReplayProtocol.RECOVERY,
                ClientBattleTextReplayProtocol.STATUS,
                ClientBattleTextReplayProtocol.END,
                ClientBattleTextReplayProtocol.FINAL_TROOPS,
            ).all(ids::contains),
        )
    }
}
```

- [ ] **Step 2: 运行测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayProtocolTest
```

Expected: FAIL，`ClientBattleTextReplayProtocol` 不存在。

- [ ] **Step 3: 实现不可变记录与固定协议常量**

创建 `ClientBattleTextReplayProtocol.kt`，使用下列核心实现：

```kotlin
package com.stzb.server.game.battle

internal data class ClientReportAction(
    val id: Int,
    val params: List<Any> = emptyList(),
) {
    fun encode(): String =
        buildString {
            append(id.toString(36).padStart(2, '0'))
            if (params.isNotEmpty()) append(params.joinToString(","))
        }
}

internal object ClientBattleTextReplayProtocol {
    const val HERO_NAME = 14
    const val PREPARE = 4
    const val ROUND = 9
    const val NORMAL_DAMAGE = 62
    const val SKILL_CAST = 301
    const val SKILL_DAMAGE = 60
    const val ONGOING_DAMAGE = 59
    const val RECOVERY = 63
    const val STATUS = 102
    const val END = 13
    const val FINAL_TROOPS = 224

    fun position(side: Side, formationPosition: Int): Int {
        require(formationPosition in 0..2) { "battle formation position must be 0..2: $formationPosition" }
        return when (side) {
            Side.ATTACKER -> formationPosition + 1
            Side.DEFENDER -> formationPosition + 4
        }
    }

    fun position(ref: BattleHeroRef): Int = position(ref.side, ref.position)

    fun effectId(status: BattleStatus): Int = when (status) {
        BattleStatus.CONFUSION -> 501
        BattleStatus.HESITATION -> 502
        BattleStatus.DISARM -> 552
        BattleStatus.SHAKE -> 303
        BattleStatus.PANIC -> 304
        BattleStatus.BURN -> 305
        BattleStatus.HEX -> 306
        BattleStatus.INSIGHT -> 771
        BattleStatus.EVADE -> 515
        BattleStatus.ATTACK_BUFF -> 101
        BattleStatus.DEFENSE_BUFF -> 102
        BattleStatus.STRATEGY_BUFF -> 103
        BattleStatus.SPEED_BUFF -> 104
        BattleStatus.ATTACK_DEBUFF -> 151
        BattleStatus.DEFENSE_DEBUFF -> 152
        BattleStatus.STRATEGY_DEBUFF -> 153
        BattleStatus.SPEED_DEBUFF -> 154
    }

    fun effectId(stat: BattleStat, delta: Int): Int = when (stat) {
        BattleStat.ATTACK -> if (delta >= 0) 101 else 151
        BattleStat.DEFENSE -> if (delta >= 0) 102 else 152
        BattleStat.STRATEGY -> if (delta >= 0) 103 else 153
        BattleStat.SPEED -> if (delta >= 0) 104 else 154
        BattleStat.SIEGE, BattleStat.HIT_RANGE -> 0
    }
}
```

`position(side, formationPosition)` 必须保留 `require(formationPosition in 0..2)`；在
`effectId(stat, delta)` 返回 `0` 的分支由适配器跳过，不能输出无效状态记录。

- [ ] **Step 4: 验证绿灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayProtocolTest
```

Expected: PASS，真实样本中恰好包含一个准备动作、八个回合动作及全部协议动作族。

- [ ] **Step 5: 提交任务**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocol.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayProtocolTest.kt
git commit -m "feat: define client battle text replay protocol"
```

## Task 3: 接入开场、回合、普攻、技能伤害与恢复文字

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientReportTextEncoder.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/battle/BattleReportCodecTest.kt`

**Interfaces:**
- Consumes: `BattleResult`，`BattleEvent.RoundStart`，`NormalAttack`，`SkillDamage`，`Recovery`。
- Produces:

```kotlin
internal object ClientBattleTextReplayAdapter {
    fun adapt(result: BattleResult): List<ClientReportAction>
}
```

`ClientReportTextEncoder.encode(result)` 只做：

```kotlin
fun encode(result: BattleResult): String =
    ClientBattleTextReplayAdapter.adapt(result).joinToString("#") { it.encode() }
```

- [ ] **Step 1: 写开场和实际回合数的失败测试**

创建 `ClientBattleTextReplayAdapterTest.kt`，使用两个攻击方英雄、一个防守方英雄和两个
`RoundStart` 的构造结果：

```kotlin
@Test
fun `creates one preparation stage and one client round per engine round`() {
    val actions = ClientBattleTextReplayAdapter.adapt(twoRoundResult())

    assertEquals(1, actions.count { it.id == ClientBattleTextReplayProtocol.PREPARE })
    assertEquals(
        listOf(listOf<Any>(1), listOf<Any>(2)),
        actions.filter { it.id == ClientBattleTextReplayProtocol.ROUND }.map { it.params },
    )
    assertEquals(
        listOf(1, 2, 4),
        actions.filter { it.id == ClientBattleTextReplayProtocol.HERO_NAME }.map { it.params.first() },
    )
}
```

`twoRoundResult()` 必须包含：

```kotlin
BattleEvent.BattleStart,
BattleEvent.RoundStart(1),
BattleEvent.RoundStart(2),
BattleEvent.BattleEnd(BattleOutcome.ATTACKER_WIN)
```

并让攻击方位置为 `0`、`1`，防守方位置为 `0`。

- [ ] **Step 2: 运行测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest
```

Expected: FAIL，`ClientBattleTextReplayAdapter` 不存在。

- [ ] **Step 3: 写普攻、技能伤害和恢复参数形状的失败测试**

在同一测试文件添加：

```kotlin
@Test
fun `projects normal skill damage and recovery into distinct text actions`() {
    val actions = ClientBattleTextReplayAdapter.adapt(eventResult())

    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.NORMAL_DAMAGE &&
            it.params == listOf<Any>(4, 1, 0, 120, 880)
    })
    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.SKILL_CAST &&
            it.params == listOf<Any>(1, 1, 200012)
    })
    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.SKILL_DAMAGE &&
            it.params == listOf<Any>(1, 200012, 4, 180, 700)
    })
    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.RECOVERY &&
            it.params == listOf<Any>(1, 200001, 1, 70, 950)
    })
}
```

`eventResult()` 按顺序放入：

```kotlin
BattleEvent.NormalAttack(1, attackerRef, defenderRef, 120, 880),
BattleEvent.SkillDamage(1, 200012, 301, attackerRef, defenderRef, 180, 700),
BattleEvent.Recovery(1, attackerRef, attackerRef, 70, 950, skillId = 200001),
```

- [ ] **Step 4: 运行测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest
```

Expected: FAIL，适配器尚未输出这些文字动作。

- [ ] **Step 5: 实现开场、回合和三类数值事件**

创建适配器，核心逻辑如下：

```kotlin
internal object ClientBattleTextReplayAdapter {
    fun adapt(result: BattleResult): List<ClientReportAction> {
        val actions = mutableListOf<ClientReportAction>()
        (
            result.attacker.heroes.map { Side.ATTACKER to it } +
                result.defender.heroes.map { Side.DEFENDER to it }
            )
            .sortedBy { (side, hero) -> ClientBattleTextReplayProtocol.position(side, hero.position) }
            .forEach { (side, hero) ->
                actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.HERO_NAME,
                    listOf(ClientBattleTextReplayProtocol.position(side, hero.position), hero.id.value),
                )
            }
        actions += ClientReportAction(ClientBattleTextReplayProtocol.PREPARE)

        result.events.forEach { event ->
            when (event) {
                is BattleEvent.RoundStart -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.ROUND,
                    listOf(event.round),
                )
                is BattleEvent.NormalAttack -> actions += ClientReportAction(
                    ClientBattleTextReplayProtocol.NORMAL_DAMAGE,
                    listOf(
                        ClientBattleTextReplayProtocol.position(event.target),
                        ClientBattleTextReplayProtocol.position(event.source),
                        0,
                        event.damage,
                        event.targetTroopsAfter,
                    ),
                )
                is BattleEvent.SkillDamage -> {
                    actions += skillCast(event.source, event.skillId)
                    actions += ClientReportAction(
                        ClientBattleTextReplayProtocol.SKILL_DAMAGE,
                        listOf(
                            ClientBattleTextReplayProtocol.position(event.source),
                            event.skillId,
                            ClientBattleTextReplayProtocol.position(event.target),
                            event.damage,
                            event.targetTroopsAfter,
                        ),
                    )
                }
                is BattleEvent.Recovery -> {
                    actions += skillCast(event.source, event.skillId)
                    actions += ClientReportAction(
                        ClientBattleTextReplayProtocol.RECOVERY,
                        listOf(
                            ClientBattleTextReplayProtocol.position(event.source),
                            event.skillId,
                            ClientBattleTextReplayProtocol.position(event.target),
                            event.amount,
                            event.targetTroopsAfter,
                        ),
                    )
                }
                else -> Unit
            }
        }
        return actions
    }
}
```

`skillCast(source, skillId)` 在 `skillId <= 0` 时返回空列表，防止普攻或初始状态伪装成技能发动。结算段由 Task 4 在当前适配器基础上追加，Task 3 不得提前发出 `0d` 或 `68`。

`ClientReportTextEncoder` 删除 `action()`、`prefix()`、`sideIndex()`、`toClientEffectId()` 与
`BattleOutcome.toClientResult()`，不保留动作 `30` 的任何调用。

- [ ] **Step 6: 验证绿灯并替换旧压缩断言**

将 `BattleReportCodecTest` 的旧断言：

```kotlin
assertTrue(unzipped.split("#").any { it.startsWith("0u") })
```

替换为：

```kotlin
assertTrue(unzipped.split("#").none { it.startsWith("0u") })
assertEquals(1, unzipped.split("#").count { it == "04" })
assertTrue(unzipped.split("#").any { it.startsWith("1o") })
```

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest \
  --tests com.stzb.server.game.battle.BattleReportCodecTest
```

Expected: PASS，输出一次 `04`，每个引擎回合输出 `09`，且不含 `0u`。

- [ ] **Step 7: 提交任务**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt \
  src/main/kotlin/com/stzb/server/game/battle/ClientReportTextEncoder.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleReportCodecTest.kt
git commit -m "feat: emit client battle text rounds and damage"
```

## Task 4: 接入状态、DOT、规避、属性变化和结算

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleReportStoreTest.kt`

**Interfaces:**
- Consumes: 带 `skillId` 的 `StatusApplied`、`OngoingDamage`、`StatChanged`、`Evaded`、`BattleEnd`。
- Produces:
  - `STATUS` 动作参数：`source,target,skillId,effectId`
  - `ONGOING_DAMAGE` 动作参数：`source,skillId,target,damage,targetTroopsAfter,effectId`
  - `END` 后跟双方每个英雄一个 `FINAL_TROOPS`

- [ ] **Step 1: 写状态和 DOT 的失败测试**

在 `ClientBattleTextReplayAdapterTest` 添加：

```kotlin
@Test
fun `projects status dot evade and stat change with their original skill id`() {
    val actions = ClientBattleTextReplayAdapter.adapt(stateResult())

    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.STATUS &&
            it.params == listOf<Any>(1, 4, 200002, 305)
    })
    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.ONGOING_DAMAGE &&
            it.params == listOf<Any>(1, 200002, 4, 60, 640, 305)
    })
    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.STATUS &&
            it.params == listOf<Any>(1, 4, 0, 515)
    })
    assertTrue(actions.any {
        it.id == ClientBattleTextReplayProtocol.STATUS &&
            it.params == listOf<Any>(1, 1, 200036, 101)
    })
}
```

`stateResult()` 使用：

```kotlin
BattleEvent.StatusApplied(1, attackerRef, defenderRef, BattleStatus.BURN, 2, skillId = 200002),
BattleEvent.OngoingDamage(2, attackerRef, defenderRef, BattleStatus.BURN, 60, 640, skillId = 200002),
BattleEvent.Evaded(2, attackerRef, defenderRef),
BattleEvent.StatChanged(2, attackerRef, attackerRef, BattleStat.ATTACK, 10, 2, skillId = 200036),
```

- [ ] **Step 2: 运行测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest
```

Expected: FAIL，因为适配器尚未处理状态、DOT、规避和属性变化。

- [ ] **Step 3: 写结算顺序与最终兵力的失败测试**

在同一文件添加：

```kotlin
@Test
fun `ends the report before writing final troops for both sides`() {
    val actions = ClientBattleTextReplayAdapter.adapt(eventResult())
    val endIndex = actions.indexOfFirst { it.id == ClientBattleTextReplayProtocol.END }
    val finalTroops = actions.drop(endIndex + 1)
        .filter { it.id == ClientBattleTextReplayProtocol.FINAL_TROOPS }

    assertTrue(endIndex >= 0)
    assertEquals(3, finalTroops.size)
    assertEquals(listOf<Any>(1, 950, 50), finalTroops[0].params)
    assertEquals(listOf<Any>(2, 1000, 0), finalTroops[1].params)
    assertEquals(listOf<Any>(4, 700, 300), finalTroops[2].params)
}
```

- [ ] **Step 4: 运行测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest
```

Expected: FAIL，因为适配器尚未输出 `0d` 与 `68` 结算段。

- [ ] **Step 5: 写 `cmd=11` 结算段的失败测试**

在 `ClientBattleReportStoreTest` 的 `provides compressed client detail response` 中，在现有结构断言后添加：

```kotlin
val reportText = root[1]["report"].asText()
val text = GZIPInputStream(
    ByteArrayInputStream(Base64.getDecoder().decode(reportText.removePrefix("zzz"))),
).reader(Charsets.UTF_8).readText()
val records = text.split("#")

assertEquals(1, records.count { it == "04" })
assertTrue(records.count { it.startsWith("09") } >= 1)
assertTrue(records.none { it.startsWith("0u") })
assertTrue(records.any { it == "0d" })
assertTrue(records.any { it.startsWith("68") })
```

补充导入：

```kotlin
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
```

- [ ] **Step 6: 运行 `cmd=11` 测试并确认红灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleReportStoreTest
```

Expected: FAIL，因为 Task 3 的适配器尚未产生 `0d` 和 `68` 结算段。

- [ ] **Step 7: 实现状态和结算映射**

在适配器的 `when` 添加：

```kotlin
is BattleEvent.StatusApplied -> actions += statusActions(
    source = event.source,
    target = event.target,
    skillId = event.skillId,
    effectId = ClientBattleTextReplayProtocol.effectId(event.status),
)
is BattleEvent.OngoingDamage -> actions += ClientReportAction(
    ClientBattleTextReplayProtocol.ONGOING_DAMAGE,
    listOf(
        ClientBattleTextReplayProtocol.position(event.source),
        event.skillId,
        ClientBattleTextReplayProtocol.position(event.target),
        event.damage,
        event.targetTroopsAfter,
        ClientBattleTextReplayProtocol.effectId(event.status),
    ),
)
is BattleEvent.Evaded -> actions += statusActions(
    source = event.source,
    target = event.target,
    skillId = 0,
    effectId = ClientBattleTextReplayProtocol.effectId(BattleStatus.EVADE),
)
is BattleEvent.StatChanged -> {
    val effectId = ClientBattleTextReplayProtocol.effectId(event.stat, event.delta)
    if (effectId != 0) {
        actions += statusActions(event.source, event.target, event.skillId, effectId)
    }
}
```

`statusActions()` 必须实现为：

```kotlin
private fun statusActions(
    source: BattleHeroRef,
    target: BattleHeroRef,
    skillId: Int,
    effectId: Int,
): List<ClientReportAction> =
    buildList {
        if (skillId > 0) {
            add(
                ClientReportAction(
                    ClientBattleTextReplayProtocol.SKILL_CAST,
                    listOf(
                        ClientBattleTextReplayProtocol.position(source),
                        ClientBattleTextReplayProtocol.position(source),
                        skillId,
                    ),
                ),
            )
        }
        add(
            ClientReportAction(
                ClientBattleTextReplayProtocol.STATUS,
                listOf(
                    ClientBattleTextReplayProtocol.position(source),
                    ClientBattleTextReplayProtocol.position(target),
                    skillId,
                    effectId,
                ),
            ),
        )
    }
```

它在 `skillId > 0` 时先包含 `SKILL_CAST`，再包含：

```kotlin
ClientReportAction(
    ClientBattleTextReplayProtocol.STATUS,
    listOf(
        ClientBattleTextReplayProtocol.position(source),
        ClientBattleTextReplayProtocol.position(target),
        skillId,
        effectId,
    ),
)
```

在 `adapt()` 的事件循环结束后调用：

```kotlin
appendFinalization(actions, result)
```

结算函数必须实现为：

```kotlin
private fun appendFinalization(
    actions: MutableList<ClientReportAction>,
    result: BattleResult,
) {
    actions += ClientReportAction(ClientBattleTextReplayProtocol.END)
    val heroes = result.attacker.heroes
        .sortedBy(BattleHero::position)
        .map { Side.ATTACKER to it } +
        result.defender.heroes
            .sortedBy(BattleHero::position)
            .map { Side.DEFENDER to it }
    heroes.forEach { (side, hero) ->
        actions += ClientReportAction(
            ClientBattleTextReplayProtocol.FINAL_TROOPS,
            listOf(
                ClientBattleTextReplayProtocol.position(side, hero.position),
                hero.troops,
                (hero.maxTroops - hero.troops).coerceAtLeast(0),
            ),
        )
    }
}
```

结算函数中的 `heroes` 顺序必须是攻击方 `position=0..2`，再防守方
`position=0..2`；空位不补造 `68`，只对 `BattleTeam.heroes` 中实际存在的武将输出。

`BattleEvent.UnsupportedSkillEffect` 与 `UnsupportedEquipmentEffect` 明确保持 `Unit`：

```kotlin
is BattleEvent.UnsupportedSkillEffect,
is BattleEvent.UnsupportedEquipmentEffect -> Unit
```

它们已由 `BattleReportCodec.toJson()` 保存，不进入客户端文字动作流。

- [ ] **Step 8: 用真实 8 回合引擎结果写集成断言**

在 `BattleIntegrationTest` 的 `playable battle uses real heroes skills equipment and full round budget` 中，在拿到
`report` 后解压并断言：

```kotlin
val text = GZIPInputStream(
    ByteArrayInputStream(Base64.getDecoder().decode(report.removePrefix("zzz"))),
).reader(Charsets.UTF_8).readText()
val records = text.split("#")

assertEquals(1, records.count { it == "04" })
assertEquals(
    result.events.filterIsInstance<BattleEvent.RoundStart>().size,
    records.count { it.startsWith("09") },
)
assertTrue(records.none { it.startsWith("0u") })
assertTrue(records.any { it.startsWith("68") })
assertTrue(records.any { it == "0d" })
```

补充导入：

```kotlin
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
```

- [ ] **Step 9: 验证绿灯**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process \
  --tests com.stzb.server.game.battle.ClientBattleTextReplayAdapterTest \
  --tests com.stzb.server.game.battle.ClientBattleReportStoreTest \
  --tests com.stzb.server.game.battle.BattleIntegrationTest \
  --tests com.stzb.server.game.battle.BattleReportCodecTest
```

Expected: PASS，状态、DOT、规避、属性变化有独立文字记录；`0d` 出现在所有 `68` 之前；真实配置战斗输出完整回合结构。

- [ ] **Step 10: 提交任务**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapter.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleTextReplayAdapterTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleReportStoreTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/BattleIntegrationTest.kt
git commit -m "feat: complete client battle text replay events"
```

## Task 5: 构建分发包与真机文字回放验收

**Files:**
- 不修改源码；此任务验证 Task 1 至 Task 4 已提交的完整行为。

**Interfaces:**
- Consumes: `ClientBattleReportStore -> BattleReportCodec -> ClientReportTextEncoder -> ClientBattleTextReplayAdapter` 的既有链路。
- Produces: 可运行分发包和真机文字战报回放证据。

- [ ] **Step 1: 运行完整自动化回归**

Run:

```bash
./gradlew test --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: PASS。若 Kotlin daemon 目录权限报错但 Gradle 自动回退至 in-process 编译，测试结果仍必须为
`BUILD SUCCESSFUL`。

- [ ] **Step 2: 构建可运行分发包**

Run:

```bash
./gradlew installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`，生成 `build/install/server/` 下可启动的服务。

- [ ] **Step 3: 真机验收**

1. 启动新分发包中的服务，并执行 `adb reverse tcp:59979 tcp:59979`。
2. 客户端重新登录，确保获得更新后的 `Tb_hero` 和 `Tb_army`。
3. 选择可出征队伍，出征到 PVE 地块，等待固定 3 秒。
4. 确认地图先收到 `Tb_battle_report_attack`，再移除行军部队。
5. 打开战报列表，再打开该战报详情。
6. 确认只显示一个准备阶段，随后显示实际 `09` 数对应的第 1 至第 N 回合。
7. 确认文字中出现普攻、战法伤害、恢复、状态或 DOT（以该场引擎事件为准），并且胜负和最终兵力与摘要一致。
8. 关闭详情，返回列表，再关闭列表；确认没有 `FormatException`、`IndexOutOfRangeException` 或 `NullReferenceException`。

## Completion Checklist

- [ ] 一个战报只有一个 `04`。
- [ ] `09` 的数量和回合号与 `BattleEngine` 实际执行回合一致。
- [ ] 普攻、技能伤害、恢复、状态、DOT、规避和属性变化由适配器独立表达。
- [ ] `0u` 不再出现在任何客户端战报文本中。
- [ ] `0d` 后输出真实双方最终兵力的 `68` 记录。
- [ ] 未支持效果只保留在服务端 JSON，不伪装为客户端伤害。
- [ ] `cmd=10` 摘要和 `cmd=11` 详情仍由同一个 `BattleResult` 驱动。
- [ ] 完整 Gradle 测试、`installDist` 和真机文字回放通过。
