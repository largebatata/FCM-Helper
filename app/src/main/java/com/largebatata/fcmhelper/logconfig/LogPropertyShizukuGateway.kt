package com.largebatata.fcmhelper.logconfig

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

internal class LogPropertyShizukuGateway(context: Context) {
    private val args = Shizuku.UserServiceArgs(ComponentName(context, LogPropertyUserService::class.java))
        .daemon(false).processNameSuffix("log_repair").tag("log_property_repair").version(1).debuggable(false)

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun hasPermission(): Boolean = isRunning() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun repair(): List<Int> {
        check(isRunning()) { "Shizuku unavailable" }
        if (!hasPermission()) throw SecurityException("Shizuku permission missing")
        val ready = CompletableDeferred<ILogPropertyService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = binder?.let(ILogPropertyService.Stub::asInterface)
                if (service != null) ready.complete(service)
                else ready.completeExceptionally(IllegalStateException("No property service binder"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                ready.completeExceptionally(IllegalStateException("Property service disconnected"))
            }
        }
        try {
            Shizuku.bindUserService(args, connection)
            val service = withTimeout(10_000L) { ready.await() }
            // Each of the four fixed commands is bounded in the privileged service.
            return service.repairProperties().toList()
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}
