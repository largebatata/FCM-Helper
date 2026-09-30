package com.largebatata.fcmhelper.wake

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class WeChatWakeUserService : IWeChatWakeService.Stub {
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun startCoreService(): Int {
        val process = try {
            ProcessBuilder(
                "/system/bin/am",
                "startservice",
                "--user",
                "0",
                "-n",
                "com.tencent.mm/.booter.CoreService",
            ).redirectErrorStream(true).start()
        } catch (_: Throwable) {
            return RESULT_EXCEPTION
        }
        return try {
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                RESULT_TIMEOUT
            } else if (process.exitValue() == 0) {
                RESULT_SUCCESS
            } else {
                classifyFailure(process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT_CHARS) })
            }
        } catch (_: Throwable) {
            process.destroyForcibly()
            RESULT_EXCEPTION
        }
    }

    private fun classifyFailure(output: String): Int {
        val normalized = output.lowercase()
        return when {
            "not exported" in normalized -> RESULT_COMPONENT_NOT_EXPORTED
            "component" in normalized && ("not found" in normalized || "does not exist" in normalized) ->
                RESULT_COMPONENT_NOT_FOUND
            "app is in background" in normalized -> RESULT_BACKGROUND_RESTRICTED
            "permission denial" in normalized || "requires permission" in normalized -> RESULT_PERMISSION_DENIED
            else -> RESULT_FAILED
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    companion object {
        const val RESULT_SUCCESS = 0
        const val RESULT_FAILED = -1
        const val RESULT_TIMEOUT = -2
        const val RESULT_COMPONENT_NOT_FOUND = -3
        const val RESULT_COMPONENT_NOT_EXPORTED = -4
        const val RESULT_PERMISSION_DENIED = -5
        const val RESULT_BACKGROUND_RESTRICTED = -6
        const val RESULT_EXCEPTION = -7
        private const val COMMAND_TIMEOUT_MS = 1_800L
        private const val MAX_OUTPUT_CHARS = 512
    }
}
