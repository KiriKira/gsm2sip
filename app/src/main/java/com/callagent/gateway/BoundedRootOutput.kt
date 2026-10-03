package com.callagent.gateway

import java.io.IOException
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounds a single line as well as an entire reply: readLine() alone can allocate without limit. */
internal fun readBoundedRootLine(reader: Reader, maxChars: Int): String? {
    val line = StringBuilder()
    while (true) {
        val next = reader.read()
        if (next == -1) return if (line.isEmpty()) null else line.toString()
        if (next == '\n'.code) return line.toString().removeSuffix("\r")
        if (line.length >= maxChars) throw IOException("Root reply exceeds output limit")
        line.append(next.toChar())
    }
}

/** A one-shot su fallback must drain both pipes and respect one deadline, even without a newline. */
internal object BoundedRootProcess {
    data class Result(val exitCode: Int, val output: String)

    fun run(process: Process, timeoutMs: Long, maxOutputChars: Int): Result {
        val failed = AtomicBoolean(false)
        val finished = CountDownLatch(2)
        val output = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        Thread({
            try {
                process.inputStream.reader().use { reader ->
                    val chunk = CharArray(2048)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        if (output.length + count > maxOutputChars) {
                            failed.set(true)
                            process.destroyForcibly()
                            break
                        }
                        output.append(chunk, 0, count)
                    }
                }
            } catch (_: Exception) { failed.set(true) }
            finally { finished.countDown() }
        }, "RootFallback-Out").apply { isDaemon = true; start() }
        Thread({
            try {
                process.errorStream.use { stream ->
                    val chunk = ByteArray(2048)
                    while (stream.read(chunk) >= 0) { /* discard; never block stderr or log payloads */ }
                }
            } catch (_: Exception) { failed.set(true) }
            finally { finished.countDown() }
        }, "RootFallback-Err").apply { isDaemon = true; start() }
        return try {
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            val drained = exited && finished.await(remaining, TimeUnit.NANOSECONDS)
            if (!drained || failed.get()) Result(-1, "")
            else Result(process.exitValue(), output.toString().trimEnd())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result(-1, "")
        } finally {
            // Terminate before closing pipes: BufferedReader.close can otherwise wait on a blocked read.
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
