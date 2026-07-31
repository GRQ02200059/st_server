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
