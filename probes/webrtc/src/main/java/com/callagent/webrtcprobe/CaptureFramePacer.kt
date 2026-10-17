package com.callagent.webrtcprobe

import java.util.concurrent.locks.LockSupport

/** The SDK's external capture callback replaces the blocking AudioRecord.read. */
class CaptureFramePacer(
    private val now: () -> Long = System::nanoTime,
    private val park: (Long) -> Unit = { LockSupport.parkNanos(it) }
) {
    private var nextFrame: Long? = null

    fun awaitNextFrame() {
        val deadline = nextFrame ?: now()
        var remaining = deadline - now()
        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
            park(remaining)
            remaining = deadline - now()
        }
        val deliveredAt = now()
        // Keep normal 10 ms cadence, but do not burst stale frames after a stall.
        nextFrame = if (deliveredAt - deadline >= PERIOD_NS) deliveredAt + PERIOD_NS else deadline + PERIOD_NS
    }

    companion object {
        const val PERIOD_NS = 10_000_000L
    }
}
