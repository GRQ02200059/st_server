# Army Command Capacity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the client display and enforce a 10.0 army command capacity for the local development server.

**Architecture:** The client derives the displayed capacity by dividing `Tb_build_effect_city[26]` by 10. The login/role-creation snapshot is built by `UserInitTableBuilder`; building upgrades use `GameResponses.userBuildUpsertNotify`. Both must emit the same integer protocol value, `100`.

**Tech Stack:** Kotlin/JVM, Jackson `ArrayNode`, JUnit 5, Gradle.

## Global Constraints

- Send `Tb_build_effect_city.army_cost_max` at field index `26`.
- Use integer value `100`, which the client displays as `10.0`.
- Keep login/role-creation snapshots and building-upgrade deltas consistent.
- Do not alter army count, hero cost, hero assignment, card packs, or battle logic.

---

### Task 1: Align Army Command Capacity Responses

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt:40-43`
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt:168-170`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:328-334`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt:534-540`

**Interfaces:**
- Consumes: `UserInitTableBuilder.build(userId, cityWid, roleName, serverOpenTime): ArrayNode`.
- Consumes: `GameResponses.userBuildUpsertNotify(userId, cityWid, buildId, level, resources): String`.
- Produces: `Tb_build_effect_city` rows where `row[26].asInt()` equals `100`.

- [ ] **Step 1: Write failing tests**

Replace the snapshot assertion:

```kotlin
assertTrue(buildEffect[26].asInt() > 0)
```

with:

```kotlin
assertEquals(100, buildEffect[26].asInt())
```

Replace the building-delta assertion:

```kotlin
assertTrue(response[1][2][26].asInt() > 0)
```

with:

```kotlin
assertEquals(100, response[1][2][26].asInt())
```

- [ ] **Step 2: Run focused tests to verify they fail**

Run:

```bash
./gradlew test --tests com.stzb.server.game.UserInitTableBuilderTest --tests com.stzb.server.game.GameResponsesTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: both exact-value assertions fail because current response rows contain `30`.

- [ ] **Step 3: Write minimal implementation**

Update both response builders:

```kotlin
.i(26, 100)                    // army_cost_max => 10.0 cost
```

and:

```kotlin
add(100)           // 26 army_cost_max => 10.0 cost
```

- [ ] **Step 4: Run focused tests to verify they pass**

Run:

```bash
./gradlew test --tests com.stzb.server.game.UserInitTableBuilderTest --tests com.stzb.server.game.GameResponsesTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run complete verification**

Run:

```bash
./gradlew check installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all tests pass and the distribution includes the updated server.

- [ ] **Step 6: Commit**

The repository source tree is untracked. Do not create a partial commit
unless the user first asks to establish a Git baseline.
