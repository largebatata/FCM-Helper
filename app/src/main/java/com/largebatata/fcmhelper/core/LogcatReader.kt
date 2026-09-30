package com.largebatata.fcmhelper.core

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface LogConnection : Closeable {
    fun readLine(): String?
    val permissionDenied: Boolean get() = false
    val failed: Boolean get() = false
    val pid: Long? get() = null
    val isAlive: Boolean get() = true
    val exitCode: Int? get() = null
    /** Signal the child to exit without waiting on streams owned by the reader thread. */
    fun terminate() = close()
}

fun interface LogSource {
    fun open(cursor: String): LogConnection
}

enum class ReaderState {
    Stopped, Starting, WaitingForLogs, Receiving, PermissionMissing,
    AccessDenied, Reconnecting, Error,
}

data class ReaderHealth(
    val pid: Long? = null,
    val isAlive: Boolean = false,
    val reconnectCount: Int = 0,
    val lastStartTime: Long? = null,
    val lastSuccessfulLineTime: Long? = null,
    val lastExitCode: Int? = null,
    val lastFailureType: String? = null,
)

/**
 * Owns at most one blocking logcat child. Every failure path returns to the
 * unbounded restart loop. The watchdog wakes this supervisor; it never spawns.
 */
class SupervisedLineReader(
    private val source: LogSource,
    private val permission: () -> Boolean,
    private val enabled: () -> Boolean,
    private val cursor: () -> String,
    private val elapsedClock: () -> Long,
    private val wallClock: () -> Long,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val onState: (ReaderState, Int) -> Unit,
    private val onHealth: (ReaderHealth) -> Unit = {},
    private val onDiagnostic: (String, Map<String, String>) -> Unit = { _, _ -> },
    private val onLine: (String) -> Unit,
) {
    private val active = AtomicBoolean(false)
    private val child = AtomicReference<LogConnection?>(null)
    private val restartInProgress = AtomicBoolean(false)
    private val signal = Object()
    @Volatile private var worker: Thread? = null
    @Volatile private var health = ReaderHealth()

    @Synchronized
    fun start(): Boolean {
        if (active.get() && worker?.isAlive == true) return false
        active.set(true)
        return ensureWorker()
    }

    @Synchronized
    private fun ensureWorker(): Boolean {
        if (!active.get() || !enabled() || worker?.isAlive == true) return false
        worker = Thread(::runLoop, "logcat-supervisor").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        active.set(false)
        runCatching { child.get()?.terminate() }
        synchronized(signal) { signal.notifyAll() }
        worker?.interrupt()
    }

    fun checkPermission() {
        if (active.get() && !permission()) runCatching { child.get()?.terminate() }
        synchronized(signal) { signal.notifyAll() }
    }

    /** Returns true only when a recovery was requested. */
    fun watchdog(): Boolean {
        if (!active.get() || !enabled()) return false
        val current = child.get()
        if (current != null && !runCatching { current.isAlive }.getOrDefault(false)) {
            diagnostic("READER_WATCHDOG_RECOVERY", mapOf("reason" to "dead_child"))
            runCatching { current.terminate() }
            synchronized(signal) { signal.notifyAll() }
            return true
        }
        if (current == null && !restartInProgress.get()) {
            diagnostic("READER_WATCHDOG_RECOVERY", mapOf("reason" to "missing_child"))
            if (worker?.isAlive != true) ensureWorker()
            synchronized(signal) { signal.notifyAll() }
            return true
        }
        return false
    }

    fun snapshot(): ReaderHealth = health
    fun awaitStopped(timeoutMillis: Long) = worker?.join(timeoutMillis)

    private fun runLoop() {
        var reconnects = health.reconnectCount
        var delay = 1_000L
        diagnostic("READER_SUPERVISOR_START")
        try {
            while (active.get() && enabled()) {
                if (!permission()) {
                    state(ReaderState.PermissionMissing, reconnects)
                    synchronized(signal) {
                        while (active.get() && enabled() && !permission()) signal.wait()
                    }
                    continue
                }

                val attemptStarted = elapsedClock()
                var failure = ReaderState.Reconnecting
                var failureType: String? = null
                var current: LogConnection? = null
                restartInProgress.set(true)
                diagnostic("READER_START_ATTEMPT", mapOf("reconnect" to reconnects.toString()))
                try {
                    state(ReaderState.Starting, reconnects)
                    current = source.open(cursor())
                    if (!child.compareAndSet(null, current)) {
                        throw IllegalStateException("reader child already active")
                    }
                    restartInProgress.set(false)
                    updateHealth {
                        it.copy(
                            pid = current.pid,
                            isAlive = current.isAlive,
                            reconnectCount = reconnects,
                            lastStartTime = wallClock(),
                        )
                    }
                    diagnostic("READER_START_SUCCESS", mapOf("pid" to (current.pid?.toString() ?: "unknown")))
                    if (reconnects > 0) diagnostic("READER_RESTART_SUCCESS")
                    state(ReaderState.WaitingForLogs, reconnects)
                    var firstLine = true
                    while (active.get() && enabled()) {
                        val line = try {
                            current.readLine()
                        } catch (error: Exception) {
                            failureType = error.javaClass.simpleName
                            diagnostic("READER_READ_EXCEPTION", mapOf("type" to failureType.orEmpty()))
                            break
                        }
                        if (line == null) {
                            failureType = "EOF"
                            diagnostic("READER_EOF")
                            break
                        }
                        if (!permission()) {
                            failure = ReaderState.PermissionMissing
                            break
                        }
                        updateHealth {
                            it.copy(isAlive = current.isAlive, lastSuccessfulLineTime = wallClock())
                        }
                        if (firstLine) {
                            firstLine = false
                            diagnostic("READER_FIRST_LINE")
                        }
                        state(ReaderState.Receiving, reconnects)
                        runCatching { onLine(line) }.onFailure {
                            diagnostic("READER_LINE_HANDLER_EXCEPTION", mapOf("type" to it.javaClass.simpleName))
                        }
                    }
                    failure = when {
                        current.permissionDenied -> ReaderState.AccessDenied
                        !permission() -> ReaderState.PermissionMissing
                        current.failed -> ReaderState.Error
                        else -> failure
                    }
                    val exit = current.exitCode
                    if (exit != null && exit != 0) failureType = "EXIT_NONZERO"
                    updateHealth { it.copy(isAlive = false, lastExitCode = exit) }
                    if (exit != null) diagnostic("READER_EXIT", mapOf("code" to exit.toString()))
                } catch (error: InterruptedException) {
                    if (!active.get() || !enabled()) break
                    failureType = error.javaClass.simpleName
                } catch (error: SecurityException) {
                    failure = ReaderState.AccessDenied
                    failureType = error.javaClass.simpleName
                    diagnostic("READER_START_EXCEPTION", mapOf("type" to failureType))
                } catch (error: Exception) {
                    failure = ReaderState.Error
                    failureType = error.javaClass.simpleName
                    diagnostic("READER_START_EXCEPTION", mapOf("type" to failureType))
                } finally {
                    if (current != null) {
                        child.compareAndSet(current, null)
                        runCatching { current.terminate() }
                        runCatching { current.close() }
                    }
                    updateHealth {
                        it.copy(
                            pid = null,
                            isAlive = false,
                            lastFailureType = failureType ?: it.lastFailureType,
                        )
                    }
                }

                if (!active.get() || !enabled()) break
                if (failure == ReaderState.PermissionMissing) {
                    restartInProgress.set(false)
                    continue
                }
                if (elapsedClock() - attemptStarted >= STABLE_CONNECTION_MS) delay = 1_000L
                reconnects++
                updateHealth { it.copy(reconnectCount = reconnects, lastFailureType = failureType) }
                state(failure, reconnects)
                diagnostic("READER_RESTART_SCHEDULED", mapOf("delayMs" to delay.toString()))
                restartInProgress.set(true)
                sleep(delay)
                delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MS)
                restartInProgress.set(false)
            }
        } catch (_: InterruptedException) {
            // Explicit stop interrupts backoff or a permission wait.
        } catch (error: Throwable) {
            diagnostic("READER_SUPERVISOR_EXCEPTION", mapOf("type" to error.javaClass.simpleName))
        } finally {
            restartInProgress.set(false)
            child.getAndSet(null)?.let {
                runCatching { it.terminate() }
                runCatching { it.close() }
            }
            updateHealth { it.copy(pid = null, isAlive = false) }
            state(ReaderState.Stopped, reconnects)
        }
    }

    private fun state(value: ReaderState, reconnects: Int) {
        runCatching { onState(value, reconnects) }
    }

    private fun updateHealth(block: (ReaderHealth) -> ReaderHealth) {
        health = block(health)
        runCatching { onHealth(health) }
    }

    private fun diagnostic(event: String, fields: Map<String, String> = emptyMap()) {
        runCatching { onDiagnostic(event, fields) }
    }

    companion object {
        private const val STABLE_CONNECTION_MS = 30_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}

/** Adds FCM parsing and replay protection to the shared process supervisor. */
class LogcatReader(
    source: LogSource,
    permission: () -> Boolean,
    initialCursor: String,
    clock: () -> Long,
    wallClock: () -> Long = System::currentTimeMillis,
    enabled: () -> Boolean = { true },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    onState: (ReaderState, Int) -> Unit,
    onHealth: (ReaderHealth) -> Unit = {},
    onDiagnostic: (String, Map<String, String>) -> Unit = { _, _ -> },
    onRecord: (FcmParser.Record) -> Unit,
) {
    private val guard = ReplayGuard(initialCursor)
    private val supervisor = SupervisedLineReader(
        source = source,
        permission = permission,
        enabled = enabled,
        cursor = { guard.cursor },
        elapsedClock = clock,
        wallClock = wallClock,
        sleep = sleep,
        onState = onState,
        onHealth = onHealth,
        onDiagnostic = onDiagnostic,
        onLine = { raw ->
            val record = FcmParser.record(raw)
            if (record != null && guard.accept(raw, record.timestamp)) onRecord(record)
        },
    )

    fun start(): Boolean = supervisor.start()
    fun stop() = supervisor.stop()
    fun checkPermission() = supervisor.checkPermission()
    fun watchdog(): Boolean = supervisor.watchdog()
    fun snapshot(): ReaderHealth = supervisor.snapshot()
    fun awaitStopped(timeoutMillis: Long) = supervisor.awaitStopped(timeoutMillis)
}
