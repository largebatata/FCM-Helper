package com.largebatata.fcmhelper.core

data class EventHistoryEntry(
    val timestamp: Long,
    val type: WeChatFcmType,
)

object EventHistoryPolicy {
    const val RETENTION_MS = 24L * 60L * 60L * 1_000L
    const val MAX_ENTRIES = 200

    fun normalize(
        entries: List<EventHistoryEntry>,
        now: Long,
        maxEntries: Int = MAX_ENTRIES,
    ): List<EventHistoryEntry> {
        val oldest = now - RETENTION_MS
        return entries
            .asSequence()
            .filter { it.timestamp in oldest..now }
            .sortedByDescending(EventHistoryEntry::timestamp)
            .take(maxEntries.coerceAtLeast(0))
            .toList()
    }
}
