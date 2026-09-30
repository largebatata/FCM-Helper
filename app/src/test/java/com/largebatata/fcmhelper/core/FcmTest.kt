package com.largebatata.fcmhelper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmTest {
    private fun input(seq: String?, rdata: Boolean = false, category: String = WECHAT_CATEGORY): String =
        "1700000000.123 1234 5678 D GmsGcmMcsInput: Incoming message: DataMessageStanza{category=$category, appData=[" +
            (seq?.let { "AppData{key=seq, value_=$it}, " } ?: "") +
            (if (rdata) "AppData{key=rdata, value_=REDACTED}" else "") + "]}"

    private fun data(raw: String): Signal.Data = FcmParser.signal(FcmParser.record(raw)!!) as Signal.Data
    private val classifier = SeqLengthClassifier()

    @Test
    fun zeroSeqIsPcLoginBeforeAllOtherRules() {
        assertEquals(WeChatFcmType.PC_LOGIN, classifier.classify(data(input("0"))))
        assertEquals(WeChatFcmType.PC_LOGIN, classifier.classify(data(input("0", rdata = true))))
    }

    @Test
    fun eighteenAndNineteenDigitSeqAreMessages() {
        for (seq in listOf("123456789012345678", "1234567890123456789")) {
            assertEquals(WeChatFcmType.MESSAGE, classifier.classify(data(input(seq))))
        }
    }

    @Test
    fun longMessageMayContainRdata() {
        assertEquals(
            WeChatFcmType.MESSAGE,
            classifier.classify(data(input("123456789012345678", rdata = true))),
        )
    }

    @Test
    fun nineAndTenDigitsWithRdataAreCalls() {
        for (seq in listOf("123456789", "1234567890")) {
            assertEquals(WeChatFcmType.CALL, classifier.classify(data(input(seq, rdata = true))))
        }
    }

    @Test
    fun shortSeqWithoutRdataIsNotCall() {
        assertEquals(WeChatFcmType.UNKNOWN, classifier.classify(data(input("123456789"))))
    }

    @Test
    fun foreignCategoryIsIgnored() {
        assertNull(FcmParser.signal(FcmParser.record(input("123456789", true, "com.example.other"))!!))
        assertNull(FcmParser.signal(FcmParser.record(input("123456789", true, "com.tencent.mm.extra"))!!))
    }

    @Test
    fun missingMalformedAndAmbiguousSeqAreUnknown() {
        for (seq in listOf(null, "", "-123456789", "12345678x", "12345678", "12345678901")) {
            assertEquals(WeChatFcmType.UNKNOWN, classifier.classify(data(input(seq, rdata = true))))
        }
        val duplicate = input("123456789", true)
            .replace("AppData{key=seq", "AppData{key=seq, value_=1234567890}, AppData{key=seq")
        assertEquals(WeChatFcmType.UNKNOWN, classifier.classify(data(duplicate)))
    }

    @Test
    fun modelContainsOnlyRequiredRedactedMetadata() {
        val events = mutableListOf<WeChatFcmEvent>()
        val pipeline = FcmPipeline(onEvent = events::add, onReminder = {})
        pipeline.accept(data(input("123456789", true)), 1_000)
        val event = events.single()
        assertEquals(1_700_000_000_123L, event.timestamp)
        assertEquals("GmsGcmMcsInput", event.sourceTag)
        assertEquals(WECHAT_CATEGORY, event.category)
        assertEquals(9, event.seqLength)
        assertTrue(event.hasRdata)
        assertEquals(WeChatFcmType.CALL, event.type)
    }

    @Test
    fun allowlistSignalIsExact() {
        val raw = "1700000000.124 1234 5678 D GmsGcmMcsSvc: Adding app com.tencent.mm to the temp allowlist"
        assertTrue(FcmParser.signal(FcmParser.record(raw)!!) is Signal.Arrival)
        assertNull(FcmParser.signal(FcmParser.record(raw.replace("com.tencent.mm", "com.tencent.mm.extra"))!!))
    }

    @Test
    fun allowlistSignalNeverCreatesTypedEventOrReminder() {
        val events = mutableListOf<WeChatFcmEvent>()
        val reminders = mutableListOf<WeChatFcmEvent>()
        val pipeline = FcmPipeline(onEvent = events::add, onReminder = reminders::add)
        pipeline.accept(Signal.Arrival(), 0)
        pipeline.flush(10_000)
        assertTrue(events.isEmpty())
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun callBurstUsesTenSecondWindow() {
        val reminders = mutableListOf<WeChatFcmEvent>()
        val pipeline = FcmPipeline(onEvent = {}, onReminder = reminders::add)
        pipeline.accept(Signal.Data("123456789", true), 0)
        pipeline.accept(Signal.Data("223456789", true), 3_500)
        assertEquals(1, reminders.size)
        pipeline.accept(Signal.Data("323456789", true), 9_999)
        assertEquals(1, reminders.size)
        pipeline.accept(Signal.Data("423456789", true), 10_000)
        assertEquals(2, reminders.size)
    }

    @Test
    fun messagesUseFiveSecondWindowAndCallSuppressesPendingMessage() {
        val reminders = mutableListOf<WeChatFcmEvent>()
        val pipeline = FcmPipeline(onEvent = {}, onReminder = reminders::add)
        pipeline.accept(Signal.Data("123456789012345678", false), 0)
        pipeline.accept(Signal.Data("123456789", true), 200)
        pipeline.flush(2_000)
        assertEquals(listOf(WeChatFcmType.CALL), reminders.map { it.type })

        val gate = ReminderGate()
        assertTrue(gate.allow(WeChatFcmType.MESSAGE, 0))
        assertFalse(gate.allow(WeChatFcmType.MESSAGE, 4_999))
        assertTrue(gate.allow(WeChatFcmType.MESSAGE, 5_000))
    }

    @Test
    fun pcLoginUsesIndependentTenSecondWindow() {
        val reminders = mutableListOf<WeChatFcmEvent>()
        val pipeline = FcmPipeline(onEvent = {}, onReminder = reminders::add)
        pipeline.accept(Signal.Data("0", false), 0)
        pipeline.accept(Signal.Data("0", false), 9_999)
        assertEquals(1, reminders.size)
        pipeline.accept(Signal.Data("0", false), 10_000)
        pipeline.flush(11_200)
        assertEquals(listOf(WeChatFcmType.PC_LOGIN, WeChatFcmType.PC_LOGIN), reminders.map { it.type })
    }

    @Test
    fun replayGuardRejectsOldAndRepeatedLines() {
        val guard = ReplayGuard("1700000000.000")
        assertFalse(guard.accept("old", "1699999999.999"))
        assertTrue(guard.accept("a", "1700000000.123"))
        assertFalse(guard.accept("a", "1700000000.123"))
        assertTrue(guard.accept("b", "1700000000.123"))
    }
}
