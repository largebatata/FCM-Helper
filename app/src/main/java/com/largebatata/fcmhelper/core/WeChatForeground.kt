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
        val (markerText, sourceType) = when {
            RESUME_MARKER in trimmed -> RESUME_MARKER to ActivityResumeSource.RESUME_ACTIVITY
            SET_RESUMED_MARKER in trimmed -> SET_RESUMED_MARKER to ActivityResumeSource.SET_RESUMED_ACTIVITY
            else -> return null
        }
        val marker = trimmed.indexOf(markerText)
        if (marker <= 0) return null
        val timestamp = trimmed.substring(0, marker).substringBefore(' ').toBigDecimalOrNull() ?: return null
        val body = trimmed.substring(marker + markerText.length).trim()
        if (!body.startsWith('[') || !body.endsWith(']')) return null
        val fields = body.substring(1, body.length - 1).split(',').map(String::trim)
        val expectedFields = if (sourceType == ActivityResumeSource.RESUME_ACTIVITY) 4 else 3
        if (fields.size != expectedFields || fields.any(String::isEmpty)) return null
        val userId = fields[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val component = fields[if (sourceType == ActivityResumeSource.RESUME_ACTIVITY) 3 else 1]
        val slash = component.indexOf('/')
        if (slash <= 0 || slash != component.lastIndexOf('/')) return null
        val packageName = component.substring(0, slash)
        val className = component.substring(slash + 1).removePrefix(".")
        if (!validName(packageName, allowDollar = false) || !validName(className, allowDollar = true)) return null
        ActivityResumeEvent(
            timestampMillis = timestamp.movePointRight(3).setScale(0, java.math.RoundingMode.DOWN).longValueExact().also { require(it >= 0) },
            userId = userId,
            packageName = packageName,
            componentName = component,
            sourceType = sourceType,
        )
    }.getOrNull()

    private fun validName(name: String, allowDollar: Boolean): Boolean = name.split('.').all { part ->
        fun isStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || (allowDollar && c == '$')
        part.isNotEmpty() && isStart(part.first()) && part.all { isStart(it) || it in '0'..'9' }
    }

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
    val cancelIds: Set<Int> = emptySet(),
)

/** Tracks current notification slots only; historical event counters are untouched. */
class AlertNotificationPolicy {
    private val pendingIds = LinkedHashSet<Int>()

    @Synchronized
    fun onReminder(type: WeChatFcmType, notificationPosted: Boolean): AlertTransition {
        // A new Toast is not evidence that any older notification was viewed.
        if (!notificationPosted) return AlertTransition()
        val wasEmpty = pendingIds.isEmpty()
        pendingIds.add(HelperNotificationIds.forType(type))
        return AlertTransition(startWatcher = wasEmpty)
    }

    @Synchronized
    fun onRemoved(notificationId: Int): AlertTransition {
        val removed = pendingIds.remove(notificationId)
        val becameEmpty = removed && pendingIds.isEmpty()
        return AlertTransition(stopWatcher = becameEmpty)
    }

    @Synchronized
    fun onActivityResumed(
        activity: ActivityResumeEvent,
        isInteractive: Boolean,
        keyguardLocked: Boolean,
    ): AlertTransition {
        if (!isInteractive || keyguardLocked ||
            !WeChatForegroundParser.isMainWeChat(activity) || pendingIds.isEmpty()
        ) {
            return AlertTransition()
        }
        pendingIds.clear()
        return AlertTransition(
            stopWatcher = true,
            cancelIds = HelperNotificationIds.alertIds,
        )
    }

    @Synchronized
    fun hasPending(): Boolean = pendingIds.isNotEmpty()
}
