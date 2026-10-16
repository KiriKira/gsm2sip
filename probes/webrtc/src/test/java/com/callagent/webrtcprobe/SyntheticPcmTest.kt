package com.callagent.webrtcprobe

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SyntheticPcmTest {
    @Test fun oppositeTonePassesButSilenceAndLocalEchoDoNot() {
        val source = SyntheticPcm(1000.0)
        val expected = ToneDetector(1000.0, 1500.0)
        val localEcho = ToneDetector(1500.0, 1000.0)
        val silence = ToneDetector(1000.0, 1500.0)
        val frame = ByteBuffer.allocateDirect(960)
        repeat(100) {
            source.fill(frame, 2, 1, 48000)
            val data = ByteArray(frame.remaining()).also { frame.get(it) }
            expected.accept(2, 1, 48000, data)
            localEcho.accept(2, 1, 48000, data)
            silence.accept(2, 1, 48000, ByteArray(960))
        }
        assertEquals(100, expected.matchedFrames.get())
        assertEquals(0, localEcho.matchedFrames.get())
        assertEquals(0, silence.matchedFrames.get())
    }

    @Test fun stoppedSourceOverwritesTheWholeBufferWithSilence() {
        val source = SyntheticPcm(1500.0)
        val frame = ByteBuffer.allocateDirect(960)
        source.fill(frame, 2, 1, 48000)
        assertTrue((0 until frame.limit()).any { frame.get(it).toInt() != 0 })
        source.stop()
        source.fill(frame, 2, 1, 48000)
        assertEquals(0, frame.position())
        assertTrue((0 until frame.limit()).all { frame.get(it).toInt() == 0 })
    }

    @Test fun toneUsesSignedLittleEndianPcm16() {
        val frame = ByteBuffer.allocateDirect(960)
        SyntheticPcm(1000.0).fill(frame, 2, 1, 48000)
        assertEquals(0, frame.get(0).toInt())
        assertEquals(0, frame.get(1).toInt())
        val second = (frame.get(2).toInt() and 255) or (frame.get(3).toInt() shl 8)
        assertTrue(second in 700..850)
    }

    @Test fun unsupportedFormatsFailInsteadOfPretendingToResample() {
        val source = SyntheticPcm(1000.0)
        for ((format, channels, rate) in listOf(Triple(3, 1, 48000), Triple(2, 2, 48000), Triple(2, 1, 8000))) {
            try {
                source.fill(ByteBuffer.allocateDirect(960), format, channels, rate)
                fail("Unsupported PCM format was accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
