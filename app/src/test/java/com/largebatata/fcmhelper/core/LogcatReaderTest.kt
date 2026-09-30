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

class LogcatReaderTest {
    private class Idle : LogConnection {
        val entered = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override fun readLine(): String? {
            entered.countDown()
            closed.await()
            return null
        }
        override fun close() { closed.countDown() }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("worker timed out", latch.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun singleInstanceIdleReadNeverRetriesAndStopUnblocks() {
        val idle = Idle()
        val opens = AtomicInteger()
        val sleeps = AtomicInteger()
        val reader = LogcatReader(
            LogSource { opens.incrementAndGet(); idle }, { true }, "0.0", { Long.MAX_VALUE },
            sleep = { sleeps.incrementAndGet() }, onState = { _, _ -> }, onRecord = {},
        )
        try {
            assertTrue(reader.start())
            await(idle.entered)
            assertFalse(reader.start())
            assertEquals(1, opens.get())
            assertEquals(0, sleeps.get())
        } finally {
            reader.stop()
            reader.awaitStopped(1_000)
        }
    }

    @Test
    fun eofAndExceptionUseBoundedExponentialBackoff() {
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val opened = AtomicInteger()
        val done = CountDownLatch(1)
        val idle = Idle()
        val reader = LogcatReader(
            LogSource {
                when (opened.incrementAndGet()) {
                    1 -> throw IOException("synthetic")
                    in 2..8 -> object : LogConnection {
                        override fun readLine(): String? = null
                        override fun close() = Unit
                    }
                    else -> { done.countDown(); idle }
                }
            },
            { true }, "0.0", { 0L }, sleep = { delays.add(it) }, onState = { _, _ -> }, onRecord = {},
        )
        try {
            reader.start()
            await(done)
            assertEquals(
                listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
                delays.toList(),
            )
        } finally {
            reader.stop()
            reader.awaitStopped(1_000)
        }
    }

    @Test
    fun missingPermissionWaitsForChangeWithoutOpeningOrPolling() {
        val allowed = AtomicBoolean(false)
        val missing = CountDownLatch(1)
        val idle = Idle()
        val opens = AtomicInteger()
        val sleeps = AtomicInteger()
        val reader = LogcatReader(
            LogSource { opens.incrementAndGet(); idle }, allowed::get, "0.0", { 0L },
            sleep = { sleeps.incrementAndGet() },
            onState = { state, _ -> if (state == ReaderState.PermissionMissing) missing.countDown() },
            onRecord = {},
        )
        try {
            reader.start()
            await(missing)
            assertEquals(0, opens.get())
            assertEquals(0, sleeps.get())
            allowed.set(true)
            reader.checkPermission()
            await(idle.entered)
            assertEquals(1, opens.get())
        } finally {
            reader.stop()
            reader.awaitStopped(1_000)
        }
    }

    @Test
    fun securityExceptionAndFailedChildAreVisible() {
        val states = Collections.synchronizedList(mutableListOf<ReaderState>())
        val count = AtomicInteger()
        val idle = Idle()
        val reader = LogcatReader(
            LogSource {
                when (count.incrementAndGet()) {
                    1 -> throw SecurityException()
                    2 -> object : LogConnection {
                        override val failed = true
                        override fun readLine(): String? = null
                        override fun close() = Unit
                    }
                    else -> idle
                }
            },
            { true }, "0.0", { 0L }, sleep = {}, onState = { state, _ -> states.add(state) }, onRecord = {},
        )
        try {
            reader.start()
            await(idle.entered)
            assertTrue(states.contains(ReaderState.AccessDenied))
            assertTrue(states.contains(ReaderState.Error))
        } finally {
            reader.stop()
            reader.awaitStopped(1_000)
        }
    }

    @Test
    fun reconnectUsesCursorAndSuppressesReplay() {
        val raw = "1700000000.123 123 456 D GmsGcmMcsSvc: Adding app com.tencent.mm to the temp allowlist"
        val cursors = Collections.synchronizedList(mutableListOf<String>())
        val records = AtomicInteger()
        val idle = Idle()
        val reader = LogcatReader(
            LogSource { cursor ->
                cursors.add(cursor)
                if (cursors.size < 3) object : LogConnection {
                    private var sent = false
                    override fun readLine(): String? = if (!sent) { sent = true; raw } else null
                    override fun close() = Unit
                } else idle
            },
            { true }, "1700000000.000", { 0L }, sleep = {},
            onState = { _, _ -> }, onRecord = { records.incrementAndGet() },
        )
        try {
            reader.start()
            await(idle.entered)
            assertEquals(1, records.get())
            assertEquals(listOf("1700000000.000", "1700000000.123", "1700000000.123"), cursors.toList())
        } finally {
            reader.stop()
            reader.awaitStopped(1_000)
        }
    }

    @Test
    fun permissionRevocationClosesQuietReadAndGrantReopens() {
        val allowed = AtomicBoolean(true)
        val first = Idle()
        val second = Idle()
        val opens = AtomicInteger()
        val missing = CountDownLatch(1)
        val reader = LogcatReader(
            LogSource { if (opens.incrementAndGet() == 1) first else second },
            allowed::get, "0.0", { 0L }, sleep = {},
            onState = { state, _ -> if (state == ReaderState.PermissionMissing) missing.countDown() },
            onRecord = {},
        )
        try {
            reader.start()
            await(first.entered)
            allowed.set(false)
            reader.checkPermission()
            await(missing)
            allowed.set(true)
            reader.checkPermission()
            await(second.entered)
            assertEquals(2, opens.get())
        } finally {
            reader.stop()
            reader.awaitStopped(1_000)
        }
    }
}
