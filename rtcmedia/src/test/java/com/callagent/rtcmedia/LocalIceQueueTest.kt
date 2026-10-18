package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class LocalIceQueueTest {
    @Test fun completionDuringSerializationCannotOvertakeFinalCandidate() {
        val queue = LocalIceQueue<String>()
        queue.add("first")
        val firstBatch = queue.drain()
        // Native callbacks run while the signal worker serializes this batch.
        queue.add("last")
        queue.seal()
        assertFalse(firstBatch.complete)
        val finalBatch = queue.drain()
        assertEquals(listOf("first", "last"), firstBatch.candidates + finalBatch.candidates)
        assertTrue(finalBatch.complete)
        assertFalse(queue.hasCandidates())
    }

    @Test fun concurrentDrainObservesEveryCandidateBeforeCompletion() {
        repeat(40) {
            val queue = LocalIceQueue<Int>()
            val start = CountDownLatch(1)
            val producer = thread {
                start.await()
                repeat(512) { queue.add(it); if (it % 7 == 0) Thread.yield() }
                queue.seal()
            }
            start.countDown()
            val received = mutableListOf<Int>()
            while (true) {
                val batch = queue.drain()
                received.addAll(batch.candidates)
                if (batch.complete) break
                Thread.yield()
            }
            producer.join()
            assertEquals((0 until 512).toList(), received)
            assertFalse(queue.hasCandidates())
        }
    }

    @Test fun emptyGatheringCanBeFollowedByCandidatesUntilConnected() {
        val queue = LocalIceQueue<String>()
        // An empty COMPLETE from the platform is not ICE nomination.
        assertFalse(queue.drain().complete)
        assertTrue(queue.add("late-interface-host"))
        assertFalse(queue.drain().complete)
        assertTrue(queue.add("turn-relay"))
        queue.seal()
        val finalBatch = queue.drain()
        assertEquals(listOf("turn-relay"), finalBatch.candidates)
        assertTrue(finalBatch.complete)
    }

    @Test fun callbacksAfterNominationCannotTricklePastEnd() {
        val queue = LocalIceQueue<String>()
        queue.add("selected-relay")
        queue.seal()
        assertFalse(queue.add("later-network"))
        val finalBatch = queue.drain()
        assertEquals(listOf("selected-relay"), finalBatch.candidates)
        assertTrue(finalBatch.complete)
        assertFalse(queue.hasCandidates())
        assertTrue(queue.drain().complete)
    }
}
