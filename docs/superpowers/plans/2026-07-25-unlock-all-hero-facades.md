# Unlock All Hero Facades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every client-configured normal and achievement hero facade permanently owned and selectable without granting achievement progress or ancillary rewards.

**Architecture:** Add a read-only `HeroFacadeCatalog` over packaged `hero_table.csv`, serialize its entries into login `Tb_user_facade_card`, and persist the selected facade on each `PlayerHero`. Handle direct facade selection command 674 through the authenticated session and publish the updated `Tb_hero`.

**Tech Stack:** Kotlin, Jackson `ArrayNode`, Netty handler, Gradle/Kotlin Test, client-decompiled C# and CSV protocol references.

## Global Constraints

- Unlock normal and achievement hero facades only.
- Do not modify `Tb_hero_achieve`.
- Do not unlock heads, frames, login themes, or other rewards.
- Do not create additional hero cards.
- Preserve existing uncommitted user changes.

---

### Task 1: Hero facade catalog

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/HeroFacadeCatalog.kt`
- Create: `src/test/kotlin/com/stzb/server/game/HeroFacadeCatalogTest.kt`

**Interfaces:**
- Produces: `HeroFacadeCatalog.all(): List<HeroFacadeDefinition>`
- Produces: `HeroFacadeCatalog.canUse(facadeHeroId: Int, baseHeroId: Int): Boolean`

- [ ] Write tests proving known normal and achievement facades are present, normal heroes are excluded, IDs are unique, and bindings validate.
- [ ] Run `./gradlew test --tests 'com.stzb.server.game.HeroFacadeCatalogTest'` and verify RED.
- [ ] Parse rows with `base_hero_id > 0`, retaining `heroid`, `base_hero_id`, `facade_ex_hero_id`, and `book_type`.
- [ ] Run the focused test and verify GREEN.

### Task 2: Login ownership rows

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`

**Interfaces:**
- Consumes: `HeroFacadeCatalog.all()`
- Produces: `Tb_user_facade_card` rows with stable ID, `end_time=0`, `gain_type=0`, and `read=1`.

- [ ] Write a test extracting `Tb_user_facade_card` from login data and checking count, known facade, permanence, read state, and absence of `Tb_hero_achieve`.
- [ ] Run the focused test and verify RED.
- [ ] Add the populated facade table before empty-table padding.
- [ ] Run the focused test and verify GREEN.

### Task 3: Selected facade persistence and serialization

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/PlayerState.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/PlayerStatePersistenceTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt`

**Interfaces:**
- Produces: `PlayerState.selectHeroFacade(heroUid: Int, facadeHeroId: Int): Boolean`
- Persists: `PlayerHero.dynamicIcon`

- [ ] Write tests for valid selection, reset to zero, invalid binding rejection, snapshot restore, and `Tb_hero[43]` serialization.
- [ ] Run focused tests and verify RED.
- [ ] Add `dynamicIcon`, validation, snapshot persistence, and protocol field 43 serialization.
- [ ] Run focused tests and verify GREEN.

### Task 4: Facade selection command

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`

**Interfaces:**
- Consumes: command 674 body `[heroUid, facadeHeroId]`
- Produces: command 674 success response plus `Tb_hero` DB notification.

- [ ] Add command constant and route.
- [ ] Resolve player state through `session.accountKey`, validate and persist selection.
- [ ] Return an empty-array success response and publish the updated hero row.
- [ ] Compile with `./gradlew compileKotlin --no-daemon -Dkotlin.compiler.execution.strategy=in-process`.

### Task 5: Verification

**Files:**
- Verify all modified production and test files.

- [ ] Run `./gradlew test installDist --no-daemon -Dkotlin.compiler.execution.strategy=in-process`.
- [ ] Run `git diff --check`.
- [ ] Confirm no `Tb_hero_achieve`, head, frame, or login-theme state was introduced.
