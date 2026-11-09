package com.largebatata.fcmhelper.logconfig

import android.content.Context
import com.largebatata.fcmhelper.ReaderDiagnostics
import com.largebatata.fcmhelper.core.LogPropertyCheck
import com.largebatata.fcmhelper.core.LogPropertyRepairReport
import com.largebatata.fcmhelper.core.checkLogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class LogPropertyUiState(
    val busy: Boolean = false,
    val checkedAt: Long? = null,
    val checks: List<LogPropertyCheck> = emptyList(),
    val lastRepair: LogPropertyRepairReport? = null,
    val notice: String? = null,
)

object LogPropertyManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = AtomicBoolean(false)
    private val mutable = MutableStateFlow(LogPropertyUiState())
    val state = mutable.asStateFlow()
    private lateinit var gateway: LogPropertyShizukuGateway
    private lateinit var diagnostics: ReaderDiagnostics
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        gateway = LogPropertyShizukuGateway(context.applicationContext)
        diagnostics = ReaderDiagnostics(context.applicationContext)
        initialized = true
        refresh()
    }

    fun refresh() {
        if (!initialized || !operation.compareAndSet(false, true)) return
        mutable.value = mutable.value.copy(busy = true)
        scope.launch {
            try {
                mutable.value = mutable.value.copy(checks = readChecks(), checkedAt = System.currentTimeMillis())
            } finally {
                mutable.value = mutable.value.copy(busy = false)
                operation.set(false)
            }
        }
    }

    /** The only write entry point, called by the explicit UI repair button; never from startup/refresh. */
    fun repair() {
        if (!initialized || !operation.compareAndSet(false, true)) return
        mutable.value = mutable.value.copy(busy = true, lastRepair = null, notice = null)
        scope.launch {
            try {
                val before = readChecks()
                mutable.value = mutable.value.copy(checks = before, checkedAt = System.currentTimeMillis())
                if (!gateway.isRunning()) {
                    mutable.value = mutable.value.copy(notice = "Shizuku 未启动，请先启动后再重试。")
                    return@launch
                }
                if (!gateway.hasPermission()) {
                    mutable.value = mutable.value.copy(notice = "Shizuku 未授权，请先点击授予 Shizuku 权限。")
                    return@launch
                }
                var failure: String? = null
                val exits = try {
                    gateway.repair()
                } catch (_: SecurityException) {
                    failure = "Shizuku 权限不足，未能完成修复。"
                    emptyList()
                } catch (_: TimeoutCancellationException) {
                    failure = "连接 Shizuku 修复服务超时。"
                    emptyList()
                } catch (_: Exception) {
                    failure = "Shizuku 修复服务失败或连接已断开。"
                    emptyList()
                }
                // Read back even after IPC failure: some commands may already have executed.
                val after = readChecks()
                val report = LogPropertyRepairReport(before, after, exits)
                diagnostics.record("LOG_PROPERTIES_REPAIR", mapOf("verified" to report.verified.toString()))
                mutable.value = mutable.value.copy(
                    checks = after, checkedAt = System.currentTimeMillis(), lastRepair = report,
                    notice = if (report.verified) "四项临时属性已设置，读回验证通过。重启后请重新检查。"
                    else (failure ?: "修复未全部通过，ROM 可能拒绝设置；请查看逐项结果，不要视为已修好。"),
                )
            } finally {
                mutable.value = mutable.value.copy(busy = false)
                operation.set(false)
            }
        }
    }

    private fun readChecks(): List<LogPropertyCheck> = checkLogProperties(LogPropertyCommands::read).also { checks ->
        diagnostics.record("LOG_PROPERTIES_CHECK", checks.associate { it.property.name to it.state.name })
    }
}
