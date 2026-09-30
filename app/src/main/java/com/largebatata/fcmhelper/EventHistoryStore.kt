package com.largebatata.fcmhelper

import android.content.Context
import com.largebatata.fcmhelper.core.EventHistoryEntry
import com.largebatata.fcmhelper.core.EventHistoryPolicy
import com.largebatata.fcmhelper.core.WeChatFcmType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class EventHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(timestamp: Long, type: WeChatFcmType): List<EventHistoryEntry> =
        save(EventHistoryPolicy.normalize(read() + EventHistoryEntry(timestamp, type), timestamp))

    @Synchronized
    fun loadAndPrune(now: Long = System.currentTimeMillis()): List<EventHistoryEntry> =
        save(EventHistoryPolicy.normalize(read(), now))

    @Synchronized
    fun clear(): List<EventHistoryEntry> {
        preferences.edit().remove(KEY_EVENTS).apply()
        return emptyList()
    }

    private fun read(): List<EventHistoryEntry> = preferences.getString(KEY_EVENTS, null)
        ?.lineSequence()
        ?.mapNotNull(::decode)
        ?.toList()
        .orEmpty()

    private fun save(entries: List<EventHistoryEntry>): List<EventHistoryEntry> {
        preferences.edit().putString(
            KEY_EVENTS,
            entries.joinToString("\n") { "${it.timestamp}|${it.type.name}" },
        ).apply()
        return entries
    }

    private fun decode(line: String): EventHistoryEntry? {
        val fields = line.split('|', limit = 2)
        if (fields.size != 2) return null
        val timestamp = fields[0].toLongOrNull() ?: return null
        val type = runCatching { WeChatFcmType.valueOf(fields[1]) }.getOrNull() ?: return null
        return EventHistoryEntry(timestamp, type)
    }

    private companion object {
        const val PREFS = "event_history"
        const val KEY_EVENTS = "events"
    }
}

object EventHistoryRepository {
    private val mutableEntries = MutableStateFlow<List<EventHistoryEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    private lateinit var store: EventHistoryStore

    @Synchronized
    fun initialize(context: Context) {
        if (::store.isInitialized) return
        store = EventHistoryStore(context.applicationContext)
        mutableEntries.value = store.loadAndPrune()
    }

    @Synchronized
    fun record(timestamp: Long, type: WeChatFcmType) {
        if (!::store.isInitialized) return
        mutableEntries.value = store.add(timestamp, type)
    }

    @Synchronized
    fun refresh(now: Long = System.currentTimeMillis()) {
        if (!::store.isInitialized) return
        mutableEntries.value = store.loadAndPrune(now)
    }

    @Synchronized
    fun clear() {
        if (!::store.isInitialized) return
        mutableEntries.value = store.clear()
    }
}
