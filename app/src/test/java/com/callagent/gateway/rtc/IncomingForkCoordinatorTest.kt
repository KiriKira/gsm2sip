package com.callagent.gateway.rtc

import com.callagent.rtcmedia.MediaApiException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class IncomingForkCoordinatorTest {
    @Test fun definiteRejectedNodeDoesNotGetTerminalAndHealthyLaterNodeCanSucceed() {
        val unavailable = MediaApiException(503, "GATEWAY_UNAVAILABLE")
        assertFalse(mayHaveCommittedCreate(unavailable))
        assertTrue(mayHaveCommittedCreate(MediaApiException(503, "NETWORK_UNAVAILABLE")))

        val fork = IncomingForkCoordinator(listOf("unavailable-node", "healthy-node"))
        assertTrue(fork.beginCreate("unavailable-node"))
        assertTrue(
            fork.completeCreate(
                "unavailable-node",
                if (mayHaveCommittedCreate(unavailable)) IncomingForkCoordinator.CreateOutcome.UNKNOWN
                else IncomingForkCoordinator.CreateOutcome.REJECTED
            ).isEmpty()
        )

        assertTrue("a rejected node must not close the fork", fork.beginCreate("healthy-node"))
        assertTrue(fork.completeCreate("healthy-node", IncomingForkCoordinator.CreateOutcome.CREATED).isEmpty())
        assertTrue("the healthy node can win without cleaning the rejected node", fork.winner("healthy-node").isEmpty())
    }

    @Test fun winnerDuringCreateDoesNotQueueEarly404AckOrStartTheNextNode() {
        val fork = IncomingForkCoordinator(listOf("node-a", "node-b", "node-c"))
        assertTrue(fork.beginCreate("node-a"))
        assertTrue(fork.completeCreate("node-a", IncomingForkCoordinator.CreateOutcome.CREATED).isEmpty())
        assertTrue(fork.beginCreate("node-b"))

        val createStarted = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val completed = AtomicReference<List<IncomingForkCoordinator.TerminalDelivery>>()
        val workerFailure = AtomicReference<Throwable>()
        val createWorker = Thread {
            try {
                createStarted.countDown()
                check(releaseCreate.await(3, TimeUnit.SECONDS)) { "test did not release the blocked create" }
                // A transport error is ambiguous: the server may have committed
                // the incoming row before the response was lost.
                completed.set(fork.completeCreate("node-b", IncomingForkCoordinator.CreateOutcome.UNKNOWN))
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        createWorker.start()

        try {
            assertTrue(createStarted.await(2, TimeUnit.SECONDS))
            val outbox = mutableMapOf<String, IncomingForkCoordinator.TerminalDelivery>()
            outbox.putAll(fork.winner("node-a").associateBy { it.nodeGatewayId })

            // A poll receiving 404 cannot ACK node-b's terminal because none
            // was enqueued while its POST was still in flight.
            val early404WasAcknowledged = outbox.remove("node-b") != null
            assertFalse(early404WasAcknowledged)
            assertFalse("a node after the winner must never receive a new create", fork.beginCreate("node-c"))

            releaseCreate.countDown()
            createWorker.join(2_000)
            assertFalse("blocked create worker leaked", createWorker.isAlive)
            workerFailure.get()?.let { throw AssertionError("blocked create worker failed", it) }

            val lateTerminal = completed.get()
            assertEquals(
                listOf(IncomingForkCoordinator.TerminalDelivery("node-b", "answered_elsewhere")),
                lateTerminal
            )
            outbox.putAll(lateTerminal.associateBy { it.nodeGatewayId })
            assertEquals("the terminal remains available after the create returns", "answered_elsewhere", outbox["node-b"]?.kind)
            assertFalse("a terminal GET 404 must not revive a node offer", fork.beginCreate("node-c"))
        } finally {
            releaseCreate.countDown()
            createWorker.join(2_000)
        }
    }

    @Test fun cellularEndSupersedesWinnerForAnInFlightUnknownCreate() {
        val fork = IncomingForkCoordinator(listOf("node-a", "node-b"))
        assertTrue(fork.beginCreate("node-a"))
        fork.completeCreate("node-a", IncomingForkCoordinator.CreateOutcome.CREATED)
        assertTrue(fork.beginCreate("node-b"))

        assertTrue(fork.winner("node-a").isEmpty())
        assertEquals(
            listOf(IncomingForkCoordinator.TerminalDelivery("node-a", "ended")),
            fork.ended()
        )

        assertTrue(
            fork.completeCreate("node-b", IncomingForkCoordinator.CreateOutcome.UNKNOWN)
                .contains(IncomingForkCoordinator.TerminalDelivery("node-b", "ended"))
        )
    }
}
