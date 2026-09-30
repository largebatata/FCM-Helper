package com.largebatata.fcmhelper.core

import java.security.MessageDigest
import java.util.ArrayDeque

enum class WeChatFcmType { MESSAGE, CALL, PC_LOGIN, UNKNOWN }

data class WeChatFcmEvent(
    val timestamp: Long,
    val sourceTag: String,
    val category: String,
    val seqLength: Int?,
    val hasRdata: Boolean,
    val type: WeChatFcmType,
)

sealed interface Signal {
    data class Data(
        val seq: String?,
        val hasRdata: Boolean,
        val timestamp: Long = 0L,
        val sourceTag: String = "GmsGcmMcsInput",
        val category: String = WECHAT_CATEGORY,
    ) : Signal

    data class Arrival(
        val timestamp: Long = 0L,
        val sourceTag: String = "GmsGcmMcsSvc",
        val category: String = WECHAT_CATEGORY,
    ) : Signal
}

const val WECHAT_CATEGORY = "com.tencent.mm"

/** An observed heuristic that is deliberately replaceable; it is not a WeChat protocol. */
fun interface FcmClassifier {
    fun classify(data: Signal.Data): WeChatFcmType
}

class SeqLengthClassifier : FcmClassifier {
    override fun classify(data: Signal.Data): WeChatFcmType {
        val seq = data.seq ?: return WeChatFcmType.UNKNOWN
        if (seq.isEmpty() || !seq.all { it in '0'..'9' }) return WeChatFcmType.UNKNOWN
        return when {
            seq == "0" -> WeChatFcmType.PC_LOGIN
            data.hasRdata && seq.length in 9..10 -> WeChatFcmType.CALL
            seq.length in 18..19 -> WeChatFcmType.MESSAGE
            else -> WeChatFcmType.UNKNOWN
        }
    }
}

object FcmParser {
    private const val INPUT_TAG = "GmsGcmMcsInput"
    private const val SERVICE_TAG = "GmsGcmMcsSvc"
    private const val DATA_PREFIX = "Incoming message: DataMessageStanza{"
    private const val ARRIVAL_TEXT = "Adding app com.tencent.mm to the temp allowlist"

    data class Record(val timestamp: String, val tag: String, val body: String) {
        val timestampMillis: Long
            get() = timestamp.toBigDecimal().movePointRight(3).toLong()
    }

    fun record(raw: String): Record? = runCatching {
        val parts = raw.trimStart().split(Regex("\\s+"), limit = 6)
        if (parts.size != 6 || parts[0].toBigDecimalOrNull() == null ||
            parts[1].toIntOrNull() == null || parts[2].toIntOrNull() == null ||
            parts[3].length != 1
        ) return null
        val tag = parts[4].removeSuffix(":")
        if (tag != INPUT_TAG && tag != SERVICE_TAG) return null
        Record(parts[0], tag, parts[5])
    }.getOrNull()

    fun signal(record: Record): Signal? = runCatching {
        when (record.tag) {
            SERVICE_TAG -> if (record.body == ARRIVAL_TEXT || record.body.startsWith("$ARRIVAL_TEXT ")) {
                Signal.Arrival(timestamp = record.timestampMillis)
            } else null

            INPUT_TAG -> parseData(record)
            else -> null
        }
    }.getOrNull()

    private fun parseData(record: Record): Signal.Data? {
        if (!record.body.startsWith(DATA_PREFIX) || !record.body.trimEnd().endsWith('}')) return null
        if (scalarField(record.body, "category") != WECHAT_CATEGORY) return null

        var hasRdata = false
        val seqValues = ArrayList<String>(1)
        var position = 0
        while (true) {
            val start = record.body.indexOf("AppData{", position)
            if (start < 0) break
            val end = record.body.indexOf('}', start + 8)
            if (end < 0) break
            val entry = record.body.substring(start + 8, end)
            when (scalarField(entry, "key")) {
                "seq" -> scalarField(entry, "value_")?.let(seqValues::add)
                "rdata" -> hasRdata = true
            }
            position = end + 1
        }

        return Signal.Data(
            seq = seqValues.singleOrNull(),
            hasRdata = hasRdata,
            timestamp = record.timestampMillis,
        )
    }

    private fun scalarField(text: String, name: String): String? {
        var from = 0
        while (true) {
            val key = text.indexOf(name, from)
            if (key < 0) return null
            val beforeIsBoundary = key == 0 || text[key - 1].isWhitespace() || text[key - 1] in "{,["
            var cursor = key + name.length
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (beforeIsBoundary && cursor < text.length && text[cursor] == '=') {
                cursor++
                while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                val start = cursor
                while (cursor < text.length && !text[cursor].isWhitespace() && text[cursor] !in ",}]") cursor++
                return text.substring(start, cursor)
            }
            from = key + name.length
        }
    }
}

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Only hashes and a log cursor survive reconnects, never raw lines or rdata. */
class ReplayGuard(initialCursor: String, private val capacity: Int = 2048) {
    var cursor: String = initialCursor
        private set
    private val seen = LinkedHashSet<String>()

    fun accept(raw: String, timestamp: String): Boolean {
        if (timestamp.toBigDecimal() < cursor.toBigDecimal()) return false
        val hash = digest(raw)
        if (!seen.add(hash)) return false
        if (timestamp.toBigDecimal() > cursor.toBigDecimal()) cursor = timestamp
        while (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}

class ReminderGate {
    private var messageAt: Long? = null
    private var callAt: Long? = null
    private var pcLoginAt: Long? = null

    fun allow(type: WeChatFcmType, now: Long): Boolean {
        if (type == WeChatFcmType.CALL) {
            if (callAt?.let { now - it < 10_000 } == true) return false
            callAt = now
            return true
        }
        if (type == WeChatFcmType.PC_LOGIN) {
            if (pcLoginAt?.let { now - it < 10_000 } == true) return false
            pcLoginAt = now
            return true
        }
        if (callAt?.let { now - it < 10_000 } == true) return false
        if (messageAt?.let { now - it < 5_000 } == true) return false
        messageAt = now
        return true
    }
}

/** Calls are serialized by the service. Timing uses a monotonic clock; event timestamps come from logcat. */
class FcmPipeline(
    private val classifier: FcmClassifier = SeqLengthClassifier(),
    private val onEvent: (WeChatFcmEvent) -> Unit,
    private val onReminder: (WeChatFcmEvent) -> Unit,
    private val mergeWindow: Long = MERGE_WINDOW_MS,
) {
    private data class Pending(val time: Long, val event: WeChatFcmEvent)
    private val reminders = ArrayDeque<Pending>()
    private data class SeenEvent(val time: Long, val type: WeChatFcmType)
    private val eventKeys = LinkedHashMap<String, SeenEvent>()
    private val gate = ReminderGate()

    fun accept(signal: Signal, now: Long) {
        flush(now)
        when (signal) {
            is Signal.Arrival -> Unit

            is Signal.Data -> {
                val type = classifier.classify(signal)
                val key = signal.seq?.let(::digest)
                val duplicateWindow = if (type == WeChatFcmType.CALL || type == WeChatFcmType.PC_LOGIN) 10_000L else 5_000L
                if (key != null && eventKeys[key]?.let { now - it.time < duplicateWindow } == true) return
                if (key != null) {
                    eventKeys[key] = SeenEvent(now, type)
                    while (eventKeys.size > 512) eventKeys.remove(eventKeys.keys.first())
                }
                val event = event(signal, type)
                onEvent(event)
                if (event.type == WeChatFcmType.CALL) {
                    reminders.removeIf {
                        it.event.type == WeChatFcmType.MESSAGE || it.event.type == WeChatFcmType.UNKNOWN
                    }
                    if (gate.allow(event.type, now)) onReminder(event)
                } else {
                    reminders.add(Pending(now, event))
                    trim(reminders)
                }
            }
        }
    }

    fun flush(now: Long) {
        eventKeys.entries.removeAll { now - it.value.time >= 10_000 }
        while (reminders.isNotEmpty() && now - reminders.first.time >= mergeWindow) {
            val pending = reminders.removeFirst()
            val event = pending.event
            if (gate.allow(event.type, pending.time)) onReminder(event)
        }
    }

    private fun event(signal: Signal.Data, type: WeChatFcmType) = WeChatFcmEvent(
        timestamp = signal.timestamp,
        sourceTag = signal.sourceTag,
        category = signal.category,
        seqLength = signal.seq?.length,
        hasRdata = signal.hasRdata,
        type = type,
    )

    private fun <T> trim(queue: ArrayDeque<T>) {
        while (queue.size > 128) queue.removeFirst()
    }

    companion object {
        const val MERGE_WINDOW_MS = 1_200L
    }
}

