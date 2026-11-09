package com.largebatata.fcmhelper.core

import org.junit.Assert.*
import org.junit.Test

class ListenerEvidenceTest {
    @Test fun processAndReadLoopDoNotProveSystemEventDelivery() {
        val child = ActivityWatcherEvidence(pid = 100, childAlive = true, childStartedAt = 10)
        assertFalse(child.running)
        assertFalse(child.eventDeliveryVerified)
        val waiting = child.copy(state = ReaderState.WaitingForLogs)
        assertTrue(waiting.running)
        assertFalse(waiting.eventDeliveryVerified)
        // A logcat banner or error can set Receiving without a valid parsed event.
        assertFalse(waiting.copy(state = ReaderState.Receiving).eventDeliveryVerified)
    }

    @Test fun eventRecognitionAndCancellationAreIndependentEvidence() {
        val event = ActivityWatcherEvidence(state = ReaderState.Receiving, childAlive = true,
            childStartedAt = 10, lastEventRead = 11)
        assertTrue(event.eventDeliveryVerified)
        assertNull(event.lastMainWeChatVisible)
        assertNull(event.lastCancellationConfirmed)
        val recognized = event.copy(lastMainWeChatVisible = 12)
        assertNull(recognized.lastCancellationConfirmed)
        assertEquals(13L, recognized.copy(lastCancellationConfirmed = 13).lastCancellationConfirmed)
    }

    @Test fun reconnectDoesNotReuseOldReadEvidenceAsCurrentSuccess() {
        val restarted = ActivityWatcherEvidence(state = ReaderState.WaitingForLogs, childAlive = true,
            childStartedAt = 20, lastEventRead = 11, lastMainWeChatVisible = 12, lastCancellationConfirmed = 13)
        assertFalse(restarted.eventDeliveryVerified)
        assertTrue(restarted.copy(lastEventRead = 21).eventDeliveryVerified)
        assertFalse(restarted.copy(childAlive = false, lastEventRead = 21).eventDeliveryVerified)
    }

    @Test fun fcmReaderAlsoNeedsValidCurrentRecordNotOnlyChildOrBanner() {
        assertFalse(hasCurrentLogEvidence(ReaderState.Receiving, true, 20, null))
        assertFalse(hasCurrentLogEvidence(ReaderState.WaitingForLogs, true, 20, 19))
        assertTrue(hasCurrentLogEvidence(ReaderState.Receiving, true, 20, 21))
        assertFalse(hasCurrentLogEvidence(ReaderState.AccessDenied, true, 20, 21))
        assertFalse(hasCurrentLogEvidence(ReaderState.Stopped, false, 20, 21))
    }
}
