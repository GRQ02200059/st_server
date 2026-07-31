# World Chat Display Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make world chat display correctly in the client, including realtime `2100` delivery and `711` history replay.

**Architecture:** Store canonical 46-field chat records in a bounded process-local `WorldChatStore`. `GameServerHandler` creates one record per `710`, uses it unchanged for XOR `2100` broadcasts, and transforms the same record into `[id, value]` history entries for the world slot in the ZLIB `711` response.

**Tech Stack:** Kotlin 1.9, Netty embedded channels, Jackson, JUnit 5/Kotlin Test.

## Global Constraints

- Server-side Kotlin only. Do not modify or inject the client.
- Normal world chat uses server channel `0`.
- Realtime `2100` packets use `DownType.XOR` and exactly 46 fields.
- `711` returns 18 history slots with world history in index `0`, encoded as `DownType.ZLIB`.
- Keep only the latest 100 world records in memory; history does not survive restart.
- Preserve unrelated worktree changes.

---

### Task 1: Add a Bounded Canonical World Chat Store

**Files:**
- Create: `src/main/kotlin/com/stzb/server/game/WorldChatStore.kt`
- Test: `src/test/kotlin/com/stzb/server/game/WorldChatStoreTest.kt`

**Interfaces:**
- Produces `WorldChatRecord(fields: List<Any?>)`.
- Produces `WorldChatStore.append(record: WorldChatRecord)`, `snapshot(): List<WorldChatRecord>`, and `reset()`.
- `WorldChatRecord.historyEntry()` returns `[chatId, fieldsWithoutChatId]`.

- [ ] **Step 1: Write the failing store test**

```kotlin
package com.stzb.server.game

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldChatStoreTest {
    @AfterTest
    fun tearDown() {
        WorldChatStore.reset()
    }

    @Test
    fun `stores bounded canonical records and exposes history id pairs`() {
        repeat(WorldChatStore.MAX_RECORDS + 1) { index ->
            WorldChatStore.append(record(index + 1))
        }

        val snapshot = WorldChatStore.snapshot()

        assertEquals(WorldChatStore.MAX_RECORDS, snapshot.size)
        assertEquals(2, snapshot.first().fields[0])
        assertEquals(WorldChatStore.MAX_RECORDS + 1, snapshot.last().fields[0])
        assertEquals(
            listOf(2, snapshot.first().fields.drop(1)),
            snapshot.first().historyEntry(),
        )
    }

    @Test
    fun `rejects records that do not match the 46 field chat contract`() {
        assertFailsWith<IllegalArgumentException> {
            WorldChatRecord(List(WorldChatRecord.FIELD_COUNT - 1) { 0 })
        }
    }

    private fun record(id: Int): WorldChatRecord =
        WorldChatRecord(
            MutableList<Any?>(WorldChatRecord.FIELD_COUNT) { 0 }.also { fields ->
                fields[0] = id
                fields[1] = 0
                fields[4] = "主公"
                fields[5] = "消息$id"
            },
        )
}
```

- [ ] **Step 2: Run the store test and verify it fails**

Run:

```bash
./gradlew test --tests com.stzb.server.game.WorldChatStoreTest
```

Expected: compilation failure because `WorldChatRecord` and `WorldChatStore` do not exist.

- [ ] **Step 3: Add the minimal canonical store**

```kotlin
package com.stzb.server.game

data class WorldChatRecord(
    val fields: List<Any?>,
) {
    init {
        require(fields.size == FIELD_COUNT) {
            "world chat requires $FIELD_COUNT fields, got ${fields.size}"
        }
    }

    fun historyEntry(): List<Any?> =
        listOf(fields.first(), fields.drop(1))

    companion object {
        const val FIELD_COUNT = 46
    }
}

object WorldChatStore {
    const val MAX_RECORDS = 100

    private val records = ArrayDeque<WorldChatRecord>()

    @Synchronized
    fun append(record: WorldChatRecord) {
        records.addLast(record)
        while (records.size > MAX_RECORDS) {
            records.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<WorldChatRecord> = records.toList()

    @Synchronized
    fun reset() {
        records.clear()
    }
}
```

- [ ] **Step 4: Run the store test and verify it passes**

Run:

```bash
./gradlew test --tests com.stzb.server.game.WorldChatStoreTest
```

Expected: `BUILD SUCCESSFUL` and two passing tests.

- [ ] **Step 5: Commit the isolated store**

```bash
git add src/main/kotlin/com/stzb/server/game/WorldChatStore.kt \
  src/test/kotlin/com/stzb/server/game/WorldChatStoreTest.kt
git commit -m "feat: add bounded world chat store"
```

### Task 2: Emit Official-Shaped World Chat and Serve History

**Files:**
- Modify: `src/main/kotlin/com/stzb/server/protocol/Cmd.kt`
- Modify: `src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt`
- Modify: `src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt`

**Interfaces:**
- Consumes `WorldChatRecord` and `WorldChatStore` from Task 1.
- Adds `Cmd.CHAT_HISTORY = 711`.
- Adds `sendChatHistory(ctx: ChannelHandlerContext)`.
- Extends `GameServerHandler.resetRuntimeForTests()` to call `WorldChatStore.reset()`.

- [ ] **Step 1: Replace the existing chat regression test with a failing official-shape test**

In `GameServerHandlerProtocolTest.kt`, replace the current chat assertions with:

```kotlin
@Test
fun `world chat uses official 2100 shape and is returned by history`() {
    val channel = newChannel()
    val playerId = platformLogin(channel, "alice")

    channel.writeInbound(
        upPacket(
            cmdId = Cmd.CHAT,
            json = """[0,0,"你好",[[]],0,0,"","",0,"",""]""",
            userId = playerId,
        ),
    )

    val acknowledgement = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.CHAT, acknowledgement.cmd)
    assertEquals("[false,0]", acknowledgement.body.toString(Charsets.UTF_8))

    val notification = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(Cmd.NOTIFY_CHAT_MSG, notification.cmd)
    assertEquals(DownType.XOR, notification.dataType)
    val message = mapper.readTree(notification.body)
    assertEquals(46, message.size())
    assertEquals(0, message[1].asInt())
    assertEquals(0, message[2].asInt())
    assertEquals(playerId, message[3].asInt())
    assertEquals(GameServerConfig.ROLE_NAME, message[4].asText())
    assertEquals("你好", message[5].asText())
    assertTrue(message[19].isNull)
    assertEquals("role_$playerId", message[45].asText())

    channel.writeInbound(upPacket(711, "[]", playerId))

    val history = assertIs<DownPacket>(channel.readOutbound<Any>())
    assertEquals(711, history.cmd)
    assertEquals(DownType.ZLIB, history.dataType)
    val slots = mapper.readTree(history.body)
    assertEquals(18, slots.size())
    assertEquals(1, slots[0].size())
    assertEquals(message[0].asInt(), slots[0][0][0].asInt())
    assertEquals(message[1], slots[0][0][1][0])
    assertEquals(message[45], slots[0][0][1][44])
    assertNull(channel.readOutbound<Any>())
    channel.finishAndReleaseAll()
}

@Test
fun `world chat broadcasts the same canonical record to every online session`() {
    val alice = newChannel()
    val bob = newChannel()
    val aliceId = platformLogin(alice, "alice")
    platformLogin(bob, "bob")

    alice.writeInbound(
        upPacket(
            Cmd.CHAT,
            """[0,0,"全服可见",[[]],0,0,"","",0,"",""]""",
            aliceId,
        ),
    )

    assertIs<DownPacket>(alice.readOutbound<Any>())
    val aliceNotification = assertIs<DownPacket>(alice.readOutbound<Any>())
    val bobNotification = assertIs<DownPacket>(bob.readOutbound<Any>())

    assertEquals(Cmd.NOTIFY_CHAT_MSG, aliceNotification.cmd)
    assertEquals(Cmd.NOTIFY_CHAT_MSG, bobNotification.cmd)
    assertEquals(DownType.XOR, aliceNotification.dataType)
    assertEquals(
        mapper.readTree(aliceNotification.body),
        mapper.readTree(bobNotification.body),
    )
    assertEquals("全服可见", mapper.readTree(bobNotification.body)[5].asText())

    alice.finishAndReleaseAll()
    bob.finishAndReleaseAll()
}
```

Add the required imports:

```kotlin
import com.stzb.server.protocol.DownType
```

- [ ] **Step 2: Run the protocol test and verify it fails**

Run:

```bash
./gradlew test --tests com.stzb.server.handler.GameServerHandlerProtocolTest.'world chat uses official 2100 shape and is returned by history' \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest.'world chat broadcasts the same canonical record to every online session'
```

Expected: assertion failure because the current notification is `DownType.PLAIN`, has 22 fields, and `711` returns `[]`; the broadcast test also rejects the plaintext notification.

- [ ] **Step 3: Add the history command and route**

Add this constant in `Cmd.kt` beside `CHAT`:

```kotlin
const val CHAT_HISTORY = 711
```

Add this branch after `Cmd.CHAT` in `GameServerHandler.channelRead0`:

```kotlin
Cmd.CHAT_HISTORY -> {
    logIn(msg)
    sendChatHistory(ctx)
}
```

Add these imports:

```kotlin
import com.stzb.server.game.WorldChatRecord
import com.stzb.server.game.WorldChatStore
```

- [ ] **Step 4: Replace the ad-hoc 22-field notification with one canonical 46-field record**

Replace `notification` construction in `sendChat` with:

```kotlin
val notification = WorldChatRecord(
    listOf(
        chatId, channelId, subType, userId, state.roleName, content, nowSec,
        0, "", 0, params, 0, channelIdIndeed, GameServerConfig.SERVER_ID,
        0, "", 0, 0, "", null,
        "", 0, 0, "", 0, 0, 0, 0, 0, "",
        0, "", 0, 0, -1, "", "", "", 0, "", 0,
        0, 0, 0, 0, "role_$userId",
    ),
)
if (channelId == WORLD_CHAT_CHANNEL_ID) {
    WorldChatStore.append(notification)
}
val notificationJson = mapper.writeValueAsString(notification.fields)
```

Change the notification write to:

```kotlin
DownPacket.json(
    Cmd.NOTIFY_CHAT_MSG,
    notificationJson,
    dataType = DownType.XOR,
)
```

Add the companion constant:

```kotlin
private const val WORLD_CHAT_CHANNEL_ID = 0
private const val CHAT_HISTORY_SLOT_COUNT = 18
```

Add the history response method:

```kotlin
private fun sendChatHistory(ctx: ChannelHandlerContext) {
    val slots = MutableList<Any?>(CHAT_HISTORY_SLOT_COUNT) { emptyList<Any?>() }
    slots[0] = WorldChatStore.snapshot().map(WorldChatRecord::historyEntry)
    ctx.writeAndFlush(
        DownPacket.json(
            Cmd.CHAT_HISTORY,
            mapper.writeValueAsString(slots),
            dataType = DownType.ZLIB,
        ),
    )
}
```

Extend `resetRuntimeForTests`:

```kotlin
WorldChatStore.reset()
```

- [ ] **Step 5: Run the protocol test and verify it passes**

Run:

```bash
./gradlew test --tests com.stzb.server.handler.GameServerHandlerProtocolTest.'world chat uses official 2100 shape and is returned by history' \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest.'world chat broadcasts the same canonical record to every online session'
```

Expected: `BUILD SUCCESSFUL`; the acknowledgement, XOR 46-field notification, 18-slot history response, and two-session broadcast all pass.

- [ ] **Step 6: Run both chat protocol tests and verify they pass**

Run:

```bash
./gradlew test --tests com.stzb.server.handler.GameServerHandlerProtocolTest.'world chat uses official 2100 shape and is returned by history' \
  --tests com.stzb.server.handler.GameServerHandlerProtocolTest.'world chat broadcasts the same canonical record to every online session'
```

Expected: `BUILD SUCCESSFUL`; both online sessions receive the same 46-field XOR record.

- [ ] **Step 7: Commit the handler integration and broadcast coverage**

```bash
git add src/main/kotlin/com/stzb/server/protocol/Cmd.kt \
  src/main/kotlin/com/stzb/server/handler/GameServerHandler.kt \
  src/test/kotlin/com/stzb/server/handler/GameServerHandlerProtocolTest.kt
git commit -m "fix: align world chat delivery and history"
```

### Task 3: Verify the Real TCP Protocol

**Files:**
- No source changes.

- [ ] **Step 1: Build the runnable server distribution**

Run:

```bash
./gradlew installDist
```

Expected: `BUILD SUCCESSFUL` and `build/install/stzb-server/bin/stzb-server` exists.

- [ ] **Step 2: Run an isolated TCP smoke test**

Run:

```bash
STZB_PORT=59980 STZB_DATA_DIR="$(mktemp -d /tmp/stzb-world-chat.XXXXXX)" \
  ./build/install/stzb-server/bin/stzb-server
```

From a separate terminal, authenticate a test player, send `710 [0,0,"world-smoke",[[]],0,0,"","",0,"",""]`, then request `711 []`.

Expected:

```text
2100 dataType=5 fields=46 channel=0 content=world-smoke
711 dataType=3 slots=18 worldEntries=1 content=world-smoke
```
