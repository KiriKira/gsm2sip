package org.webrtc.audio

import org.junit.Assert.*
import org.junit.Test

class ExternalPlayoutPacerTest {
    @Test fun normalCadenceIncludesWorkTime() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = ExternalPlayout.FramePacer({ now }, { waits += it; now += it })
        pacer.awaitNextFrame()
        now += 2_000_000L
        pacer.awaitNextFrame()
        now += 2_000_000L
        pacer.awaitNextFrame()
        assertEquals(listOf(8_000_000L, 8_000_000L), waits)
        assertEquals(20_000_000L, now)
    }

    @Test fun stalledPlayoutDoesNotImmediatelyPullAnotherFrame() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = ExternalPlayout.FramePacer({ now }, { waits += it; now += it })
        pacer.awaitNextFrame()
        now = 100_000_000L
        pacer.awaitNextFrame()
        assertTrue(waits.isEmpty())
        pacer.awaitNextFrame()
        assertEquals(listOf(10_000_000L), waits)
        assertEquals(110_000_000L, now)
    }

    @Test fun spuriousWakeupsDoNotPullEarly() {
        var now = 0L
        var wakes = 0
        val pacer = ExternalPlayout.FramePacer({ now }, { now += minOf(it, 2_000_000L); wakes++ })
        pacer.awaitNextFrame()
        pacer.awaitNextFrame()
        assertEquals(10_000_000L, now)
        assertEquals(5, wakes)
    }

    @Test fun stopInterruptCannotLeavePacerWaiting() {
        var parks = 0
        val pacer = ExternalPlayout.FramePacer({ 0L }, { parks++ })
        pacer.awaitNextFrame()
        Thread.currentThread().interrupt()
        try { pacer.awaitNextFrame(); assertEquals(0, parks) }
        finally { Thread.interrupted() }
    }
}
