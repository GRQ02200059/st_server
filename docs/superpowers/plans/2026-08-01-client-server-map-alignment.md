# Client-Server Map Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make land levels and defender armies use the same map 5 data loaded by the client, while removing stale client-side dynamic land overrides.

**Architecture:** `GameServerConfig.CFG_DB_ID` becomes the only default map selector. The login snapshot clears unsupported dynamic land tables so client `GetRealResLevel()` falls back to the same static `resources_in_map` consumed by `LandDefenderFactory`.

**Tech Stack:** Kotlin 1.9.23, JUnit Platform via `kotlin.test`, Gradle, Jackson JSON nodes.

## Global Constraints

- Do not modify or inject client map logic.
- Do not start or restart the game server.
- Preserve `CFG_DB_ID=5` because map 984 breaks the current card-pack and warfare-skill configuration.
- Do not implement land development, reclamation, or store-house gameplay.
- Do not commit implementation files from the dirty worktree; they contain unrelated in-progress changes.

---

### Task 1: Bind Defender Selection to the Login Map

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/LandDefenderFactoryTest.kt:1-42`
- Modify: `src/main/kotlin/com/stzb/server/game/LandMapRepository.kt:62-63`
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt:94-108`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt:465-470`

**Interfaces:**
- Consumes: `GameServerConfig.CFG_DB_ID: Int`
- Produces: `LandMapRepository.loadDefault(): LandMapRepository` backed by the same cfg index advertised to the client.

- [ ] **Step 1: Write the failing map-alignment tests**

Add the import and tests below, and update the existing target-coordinate assertion:

```kotlin
import com.stzb.server.protocol.GameServerConfig

@Test
fun `default resource map is the map advertised to the client`() {
    val advertised = LandMapRepository.load(GameServerConfig.CFG_DB_ID)
    val default = LandMapRepository.loadDefault()

    listOf(15_061_504, 15_071_503, 15_081_505, 15_031_503, 15_031_501).forEach { wid ->
        assertEquals(advertised.tile(wid), default.tile(wid), "wid=$wid")
    }
}

@Test
fun `default defender map follows the resource map selected by the client config`() {
    val factory = LandDefenderFactory()

    assertEquals(6, factory.levelForWid(15_061_504))
    assertEquals(listOf(611, 612), factory.armyIdsForWid(15_061_504))
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.LandDefenderFactoryTest --no-daemon
```

Expected: FAIL because the current default repository uses map 984; `15061504` is level 3 instead of level 6.

- [ ] **Step 3: Remove the independent defender-map selector**

Change the default loader:

```kotlin
/** Loads the resource map advertised to the active client. */
fun loadDefault(): LandMapRepository = load(GameServerConfig.CFG_DB_ID)
```

Delete `GameServerConfig.RESOURCE_MAP_CFG_ID` and its comment. Update the debug payload to avoid a stale second source:

```kotlin
"cfgDataIndex" to GameServerConfig.CFG_DB_ID,
"resourceMapId" to GameServerConfig.CFG_DB_ID,
```

- [ ] **Step 4: Run the map tests and verify GREEN**

Run the Step 2 command again.

Expected: PASS, including `15061504 -> level 6 -> [611, 612]`.

- [ ] **Step 5: Inspect the scoped diff**

Run:

```bash
git diff --check -- \
  src/main/kotlin/com/stzb/server/game/LandMapRepository.kt \
  src/main/kotlin/com/stzb/server/protocol/Cmd.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/game/LandDefenderFactoryTest.kt
```

Expected: no whitespace errors. Do not commit these dirty shared files.

### Task 2: Clear Unsupported Dynamic Land Overrides

**Files:**
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt:167-179`
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:225-249`

**Interfaces:**
- Consumes: `UserInitTableBuilder.build(...)`
- Produces: empty login-snapshot tables named `Tb_developed_land`, `Tb_land_reclamation`, and `Tb_store_house`.

- [ ] **Step 1: Write the failing snapshot test**

Add:

```kotlin
@Test
fun `login snapshot clears stale dynamic land level overrides`() {
    val snapshot = UserInitTableBuilder.build(
        userId = 42,
        cityWid = 10001,
        roleName = "主公",
        serverOpenTime = 1_700_000_000L,
    )
    val tables = snapshot.drop(1).associateBy { it[0].asText() }

    listOf("Tb_developed_land", "Tb_land_reclamation", "Tb_store_house").forEach { name ->
        assertTrue(tables.containsKey(name), "$name must be present")
        assertTrue(tables.getValue(name)[1].isEmpty, "$name must be empty")
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.UserInitTableBuilderTest \
  --no-daemon
```

Expected: FAIL because the snapshot currently omits `Tb_developed_land`.

- [ ] **Step 3: Add the three empty tables**

Add the names to the existing `addEmptyTables` call:

```kotlin
"Tb_user_npc_army",
"Tb_developed_land",
"Tb_land_reclamation",
"Tb_store_house",
```

- [ ] **Step 4: Run the snapshot tests and verify GREEN**

Run the Step 2 command again.

Expected: PASS; all three tables are present with empty row arrays.

- [ ] **Step 5: Inspect the scoped diff**

Run:

```bash
git diff --check -- \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt
```

Expected: no whitespace errors. Do not commit these dirty shared files.

### Task 3: Build and Verify the Protocol Result

**Files:**
- Verify only; no additional production changes.

**Interfaces:**
- Consumes: `GameResponses.userNpcArmy(Int): String`
- Consumes: `GameResponses.landDefenderArmy(Int): String`
- Produces: matching `4329` and `4331` payloads for the aligned map.

- [ ] **Step 1: Run both focused suites together**

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.LandDefenderFactoryTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Build the distribution without starting it**

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  installDist --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify both defender responses from the built distribution**

```bash
CLASSPATH=$(find build/install/stzb-server/lib -name '*.jar' -print | paste -sd: -)
jshell --class-path "$CLASSPATH" <<'EOF'
import com.stzb.server.game.*;
System.out.println(GameResponses.INSTANCE.userNpcArmy(15061504));
System.out.println(GameResponses.INSTANCE.landDefenderArmy(15061504));
EOF
```

Expected twice:

```text
[15061504,"611,612"]
```

- [ ] **Step 4: Verify the client map evidence remains identical**

Compare the PipeBridge-exported client plaintext against the server map 5 plaintext:

```bash
python3 - <<'PY'
from pathlib import Path
import hashlib
import zlib

client = Path("../tools/monitor-agent/build/client-map-alignment-20260801/map5/resources_in_map.bin").read_bytes()
server = zlib.decompress(Path("src/main/resources/map/5/resources_in_map.mbd").read_bytes())
assert client == server
print(hashlib.sha256(client).hexdigest())
PY
```

Expected:

```text
f2b7949f37c8aa8ad147a3eebac66ee9b8b94d3edd3267a5f3adeaffc29e5e74
```

- [ ] **Step 5: Report deployment boundary**

Report the changed behavior and verification results. Explicitly state that the server was not started or restarted and must be restarted manually by the user to load the new build.
