package com.largebatata.fcmhelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.largebatata.fcmhelper.wake.WakeUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticExporter(private val context: Context) {
    fun create(status: MonitorStatus, wake: WakeUiState): File {
        val app = packageVersion(context.packageName)
        val outputDirectory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        outputDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("fcm-helper-diagnostics-") }
            ?.forEach(File::delete)
        val output = File(
            outputDirectory,
            "fcm-helper-diagnostics-${fileTimestamp(System.currentTimeMillis())}.txt",
        )
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("FCM Helper diagnostics")
            writer.appendLine("Generated: ${displayTimestamp(System.currentTimeMillis())}")
            writer.appendLine("App version: ${app.first}")
            writer.appendLine("Version code: ${app.second}")
            writer.appendLine("Package: ${context.packageName}")
            writer.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            writer.appendLine("Manufacturer: ${Build.MANUFACTURER}")
            writer.appendLine("Device model: ${Build.MODEL}")
            writer.appendLine("WeChat: ${packageVersion("com.tencent.mm").first}")
            writer.appendLine("microG: ${packageVersion("com.google.android.gms").first}")
            writer.appendLine("READ_LOGS granted: ${granted(Manifest.permission.READ_LOGS)}")
            writer.appendLine("Notification permission granted: ${notificationGranted()}")
            writer.appendLine("Monitor service: ${if (status.serviceRunning) "RUNNING" else "STOPPED"}")
            writer.appendLine("Reader state: ${status.readerState.name}")
            writer.appendLine("Reader child alive: ${status.readerAlive}")
            writer.appendLine("Reader child PID: ${status.readerPid ?: "NONE"}")
            writer.appendLine("Reader reconnect count: ${status.reconnectCount}")
            writer.appendLine("Last reader start: ${displayTimestamp(status.lastReaderStart)}")
            writer.appendLine("Last reader activity: ${displayTimestamp(status.lastSuccessfulLogLine)}")
            writer.appendLine("Last reader exit code: ${status.lastReaderExitCode ?: "NONE"}")
            writer.appendLine("Last reader failure: ${status.lastReaderFailureType ?: "NONE"}")
            writer.appendLine("Last WeChat FCM: ${displayTimestamp(status.lastFcm)}")
            writer.appendLine("Recent event: ${status.recentEvent?.name ?: "NONE"}")
            writer.appendLine("Shizuku installed: ${wake.installed}")
            writer.appendLine("Shizuku connected: ${wake.running}")
            writer.appendLine("Shizuku permission: ${wake.permissionGranted}")
            writer.appendLine("Enhanced wake enabled: ${wake.enabled}")
            writer.appendLine("Last wake result: ${wake.lastWake?.outcome?.name ?: "NONE"}")
            writer.appendLine()
            writer.appendLine("Reader diagnostics (privacy-minimized)")
            ReaderDiagnostics(context).readForExport().forEach { (name, content) ->
                writer.appendLine("--- $name ---")
                writer.append(content)
                if (!content.endsWith('\n')) writer.appendLine()
            }
        }
        return output
    }

    fun packageVersion(packageName: String): Pair<String, Long> = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(packageName, 0)
        (info.versionName ?: "未知") to info.longVersionCode
    }.getOrDefault("未知" to 0L)

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun displayTimestamp(value: Long?): String = value?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(it))
    } ?: "NONE"

    private fun fileTimestamp(value: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(value))

    private companion object {
        const val DIRECTORY = "shared-diagnostics"
    }
}
