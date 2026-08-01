# 战报武将卡框与动态画像 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅通过 Kotlin 服务端，让新生成的 PVE 战报显示出征时冻结的御龙卡框和动态画像，并允许服务端持久化卡框选择。

**Architecture:** `PlayerHero` 保存当前卡框，`PlayerMarchHero` 在出征时复制卡框与动态画像。`ClientBattleReport` 另存展示快照，战报摘要直接序列化这份不可变数据，不把视觉字段放进数值战斗模型。登录快照下发 `Tb_hero.card_border`、普通蟠龙持有记录和已完成的英雄成就记录。

**Tech Stack:** Kotlin 1.9.23、Netty、Jackson、`kotlin.test`、Gradle。

## Global Constraints

- 只修改 `server/` 内 Kotlin 服务端与测试。
- 不修改客户端资源、客户端 DLL、热更新包或客户端逻辑。
- 不注入客户端，不通过 ADB 推送或部署客户端文件。
- 不回填已生成的历史战报；没有已存外观快照的战报继续输出全零 surface。
- NPC 守军的卡框、动态画像、特性 ID 均为 `0`。
- 新建武将与缺失该字段的旧账号存档默认使用成就御龙卡框 `101260`。
- 客户端支持的卡框类型为御龙 `101260` 与蟠龙 `110997`；服务端同时识别蟠龙变体 `110998`、`110999`。
- 所有 Kotlin 验证使用 `-Dkotlin.compiler.execution.strategy=in-process`，规避 macOS Kotlin daemon 权限问题。

---

## File Structure

| 文件 | 职责 |
| --- | --- |
| `src/main/kotlin/com/stzb/server/game/CardBorderCatalog.kt` | 卡框 ID、默认值、可选集合与旧存档归一化。 |
| `src/main/kotlin/com/stzb/server/game/PlayerState.kt` | 持久化每张武将卡的卡框；出征时保存外观快照。 |
| `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt` | 登录快照写入 `Tb_hero[42]`、普通蟠龙持有记录和 `Tb_hero_achieve`。 |
| `src/main/kotlin/com/stzb/server/game/GameResponses.kt` | `Tb_hero` 完整上行行写入卡框，以及 `90005` 稀疏卡框更新。 |
| `src/main/kotlin/com/stzb/server/protocol/Cmd.kt` | 卡框协议命令常量。 |
| `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt` | `673`/`675`/`1673`/`1674` 的服务端处理。 |
| `src/main/kotlin/com/stzb/server/game/battle/ClientBattleReportStore.kt` | 独立的战报展示快照和客户端 surface 文本序列化。 |
| `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt` | 从玩家与行军状态向战报传递冻结的攻击方外观。 |
| `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt` | 卡框默认值、选择和持久化回归。 |
| `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt` | 登录快照中的卡框与解锁表回归。 |
| `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt` | `Tb_hero[42]` 与 `90005` 稀疏更新格式回归。 |
| `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt` | 卡框选择和只确认命令的协议回归。 |
| `src/test/kotlin/com/stzb/server/game/battle/ClientBattleReportStoreTest.kt` | surface 固定行数、正确值和 NPC 零值回归。 |
| `src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt` | 出征后更换外观不影响已生成战报的回归。 |

## Task 1: 持久化卡框状态与 DB 更新

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/CardBorderCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt:22-116,265-270,391-440,530-555`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt:497-507,770-812`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

**Interfaces:**
- Produces `CardBorderCatalog.DEFAULT_ID: Int` (`101260`), `CardBorderCatalog.isSupported(cardBorder: Int): Boolean`, and `CardBorderCatalog.normalizePersisted(cardBorder: Int): Int`.
- Adds `PlayerHero.cardBorder: Int`.
- Adds `PlayerState.selectHeroCardBorder(heroUid: Int, cardBorder: Int): Boolean`.
- Adds `GameResponses.heroCardBorderUpdateNotify(heroUid: Int, cardBorder: Int): String`.
- Extends all `Tb_hero` row encoders so field `42` is `hero.cardBorder` and field `43` stays `hero.dynamicIcon`.

- [ ] **Step 1: Write the failing state and response tests**

Add these tests to `PlayerStatePersistenceTest.kt`:

```kotlin
@Test
fun `card border defaults to yulong persists and accepts only supported borders`() {
    val state = PlayerState(userId = 57, cityWid = 10057, roleName = "主公")
    val hero = state.addHero(100017)

    assertEquals(CardBorderCatalog.DEFAULT_ID, hero.cardBorder)
    assertTrue(state.selectHeroCardBorder(hero.heroUid, 110997))
    assertFalse(state.selectHeroCardBorder(hero.heroUid, 777777))

    val restored = PlayerState.fromSnapshot(state.toSnapshot())
    assertEquals(110997, restored.hero(hero.heroUid)?.cardBorder)
}

@Test
fun `legacy hero snapshot gains the yulong default card border`() {
    val restored = PlayerState.fromSnapshot(
        PlayerStateSnapshot(
            accountKey = "legacy-card-border",
            userId = 58,
            cityWid = 10058,
            roleName = "主公",
            heroes = listOf(
                PlayerHeroSnapshot(
                    heroUid = 58_000_001,
                    heroId = 100017,
                    createdAtSec = 1_700_000_000,
                ),
            ),
        ),
    )

    assertEquals(CardBorderCatalog.DEFAULT_ID, restored.hero(58_000_001)?.cardBorder)
}
```

Add these tests to `GameResponsesTest.kt`:

```kotlin
@Test
fun `hero upsert contains selected card border and dynamic icon`() {
    val hero = PlayerHero(
        heroUid = 4_200_004,
        heroId = 100067,
        createdAtSec = 1_700_000_000,
        cardBorder = 110997,
        dynamicIcon = 100534,
    )

    val row = mapper.readTree(
        GameResponses.heroUpsertNotify(userId = 42, heroes = listOf(hero)),
    )[0][2]

    assertEquals(110997, row[42].asInt())
    assertEquals(100534, row[43].asInt())
}

@Test
fun `card border update uses sparse hero field forty two`() {
    val update = mapper.readTree(
        GameResponses.heroCardBorderUpdateNotify(
            heroUid = 4_200_004,
            cardBorder = 110997,
        ),
    )

    assertEquals(1, update.size())
    assertEquals(2, update[0][0].asInt())
    assertEquals("Tb_hero", update[0][1].asText())
    assertEquals(
        listOf(0, 4_200_004, 42, 110997),
        update[0][2].map { it.asInt() },
    )
}
```

- [ ] **Step 2: Run the focused tests and verify the expected RED state**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.PlayerStatePersistenceTest \
  --tests com.stzb.server.game.GameResponsesTest
```

Expected: compilation fails because `CardBorderCatalog`, `PlayerHero.cardBorder`,
`PlayerState.selectHeroCardBorder`, and `GameResponses.heroCardBorderUpdateNotify`
do not exist.

- [ ] **Step 3: Add the single source of truth for allowed card borders**

Create `src/main/kotlin/com/stzb/server/game/CardBorderCatalog.kt`:

```kotlin
package com.stzb.server.game

object CardBorderCatalog {
    const val DEFAULT_ID = 101260
    const val PANLONG_ID = 110997
    const val PANLONG_ATTACK_ID = 110998
    const val PANLONG_STRATEGY_ID = 110999

    private val supportedIds = setOf(
        0,
        DEFAULT_ID,
        PANLONG_ID,
        PANLONG_ATTACK_ID,
        PANLONG_STRATEGY_ID,
    )

    fun isSupported(cardBorder: Int): Boolean = cardBorder in supportedIds

    fun normalizePersisted(cardBorder: Int): Int =
        cardBorder.takeIf(::isSupported) ?: DEFAULT_ID
}
```

- [ ] **Step 4: Add state fields, snapshot persistence and selection validation**

In `PlayerState.kt`, make these exact additions:

```kotlin
class PlayerHero(
    // Existing parameters unchanged.
    var dynamicIcon: Int = 0,
    var cardBorder: Int = CardBorderCatalog.DEFAULT_ID,
    var awakeState: Int = 1,
    // Existing parameters unchanged.
)
```

```kotlin
data class PlayerHeroSnapshot(
    // Existing parameters unchanged.
    val dynamicIcon: Int = 0,
    val cardBorder: Int = CardBorderCatalog.DEFAULT_ID,
    val awakeState: Int = 1,
    // Existing parameters unchanged.
)
```

Place the following method immediately after `selectHeroFacade`:

```kotlin
fun selectHeroCardBorder(heroUid: Int, cardBorder: Int): Boolean {
    val hero = hero(heroUid) ?: return false
    if (!CardBorderCatalog.isSupported(cardBorder)) return false
    hero.cardBorder = cardBorder
    return true
}
```

Copy `hero.cardBorder` into `PlayerHeroSnapshot` in `toSnapshot()`. In
`fromSnapshot()`, construct the hero with:

```kotlin
cardBorder = CardBorderCatalog.normalizePersisted(saved.cardBorder),
```

Do not special-case `0`; it is the valid persisted representation for an
explicitly unequipped card frame. Old JSON omitted the property and receives
the `PlayerHeroSnapshot` default `101260`.

- [ ] **Step 5: Encode the card border in complete and sparse `Tb_hero` updates**

In `GameResponses.tbHero`, replace:

```kotlin
repeat(5) { add(0) } // 38..42 feature/card-border fields
add(hero.dynamicIcon) // 43 dynamic_icon
```

with:

```kotlin
repeat(4) { add(0) } // 38..41 feature fields
add(hero.cardBorder) // 42 card_border
add(hero.dynamicIcon) // 43 dynamic_icon
```

Add this public method next to `heroSkillUpdateNotify`:

```kotlin
fun heroCardBorderUpdateNotify(heroUid: Int, cardBorder: Int): String =
    mapper.writeValueAsString(
        nf.arrayNode().add(
            nf.arrayNode()
                .add(2)
                .add("Tb_hero")
                .add(nf.arrayNode().add(0).add(heroUid).add(42).add(cardBorder)),
        ),
    )
```

The existing `heroUpsertNotify` continues to use `tbHero`, so no separate
insert/update path is needed.

- [ ] **Step 6: Run the focused tests and verify GREEN**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.PlayerStatePersistenceTest \
  --tests com.stzb.server.game.GameResponsesTest
```

Expected: both selected test classes pass.

- [ ] **Step 7: Commit the self-contained state and notification change**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/CardBorderCatalog.kt \
  src/main/kotlin/com/stzb/server/game/PlayerState.kt \
  src/main/kotlin/com/stzb/server/game/GameResponses.kt \
  src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt \
  src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt
git commit -m "feat: persist selected hero card borders"
```

## Task 2: 登录快照解锁御龙与蟠龙卡框

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:129-144,492-526,555-575`
- Test: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt:231-252`

**Interfaces:**
- `Tb_hero` field `42` is the persisted `PlayerHero.cardBorder`.
- The login snapshot has a `Tb_hero_achieve` table with one `finish_reward = 2`
  row per distinct non-material hero configuration.
- The login snapshot has one permanent `Tb_user_facade_card` border record for
  every distinct non-material player hero configuration and every ID in
  `CardBorderCatalog.normalBorderIds()`.

- [ ] **Step 1: Replace the obsolete facade-only test with a failing unlock test**

Replace the existing test named
`snapshot owns every facade without fabricating hero achievements` with:

```kotlin
@Test
fun `snapshot equips yulong and unlocks every supported hero card border`() {
    val root = createTempDirectory("stzb-card-border-snapshot")
    try {
        PlayerStateRepository.configure(FilePlayerRepository(root))
        val state = PlayerStateRepository.getOrCreate(
            userId = 44,
            cityWid = 10044,
            roleName = "主公",
        )
        val hero = state.addHero(100017, nowSec = 1_700_000_000)
        PlayerStateRepository.save(state)

        val snapshot = UserInitTableBuilder.build(
            userId = 44,
            cityWid = 10044,
            roleName = "主公",
            serverOpenTime = 1_700_000_000L,
        )
        val tables = snapshot.drop(1).associateBy { it[0].asText() }
        val heroRow = tables.getValue("Tb_hero")[1]
            .single { it[0].asInt() == hero.heroUid }
        val achievements = tables.getValue("Tb_hero_achieve")[1]
        val facades = tables.getValue("Tb_user_facade_card")[1]

        assertEquals(CardBorderCatalog.DEFAULT_ID, heroRow[42].asInt())
        assertTrue(
            achievements.any {
                it[2].asInt() == hero.heroId && it[5].asInt() == 2
            },
        )
        CardBorderCatalog.normalBorderIds().forEach { borderId ->
            assertTrue(
                facades.any {
                    it[2].asInt() == hero.heroId && it[3].asInt() == borderId
                },
            )
        }
    } finally {
        PlayerStateRepository.reset()
    }
}
```

- [ ] **Step 2: Run the snapshot test and verify RED**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.UserInitTableBuilderTest
```

Expected: the test does not compile because `normalBorderIds` does not exist,
then fails because `Tb_hero[42]` is `0` and `Tb_hero_achieve` is absent.

- [ ] **Step 3: Expose the normal-frame list and build stable login rows**

Add this method to `CardBorderCatalog`:

```kotlin
fun normalBorderIds(): List<Int> =
    listOf(PANLONG_ID, PANLONG_ATTACK_ID, PANLONG_STRATEGY_ID)
```

In `UserInitTableBuilder.build`, calculate stable non-material representatives:

```kotlin
val playableHeroes = state.allHeroes()
    .filterNot(PlayerHero::isAdvanceMaterial)
    .distinctBy(PlayerHero::heroId)
```

Immediately after the existing `Tb_hero` table, add:

```kotlin
root.add(
    table(
        "Tb_hero_achieve",
        *playableHeroes.map(::tbHeroAchieve).toTypedArray(),
    ),
)
```

When creating `Tb_user_facade_card`, append the border ownership rows after
the existing `HeroFacadeCatalog.all()` rows:

```kotlin
val normalFacades = HeroFacadeCatalog.all().mapIndexed { index, facade ->
    tbUserFacadeCard(playerId, index, facade)
}
val borderFacades = playableHeroes.flatMapIndexed { heroIndex, hero ->
    CardBorderCatalog.normalBorderIds().mapIndexed { borderIndex, borderId ->
        tbUserCardBorderFacade(
            userId = playerId,
            rowIndex = normalFacades.size + heroIndex * CardBorderCatalog.normalBorderIds().size + borderIndex,
            baseHeroId = hero.heroId,
            borderId = borderId,
        )
    }
}
root.add(table("Tb_user_facade_card", *(normalFacades + borderFacades).toTypedArray()))
```

Replace the prior standalone `Tb_user_facade_card` `root.add` block with the
combined block above so the table name occurs exactly once.

- [ ] **Step 4: Implement the two login-table row builders**

In `UserInitTableBuilder.kt`, extend `tbHero` by adding this field before the
existing dynamic icon field:

```kotlin
.i(42, hero.cardBorder)         // card_border
.i(43, hero.dynamicIcon)        // dynamic_icon
```

Replace the current terminal `.i(43, hero.dynamicIcon)` line rather than
adding a second field `43`.

Add the following helpers beside `tbUserFacadeCard`:

```kotlin
private fun tbUserCardBorderFacade(
    userId: Int,
    rowIndex: Int,
    baseHeroId: Int,
    borderId: Int,
): ArrayNode =
    row("Tb_user_facade_card")
        .i(0, userId * 10_000 + rowIndex + 1)
        .i(1, userId)
        .i(2, baseHeroId)
        .i(3, borderId)
        .i(4, 0)
        .i(5, 0)
        .i(6, 0)
        .i(7, 0)
        .s(8, "").s(9, "")
        .i(10, 0)
        .s(11, "").s(12, "")
        .i(13, 1)
        .arr

private fun tbHeroAchieve(hero: PlayerHero): ArrayNode =
    row("Tb_hero_achieve")
        .i(0, hero.heroUid)
        .i(1, hero.heroUid / 1_000)
        .i(2, hero.heroId)
        .s(3, "")
        .s(4, "")
        .i(5, 2)
        .i(6, hero.createdAtSec)
        .i(7, 0)
        .s(8, "")
        .i(9, 0)
        .s(10, "")
        .s(11, "")
        .s(12, "")
        .s(13, "")
        .s(14, "")
        .arr
```

For `tbHeroAchieve`, change the second argument to an explicit `userId`
parameter instead of deriving it from `heroUid`; use that `userId` in field
`1`. The final signature must be:

```kotlin
private fun tbHeroAchieve(userId: Int, hero: PlayerHero): ArrayNode
```

Call it as `playableHeroes.map { hero -> tbHeroAchieve(playerId, hero) }`.
This prevents a future hero UID stride change from corrupting the table's
`userid` column.

- [ ] **Step 5: Run the snapshot test and verify GREEN**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.UserInitTableBuilderTest
```

Expected: `UserInitTableBuilderTest` passes, including the new table and
frame assertions.

- [ ] **Step 6: Commit the login unlock data**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/CardBorderCatalog.kt \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt
git commit -m "feat: unlock hero card borders in login snapshot"
```

## Task 3: 服务端卡框命令与 90005 同步

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt:45-54`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt:225-238,727-754`
- Test: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- Adds `Cmd.HERO_USE_CARD_BORDER = 673`.
- Adds `Cmd.HERO_ACTIVE_CARD_BORDER = 675`.
- Adds `Cmd.ROTATE_CARD_BORDER_ADD = 1673` and
  `Cmd.ROTATE_CARD_BORDER_REMOVE = 1674`.
- `675` accepts `[heroUid, cardBorder]`, responds `[]`, persists a supported
  value, and then sends `90005` with `[2,"Tb_hero",[0,heroUid,42,cardBorder]]`.
- `673`, `1673`, and `1674` respond `[]` and do not mutate card-frame state.

- [ ] **Step 1: Write the failing handler protocol tests**

Add this test to `GameServerHandlerProtocolTest.kt`:

```kotlin
@Test
fun `active card border persists and sends sparse hero update`() {
    val channel = newChannel()
    platformLogin(channel, "alice")
    val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
    val state = PlayerStateRepository.getOrCreate(
        accountKey = requireNotNull(session.accountKey),
        cityWid = GameServerConfig.CITY_WID,
        roleName = GameServerConfig.ROLE_NAME,
    )
    val hero = state.addHero(100017, nowSec = 1_700_000_000)
    PlayerStateRepository.save(state)

    channel.writeInbound(
        upPacket(
            Cmd.HERO_ACTIVE_CARD_BORDER,
            """[${hero.heroUid},110997]""",
            userId = session.userId,
        ),
    )

    val response = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.HERO_ACTIVE_CARD_BORDER, response.cmd)
    assertEquals("[]", response.body.toString(Charsets.UTF_8))
    val notify = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.SYS_NOTIFY_DB_UPDATE, notify.cmd)
    assertEquals(
        listOf(0, hero.heroUid, 42, 110997),
        mapper.readTree(notify.body)[0][2].map { it.asInt() },
    )
    assertEquals(110997, state.hero(hero.heroUid)?.cardBorder)
    channel.finishAndReleaseAll()
}

@Test
fun `invalid card border and rotate requests acknowledge without mutation`() {
    val channel = newChannel()
    platformLogin(channel, "alice")
    val session = channel.attr(GameServerHandler.SESSION).get() ?: error("missing session")
    val state = PlayerStateRepository.getOrCreate(
        accountKey = requireNotNull(session.accountKey),
        cityWid = GameServerConfig.CITY_WID,
        roleName = GameServerConfig.ROLE_NAME,
    )
    val hero = state.addHero(100017)

    channel.writeInbound(
        upPacket(Cmd.HERO_ACTIVE_CARD_BORDER, """[${hero.heroUid},777777]""", session.userId),
    )
    val invalid = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.HERO_ACTIVE_CARD_BORDER, invalid.cmd)
    assertEquals(CardBorderCatalog.DEFAULT_ID, hero.cardBorder)
    assertNull(channel.readOutbound<Any>())

    listOf(Cmd.HERO_USE_CARD_BORDER, Cmd.ROTATE_CARD_BORDER_ADD, Cmd.ROTATE_CARD_BORDER_REMOVE)
        .forEach { cmd ->
            channel.writeInbound(upPacket(cmd, """[${hero.heroUid},110997]""", session.userId))
            val response = assertIs<DownPacket>(channel.readOutbound<Any>())
            assertEquals(cmd, response.cmd)
            assertEquals("[]", response.body.toString(Charsets.UTF_8))
        }
    assertEquals(CardBorderCatalog.DEFAULT_ID, hero.cardBorder)
    channel.finishAndReleaseAll()
}
```

- [ ] **Step 2: Run the handler test and verify RED**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: compilation fails because the card-border command constants are
undefined.

- [ ] **Step 3: Define the protocol constants and route the commands**

In `Cmd.kt`, add:

```kotlin
const val HERO_USE_CARD_BORDER = 673
const val HERO_ACTIVE_CARD_BORDER = 675
const val ROTATE_CARD_BORDER_ADD = 1673
const val ROTATE_CARD_BORDER_REMOVE = 1674
```

In `GameServerHandler.channelRead0`, add the explicit branches immediately
after `HERO_SELECT_FACADE`:

```kotlin
Cmd.HERO_USE_CARD_BORDER,
Cmd.ROTATE_CARD_BORDER_ADD,
Cmd.ROTATE_CARD_BORDER_REMOVE -> {
    logIn(msg)
    sendNoOpSuccess(ctx, msg)
}

Cmd.HERO_ACTIVE_CARD_BORDER -> {
    logIn(msg)
    sendSelectHeroCardBorder(ctx, session, msg)
}
```

Do not combine `675` with `sendNoOpSuccess`; it is the only frame command
that must mutate and persist state.

- [ ] **Step 4: Implement the `675` state transition**

Add this method immediately after `sendSelectHeroFacade`:

```kotlin
private fun sendSelectHeroCardBorder(ctx: ChannelHandlerContext, session: Session?, msg: UpPacket) {
    val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
    val body = runCatching { mapper.readTree(msg.body) }.getOrNull()
    val heroUid = body?.get(0)?.asInt() ?: 0
    val cardBorder = body?.get(1)?.asInt() ?: 0
    val state = playerState(session, userId, GameServerConfig.CITY_WID)
    val changed = state.selectHeroCardBorder(heroUid, cardBorder)

    ctx.writeAndFlush(
        DownPacket.json(
            Cmd.HERO_ACTIVE_CARD_BORDER,
            GameResponses.emptyArray(),
            dataType = DownType.PLAIN,
        ),
    )
    if (changed) {
        PlayerStateRepository.save(state)
        ctx.writeAndFlush(
            DownPacket.json(
                Cmd.SYS_NOTIFY_DB_UPDATE,
                GameResponses.heroCardBorderUpdateNotify(heroUid, cardBorder),
                dataType = DownType.PLAIN,
            ),
        )
    }
    log.info(
        ">> cmd=675 武将卡框切换 " +
            "(uid=$userId, heroUid=$heroUid, cardBorder=$cardBorder, changed=$changed)",
    )
}
```

The handler deliberately sends a normal success shape for invalid input but
does not send `90005`, matching the existing facade-selection behavior.

- [ ] **Step 5: Run the handler test and verify GREEN**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: all handler protocol tests pass.

- [ ] **Step 6: Commit the protocol support**

```bash
git add \
  src/main/kotlin/com/stzb/server/protocol/Cmd.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git commit -m "feat: handle server-side hero card border commands"
```

## Task 4: 冻结战报展示快照并序列化客户端 surface

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt:75-91`
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt:50-65,75-105,117-122`
- Modify: `src/main/kotlin/com/stzb/server/game/battle/ClientBattleReportStore.kt:12-18,50-63,115-198,229-269`
- Test: `src/test/kotlin/com/stzb/server/game/battle/ClientBattleReportStoreTest.kt`
- Test: `src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt`

**Interfaces:**
- Adds `BattleHeroSurface(heroId, position, cardBorder, dynamicIcon, activeFeatureId = 0)`.
- `ClientBattleReport` has `attackerSurfaces` and `defenderSurfaces`, both
  defaulting to `emptyList()` to preserve historical in-memory reports.
- `ClientBattleReportStore.record` accepts optional attack and defense surface
  lists, preserving existing callers.
- `PlayerMarchHero` stores `cardBorder`, `dynamicIcon`, and
  `activeFeatureId`; all default to `0` so persisted marches created before
  this feature continue to produce a historical zero surface.

- [ ] **Step 1: Write the failing report-store serialization test**

Add this test to `ClientBattleReportStoreTest.kt`:

```kotlin
@Test
fun `profile encodes frozen hero card border and dynamic icon surfaces`() {
    val store = ClientBattleReportStore.createDefault(nowSec = 1_700_000_000)
    val base = store.getOrCreateDefault()
    val report = store.record(
        wid = 10001,
        timeSec = 1_700_000_001,
        result = base.result,
        attackerSurfaces = listOf(
            BattleHeroSurface(
                heroId = 100017,
                position = 0,
                cardBorder = 101260,
                dynamicIcon = 100534,
            ),
            BattleHeroSurface(
                heroId = 100023,
                position = 1,
                cardBorder = 110997,
                dynamicIcon = 0,
            ),
        ),
    )

    val profile = mapper.readTree(
        store.profileResponse(listOf(report.battleId), serverId = 0),
    )[1][0]

    assertEquals(
        "100017,100534;100023,0;0,0",
        profile["attack_all_surface"].asText(),
    )
    assertEquals(
        "0,0,0;101260,100534,0;110997,0,0;0,0,0",
        profile["attacker_surface"].asText(),
    )
    assertEquals("0,0;0,0;0,0", profile["defend_all_surface"].asText())
    assertEquals("0,0,0;0,0,0;0,0,0;0,0,0", profile["defender_surface"].asText())
}
```

- [ ] **Step 2: Write the failing end-to-end freeze test**

Add this test to `PlayerBattleServiceTest.kt`:

```kotlin
@Test
fun `settled pve report keeps the card border and dynamic icon from departure`() {
    val state = PlayerState(userId = 913, cityWid = 1913, roleName = "主公")
    val hero = state.addHero(100017).apply {
        dynamicIcon = 100534
        cardBorder = 101260
    }
    state.saveTeam(listOf(hero.heroUid))
    val store = ClientBattleReportStore.createEmpty()
    val service = PlayerBattleService(store, defenderFactory = defendersOn2001())

    service.launchPveBattle(state, targetWid = 10_011, nowSec = 1_700_000_010)
        ?: error("expedition should start")
    hero.cardBorder = 110997
    hero.dynamicIcon = 0

    val settlement = service.settlePveBattle(state, nowSec = 1_700_000_013)
        ?: error("arrival should resolve battle")
    val profile = mapper.readTree(
        store.profileResponse(state.userId, listOf(settlement.battleId), serverId = 0),
    )[1][0]

    assertEquals("100017,100534;0,0;0,0", profile["attack_all_surface"].asText())
    assertEquals(
        "0,0,0;101260,100534,0;0,0,0;0,0,0",
        profile["attacker_surface"].asText(),
    )
    assertEquals("0,0;0,0;0,0", profile["defend_all_surface"].asText())
    assertEquals("0,0,0;0,0,0;0,0,0;0,0,0", profile["defender_surface"].asText())
}
```

- [ ] **Step 3: Run the battle tests and verify RED**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.battle.ClientBattleReportStoreTest \
  --tests com.stzb.server.game.PlayerBattleServiceTest
```

Expected: compilation fails because `BattleHeroSurface` and the new
`record(..., attackerSurfaces = ...)` parameter do not exist.

- [ ] **Step 4: Add presentation-only report state**

At the top of `ClientBattleReportStore.kt`, before `ClientBattleReport`, add:

```kotlin
data class BattleHeroSurface(
    val heroId: Int,
    val position: Int,
    val cardBorder: Int,
    val dynamicIcon: Int,
    val activeFeatureId: Int = 0,
)
```

Extend `ClientBattleReport`:

```kotlin
data class ClientBattleReport(
    val battleId: Int,
    val ownerUserId: Int,
    val wid: Int,
    val timeSec: Int,
    val result: BattleResult,
    val attackerSurfaces: List<BattleHeroSurface> = emptyList(),
    val defenderSurfaces: List<BattleHeroSurface> = emptyList(),
)
```

Extend both `record` overloads:

```kotlin
fun record(
    ownerUserId: Int,
    wid: Int,
    timeSec: Int,
    result: BattleResult,
    attackerSurfaces: List<BattleHeroSurface> = emptyList(),
    defenderSurfaces: List<BattleHeroSurface> = emptyList(),
): ClientBattleReport
```

Pass these lists into the constructed report. Keep the internal three-argument
overload and delegate to the public method without surface arguments so all
existing tests keep their historical zero surface behavior.

- [ ] **Step 5: Replace zero-only surface serialization**

In `ClientBattleReport.toProfileNode`, replace:

```kotlin
put("attack_all_surface", attacker.toHeroSurfaceInfo())
put("defend_all_surface", defender.toHeroSurfaceInfo())
// ...
put("attacker_surface", emptyFourRows(3))
put("defender_surface", emptyFourRows(3))
```

with:

```kotlin
put("attack_all_surface", attackerSurfaces.toHeroSurfaceInfo())
put("defend_all_surface", defenderSurfaces.toHeroSurfaceInfo())
// ...
put("attacker_surface", attackerSurfaces.toBattleSurfaceInfo())
put("defender_surface", defenderSurfaces.toBattleSurfaceInfo())
```

Replace the old `List<BattleHero>.toHeroSurfaceInfo` with:

```kotlin
private fun List<BattleHeroSurface>.toHeroSurfaceInfo(): String =
    (0..2).joinToString(";") { position ->
        val surface = firstOrNull { it.position == position }
        "${surface?.heroId ?: 0},${surface?.dynamicIcon ?: 0}"
    }

private fun List<BattleHeroSurface>.toBattleSurfaceInfo(): String =
    listOf("0,0,0") + (0..2).map { position ->
        val surface = firstOrNull { it.position == position }
        "${surface?.cardBorder ?: 0},${surface?.dynamicIcon ?: 0},${surface?.activeFeatureId ?: 0}"
    }.joinToString(";")
```

The base row must remain `0,0,0`; PVE has no separate base-hero surface.
Keep `createDefaultReport` untouched, so its old synthetic report retains
zero-only visual state.

- [ ] **Step 6: Freeze player appearance at departure**

In `PlayerState.kt`, extend `PlayerMarchHero`:

```kotlin
data class PlayerMarchHero(
    val heroUid: Int,
    val position: Int,
    val heroId: Int,
    val troops: Int,
    val level: Int,
    val skillIds: List<Int>,
    val cardBorder: Int = 0,
    val dynamicIcon: Int = 0,
    val activeFeatureId: Int = 0,
)
```

Defaults remain zero specifically for old serialized marches.

In both `PlayerMarchHero(...)` construction paths in `PlayerBattleService`,
copy:

```kotlin
cardBorder = hero.cardBorder,
dynamicIcon = hero.dynamicIcon,
activeFeatureId = 0,
```

Add this import:

```kotlin
import com.stzb.server.game.battle.BattleHeroSurface
```

At the beginning of `settlePveBattle`, after `participants` is non-empty,
create:

```kotlin
val attackerSurfaces = participants.map { participant ->
    BattleHeroSurface(
        heroId = participant.heroId,
        position = participant.position,
        cardBorder = participant.cardBorder,
        dynamicIcon = participant.dynamicIcon,
        activeFeatureId = participant.activeFeatureId,
    )
}
```

Pass the list to every generated PVE report:

```kotlin
report = reportStore.record(
    ownerUserId = state.userId,
    wid = march.targetWid,
    timeSec = nowSec,
    result = result,
    attackerSurfaces = attackerSurfaces,
)
```

Do not pass defender surfaces: the default empty list intentionally produces
zero-valued NPC defender rows.

- [ ] **Step 7: Run the battle tests and verify GREEN**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.battle.ClientBattleReportStoreTest \
  --tests com.stzb.server.game.PlayerBattleServiceTest
```

Expected: the report-store test validates exact three-row and four-row strings;
the service test validates that changing the current hero appearance after
departure cannot change the finished report.

- [ ] **Step 8: Commit the frozen report-appearance feature**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/PlayerState.kt \
  src/main/kotlin/com/stzb/server/game/PlayerBattleService.kt \
  src/main/kotlin/com/stzb/server/game/battle/ClientBattleReportStore.kt \
  src/test/kotlin/com/stzb/server/game/PlayerBattleServiceTest.kt \
  src/test/kotlin/com/stzb/server/game/battle/ClientBattleReportStoreTest.kt
git commit -m "feat: preserve hero appearances in new battle reports"
```

## Task 5: 全量回归与交付核验

**Files:**
- Verify only: all files changed in Tasks 1-4.

**Interfaces:**
- Verifies the complete server-only implementation: card-frame persistence,
  login snapshots, protocol handling, and battle-report surface serialization.

- [ ] **Step 1: Inspect the final diff for forbidden client changes**

Run:

```bash
git diff --name-only HEAD~4..HEAD
```

Expected: all listed paths are under `src/` or `docs/`; no APK,
DLL, client asset, hot-update, or ADB script path appears.

- [ ] **Step 2: Run the affected regression suite**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.PlayerStatePersistenceTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest \
  --tests com.stzb.server.game.PlayerBattleServiceTest \
  --tests com.stzb.server.game.battle.ClientBattleReportStoreTest
```

Expected: Gradle exits `0`; all six selected classes pass.

- [ ] **Step 3: Build the distributable server**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process installDist
```

Expected: Gradle exits `0` and produces
`build/install/stzb-server/bin/stzb-server`.

- [ ] **Step 4: Record any unrelated existing failures accurately**

Run:

```bash
STZB_DATA_DIR=/tmp/stzb-card-border-full-test-data \
./gradlew -Dkotlin.compiler.execution.strategy=in-process test
```

Expected: report the exact final test count and every failing test. Do not
attribute known unrelated failures to this feature unless the failure stack
references a file changed in Tasks 1-4.

- [ ] **Step 5: Confirm no feature work remains uncommitted**

Run:

```bash
git status --short
```

Expected: no uncommitted files created by Tasks 1-4. Do not stage, modify, or
commit unrelated worktree changes.
