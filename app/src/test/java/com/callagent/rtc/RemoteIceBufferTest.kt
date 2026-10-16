package com.callagent.rtc

import org.junit.Assert.*
import org.junit.Test

class RemoteIceBufferTest {
    private val scope = RtcScope("node", "call", "leg", "attempt")
    private val key = RtcGeneration(scope, 1)
    private var now = 1_000L
    private fun buffer() = RemoteIceBuffer(key, "gateway") { now }
    private fun frame(sequence: Long, generation: RtcGeneration = key, ice: RtcIce? = RtcIce("candidate:1", "0", 0)) =
        RemoteIceFrame(generation, sequence, "gateway", 10_000L, ice)

    @Test fun earlyIceWaitsForSuccessfulMatchingRemoteDescription() {
        val buffer = buffer()
        assertTrue(buffer.accept(frame(3)).isEmpty())
        assertTrue(buffer.accept(frame(2)).isEmpty())
        assertTrue(buffer.accept(frame(4, ice = null)).isEmpty())
        val token = buffer.beginDescription(key, 5, 10_000L)!!
        assertEquals(listOf(2L, 3L, 4L), buffer.descriptionApplied(token).map { it.sequence })
        assertTrue(buffer.descriptionApplied(token).isEmpty())
        assertTrue(buffer.accept(frame(2)).isEmpty())
    }

    @Test fun generationChangeFencesQueuedIceAndLateNativeCompletion() {
        val buffer = buffer()
        val oldToken = buffer.beginDescription(key, 1, 10_000L)!!
        buffer.accept(frame(2))
        val next = key.copy(generation = 2)
        buffer.advance(next)
        assertTrue(buffer.descriptionApplied(oldToken).isEmpty())
        assertTrue(buffer.accept(frame(3)).isEmpty())
        buffer.accept(frame(4, next))
        val token = buffer.beginDescription(next, 3, 10_000L)!!
        assertEquals(listOf(4L), buffer.descriptionApplied(token).map { it.sequence })
    }

    @Test fun candidateCannotCrossCallNodeLegAttemptOrSender() {
        val buffer = buffer()
        for (other in listOf(scope.copy(nodeId = "other"), scope.copy(callId = "other"), scope.copy(legId = "other"), scope.copy(attemptId = "other"))) {
            assertTrue(buffer.accept(frame(1, RtcGeneration(other, 1))).isEmpty())
        }
        expectClosed(buffer) { buffer.accept(frame(1).copy(senderId = "another-device")) }
    }

    @Test fun descriptionTokenCannotBeUsedOnAnotherPeerConnection() {
        val a = buffer(); val b = buffer()
        val token = a.beginDescription(key, 1, 10_000L)!!
        b.accept(frame(2))
        assertTrue(b.descriptionApplied(token).isEmpty())
        val own = b.beginDescription(key, 1, 10_000L)!!
        assertEquals(1, b.descriptionApplied(own).size)
    }

    @Test fun duplicateDoesNotRepeatNativeWorkButChangedPayloadFailsClosed() {
        val buffer = buffer()
        val token = buffer.beginDescription(key, 1, 10_000L)!!
        buffer.descriptionApplied(token)
        assertEquals(listOf(frame(2)), buffer.accept(frame(2)))
        assertTrue(buffer.accept(frame(2)).isEmpty())
        expectClosed(buffer) { buffer.accept(frame(2).copy(ice = RtcIce("changed", "0", 0))) }
    }

    @Test fun expiredBufferedIceCannotProduceFalseEndOfCandidates() {
        val buffer = buffer()
        buffer.accept(frame(2).copy(expiresAtMillis = 2_000L))
        buffer.accept(frame(3, ice = null))
        val token = buffer.beginDescription(key, 1, 10_000L)!!
        now = 2_000L
        expectClosed(buffer) { buffer.descriptionApplied(token) }
    }

    @Test fun boundedQueueDoesNotEvictAndReapplyOldCandidates() {
        val buffer = buffer()
        repeat(RemoteIceBuffer.MAX_CANDIDATES) { buffer.accept(frame(it + 1L)) }
        assertTrue(buffer.accept(frame(1)).isEmpty())
        expectClosed(buffer) { buffer.accept(frame(200)) }
    }

    @Test fun terminalBufferCannotBeReopenedByRestartOrLateCallbacks() {
        val buffer = buffer()
        val token = buffer.beginDescription(key, 1, 10_000L)!!
        buffer.close(); buffer.close()
        assertTrue(buffer.descriptionApplied(token).isEmpty())
        expectClosed(buffer) { buffer.advance(key.copy(generation = 2)) }
    }

    @Test fun invalidCandidateAndClockRollbackFailClosed() {
        for (ice in listOf(RtcIce("x\n", "0", 0), RtcIce("x".repeat(2049), "0", 0), RtcIce("x", "0", -1))) {
            val buffer = buffer()
            expectClosed(buffer) { buffer.accept(frame(1, ice = ice)) }
        }
        val buffer = buffer(); buffer.accept(frame(1))
        now--
        expectClosed(buffer) { buffer.accept(frame(2)) }
    }

    @Test fun endOfCandidatesCannotBeFollowedByAnotherNativeCandidate() {
        val buffer = buffer()
        buffer.descriptionApplied(buffer.beginDescription(key, 1, 10_000L)!!)
        assertEquals(1, buffer.accept(frame(4, ice = null)).size)
        expectClosed(buffer) { buffer.accept(frame(3)) }
    }

    private fun expectClosed(buffer: RemoteIceBuffer, operation: () -> Unit) {
        try { operation(); fail("Expected protocol failure") } catch (_: RtcProtocolException) { }
        assertNull(buffer.currentKey())
    }
}
