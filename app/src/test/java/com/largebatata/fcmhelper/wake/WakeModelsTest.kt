package com.largebatata.fcmhelper.wake

import com.largebatata.fcmhelper.core.WeChatFcmType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeModelsTest {
    @Test
    fun unknownDoesNotWake() = runBlocking {
        var invoked = false
        val executor = WakeExecutor(WakeGateway { invoked = true; WakeCommandResult.SUCCESS })
        val result = executor.execute(WeChatFcmType.UNKNOWN, true, true, true)
        assertEquals(WakeOutcome.NOT_ELIGIBLE, result)
        assertFalse(invoked)
    }

    @Test
    fun eligibleTypesWakeWhenReady() = runBlocking {
        WeChatFcmType.entries.filter(WakePolicy::isEligible).forEach { type ->
            var invoked = false
            val executor = WakeExecutor(WakeGateway { invoked = true; WakeCommandResult.SUCCESS })
            assertEquals(WakeOutcome.SUCCESS, executor.execute(type, true, true, true))
            assertTrue(invoked)
        }
    }

    @Test
    fun gatewayFailureIsContained() = runBlocking {
        val executor = WakeExecutor(WakeGateway { error("Shizuku failure") })
        assertEquals(
            WakeOutcome.EXCEPTION,
            executor.execute(WeChatFcmType.MESSAGE, true, true, true),
        )
    }

    @Test
    fun detailedCommandFailureIsPreserved() = runBlocking {
        val mappings = mapOf(
            WakeCommandResult.COMPONENT_NOT_FOUND to WakeOutcome.COMPONENT_NOT_FOUND,
            WakeCommandResult.COMPONENT_NOT_EXPORTED to WakeOutcome.COMPONENT_NOT_EXPORTED,
            WakeCommandResult.PERMISSION_DENIED to WakeOutcome.PERMISSION_DENIED,
            WakeCommandResult.BACKGROUND_RESTRICTED to WakeOutcome.BACKGROUND_RESTRICTED,
            WakeCommandResult.COMMAND_FAILED to WakeOutcome.COMMAND_FAILED,
            WakeCommandResult.EXCEPTION to WakeOutcome.EXCEPTION,
        )
        mappings.forEach { (command, expected) ->
            val executor = WakeExecutor(WakeGateway { command })
            assertEquals(expected, executor.execute(WeChatFcmType.MESSAGE, true, true, true))
        }
    }

    @Test
    fun timeoutReturnsTimeout() = runBlocking {
        val executor = WakeExecutor(WakeGateway { delay(100); WakeCommandResult.SUCCESS }, timeoutMs = 10)
        assertEquals(
            WakeOutcome.TIMEOUT,
            executor.execute(WeChatFcmType.CALL, true, true, true),
        )
    }
}
