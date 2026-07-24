# Hero Card Capacity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the development server's effective hero-card bag limit by supplying a large client-supported capacity in the login snapshot.

**Architecture:** `UserInitTableBuilder` owns the `99991` snapshot and already writes sparse `Tb_user_stuff` rows. Add the documented `hero_card_max` value at field index `63`, reusing the project's existing bounded unlimited constant. Protect the wire contract with the existing snapshot test.

**Tech Stack:** Kotlin/JVM, Jackson `ArrayNode`, JUnit 5, Gradle.

## Global Constraints

- Send `Tb_user_stuff.hero_card_max` at field index `63`.
- Use `PlayerResources.UNLIMITED_AMOUNT`, exactly `2_000_000_000`.
- Keep the change limited to login snapshot capacity; do not alter recruiting, card packs, or persistence.
- The client must fully reconnect to receive the new table snapshot.

---

### Task 1: Supply the Login-Snapshot Hero Capacity

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt:12-26`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:390-397`

**Interfaces:**
- Consumes: `UserInitTableBuilder.build(userId, cityWid, roleName, serverOpenTime): ArrayNode`
- Produces: `Tb_user_stuff` snapshot row where `row[63].asInt()` is `PlayerResources.UNLIMITED_AMOUNT`.

- [ ] **Step 1: Write the failing test**

Add the `Tb_user_stuff` lookup and assertion to the existing login-snapshot test:

```kotlin
val userStuff = tables.getValue("Tb_user_stuff")[1][0]
assertEquals(PlayerResources.UNLIMITED_AMOUNT, userStuff[63].asInt())
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.stzb.server.game.UserInitTableBuilderTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: the assertion fails because index `63` is absent and Jackson returns a missing node whose `asInt()` is `0`.

- [ ] **Step 3: Write minimal implementation**

Extend `tbUserStuff`:

```kotlin
.s(62, "")                    // occupy_land_level (empty string is safe)
.i(63, PlayerResources.UNLIMITED_AMOUNT) // hero_card_max
.arr
```

- [ ] **Step 4: Run focused test to verify it passes**

Run:

```bash
./gradlew test --tests com.stzb.server.game.UserInitTableBuilderTest --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run complete verification**

Run:

```bash
./gradlew test installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process
```

Expected: all tests pass and `installDist` produces the updated server distribution.

- [ ] **Step 6: Commit**

The current repository has untracked source files. Do not create a partial commit unless the user first asks to establish the project baseline; otherwise report the exact changed files.
