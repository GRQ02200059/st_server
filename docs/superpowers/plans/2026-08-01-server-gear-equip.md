# Server Gear Equip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让服务端支持 `1226` 武器装配、换装、跨武将转移与 `1227` 卸下，并在 99991 登录和 90005 增量中保持双向装备关系。

**Architecture:** 将装备实例 UID 保存为 `PlayerHero.gearUid`，以武将状态作为唯一持久化来源。`PlayerState` 原子维护一对一关系并返回所有受影响的武将/武器；登录快照和 `GameResponses` 分别把该状态投影到 `Tb_hero[23]` 与 `Tb_gear[9]`。处理器只解析客户端的双整数请求、持久化成功变更并发送一条稀疏 `90005` 通知。

**Tech Stack:** Kotlin 1.9.23、JUnit 5/kotlin.test、Jackson、Netty EmbeddedChannel、Gradle application 插件。

## 全局约束

- 只修改 Kotlin 服务端；不得修改客户端 DLL、客户端配置或使用运行时注入。
- `1226` 与 `1227` 请求体均为 `[heroUid, gearUid]`。
- 可装备实例只能是 `InventoryCatalog.normalWeapons()` 与 `hongjiCopies()` 生成的稳定 UID。
- `Tb_hero[23] gearid_u` 与 `Tb_gear[9] heroid_u` 必须始终互相一致。
- 有状态变更时，原命令先回 `[]`，随后只发送一条包含全部受影响行的 `90005`。
- 无效、无变化或不匹配的卸下请求只回 `[]`，不保存且不发送 `90005`。
- 旧 JSON 存档的缺失装备字段默认未装备；非法或重复武器关联在恢复时归一化。
- 本次不实现装备冷却、体力消耗、装备条件服务端复核或战斗结算读取玩家装备。
- 工作区已有与卡框、建筑、战报和战斗相关的未提交改动；提交前仅暂存本计划所引入的 hunks，不能捎带提交其它改动。
- 所有 Gradle 命令使用 `-Dkotlin.compiler.execution.strategy=in-process`，规避 macOS Kotlin daemon 权限问题。

---

## 文件结构

- Create: `src/main/kotlin/com/stzb/server/game/GearOperationRequestParser.kt`
  - 解析并验证两个原生装备命令的 `[heroUid, gearUid]` 请求体。
- Create: `src/test/kotlin/com/stzb/server/game/GearOperationRequestParserTest.kt`
  - 覆盖合法与非法请求解析。
- Modify: `src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt`
  - 提供对固定赠送武器 UID 的只读成员判断。
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
  - 保存 `gearUid`，维护一对一装备关系，完成存档恢复归一化。
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt`
  - 覆盖装配、换装、转移、卸下、存档恢复与非法请求。
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
  - 在 99991 中输出装备双向字段。
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
  - 验证登录快照的双向字段。
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
  - 将 `gearUid` 写入全量武将行，并生成装备变更的稀疏 `90005`。
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`
  - 验证完整武将行和稀疏装备通知。
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`
  - 声明 `GEAR_EQUIP=1226`、`GEAR_FORGET=1227`。
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
  - 分发、处理和持久化装备操作。
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`
  - 在 EmbeddedChannel 中验证实际命令回包与 `90005`。

## 统一接口

```kotlin
data class GearOperationRequest(
    val heroUid: Int,
    val gearUid: Int,
)

object GearOperationRequestParser {
    fun parse(body: String): GearOperationRequest?
}

data class GearEquipResult(
    val heroGearUids: Map<Int, Int>,
    val gearHeroUids: Map<Int, Int>,
)

object InventoryCatalog {
    fun isGrantedGearUid(gearUid: Int): Boolean
}

class PlayerState {
    fun equippedGearUid(heroUid: Int): Int
    fun equipGrantedGear(heroUid: Int, gearUid: Int): GearEquipResult?
    fun forgetGrantedGear(heroUid: Int, gearUid: Int): GearEquipResult?
}

object GameResponses {
    fun gearEquipNotify(result: GearEquipResult): String
}
```

### Task 1: 解析原生装备请求

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/GearOperationRequestParser.kt`
- Create: `src/test/kotlin/com/stzb/server/game/GearOperationRequestParserTest.kt`

**Interfaces:**
- Consumes: 客户端明文 JSON 请求体。
- Produces: `GearOperationRequestParser.parse(body): GearOperationRequest?`，只接受至少两个正整数的 JSON 数组。

- [ ] **Step 1: 写入失败的请求解析测试**

创建 `src/test/kotlin/com/stzb/server/game/GearOperationRequestParserTest.kt`：

```kotlin
package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GearOperationRequestParserTest {
    @Test
    fun `both native gear commands use hero uid then gear uid`() {
        assertEquals(
            GearOperationRequest(heroUid = 1_000_071, gearUid = 800_001_042),
            GearOperationRequestParser.parse("[1000071,800001042]"),
        )
        assertEquals(
            GearOperationRequest(heroUid = 1_000_071, gearUid = 840_100_050),
            GearOperationRequestParser.parse("[1000071,840100050,0]"),
        )
    }

    @Test
    fun `malformed incomplete and nonpositive gear requests are rejected`() {
        assertNull(GearOperationRequestParser.parse("not-json"))
        assertNull(GearOperationRequestParser.parse("""{"heroUid":1000071}"""))
        assertNull(GearOperationRequestParser.parse("[1000071]"))
        assertNull(GearOperationRequestParser.parse("[0,800001042]"))
        assertNull(GearOperationRequestParser.parse("[1000071,0]"))
    }
}
```

- [ ] **Step 2: 运行测试并确认红灯原因正确**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.GearOperationRequestParserTest
```

Expected: 编译失败，提示 `GearOperationRequest` 与 `GearOperationRequestParser` 未定义。

- [ ] **Step 3: 以现有 `SkillOperationRequestParser` 模式实现解析器**

创建 `src/main/kotlin/com/stzb/server/game/GearOperationRequestParser.kt`：

```kotlin
package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class GearOperationRequest(
    val heroUid: Int,
    val gearUid: Int,
)

object GearOperationRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parse(body: String): GearOperationRequest? =
        runCatching { mapper.readTree(body) }
            .getOrNull()
            ?.takeIf { it.isArray && it.size() >= 2 }
            ?.let { root ->
                GearOperationRequest(
                    heroUid = root[0].asInt(),
                    gearUid = root[1].asInt(),
                ).takeIf { request ->
                    request.heroUid > 0 && request.gearUid > 0
                }
            }
}
```

- [ ] **Step 4: 运行解析器测试并确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.GearOperationRequestParserTest
```

Expected: `BUILD SUCCESSFUL`，2 个请求解析测试全部通过。

- [ ] **Step 5: 只提交本任务新增文件**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/GearOperationRequestParser.kt \
  src/test/kotlin/com/stzb/server/game/GearOperationRequestParserTest.kt
git diff --cached --check
git commit -m "feat: parse gear operation requests"
```

### Task 2: 建立装备状态、一对一约束与存档恢复

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt`

**Interfaces:**
- Consumes: `InventoryCatalog.isGrantedGearUid(gearUid)` 与当前玩家武将集合。
- Produces: `GearEquipResult(heroGearUids, gearHeroUids)`；键是发生变化的实例 UID，值是操作后的关联 UID，`0` 表示解除。

- [ ] **Step 1: 添加状态、转移、卸下和恢复归一化的失败测试**

在 `PlayerStatePersistenceTest.kt` 的 import 中加入：

```kotlin
import kotlin.test.assertNull
```

追加以下测试：

```kotlin
@Test
fun `granted gear replaces and transfers through one to one state`() {
    val state = PlayerState(userId = 61, cityWid = 10061, roleName = "主公")
    val firstHero = state.addHero(100017)
    val secondHero = state.addHero(100021)
    val firstGear = InventoryCatalog.normalWeapons().first().uid
    val secondGear = InventoryCatalog.normalWeapons().drop(1).first().uid

    assertEquals(
        GearEquipResult(
            heroGearUids = mapOf(firstHero.heroUid to firstGear),
            gearHeroUids = mapOf(firstGear to firstHero.heroUid),
        ),
        state.equipGrantedGear(firstHero.heroUid, firstGear),
    )
    assertEquals(
        GearEquipResult(
            heroGearUids = mapOf(firstHero.heroUid to secondGear),
            gearHeroUids = mapOf(firstGear to 0, secondGear to firstHero.heroUid),
        ),
        state.equipGrantedGear(firstHero.heroUid, secondGear),
    )
    assertEquals(
        GearEquipResult(
            heroGearUids = mapOf(firstHero.heroUid to 0, secondHero.heroUid to secondGear),
            gearHeroUids = mapOf(secondGear to secondHero.heroUid),
        ),
        state.equipGrantedGear(secondHero.heroUid, secondGear),
    )
    assertEquals(0, state.equippedGearUid(firstHero.heroUid))
    assertEquals(secondGear, state.equippedGearUid(secondHero.heroUid))
}

@Test
fun `gear operations reject foreign pairs and forget only an exact equipment pair`() {
    val state = PlayerState(userId = 62, cityWid = 10062, roleName = "主公")
    val hero = state.addHero(100017)
    val gearUid = InventoryCatalog.normalWeapons().first().uid

    assertNull(state.equipGrantedGear(heroUid = 999_999, gearUid = gearUid))
    assertNull(state.equipGrantedGear(heroUid = hero.heroUid, gearUid = 123_456))
    assertEquals(0, state.equippedGearUid(hero.heroUid))

    assertEquals(
        GearEquipResult(
            heroGearUids = mapOf(hero.heroUid to gearUid),
            gearHeroUids = mapOf(gearUid to hero.heroUid),
        ),
        state.equipGrantedGear(hero.heroUid, gearUid),
    )
    assertNull(state.forgetGrantedGear(hero.heroUid, InventoryCatalog.normalWeapons().drop(1).first().uid))
    assertEquals(
        GearEquipResult(
            heroGearUids = mapOf(hero.heroUid to 0),
            gearHeroUids = mapOf(gearUid to 0),
        ),
        state.forgetGrantedGear(hero.heroUid, gearUid),
    )
    assertNull(state.forgetGrantedGear(hero.heroUid, gearUid))
}

@Test
fun `gear persists and snapshot recovery keeps only the smallest hero owner`() {
    val gearUid = InventoryCatalog.normalWeapons().first().uid
    val restored = PlayerState.fromSnapshot(
        PlayerStateSnapshot(
            accountKey = "gear-normalization",
            userId = 63,
            cityWid = 10063,
            roleName = "主公",
            heroes = listOf(
                PlayerHeroSnapshot(heroUid = 63_000_002, heroId = 100017, createdAtSec = 1, gearUid = gearUid),
                PlayerHeroSnapshot(heroUid = 63_000_001, heroId = 100021, createdAtSec = 1, gearUid = gearUid),
                PlayerHeroSnapshot(heroUid = 63_000_003, heroId = 100023, createdAtSec = 1, gearUid = 123_456),
            ),
        ),
    )

    assertEquals(gearUid, restored.equippedGearUid(63_000_001))
    assertEquals(0, restored.equippedGearUid(63_000_002))
    assertEquals(0, restored.equippedGearUid(63_000_003))
    assertEquals(
        gearUid,
        PlayerState.fromSnapshot(restored.toSnapshot()).equippedGearUid(63_000_001),
    )
}
```

- [ ] **Step 2: 运行状态测试并确认红灯原因正确**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.PlayerStatePersistenceTest
```

Expected: 编译失败，提示 `gearUid`、`GearEquipResult`、`equippedGearUid`、
`equipGrantedGear` 和 `forgetGrantedGear` 未定义。

- [ ] **Step 3: 为固定赠送库藏增加 UID 成员判断**

在 `InventoryCatalog` 的公开方法区域加入：

```kotlin
fun isGrantedGearUid(gearUid: Int): Boolean =
    gearUid > 0 && gearUid in inventory.grantedGearUids
```

将 `GeneratedInventory` 改为以下形式，使成员集合从现有基础武器与 50 把鸿级副本
派生，而不复制 UID 生成规则：

```kotlin
private data class GeneratedInventory(
    val baseWeapons: List<InventoryGearDefinition>,
    val hongjiCopies: List<InventoryGearDefinition>,
    val items: List<InventoryItemDefinition>,
) {
    val grantedGearUids: Set<Int> =
        (baseWeapons + hongjiCopies).map(InventoryGearDefinition::uid).toSet()
}
```

- [ ] **Step 4: 为武将与快照保存 `gearUid`**

在 `PlayerHero` 的 `dynamicIcon` 后保留现有字段并插入：

```kotlin
var gearUid: Int = 0,
```

在 `PlayerHeroSnapshot` 的 `dynamicIcon` 后插入：

```kotlin
val gearUid: Int = 0,
```

在 `PlayerState.toSnapshot()` 的 `PlayerHeroSnapshot(...)` 构造中插入：

```kotlin
gearUid = hero.gearUid,
```

在 `PlayerState.fromSnapshot()` 的 `PlayerHero(...)` 构造中插入：

```kotlin
gearUid = saved.gearUid,
```

- [ ] **Step 5: 实现原子装配、卸下和恢复归一化**

在 `HeroAdvanceResult` 后新增结果类型：

```kotlin
data class GearEquipResult(
    val heroGearUids: Map<Int, Int>,
    val gearHeroUids: Map<Int, Int>,
)
```

在 `PlayerState` 的 `hero(heroUid)` 后新增以下方法：

```kotlin
fun equippedGearUid(heroUid: Int): Int =
    hero(heroUid)?.gearUid ?: 0

fun equipGrantedGear(heroUid: Int, gearUid: Int): GearEquipResult? {
    val targetHero = hero(heroUid) ?: return null
    if (!InventoryCatalog.isGrantedGearUid(gearUid) || targetHero.gearUid == gearUid) return null

    val changedHeroUids = linkedSetOf<Int>()
    val changedGearUids = linkedSetOf<Int>()
    fun removeGear(currentHero: PlayerHero) {
        val previousGearUid = currentHero.gearUid
        if (previousGearUid == 0) return
        currentHero.gearUid = 0
        changedHeroUids += currentHero.heroUid
        changedGearUids += previousGearUid
    }

    removeGear(targetHero)
    heroes.values
        .filter { it.heroUid != targetHero.heroUid && it.gearUid == gearUid }
        .forEach(::removeGear)
    targetHero.gearUid = gearUid
    changedHeroUids += targetHero.heroUid
    changedGearUids += gearUid
    return gearEquipResult(changedHeroUids, changedGearUids)
}

fun forgetGrantedGear(heroUid: Int, gearUid: Int): GearEquipResult? {
    val targetHero = hero(heroUid) ?: return null
    if (!InventoryCatalog.isGrantedGearUid(gearUid) || targetHero.gearUid != gearUid) return null

    targetHero.gearUid = 0
    return gearEquipResult(setOf(heroUid), setOf(gearUid))
}
```

在 `PlayerState` 私有方法区加入：

```kotlin
private fun gearEquipResult(
    changedHeroUids: Set<Int>,
    changedGearUids: Set<Int>,
): GearEquipResult {
    val gearOwnerByUid = heroes.values
        .filter { it.gearUid in changedGearUids }
        .associate { hero -> hero.gearUid to hero.heroUid }
    return GearEquipResult(
        heroGearUids = changedHeroUids.sorted().associateWith { heroUid ->
            heroes.getValue(heroUid).gearUid
        },
        gearHeroUids = changedGearUids.sorted().associateWith { gearUid ->
            gearOwnerByUid[gearUid] ?: 0
        },
    )
}

private fun normalizeEquippedGears() {
    val claimedGearUids = mutableSetOf<Int>()
    heroes.values.sortedBy(PlayerHero::heroUid).forEach { hero ->
        val gearUid = hero.gearUid
        if (gearUid == 0) return@forEach
        if (!InventoryCatalog.isGrantedGearUid(gearUid) || !claimedGearUids.add(gearUid)) {
            hero.gearUid = 0
        }
    }
}
```

在 `fromSnapshot()` 中，完成 `snapshot.heroes.forEach` 并设置 `heroSeq` 前调用：

```kotlin
state.normalizeEquippedGears()
```

- [ ] **Step 6: 运行状态测试并确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.PlayerStatePersistenceTest
```

Expected: `BUILD SUCCESSFUL`；所有既有持久化测试及新增装备状态测试通过。

- [ ] **Step 7: 隔离暂存并提交状态层改动**

`PlayerState.kt` 与测试文件已有无关在途改动，只选择本任务添加的装备 hunks：

```bash
git add src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt
git add -p \
  src/main/kotlin/com/stzb/server/game/PlayerState.kt \
  src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt
git diff --cached --check
git diff --cached --stat
git commit -m "feat: persist player gear equipment"
```

### Task 3: 投影登录快照和稀疏数据库更新

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

**Interfaces:**
- Consumes: `PlayerHero.gearUid`、`GearEquipResult` 和固定赠送装备定义。
- Produces: 99991 中的 `Tb_hero[23]`/`Tb_gear[9]`，以及按武将 UID、武器 UID 升序输出的 `GameResponses.gearEquipNotify(result)`。

- [ ] **Step 1: 写入失败的快照与 90005 投影测试**

在 `GameResponsesTest.kt` 追加：

```kotlin
@Test
fun `gear notification updates affected heroes before affected gears`() {
    val update = mapper.readTree(
        GameResponses.gearEquipNotify(
            GearEquipResult(
                heroGearUids = mapOf(10_002 to 800_001_042, 10_001 to 0),
                gearHeroUids = mapOf(800_001_041 to 0, 800_001_042 to 10_002),
            ),
        ),
    )

    assertEquals(4, update.size())
    assertEquals("Tb_hero", update[0][1].asText())
    assertEquals(listOf(0, 10_001, 23, 0), update[0][2].map { it.asInt() })
    assertEquals("Tb_hero", update[1][1].asText())
    assertEquals(listOf(0, 10_002, 23, 800_001_042), update[1][2].map { it.asInt() })
    assertEquals("Tb_gear", update[2][1].asText())
    assertEquals(listOf(0, 800_001_041, 9, 0), update[2][2].map { it.asInt() })
    assertEquals("Tb_gear", update[3][1].asText())
    assertEquals(listOf(0, 800_001_042, 9, 10_002), update[3][2].map { it.asInt() })
}

@Test
fun `hero upsert retains the equipped gear uid`() {
    val hero = PlayerHero(
        heroUid = 4_200_008,
        heroId = 100017,
        createdAtSec = 1_700_000_000,
        gearUid = 800_001_042,
    )

    val row = mapper.readTree(GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)))[0][2]

    assertEquals(800_001_042, row[23].asInt())
}
```

在 `UserInitTableBuilderTest.kt` 的 import 中加入：

```kotlin
import kotlin.test.assertNotNull
```

追加以下测试：

```kotlin
@Test
fun `login snapshot restores the bidirectional equipped gear fields`() {
    val root = createTempDirectory("stzb-gear-snapshot")
    try {
        PlayerStateRepository.configure(FilePlayerRepository(root))
        val state = PlayerStateRepository.getOrCreate(
            accountKey = "gear-snapshot",
            cityWid = 10064,
            roleName = "主公",
        )
        val hero = state.addHero(100017)
        val gearUid = InventoryCatalog.normalWeapons().first().uid
        assertNotNull(state.equipGrantedGear(hero.heroUid, gearUid))
        PlayerStateRepository.save(state)

        val tables = UserInitTableBuilder.build(
            userId = state.userId,
            cityWid = state.cityWid,
            roleName = state.roleName,
            serverOpenTime = 1_700_000_000L,
            accountKey = state.accountKey,
        ).drop(1).associateBy { it[0].asText() }

        val heroes = tables.getValue("Tb_hero")[1].associateBy { it[0].asInt() }
        val gear = tables.getValue("Tb_gear")[1].single { it[0].asInt() == gearUid }
        assertEquals(gearUid, heroes.getValue(hero.heroUid)[23].asInt())
        assertEquals(hero.heroUid, gear[9].asInt())
    } finally {
        PlayerStateRepository.reset()
    }
}
```

- [ ] **Step 2: 运行投影测试并确认红灯原因正确**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest
```

Expected: `gearEquipNotify` 未定义，且快照断言显示 `Tb_hero[23]`、`Tb_gear[9]`
仍为 `0`。

- [ ] **Step 3: 在登录快照中投影双向关系**

在 `UserInitTableBuilder.build()` 生成 `grantedGears` 后、创建 `Tb_gear` 前加入：

```kotlin
val equippedHeroUidByGear = state.allHeroes()
    .asSequence()
    .filter { hero -> hero.gearUid > 0 }
    .associate { hero -> hero.gearUid to hero.heroUid }
```

将 `Tb_gear` 行生成改为传入关联武将：

```kotlin
*grantedGears.map { gear ->
    tbGear(
        userId = playerId,
        gear = gear,
        equippedHeroUid = equippedHeroUidByGear[gear.uid] ?: 0,
    )
}.toTypedArray(),
```

把 `tbGear` 签名与字段 9 改为：

```kotlin
private fun tbGear(
    userId: Int,
    gear: InventoryGearDefinition,
    equippedHeroUid: Int,
): ArrayNode =
    row("Tb_gear")
        // 保留字段 0..8 的既有构造
        .i(9, equippedHeroUid)
```

在 `tbHero(hero, userId)` 的字段 22 与字段 24 之间加入：

```kotlin
.i(23, hero.gearUid)
```

- [ ] **Step 4: 在完整武将回包和 90005 中投影装备关系**

将 `GameResponses.tbHero(...)` 的字段 23 改为：

```kotlin
add(hero.gearUid) // 23 gearid_u
```

在 `GameResponses` 中、`heroSkillUpdateNotify` 后增加：

```kotlin
fun gearEquipNotify(result: GearEquipResult): String =
    mapper.writeValueAsString(
        nf.arrayNode().apply {
            result.heroGearUids.toSortedMap().forEach { (heroUid, gearUid) ->
                add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_hero")
                        .add(nf.arrayNode().add(0).add(heroUid).add(23).add(gearUid)),
                )
            }
            result.gearHeroUids.toSortedMap().forEach { (gearUid, heroUid) ->
                add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_gear")
                        .add(nf.arrayNode().add(0).add(gearUid).add(9).add(heroUid)),
                )
            }
        },
    )
```

- [ ] **Step 5: 运行投影测试并确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest
```

Expected: `BUILD SUCCESSFUL`；全量武将行、99991 快照和稀疏更新中的双向字段一致。

- [ ] **Step 6: 隔离暂存并提交投影层改动**

这些文件可能同时包含卡框或属性等在途改动；只选择装备关联的 hunks：

```bash
git add -p \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/main/kotlin/com/stzb/server/game/GameResponses.kt \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt \
  src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt
git diff --cached --check
git diff --cached --stat
git commit -m "feat: project equipped gear to client tables"
```

### Task 4: 分发 1226/1227 并验证端到端协议

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- Consumes: `GearOperationRequestParser.parse(msg.bodyText)`、`PlayerState.equipGrantedGear`、`PlayerState.forgetGrantedGear`。
- Produces: 原命令 `[]`，成功时附加一条 `Cmd.SYS_NOTIFY_DB_UPDATE`，其 body 来自 `GameResponses.gearEquipNotify`。

- [ ] **Step 1: 编写失败的 EmbeddedChannel 协议测试**

在 `GameServerHandlerProtocolTest.kt` 的 import 区加入：

```kotlin
import com.stzb.server.game.InventoryCatalog
```

在 `GameServerHandlerProtocolTest.kt` 追加：

```kotlin
@Test
fun `gear equip transfer forget and invalid requests keep client tables synchronized`() {
    val channel = newChannel()
    platformLogin(channel, "gear-owner")
    val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
    val state = PlayerStateRepository.getOrCreate(
        accountKey = requireNotNull(session.accountKey),
        cityWid = GameServerConfig.CITY_WID,
        roleName = GameServerConfig.ROLE_NAME,
    )
    val firstHero = state.addHero(100017)
    val secondHero = state.addHero(100021)
    val gearUid = InventoryCatalog.normalWeapons().first().uid
    PlayerStateRepository.save(state)

    channel.writeInbound(
        upPacket(Cmd.GEAR_EQUIP, "[${firstHero.heroUid},$gearUid]", userId = session.userId),
    )
    val equipResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.GEAR_EQUIP, equipResponse.cmd)
    assertEquals("[]", equipResponse.body.toString(Charsets.UTF_8))
    val equipNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, equipNotify.cmd)
    val equipChanges = mapper.readTree(equipNotify.body)
    assertEquals(listOf(0, firstHero.heroUid, 23, gearUid), equipChanges[0][2].map { it.asInt() })
    assertEquals(listOf(0, gearUid, 9, firstHero.heroUid), equipChanges[1][2].map { it.asInt() })

    channel.writeInbound(
        upPacket(Cmd.GEAR_EQUIP, "[${secondHero.heroUid},$gearUid]", userId = session.userId),
    )
    assertEquals(Cmd.GEAR_EQUIP, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    val transferNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
    val transferChanges = mapper.readTree(transferNotify.body)
    assertEquals(listOf(0, firstHero.heroUid, 23, 0), transferChanges[0][2].map { it.asInt() })
    assertEquals(listOf(0, secondHero.heroUid, 23, gearUid), transferChanges[1][2].map { it.asInt() })
    assertEquals(listOf(0, gearUid, 9, secondHero.heroUid), transferChanges[2][2].map { it.asInt() })

    channel.writeInbound(
        upPacket(Cmd.GEAR_FORGET, "[${secondHero.heroUid},$gearUid]", userId = session.userId),
    )
    assertEquals(Cmd.GEAR_FORGET, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    val forgetNotify = assertIs<DownPacket>(channel.readOutbound<Any>())
    val forgetChanges = mapper.readTree(forgetNotify.body)
    assertEquals(listOf(0, secondHero.heroUid, 23, 0), forgetChanges[0][2].map { it.asInt() })
    assertEquals(listOf(0, gearUid, 9, 0), forgetChanges[1][2].map { it.asInt() })

    channel.writeInbound(upPacket(Cmd.GEAR_EQUIP, "[${firstHero.heroUid}]", userId = session.userId))
    val invalidResponse = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.GEAR_EQUIP, invalidResponse.cmd)
    assertEquals("[]", invalidResponse.body.toString(Charsets.UTF_8))
    assertNull(channel.readOutbound<Any>())
    assertEquals(0, state.equippedGearUid(firstHero.heroUid))
    assertEquals(0, state.equippedGearUid(secondHero.heroUid))
    channel.finishAndReleaseAll()
}
```

- [ ] **Step 2: 运行协议测试并确认红灯原因正确**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: 编译失败，`Cmd.GEAR_EQUIP` 和 `Cmd.GEAR_FORGET` 未定义；实现命令常量后，
测试会因处理器尚未分发命令而缺少预期回包。

- [ ] **Step 3: 声明命令并在处理器中分发**

在 `Cmd` 的业务命令常量区加入：

```kotlin
const val GEAR_EQUIP = 1226
const val GEAR_FORGET = 1227
```

在 `GameServerHandler` import 区加入：

```kotlin
import com.stzb.server.game.GearOperationRequestParser
```

在 `channelRead0` 的业务 `when` 中加入：

```kotlin
Cmd.GEAR_EQUIP,
Cmd.GEAR_FORGET -> {
    logIn(msg)
    sendGearOperation(ctx, session, msg)
}
```

- [ ] **Step 4: 实现有状态变更才通知的装备处理器**

在 `GameServerHandler` 中、`sendHeroAdvance` 后加入：

```kotlin
private fun sendGearOperation(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
    val request = GearOperationRequestParser.parse(msg.bodyText)
    val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
    val state = playerState(session, userId, GameServerConfig.CITY_WID)
    val result = request?.let { operation ->
        when (msg.cmdId) {
            Cmd.GEAR_EQUIP -> state.equipGrantedGear(operation.heroUid, operation.gearUid)
            Cmd.GEAR_FORGET -> state.forgetGrantedGear(operation.heroUid, operation.gearUid)
            else -> null
        }
    }

    ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
    if (result != null) {
        PlayerStateRepository.save(state)
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.gearEquipNotify(result),
                dataType = DownType.PLAIN,
            ),
        )
    }
    log.info(
        ">> cmd=${msg.cmdId} 武器操作已处理 " +
            "(uid=$userId, heroUid=${request?.heroUid ?: 0}, gearUid=${request?.gearUid ?: 0}, " +
            "changed=${result != null})",
    )
}
```

- [ ] **Step 5: 运行协议测试并确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: `BUILD SUCCESSFUL`；测试确认 `1226` 装配、转移，`1227` 卸下和非法请求的回包/通知行为。

- [ ] **Step 6: 隔离暂存并提交协议层改动**

`Cmd.kt` 与协议测试已有其它在途变更，只选择本任务相关 hunks：

```bash
git add -p \
  src/main/kotlin/com/stzb/server/protocol/Cmd.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git diff --cached --check
git diff --cached --stat
git commit -m "feat: handle gear equip and forget commands"
```

### Task 5: 完整验证、发行构建和真实客户端验收

**Files:**
- Verify: 所有任务中已修改的 Kotlin 代码与测试。
- Build output: `build/install/stzb-server/lib/stzb-server-0.1.0.jar`

**Interfaces:**
- Consumes: 上述四个已提交任务。
- Produces: 经完整测试、`installDist` 和 SHA-256 验证的本地发行包。

- [ ] **Step 1: 运行全部装备相关测试**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.GearOperationRequestParserTest \
  --tests com.stzb.server.game.PlayerStatePersistenceTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: `BUILD SUCCESSFUL`，没有失败测试。

- [ ] **Step 2: 运行完整自动化测试**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test
```

Expected: `BUILD SUCCESSFUL`。若基线中仍有与装备无关的 `CompleteSkillCoverageTest` 失败，
记录完整失败名称和日志，不能把它归因为本次改动。

- [ ] **Step 3: 构建发行包并验证 SHA-256**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process installDist
test -f build/install/stzb-server/lib/stzb-server-0.1.0.jar
shasum -a 256 build/install/stzb-server/lib/stzb-server-0.1.0.jar
```

Expected: Gradle 退出码为 `0`，JAR 存在并输出一行 SHA-256。

- [ ] **Step 4: 检查提交与工作区边界**

Run:

```bash
git log --oneline -4
git status --short
git diff --check
```

Expected: 新增的装备提交可见；工作区保留的卡框、建筑、战报和战斗改动仍未被本任务提交。

- [ ] **Step 5: 真实客户端验收**

使用新发行包按现有本地启动方式监听 `59979`，在干净客户端按顺序验证：

1. 登录后库藏中存在固定赠送的鸿级武器。
2. 将一把武器装配到武将，武将卡面和库藏详情同时显示关联。
3. 给同一武将换另一把武器，旧武器显示未装备。
4. 将当前武器装给另一名武将，原武将显示未装备。
5. 卸下武器。
6. 再次装配后退出并重登，武将卡面和库藏详情仍显示相同关联。

验收失败时保留客户端请求与服务端日志；重点检查 `cmd=1226`/`1227`、紧随其后的
`90005`，以及字段 `Tb_hero[23]` 和 `Tb_gear[9]`。
