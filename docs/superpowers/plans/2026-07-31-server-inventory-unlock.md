# Pure Server Inventory Unlock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send all configured library items and a fully populated weapon inventory in the 99991 login snapshot, with no client-side injection dependency.

**Architecture:** `InventoryCatalog` reads the bundled client MemoryPack configuration tables and produces deterministic weapon and item definitions. `UserInitTableBuilder` converts those definitions into `Tb_gear` and `Tb_user_item` snapshot rows. The generated inventory is stateless: it is recreated at every login rather than written to `PlayerStateSnapshot`.

**Tech Stack:** Kotlin 1.9.23, JUnit 5/kotlin.test, Jackson ArrayNode, existing client MemoryPack table reader.

## Global Constraints

- Send data only from the Kotlin server; do not edit or require Android DLL, PipeBridge, or runtime injection code.
- Bundle `tb_cfg_item.bin` with the existing server client-config resources.
- Every eligible normal weapon gets one max-level, owned red-feature copy.
- Add exactly 50 highest-level `advance=1` hongji copies.
- Add exactly 10 highest-level copies for every non-hongji `level_type` present in the feature table.
- Send all 111 `Tcfg_item` rows as `Tb_user_item` rows with `item_num=5` and `valid_time=0`.
- Generated inventory must be deterministic and use stable IDs, so reconnecting cannot create duplicate client rows.
- Do not persist generated inventory in player JSON; a relog restores the same stock.

---

## File Structure

- `src/main/resources/client-config/tb_cfg_item.bin`
  - Client cfg-5-compatible source table copied from the extracted client resources.
- `src/main/kotlin/com/stzb/server/game/ClientNpcArmyRepository.kt`
  - Changes the existing top-level `MemoryPackTable` and `LittleEndianReader` helpers from file-private to package-visible so other game catalogs can parse the same table format.
- `src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt`
  - Parses gear, feature, and item configuration tables and materializes deterministic snapshot definitions.
- `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
  - Adds concrete `Tb_gear` and `Tb_user_item` login rows and removes `Tb_gear` from the empty-table list.
- `src/test/kotlin/com/stzb/server/game/InventoryCatalogTest.kt`
  - Verifies config parsing and all weapon generation quantity/tier invariants.
- `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
  - Verifies the actual 99991 snapshot contains correct library rows and field values.

## Task 1: Package and Parse Inventory Config

**Files:**
- Create: `src/main/resources/client-config/tb_cfg_item.bin`
- Create: `src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt`
- Modify: `src/main/kotlin/com/stzb/server/game/ClientNpcArmyRepository.kt:449-509`
- Test: `src/test/kotlin/com/stzb/server/game/InventoryCatalogTest.kt`

**Interfaces:**
- Consumes: `MemoryPackTable.open(bytes, source)` and `LittleEndianReader`.
- Produces:
  - `InventoryCatalog.normalWeapons(): List<InventoryGearDefinition>`
  - `InventoryCatalog.hongjiCopies(): List<InventoryGearDefinition>`
  - `InventoryCatalog.normalTierCopies(): List<InventoryGearDefinition>`
  - `InventoryCatalog.items(): List<InventoryItemDefinition>`
  - `InventoryGearDefinition(uid: Int, gearId: Int, featureId: Int, phase: Int, isSeason: Int, skill: String)`
  - `InventoryItemDefinition(id: Int, itemId: Int, repoType: Int)`

- [ ] **Step 1: Copy the required client item config into server resources**

Run:

```bash
cp \
  /Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/npk_extracted_all/others/res/csharp/data/tcfg/default/tb_cfg_item.bin \
  /Users/bytedance/stzb/server/src/main/resources/client-config/tb_cfg_item.bin
```

Expected: `src/main/resources/client-config/tb_cfg_item.bin` exists and is 26,998 bytes.

- [ ] **Step 2: Write the failing catalog test**

Create `src/test/kotlin/com/stzb/server/game/InventoryCatalogTest.kt`:

```kotlin
package com.stzb.server.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InventoryCatalogTest {
    @Test
    fun `catalog unlocks every configured item and creates requested weapon copies`() {
        val normalWeapons = InventoryCatalog.normalWeapons()
        val hongjiCopies = InventoryCatalog.hongjiCopies()
        val normalTierCopies = InventoryCatalog.normalTierCopies()
        val items = InventoryCatalog.items()

        assertTrue(normalWeapons.isNotEmpty())
        assertTrue(normalWeapons.all { it.featureTier.advance == 0 && it.featureTier.levelType == 30 })
        assertEquals(50, hongjiCopies.size)
        assertTrue(hongjiCopies.all { it.featureTier.advance == 1 })

        val tiers = normalTierCopies.groupBy { it.featureTier.levelType }
        assertTrue(tiers.isNotEmpty())
        assertTrue(tiers.all { (_, copies) -> copies.size == 10 })
        assertTrue(tiers.keys.all { it >= 0 })

        assertEquals(111, items.size)
        assertEquals(111, items.map(InventoryItemDefinition::itemId).distinct().size)
        assertTrue(items.all { it.repoType >= 0 })
    }
}
```

- [ ] **Step 3: Run the catalog test to verify it fails**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.InventoryCatalogTest
```

Expected: compilation fails because `InventoryCatalog`, `InventoryGearDefinition`, and `InventoryItemDefinition` do not exist.

- [ ] **Step 4: Expose the shared MemoryPack reader**

In `ClientNpcArmyRepository.kt`, change only the visibility of the existing helpers:

```kotlin
internal class MemoryPackTable private constructor(
    val strings: List<String?>,
    val keys: List<Int>,
    val reader: LittleEndianReader,
) { /* existing body unchanged */ }

internal class LittleEndianReader(bytes: ByteArray) { /* existing body unchanged */ }
```

Do not change their binary decoding behavior.

- [ ] **Step 5: Implement `InventoryCatalog`**

Create `src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt` with:

```kotlin
package com.stzb.server.game

data class InventoryFeatureTier(
    val advance: Int,
    val levelType: Int,
    val level: Int,
)

data class InventoryGearDefinition(
    val uid: Int,
    val gearId: Int,
    val featureId: Int,
    val featureTier: InventoryFeatureTier,
    val phase: Int,
    val isSeason: Int,
    val skill: String,
)

data class InventoryItemDefinition(
    val id: Int,
    val itemId: Int,
    val repoType: Int,
)

object InventoryCatalog {
    fun normalWeapons(): List<InventoryGearDefinition>
    fun hongjiCopies(): List<InventoryGearDefinition>
    fun normalTierCopies(): List<InventoryGearDefinition>
    fun items(): List<InventoryItemDefinition>
}
```

Use `MemoryPackTable.open` to parse:

```kotlin
// Tcfg_gear: object header 22, 8 ints, 3 bytes, 11 string indices.
require(table.reader.byte().toInt() and 0xff == 22)
val gearId = table.reader.int()
val gearType = table.reader.int()
repeat(2) { table.reader.int() }
val phase = table.reader.int()
repeat(3) { table.reader.int() }
val isSeason = table.reader.byte().toInt()
val isDefective = table.reader.byte().toInt()
val tag = table.reader.byte().toInt()
val strings = List(11) { table.string(table.reader.int()).orEmpty() }
val skill = strings[2].ifBlank { strings[10] }
```

```kotlin
// Tcfg_gear_feature: header 11, 8 ints, then skill/desc/policy.
require(table.reader.byte().toInt() and 0xff == 11)
val featureId = table.reader.int()
val gearType = table.reader.int()
val level = table.reader.int()
val levelType = table.reader.int()
val advance = table.reader.int()
repeat(3) { table.reader.int() }
val skill = table.string(table.reader.int()).orEmpty()
table.reader.int() // desc
val policy = table.string(table.reader.int()).orEmpty()
```

```kotlin
// Tcfg_item: header 22, 13 ints, then 9 string indices.
require(table.reader.byte().toInt() and 0xff == 22)
val itemId = table.reader.int()
val repoType = table.reader.int()
repeat(11) { table.reader.int() }
repeat(9) { table.reader.int() }
```

Apply these deterministic generation rules:

```kotlin
private const val BASE_GEAR_UID = 800_000_000
private const val HONGJI_UID = 840_100_000
private const val NORMAL_TIER_UID = 841_000_000
private const val ITEM_UID = 1_900_000_000
private const val HONGJI_COPY_COUNT = 50
private const val NORMAL_TIER_COPY_COUNT = 10

// Eligible weapon: gearId > 0, gearType > 0, isDefective == 0, tag == 0.
// Base feature: same gear type's best (advance == 0, levelType == 30), else
// the global best red feature. Sort by level descending then feature id ascending.
// Hongji feature: global best advance == 1, ordered by level descending then id.
// Normal tier feature: best feature for each advance == 0 levelType, ordered the same.
// Choose matching gear type body; fall back to the first eligible normal weapon.
```

For generated UIDs:

```kotlin
baseUid = BASE_GEAR_UID + gearId
hongjiUid = HONGJI_UID + copyIndex // copyIndex is 1..50
normalTierUid = NORMAL_TIER_UID + levelType * 100 + copyIndex // 1..10
itemUid = ITEM_UID + rowIndex // rowIndex is 1-based
```

- [ ] **Step 6: Run the catalog test to verify it passes**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process \
  test --tests com.stzb.server.game.InventoryCatalogTest
```

Expected: `BUILD SUCCESSFUL`; 50 hongji copies, 10 copies for every actual normal tier, and 111 item definitions.

- [ ] **Step 7: Commit the catalog**

```bash
git add \
  src/main/resources/client-config/tb_cfg_item.bin \
  src/main/kotlin/com/stzb/server/game/ClientNpcArmyRepository.kt \
  src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt \
  src/test/kotlin/com/stzb/server/game/InventoryCatalogTest.kt
git commit -m "feat: catalog server inventory unlocks"
```

### Task 2: Emit Real `Tb_gear` and `Tb_user_item` Login Rows

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt:124-170`
- Modify: `src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- Consumes: `InventoryCatalog.normalWeapons()`, `hongjiCopies()`, `normalTierCopies()`, and `items()`.
- Produces: populated `Tb_gear` and `Tb_user_item` arrays in `UserInitTableBuilder.build()`.

- [ ] **Step 1: Write failing snapshot and 99991 handler tests**

Add this test to `UserInitTableBuilderTest.kt`:

```kotlin
@Test
fun `login snapshot grants configured weapons and five copies of every item`() {
    val userId = 77
    val snapshot = UserInitTableBuilder.build(
        userId = userId,
        cityWid = 10077,
        roleName = "主公",
        serverOpenTime = 1_700_000_000L,
    )
    val tables = snapshot.drop(1).associateBy { it[0].asText() }
    val gears = tables.getValue("Tb_gear")[1]
    val items = tables.getValue("Tb_user_item")[1]

    val expectedGearCount =
        InventoryCatalog.normalWeapons().size +
            InventoryCatalog.hongjiCopies().size +
            InventoryCatalog.normalTierCopies().size
    assertEquals(expectedGearCount, gears.size())
    assertTrue(gears.all { it[2].asInt() == userId && it[5].asInt() == 2 && it[9].asInt() == 0 })
    assertEquals(50, gears.count { it[0].asInt() in 840_100_001..840_100_050 })
    assertTrue(gears.filter { it[0].asInt() in 800_000_000..800_999_999 }.all { it[4].asInt() > 0 })

    assertEquals(111, items.size())
    assertTrue(items.all {
        it[2].asInt() == userId &&
            it[4].asInt() == 5 &&
            it[5].asInt() == 0 &&
            it[6].asInt() == 0
    })
    assertEquals(111, items.map { it[1].asInt() }.distinct().size)
}
```

In `GameServerHandlerProtocolTest.kt`, add a 99991 login assertion which parses
the returned `UserInitTable` and verifies:

```kotlin
assertEquals(111, itemRows.size())
assertTrue(itemRows.all { row -> row[4].asInt() == 5 && row[5].asInt() == 0 })
assertEquals(50, gearRows.count { row -> row[0].asInt() in 840_100_001..840_100_050 })
```

- [ ] **Step 2: Run both tests to verify they fail**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: assertion failures because `Tb_gear` is empty and `Tb_user_item` is absent
from both the direct builder snapshot and the real 99991 response.

- [ ] **Step 3: Add populated inventory tables in `UserInitTableBuilder.build`**

Replace the empty `Tb_gear` path with:

```kotlin
val grantedGears =
    InventoryCatalog.normalWeapons() +
        InventoryCatalog.hongjiCopies() +
        InventoryCatalog.normalTierCopies()

root.add(
    table(
        "Tb_gear",
        *grantedGears.map { gear -> tbGear(userId, gear) }.toTypedArray(),
    ),
)
root.add(
    table(
        "Tb_user_item",
        *InventoryCatalog.items().map { item -> tbUserItem(userId, item) }.toTypedArray(),
    ),
)
```

Remove `"Tb_gear"` from the `addEmptyTables` argument list. Do not add
`Tb_user_item` to that list.

- [ ] **Step 4: Add row builders with schema-correct fields**

Add to `UserInitTableBuilder`:

```kotlin
private fun tbGear(userId: Int, gear: InventoryGearDefinition): ArrayNode =
    row("Tb_gear")
        .i(0, gear.uid)
        .i(1, gear.gearId)
        .i(2, userId)
        .s(3, gear.skill)
        .i(4, gear.featureId)
        .i(5, 2)
        .i(6, 0)
        .i(7, if (gear.phase <= 1) 5 else 10)
        .i(8, gear.phase.coerceAtLeast(1))
        .i(9, 0)
        .i(10, 0).i(11, 0).i(12, 0).i(13, 0).i(14, 0).i(15, 0).i(16, 0)
        .s(17, "")
        .i(18, 0)
        .s(19, "")
        .i(20, 0)
        .s(21, "")
        .i(22, 0).i(23, 0).i(24, 0).i(25, 0).i(26, 0).i(27, 0).i(28, 0)
        .s(29, "").s(30, "")
        .i(31, 0)
        .s(32, "").s(33, "").s(34, "").s(35, "").s(36, "")
        .i(37, gear.isSeason)
        .i(38, 0).i(39, 0).i(40, 0)
        .s(41, "")
        .arr

private fun tbUserItem(userId: Int, item: InventoryItemDefinition): ArrayNode =
    row("Tb_user_item")
        .i(0, item.id)
        .i(1, item.itemId)
        .i(2, userId)
        .i(3, item.repoType)
        .i(4, 5)
        .i(5, 0)
        .i(6, 0)
        .i(7, 0)
        .i(8, 0)
        .arr
```

- [ ] **Step 5: Run builder and handler tests to verify they pass**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: `BUILD SUCCESSFUL`; the builder and real 99991 response both contain all
generated gear rows and exactly 111 permanent item rows with quantity 5.

- [ ] **Step 6: Commit snapshot generation**

```bash
git add \
  src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt \
  src/test/kotlin/com/stzb/server/game/UserInitTableBuilderTest.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git commit -m "feat: grant inventory in login snapshot"
```

### Task 3: Verify and Package the Server Distribution

**Files:**
- Verify: `src/main/kotlin/com/stzb/server/game/InventoryCatalog.kt`
- Verify: `src/main/kotlin/com/stzb/server/game/UserInitTableBuilder.kt`
- Verify: `src/test/kotlin/com/stzb/server/game/InventoryCatalogTest.kt`

**Interfaces:**
- Consumes: completed catalog and login snapshot generation.
- Produces: a checked `build/install/stzb-server` distribution.

- [ ] **Step 1: Run focused inventory verification**

Run:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process test \
  --tests com.stzb.server.game.InventoryCatalogTest \
  --tests com.stzb.server.game.UserInitTableBuilderTest \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run full verification and build the deployable distribution**

Run:

```bash
git diff --check
./gradlew -Dkotlin.compiler.execution.strategy=in-process test
./gradlew -Dkotlin.compiler.execution.strategy=in-process installDist
shasum -a 256 build/install/stzb-server/lib/stzb-server-0.1.0.jar
```

Expected: no diff whitespace errors and `installDist` succeeds. If the full suite
has pre-existing unrelated failures, record the exact failed test names and verify
the focused inventory suite still succeeds before proceeding.

- [ ] **Step 3: Smoke-test the packaged server**

```bash
STZB_PORT=59980 STZB_DATA_DIR=/tmp/stzb-inventory-smoke \
  ./build/install/stzb-server/bin/stzb-server &
server_pid=$!
sleep 1
python3 test_client.py 59980
kill "$server_pid"
wait "$server_pid" || true
```

Expected: `test_client.py` completes platform login, server-list, and 99991 login
without a frame or schema error. Inspect the decoded UserInitTable in the smoke
test output or use the handler test assertions to confirm the generated inventory.
