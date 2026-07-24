# Card Recruit Five-Star Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return only client-pack five-star heroes for commands `301` and
`304`, while preventing quick-summon result counts outside the client's
supported 1-10 card layout.

**Architecture:** `HeroCatalog` owns static client hero metadata and the
fixed client-default pack mapping. `GameResponses` selects a candidate pool
and serializes command-specific response shapes. `GameServerHandler` parses
the client request fields and resolves login-snapshot summon UIDs before
calling the response layer.

**Tech Stack:** Kotlin/JVM, Jackson `ArrayNode`, JUnit 5, Gradle.

## Global Constraints

- Five-star means `hero_table.csv.quality == 4`.
- Only `281` and `901` through `907` have direct configured pools.
- Parent pack `801` and unknown packs use the deduplicated `901`-`907`
  five-star union.
- `301` returns one card for `summonOpType == 0`; all other values return
  five cards.
- `304` returns no fewer than one and no more than ten cards.
- `304` response index `5` must sum to its card list size at index `7`.
- Do not change battle, resource, or draw-probability logic.

---

### Task 1: Expose Client Pack Five-Star Metadata

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/HeroCatalogTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/HeroCatalog.kt`

**Interfaces:**
- Produces: `HeroCatalog.heroQuality(heroId: Int): Int`
- Produces: `HeroCatalog.fiveStarHeroIdsForCardPack(packId: Int): List<Int>`
- Produces: `HeroCatalog.defaultFiveStarHeroIds(): List<Int>`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `configured card packs expose only five-star heroes`() {
    val pack281 = HeroCatalog.fiveStarHeroIdsForCardPack(281)

    assertEquals(11, pack281.size)
    assertTrue(100006 in pack281)
    assertTrue(pack281.all { HeroCatalog.heroQuality(it) == 4 })
}

@Test
fun `parent pack falls back to child five-star union`() {
    val parentPool = HeroCatalog.fiveStarHeroIdsForCardPack(801)

    assertTrue(parentPool.size > 11)
    assertTrue(100008 in parentPool)
    assertTrue(parentPool.all { HeroCatalog.heroQuality(it) == 4 })
}
```

- [ ] **Step 2: Run the catalog tests and verify they fail**

Run:

```bash
./gradlew test --tests com.stzb.server.game.HeroCatalogTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: compilation fails because the five-star catalog API is absent.

- [ ] **Step 3: Implement the minimal catalog API**

Parse `hero_table.csv` fully enough to preserve troop type and quality, add
the client-extracted `281` and `901`-`907` hero-ID mapping, filter each pool
with `quality == 4`, and return the child-pack union for `801` or unknown
pack IDs.

- [ ] **Step 4: Run the catalog tests and verify they pass**

Run:

```bash
./gradlew test --tests com.stzb.server.game.HeroCatalogTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`.

### Task 2: Serialize Pack-Bound Recruit Results

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`

**Interfaces:**
- Consumes: `HeroCatalog.fiveStarHeroIdsForCardPack(packId: Int): List<Int>`
- Produces: `GameResponses.cardRecruit(userId, summonUid, summonCfgId, childCfgId, summonOpType): String`
- Produces: `GameResponses.quickCardRecruit(summonUid, packId, quickCount): String`

- [ ] **Step 1: Write the failing response tests**

```kotlin
@Test
fun `card recruit returns five-star heroes from the requested child pack`() {
    val response = mapper.readTree(
        GameResponses.cardRecruit(42, 4201, 801, 901, summonOpType = 1),
    )
    val expected = HeroCatalog.fiveStarHeroIdsForCardPack(901).toSet()

    assertEquals(5, response[1].size())
    assertTrue(response[1].all { card ->
        card[1].asInt() in expected && HeroCatalog.heroQuality(card[1].asInt()) == 4
    })
}

@Test
fun `quick recruit clamps the result to ten cards and matching count`() {
    val response = mapper.readTree(GameResponses.quickCardRecruit(4201, 281, 99))

    assertEquals(10, response[7].size())
    assertEquals(10, response[5].sumOf { it.asInt() })
}
```

- [ ] **Step 2: Run the response tests and verify they fail**

Run:

```bash
./gradlew test --tests com.stzb.server.game.GameResponsesTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: compilation fails because the response APIs do not accept a pack ID.

- [ ] **Step 3: Implement the minimal response change**

Select the effective pack using `childCfgId.takeIf { it > 0 } ?: summonCfgId`,
draw from its five-star candidates, clamp quick count with
`coerceIn(1, 10)`, and serialize `304[5]` as a five-element array whose first
value is the number of returned cards and whose remaining values are zero.

- [ ] **Step 4: Run the response tests and verify they pass**

Run:

```bash
./gradlew test --tests com.stzb.server.game.GameResponsesTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`.

### Task 3: Carry Request Pack Identity Through Network Handlers

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/RecruitResultParserTest.kt`

**Interfaces:**
- Consumes: command `301` request index `0` as `summonCfgId` and index `5`
  as `childCfgId`.
- Consumes: command `304` request index `0` as `summonUid`.
- Produces: default UID resolution:
  `userId * 100 + 1 -> 801`, `userId * 100 + 2 -> 281`.

- [ ] **Step 1: Write the failing handler-adjacent parsing test**

```kotlin
@Test
fun `quick recruit response remains parseable after a capped result`() {
    val json = GameResponses.quickCardRecruit(4_200_001, 801, 100)

    assertEquals(10, RecruitResultParser.heroIdsFrom(Cmd.CARD_QUICK_RECRUIT, json).size)
}
```

- [ ] **Step 2: Run the parsing test and verify it fails**

Run:

```bash
./gradlew test --tests com.stzb.server.game.RecruitResultParserTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: compilation fails because `quickCardRecruit` does not accept the
resolved pack ID.

- [ ] **Step 3: Implement handler parameter propagation**

Read `body[0]` in `sendCardRecruit` and pass it as `summonCfgId`. Add a
private resolver for `sendQuickCardRecruit` that maps the two login snapshot
UIDs to their `refresh_way_id`; unknown UIDs return `801` so the response
uses the safe child-pack union.

- [ ] **Step 4: Run the parsing test and verify it passes**

Run:

```bash
./gradlew test --tests com.stzb.server.game.RecruitResultParserTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`.

### Task 4: Full Regression Verification

**Files:**
- Verify: `src/main/kotlin/com/stzb/server/game/HeroCatalog.kt`
- Verify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Verify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`

- [ ] **Step 1: Run the focused recruitment tests**

Run:

```bash
./gradlew test --tests com.stzb.server.game.HeroCatalogTest --tests com.stzb.server.game.GameResponsesTest --tests com.stzb.server.game.RecruitResultParserTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run complete build verification**

Run:

```bash
./gradlew test installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all tests pass and `build/install/stzb-server/` contains the
updated distribution.

- [ ] **Step 3: Inspect the final diff**

Run:

```bash
git diff -- src/main/kotlin/com/stzb/server/game/HeroCatalog.kt src/main/kotlin/com/stzb/server/game/GameResponses.kt src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt src/test/kotlin/com/stzb/server/game/HeroCatalogTest.kt src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt src/test/kotlin/com/stzb/server/game/RecruitResultParserTest.kt
```

Expected: the diff is limited to card-pack five-star selection, result-count
clamping, handler propagation, and regression coverage.
