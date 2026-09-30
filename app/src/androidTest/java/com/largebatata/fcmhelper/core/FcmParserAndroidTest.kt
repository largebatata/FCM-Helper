package com.largebatata.fcmhelper.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs with Android's ICU regex implementation rather than the desktop JVM implementation. */
@RunWith(AndroidJUnit4::class)
class FcmParserAndroidTest {
    private fun parse(seq: String, hasRdata: Boolean, category: String = WECHAT_CATEGORY): Signal.Data? {
        val appData = buildString {
            append("AppData{key=seq, value_=")
            append(seq)
            append("}")
            if (hasRdata) append(", AppData{key=rdata, value_=REDACTED}")
        }
        val line = "1700000000.123 1234 5678 D GmsGcmMcsInput: " +
            "Incoming message: DataMessageStanza{category=$category, appData=[$appData]}"
        return FcmParser.signal(FcmParser.record(line)!!) as? Signal.Data
    }

    @Test
    fun parserInitializesAndClassifiesRedactedSamplesOnAndroidRuntime() {
        val classifier = SeqLengthClassifier()
        assertEquals(WeChatFcmType.PC_LOGIN, classifier.classify(parse("0", false)!!))
        assertEquals(WeChatFcmType.CALL, classifier.classify(parse("123456789", true)!!))
        assertEquals(WeChatFcmType.CALL, classifier.classify(parse("1234567890", true)!!))
        assertEquals(WeChatFcmType.MESSAGE, classifier.classify(parse("123456789012345678", false)!!))
        assertEquals(WeChatFcmType.MESSAGE, classifier.classify(parse("1234567890123456789", false)!!))
        assertEquals(WeChatFcmType.MESSAGE, classifier.classify(parse("123456789012345678", true)!!))
        assertNull(parse("123456789", true, "com.example.other"))
    }
}
