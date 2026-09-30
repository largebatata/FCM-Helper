package com.largebatata.fcmhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventHistoryTest {
    private val now = 1_800_000_000_000L

    @Test
    fun removesEntriesOlderThan24Hours() {
        val result = EventHistoryPolicy.normalize(
            listOf(
                EventHistoryEntry(now - EventHistoryPolicy.RETENTION_MS - 1, WeChatFcmType.MESSAGE),
                EventHistoryEntry(now - EventHistoryPolicy.RETENTION_MS, WeChatFcmType.CALL),
            ),
            now,
        )
        assertEquals(listOf(WeChatFcmType.CALL), result.map(EventHistoryEntry::type))
    }

    @Test
    fun keepsAtMost200Entries() {
        val entries = (0L..250L).map { EventHistoryEntry(now - it, WeChatFcmType.MESSAGE) }
        assertEquals(200, EventHistoryPolicy.normalize(entries, now).size)
    }

    @Test
    fun sortsNewestFirst() {
        val result = EventHistoryPolicy.normalize(
            listOf(
                EventHistoryEntry(now - 30, WeChatFcmType.MESSAGE),
                EventHistoryEntry(now - 10, WeChatFcmType.PC_LOGIN),
                EventHistoryEntry(now - 20, WeChatFcmType.CALL),
            ),
            now,
        )
        assertEquals(listOf(now - 10, now - 20, now - 30), result.map(EventHistoryEntry::timestamp))
    }

    @Test
    fun modelContainsOnlyTimestampAndType() {
        val entry = EventHistoryEntry(now, WeChatFcmType.UNKNOWN)
        assertEquals(now, entry.timestamp)
        assertEquals(WeChatFcmType.UNKNOWN, entry.type)
        assertTrue(EventHistoryEntry::class.java.declaredFields.map { it.name }.containsAll(listOf("timestamp", "type")))
    }
}
