package com.largebatata.fcmhelper.core

enum class ActivityResumeSource {
    RESUME_ACTIVITY,
    SET_RESUMED_ACTIVITY,
}

data class ActivityResumeEvent(
    val timestampMillis: Long,
    val userId: Int,
    val packageName: String,
    val componentName: String,
    val sourceType: ActivityResumeSource,
)

/** Parses only the two verified event-log tags, without a complex regular expression. */
object WeChatForegroundParser {
    private const val RESUME_MARKER = " I wm_resume_activity: "
    private const val SET_RESUMED_MARKER = " I wm_set_resumed_activity: "

    fun parse(raw: String): ActivityResumeEvent? = runCatching {
        val trimmed = raw.trimStart()
        val (markerText, sourceType, componentIndex) = when {
            RESUME_MARKER in trimmed -> Triple(RESUME_MARKER, ActivityResumeSource.RESUME_ACTIVITY, 3)
            SET_RESUMED_MARKER in trimmed -> Triple(
                SET_RESUMED_MARKER,
                ActivityResumeSource.SET_RESUMED_ACTIVITY,
                1,
            )
            else -> return null
        }
        val marker = trimmed.indexOf(markerText)
        if (marker <= 0) return null
        val timestamp = trimmed.substring(0, marker).substringBefore(' ').toBigDecimalOrNull() ?: return null
        val body = trimmed.substring(marker + markerText.length)
        if (!body.startsWith('[') || !body.endsWith(']')) return null
        val fields = body.substring(1, body.length - 1).split(',', limit = 4)
        if (fields.size != 4) return null
        val userId = fields[0].trim().toIntOrNull() ?: return null
        val component = fields[componentIndex].trim()
        val slash = component.indexOf('/')
        if (slash <= 0) return null
        ActivityResumeEvent(
            timestampMillis = timestamp.movePointRight(3).toLong(),
            userId = userId,
            packageName = component.substring(0, slash),
            componentName = component,
            sourceType = sourceType,
        )
    }.getOrNull()

    fun isMainWeChat(activity: ActivityResumeEvent): Boolean =
        activity.userId == 0 && activity.packageName == WECHAT_CATEGORY
}

object HelperNotificationIds {
    const val MONITOR_NOTIFICATION_ID = 1
    const val MESSAGE_NOTIFICATION_ID = 2
    const val CALL_NOTIFICATION_ID = 3

    // Existing behavior uses one notification slot for all non-call reminders.
    const val PC_LOGIN_NOTIFICATION_ID = MESSAGE_NOTIFICATION_ID
    const val UNKNOWN_NOTIFICATION_ID = MESSAGE_NOTIFICATION_ID

    fun forType(type: WeChatFcmType): Int = when (type) {
        WeChatFcmType.MESSAGE -> MESSAGE_NOTIFICATION_ID
        WeChatFcmType.CALL -> CALL_NOTIFICATION_ID
        WeChatFcmType.PC_LOGIN -> PC_LOGIN_NOTIFICATION_ID
        WeChatFcmType.UNKNOWN -> UNKNOWN_NOTIFICATION_ID
    }

    val alertIds: Set<Int> = WeChatFcmType.entries.map(::forType).toSet()
}

data class AlertTransition(
    val startWatcher: Boolean = false,
    val stopWatcher: Boolean = false,
    val startUnlockTimeout: Boolean = false,
    val cancelUnlockTimeout: Boolean = false,
    val cancelIds: Set<Int> = emptySet(),
)

/** Tracks current notification slots only; historical event counters are untouched. */
class AlertNotificationPolicy {
    private val pendingIds = LinkedHashSet<Int>()
    private var unlockTimeoutActive = false

    @Synchronized
    fun onPosted(type: WeChatFcmType): AlertTransition {
        val wasEmpty = pendingIds.isEmpty()
        pendingIds.add(HelperNotificationIds.forType(type))
        return AlertTransition(startWatcher = wasEmpty)
    }

    @Synchronized
    fun onRemoved(notificationId: Int): AlertTransition {
        val removed = pendingIds.remove(notificationId)
        val becameEmpty = removed && pendingIds.isEmpty()
        if (becameEmpty) unlockTimeoutActive = false
        return AlertTransition(
            stopWatcher = becameEmpty,
            cancelUnlockTimeout = becameEmpty,
        )
    }

    @Synchronized
    fun onActivityResumed(activity: ActivityResumeEvent): AlertTransition {
        if (!WeChatForegroundParser.isMainWeChat(activity) || pendingIds.isEmpty()) {
            return AlertTransition()
        }
        pendingIds.clear()
        val cancelTimeout = unlockTimeoutActive
        unlockTimeoutActive = false
        return AlertTransition(
            stopWatcher = true,
            cancelUnlockTimeout = cancelTimeout,
            cancelIds = HelperNotificationIds.alertIds,
        )
    }

    @Synchronized
    fun onUserPresent(keyguardLocked: Boolean): AlertTransition {
        if (keyguardLocked || pendingIds.isEmpty() || unlockTimeoutActive) return AlertTransition()
        unlockTimeoutActive = true
        return AlertTransition(startUnlockTimeout = true)
    }

    @Synchronized
    fun onUnlockTimeout(): AlertTransition {
        if (!unlockTimeoutActive || pendingIds.isEmpty()) {
            unlockTimeoutActive = false
            return AlertTransition()
        }
        unlockTimeoutActive = false
        pendingIds.clear()
        return AlertTransition(
            stopWatcher = true,
            cancelIds = HelperNotificationIds.alertIds,
        )
    }

    @Synchronized
    fun hasPending(): Boolean = pendingIds.isNotEmpty()

    @Synchronized
    fun hasUnlockTimeout(): Boolean = unlockTimeoutActive
}
