package com.largebatata.fcmhelper.core

/** Evidence is observational: an idle, live process alone never proves event delivery. */
data class ActivityWatcherEvidence(
    val state: ReaderState = ReaderState.Stopped,
    val pid: Long? = null,
    val childAlive: Boolean = false,
    val childStartedAt: Long? = null,
    val lastEventRead: Long? = null,
    val lastMainWeChatVisible: Long? = null,
    val lastCancellationConfirmed: Long? = null,
) {
    val running: Boolean get() = childAlive && state in setOf(ReaderState.WaitingForLogs, ReaderState.Receiving)
    val eventDeliveryVerified: Boolean get() = hasCurrentLogEvidence(state, childAlive, childStartedAt, lastEventRead)
}

fun hasCurrentLogEvidence(state: ReaderState, alive: Boolean, startedAt: Long?, readAt: Long?): Boolean =
    alive && state in setOf(ReaderState.WaitingForLogs, ReaderState.Receiving) &&
        startedAt != null && readAt != null && readAt >= startedAt
