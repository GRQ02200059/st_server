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
        const val FIELD_COUNT = 48
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
