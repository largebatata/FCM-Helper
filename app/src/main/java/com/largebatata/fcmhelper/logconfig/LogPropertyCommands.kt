package com.largebatata.fcmhelper.logconfig

import com.largebatata.fcmhelper.core.LogProperty
import java.util.concurrent.TimeUnit

/** Tiny bounded subprocesses; never a shell, hidden API, persistent property, or logcat dump. */
internal object LogPropertyCommands {
    private const val TIMEOUT_MS = 2_000L
    const val FAILED = -1
    const val TIMEOUT = -2

    fun read(property: LogProperty): String? = runCatching {
        val process = ProcessBuilder(property.readCommand()).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
            else process.inputStream.bufferedReader().use { it.readLine().orEmpty().take(96).trim() }
        } finally {
            process.destroyForcibly()
            process.inputStream.close()
        }
    }.getOrNull()

    fun write(property: LogProperty): Int = runCatching {
        val process = ProcessBuilder(property.repairCommand()).redirectErrorStream(true).start()
        try {
            if (process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.exitValue() else TIMEOUT
        } finally {
            process.destroyForcibly()
            process.inputStream.close()
        }
    }.getOrDefault(FAILED)
}
