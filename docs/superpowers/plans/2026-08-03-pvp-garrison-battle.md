# PvP 玩家驻守 + 抵达战斗 + 归属转移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让玩家A 行军攻打玩家B 手动驻守的土地/城池，抵达后结算 PvP 战斗，A 胜则转移归属。

**Architecture:** 复用现有 march 调度、`BattleEngine`、`ClientBattleReportStore`、`WorldStateRepository` 与 `broadcastWorldScene`。防守方驻军以 `BattleHeroSpec` 战斗快照存入 `WorldService`（跨玩家可见、不依赖B在线）。攻击结算时先查该 wid 有无敌方驻军：有则走 PvP，否则回退现有 PvE。

**Tech Stack:** Kotlin 1.9.23 + Netty 4.1.109，Gradle，kotlin.test + JUnit。

## Global Constraints

- 测试运行方式（本环境唯一稳定方式）：`./gradlew test --tests "<FQN>" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
- 服务端"几乎不自造游戏数据"：驻军 spec 必须复用现有"从 teamHeroes 生成 BattleHeroSpec"的构建逻辑，禁止另写一套。
- `result` 语义固定：`1=胜 2=败 3=平`（`BattleOutcome.ATTACKER_WIN/DEFENDER_WIN/DRAW`）。
- 驻守协议：`cmd 60`，body `[wid, armyId, needPortWid, techID, isJianJun, useSpeedup]`（6 个 int）；`needPortWid/techID/isJianJun/useSpeedup` 本次收下即忽略。
- 并发：所有 `WorldService` 新增读写都必须走其现有 `ReentrantReadWriteLock`（`lock.write{}`/`lock.read{}`）。
- 提交粒度：每个 Task 末尾单独 commit，禁止 `--amend`、禁止 `git add -A`。

---

### Task 1: WorldService 驻军快照存储

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/WorldState.kt`（新增 `GarrisonSnapshot` 数据类、`WorldService` 内 `garrisonsByWid` 及方法、`WorldStateRepository` facade 转发）
- Test: `src/test/kotlin/com/stzb/server/game/WorldGarrisonTest.kt`

**Interfaces:**
- Produces:
  - `data class GarrisonSnapshot(val wid: Int, val ownerUserId: Int, val armyId: Int, val specs: List<com.stzb.server.game.battle.BattleHeroSpec>, val residedAtSec: Int)`
  - `WorldStateRepository.putGarrison(snapshot: GarrisonSnapshot)`
  - `WorldStateRepository.garrisonAt(wid: Int): GarrisonSnapshot?`
  - `WorldStateRepository.removeGarrison(wid: Int): GarrisonSnapshot?`
  - `WorldStateRepository.garrisons(): List<GarrisonSnapshot>`
- Consumes: 现有 `WorldService.lock`（`ReentrantReadWriteLock`）。

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/stzb/server/game/WorldGarrisonTest.kt`:

```kotlin
package com.stzb.server.game

import com.stzb.server.game.battle.BattleHeroSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldGarrisonTest {
    private fun snapshot(wid: Int, owner: Int) = GarrisonSnapshot(
        wid = wid,
        ownerUserId = owner,
        armyId = 1,
        specs = listOf(BattleHeroSpec(heroId = 100021, position = 0, troops = 1000)),
        residedAtSec = 1_700_000_000,
    )

    @Test
    fun `put then read then remove a garrison`() {
        WorldStateRepository.putGarrison(snapshot(20001, owner = 501))

        val read = WorldStateRepository.garrisonAt(20001)
        assertEquals(501, read?.ownerUserId)
        assertEquals(1, read?.specs?.size)

        val removed = WorldStateRepository.removeGarrison(20001)
        assertEquals(501, removed?.ownerUserId)
        assertNull(WorldStateRepository.garrisonAt(20001))
    }

    @Test
    fun `garrisons lists every stored wid`() {
        WorldStateRepository.putGarrison(snapshot(20002, owner = 502))
        WorldStateRepository.putGarrison(snapshot(20003, owner = 503))

        val wids = WorldStateRepository.garrisons().map { it.wid }
        assertEquals(true, wids.containsAll(listOf(20002, 20003)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.stzb.server.game.WorldGarrisonTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: FAIL — `GarrisonSnapshot` / `putGarrison` 未定义（编译错误）。

- [ ] **Step 3: Add GarrisonSnapshot data class**

在 `WorldState.kt` 的 `LandClaim` 数据类之后加入：

```kotlin
data class GarrisonSnapshot(
    val wid: Int,
    val ownerUserId: Int,
    val armyId: Int,
    val specs: List<com.stzb.server.game.battle.BattleHeroSpec>,
    val residedAtSec: Int,
)
```

- [ ] **Step 4: Add garrison storage to WorldService**

在 `WorldService` 类里，`landsByWid` 声明之后加字段：

```kotlin
    private val garrisonsByWid = LinkedHashMap<Int, GarrisonSnapshot>()
```

在 `ownerOf` 方法之后加入方法（注意：驻军不落盘到 world.json，属运行时态）：

```kotlin
    fun putGarrison(snapshot: GarrisonSnapshot): Unit = lock.write {
        garrisonsByWid[snapshot.wid] = snapshot
    }

    fun garrisonAt(wid: Int): GarrisonSnapshot? = lock.read { garrisonsByWid[wid] }

    fun removeGarrison(wid: Int): GarrisonSnapshot? = lock.write {
        garrisonsByWid.remove(wid)
    }

    fun garrisons(): List<GarrisonSnapshot> = lock.read { garrisonsByWid.values.toList() }
```

- [ ] **Step 5: Add WorldStateRepository facade forwards**

在 `object WorldStateRepository` 里 `ownerOf` 转发之后加入：

```kotlin
    fun putGarrison(snapshot: GarrisonSnapshot): Unit = service.putGarrison(snapshot)

    fun garrisonAt(wid: Int): GarrisonSnapshot? = service.garrisonAt(wid)

    fun removeGarrison(wid: Int): GarrisonSnapshot? = service.removeGarrison(wid)

    fun garrisons(): List<GarrisonSnapshot> = service.garrisons()
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.stzb.server.game.WorldGarrisonTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（2 tests）。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/WorldState.kt src/test/kotlin/com/stzb/server/game/WorldGarrisonTest.kt
git commit -m "feat(pvp): store player garrison snapshots in world state"
```

---

### Task 2: 共享的攻/防 BattleHeroSpec 构建器

**背景：** `PlayerBattleService.settlePveBattle` 里已有"从 `PlayerMarchHero` 生成 `BattleHeroSpec`（含装备）"的逻辑（约 `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt:120-135` 与 `:159-171`）。PvP 结算与驻守快照都要用同一构建，需先抽成一个可复用函数，避免三套重复。

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/BattleSpecFactory.kt`
- Test: `src/test/kotlin/com/stzb/server/game/BattleSpecFactoryTest.kt`

**Interfaces:**
- Produces: `object BattleSpecFactory { fun fromMarchHero(hero: com.stzb.server.game.PlayerMarchHero): com.stzb.server.game.battle.BattleHeroSpec }`
- Consumes: `PlayerMarchHero`（现有字段：heroId, position, troops, level, skillIds, heroType, attributePoints, activeFeatureId, advanceNum, equipmentIds, equipmentFeatureSkillIds, equipmentFeatureSkillLevels）、`PlayerHero.MAX_TROOPS`。

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/stzb/server/game/BattleSpecFactoryTest.kt`:

```kotlin
package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals

class BattleSpecFactoryTest {
    @Test
    fun `march hero maps its combat fields including equipment into a spec`() {
        val hero = PlayerMarchHero(
            heroUid = 1,
            position = 2,
            heroId = 100021,
            troops = 12_345,
            level = 40,
            skillIds = listOf(200021, 200012, 0),
            equipmentIds = listOf(1021),
            equipmentFeatureSkillIds = listOf(450019),
            equipmentFeatureSkillLevels = listOf(9),
            advanceNum = 3,
        )

        val spec = BattleSpecFactory.fromMarchHero(hero)

        assertEquals(100021, spec.heroId)
        assertEquals(2, spec.position)
        assertEquals(40, spec.level)
        assertEquals(listOf(1021), spec.equipmentIds)
        assertEquals(listOf(450019), spec.equipmentFeatureSkillIds)
        assertEquals(listOf(9), spec.equipmentFeatureSkillLevels)
        // extra skills drop the first slot and filter zeros, matching PvE builder
        assertEquals(listOf(200012), spec.extraSkillIds)
        assertEquals(3, spec.advanceLevel)
        assertEquals(12_345, spec.troops)
    }

    @Test
    fun `troops are capped at the maximum`() {
        val hero = PlayerMarchHero(
            heroUid = 1, position = 0, heroId = 100021,
            troops = PlayerHero.MAX_TROOPS + 5_000, level = 50, skillIds = listOf(200021),
        )
        assertEquals(PlayerHero.MAX_TROOPS, BattleSpecFactory.fromMarchHero(hero).troops)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.stzb.server.game.BattleSpecFactoryTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: FAIL — `BattleSpecFactory` 未定义。

- [ ] **Step 3: Create BattleSpecFactory (copy the exact mapping PvE uses)**

Create `src/main/kotlin/com/stzb/server/game/BattleSpecFactory.kt`:

```kotlin
package com.stzb.server.game

import com.stzb.server.game.battle.BattleHeroSpec

/**
 * Single source of truth for turning a departed march hero into a battle spec.
 * Attacker settlement, PvP defender snapshots, and reside snapshots all use this
 * so equipment/skill wiring never drifts between paths.
 */
object BattleSpecFactory {
    fun fromMarchHero(hero: PlayerMarchHero): BattleHeroSpec =
        BattleHeroSpec(
            heroId = hero.heroId,
            position = hero.position,
            troops = hero.troops.coerceAtMost(PlayerHero.MAX_TROOPS),
            level = hero.level,
            extraSkillIds = hero.skillIds.drop(1).filter { it > 0 },
            skillLevels = hero.skillIds.filter { it > 0 }
                .map { PlayerHero.MAX_SKILL_LEVEL },
            heroType = hero.heroType,
            surfaceSkillId = hero.activeFeatureId,
            attributePoints = hero.attributePoints,
            advanceLevel = hero.advanceNum,
            equipmentIds = hero.equipmentIds,
            equipmentFeatureSkillIds = hero.equipmentFeatureSkillIds,
            equipmentFeatureSkillLevels = hero.equipmentFeatureSkillLevels,
        )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.stzb.server.game.BattleSpecFactoryTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（2 tests）。

- [ ] **Step 5: Refactor PvE settle to use the factory (no behavior change)**

在 `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt` 的 `settlePveBattle` 中，把两处内联构造 `BattleHeroSpec(...)`（attacker 初次构建 ~`:120-135` 和逐波重建 ~`:159-171`）替换为：

```kotlin
                BattleSpecFactory.fromMarchHero(participant)
```

逐波重建处需要用当波剩余兵力，替换为（保留 remainingTroops 逻辑）：

```kotlin
                    BattleSpecFactory.fromMarchHero(
                        participant.copy(troops = remainingTroops),
                    )
```

- [ ] **Step 6: Run PvE regression to verify no behavior change**

Run: `./gradlew test --tests "com.stzb.server.game.PlayerBattleServiceTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（全部，含上一阶段新增的装备测试）。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/BattleSpecFactory.kt src/test/kotlin/com/stzb/server/game/BattleSpecFactoryTest.kt src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt
git commit -m "refactor(battle): extract shared march-hero to battle-spec builder"
```

---

### Task 3: 驻守命令解析 + PlayerMarch.targetType

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`（新增 `RESIDE_FIELD = 60`）
- Create: `src/main/kotlin/com/stzb/server/game/ResideRequestParser.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`（`PlayerMarch` 加 `targetType`，`startMarch` 加参数）
- Test: `src/test/kotlin/com/stzb/server/game/ResideRequestParserTest.kt`

**Interfaces:**
- Produces:
  - `Cmd.RESIDE_FIELD = 60`
  - `data class ResideRequest(val wid: Int, val armyId: Int)`
  - `object ResideRequestParser { fun parse(bodyText: String): ResideRequest? }`
  - `PlayerMarch.targetType: Int`（默认 `1`）
  - `PlayerState.startMarch(..., targetType: Int = MarchTargetType.EXPEDITION)`
  - `object MarchTargetType { const val EXPEDITION = 1; const val RESIDE_GOING = 2 }`
- Consumes: 现有 `ArmyBattleRequestParser` 的 Jackson 解析模式。

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/stzb/server/game/ResideRequestParserTest.kt`:

```kotlin
package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResideRequestParserTest {
    @Test
    fun `parses wid and armyId from the six-field reside body`() {
        val req = ResideRequestParser.parse("[20001,150615061,0,0,0,0]")
        assertEquals(20001, req?.wid)
        assertEquals(150615061, req?.armyId)
    }

    @Test
    fun `rejects non positive wid or armyId`() {
        assertNull(ResideRequestParser.parse("[0,150615061,0,0,0,0]"))
        assertNull(ResideRequestParser.parse("[20001,0,0,0,0,0]"))
    }

    @Test
    fun `rejects malformed body`() {
        assertNull(ResideRequestParser.parse("not-json"))
        assertNull(ResideRequestParser.parse("[]"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.stzb.server.game.ResideRequestParserTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: FAIL — `ResideRequestParser` 未定义。

- [ ] **Step 3: Add the reside command constant**

在 `src/main/kotlin/com/stzb/server/protocol/Cmd.kt` 的 `ARMY_BATTLE = 6` 附近加入：

```kotlin
    const val RESIDE_FIELD = 60  // ArmyOpRequest.RequestDefend: 派部队驻守某地
```

- [ ] **Step 4: Create ResideRequestParser (mirror ArmyBattleRequestParser)**

Create `src/main/kotlin/com/stzb/server/game/ResideRequestParser.kt`:

```kotlin
package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ResideRequest(
    val wid: Int,
    val armyId: Int,
)

/** Parses cmd 60 body [wid, armyId, needPortWid, techID, isJianJun, useSpeedup]. */
object ResideRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parse(bodyText: String): ResideRequest? {
        val body = runCatching { mapper.readTree(bodyText) }.getOrNull() ?: return null
        if (!body.isArray || body.size() < 2) return null
        val wid = body[0].asInt()
        val armyId = body[1].asInt()
        if (wid <= 0 || armyId <= 0) return null
        return ResideRequest(wid = wid, armyId = armyId)
    }
}
```

- [ ] **Step 5: Add MarchTargetType and thread targetType through PlayerMarch/startMarch**

在 `PlayerState.kt` 顶部（文件内其他 object/常量附近）加入：

```kotlin
object MarchTargetType {
    const val EXPEDITION = 1
    const val RESIDE_GOING = 2
}
```

在 `data class PlayerMarch(...)` 末尾字段加入（放在 `specialArmyFacadeId` 之后）：

```kotlin
    val targetType: Int = MarchTargetType.EXPEDITION,
```

在 `fun startMarch(...)` 参数列表末尾加入 `targetType`，并在构造 `PlayerMarch(...)` 时传入：

```kotlin
    fun startMarch(
        targetWid: Int,
        nowSec: Int,
        armyId: Int = primaryArmyId(),
        participants: List<PlayerMarchHero> = emptyList(),
        specialArmyFacadeId: Int = 0,
        targetType: Int = MarchTargetType.EXPEDITION,
    ): PlayerMarch {
        val beginSec = nowSec.coerceAtLeast(1)
        return PlayerMarch(
            armyId = normalizeArmyId(armyId),
            fromWid = cityWid,
            targetWid = targetWid,
            beginSec = beginSec,
            endSec = beginSec + MARCH_DURATION_SECONDS,
            participants = participants,
            specialArmyFacadeId = specialArmyFacadeId.takeIf {
                it != ArmyFacadeCatalog.YUXI_FACADE_ID && ArmyFacadeCatalog.isSpecialFacade(it)
            } ?: 0,
            targetType = targetType,
        ).also { marches[it.armyId] = it }
    }
```

- [ ] **Step 6: Run test to verify it passes + snapshot round-trip regression**

Run: `./gradlew test --tests "com.stzb.server.game.ResideRequestParserTest" --tests "com.stzb.server.game.PlayerStateTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（新解析器 3 tests + 现有 PlayerState 测试不回归；`targetType` 有默认值，snapshot 不受影响）。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/stzb/server/protocol/Cmd.kt src/main/kotlin/com/stzb/server/game/ResideRequestParser.kt src/main/kotlin/com/stzb/server/game/PlayerState.kt src/test/kotlin/com/stzb/server/game/ResideRequestParserTest.kt
git commit -m "feat(pvp): add reside command parser and march target type"
```

---

### Task 4: GarrisonService — 驻守抵达写入 WorldState

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/GarrisonService.kt`
- Test: `src/test/kotlin/com/stzb/server/game/GarrisonServiceTest.kt`

**Interfaces:**
- Produces:
  - `class GarrisonService { fun startReside(state: PlayerState, wid: Int, armyId: Int, nowSec: Int): PlayerMarch?; fun settleReside(state: PlayerState, armyId: Int, nowSec: Int): GarrisonSnapshot? }`
  - `startReside`：校验 `state.teamHeroes(armyId)` 非空且有兵力 → `state.startMarch(targetWid=wid, targetType=RESIDE_GOING, participants=...)`；返回 march 或 null。
  - `settleReside`：march 到期 → 生成 `GarrisonSnapshot`（specs 用 `BattleSpecFactory.fromMarchHero`）→ `WorldStateRepository.putGarrison` → 返回快照。
- Consumes: Task 1 `GarrisonSnapshot`/`WorldStateRepository.putGarrison`；Task 2 `BattleSpecFactory`；Task 3 `MarchTargetType`；现有 `state.startMarch`/`completeMarchIfDue`/`teamHeroes`/`hero`。

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/stzb/server/game/GarrisonServiceTest.kt`:

```kotlin
package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GarrisonServiceTest {
    @Test
    fun `reside arrival records a garrison snapshot at the target wid`() {
        val state = PlayerState(userId = 601, cityWid = 15061510, roleName = "主公")
        val hero = state.addHero(heroId = 100021).apply { troops = 8_000; level = 40 }
        state.saveTeam(listOf(hero.heroUid))
        val service = GarrisonService()

        val march = service.startReside(state, wid = 15051599, armyId = state.primaryArmyId(), nowSec = 1_700_000_000)
        assertNotNull(march)
        assertEquals(MarchTargetType.RESIDE_GOING, march.targetType)
        // not due yet
        assertNull(service.settleReside(state, state.primaryArmyId(), nowSec = 1_700_000_001))

        val snapshot = service.settleReside(state, state.primaryArmyId(), nowSec = 1_700_000_600)
        assertNotNull(snapshot)
        assertEquals(601, snapshot.ownerUserId)
        assertEquals(15051599, snapshot.wid)
        assertEquals(100021, snapshot.specs.single().heroId)
        assertEquals(snapshot.wid, WorldStateRepository.garrisonAt(15051599)?.wid)
    }

    @Test
    fun `empty team cannot reside`() {
        val state = PlayerState(userId = 602, cityWid = 15061511, roleName = "主公")
        assertNull(GarrisonService().startReside(state, wid = 15051598, armyId = state.primaryArmyId(), nowSec = 1_700_000_000))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.stzb.server.game.GarrisonServiceTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: FAIL — `GarrisonService` 未定义。

- [ ] **Step 3: Create GarrisonService**

Create `src/main/kotlin/com/stzb/server/game/GarrisonService.kt`:

```kotlin
package com.stzb.server.game

/**
 * Player-driven garrison: send an army to reside at a wid. On arrival the team
 * is frozen into a WorldState garrison snapshot so other players can attack it.
 */
class GarrisonService {
    fun startReside(
        state: PlayerState,
        wid: Int,
        armyId: Int = state.primaryArmyId(),
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): PlayerMarch? {
        if (wid <= 0 || wid == state.cityWid) return null
        if (state.activeMarch(armyId) != null) return null
        val participants = state.teamHeroes(armyId)
            .withIndex()
            .mapNotNull { (position, heroUid) ->
                state.hero(heroUid)
                    ?.takeIf { it.troops > 0 }
                    ?.let { hero ->
                        PlayerMarchHero(
                            heroUid = hero.heroUid,
                            position = position,
                            heroId = hero.heroId,
                            troops = hero.troops,
                            level = hero.level,
                            skillIds = hero.normalizedSkillIds(),
                            heroType = hero.heroType,
                            attributePoints = hero.attributePoints,
                            activeFeatureId = hero.activeFeatureId,
                            cardBorder = hero.cardBorder,
                            dynamicIcon = hero.dynamicIcon,
                            armyFacadeCardId = hero.armyFacadeCardId,
                            advanceNum = hero.advanceNum,
                            equipmentIds = InventoryCatalog.battleLoadoutForGearUid(hero.gearUid)
                                ?.equipmentIds.orEmpty(),
                            equipmentFeatureSkillIds = InventoryCatalog
                                .battleLoadoutForGearUid(hero.gearUid)
                                ?.equipmentFeatureSkillIds.orEmpty(),
                            equipmentFeatureSkillLevels = InventoryCatalog
                                .battleLoadoutForGearUid(hero.gearUid)
                                ?.equipmentFeatureSkillLevels.orEmpty(),
                        )
                    }
            }
        if (participants.isEmpty()) return null
        return state.startMarch(
            targetWid = wid,
            nowSec = nowSec,
            armyId = armyId,
            participants = participants,
            targetType = MarchTargetType.RESIDE_GOING,
        )
    }

    fun settleReside(
        state: PlayerState,
        armyId: Int = state.primaryArmyId(),
        nowSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    ): GarrisonSnapshot? {
        val march = state.completeMarchIfDue(nowSec, armyId) ?: return null
        if (march.targetType != MarchTargetType.RESIDE_GOING) {
            // Not a reside march; put it back is unnecessary — reside settle only
            // handles reside marches. Attack marches are settled elsewhere.
            return null
        }
        val snapshot = GarrisonSnapshot(
            wid = march.targetWid,
            ownerUserId = state.userId,
            armyId = march.armyId,
            specs = march.participants.map(BattleSpecFactory::fromMarchHero),
            residedAtSec = nowSec,
        )
        WorldStateRepository.putGarrison(snapshot)
        return snapshot
    }
}
```

Note: `settleReside` 只处理 `RESIDE_GOING` march；攻击 march 由 `PlayerBattleService` 结算，二者按 `targetType` 区分，互不吞对方的 march（见 Task 6 handler 分派）。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.stzb.server.game.GarrisonServiceTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（2 tests）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/GarrisonService.kt src/test/kotlin/com/stzb/server/game/GarrisonServiceTest.kt
git commit -m "feat(pvp): reside march settles into a world garrison snapshot"
```

---

### Task 5: PvpBattleService — 对驻军结算 + 归属转移 + 改B存档

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/PvpBattleService.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PvpBattleServiceTest.kt`

**Interfaces:**
- Produces:
  - `data class PvpSettlement(val battleId: Int, val targetWid: Int, val outcome: com.stzb.server.game.battle.BattleOutcome, val defenderUserId: Int, val ownershipTransferred: Boolean)`
  - `class PvpBattleService(reportStore, config, equipmentRepository, battleRandomFactory) { fun settle(attacker: PlayerState, march: PlayerMarch, garrison: GarrisonSnapshot, nowSec: Int, loadDefenderState: (Int) -> PlayerState?): PvpSettlement }`
- Consumes: Task 1 `GarrisonSnapshot`/`removeGarrison`；Task 2 `BattleSpecFactory`；现有 `BattleTeamBuilder`/`BattleEngine`/`ClientBattleReportStore.record`/`WorldStateRepository.claimLand`/`BattleOutcome`/`stableBattleSeed` 同等的种子逻辑。

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/stzb/server/game/PvpBattleServiceTest.kt`:

```kotlin
package com.stzb.server.game

import com.stzb.server.game.battle.BattleHeroSpec
import com.stzb.server.game.battle.BattleOutcome
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.FixedBattleRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PvpBattleServiceTest {
    private fun weakGarrison(wid: Int, owner: Int) = GarrisonSnapshot(
        wid = wid,
        ownerUserId = owner,
        armyId = 999,
        specs = listOf(BattleHeroSpec(heroId = 100017, position = 0, troops = 1, level = 1)),
        residedAtSec = 1_700_000_000,
    )

    @Test
    fun `attacker win clears garrison and transfers ownership`() {
        val defender = PlayerState(userId = 720, cityWid = 15061520, roleName = "守方")
        val attacker = PlayerState(userId = 710, cityWid = 15061510, roleName = "攻方")
        val hero = attacker.addHero(heroId = 100021).apply { troops = 100_000; level = 1000 }
        attacker.saveTeam(listOf(hero.heroUid))
        val targetWid = 15051530
        WorldStateRepository.putGarrison(weakGarrison(targetWid, owner = 720))

        attacker.startMarch(targetWid = targetWid, nowSec = 1_700_000_000)
        val march = attacker.completeMarchIfDue(1_700_000_600)!!
        val service = PvpBattleService(
            reportStore = ClientBattleReportStore.createEmpty(),
            battleRandomFactory = { FixedBattleRandom(0) },
        )

        val result = service.settle(
            attacker = attacker,
            march = march,
            garrison = WorldStateRepository.garrisonAt(targetWid)!!,
            nowSec = 1_700_000_600,
            loadDefenderState = { if (it == 720) defender else null },
        )

        assertEquals(BattleOutcome.ATTACKER_WIN, result.outcome)
        assertEquals(720, result.defenderUserId)
        assertTrue(result.ownershipTransferred)
        assertNull(WorldStateRepository.garrisonAt(targetWid))
        assertTrue(attacker.ownsLand(targetWid))
        assertTrue(result.battleId > 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.stzb.server.game.PvpBattleServiceTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: FAIL — `PvpBattleService` 未定义。

- [ ] **Step 3: Create PvpBattleService**

Create `src/main/kotlin/com/stzb/server/game/PvpBattleService.kt`:

```kotlin
package com.stzb.server.game

import com.stzb.server.game.battle.BattleConfigRepository
import com.stzb.server.game.battle.BattleEngine
import com.stzb.server.game.battle.BattleEquipmentRepository
import com.stzb.server.game.battle.BattleOutcome
import com.stzb.server.game.battle.BattleRandom
import com.stzb.server.game.battle.BattleRequest
import com.stzb.server.game.battle.BattleTeamBuilder
import com.stzb.server.game.battle.ClientBattleReportStore
import com.stzb.server.game.battle.SeededBattleRandom

data class PvpSettlement(
    val battleId: Int,
    val targetWid: Int,
    val outcome: BattleOutcome,
    val defenderUserId: Int,
    val ownershipTransferred: Boolean,
)

/**
 * Resolves an attacker march against a player garrison snapshot. On victory the
 * garrison is cleared and the target land ownership transfers to the attacker;
 * the defender's persisted state is reconciled by the caller-supplied loader.
 */
class PvpBattleService(
    private val reportStore: ClientBattleReportStore,
    private val config: BattleConfigRepository = BattleConfigRepository.loadDefault(),
    equipmentRepository: BattleEquipmentRepository = BattleEquipmentRepository.loadDefault(),
    private val battleRandomFactory: (Int) -> BattleRandom = ::SeededBattleRandom,
) {
    private val builder = BattleTeamBuilder(config, equipmentRepository)

    fun settle(
        attacker: PlayerState,
        march: PlayerMarch,
        garrison: GarrisonSnapshot,
        nowSec: Int,
        loadDefenderState: (Int) -> PlayerState?,
    ): PvpSettlement {
        val attackerTeam = builder.build(march.participants.map(BattleSpecFactory::fromMarchHero))
        val defenderTeam = builder.build(garrison.specs)
        val result = BattleEngine.resolve(
            BattleRequest(attacker = attackerTeam, defender = defenderTeam, maxRounds = 8),
            config,
            battleRandomFactory(seed(march)),
        )
        val report = reportStore.record(
            ownerUserId = attacker.userId,
            wid = march.targetWid,
            timeSec = nowSec,
            result = result,
        )

        // Persist attacker hero troops from the settled result.
        result.attacker.heroes.forEach { battleHero ->
            val heroUid = march.participants.firstOrNull { it.position == battleHero.position }?.heroUid ?: 0
            attacker.hero(heroUid)?.troops = battleHero.troops.coerceIn(0, PlayerHero.MAX_TROOPS)
        }

        var transferred = false
        if (result.outcome == BattleOutcome.ATTACKER_WIN) {
            WorldStateRepository.removeGarrison(march.targetWid)
            transferred = WorldStateRepository.claimLand(attacker, march.targetWid, nowSec)
            val defenderState = loadDefenderState(garrison.ownerUserId)
            if (defenderState != null && transferred) {
                defenderState.replaceOccupiedLands(
                    defenderState.occupiedLands().filter { it != march.targetWid },
                )
            }
        }

        return PvpSettlement(
            battleId = report.battleId,
            targetWid = march.targetWid,
            outcome = result.outcome,
            defenderUserId = garrison.ownerUserId,
            ownershipTransferred = transferred,
        )
    }

    private fun seed(march: PlayerMarch): Int =
        march.armyId * 31 xor
            march.fromWid * 17 xor
            march.targetWid xor
            march.beginSec
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.stzb.server.game.PvpBattleServiceTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（1 test）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/PvpBattleService.kt src/test/kotlin/com/stzb/server/game/PvpBattleServiceTest.kt
git commit -m "feat(pvp): resolve attacker march against a player garrison"
```

---

### Task 6: Handler 接线 — cmd 60 驻守 + 攻击结算分派 PvP/PvE

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`（when 分支加 `Cmd.RESIDE_FIELD`；新增 `sendReside` + `scheduleResideArrival`；`schedulePveBattleSettlement` 抵达时先查 garrison 分派 PvP）

**Interfaces:**
- Consumes: Task 3 `Cmd.RESIDE_FIELD`/`ResideRequestParser`；Task 4 `GarrisonService`；Task 5 `PvpBattleService`/`PvpSettlement`；现有 `playerState`/`sendArmyStateNotify`/`sendWorldSceneFullInfo`/`broadcastWorldScene`/`onlineSessions`/`PlayerStateRepository`.
- Produces: 运行时行为（无对外类型）。此 Task 为集成接线，验证靠现有单测已覆盖的底层 + 编译通过 + 手动联调，遵循 diagnosing-bugs 的"无合适单测 seam 时记录"。

- [ ] **Step 1: 在命令分派 when 中加入驻守分支**

在 `GameServerHandler` 处理 `Cmd.ARMY_BATTLE` 的分支附近加入：

```kotlin
            Cmd.RESIDE_FIELD -> {
                logIn(msg)
                sendReside(ctx, session, msg)
            }
```

- [ ] **Step 2: 实现 sendReside + 抵达调度**

在 `sendArmyBattle` 附近加入（复用其 session/state 解析与调度模式）：

```kotlin
    private fun sendReside(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
        val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
        val request = ResideRequestParser.parse(msg.bodyText)
        if (request == null) {
            ctx.writeAndFlush(DownPacket.json(Cmd.RESIDE_FIELD, "null", dataType = DownType.PLAIN))
            log.info(">> cmd=60 驻守请求无效 (uid=$userId, body=${msg.bodyText})")
            return
        }
        val state = playerState(session, userId, GameServerConfig.CITY_WID)
        val garrisonService = GarrisonService()
        val march = garrisonService.startReside(
            state = state,
            wid = request.wid,
            armyId = request.armyId,
            nowSec = (System.currentTimeMillis() / 1000).toInt(),
        )
        ctx.writeAndFlush(DownPacket.json(Cmd.RESIDE_FIELD, "null", dataType = DownType.PLAIN))
        if (march != null) {
            PlayerStateRepository.save(state)
            sendArmyStateNotify(ctx, userId, state, request.armyId)
            sendWorldSceneFullInfo(ctx, session)
            scheduleResideArrival(ctx, session, userId, state, garrisonService, request.armyId)
            log.info(">> cmd=60 驻守出发 (uid=$userId, wid=${request.wid}, armyId=${request.armyId})")
        } else {
            log.info(">> cmd=60 驻守未执行: 队伍无可战斗武将或目标非法 (uid=$userId, wid=${request.wid})")
        }
    }

    private fun scheduleResideArrival(
        ctx: ChannelHandlerContext,
        session: Session?,
        userId: Int,
        state: com.stzb.server.game.PlayerState,
        garrisonService: GarrisonService,
        armyId: Int,
    ) {
        val march = state.activeMarch(armyId) ?: return
        val delayMillis = (march.endSec * 1_000L - System.currentTimeMillis()).coerceAtLeast(0L)
        ctx.channel().eventLoop().schedule({
            if (!ctx.channel().isActive) return@schedule
            val snapshot = garrisonService.settleReside(state, armyId) ?: return@schedule
            PlayerStateRepository.save(state)
            sendArmyStateNotify(ctx, userId, state, armyId)
            broadcastWorldScene(removedArmyUserId = userId, removedArmyId = armyId)
            log.info(">> cmd=60 驻守抵达 (uid=$userId, wid=${snapshot.wid}, armyId=$armyId)")
        }, delayMillis, TimeUnit.MILLISECONDS)
    }
```

- [ ] **Step 3: 在攻击结算调度中分派 PvP**

在 `schedulePveBattleSettlement` 的定时回调内，`battleService.settlePveBattle` 调用之前插入 garrison 分派（若目标有敌方驻军则走 PvP，处理完 `return@schedule`）：

```kotlin
            val activeMarch = state.activeMarch(armyId)
            val garrison = activeMarch?.let { WorldStateRepository.garrisonAt(it.targetWid) }
            if (activeMarch != null && garrison != null && garrison.ownerUserId != userId) {
                val due = state.completeMarchIfDue((System.currentTimeMillis() / 1000).toInt(), armyId)
                if (due != null) {
                    val pvp = PvpBattleService(ClientBattleReportStore.global()).settle(
                        attacker = state,
                        march = due,
                        garrison = garrison,
                        nowSec = (System.currentTimeMillis() / 1000).toInt(),
                        loadDefenderState = { uid ->
                            PlayerStateRepository.findExistingByUserId(uid)
                        },
                    )
                    PlayerStateRepository.save(state)
                    ctx.writeAndFlush(
                        DownPacket.json(
                            Cmd.SYS_NOTIFY_DB_UPDATE,
                            GameResponses.battleReportAttackInsertNotify(
                                userId = userId,
                                battleId = pvp.battleId,
                                armyId = armyId,
                                targetWid = pvp.targetWid,
                                outcome = pvp.outcome,
                                heroIds = state.teamHeroes(armyId)
                                    .mapNotNull { state.hero(it)?.heroId }
                                    .filter { it > 0 },
                            ),
                            dataType = DownType.PLAIN,
                        ),
                    )
                    if (pvp.ownershipTransferred) {
                        ctx.writeAndFlush(
                            DownPacket.json(
                                Cmd.SYS_NOTIFY_DB_UPDATE,
                                GameResponses.occupiedLandUpsertNotify(
                                    userId = userId,
                                    cityWid = state.cityWid,
                                    landWid = pvp.targetWid,
                                ),
                                dataType = DownType.PLAIN,
                            ),
                        )
                        broadcastWorldScene(removedArmyUserId = userId, removedArmyId = armyId)
                    } else {
                        sendArmyStateNotify(ctx, userId, state, armyId)
                        sendWorldSceneFullInfo(ctx, session, removedArmyId = armyId)
                    }
                    log.info(">> cmd=6 PvP 结算 (uid=$userId, wid=${pvp.targetWid}, outcome=${pvp.outcome}, transfer=${pvp.ownershipTransferred})")
                    return@schedule
                }
            }
```

Note: 若 `PlayerStateRepository` 无 `findExistingByUserId`，在其中新增：

```kotlin
    fun findExistingByUserId(userId: Int): PlayerState? =
        findExisting("legacy-user-$userId") ?: players.values.firstOrNull { it.userId == userId }
```

（先 grep 现有 `findExisting` 命名，与之对齐；仅当缺失时新增。）

- [ ] **Step 4: 编译并跑全量已有单测（回归保护）**

Run: `./gradlew test --tests "com.stzb.server.game.PlayerBattleServiceTest" --tests "com.stzb.server.game.PvpBattleServiceTest" --tests "com.stzb.server.game.GarrisonServiceTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（PvE 不回归 + PvP/驻守单测通过）；handler 集成靠编译通过，运行时联调在 Task 7。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt src/main/kotlin/com/stzb/server/game/PlayerState.kt
git commit -m "feat(pvp): wire reside command and pvp settlement into handler"
```

---

### Task 7: 5026/5028 世界视野下发驻军 + 端到端联调

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`（`worldMapArmies` 追加 WorldState 驻军段）
- Test: `src/test/kotlin/com/stzb/server/game/GameResponsesGarrisonTest.kt`

**Interfaces:**
- Consumes: Task 1 `WorldStateRepository.garrisons()`；现有 `worldMapArmies` 的 army 段布局（index 0=state, 1=userId, 10=reside_wid）。
- Produces: 5026 army 段包含所有驻军（state=5）。

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/stzb/server/game/GameResponsesGarrisonTest.kt`:

```kotlin
package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.stzb.server.game.battle.BattleHeroSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class GameResponsesGarrisonTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `world scene includes stored garrisons as reside armies`() {
        val wid = 15051540
        WorldStateRepository.putGarrison(
            GarrisonSnapshot(
                wid = wid,
                ownerUserId = 810,
                armyId = 88_001,
                specs = listOf(BattleHeroSpec(heroId = 100021, position = 0, troops = 5000)),
                residedAtSec = 1_700_000_000,
            ),
        )

        val json = GameResponses.worldSceneFullInfo(
            userId = 999,
            cityWid = 15061599,
            roleName = "观察者",
        )
        val armies = mapper.readTree(json)[6]
        val garrisonArmy = armies["88001"]
        assertEquals(5, garrisonArmy[0].asInt())   // state = RESIDE
        assertEquals(810, garrisonArmy[1].asInt()) // userId = defender
        assertEquals(wid, garrisonArmy[10].asInt()) // reside_wid
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.stzb.server.game.GameResponsesGarrisonTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: FAIL — 驻军未出现在 army 段（找不到 key "88001"）。

- [ ] **Step 3: Emit garrisons in worldMapArmies**

在 `GameResponses.worldMapArmies` 生成 marches 段之后、`return@apply`/收尾之前，追加驻军条目（每支驻军一条，state=5, reside_wid 填 index 10，其余槽沿用现有 army 段的占位规则）：

```kotlin
            WorldStateRepository.garrisons().forEach { g ->
                putArray(g.armyId.toString()).apply {
                    add(5)              // 0 state: RESIDE
                    add(g.ownerUserId)  // 1 user id
                    add(g.wid)          // 2 from wid
                    add(g.wid)          // 3 target wid
                    add(g.residedAtSec) // 4 begin time
                    add(0)              // 5 end time (0 = static garrison)
                    add(0)              // 6 army group id
                    add(0)              // 7 center wid
                    add(0)              // 8 shop cancel move
                    add(0)              // 9 target type
                    add(g.wid)          // 10 reside wid
                    add(0)              // 11 stay wid
                    add(0)              // 12 tech jianjun
                    add(0)              // 13 tech quanxiang
                    add(0)              // 14 invited user id
                    add("")             // 15 facade ids
                    add("")             // 16 army hero type
                    add("")             // 17 emotion
                    add("")             // 18 battle effect
                    addNull()           // 19 facade data
                    add(nf.objectNode())// 20 facade data by type
                    add(0)              // 21 serious injury time
                    add(0)              // 22 fort army group
                    add(g.residedAtSec) // 23 reside time
                    add(0)              // 24 siege camp next attack time
                    add(0)              // 25 attack-heart shiqi down
                    add(0)              // 26 countdown facade
                    add(0)              // 27 shiqi
                    add(0)              // 28 real march id
                    add("")             // 29 buffs
                    add(0)              // 30 lu jiao wid
                    add("")             // 31 battle show
                }
            }
```

Note: 确认此追加发生在 `worldMapArmies` 的 `nf.objectNode().apply { ... }` 块内，与 marches 循环并列，且不受 `marches.isEmpty()` 的 early-return 影响（若现有代码在 marches 为空时提前 return，需把驻军循环移到该 return 之前，或去掉 early-return 改为都走循环）。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.stzb.server.game.GameResponsesGarrisonTest" --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: PASS（1 test）。

- [ ] **Step 5: 全量回归**

Run: `./gradlew test --console=plain --no-daemon -Dkotlin.compiler.execution.strategy=in-process`
Expected: 除本会话开始前已存在的既有失败（`OfficialFullBattleReportDiffTest` 等 14 项，改动前后一致）外，新增测试全绿、PvE 与协议测试不回归。运行前先记录既有失败基线，仅对比"新增失败"。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/stzb/server/game/GameResponses.kt src/test/kotlin/com/stzb/server/game/GameResponsesGarrisonTest.kt
git commit -m "feat(pvp): broadcast player garrisons in world scene"
```

- [ ] **Step 7: 端到端联调（手动，双开A/B）**

1. 重编译并重启 59979 服务（`./gradlew installDist` 或现有启动脚本）。
2. 客户端B：选一块土地/主城 → 派队"驻守"（cmd 60）→ 确认地图上显示 state=5 驻军。
3. 客户端A：行军攻打该 wid → 抵达后战报出现、A 队伍减员、胜则该地归属变A。
4. B（在线）：确认收到防守战报红点 / 地图归属更新。
5. 记录联调结果到 `debug-` 或 spec 附注；如失败按 diagnosing-bugs 抓运行时证据。

---

## 附：防守方战报（B 的 Tb_battle_report_defend）范围说明

Spec §8 提到防守方战报测试。本计划的 Task 6 只下发**进攻方 A** 的 `Tb_battle_report_attack` + 归属变更；防守方 B 靠世界视野归属更新（`broadcastWorldScene`）感知被占。给 B 单独下发 `Tb_battle_report_defend` 需要 B 在线时定位其 channel 并推 90005，属可选增强，**本次不做**（YAGNI，spec §7 已允许"离线仅落盘"）。若后续要补，新增一个 Task：`GameResponses.battleReportDefendInsertNotify(userId=B, ...)` + 在 Task 6 PvP 分支里遍历 `onlineSessions` 找到 B 的 channel 推送。此处显式记录，避免"以为覆盖了"。

## 附：与既有武器修复的关系

本会话前序已完成三处武器相关修复（`InventoryCatalog.battleLoadoutForGearUid`、`PlayerBattleService` 装备接入、`ClientBattleReportStore.toGearInfo`、`PlayerState.PlayerMarchHero` 装备字段）尚未提交。本计划 Task 2/4 依赖 `InventoryCatalog.battleLoadoutForGearUid` 与 `PlayerMarchHero` 的装备字段，执行本计划前应先提交那些改动（或在 Task 1 之前作为前置提交）。
