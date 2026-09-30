package com.largebatata.fcmhelper.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeUiStateTest {
    @Test fun readyStateEnablesSwitch() {
        val state = WakeUiState(running = true, permissionGranted = true)
        assertTrue(state.ready)
        assertTrue(state.switchEnabled)
    }

    @Test fun connectedWithoutPermissionOffersAuthorization() {
        val state = WakeUiState(running = true, permissionGranted = false)
        assertFalse(state.switchEnabled)
        assertTrue(state.canRequestPermission)
        assertEquals("未授权", state.permissionLabel)
    }

    @Test fun unavailableBinderUsesUnavailablePermissionLabel() {
        val state = WakeUiState(running = false, permissionGranted = false)
        assertFalse(state.switchEnabled)
        assertFalse(state.canRequestPermission)
        assertEquals("—", state.permissionLabel)
    }

    @Test fun userPreferenceIsIndependentFromRuntimeReadiness() {
        val state = WakeUiState(running = false, permissionGranted = false, enabled = true)
        assertTrue(state.enabled)
        assertFalse(state.ready)
    }

    @Test fun refreshedPermissionProducesReadyState() {
        val before = WakeUiState(running = true, permissionGranted = false)
        val after = before.copy(permissionGranted = true)
        assertFalse(before.ready)
        assertTrue(after.ready)
    }
}
