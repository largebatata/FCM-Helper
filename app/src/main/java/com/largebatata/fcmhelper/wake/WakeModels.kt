package com.largebatata.fcmhelper.wake

import com.largebatata.fcmhelper.core.WeChatFcmType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class WakeCommandResult {
    SUCCESS,
    COMPONENT_NOT_FOUND,
    COMPONENT_NOT_EXPORTED,
    PERMISSION_DENIED,
    BACKGROUND_RESTRICTED,
    COMMAND_FAILED,
    TIMEOUT,
    EXCEPTION,
}

enum class WakeOutcome {
    NOT_ELIGIBLE,
    DISABLED,
    UNAVAILABLE,
    PERMISSION_DENIED,
    SUCCESS,
    COMPONENT_NOT_FOUND,
    COMPONENT_NOT_EXPORTED,
    BACKGROUND_RESTRICTED,
    COMMAND_FAILED,
    TIMEOUT,
    EXCEPTION,
}

fun interface WakeGateway {
    suspend fun startCoreService(): WakeCommandResult
}

object WakePolicy {
    fun isEligible(type: WeChatFcmType): Boolean = when (type) {
        WeChatFcmType.MESSAGE, WeChatFcmType.CALL, WeChatFcmType.PC_LOGIN -> true
        WeChatFcmType.UNKNOWN -> false
    }
}

class WakeExecutor(
    private val gateway: WakeGateway,
    private val timeoutMs: Long = 2_500L,
) {
    suspend fun execute(
        type: WeChatFcmType,
        enabled: Boolean,
        available: Boolean,
        permissionGranted: Boolean,
    ): WakeOutcome {
        if (!WakePolicy.isEligible(type)) return WakeOutcome.NOT_ELIGIBLE
        if (!enabled) return WakeOutcome.DISABLED
        if (!available) return WakeOutcome.UNAVAILABLE
        if (!permissionGranted) return WakeOutcome.PERMISSION_DENIED
        return try {
            withTimeout(timeoutMs) {
                when (gateway.startCoreService()) {
                    WakeCommandResult.SUCCESS -> WakeOutcome.SUCCESS
                    WakeCommandResult.COMPONENT_NOT_FOUND -> WakeOutcome.COMPONENT_NOT_FOUND
                    WakeCommandResult.COMPONENT_NOT_EXPORTED -> WakeOutcome.COMPONENT_NOT_EXPORTED
                    WakeCommandResult.PERMISSION_DENIED -> WakeOutcome.PERMISSION_DENIED
                    WakeCommandResult.BACKGROUND_RESTRICTED -> WakeOutcome.BACKGROUND_RESTRICTED
                    WakeCommandResult.COMMAND_FAILED -> WakeOutcome.COMMAND_FAILED
                    WakeCommandResult.TIMEOUT -> WakeOutcome.TIMEOUT
                    WakeCommandResult.EXCEPTION -> WakeOutcome.EXCEPTION
                }
            }
        } catch (_: TimeoutCancellationException) {
            WakeOutcome.TIMEOUT
        } catch (_: Throwable) {
            WakeOutcome.EXCEPTION
        }
    }
}
