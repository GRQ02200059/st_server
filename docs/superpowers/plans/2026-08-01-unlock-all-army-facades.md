# 全行军外观解锁实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅通过 Kotlin 服务端解锁 16 个真实行军外观，支持普通外观卡绑定、特殊外观启用、持久化与出征地图渲染。

**Architecture:** `ArmyFacadeCatalog` 从客户端二进制配置生成 12 个普通外观目录，`PlayerState` 保存 60 张普通卡、4 张特殊卡和武将当前外观。登录快照、90005、`Tb_army[61]` 与 5026 都从同一状态投影，确保客户端库存、武将详情与地图行军模型一致。

**Tech Stack:** Kotlin 1.9.23, JDK 17, Gradle, Netty, Jackson, kotlin.test, MemoryPack client configuration tables.

## Global Constraints

- 只修改服务端 Kotlin 和服务端资源；禁止修改、注入或替换客户端文件、DLL、内存或网络代理。
- 普通行军外观严格来自 `tb_cfg_army_facade_shop.bin`；不能恢复硬编码 12 项列表作为回退。
- 每种普通外观固定下发 5 张永久卡，共 60 张；每张卡只能绑定一个五星、非素材武将配置。
- 支持的特殊行军外观固定为 `101073`、`101515`、`101618`、`101680`；拒绝 `101681`、`101155`、`5100`、`999991`。
- 保持客户端字段约定：`Tb_user_army_facade_card[5]`、`Tb_hero[72]`、特殊卡 `Tb_hero[5]`、`Tb_army[61]`、5026 行军 tuple `[15]`。
- 成功操作先回原命令 `[]`，再保存并下发 `90005`；无效或无变化请求只回 `[]`，不保存、不发 `90005`。
- 每一步只暂存计划列出的文件，不提交工作区已有的战报、地图、主城、工会或其他在途改动。
- Kotlin 编译与测试统一使用 `./gradlew -Dkotlin.compiler.execution.strategy=in-process ...`。
- 运行服务端仍使用端口 `59979`；完成时验证发行 JAR 的 SHA-256。

---

## 文件结构

| 文件 | 责任 |
|---|---|
| `src/main/resources/client-config/tb_cfg_army_facade_shop.bin` | 与客户端一致的普通行军外观权威配置。 |
| `src/main/kotlin/com/stzb/server/game/ArmyFacadeCatalog.kt` | 解析普通外观配置、定义特殊外观集合与稳定卡 ID。 |
| `src/main/kotlin/com/stzb/server/game/ArmyFacadeOperationRequestParser.kt` | 解析 `677`、`678`、`682`、`2520` 的 JSON 请求体。 |
| `src/main/kotlin/com/stzb/server/game/PlayerState.kt` | 保存外观卡、武将当前外观、特殊卡状态和行军快照外观。 |
| `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt` | 在发兵与补偿结算路径冻结每名参战武将的行军外观。 |
| `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt` | 将普通卡、特殊卡、武将和部队外观投影到 99991。 |
| `src/main/kotlin/com/stzb/server/game/GameResponses.kt` | 构造 90005 外观更新、`Tb_army[61]` 和 5026 行军外观数据。 |
| `src/main/kotlin/com/stzb/server/protocol/Cmd.kt` | 声明四个行军外观命令号。 |
| `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt` | 路由、执行、保存并下发外观命令结果。 |
| `src/test/kotlin/com/stzb/server/game/ArmyFacadeCatalogTest.kt` | 验证二进制配置解析和 16 项目录边界。 |
| `src/test/kotlin/com/stzb/server/game/ArmyFacadeOperationRequestParserTest.kt` | 验证请求体解析与拒绝规则。 |
| `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt` | 验证状态转换、数量上限、旧存档归一化与重登。 |
| `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt` | 验证 99991 的普通卡、特殊卡、武将和部队投影。 |
| `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt` | 验证 90005 排序和 5026/`facade_ids` 格式。 |
| `src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt` | 验证发兵时冻结行军外观。 |
| `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt` | 验证四个命令的网络顺序、持久化和活跃行军刷新。 |

### Task 1: 配置驱动的行军外观目录

**Files:**
- Create: `src/main/resources/client-config/tb_cfg_army_facade_shop.bin`
- Create: `src/main/kotlin/com/stzb/server/game/ArmyFacadeCatalog.kt`
- Create: `src/test/kotlin/com/stzb/server/game/ArmyFacadeCatalogTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/FacadeCatalog.kt:35-38`

**Interfaces:**
- Consumes: `MemoryPackTable.open(bytes, source)`、`LittleEndianReader.byte()`、`LittleEndianReader.int()`。
- Produces: `ArmyFacadeCatalog.standardFacadeIds()`, `ArmyFacadeCatalog.isStandardFacade(id)`, `ArmyFacadeCatalog.isSpecialFacade(id)`, `ArmyFacadeCatalog.specialFacadeIds()`, `ArmyFacadeCatalog.cardId(facadeId, copyIndex)`, `ArmyFacadeCatalog.specialCardUid(facadeId)`。

- [ ] **Step 1: 复制客户端权威配置到服务端资源目录**

```bash
cp \
  ../stzb_9.2.2_out_branch_9.1.1776213/assets/npk_extracted_all/others/res/csharp/data/tcfg/default/tb_cfg_army_facade_shop.bin \
  src/main/resources/client-config/tb_cfg_army_facade_shop.bin
shasum -a 256 src/main/resources/client-config/tb_cfg_army_facade_shop.bin
```

Expected: 文件大小为 `881` 字节，SHA-256 输出一行。

- [ ] **Step 2: 写出目录测试**

```kotlin
package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmyFacadeCatalogTest {
    @Test
    fun `catalog parses every normal facade from the client shop table`() {
        assertEquals(
            listOf(
                101138, 101156, 101174, 101216, 101239, 101342,
                101417, 101460, 101554, 101565, 101611, 101682,
            ),
            ArmyFacadeCatalog.standardFacadeIds(),
        )
        assertEquals(12 * ArmyFacadeCatalog.COPIES_PER_STANDARD_FACADE, ArmyFacadeCatalog.cardCount())
    }

    @Test
    fun `catalog separates supported special facades from unsupported ids`() {
        assertEquals(setOf(101073, 101515, 101618, 101680), ArmyFacadeCatalog.specialFacadeIds())
        assertTrue(ArmyFacadeCatalog.isSpecialFacade(101073))
        assertTrue(ArmyFacadeCatalog.isStandardFacade(101682))
        listOf(101681, 101155, 5100, 999991).forEach { id ->
            assertFalse(ArmyFacadeCatalog.isStandardFacade(id))
            assertFalse(ArmyFacadeCatalog.isSpecialFacade(id))
        }
    }
}
```

- [ ] **Step 3: 运行测试确认红灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.ArmyFacadeCatalogTest
```

Expected: FAIL，`ArmyFacadeCatalog` 尚未定义。

- [ ] **Step 4: 实现目录和稳定 ID**

```kotlin
package com.stzb.server.game

data class ArmyFacadeCardSeed(
    val cardId: Int,
    val facadeId: Int,
)

object ArmyFacadeCatalog {
    const val COPIES_PER_STANDARD_FACADE = 5
    const val YUXI_FACADE_ID = 101073

    private const val RESOURCE = "tb_cfg_army_facade_shop.bin"
    private const val SPECIAL_CARD_UID_BASE = Int.MAX_VALUE - 10

    private val standardIds: List<Int> by lazy(::loadStandardFacadeIds)
    private val specialIds = listOf(101073, 101515, 101618, 101680)
    private val specialIdSet = specialIds.toSet()

    fun standardFacadeIds(): List<Int> = standardIds

    fun specialFacadeIds(): Set<Int> = specialIdSet

    fun isStandardFacade(facadeId: Int): Boolean = facadeId in standardIds

    fun isSpecialFacade(facadeId: Int): Boolean = facadeId in specialIdSet

    fun cardCount(): Int = standardIds.size * COPIES_PER_STANDARD_FACADE

    fun defaultCards(): List<ArmyFacadeCardSeed> =
        standardIds.flatMap { facadeId ->
            (1..COPIES_PER_STANDARD_FACADE).map { copyIndex ->
                ArmyFacadeCardSeed(cardId = cardId(facadeId, copyIndex), facadeId = facadeId)
            }
        }

    fun cardId(facadeId: Int, copyIndex: Int): Int {
        require(isStandardFacade(facadeId))
        require(copyIndex in 1..COPIES_PER_STANDARD_FACADE)
        return facadeId * 100 + copyIndex
    }

    fun specialCardUid(facadeId: Int): Int {
        val index = specialIds.indexOf(facadeId)
        require(index >= 0) { "unsupported special army facade: $facadeId" }
        return SPECIAL_CARD_UID_BASE + index
    }

    private fun loadStandardFacadeIds(): List<Int> {
        val bytes = ArmyFacadeCatalog::class.java.getResourceAsStream("/client-config/$RESOURCE")
            ?.use { it.readBytes() }
            ?: error("missing client configuration: /client-config/$RESOURCE")
        val table = MemoryPackTable.open(bytes, RESOURCE)
        return table.keys.map { key ->
            require(table.reader.byte().toInt() and 0xff == 9) { "invalid $RESOURCE row" }
            val heroId = table.reader.int()
            repeat(5) { table.reader.int() }
            repeat(3) { table.reader.int() }
            require(heroId == key && heroId > 0) { "invalid $RESOURCE key/hero pair: $key/$heroId" }
            heroId
        }.distinct()
    }
}
```

Replace `FacadeCatalog.armyFacadeIds` with:

```kotlin
val armyFacadeIds: List<Int>
    get() = ArmyFacadeCatalog.standardFacadeIds()
```

- [ ] **Step 5: 运行目录测试确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.ArmyFacadeCatalogTest
```

Expected: PASS，两个测试均通过。

- [ ] **Step 6: 提交目录功能**

```bash
git add \
  src/main/resources/client-config/tb_cfg_army_facade_shop.bin \
  src/main/kotlin/com/stzb/server/game/ArmyFacadeCatalog.kt \
  src/main/kotlin/com/stzb/server/game/FacadeCatalog.kt \
  src/test/kotlin/com/stzb/server/game/ArmyFacadeCatalogTest.kt
git diff --cached --check
git commit -m "feat: load army facades from client config"
```

### Task 2: 持久化外观卡和行军快照状态

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt:23-151,414-512,604-700`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt:42-71,80-103`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt`

**Interfaces:**
- Consumes: `ArmyFacadeCatalog.defaultCards()`, `HeroCatalog.heroQuality(heroId)`, `PlayerHero.isAdvanceMaterial`.
- Produces: `PlayerState.bindArmyFacadeCards(facadeId, heroUids)`, `PlayerState.useArmyFacade(heroUid, facadeId)`, `PlayerState.setSpecialArmyFacadeState(cardUid, state)`, `PlayerState.armyFacadeCards()`, `PlayerState.specialArmyFacadeCards()`, `PlayerState.armyFacadeIds(armyId)`, `PlayerMarch.facadeIds()`.

- [ ] **Step 1: 写出状态和持久化测试**

```kotlin
@Test
fun `each normal facade has five cards and batch binding survives restore`() {
    val state = PlayerState(userId = 71, cityWid = 10071, roleName = "主公")
    val heroes = HeroCatalog.defaultFiveStarHeroIds().take(5).map(state::addHero)
    require(heroes.size == 5)

    assertEquals(60, state.armyFacadeCards().size)
    val result = state.bindArmyFacadeCards(101138, heroes.map(PlayerHero::heroUid))
    assertEquals(5, result?.cardCfgHeroIds?.size)
    assertTrue(heroes.all { it.armyFacadeCardId == 101138 })

    val restored = PlayerState.fromSnapshot(state.toSnapshot())
    assertEquals(
        heroes.map(PlayerHero::heroId).toSet(),
        restored.armyFacadeCards()
            .filter { it.facadeId == 101138 && it.cfgHeroId > 0 }
            .map(PlayerArmyFacadeCard::cfgHeroId)
            .toSet(),
    )
    assertTrue(heroes.all { restored.hero(it.heroUid)?.armyFacadeCardId == 101138 })
}

@Test
fun `army facade binding rejects duplicate configs foreign non five star and exhausted cards`() {
    val state = PlayerState(userId = 72, cityWid = 10072, roleName = "主公")
    val fiveStarId = HeroCatalog.defaultFiveStarHeroIds().first()
    val sameConfigA = state.addHero(fiveStarId)
    val sameConfigB = state.addHero(fiveStarId)
    val nonFiveStar = state.addHero(
        HeroCatalog.recruitableHeroIds().first { HeroCatalog.heroQuality(it) != 4 },
    )

    assertNull(state.bindArmyFacadeCards(101138, listOf(sameConfigA.heroUid, sameConfigB.heroUid)))
    assertNull(state.bindArmyFacadeCards(101138, listOf(nonFiveStar.heroUid)))
    assertNull(state.bindArmyFacadeCards(999999, listOf(sameConfigA.heroUid)))
    assertEquals(0, sameConfigA.armyFacadeCardId)
}

@Test
fun `special facade activation and march snapshot preserve facade ids`() {
    val state = PlayerState(userId = 73, cityWid = 10073, roleName = "主公")
    val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
    state.assignTeamHero(hero.heroUid, pos = 1)
    assertTrue(state.useArmyFacade(hero.heroUid, 101073) != null)

    val xiyuan = state.specialArmyFacadeCards().single { it.facadeId == 101515 }
    assertTrue(state.setSpecialArmyFacadeState(xiyuan.heroUid, 2) != null)
    val march = state.startMarch(
        targetWid = 10074,
        nowSec = 1,
        participants = listOf(
            PlayerMarchHero(
                heroUid = hero.heroUid,
                position = 0,
                heroId = hero.heroId,
                troops = hero.troops,
                level = hero.level,
                skillIds = hero.normalizedSkillIds(),
                armyFacadeCardId = hero.armyFacadeCardId,
            ),
        ),
        specialArmyFacadeId = state.activeSpecialArmyFacadeId(),
    )

    assertEquals("101515,0;", march.facadeIds())
    assertEquals("101515,0;", PlayerState.fromSnapshot(state.toSnapshot()).activeMarch()?.facadeIds())
}

@Test
fun `legacy snapshots gain all facade cards and only one non yuxi special card can be active`() {
    val restored = PlayerState.fromSnapshot(
        PlayerStateSnapshot(
            accountKey = "legacy-army-facade",
            userId = 74,
            cityWid = 10074,
            roleName = "主公",
        ),
    )
    val xiyuan = restored.specialArmyFacadeCards().single { it.facadeId == 101515 }
    val xiyuanYuxi = restored.specialArmyFacadeCards().single { it.facadeId == 101618 }

    assertEquals(60, restored.armyFacadeCards().size)
    assertEquals(4, restored.specialArmyFacadeCards().size)
    assertTrue(restored.setSpecialArmyFacadeState(xiyuan.heroUid, 2) != null)
    assertTrue(restored.setSpecialArmyFacadeState(xiyuanYuxi.heroUid, 2) != null)
    assertEquals(0, restored.specialArmyFacadeCards().single { it.heroUid == xiyuan.heroUid }.state)
    assertEquals(2, restored.specialArmyFacadeCards().single { it.heroUid == xiyuanYuxi.heroUid }.state)
}
```

- [ ] **Step 2: 运行状态测试确认红灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.PlayerStatePersistenceTest \
  --tests com.stzb.server.game.PlayerBattleServiceTest
```

Expected: FAIL，`armyFacadeCards`、`bindArmyFacadeCards`、`armyFacadeCardId` 和特殊卡 API 未定义。

- [ ] **Step 3: 添加状态类型、快照字段和归一化**

Add these types above `PlayerStateSnapshot`:

```kotlin
data class PlayerArmyFacadeCard(
    val cardId: Int,
    val facadeId: Int,
    var cfgHeroId: Int = 0,
)

data class PlayerSpecialArmyFacadeCard(
    val heroUid: Int,
    val facadeId: Int,
    var state: Int = 0,
)

data class PlayerArmyFacadeCardSnapshot(
    val cardId: Int,
    val facadeId: Int,
    val cfgHeroId: Int = 0,
)

data class PlayerSpecialArmyFacadeCardSnapshot(
    val heroUid: Int,
    val facadeId: Int,
    val state: Int = 0,
)

data class ArmyFacadeMutation(
    val cardCfgHeroIds: Map<Int, Int> = emptyMap(),
    val heroFacadeIds: Map<Int, Int> = emptyMap(),
    val specialCardStates: Map<Int, Int> = emptyMap(),
    val affectedArmyIds: Set<Int> = emptySet(),
)
```

Add these fields with defaults to `PlayerStateSnapshot`:

```kotlin
val armyFacadeCards: List<PlayerArmyFacadeCardSnapshot> = emptyList(),
val specialArmyFacadeCards: List<PlayerSpecialArmyFacadeCardSnapshot> = emptyList(),
```

Add `armyFacadeCardId` with default `0` to `PlayerHero` and `PlayerHeroSnapshot`; add
`armyFacadeCardId: Int = 0` to `PlayerMarchHero`, and
`specialArmyFacadeId: Int = 0` to `PlayerMarch` and `PlayerMarchSnapshot`.

Add the state collections and public operations to `PlayerState`:

```kotlin
private val armyFacadeCards = mutableListOf<PlayerArmyFacadeCard>()
private val specialArmyFacadeCards = mutableListOf<PlayerSpecialArmyFacadeCard>()

fun armyFacadeCards(): List<PlayerArmyFacadeCard> =
    armyFacadeCards.sortedBy(PlayerArmyFacadeCard::cardId)

fun specialArmyFacadeCards(): List<PlayerSpecialArmyFacadeCard> =
    specialArmyFacadeCards.sortedBy(PlayerSpecialArmyFacadeCard::heroUid)

fun activeSpecialArmyFacadeId(): Int =
    specialArmyFacadeCards
        .singleOrNull { it.facadeId != ArmyFacadeCatalog.YUXI_FACADE_ID && it.state == 2 }
        ?.facadeId
        ?: 0

fun bindArmyFacadeCards(facadeId: Int, heroUids: List<Int>): ArmyFacadeMutation? {
    if (!ArmyFacadeCatalog.isStandardFacade(facadeId)) return null
    val distinctUids = heroUids.distinct()
    if (distinctUids.isEmpty() || distinctUids.size != heroUids.size) return null
    val heroesToBind = distinctUids.map { hero(it) ?: return null }
    if (heroesToBind.any { it.isAdvanceMaterial || HeroCatalog.heroQuality(it.heroId) != 4 }) return null
    if (heroesToBind.map(PlayerHero::heroId).toSet().size != heroesToBind.size) return null
    if (heroesToBind.any { target ->
            armyFacadeCards.any { it.facadeId == facadeId && it.cfgHeroId == target.heroId }
        }
    ) {
        return null
    }
    val availableCards = armyFacadeCards
        .filter { it.facadeId == facadeId && it.cfgHeroId == 0 }
        .sortedBy(PlayerArmyFacadeCard::cardId)
    if (availableCards.size < heroesToBind.size) return null

    val changedCards = linkedMapOf<Int, Int>()
    val changedHeroes = linkedMapOf<Int, Int>()
    heroesToBind.zip(availableCards).forEach { (target, card) ->
        card.cfgHeroId = target.heroId
        target.armyFacadeCardId = facadeId
        changedCards[card.cardId] = card.cfgHeroId
        changedHeroes[target.heroUid] = facadeId
    }
    return ArmyFacadeMutation(
        cardCfgHeroIds = changedCards,
        heroFacadeIds = changedHeroes,
        affectedArmyIds = heroesToBind.map(PlayerHero::armyId).filter { it > 0 }.toSortedSet(),
    )
}

fun useArmyFacade(heroUid: Int, facadeId: Int): ArmyFacadeMutation? {
    val target = hero(heroUid) ?: return null
    if (target.isAdvanceMaterial || HeroCatalog.heroQuality(target.heroId) != 4) return null
    val allowed = when {
        facadeId == 0 -> true
        facadeId == ArmyFacadeCatalog.YUXI_FACADE_ID ->
            specialArmyFacadeCards.any { it.facadeId == facadeId }
        ArmyFacadeCatalog.isStandardFacade(facadeId) ->
            armyFacadeCards.any { it.facadeId == facadeId && it.cfgHeroId == target.heroId }
        else -> false
    }
    if (!allowed || target.armyFacadeCardId == facadeId) return null
    target.armyFacadeCardId = facadeId
    return ArmyFacadeMutation(
        heroFacadeIds = mapOf(target.heroUid to facadeId),
        affectedArmyIds = setOfNotNull(target.armyId.takeIf { it > 0 }),
    )
}

fun setSpecialArmyFacadeState(cardUid: Int, state: Int): ArmyFacadeMutation? {
    if (state !in setOf(0, 2)) return null
    val target = specialArmyFacadeCards.singleOrNull { it.heroUid == cardUid } ?: return null
    if (target.facadeId == ArmyFacadeCatalog.YUXI_FACADE_ID) return null
    if (state == target.state) return null

    val changed = linkedMapOf<Int, Int>()
    if (state == 2) {
        specialArmyFacadeCards
            .filter { it.facadeId != ArmyFacadeCatalog.YUXI_FACADE_ID && it.state != 0 }
            .forEach {
                it.state = 0
                changed[it.heroUid] = 0
            }
    }
    target.state = state
    changed[target.heroUid] = state
    return ArmyFacadeMutation(
        specialCardStates = changed,
        affectedArmyIds = armyIds().toSortedSet(),
    )
}
```

Implement `normalizeArmyFacades()` and call it after `normalizeEquippedGears()` in
`fromSnapshot`. It must create all `ArmyFacadeCatalog.defaultCards()` absent from the
snapshot, keep only cards whose `(cardId, facadeId)` match the catalog, clear invalid
bindings, deduplicate `(facadeId, cfgHeroId)`, retain all four supported special cards,
and normalize special states to exactly zero or one card at state `2`.

Persist both collections from `toSnapshot()` and restore them before normalization.

Add:

```kotlin
fun armyFacadeIds(armyId: Int): String =
    encodeFacadeIds(
        teamHeroes(armyId).mapIndexedNotNull { position, heroUid ->
            hero(heroUid)?.let { current ->
                (activeSpecialArmyFacadeId().takeIf { it > 0 } ?: current.armyFacadeCardId)
                    .takeIf { it > 0 }
                    ?.let { facadeId -> facadeId to position }
            }
        },
    )

private fun encodeFacadeIds(entries: List<Pair<Int, Int>>): String =
    entries.joinToString(separator = "", postfix = "") { (facadeId, position) ->
        "$facadeId,$position;"
    }
```

Add the corresponding `PlayerMarch.facadeIds()` method using
`specialArmyFacadeId` when it is positive, otherwise each participant's
`armyFacadeCardId`.

- [ ] **Step 4: 冻结发兵时的外观**

In both `PlayerMarchHero` constructors in `PlayerBattleService`, add:

```kotlin
armyFacadeCardId = hero.armyFacadeCardId,
```

In the `state.startMarch` call, add:

```kotlin
specialArmyFacadeId = state.activeSpecialArmyFacadeId(),
```

In the fallback `PlayerMarchHero` construction used by `settlePveBattle`, add the
same `armyFacadeCardId` assignment so an old empty participant list uses the current
state exactly once.

- [ ] **Step 5: 运行状态与行军测试确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.PlayerStatePersistenceTest \
  --tests com.stzb.server.game.PlayerBattleServiceTest
```

Expected: PASS，旧快照恢复、60 张普通卡、4 张特殊卡、5 张上限和行军快照均通过。

- [ ] **Step 6: 提交状态功能**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/PlayerState.kt \
  src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt \
  src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt \
  src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt
git diff --cached --check
git commit -m "feat: persist army facade ownership"
```

### Task 3: 登录、90005 和地图行军投影

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:135-180,510-669`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt:233-333,498-560,715-758,930-988`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

**Interfaces:**
- Consumes: `PlayerState.armyFacadeCards()`, `PlayerState.specialArmyFacadeCards()`, `PlayerState.armyFacadeIds(armyId)`, `PlayerMarch.facadeIds()`, `ArmyFacadeMutation`.
- Produces: `GameResponses.armyFacadeNotify(state, mutation)`, `Tb_hero[72]`, special `Tb_hero[5]`, `Tb_army[61]`, 5026 tuple `[15]`.

- [ ] **Step 1: 写出登录和回包投影测试**

```kotlin
@Test
fun `login snapshot projects sixty normal cards four special cards and hero facade field`() {
    val state = PlayerState(userId = 81, cityWid = 10081, roleName = "主公")
    val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
    assertTrue(state.bindArmyFacadeCards(101138, listOf(hero.heroUid)) != null)
    val root = createTempDirectory("army-facade-snapshot")
    PlayerStateRepository.configure(FilePlayerRepository(root))
    PlayerStateRepository.save(state)

    val tables = UserInitTableBuilder.build(81, 10081, "主公", 1_700_000_000L)
        .drop(1)
        .associateBy { it[0].asText() }
    val normalCards = tables.getValue("Tb_user_army_facade_card")[1]
    val heroes = tables.getValue("Tb_hero")[1]

    assertEquals(60, normalCards.size())
    assertEquals(5, normalCards.count { it[2].asInt() == 101138 })
    assertEquals(4, heroes.count { it[1].asInt() in ArmyFacadeCatalog.specialFacadeIds() })
    assertEquals(101138, heroes.single { it[0].asInt() == hero.heroUid }[72].asInt())
}

@Test
fun `facade notification orders cards before heroes then special cards and armies`() {
    val state = PlayerState(userId = 82, cityWid = 10082, roleName = "主公")
    val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
    val mutation = requireNotNull(state.bindArmyFacadeCards(101138, listOf(hero.heroUid)))

    val updates = mapper.readTree(GameResponses.armyFacadeNotify(state, mutation))

    assertEquals("Tb_user_army_facade_card", updates[0][1].asText())
    assertEquals(listOf(0, 10113801, 5, 100017), updates[0][2].map { it.asInt() })
    assertEquals("Tb_hero", updates[1][1].asText())
    assertEquals(listOf(0, hero.heroUid, 72, 101138), updates[1][2].map { it.asInt() })
}

@Test
fun `world scene projects the captured facade id at tuple index fifteen`() {
    val march = PlayerMarch(
        armyId = 100_811,
        fromWid = 10081,
        targetWid = 10082,
        beginSec = 1,
        endSec = 4,
        participants = listOf(
            PlayerMarchHero(1, 0, 100017, 1000, 50, listOf(200017), armyFacadeCardId = 101138),
        ),
    )

    val response = mapper.readTree(
        GameResponses.worldSceneFullInfo(81, 10081, "主公", marches = listOf(march)),
    )

    assertEquals("101138,0;", response[6]["100811"][15].asText())
}
```

- [ ] **Step 2: 运行投影测试确认红灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.GameResponsesTest
```

Expected: FAIL，快照仍只有 12 张普通卡，`armyFacadeNotify` 和字段 72 尚未实现。

- [ ] **Step 3: 修改 99991 投影**

In `UserInitTableBuilder.build`, replace the existing `Tb_hero` creation with normal and
special rows:

```kotlin
val specialFacadeHeroes = state.specialArmyFacadeCards()
    .map { card -> tbSpecialArmyFacade(playerId, card) }
root.add(
    table(
        "Tb_hero",
        *(state.allHeroes().map { tbHero(it, playerId) } + specialFacadeHeroes).toTypedArray(),
    ),
)
```

Replace the normal card table with:

```kotlin
root.add(
    table(
        "Tb_user_army_facade_card",
        *state.armyFacadeCards()
            .map { card -> tbUserArmyFacadeCard(playerId, card) }
            .toTypedArray(),
    ),
)
```

Change the normal hero row to include:

```kotlin
.i(72, hero.armyFacadeCardId) // army_facade_card_id
```

Implement the special card row without adding it to `PlayerState.heroes`:

```kotlin
private fun tbSpecialArmyFacade(
    userId: Int,
    card: PlayerSpecialArmyFacadeCard,
): ArrayNode =
    row("Tb_hero")
        .i(0, card.heroUid)
        .i(1, card.facadeId)
        .i(2, userId)
        .i(3, 0)
        .i(4, 0)
        .i(5, card.state)
        .i(6, 1)
        .i(7, PlayerHero.MAX_STAMINA)
        .i(9, 1)
        .i(11, 0)
        .s(22, "0,0;0,0;0,0;")
        .i(24, 1)
        .i(32, 1)
        .i(72, 0)
        .arr
```

Change `tbUserArmyFacadeCard` to consume `PlayerArmyFacadeCard`:

```kotlin
private fun tbUserArmyFacadeCard(
    userId: Int,
    card: PlayerArmyFacadeCard,
): ArrayNode =
    row("Tb_user_army_facade_card")
        .i(0, card.cardId)
        .i(1, userId)
        .i(2, card.facadeId)
        .i(3, 0)
        .i(4, 0)
        .i(5, card.cfgHeroId)
        .i(6, 0)
        .i(7, 0)
        .arr
```

Append `.s(61, state.armyFacadeIds(armyId))` to `tbArmy` so the row builder fills
intermediate typed defaults safely.

- [ ] **Step 4: 修改增量和地图投影**

Add `armyFacadeNotify` to `GameResponses`:

```kotlin
fun armyFacadeNotify(state: PlayerState, mutation: ArmyFacadeMutation): String =
    mapper.writeValueAsString(
        nf.arrayNode().apply {
            mutation.cardCfgHeroIds.toSortedMap().forEach { (cardId, cfgHeroId) ->
                add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_user_army_facade_card")
                        .add(nf.arrayNode().add(0).add(cardId).add(5).add(cfgHeroId)),
                )
            }
            mutation.heroFacadeIds.toSortedMap().forEach { (heroUid, facadeId) ->
                add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_hero")
                        .add(nf.arrayNode().add(0).add(heroUid).add(72).add(facadeId)),
                )
            }
            mutation.specialCardStates.toSortedMap().forEach { (cardUid, stateValue) ->
                add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_hero")
                        .add(nf.arrayNode().add(0).add(cardUid).add(5).add(stateValue)),
                )
            }
            mutation.affectedArmyIds.sorted().forEach { armyId ->
                add(
                    nf.arrayNode()
                        .add(2)
                        .add("Tb_army")
                        .add(nf.arrayNode().add(0).add(armyId).add(61).add(state.armyFacadeIds(armyId))),
                )
            }
        },
    )
```

Update `GameResponses.tbHero` with the actual types from `tb_field_types.json` through
index 72:

```kotlin
repeat(25) { add(0) } // 44..68
add("") // 69 recurit_unit_res_cost
add(0) // 70 read_time
add(0) // 71 get_time
add(hero.armyFacadeCardId) // 72 army_facade_card_id
```

Replace the 5026 tuple slot 15:

```kotlin
add(march.facadeIds()) // 15 facade ids
```

No other 5026 slot changes are permitted.

Do not extend `GameResponses.tbArmy` to field 61. `Tb_army` has string fields between
indices 19 and 61, and the `armyFacadeNotify` sparse update above is the only runtime
path that writes field 61. The 99991 `Row` builder is safe to write field 61 because it
fills all intervening values from `tb_field_types.json`.

- [ ] **Step 5: 运行投影测试确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.GameResponsesTest
```

Expected: PASS，60 张普通卡、4 张特殊卡、字段 72、字段 61 和 5026 第 15 槽断言通过。

- [ ] **Step 6: 提交投影功能**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/main/kotlin/com/stzb/server/game/GameResponses.kt \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt \
  src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt
git diff --cached --check
git commit -m "feat: project army facades to client tables"
```

### Task 4: 实现行军外观协议处理

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/ArmyFacadeOperationRequestParser.kt`
- Create: `src/test/kotlin/com/stzb/server/game/ArmyFacadeOperationRequestParserTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt:35-79`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt:207-285,841-865`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- Consumes: `PlayerState.bindArmyFacadeCards`, `PlayerState.useArmyFacade`, `PlayerState.setSpecialArmyFacadeState`, `GameResponses.armyFacadeNotify`.
- Produces: `ArmyFacadeBindRequest`, `ArmyFacadeUseRequest`, `SpecialArmyFacadeStateRequest`, and handler support for `677`/`678`/`682`/`2520`.

- [ ] **Step 1: 写出请求解析测试**

```kotlin
package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArmyFacadeOperationRequestParserTest {
    @Test
    fun `batch and single facade requests require positive facade and hero ids`() {
        assertEquals(
            ArmyFacadeBindRequest(101138, listOf(10001, 10002)),
            ArmyFacadeOperationRequestParser.parseBatch("""[101138,[10001,10002]]"""),
        )
        assertEquals(
            ArmyFacadeBindRequest(101138, listOf(10001)),
            ArmyFacadeOperationRequestParser.parseSingle("""[101138,10001]"""),
        )
        assertNull(ArmyFacadeOperationRequestParser.parseBatch("""[101138,[]]"""))
        assertNull(ArmyFacadeOperationRequestParser.parseSingle("""[0,10001]"""))
    }

    @Test
    fun `use and special state requests accept only exact scalar shapes`() {
        assertEquals(
            ArmyFacadeUseRequest(101073, 10001),
            ArmyFacadeOperationRequestParser.parseUse("""[101073,10001]"""),
        )
        assertEquals(
            SpecialArmyFacadeStateRequest(ArmyFacadeCatalog.specialCardUid(101515), 2),
            ArmyFacadeOperationRequestParser.parseSpecialState(
                "[${ArmyFacadeCatalog.specialCardUid(101515)},2]",
            ),
        )
        assertNull(ArmyFacadeOperationRequestParser.parseUse("""[101073]"""))
        assertNull(ArmyFacadeOperationRequestParser.parseSpecialState("""[1,1]"""))
    }
}
```

- [ ] **Step 2: 运行解析测试确认红灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.ArmyFacadeOperationRequestParserTest
```

Expected: FAIL，解析器和请求数据类未定义。

- [ ] **Step 3: 实现请求解析器**

```kotlin
package com.stzb.server.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ArmyFacadeBindRequest(
    val facadeId: Int,
    val heroUids: List<Int>,
)

data class ArmyFacadeUseRequest(
    val facadeId: Int,
    val heroUid: Int,
)

data class SpecialArmyFacadeStateRequest(
    val specialCardUid: Int,
    val state: Int,
)

object ArmyFacadeOperationRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parseBatch(body: String): ArmyFacadeBindRequest? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 && it[1].isArray }
            ?.let { root ->
                val facadeId = root[0].asInt()
                val heroUids = root[1].map { it.asInt() }
                ArmyFacadeBindRequest(facadeId, heroUids)
                    .takeIf { facadeId > 0 && heroUids.isNotEmpty() && heroUids.all { it > 0 } }
            }

    fun parseSingle(body: String): ArmyFacadeBindRequest? =
        parsePair(body, allowDefaultFacade = false)?.let { (facadeId, heroUid) ->
            ArmyFacadeBindRequest(facadeId, listOf(heroUid))
        }

    fun parseUse(body: String): ArmyFacadeUseRequest? =
        parsePair(body, allowDefaultFacade = true)?.let { (facadeId, heroUid) ->
            ArmyFacadeUseRequest(facadeId, heroUid)
        }

    fun parseSpecialState(body: String): SpecialArmyFacadeStateRequest? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 }
            ?.let { root ->
                SpecialArmyFacadeStateRequest(root[0].asInt(), root[1].asInt())
                    .takeIf { it.specialCardUid > 0 && it.state in setOf(0, 2) }
            }

    private fun parsePair(body: String, allowDefaultFacade: Boolean): Pair<Int, Int>? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf { it.isArray && it.size() == 2 }
            ?.let { root -> root[0].asInt() to root[1].asInt() }
            ?.takeIf { (facadeId, heroUid) ->
                heroUid > 0 && if (allowDefaultFacade) facadeId >= 0 else facadeId > 0
            }
}
```

- [ ] **Step 4: 写出协议级红灯测试**

```kotlin
@Test
fun `army facade commands mutate only valid owned cards and publish ordered updates`() {
    val channel = newChannel()
    platformLogin(channel, "army-facade-owner")
    val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
    val state = PlayerStateRepository.getOrCreate(
        accountKey = requireNotNull(session.accountKey),
        cityWid = GameServerConfig.CITY_WID,
        roleName = GameServerConfig.ROLE_NAME,
    )
    val heroes = HeroCatalog.defaultFiveStarHeroIds().take(5).map(state::addHero)
    require(heroes.size == 5)
    PlayerStateRepository.save(state)

    channel.writeInbound(
        upPacket(
            Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
            """[101138,[${heroes[0].heroUid},${heroes[1].heroUid}]]""",
            userId = session.userId,
        ),
    )
    assertEquals(Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    val batchChanges = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)
    assertEquals("Tb_user_army_facade_card", batchChanges[0][1].asText())
    assertEquals("Tb_user_army_facade_card", batchChanges[1][1].asText())
    assertEquals("Tb_hero", batchChanges[2][1].asText())
    assertEquals("Tb_hero", batchChanges[3][1].asText())
    assertEquals(listOf(0, heroes[0].heroUid, 72, 101138), batchChanges[2][2].map { it.asInt() })

    channel.writeInbound(upPacket(Cmd.USE_TROOP_FACADE_CARD, "[0,${heroes[0].heroUid}]", userId = session.userId))
    assertEquals(Cmd.USE_TROOP_FACADE_CARD, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    assertEquals(
        listOf(0, heroes[0].heroUid, 72, 0),
        mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)[0][2].map { it.asInt() },
    )

    channel.writeInbound(upPacket(Cmd.UNLOCK_TROOP_FACADE_CARD, "[101138,${heroes[2].heroUid}]", userId = session.userId))
    assertEquals(Cmd.UNLOCK_TROOP_FACADE_CARD, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    val singleChanges = mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)
    assertEquals(listOf(0, 10113803, 5, heroes[2].heroId), singleChanges[0][2].map { it.asInt() })
    assertEquals(listOf(0, heroes[2].heroUid, 72, 101138), singleChanges[1][2].map { it.asInt() })

    val xiyuanUid = state.specialArmyFacadeCards().single { it.facadeId == 101515 }.heroUid
    channel.writeInbound(upPacket(Cmd.HERO_ACTIVE_FACADE, "[$xiyuanUid,2]", userId = session.userId))
    assertEquals(Cmd.HERO_ACTIVE_FACADE, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    assertEquals(
        listOf(0, xiyuanUid, 5, 2),
        mapper.readTree(assertIs<DownPacket>(channel.readOutbound<Any>()).body)[0][2].map { it.asInt() },
    )

    channel.writeInbound(
        upPacket(Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD, "[999999,[${heroes[3].heroUid}]]", userId = session.userId),
    )
    assertEquals(Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    assertNull(channel.readOutbound<Any>())
    channel.finishAndReleaseAll()
}

@Test
fun `switching a hero facade during an active march republishes its captured world scene`() {
    val channel = newChannel()
    platformLogin(channel, "army-facade-march-owner")
    val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
    val state = PlayerStateRepository.getOrCreate(
        accountKey = requireNotNull(session.accountKey),
        cityWid = GameServerConfig.CITY_WID,
        roleName = GameServerConfig.ROLE_NAME,
    )
    val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
    state.assignTeamHero(hero.heroUid, pos = 1)
    requireNotNull(state.bindArmyFacadeCards(101138, listOf(hero.heroUid)))
    state.startMarch(
        targetWid = GameServerConfig.CITY_WID + 1,
        nowSec = 1,
        participants = listOf(
            PlayerMarchHero(
                heroUid = hero.heroUid,
                position = 0,
                heroId = hero.heroId,
                troops = hero.troops,
                level = hero.level,
                skillIds = hero.normalizedSkillIds(),
                armyFacadeCardId = hero.armyFacadeCardId,
            ),
        ),
    )
    PlayerStateRepository.save(state)

    channel.writeInbound(upPacket(Cmd.USE_TROOP_FACADE_CARD, "[0,${hero.heroUid}]", userId = session.userId))

    assertEquals(Cmd.USE_TROOP_FACADE_CARD, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    val scene = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.SEND_WORLD_SCENCE_FULL_INFO, scene.cmd)
    assertEquals(
        "101138,0;",
        mapper.readTree(scene.body)[6][state.primaryArmyId().toString()][15].asText(),
    )
    channel.finishAndReleaseAll()
}
```

- [ ] **Step 5: 添加命令号和处理器**

Add constants:

```kotlin
const val USE_TROOP_FACADE_CARD = 677
const val UNLOCK_TROOP_FACADE_CARD = 678
const val BATCH_ACTIVE_ARMY_FACADE_CARD = 682
const val HERO_ACTIVE_FACADE = 2520
```

Add routing immediately after the other hero facade routes:

```kotlin
Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD,
Cmd.UNLOCK_TROOP_FACADE_CARD,
Cmd.USE_TROOP_FACADE_CARD,
Cmd.HERO_ACTIVE_FACADE -> {
    logIn(msg)
    sendArmyFacadeOperation(ctx, session, msg)
}
```

Implement the handler:

```kotlin
private fun sendArmyFacadeOperation(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
    val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
    val state = playerState(session, userId, GameServerConfig.CITY_WID)
    val mutation = when (msg.cmdId) {
        Cmd.BATCH_ACTIVE_ARMY_FACADE_CARD ->
            ArmyFacadeOperationRequestParser.parseBatch(msg.bodyText)
                ?.let { state.bindArmyFacadeCards(it.facadeId, it.heroUids) }
        Cmd.UNLOCK_TROOP_FACADE_CARD ->
            ArmyFacadeOperationRequestParser.parseSingle(msg.bodyText)
                ?.let { state.bindArmyFacadeCards(it.facadeId, it.heroUids) }
        Cmd.USE_TROOP_FACADE_CARD ->
            ArmyFacadeOperationRequestParser.parseUse(msg.bodyText)
                ?.let { state.useArmyFacade(it.heroUid, it.facadeId) }
        Cmd.HERO_ACTIVE_FACADE ->
            ArmyFacadeOperationRequestParser.parseSpecialState(msg.bodyText)
                ?.let { state.setSpecialArmyFacadeState(it.specialCardUid, it.state) }
        else -> null
    }

    ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
    if (mutation == null) return

    PlayerStateRepository.save(state)
    ctx.writeAndFlush(
        DownPacket.json(
            Cmd.SYS_NOTIFY_DB_UPDATE,
            GameResponses.armyFacadeNotify(state, mutation),
            dataType = DownType.PLAIN,
        ),
    )
    if (mutation.affectedArmyIds.any { armyId -> state.activeMarch(armyId) != null }) {
        sendWorldSceneFullInfo(ctx, session)
    }
}
```

Log `uid`, `cmd`, facade ID or special card UID, requested hero count, and whether a mutation
was produced. Do not send a custom error code.

- [ ] **Step 6: 运行解析和协议测试确认绿灯**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.ArmyFacadeOperationRequestParserTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: PASS，四个命令成功路径有正确顺序，拒绝路径没有 90005。

- [ ] **Step 7: 提交协议功能**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/ArmyFacadeOperationRequestParser.kt \
  src/test/kotlin/com/stzb/server/game/ArmyFacadeOperationRequestParserTest.kt \
  src/main/kotlin/com/stzb/server/protocol/Cmd.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git diff --cached --check
git commit -m "feat: handle army facade operations"
```

### Task 5: 回归验证、发行包和真实客户端验收

**Files:**
- Modify: `docs/superpowers/specs/2026-08-01-unlock-all-army-facades-design.md` only if implementation exposes a verified protocol difference from the approved design.

**Interfaces:**
- Consumes: Tasks 1-4 completed commits and the generated distribution under `build/install/stzb-server/`.
- Produces: Verified build artifact, SHA-256 evidence, and documented test result including unrelated baseline failures.

- [ ] **Step 1: 运行本功能的完整自动化测试集合**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.ArmyFacadeCatalogTest \
  --tests com.stzb.server.game.ArmyFacadeOperationRequestParserTest \
  --tests com.stzb.server.game.PlayerStatePersistenceTest \
  --tests com.stzb.server.game.PlayerBattleServiceTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: PASS. Record the exact count of passed tests.

- [ ] **Step 2: 构建发行包并校验**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process installDist
shasum -a 256 build/install/stzb-server/lib/stzb-server-0.1.0.jar
```

Expected: `BUILD SUCCESSFUL` and one SHA-256 line for the distribution JAR.

- [ ] **Step 3: 启动 59979 服务并确认监听**

Run:

```bash
existing_pid="$(lsof -tiTCP:59979 -sTCP:LISTEN)"
if [ -n "$existing_pid" ]; then kill "$existing_pid"; fi
nohup build/install/stzb-server/bin/stzb-server \
  > /private/tmp/stzb-server-army-facade.log 2>&1 &
sleep 2
lsof -nP -iTCP:59979 -sTCP:LISTEN
tail -n 80 /private/tmp/stzb-server-army-facade.log
```

Expected: 一个 Java 进程监听 `*:59979` 或 `127.0.0.1:59979`，日志没有 Kotlin、
JSON 或客户端配置表解析异常。

- [ ] **Step 4: 执行真实客户端验收清单**

1. 使用新账号登录，确认外观库有 60 张普通卡和 4 张特殊卡。
2. 对 `101138` 一次选择两个五星武将，确认库存剩余 3 张并且两张武将卡均显示该外观。
3. 使用武将详情页将其中一个切换为默认，再切回已绑定外观。
4. 使用 `678` 为第三名五星武将绑定同一外观，确认剩余 2 张。
5. 启用西园军并出征，确认地图行军外观不再使用默认模型。
6. 退出重登，确认普通卡绑定、当前外观和特殊卡状态仍存在。
7. 选择禁军；在客户端当前地图模式可渲染时确认显示，否则记录客户端模式限制而不是修改客户端。

- [ ] **Step 5: 运行全量测试并分类报告**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test
```

Expected: 若全绿，记录成功。若存在已知无关基线失败，记录测试类、失败断言和其与
行军外观改动无关的原因；不得通过删除或跳过该测试掩盖失败。

- [ ] **Step 6: 提交最终文档修订（仅在协议事实变化时）**

```bash
git status --short
git diff -- docs/superpowers/specs/2026-08-01-unlock-all-army-facades-design.md
```

Only when the approved specification needs an evidence-backed correction:

```bash
git add docs/superpowers/specs/2026-08-01-unlock-all-army-facades-design.md
git diff --cached --check
git commit -m "docs: record verified army facade protocol"
```

If no specification correction is needed, do not create a documentation-only commit.
