package com.largebatata.fcmhelper.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeChatForegroundAndroidTest {
    @Test fun bothVerifiedEventFormatsInitializeAndParseOnAndroid() {
        for (body in listOf(
            "wm_resume_activity: [0,123,456,com.tencent.mm/.ui.LauncherUI]",
            "wm_set_resumed_activity: [0,com.tencent.mm/.ui.LauncherUI,minimalResumeActivityLocked]",
        )) {
            val event = WeChatForegroundParser.parse("1788977207.469 1777 3830 I $body")!!
            assertTrue(WeChatForegroundParser.isMainWeChat(event))
            assertFalse(WeChatForegroundParser.isMainWeChat(event.copy(userId = 128)))
        }
        assertNull(WeChatForegroundParser.parse(
            "1788977207.469 1777 3830 I wm_set_resumed_activity: [0,,reason]"))
    }
}
