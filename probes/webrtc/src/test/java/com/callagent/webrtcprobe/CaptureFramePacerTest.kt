package com.callagent.webrtcprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFramePacerTest {
    @Test fun frameWorkCountsTowardTheCapturePeriod() {
        var time = 0L
        val sleeps = mutableListOf<Long>()
        val pacer = CaptureFramePacer({ time }, { sleeps += it; time += it })
        pacer.awaitNextFrame()
        time += 3_000_000L
        pacer.awaitNextFrame()
        assertEquals(10_000_000L, time)
        assertEquals(listOf(7_000_000L), sleeps)
        pacer.awaitNextFrame()
        assertEquals(20_000_000L, time)
    }

    @Test fun stalledCaptureDoesNotProduceAnUnboundedCatchUpBurst() {
        var time = 0L
        val pacer = CaptureFramePacer({ time }, { time += it })
        pacer.awaitNextFrame()
        time = 100_000_000L
        pacer.awaitNextFrame()
        assertEquals(100_000_000L, time)
        pacer.awaitNextFrame()
        assertEquals(110_000_000L, time)
        pacer.awaitNextFrame()
        assertEquals(120_000_000L, time)
    }

    @Test fun spuriousWakeupDoesNotInjectAnEarlyFrame() {
        var time = 0L
        var waits = 0
        val pacer = CaptureFramePacer({ time }, { if (++waits > 1) time += it })
        pacer.awaitNextFrame()
        pacer.awaitNextFrame()
        assertEquals(10_000_000L, time)
        assertEquals(2, waits)
    }
}
