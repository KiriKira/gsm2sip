package com.callagent.webrtcprobe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic PCM only. It neither opens an Android audio device nor controls telephony. */
class SyntheticPcm(private val frequency: Double) {
    private val sineStep = sin(2 * PI * frequency / SAMPLE_RATE)
    private val cosineStep = cos(2 * PI * frequency / SAMPLE_RATE)
    private var sinePhase = 0.0
    private var cosinePhase = 1.0
    private var frameBytes = ByteArray(0)
    private var repeatingFrame = false
    private val stopped = AtomicBoolean(false)
    val frames = AtomicInteger()

    fun fill(buffer: ByteBuffer, format: Int, channels: Int, sampleRate: Int): Long {
        require(format == PCM_16_BIT && channels == 1 && sampleRate == SAMPLE_RATE)
        require(buffer.capacity() > 0 && buffer.capacity() % 2 == 0)
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        if (frameBytes.size != buffer.capacity()) {
            frameBytes = ByteArray(buffer.capacity())
            repeatingFrame = false
        }
        if (stopped.get()) {
            frameBytes.fill(0)
            repeatingFrame = false
        } else if (!repeatingFrame) {
            for (i in frameBytes.indices step 2) {
                val sample = (sinePhase * 6000).toInt()
                frameBytes[i] = sample.toByte()
                frameBytes[i + 1] = (sample shr 8).toByte()
                val nextSine = sinePhase * cosineStep + cosinePhase * sineStep
                cosinePhase = cosinePhase * cosineStep - sinePhase * sineStep
                sinePhase = nextSine
            }
            // Keep the oscillator on the unit circle without per-sample trig calls.
            val magnitude = sqrt(sinePhase * sinePhase + cosinePhase * cosinePhase)
            sinePhase /= magnitude
            cosinePhase /= magnitude
            // Whole-cycle windows repeat at the same phase (the probe uses 1/1.5 kHz).
            val cycles = frequency * (frameBytes.size / 2) / SAMPLE_RATE
            repeatingFrame = abs(cycles - round(cycles)) < 1e-10
        }
        // One bulk JNI copy rather than one DirectByteBuffer operation per sample.
        buffer.put(frameBytes)
        buffer.rewind()
        frames.incrementAndGet()
        return System.nanoTime()
    }

    fun stop() { stopped.set(true) }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val PCM_16_BIT = 2
    }
}

/** Checks the opposite peer's tone, so local echo, silence or callback activity cannot pass. */
class ToneDetector(private val expectedFrequency: Double, private val rejectedFrequency: Double) {
    val frames = AtomicInteger()
    val matchedFrames = AtomicInteger()
    @Volatile var lastRms = 0.0
    private var window: Window? = null

    fun accept(format: Int, channels: Int, rate: Int, bytes: ByteArray) {
        require(format == SyntheticPcm.PCM_16_BIT && channels == 1 && rate == SyntheticPcm.SAMPLE_RATE)
        require(bytes.size >= 2 && bytes.size % 2 == 0)
        val count = bytes.size / 2
        val coefficients = window?.takeIf { it.count == count && it.rate == rate }
            ?: Window(count, rate, expectedFrequency, rejectedFrequency).also { window = it }
        val expectedCosine = coefficients.expectedCosine
        val expectedSine = coefficients.expectedSine
        val rejectedCosine = coefficients.rejectedCosine
        val rejectedSine = coefficients.rejectedSine
        var sumSquares = 0.0
        var expectedReal = 0.0
        var expectedImaginary = 0.0
        var rejectedReal = 0.0
        var rejectedImaginary = 0.0
        for (i in 0 until count) {
            val sample = ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort().toDouble()
            sumSquares += sample * sample
            expectedReal += sample * expectedCosine[i]
            expectedImaginary += sample * expectedSine[i]
            rejectedReal += sample * rejectedCosine[i]
            rejectedImaginary += sample * rejectedSine[i]
        }
        val rms = sqrt(sumSquares / count)
        lastRms = rms
        frames.incrementAndGet()
        val expectedPower = expectedReal * expectedReal + expectedImaginary * expectedImaginary
        val rejectedPower = rejectedReal * rejectedReal + rejectedImaginary * rejectedImaginary
        if (rms > 100 && expectedPower > 4 * rejectedPower) matchedFrames.incrementAndGet()
    }

    private class Window(val count: Int, val rate: Int, expected: Double, rejected: Double) {
        private val expectedStep = 2 * PI * expected / rate
        private val rejectedStep = 2 * PI * rejected / rate
        val expectedCosine = DoubleArray(count) { cos(expectedStep * it) }
        val expectedSine = DoubleArray(count) { sin(expectedStep * it) }
        val rejectedCosine = DoubleArray(count) { cos(rejectedStep * it) }
        val rejectedSine = DoubleArray(count) { sin(rejectedStep * it) }
    }
}
