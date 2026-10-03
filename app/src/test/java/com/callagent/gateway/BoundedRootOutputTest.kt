package com.callagent.gateway

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.StringReader

class BoundedRootOutputTest {
    @Test fun oversizedLineWithoutNewlineIsRejected() {
        try {
            readBoundedRootLine(StringReader("x".repeat(4096)), 128)
            fail("Unbounded line was accepted")
        } catch (_: IOException) { }
    }

    @Test fun boundedLinesPreserveCrLfAndUnterminatedTail() {
        val reader = StringReader("first\r\nsecond")
        assertEquals("first", readBoundedRootLine(reader, 16))
        assertEquals("second", readBoundedRootLine(reader, 16))
        assertNull(readBoundedRootLine(reader, 16))
    }

    @Test fun fallbackDrainsLargeStderrAndCapturesStdout() {
        val process = ProcessBuilder("sh", "-c", "i=0; while [ \"\$i\" -lt 5000 ]; do echo discarded >&2; i=\$((i+1)); done; printf reply").start()
        val result = BoundedRootProcess.run(process, 5000, 128)
        assertEquals(0, result.exitCode)
        assertEquals("reply", result.output)
    }

    @Test fun fallbackRejectsOversizedOutputRatherThanReturningPartialProtocol() {
        val process = ProcessBuilder("sh", "-c", "i=0; while [ \"\$i\" -lt 100 ]; do printf 0123456789; i=\$((i+1)); done").start()
        val result = BoundedRootProcess.run(process, 5000, 128)
        assertEquals(-1, result.exitCode)
        assertEquals("", result.output)
    }

    @Test fun fallbackTimesOutWhenWriterNeverTerminates() {
        val process = ProcessBuilder("sh", "-c", "printf partial; exec sleep 30").start()
        val result = BoundedRootProcess.run(process, 150, 128)
        assertEquals(-1, result.exitCode)
        assertEquals("", result.output)
        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse(process.isAlive)
    }
}
