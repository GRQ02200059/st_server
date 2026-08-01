# Main City Map Visuals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each player's main city render as a complete city model on the map by emitting the official city layout through both login and world-scene protocols.

**Architecture:** `FacadeCatalog` owns the fixed, client-compatible main-city layout captured from an official `99991` payload. `UserInitTableBuilder` writes that layout to `Tb_world_city` and marks the real main-mansion and wall facades active. `GameResponses` emits exactly the same layout in `5026`, so the running map receives the data needed to create the city model.

**Tech Stack:** Kotlin 1.9, Jackson, Gradle, Netty protocol responses, ADB.

## Global Constraints

- Server-only change. Do not modify, inject, or deploy client code.
- Keep the official server-format layout string intact, including its leading double-quote character.
- Emit map visuals only for `cityType == 1`; city suburbs and normal land must retain empty layout fields.
- Do not alter player building persistence, land ownership, battle behavior, or client resources.
- Preserve existing unrelated worktree changes.

---

### Task 1: Lock Down the Missing Visual Protocol Fields

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt:307-354`
- Modify: `src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt:646-671`

**Interfaces:**
- Consumes: `UserInitTableBuilder.build(...)` and `GameResponses.worldSceneFullInfo(...)`.
- Produces: Regression coverage for login row fields `Tb_world_city[3..4]`, facade activation rows, and `5026[14][wid]["0"][5,13]`.

- [x] **Step 1: Write the failing login-snapshot test**

Replace the city-facade assertions in `snapshot unlocks every army and city facade and creates a normal nine tile main city` with:

```kotlin
val activeFacades = cityFacades.filter { it[5].asInt() == cityWid }
assertEquals(
    setOf(FacadeCatalog.DEFAULT_CITY_FACADE_ID, FacadeCatalog.DEFAULT_CITY_WALL_FACADE_ID),
    activeFacades.map { it[1].asInt() }.toSet(),
)
val mansion = activeFacades.single {
    it[1].asInt() == FacadeCatalog.DEFAULT_CITY_FACADE_ID
}
assertEquals(cityWid, mansion[4].asInt())
assertEquals(cityWid, mansion[5].asInt())
assertEquals(FacadeCatalog.DEFAULT_CITY_FACADE_POS, mansion[6].asInt())
assertEquals(1, mansion[8].asInt())
val wall = activeFacades.single {
    it[1].asInt() == FacadeCatalog.DEFAULT_CITY_WALL_FACADE_ID
}
assertEquals(cityWid, wall[4].asInt())
assertEquals(cityWid, wall[5].asInt())
assertEquals(FacadeCatalog.DEFAULT_CITY_WALL_POS, wall[6].asInt())

val mainCity = worldCities.single { it[0].asInt() == cityWid }
assertEquals(FacadeCatalog.DEFAULT_CITY_MAP_FACADE, mainCity[3].asText())
assertEquals(FacadeCatalog.DEFAULT_CITY_BUILD_DATA, mainCity[4].asText())
assertTrue(
    worldCities
        .filter { it[0].asInt() != cityWid }
        .all { it[3].asText().isEmpty() && it[4].asText().isEmpty() },
)
```

- [x] **Step 2: Write the failing `5026` regression test**

Add this test near `world scene full info provides all client indexed slots`:

```kotlin
@Test
fun `world scene sends city facade and build data only for the main city`() {
    val cityWid = 15_061_506
    val response = mapper.readTree(
        GameResponses.worldSceneFullInfo(
            userId = 42,
            cityWid = cityWid,
            roleName = "主公",
        ),
    )

    val cities = response[14]
    val mainCity = cities[cityWid.toString()]["0"]
    assertEquals(1, mainCity[0].asInt())
    assertEquals(FacadeCatalog.DEFAULT_CITY_MAP_FACADE, mainCity[5].asText())
    assertEquals(FacadeCatalog.DEFAULT_CITY_BUILD_DATA, mainCity[13].asText())

    HomeCity.suburbWids(cityWid).forEach { suburbWid ->
        val suburb = cities[suburbWid.toString()]["0"]
        assertEquals(5, suburb[0].asInt())
        assertEquals("", suburb[5].asText())
        assertEquals("", suburb[13].asText())
    }
}
```

- [x] **Step 3: Run the new tests and verify they are red**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.GameResponsesTest
```

Expected: failures showing empty `Tb_world_city[3]`/`[4]`, `111213` as the active facade, and empty `5026` indexes `5` and `13`.

### Task 2: Emit the Official Main-City Layout

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/FacadeCatalog.kt:7-33`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:428-448`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:663-677`
- Modify: `src/main/kotlin/com/stzb/server/game/GameResponses.kt:389-410`

**Interfaces:**
- Consumes: `FacadeCatalog.DEFAULT_CITY_*` constants.
- Produces: `Tb_world_city` and `5026` city rows whose map facade/build data are identical.

- [x] **Step 1: Add the official main-city constants**

Replace the current single `DEFAULT_CITY_FACADE_ID` declaration with:

```kotlin
/** Official main-mansion facade from a valid 9.2.2 login snapshot. */
const val DEFAULT_CITY_FACADE_ID = 113305
const val DEFAULT_CITY_FACADE_POS = 50005
const val DEFAULT_CITY_WALL_FACADE_ID = 129901
const val DEFAULT_CITY_WALL_POS = 0

/**
 * Server-side encoded city layout. The first character is intentionally a
 * double quote because MapCityData.ChangeIndexWithServer treats it as data.
 */
const val DEFAULT_CITY_MAP_FACADE = "\"4P-e0Go[=)')(',0(*',(,-*)"

/** Official `Tb_world_city.facade3d` format for a fully renderable city. */
const val DEFAULT_CITY_BUILD_DATA =
    "10,8,13,20,20,20,21,20,22,20,23,20,24,20,25,1,30,20," +
        "31,10,32,10,33,10,34,10,35,10,36,20,37,10,40,5,42,5," +
        "43,15,44,3,51,10,52,10,53,10,54,10,61,5,62,6,63,5," +
        "64,5,65,5,66,10,67,3,160,10"
```

- [x] **Step 2: Add visual fields only to `Tb_world_city` main-city rows**

In `tbWorldCity`, append the two layout fields after `cityType` and before `name`:

```kotlin
.i(0, wid)
.i(1, cityType)
.i(2, 0)
.s(3, if (cityType == 1) FacadeCatalog.DEFAULT_CITY_MAP_FACADE else "")
.s(4, if (cityType == 1) FacadeCatalog.DEFAULT_CITY_BUILD_DATA else "")
.s(5, roleName)
```

Keep the existing fields beginning at index 6 unchanged.

- [x] **Step 3: Activate the verified main mansion and wall**

Replace `tbUserBuildFacade` with:

```kotlin
private fun tbUserBuildFacade(userId: Int, cityWid: Int, facadeId: Int): ArrayNode {
    val activePos = when (facadeId) {
        FacadeCatalog.DEFAULT_CITY_FACADE_ID -> FacadeCatalog.DEFAULT_CITY_FACADE_POS
        FacadeCatalog.DEFAULT_CITY_WALL_FACADE_ID -> FacadeCatalog.DEFAULT_CITY_WALL_POS
        else -> null
    }
    return row("Tb_user_build_facade")
        .i(0, facadeId)
        .i(1, facadeId)
        .i(2, userId)
        .i(3, 0)
        .i(4, cityWid)
        .i(5, if (activePos != null) cityWid else 0)
        .i(6, activePos ?: 0)
        .i(7, 0)
        .i(8, 1)
        .i(9, 0)
        .i(10, 0)
        .arr
}
```

- [x] **Step 4: Add the same visual fields to `5026`**

In `GameResponses.putWorldCity`, replace the empty facade/build entries with:

```kotlin
val isMainCity = cityType == 1
putObject(wid.toString()).putArray("0").apply {
    add(cityType)
    add(0)
    add(userId)
    add(0)
    add(0)
    add(if (isMainCity) FacadeCatalog.DEFAULT_CITY_MAP_FACADE else "")
    add(name)
    add(belongCity)
    repeat(4) { add(0) }
    add(0)
    add(if (isMainCity) FacadeCatalog.DEFAULT_CITY_BUILD_DATA else "")
    repeat(7) { add(0) }
}
```

- [x] **Step 5: Run the regression tests and verify green**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.game.GameResponsesTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit the focused protocol fix**

Not performed: the five touched files already contain concurrent, uncommitted
work outside this change. Staging them as whole files would include unrelated
changes.

```bash
git add \
  src/main/kotlin/com/stzb/server/game/FacadeCatalog.kt \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/main/kotlin/com/stzb/server/game/GameResponses.kt \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt \
  src/test/kotlin/com/stzb/server/game/GameResponsesTest.kt
git commit -m "fix: render main city on world map"
```

### Task 3: Build, Deploy, and Verify the Running Server

**Files:**
- Build output: `build/install/stzb-server/`
- Runtime log: `.tmp/stzb-server-59979.log`
- Device evidence: `.debug/main-city/`

**Interfaces:**
- Consumes: the verified `installDist` output.
- Produces: a running server on TCP `59979` whose jar hash is recorded and whose `5026` responses include the visual fields.

- [x] **Step 1: Build the distribution**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process installDist
shasum -a 256 build/install/stzb-server/lib/stzb-server-0.1.0.jar
```

Expected: `BUILD SUCCESSFUL` and a SHA-256 digest for the new jar.

- [x] **Step 2: Replace only the server process**

Capture the current runtime path and data directory, stop only the Java process listening on `59979`, then start the newly built distribution with the same `STZB_DATA_DIR`:

```bash
old_pid="$(lsof -tiTCP:59979 -sTCP:LISTEN)"
ps eww -p "$old_pid"
kill "$old_pid"
nohup env STZB_DATA_DIR="${STZB_DATA_DIR:-data}" \
  build/install/stzb-server/bin/stzb-server \
  > .tmp/stzb-server-59979.log 2>&1 &
```

Verify:

```bash
sleep 2
lsof -nP -iTCP:59979 -sTCP:LISTEN
tail -n 40 .tmp/stzb-server-59979.log
```

Expected: one Java listener on `59979` and the server startup banner.

- [x] **Step 3: Verify actual protocol output**

Reconnect the existing device session and inspect the server log for a new `99991` and `5026`. Confirm the generated response through the two Kotlin regression tests and the active runtime jar hash:

```bash
shasum -a 256 build/install/stzb-server/lib/stzb-server-0.1.0.jar
rg -n '99991|5026' .tmp/stzb-server-59979.log | tail -20
```

Expected: a fresh login and world-scene refresh after reconnect.

- [x] **Step 4: Capture the device after refresh**

Run:

```bash
mkdir -p .debug/main-city
adb -s ce265d68 exec-out screencap -p > .debug/main-city/after-main-city-visuals.png
shasum -a 256 .debug/main-city/after-main-city-visuals.png
```

Expected: a new PNG capture and its SHA-256 digest, taken after the new `5026` has been received.
