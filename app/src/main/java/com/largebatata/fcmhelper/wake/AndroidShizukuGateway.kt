package com.largebatata.fcmhelper.wake

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class AndroidShizukuGateway(private val context: Context) : WakeGateway {
    private val weChatCoreService = ComponentName(
        WECHAT_PACKAGE,
        "$WECHAT_PACKAGE.booter.CoreService",
    )
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context, WeChatWakeUserService::class.java),
    )
        .daemon(false)
        .processNameSuffix("wechat_wake")
        .tag("wechat_core_wake")
        .version(1)
        .debuggable(false)

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
    }.isSuccess

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = isRunning() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    override suspend fun startCoreService(): WakeCommandResult = withContext(Dispatchers.IO) {
        val serviceInfo = runCatching {
            context.packageManager.getServiceInfo(weChatCoreService, 0)
        }.getOrElse { return@withContext WakeCommandResult.COMPONENT_NOT_FOUND }
        if (!serviceInfo.enabled) return@withContext WakeCommandResult.COMPONENT_NOT_FOUND
        if (!serviceInfo.exported) return@withContext WakeCommandResult.COMPONENT_NOT_EXPORTED

        val service = CompletableDeferred<IWeChatWakeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val api = binder?.let(IWeChatWakeService.Stub::asInterface)
                if (api != null) service.complete(api)
                else service.completeExceptionally(IllegalStateException("Missing UserService binder"))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!service.isCompleted) {
                    service.completeExceptionally(IllegalStateException("UserService disconnected"))
                }
            }
        }
        try {
            Shizuku.bindUserService(serviceArgs, connection)
            when (service.await().startCoreService()) {
                WeChatWakeUserService.RESULT_SUCCESS -> WakeCommandResult.SUCCESS
                WeChatWakeUserService.RESULT_TIMEOUT -> WakeCommandResult.TIMEOUT
                WeChatWakeUserService.RESULT_COMPONENT_NOT_FOUND -> WakeCommandResult.COMPONENT_NOT_FOUND
                WeChatWakeUserService.RESULT_COMPONENT_NOT_EXPORTED -> WakeCommandResult.COMPONENT_NOT_EXPORTED
                WeChatWakeUserService.RESULT_PERMISSION_DENIED -> WakeCommandResult.PERMISSION_DENIED
                WeChatWakeUserService.RESULT_BACKGROUND_RESTRICTED -> WakeCommandResult.BACKGROUND_RESTRICTED
                WeChatWakeUserService.RESULT_EXCEPTION -> WakeCommandResult.EXCEPTION
                else -> WakeCommandResult.COMMAND_FAILED
            }
        } finally {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
