package com.largebatata.fcmhelper.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatForegroundTest {
    private fun resume(user: Int, component: String = "com.tencent.mm/.ui.LauncherUI") =
        "1788977207.469 1777 3830 I wm_resume_activity: [$user,22077761,48172,$component]"

    private fun setResumed(user: Int, component: String = "com.tencent.mm/.ui.LauncherUI") =
        "1788977207.469 1777 3830 I wm_set_resumed_activity: " +
            "[$user,$component,minimalResumeActivityLocked,0]"

    @Test
    fun userZeroAnyWeChatActivityMatches() {
        for (activity in listOf(
            ".ui.LauncherUI",
            ".ui.chatting.ChattingUI",
            ".plugin.setting.ui.setting.SettingsUI",
            ".plugin.webwx.ui.ExtDeviceWXLoginUI",
        )) {
            val parsed = WeChatForegroundParser.parse(resume(0, "com.tencent.mm/$activity"))!!
            assertTrue(WeChatForegroundParser.isMainWeChat(parsed))
            assertEquals(ActivityResumeSource.RESUME_ACTIVITY, parsed.sourceType)
            val coldStart = WeChatForegroundParser.parse(setResumed(0, "com.tencent.mm/$activity"))!!
            assertTrue(WeChatForegroundParser.isMainWeChat(coldStart))
            assertEquals(ActivityResumeSource.SET_RESUMED_ACTIVITY, coldStart.sourceType)
        }
    }

    @Test
    fun clonedUserAndOtherPackagesDoNotMatch() {
        assertFalse(WeChatForegroundParser.isMainWeChat(WeChatForegroundParser.parse(resume(128))!!))
        assertFalse(WeChatForegroundParser.isMainWeChat(WeChatForegroundParser.parse(setResumed(128))!!))
        assertFalse(WeChatForegroundParser.isMainWeChat(
            WeChatForegroundParser.parse(resume(0, "com.example.other/.MainActivity"))!!,
        ))
        assertFalse(WeChatForegroundParser.isMainWeChat(
            WeChatForegroundParser.parse(resume(10))!!,
        ))
    }

    @Test
    fun malformedServiceAndProcessLinesAreIgnored() {
        assertNull(WeChatForegroundParser.parse("wm_resume_activity: [0,broken]"))
        assertNull(WeChatForegroundParser.parse(
            "1788977207.469 1777 3830 I ActivityManager: Start proc 10439:com.tencent.mm:push/u0a439",
        ))
        assertNull(WeChatForegroundParser.parse(
            "1788977207.469 1777 3830 I ActivityManager: Start service com.tencent.mm/.booter.CoreService",
        ))
        assertNull(WeChatForegroundParser.parse(
            "1788977207.469 1777 3830 I wm_on_resume_called: [1,com.tencent.mm.ui.LauncherUI,RESUME_ACTIVITY]",
        ))
    }

    @Test
    fun mainWeChatClearsAlertIdsAndNeverMonitor() {
        for (raw in listOf(resume(0), setResumed(0))) {
            val policy = AlertNotificationPolicy()
            WeChatFcmType.entries.forEach { policy.onReminder(it, notificationPosted = true) }
            val transition = policy.onActivityResumed(
                WeChatForegroundParser.parse(raw)!!,
                isInteractive = true,
                keyguardLocked = false,
            )
            assertEquals(HelperNotificationIds.alertIds, transition.cancelIds)
            assertFalse(HelperNotificationIds.MONITOR_NOTIFICATION_ID in transition.cancelIds)
            for (type in WeChatFcmType.entries) {
                assertTrue(HelperNotificationIds.forType(type) in transition.cancelIds)
            }
            assertTrue(transition.stopWatcher)
            assertFalse(policy.hasPending())
        }
    }

    @Test
    fun cloneAndBackgroundEvidenceClearNothing() {
        val policy = AlertNotificationPolicy()
        assertTrue(policy.onReminder(WeChatFcmType.MESSAGE, notificationPosted = true).startWatcher)
        for (raw in listOf(resume(128), setResumed(128), resume(0, "com.example.other/.MainActivity"))) {
            val transition = policy.onActivityResumed(
                WeChatForegroundParser.parse(raw)!!,
                isInteractive = true,
                keyguardLocked = false,
            )
            assertTrue(transition.cancelIds.isEmpty())
            assertFalse(transition.stopWatcher)
            assertTrue(policy.hasPending())
        }
    }

    @Test
    fun watcherLifecycleFollowsPendingNotificationSlots() {
        val policy = AlertNotificationPolicy()
        assertFalse(policy.hasPending())
        assertTrue(policy.onReminder(WeChatFcmType.MESSAGE, notificationPosted = true).startWatcher)
        assertFalse(policy.onReminder(WeChatFcmType.PC_LOGIN, notificationPosted = true).startWatcher)
        assertFalse(policy.onReminder(WeChatFcmType.CALL, notificationPosted = true).startWatcher)
        assertFalse(policy.onRemoved(HelperNotificationIds.MESSAGE_NOTIFICATION_ID).stopWatcher)
        assertTrue(policy.onRemoved(HelperNotificationIds.CALL_NOTIFICATION_ID).stopWatcher)
        assertFalse(policy.hasPending())
    }

    @Test
    fun hiddenOrLockedMainWeChatResumePreservesEveryPendingSlot() {
        for (raw in listOf(resume(0), setResumed(0))) {
            for ((interactive, locked) in listOf(false to true, false to false, true to true)) {
                val policy = AlertNotificationPolicy()
                WeChatFcmType.entries.forEach { policy.onReminder(it, notificationPosted = true) }
                val transition = policy.onActivityResumed(
                    WeChatForegroundParser.parse(raw)!!,
                    isInteractive = interactive,
                    keyguardLocked = locked,
                )
                assertEquals(AlertTransition(), transition)
                assertTrue(policy.hasPending())
                assertFalse(policy.onRemoved(HelperNotificationIds.MESSAGE_NOTIFICATION_ID).stopWatcher)
                assertTrue(policy.onRemoved(HelperNotificationIds.CALL_NOTIFICATION_ID).stopWatcher)
            }
        }
    }

    @Test
    fun unlockAloneHasNoCleanupOrTimeoutHook() {
        val policy = AlertNotificationPolicy()
        policy.onReminder(WeChatFcmType.MESSAGE, notificationPosted = true)
        // Unlock/time passage no longer has a callback capable of clearing pending alerts.
        val callbacks = AlertNotificationPolicy::class.java.declaredMethods.map { it.name }
        assertFalse("onUserPresent" in callbacks)
        assertFalse("onUnlockTimeout" in callbacks)
        assertFalse("hasUnlockTimeout" in callbacks)
        assertTrue(policy.hasPending())
        assertTrue(policy.onRemoved(HelperNotificationIds.MESSAGE_NOTIFICATION_ID).stopWatcher)
    }

    @Test
    fun newUnlockedToastsPreserveMultiplePendingNotifications() {
        val policy = AlertNotificationPolicy()
        policy.onReminder(WeChatFcmType.MESSAGE, notificationPosted = true)
        policy.onReminder(WeChatFcmType.CALL, notificationPosted = true)
        for (type in WeChatFcmType.entries) {
            assertEquals(AlertTransition(), policy.onReminder(type, notificationPosted = false))
            assertTrue(policy.hasPending())
        }
        assertFalse(policy.onRemoved(HelperNotificationIds.MESSAGE_NOTIFICATION_ID).stopWatcher)
        assertTrue(policy.hasPending())
        assertTrue(policy.onRemoved(HelperNotificationIds.CALL_NOTIFICATION_ID).stopWatcher)
        assertFalse(policy.hasPending())
    }

    @Test
    fun manualDismissRemovesEveryTypeAndStopsTheLastWatcher() {
        for (type in WeChatFcmType.entries) {
            val policy = AlertNotificationPolicy()
            assertEquals(AlertTransition(), policy.onReminder(type, notificationPosted = false))
            assertFalse(policy.hasPending())
            policy.onReminder(type, notificationPosted = true)
            val transition = policy.onRemoved(HelperNotificationIds.forType(type))
            assertTrue(transition.cancelIds.isEmpty())
            assertTrue(transition.stopWatcher)
            assertFalse(policy.hasPending())
            assertEquals(AlertTransition(), policy.onRemoved(HelperNotificationIds.forType(type)))
        }
    }

    @Test
    fun activityChildExitRestartsOnlyWhileAlertIsPending() {
        val policy = AlertNotificationPolicy()
        policy.onReminder(WeChatFcmType.UNKNOWN, notificationPosted = true)
        val opens = AtomicInteger()
        val replacementEntered = CountDownLatch(1)
        val replacementClosed = CountDownLatch(1)
        val reader = SupervisedLineReader(
            source = LogSource {
                if (opens.incrementAndGet() == 1) object : LogConnection {
                    override val isAlive = false
                    override val exitCode = 9
                    override val failed = true
                    override fun readLine(): String? = null
                    override fun close() = Unit
                } else object : LogConnection {
                    override fun readLine(): String? {
                        replacementEntered.countDown()
                        replacementClosed.await()
                        return null
                    }
                    override fun terminate() = replacementClosed.countDown()
                    override fun close() = Unit
                }
            },
            permission = { true },
            enabled = policy::hasPending,
            cursor = { "0" },
            elapsedClock = { 0 },
            wallClock = { 0 },
            sleep = {},
            onState = { _, _ -> },
            onLine = {},
        )
        try {
            reader.start()
            assertTrue(replacementEntered.await(3, TimeUnit.SECONDS))
            assertEquals(2, opens.get())
            policy.onRemoved(HelperNotificationIds.UNKNOWN_NOTIFICATION_ID)
            reader.stop()
            reader.awaitStopped(1_000)
            assertEquals(2, opens.get())
        } finally {
            replacementClosed.countDown()
            reader.stop()
        }
    }
}
