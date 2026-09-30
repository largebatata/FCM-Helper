package com.largebatata.fcmhelper.core

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorTest {
    private class Idle(private val alive: AtomicBoolean = AtomicBoolean(true)) : LogConnection {
        val entered = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override val isAlive: Boolean get() = alive.get()
        override fun readLine(): String? {
            entered.countDown()
            closed.await()
            return null
        }
        override fun terminate() {
            alive.set(false)
            closed.countDown()
        }
        override fun close() = terminate()
    }

    private class Eof(
        private val code: Int,
        private val line: String? = null,
    ) : LogConnection {
        private var emitted = false
        override val isAlive: Boolean get() = false
        override val exitCode: Int get() = code
        override val failed: Boolean get() = code != 0
        override fun readLine(): String? =
            if (!emitted && line != null) line.also { emitted = true } else null
        override fun close() = Unit
    }

    private fun supervisor(
        source: LogSource,
        enabled: () -> Boolean = { true },
        sleep: (Long) -> Unit = {},
        onDiagnostic: (String, Map<String, String>) -> Unit = { _, _ -> },
    ) = SupervisedLineReader(
        source = source,
        permission = { true },
        enabled = enabled,
        cursor = { "0" },
        elapsedClock = { 0 },
        wallClock = { 1 },
        sleep = sleep,
        onState = { _, _ -> },
        onDiagnostic = onDiagnostic,
        onLine = {},
    )

    private fun await(latch: CountDownLatch) {
        assertTrue("worker timed out", latch.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun eofAndZeroOrNonzeroExitAlwaysRestart() {
        val opens = AtomicInteger()
        val idle = Idle()
        val reader = supervisor(LogSource {
            when (opens.incrementAndGet()) {
                1 -> Eof(0)
                2 -> Eof(7)
                else -> idle
            }
        })
        try {
            reader.start()
            await(idle.entered)
            assertEquals(3, opens.get())
            assertEquals(2, reader.snapshot().reconnectCount)
        } finally {
            reader.stop()
        }
    }

    @Test
    fun startFailuresContinueForeverAtThirtySecondCap() {
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val opens = AtomicInteger()
        val idle = Idle()
        val reader = supervisor(
            source = LogSource {
                if (opens.incrementAndGet() <= 9) throw IOException("synthetic")
                idle
            },
            sleep = { delays.add(it) },
        )
        try {
            reader.start()
            await(idle.entered)
            assertEquals(
                listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L),
                delays.toList(),
            )
        } finally {
            reader.stop()
        }
    }

    @Test
    fun watchdogTerminatesDeadChildAndSupervisorRestartsIt() {
        val dead = Idle(AtomicBoolean(false))
        val replacement = Idle()
        val opens = AtomicInteger()
        val reader = supervisor(LogSource { if (opens.incrementAndGet() == 1) dead else replacement })
        try {
            reader.start()
            await(dead.entered)
            assertTrue(reader.watchdog())
            await(replacement.entered)
            assertEquals(2, opens.get())
        } finally {
            reader.stop()
        }
    }

    @Test
    fun watchdogLeavesAliveChildAndExistingRestartAlone() {
        val alive = Idle()
        val reader = supervisor(LogSource { alive })
        try {
            reader.start()
            await(alive.entered)
            assertFalse(reader.watchdog())
        } finally {
            reader.stop()
        }

        val sleeping = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retrying = supervisor(
            source = LogSource { throw IOException("synthetic") },
            sleep = {
                sleeping.countDown()
                release.await()
            },
        )
        try {
            retrying.start()
            await(sleeping)
            assertFalse(retrying.watchdog())
        } finally {
            release.countDown()
            retrying.stop()
        }
    }

    @Test
    fun watchdogRestartsUnexpectedlyEndedSupervisorWithMissingChild() {
        val firstSleep = CountDownLatch(1)
        val replacement = Idle()
        val opens = AtomicInteger()
        val reader = supervisor(
            source = LogSource {
                if (opens.incrementAndGet() == 1) throw IOException("synthetic")
                replacement
            },
            sleep = {
                firstSleep.countDown()
                throw AssertionError("synthetic supervisor failure")
            },
        )
        try {
            reader.start()
            await(firstSleep)
            repeat(100) {
                reader.watchdog()
                if (replacement.entered.count == 0L) return@repeat
                Thread.sleep(10)
            }
            await(replacement.entered)
            assertEquals(2, opens.get())
        } finally {
            reader.stop()
        }
    }

    @Test
    fun disabledOrStoppedReaderNeverRestartsAndNeverHasTwoChildren() {
        val enabled = AtomicBoolean(true)
        val activeChildren = AtomicInteger()
        val maxChildren = AtomicInteger()
        val idle = object : LogConnection {
            val entered = CountDownLatch(1)
            val closed = CountDownLatch(1)
            override val isAlive: Boolean get() = closed.count > 0
            override fun readLine(): String? {
                entered.countDown()
                closed.await()
                return null
            }
            override fun terminate() {
                closed.countDown()
                activeChildren.decrementAndGet()
            }
            override fun close() = Unit
        }
        val opens = AtomicInteger()
        val reader = supervisor(
            source = LogSource {
                opens.incrementAndGet()
                val now = activeChildren.incrementAndGet()
                maxChildren.updateAndGet { old -> maxOf(old, now) }
                idle
            },
            enabled = enabled::get,
        )
        reader.start()
        await(idle.entered)
        repeat(10) { assertFalse(reader.watchdog()) }
        enabled.set(false)
        reader.stop()
        reader.awaitStopped(1_000)
        assertEquals(1, opens.get())
        assertEquals(1, maxChildren.get())
        assertFalse(reader.watchdog())
    }
}
