package com.callagent.webrtcprobe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic PCM only. It neither opens an Android audio device nor controls telephony. */
class SyntheticPcm(private val frequency: Double) {
    private var phase = 0.0
    private val stopped = AtomicBoolean(false)
    val frames = AtomicInteger()

    fun fill(buffer: ByteBuffer, format: Int, channels: Int, sampleRate: Int): Long {
        require(format == PCM_16_BIT && channels == 1 && sampleRate == SAMPLE_RATE)
        require(buffer.capacity() > 0 && buffer.capacity() % 2 == 0)
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val silent = stopped.get()
        val step = 2 * PI * frequency / sampleRate
        while (buffer.hasRemaining()) {
            buffer.putShort(if (silent) 0 else (sin(phase) * 6000).toInt().toShort())
            phase = (phase + step) % (2 * PI)
        }
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

    fun accept(format: Int, channels: Int, rate: Int, bytes: ByteArray) {
        require(format == SyntheticPcm.PCM_16_BIT && channels == 1 && rate == SyntheticPcm.SAMPLE_RATE)
        require(bytes.size >= 2 && bytes.size % 2 == 0)
        val samples = DoubleArray(bytes.size / 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) samples[i] = buffer.short.toDouble()
        val rms = sqrt(samples.sumOf { it * it } / samples.size)
        frames.incrementAndGet()
        if (rms > 100 && power(samples, expectedFrequency, rate) >
            4 * power(samples, rejectedFrequency, rate)) matchedFrames.incrementAndGet()
    }

    private fun power(samples: DoubleArray, frequency: Double, rate: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        val step = 2 * PI * frequency / rate
        for (i in samples.indices) {
            real += samples[i] * cos(step * i)
            imaginary += samples[i] * sin(step * i)
        }
        return real * real + imaginary * imaginary
    }
}
