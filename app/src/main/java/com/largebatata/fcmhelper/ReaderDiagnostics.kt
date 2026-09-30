package com.largebatata.fcmhelper

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReaderDiagnostics(context: Context) {
    private val current = File(context.filesDir, CURRENT_NAME)
    private val previous = File(context.filesDir, PREVIOUS_NAME)
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    @Synchronized
    fun record(event: String, fields: Map<String, String> = emptyMap()) {
        val safeEvent = safe(event)
        val safeFields = fields.entries.joinToString(" ") { (key, value) ->
            "${safe(key)}=${safe(value)}"
        }
        val line = buildString {
            append(formatter.format(Date()))
            append(" elapsedMs=")
            append(SystemClock.elapsedRealtime())
            append(" event=")
            append(safeEvent)
            if (safeFields.isNotEmpty()) {
                append(' ')
                append(safeFields)
            }
            append('\n')
        }
        val bytes = line.toByteArray(Charsets.UTF_8)
        runCatching {
            if (current.length() + bytes.size > MAX_BYTES) rotate()
            FileOutputStream(current, true).use { it.write(bytes) }
        }
    }

    /** Returns only the bounded, privacy-minimized records written by [record]. */
    @Synchronized
    fun readForExport(): List<Pair<String, String>> = listOf(previous, current)
        .filter(File::isFile)
        .map { it.name to runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }

    private fun rotate() {
        if (previous.exists()) previous.delete()
        if (current.exists() && !current.renameTo(previous)) {
            current.copyTo(previous, overwrite = true)
            current.delete()
        }
    }

    private fun safe(value: String): String = value
        .take(80)
        .map { if (it.isLetterOrDigit() || it in "._:-") it else '_' }
        .joinToString("")

    companion object {
        const val CURRENT_NAME = "reader-diagnostics.log"
        const val PREVIOUS_NAME = "reader-diagnostics.1.log"
        const val MAX_BYTES = 256 * 1024L
    }
}

class ListeningPreference(context: Context) {
    private val preferences = context.getSharedPreferences("monitor_state", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).commit()
        }

    companion object {
        private const val KEY_ENABLED = "listening_enabled"
    }
}
