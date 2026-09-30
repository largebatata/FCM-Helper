package com.largebatata.fcmhelper

import com.largebatata.fcmhelper.core.LogConnection
import com.largebatata.fcmhelper.core.LogSource
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Oversized payload lines are discarded rather than retained or exposed. */
internal class BoundedLines(input: InputStream) {
    private val reader = InputStreamReader(input, Charsets.UTF_8).buffered()

    fun next(): String? {
        while (true) {
            val line = StringBuilder()
            var oversized = false
            while (true) {
                val c = reader.read()
                if (c == -1) return if (line.isEmpty() || oversized) null else line.toString()
                if (c == '\n'.code) break
                if (line.length < MAX_LINE_LENGTH) line.append(c.toChar()) else oversized = true
            }
            if (!oversized) return line.toString().trimEnd('\r')
        }
    }

    companion object {
        private const val MAX_LINE_LENGTH = 65_536
    }
}

/**
 * stderr is merged into stdout. This removes the old concurrent stderr reader
 * whose stream close could strand the supervisor after the child disappeared.
 */
internal class AndroidProcessLogConnection(command: String) : LogConnection {
    private val process = ProcessBuilder(
        "/system/bin/sh",
        "-c",
        "echo __HELPER_PID=\$\$; exec $command",
    ).redirectErrorStream(true).start()
    private val lines = BoundedLines(process.inputStream)
    private var pendingLine: String? = lines.next()

    @Volatile
    override var permissionDenied = false
        private set

    override val pid: Long? = pendingLine
        ?.takeIf { it.startsWith("__HELPER_PID=") }
        ?.removePrefix("__HELPER_PID=")
        ?.toLongOrNull()
        .also { if (it != null) pendingLine = null }

    override val isAlive: Boolean get() = process.isAlive
    override val exitCode: Int?
        get() = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
    override val failed: Boolean
        get() = !permissionDenied && exitCode?.let { it != 0 } == true

    override fun readLine(): String? {
        val line = pendingLine?.also { pendingLine = null } ?: lines.next() ?: return null
        if (line.contains("permission denied", true) ||
            line.contains("not permitted", true) ||
            line.contains("not allowed", true)
        ) {
            permissionDenied = true
            terminate()
        }
        return line
    }

    override fun terminate() {
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
        }
    }

    override fun close() {
        terminate()
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
    }
}

class AndroidLogSource : LogSource {
    override fun open(cursor: String): LogConnection = AndroidProcessLogConnection(
        "/system/bin/logcat -b main -v epoch -T 100 " +
            "GmsGcmMcsInput:V GmsGcmMcsSvc:V '*:S'",
    )
}
