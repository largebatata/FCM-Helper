package com.largebatata.fcmhelper.wake

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.largebatata.fcmhelper.ReaderDiagnostics
import com.largebatata.fcmhelper.core.WeChatFcmType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class WakeRecord(
    val timestamp: Long,
    val type: WeChatFcmType,
    val outcome: WakeOutcome,
    val durationMs: Long,
)

data class WakeUiState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
    val enabled: Boolean = false,
    val lastWake: WakeRecord? = null,
    val notice: String? = null,
) {
    val ready: Boolean get() = running && permissionGranted
    val switchEnabled: Boolean get() = ready
    val canRequestPermission: Boolean get() = running && !permissionGranted
    val permissionLabel: String get() = when {
        !running -> "—"
        permissionGranted -> "✓ 已授权"
        else -> "未授权"
    }
}

object EnhancedWakeManager {
    private const val REQUEST_CODE = 20260911
    private const val PREFS = "enhanced_wake"
    private const val KEY_ENABLED = "enhanced_wake_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(WakeUiState())
    val state = mutableState.asStateFlow()

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var gateway: AndroidShizukuGateway
    private lateinit var diagnostics: ReaderDiagnostics

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        recordLifecycle("SHIZUKU_BINDER_RECEIVED")
        refresh("binder_received")
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        recordLifecycle("SHIZUKU_BINDER_DEAD")
        refresh("binder_dead")
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            recordLifecycle(
                "SHIZUKU_PERMISSION_RESULT",
                mapOf("granted" to (grantResult == PackageManager.PERMISSION_GRANTED).toString()),
            )
            refresh(
                reason = "permission_result",
                notice = if (grantResult == PackageManager.PERMISSION_GRANTED) null else "Shizuku 授权未通过",
            )
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        gateway = AndroidShizukuGateway(appContext)
        diagnostics = ReaderDiagnostics(appContext)
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh("initialize")
    }

    fun requestPermission() {
        if (!initialized) return
        if (!gateway.isRunning()) {
            refresh("permission_request", "Shizuku 未运行")
            return
        }
        runCatching { gateway.requestPermission(REQUEST_CODE) }
            .onFailure { refresh("permission_request_failed", "无法请求 Shizuku 授权") }
    }

    fun setEnabled(enabled: Boolean) {
        if (!initialized) return
        if (enabled && !gateway.isRunning()) {
            refresh("toggle", "请先运行 Shizuku")
            return
        }
        if (enabled && !gateway.hasPermission()) {
            refresh("toggle", "请先授予 Shizuku 权限")
            return
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        refresh("toggle", null)
    }

    fun requestWake(type: WeChatFcmType) {
        if (!initialized || !WakePolicy.isEligible(type)) return
        val snapshot = mutableState.value
        if (!snapshot.enabled) return
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val available = gateway.isRunning()
            val granted = available && gateway.hasPermission()
            val outcome = WakeExecutor(gateway).execute(
                type = type,
                enabled = snapshot.enabled,
                available = available,
                permissionGranted = granted,
            )
            val duration = SystemClock.elapsedRealtime() - started
            val record = WakeRecord(System.currentTimeMillis(), type, outcome, duration)
            mutableState.value = currentState().copy(lastWake = record, notice = null)
            diagnosticEvent(outcome)?.let { event ->
                diagnostics.record(
                    event,
                    mapOf(
                        "eventType" to type.name,
                        "reason" to outcome.name,
                        "durationMs" to duration.toString(),
                    ),
                )
            }
        }
    }

    fun refresh(reason: String = "manual", notice: String? = mutableState.value.notice) {
        if (!initialized) return
        val previous = mutableState.value
        val next = currentState().copy(lastWake = previous.lastWake, notice = notice)
        mutableState.value = next
        if (previous.installed != next.installed ||
            previous.running != next.running ||
            previous.permissionGranted != next.permissionGranted ||
            previous.enabled != next.enabled
        ) {
            diagnostics.record(
                "SHIZUKU_STATE_REFRESH",
                mapOf(
                    "refreshReason" to reason,
                    "installed" to next.installed.toString(),
                    "binderAvailable" to next.running.toString(),
                    "permissionGranted" to next.permissionGranted.toString(),
                    "enhancedWakeEnabled" to next.enabled.toString(),
                ),
            )
        }
    }

    private fun currentState(): WakeUiState {
        val installed = gateway.isInstalled()
        val running = installed && gateway.isRunning()
        val granted = running && gateway.hasPermission()
        val enabled = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        return WakeUiState(installed, running, granted, enabled)
    }

    private fun diagnosticEvent(outcome: WakeOutcome): String? = when (outcome) {
        WakeOutcome.SUCCESS -> "WAKE_SUCCESS"
        WakeOutcome.COMPONENT_NOT_FOUND -> "WAKE_COMPONENT_NOT_FOUND"
        WakeOutcome.COMPONENT_NOT_EXPORTED -> "WAKE_COMPONENT_NOT_EXPORTED"
        WakeOutcome.BACKGROUND_RESTRICTED -> "WAKE_BACKGROUND_RESTRICTED"
        WakeOutcome.COMMAND_FAILED -> "WAKE_COMMAND_FAILED"
        WakeOutcome.EXCEPTION -> "WAKE_EXCEPTION"
        WakeOutcome.TIMEOUT -> "WAKE_TIMEOUT"
        WakeOutcome.UNAVAILABLE -> "WAKE_SHIZUKU_UNAVAILABLE"
        WakeOutcome.PERMISSION_DENIED -> "WAKE_PERMISSION_DENIED"
        WakeOutcome.NOT_ELIGIBLE, WakeOutcome.DISABLED -> null
    }

    private fun recordLifecycle(event: String, fields: Map<String, String> = emptyMap()) {
        if (initialized) diagnostics.record(event, fields)
    }
}
