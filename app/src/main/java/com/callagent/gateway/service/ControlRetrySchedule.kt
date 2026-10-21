package com.callagent.gateway.service

/** Retry timing for the primary control plane, with healthy mesh nodes keeping the loop responsive. */
internal class ControlRetrySchedule(
    private val intervalMs: Long,
    private val maxRetryMs: Long
) {
    var currentDelayMs: Long = intervalMs
        private set

    fun primarySucceeded(): Long {
        currentDelayMs = intervalMs
        return currentDelayMs
    }

    fun primaryFailed(retryable: Boolean): Long {
        currentDelayMs = if (retryable) (currentDelayMs * 2).coerceAtMost(maxRetryMs) else intervalMs
        return currentDelayMs
    }

    fun secondarySucceeded(): Long {
        currentDelayMs = intervalMs
        return currentDelayMs
    }
}

/** Run the production secondary-sync block and only reset primary backoff when a node synced. */
internal fun syncSecondaryNodesAndRetryAfter(
    retrySchedule: ControlRetrySchedule,
    withWakeLock: (block: () -> Unit) -> Boolean,
    syncAll: () -> Boolean,
    onBackoffReset: () -> Unit
): Long {
    var secondarySynced = false
    val mayRun = withWakeLock { secondarySynced = syncAll() }
    if (!mayRun || !secondarySynced) return retrySchedule.currentDelayMs

    val previousDelayMs = retrySchedule.currentDelayMs
    val nextDelayMs = retrySchedule.secondarySucceeded()
    if (nextDelayMs < previousDelayMs) onBackoffReset()
    return nextDelayMs
}
