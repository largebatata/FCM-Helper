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
        val policy = AlertNotificationPolicy()
        WeChatFcmType.entries.forEach { policy.onPosted(it) }
        val transition = policy.onActivityResumed(WeChatForegroundParser.parse(resume(0))!!)
        assertEquals(HelperNotificationIds.alertIds, transition.cancelIds)
        assertFalse(HelperNotificationIds.MONITOR_NOTIFICATION_ID in transition.cancelIds)
        for (type in WeChatFcmType.entries) {
            assertTrue(HelperNotificationIds.forType(type) in transition.cancelIds)
        }
        assertTrue(transition.stopWatcher)
        assertFalse(policy.hasPending())
    }

    @Test
    fun cloneAndBackgroundEvidenceClearNothing() {
        val policy = AlertNotificationPolicy()
        assertTrue(policy.onPosted(WeChatFcmType.MESSAGE).startWatcher)
        val clone = policy.onActivityResumed(WeChatForegroundParser.parse(resume(128))!!)
        assertTrue(clone.cancelIds.isEmpty())
        assertFalse(clone.stopWatcher)
        assertTrue(policy.hasPending())
    }

    @Test
    fun watcherLifecycleFollowsPendingNotificationSlots() {
        val policy = AlertNotificationPolicy()
        assertFalse(policy.hasPending())
        assertTrue(policy.onPosted(WeChatFcmType.MESSAGE).startWatcher)
        assertFalse(policy.onPosted(WeChatFcmType.PC_LOGIN).startWatcher)
        assertFalse(policy.onPosted(WeChatFcmType.CALL).startWatcher)
        assertFalse(policy.onRemoved(HelperNotificationIds.MESSAGE_NOTIFICATION_ID).stopWatcher)
        assertTrue(policy.onRemoved(HelperNotificationIds.CALL_NOTIFICATION_ID).stopWatcher)
        assertFalse(policy.hasPending())
    }

    @Test
    fun lockedAlertsWaitForUserPresentAndRepeatedUnlockDoesNotRestartTimer() {
        val policy = AlertNotificationPolicy()
        for (type in WeChatFcmType.entries) {
            val isolated = AlertNotificationPolicy()
            isolated.onPosted(type)
            assertFalse(isolated.hasUnlockTimeout())
            assertFalse(isolated.onUserPresent(keyguardLocked = true).startUnlockTimeout)
            assertTrue(isolated.hasPending())
        }

        policy.onPosted(WeChatFcmType.MESSAGE)
        assertTrue(policy.onUserPresent(keyguardLocked = false).startUnlockTimeout)
        assertTrue(policy.hasUnlockTimeout())
        assertFalse(policy.onUserPresent(keyguardLocked = false).startUnlockTimeout)
        assertTrue(policy.hasUnlockTimeout())
    }

    @Test
    fun unlockTimeoutClearsOnlyAlertsAndEndsWatcherSession() {
        val policy = AlertNotificationPolicy()
        policy.onPosted(WeChatFcmType.MESSAGE)
        policy.onPosted(WeChatFcmType.CALL)
        policy.onUserPresent(keyguardLocked = false)

        val transition = policy.onUnlockTimeout()
        assertEquals(HelperNotificationIds.alertIds, transition.cancelIds)
        assertFalse(HelperNotificationIds.MONITOR_NOTIFICATION_ID in transition.cancelIds)
        assertTrue(transition.stopWatcher)
        assertFalse(policy.hasPending())
        assertFalse(policy.hasUnlockTimeout())
    }

    @Test
    fun mainWeChatCancelsUnlockTimerButCloneKeepsItRunning() {
        val policy = AlertNotificationPolicy()
        policy.onPosted(WeChatFcmType.PC_LOGIN)
        policy.onUserPresent(keyguardLocked = false)

        val clone = policy.onActivityResumed(WeChatForegroundParser.parse(setResumed(128))!!)
        assertTrue(clone.cancelIds.isEmpty())
        assertFalse(clone.cancelUnlockTimeout)
        assertTrue(policy.hasPending())
        assertTrue(policy.hasUnlockTimeout())

        val main = policy.onActivityResumed(WeChatForegroundParser.parse(setResumed(0))!!)
        assertEquals(HelperNotificationIds.alertIds, main.cancelIds)
        assertTrue(main.cancelUnlockTimeout)
        assertTrue(main.stopWatcher)
        assertFalse(policy.hasPending())
        assertFalse(policy.hasUnlockTimeout())
    }

    @Test
    fun userPresentWithoutPendingDoesNothingAndManualRemovalCancelsTimer() {
        val policy = AlertNotificationPolicy()
        assertFalse(policy.onUserPresent(keyguardLocked = false).startUnlockTimeout)
        policy.onPosted(WeChatFcmType.UNKNOWN)
        policy.onUserPresent(keyguardLocked = false)

        val transition = policy.onRemoved(HelperNotificationIds.UNKNOWN_NOTIFICATION_ID)
        assertTrue(transition.cancelUnlockTimeout)
        assertTrue(transition.stopWatcher)
        assertFalse(policy.hasPending())
    }

    @Test
    fun activityChildExitRestartsOnlyWhileAlertIsPending() {
        val policy = AlertNotificationPolicy()
        policy.onPosted(WeChatFcmType.UNKNOWN)
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
