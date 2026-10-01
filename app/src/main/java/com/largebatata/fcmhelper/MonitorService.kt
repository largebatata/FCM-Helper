package com.largebatata.fcmhelper

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.largebatata.fcmhelper.core.FcmParser
import com.largebatata.fcmhelper.core.FcmPipeline
import com.largebatata.fcmhelper.core.AlertNotificationPolicy
import com.largebatata.fcmhelper.core.AlertTransition
import com.largebatata.fcmhelper.core.HelperNotificationIds
import com.largebatata.fcmhelper.core.LogcatReader
import com.largebatata.fcmhelper.core.ReaderHealth
import com.largebatata.fcmhelper.core.ReaderState
import com.largebatata.fcmhelper.core.SupervisedLineReader
import com.largebatata.fcmhelper.core.WeChatFcmEvent
import com.largebatata.fcmhelper.core.WeChatFcmType
import com.largebatata.fcmhelper.core.WeChatForegroundParser
import com.largebatata.fcmhelper.wake.EnhancedWakeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class MonitorStatus(
    val serviceRunning: Boolean = false,
    val readerState: ReaderState = ReaderState.Stopped,
    val reconnectCount: Int = 0,
    val lastMatchedLog: Long? = null,
    val lastFcm: Long? = null,
    val lastCall: Long? = null,
    val recentEvent: WeChatFcmType? = null,
    val messageCount: Int = 0,
    val callCount: Int = 0,
    val pcLoginCount: Int = 0,
    val unknownCount: Int = 0,
    val readerPid: Long? = null,
    val readerAlive: Boolean = false,
    val lastReaderStart: Long? = null,
    val lastSuccessfulLogLine: Long? = null,
    val lastReaderExitCode: Int? = null,
    val lastReaderFailureType: String? = null,
)

object MonitorUi {
    internal val mutable = MutableStateFlow(MonitorStatus())
    val status = mutable.asStateFlow()
    @Volatile var messagesEnabled = true
    @Volatile var callsEnabled = true

    fun clearStatistics() {
        val current = mutable.value
        mutable.value = current.copy(
            reconnectCount = 0,
            lastMatchedLog = null,
            lastFcm = null,
            lastCall = null,
            recentEvent = null,
            messageCount = 0,
            callCount = 0,
            pcLoginCount = 0,
            unknownCount = 0,
        )
    }
}

class MonitorService : Service() {
    private val queue = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val main = Handler(Looper.getMainLooper())
    private var reader: LogcatReader? = null
    private var activityWatcher: SupervisedLineReader? = null
    private val alertPolicy = AlertNotificationPolicy()
    private lateinit var diagnostics: ReaderDiagnostics
    private lateinit var listening: ListeningPreference
    @Volatile private var stopping = false

    private val pipeline = FcmPipeline(
        onEvent = { event ->
            val now = System.currentTimeMillis()
            diagnostics.record("FCM_EVENT", mapOf("type" to event.type.name))
            EventHistoryRepository.record(now, event.type)
            updateStatus {
                it.copy(
                    lastFcm = now,
                    lastCall = if (event.type == WeChatFcmType.CALL) now else it.lastCall,
                    recentEvent = event.type,
                    messageCount = it.messageCount + if (event.type == WeChatFcmType.MESSAGE) 1 else 0,
                    callCount = it.callCount + if (event.type == WeChatFcmType.CALL) 1 else 0,
                    pcLoginCount = it.pcLoginCount + if (event.type == WeChatFcmType.PC_LOGIN) 1 else 0,
                    unknownCount = it.unknownCount + if (event.type == WeChatFcmType.UNKNOWN) 1 else 0,
                )
            }
        },
        onReminder = ::remind,
    )

    override fun onCreate() {
        super.onCreate()
        diagnostics = ReaderDiagnostics(this)
        listening = ListeningPreference(this)
        diagnostics.record("SERVICE_CREATE")
        val monitor = NotificationChannel(MONITOR_CHANNEL, "监听状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "FCM Helper 前台监听状态"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val messages = NotificationChannel(MESSAGE_CHANNEL, "微信消息", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
        val calls = NotificationChannel(CALL_CHANNEL, "微信电话", NotificationManager.IMPORTANCE_HIGH)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(monitor, messages, calls))
        diagnostics.record(
            "MONITOR_NOTIFICATION_STATE",
            mapOf(
                "notificationsEnabled" to manager.areNotificationsEnabled().toString(),
                "channelImportance" to manager.getNotificationChannel(MONITOR_CHANNEL).importance.toString(),
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diagnostics.record(
            "SERVICE_START_COMMAND",
            mapOf("action" to (intent?.action ?: "sticky_restart"), "startId" to startId.toString()),
        )
        if (intent?.action == ACTION_ALERT_DISMISSED) {
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (notificationId in HelperNotificationIds.alertIds) {
                applyAlertTransition(alertPolicy.onRemoved(notificationId))
            }
        }
        if (intent?.action == ACTION_STOP) {
            listening.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null && intent.action != ACTION_ALERT_DISMISSED) listening.enabled = true
        if (!listening.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (reader != null) return START_STICKY

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wechat_fcm)
            .setContentTitle("FCM Helper 正在监听")
            .setContentText("仅监听 microG 的两个 FCM 日志标签")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                "停止",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    HelperNotificationIds.MONITOR_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(HelperNotificationIds.MONITOR_NOTIFICATION_ID, notification)
            }
            diagnostics.record("FOREGROUND_START_OK")
        } catch (_: RuntimeException) {
            diagnostics.record("FOREGROUND_START_EXCEPTION", mapOf("type" to "RuntimeException"))
            updateStatus { it.copy(serviceRunning = false, readerState = ReaderState.Error) }
            stopSelf()
            return START_NOT_STICKY
        }

        updateStatus { it.copy(serviceRunning = true) }
        reader = LogcatReader(
            source = AndroidLogSource(),
            permission = ::hasReadLogs,
            enabled = { listening.enabled && !stopping },
            initialCursor = String.format(Locale.US, "%.3f", System.currentTimeMillis() / 1000.0),
            clock = SystemClock::elapsedRealtime,
            onState = { state, reconnects ->
                updateStatus { it.copy(readerState = state, reconnectCount = reconnects) }
            },
            onHealth = ::updateReaderHealth,
            onDiagnostic = diagnostics::record,
            onRecord = { record ->
                // Parse immediately on the reader thread. The raw body is never queued, logged, or persisted.
                val signal = FcmParser.signal(record)
                updateStatus { it.copy(lastMatchedLog = System.currentTimeMillis()) }
                if (signal != null) {
                    updateStatus { it.copy(lastFcm = System.currentTimeMillis()) }
                    post {
                        pipeline.accept(signal, SystemClock.elapsedRealtime())
                        scheduleFlush()
                    }
                }
            },
        ).also { it.start() }
        queue.scheduleWithFixedDelay(
            {
                if (!stopping && listening.enabled) {
                    reader?.watchdog()
                    if (alertPolicy.hasPending()) activityWatcher?.watchdog()
                }
            },
            WATCHDOG_INTERVAL_MS,
            WATCHDOG_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )

        return START_STICKY
    }

    private fun updateReaderHealth(health: ReaderHealth) {
        updateStatus {
            it.copy(
                readerPid = health.pid,
                readerAlive = health.isAlive,
                reconnectCount = health.reconnectCount,
                lastReaderStart = health.lastStartTime,
                lastSuccessfulLogLine = health.lastSuccessfulLineTime,
                lastReaderExitCode = health.lastExitCode,
                lastReaderFailureType = health.lastFailureType,
            )
        }
    }

    private fun scheduleFlush() {
        if (stopping) return
        queue.schedule(
            { if (!stopping) pipeline.flush(SystemClock.elapsedRealtime()) },
            FcmPipeline.MERGE_WINDOW_MS + 50,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun hasReadLogs(): Boolean =
        checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    private fun updateStatus(block: (MonitorStatus) -> MonitorStatus) {
        MonitorUi.mutable.value = block(MonitorUi.mutable.value)
    }

    private fun post(block: () -> Unit) {
        if (!stopping) runCatching { queue.execute { if (!stopping) block() } }
    }

    private fun remind(event: WeChatFcmEvent) {
        EnhancedWakeManager.requestWake(event.type)
        if (event.type == WeChatFcmType.CALL && !MonitorUi.callsEnabled) return
        if (event.type != WeChatFcmType.CALL && !MonitorUi.messagesEnabled) return
        main.post {
            if (stopping) return@post
            val call = event.type == WeChatFcmType.CALL
            val text = when (event.type) {
                WeChatFcmType.CALL -> "有微信电话"
                WeChatFcmType.PC_LOGIN -> "微信电脑版登录请求"
                WeChatFcmType.MESSAGE, WeChatFcmType.UNKNOWN -> "微信有新消息"
            }
            val manager = getSystemService(NotificationManager::class.java)
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (interactive && !locked) {
                applyAlertTransition(alertPolicy.onReminder(event.type, notificationPosted = false))
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            } else if (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                if (call) removeAlert(HelperNotificationIds.MESSAGE_NOTIFICATION_ID, cancelNotification = true)
                val notification = NotificationCompat.Builder(this, if (call) CALL_CHANNEL else MESSAGE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_wechat_fcm)
                    .setContentTitle(text)
                    .setContentText(if (call) "基于 FCM 特征识别" else "请打开微信查看")
                    .setContentIntent(openWeChat())
                    .setDeleteIntent(alertDismissed(HelperNotificationIds.forType(event.type)))
                    .setAutoCancel(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPriority(if (call) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                    .build()
                val notificationId = HelperNotificationIds.forType(event.type)
                manager.notify(notificationId, notification)
                applyAlertTransition(alertPolicy.onReminder(event.type, notificationPosted = true))
            }
        }
    }

    private fun applyAlertTransition(transition: AlertTransition) {
        val manager = getSystemService(NotificationManager::class.java)
        transition.cancelIds.forEach {
            manager.cancel(it)
        }
        if (transition.stopWatcher) stopActivityWatcher()
        if (transition.startWatcher) startActivityWatcher()
    }

    private fun startActivityWatcher() {
        if (activityWatcher != null || stopping || !alertPolicy.hasPending()) return
        val startedAt = System.currentTimeMillis()
        activityWatcher = SupervisedLineReader(
            source = AndroidActivityResumeSource(),
            permission = ::hasReadLogs,
            enabled = { listening.enabled && !stopping && alertPolicy.hasPending() },
            cursor = { "0" },
            elapsedClock = SystemClock::elapsedRealtime,
            wallClock = System::currentTimeMillis,
            onState = { _, _ -> },
            onDiagnostic = { event, fields -> diagnostics.record("ACTIVITY_$event", fields) },
            onLine = { line ->
                val activity = WeChatForegroundParser.parse(line)
                if (activity != null && activity.timestampMillis >= startedAt &&
                    WeChatForegroundParser.isMainWeChat(activity)
                ) {
                    val interactiveAtEvent = getSystemService(PowerManager::class.java).isInteractive
                    val lockedAtEvent = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                    main.post {
                        if (stopping) return@post
                        // Recheck before cancelling, and never promote a hidden event after unlocking.
                        val interactive = interactiveAtEvent &&
                            getSystemService(PowerManager::class.java).isInteractive
                        val locked = lockedAtEvent ||
                            getSystemService(KeyguardManager::class.java).isKeyguardLocked
                        diagnostics.record(
                            if (interactive && !locked) "MAIN_WECHAT_FOREGROUND"
                            else "MAIN_WECHAT_FOREGROUND_IGNORED_LOCKED",
                        )
                        applyAlertTransition(alertPolicy.onActivityResumed(activity, interactive, locked))
                    }
                }
            },
        ).also { it.start() }
        diagnostics.record("ACTIVITY_WATCHER_START")
    }

    private fun stopActivityWatcher() {
        val watcher = activityWatcher ?: return
        watcher.stop()
        activityWatcher = null
        diagnostics.record("ACTIVITY_WATCHER_STOP")
    }

    private fun removeAlert(notificationId: Int, cancelNotification: Boolean) {
        if (cancelNotification) getSystemService(NotificationManager::class.java).cancel(notificationId)
        applyAlertTransition(alertPolicy.onRemoved(notificationId))
    }

    private fun alertDismissed(notificationId: Int) = PendingIntent.getService(
        this,
        100 + notificationId,
        Intent(this, MonitorService::class.java)
            .setAction(ACTION_ALERT_DISMISSED)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openWeChat(): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val target = runCatching {
            packageManager.getLaunchIntentForPackage("com.tencent.mm")
        }.getOrNull() ?: return openApp()
        return runCatching {
            PendingIntent.getActivity(this, 2, target, flags)
        }.getOrElse {
            openApp()
        }
    }

    override fun onDestroy() {
        diagnostics.record("SERVICE_DESTROY", mapOf("listeningEnabled" to listening.enabled.toString()))
        stopping = true
        reader?.stop()
        reader = null
        stopActivityWatcher()
        queue.shutdownNow()
        main.removeCallbacksAndMessages(null)
        updateStatus { it.copy(serviceRunning = false, readerState = ReaderState.Stopped) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diagnostics.record("SERVICE_TASK_REMOVED")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.largebatata.fcmhelper.STOP_MONITOR"
        private const val ACTION_ALERT_DISMISSED = "com.largebatata.fcmhelper.ALERT_DISMISSED"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val MONITOR_CHANNEL = "monitor"
        private const val MESSAGE_CHANNEL = "messages"
        private const val CALL_CHANNEL = "calls"
        private const val WATCHDOG_INTERVAL_MS = 60_000L
    }
}
