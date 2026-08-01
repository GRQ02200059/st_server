# 主城与行军外观恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅通过 Kotlin 服务端让主城布局和所有已上阵武将的行军外观在客户端立即显示并跨重登、重启保持。

**Architecture:** `PlayerState` 是普通行军外观绑定的持久化来源，登录快照从同一状态生成武将和部队外观字段。`WorldCity.customView` 是主城建筑布局的唯一持久化来源；`3945` 验证并保存布局后，通过既有世界广播重发所有在线会话的 `5026`。

**Tech Stack:** Kotlin 1.9.23、JDK 17、Gradle 8.7、Netty EmbeddedChannel、Jackson、kotlin.test。

## Global Constraints

- 只修改服务端 Kotlin、测试和规格文档；禁止修改客户端文件、DLL、内存或网络代理。
- 普通行军外观只能使用 `ArmyFacadeCatalog` 的客户端配置目录；每种普通外观仅有 5 张永久卡。
- 首次默认绑定覆盖五支部队中全部已上阵、五星、非素材武将；按目录顺序分配，前 5 张为 `101138`，卡片耗尽后使用下一种真实外观。
- 已存在任何普通卡绑定时，不得覆盖玩家已绑定、切换或取消后的选择。
- 主城布局只接受当前账号主城坐标与 `FacadeCatalog.cityFacadeIds` 中的建筑外观。
- 有效 `3945` 必须先回复 `3945 []`，再广播 `5026`；无效、其它主城或无变化请求只回复 `3945 []`。
- 运行服务端端口保持 `59979`；本任务不启动或终止用户的服务进程。
- Kotlin 编译和测试统一使用 `-Pkotlin.compiler.execution.strategy=in-process --no-daemon`。

---

## 文件结构

| 文件 | 责任 |
| --- | --- |
| `src/main/kotlin/com/stzb/server/game/PlayerState.kt` | 首次登录的普通行军外观自动绑定与按目录顺序分配。 |
| `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt` | 登录快照生成前触发并保存默认绑定。 |
| `src/main/kotlin/com/stzb/server/game/FacadeCatalog.kt` | 校验并规范化客户端传入的主城建筑布局。 |
| `src/main/kotlin/com/stzb/server/game/CityFacadeOperationRequestParser.kt` | 严格解析客户端 `3945` 请求体。 |
| `src/main/kotlin/com/stzb/server/game/WorldState.kt` | 保存 `WorldCity.customView` 并在重启后恢复。 |
| `src/main/kotlin/com/stzb/server/game/GameResponses.kt` | 将每座城市保存的布局映射到 `5026[14][wid]["4"][0]`。 |
| `src/main/kotlin/com/stzb/server/protocol/Cmd.kt` | 声明 `BUILD_FACADE_APPLY_BUILD_SCHEME = 3945`。 |
| `src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt` | 将 `3945` 标记为受支持的服务端命令。 |
| `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt` | 分发 `3945`，回复并广播更新后的世界地图。 |
| `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt` | 默认行军外观分配与手动选择保护。 |
| `src/test/kotlin/com/stzb/server/game/CityFacadeOperationRequestParserTest.kt` | `3945` 请求体解析边界。 |
| `src/test/kotlin/com/stzb/server/game/WorldStateRepositoryTest.kt` | 自定义布局的更新、旧存档兼容和重启恢复。 |
| `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt` | `3945` 回应顺序、地图重发和无效请求不广播。 |

## Task 1: 默认行军外观绑定与登录投影

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt:375-405`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:80-88`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`

**Interfaces:**
- Consumes: `ArmyFacadeCatalog.standardFacadeIds()`, `PlayerState.armyFacadeCards()`, `PlayerState.armyIds()`, `HeroCatalog.heroQuality(heroId)`.
- Produces: `PlayerState.ensureDefaultArmyFacadeBindings(): ArmyFacadeMutation?`, which updates `PlayerHero.armyFacadeCardId` and bound `PlayerArmyFacadeCard.cfgHeroId`.

- [ ] **Step 1: 补充默认分配和手动选择保护的失败测试**

```kotlin
@Test
fun `login snapshot assigns the next real facade after the five default cards are used`() {
    val root = createTempDirectory("stzb-default-army-facade-order")
    try {
        PlayerStateRepository.configure(FilePlayerRepository(root))
        val state = PlayerStateRepository.getOrCreate(50, 10050, "主公")
        val heroes = HeroCatalog.defaultFiveStarHeroIds().take(6).map(state::addHero)
        heroes.forEachIndexed { index, hero ->
            state.assignTeamHero(
                heroUid = hero.heroUid,
                pos = index % 3 + 1,
                armyId = state.armyIds()[index / 3],
            )
        }

        UserInitTableBuilder.build(state.userId, state.cityWid, state.roleName, 1_700_000_000L)

        assertEquals(
            List(5) { 101138 } + 101156,
            heroes.map { hero -> state.hero(hero.heroUid)?.armyFacadeCardId },
        )
    } finally {
        PlayerStateRepository.reset()
        root.toFile().deleteRecursively()
    }
}

@Test
fun `login snapshot keeps an existing manual army facade binding`() {
    val root = createTempDirectory("stzb-default-army-facade-manual")
    try {
        PlayerStateRepository.configure(FilePlayerRepository(root))
        val state = PlayerStateRepository.getOrCreate(51, 10051, "主公")
        val hero = state.addHero(HeroCatalog.defaultFiveStarHeroIds().first())
        state.saveTeam(listOf(hero.heroUid))
        requireNotNull(state.bindArmyFacadeCards(101682, listOf(hero.heroUid)))

        UserInitTableBuilder.build(state.userId, state.cityWid, state.roleName, 1_700_000_000L)

        assertEquals(101682, state.hero(hero.heroUid)?.armyFacadeCardId)
        assertEquals(
            hero.heroId,
            state.armyFacadeCards().single { it.facadeId == 101682 && it.cfgHeroId > 0 }.cfgHeroId,
        )
    } finally {
        PlayerStateRepository.reset()
        root.toFile().deleteRecursively()
    }
}
```

- [ ] **Step 2: 运行快照测试确认红灯**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --no-daemon
```

Expected: 现有默认快照测试和新增默认分配测试失败，因为登录尚未将外观绑定到上阵武将。

- [ ] **Step 3: 实现目录顺序的默认绑定和保存**

```kotlin
fun ensureDefaultArmyFacadeBindings(): ArmyFacadeMutation? {
    if (armyFacadeCards.any { it.cfgHeroId > 0 }) return null
    val deployedHeroes = armyIds()
        .flatMap(::teamHeroes)
        .distinct()
        .mapNotNull(::hero)
        .filter { !it.isAdvanceMaterial && HeroCatalog.heroQuality(it.heroId) == 4 }
    if (deployedHeroes.isEmpty()) return null

    val orderedCards = ArmyFacadeCatalog.standardFacadeIds().flatMap { facadeId ->
        armyFacadeCards
            .filter { it.facadeId == facadeId && it.cfgHeroId == 0 }
            .sortedBy(PlayerArmyFacadeCard::cardId)
    }
    val changedCards = linkedMapOf<Int, Int>()
    val changedHeroes = linkedMapOf<Int, Int>()
    deployedHeroes.forEach { target ->
        val card = orderedCards.firstOrNull { candidate ->
            candidate.cfgHeroId == 0 &&
                armyFacadeCards.none {
                    it.facadeId == candidate.facadeId && it.cfgHeroId == target.heroId
                }
        } ?: return@forEach
        card.cfgHeroId = target.heroId
        target.armyFacadeCardId = card.facadeId
        changedCards[card.cardId] = target.heroId
        changedHeroes[target.heroUid] = card.facadeId
    }
    return ArmyFacadeMutation(
        cardCfgHeroIds = changedCards,
        heroFacadeIds = changedHeroes,
        affectedArmyIds = deployedHeroes.map(PlayerHero::armyId).filter { it > 0 }.toSortedSet(),
    ).takeIf { changedCards.isNotEmpty() }
}
```

在 `UserInitTableBuilder.build` 取得 `state` 后加入：

```kotlin
state.ensureAdvanceMaterials()
if (state.ensureDefaultArmyFacadeBindings() != null) {
    PlayerStateRepository.save(state)
}
```

- [ ] **Step 4: 运行快照测试确认绿灯**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --no-daemon
```

Expected: PASS，单武将、六武将分配和已有手动绑定均保持正确。

- [ ] **Step 5: 提交默认行军外观绑定**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/PlayerState.kt \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt
git diff --cached --check
git commit -m "fix: equip default facades for deployed heroes"
```

## Task 2: 主城布局校验与世界状态持久化

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/FacadeCatalog.kt`
- Create: `src/main/kotlin/com/stzb/server/game/CityFacadeOperationRequestParser.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/WorldState.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Create: `src/test/kotlin/com/stzb/server/game/CityFacadeOperationRequestParserTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/WorldStateRepositoryTest.kt`

**Interfaces:**
- Consumes: client body `[cityWid, customView, 0, ""]`, `FacadeCatalog.cityFacadeIds`.
- Produces: `CityFacadeOperationRequestParser.parseApplyScheme(body): CityFacadeApplyRequest?`, `CityFacadeLayout.normalize(serialized): String?`, and `WorldStateRepository.updateCityCustomView(state, cityWid, customView): Boolean`.

- [ ] **Step 1: 写出布局、请求体和重启恢复的失败测试**

```kotlin
@Test
fun `apply scheme parser only accepts the observed four slot body`() {
    assertEquals(
        CityFacadeApplyRequest(15_061_506, "3433080,100010;"),
        CityFacadeOperationRequestParser.parseApplyScheme("""[15061506,"3433080,100010;",0,""]"""),
    )
    assertNull(CityFacadeOperationRequestParser.parseApplyScheme("""[15061506,"3433080,100010;"]"""))
}

@Test
fun `custom city view survives repository reconstruction`() {
    val root = createTempDirectory("stzb-world-city-facade")
    try {
        PlayerStateRepository.configure(FilePlayerRepository(root))
        WorldStateRepository.configure(root)
        val state = PlayerStateRepository.getOrCreate(10001, 15_061_506, "alice")
        WorldStateRepository.registerOrRestorePlayer(state)
        assertTrue(WorldStateRepository.updateCityCustomView(state, state.cityWid, "3433080,100010;"))

        WorldStateRepository.configure(root)

        assertEquals(
            "3433080,100010;",
            WorldStateRepository.projection().cities.single().customView,
        )
    } finally {
        PlayerStateRepository.reset()
        WorldStateRepository.reset()
        root.toFile().deleteRecursively()
    }
}
```

- [ ] **Step 2: 运行领域测试确认红灯**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.CityFacadeOperationRequestParserTest \
  --tests com.stzb.server.game.WorldStateRepositoryTest \
  --no-daemon
```

Expected: FAIL，因为 `3945` 请求解析器和世界状态更新尚未完整实现。

- [ ] **Step 3: 实现严格解析、布局校验和无变化返回值**

```kotlin
data class CityFacadeApplyRequest(
    val cityWid: Int,
    val customView: String,
)

object CityFacadeOperationRequestParser {
    private val mapper = jacksonObjectMapper()

    fun parseApplyScheme(body: String): CityFacadeApplyRequest? =
        runCatching { mapper.readTree(body) }.getOrNull()
            ?.takeIf {
                it.isArray &&
                    it.size() == 4 &&
                    it[0].asInt() > 0 &&
                    it[1].isTextual &&
                    it[2].asInt() == 0 &&
                    it[3].isTextual &&
                    it[3].asText().isEmpty()
            }
            ?.let { CityFacadeApplyRequest(it[0].asInt(), it[1].asText()) }
}
```

在 `FacadeCatalog.kt` 新增 `CityFacadeLayout`，其 `normalize` 必须：

```kotlin
fun normalize(serialized: String): String? {
    val trimmed = serialized.trim().trimEnd(';')
    if (trimmed.isEmpty()) return null
    val rawPlacements = trimmed.split(';')
    val placements = rawPlacements.mapNotNull(::parsePlacement)
    if (placements.size != rawPlacements.size) return null
    if (placements.map(CityFacadePlacement::position).toSet().size != placements.size) return null
    return "$trimmed;"
}
```

在 `WorldService.updateCityCustomView` 中保持所有权与坐标校验，并将无变化改为：

```kotlin
if (city.customView == normalizedView) return@write false
```

将 `WorldCity` 增加默认字段：

```kotlin
val customView: String = FacadeCatalog.DEFAULT_CITY_CUSTOM_VIEW
```

在 `GameResponses.putWorldCity` 接收可选 `customView`，仅主城的 `"4"` 槽写入：

```kotlin
.add(customView ?: FacadeCatalog.DEFAULT_CITY_CUSTOM_VIEW)
```

调用 `worldCityChunk` 时传入 `city.customView`。

- [ ] **Step 4: 运行领域测试确认绿灯**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.CityFacadeOperationRequestParserTest \
  --tests com.stzb.server.game.WorldStateRepositoryTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --no-daemon
```

Expected: PASS，旧城池默认布局保持，保存布局经重建后仍出现在世界投影中。

- [ ] **Step 5: 提交布局领域与持久化**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/FacadeCatalog.kt \
  src/main/kotlin/com/stzb/server/game/CityFacadeOperationRequestParser.kt \
  src/main/kotlin/com/stzb/server/game/WorldState.kt \
  src/main/kotlin/com/stzb/server/game/GameResponses.kt \
  src/test/kotlin/com/stzb/server/game/CityFacadeOperationRequestParserTest.kt \
  src/test/kotlin/com/stzb/server/game/WorldStateRepositoryTest.kt
git diff --cached --check
git commit -m "fix: persist applied city facade layouts"
```

## Task 3: `3945` 协议路由、响应与世界广播

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`
- Modify: `src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- Consumes: `Cmd.BUILD_FACADE_APPLY_BUILD_SCHEME`, `CityFacadeOperationRequestParser.parseApplyScheme`, `WorldStateRepository.updateCityCustomView`.
- Produces: a `3945 []` acknowledgement followed by `broadcastWorldScene()` only when the world state changes.

- [ ] **Step 1: 写出有效和无效 `3945` 的失败协议测试**

```kotlin
@Test
fun `applying a city facade layout persists and republishes the world scene`() {
    val channel = newChannel()
    val playerId = platformLogin(channel, "city-facade-owner")
    val session = requireNotNull(channel.attr(GameServerHandler.SESSION).get())
    val state = PlayerStateRepository.getOrCreate(
        requireNotNull(session.accountKey),
        GameServerConfig.CITY_WID,
        GameServerConfig.ROLE_NAME,
    )
    val customView = FacadeCatalog.DEFAULT_CITY_CUSTOM_VIEW
        .replace("1122050,100010", "3433080,100010")

    channel.writeInbound(upPacket(3945, """[${state.cityWid},"$customView",0,""]""", playerId))

    assertEquals(3945, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    val scene = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.SEND_WORLD_SCENCE_FULL_INFO, scene.cmd)
    assertEquals(customView, mapper.readTree(scene.body)[14][state.cityWid.toString()]["4"][0].asText())
    channel.finishAndReleaseAll()
}

@Test
fun `invalid city facade scheme acknowledges without changing or broadcasting`() {
    val channel = newChannel()
    val playerId = platformLogin(channel, "city-facade-invalid")

    channel.writeInbound(upPacket(3945, """[15061506,"9999990,100010;",0,""]""", playerId))

    assertEquals(3945, assertIs<DownPacket>(channel.readOutbound<Any>()).cmd)
    assertNull(channel.readOutbound<Any>())
    channel.finishAndReleaseAll()
}
```

- [ ] **Step 2: 运行协议测试确认红灯**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest \
  --no-daemon
```

Expected: FAIL，因为 `3945` 尚未进入 `channelRead0` 分发，且没有世界广播。

- [ ] **Step 3: 路由 `3945` 并在状态变化后广播**

在 `Cmd.kt` 添加：

```kotlin
const val BUILD_FACADE_APPLY_BUILD_SCHEME = 3945
```

在 `CommandContractCatalog` 的已支持命令列表加入 `Cmd.BUILD_FACADE_APPLY_BUILD_SCHEME`，并在 `channelRead0` 加入：

```kotlin
Cmd.BUILD_FACADE_APPLY_BUILD_SCHEME -> {
    logIn(msg)
    sendApplyCityFacadeScheme(ctx, session, msg)
}
```

新增处理器：

```kotlin
private fun sendApplyCityFacadeScheme(
    ctx: ChannelHandlerContext,
    session: Session?,
    msg: UpPacket,
) {
    val userId = session?.userId ?: msg.userId.takeIf { it > 0 } ?: 10001
    val state = playerState(session, userId, GameServerConfig.CITY_WID)
    val changed = CityFacadeOperationRequestParser.parseApplyScheme(msg.bodyText)
        ?.let { request ->
            WorldStateRepository.updateCityCustomView(state, request.cityWid, request.customView)
        } == true

    ctx.writeAndFlush(DownPacket.json(msg.cmdId, GameResponses.emptyArray(), dataType = DownType.PLAIN))
    if (changed) broadcastWorldScene()
    log.info(">> cmd=${msg.cmdId} 主城布局已处理 (uid=$userId, changed=$changed)")
}
```

- [ ] **Step 4: 运行协议测试确认绿灯**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest \
  --tests com.stzb.server.handler.ArmyFacadeHandlerProtocolTest \
  --no-daemon
```

Expected: PASS，`3945` 的成功路径顺序为确认包后 `5026`，非法请求只有确认包；既有行军外观协议仍通过。

- [ ] **Step 5: 提交 `3945` 协议支持**

```bash
git add \
  src/main/kotlin/com/stzb/server/protocol/Cmd.kt \
  src/main/kotlin/com/stzb/server/protocol/CommandContractCatalog.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git diff --cached --check
git commit -m "fix: apply city facade schemes over protocol"
```

## Task 4: 集成验证与发行构建

**Files:**
- No source changes expected.

**Interfaces:**
- Verifies login tables, manual army-facade protocol, world-state restoration, `3945` protocol response, and production compilation together.

- [ ] **Step 1: 运行外观定向回归集**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.CityFacadeOperationRequestParserTest \
  --tests com.stzb.server.game.WorldStateRepositoryTest \
  --tests com.stzb.server.game.GameResponsesTest \
  --tests com.stzb.server.handler.ArmyFacadeHandlerProtocolTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest \
  --no-daemon
```

Expected: PASS，所有已列出的测试完成且失败数为 `0`。

- [ ] **Step 2: 构建可部署发行目录**

Run:

```bash
./gradlew -Pkotlin.compiler.execution.strategy=in-process installDist --no-daemon
shasum -a 256 build/libs/stzb-server-0.1.0.jar
```

Expected: `BUILD SUCCESSFUL`，并输出发行 JAR 的一行 SHA-256。

- [ ] **Step 3: 检查提交范围和工作树**

Run:

```bash
git log --oneline -5
git status --short
git diff --check c7262172..HEAD
```

Expected: 外观恢复提交可见，工作树无本任务未提交文件，不包含主工作区战法调试文件。
