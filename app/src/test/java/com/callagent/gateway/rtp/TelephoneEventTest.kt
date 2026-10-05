package com.callagent.gateway.rtp

import org.junit.Assert.assertEquals
import org.junit.Test

class TelephoneEventTest {
    @Test
    fun progressPacketsProduceOneStartAndDuplicateEndPacketsProduceOneStop() {
        val edges = mutableListOf<TelephoneToneEdge>()
        val receiver = TelephoneEventReceiver(110, edges::add)

        receiver.accept(event(110, 5, 0x04, 160), nowMs = 0) // digit 4
        receiver.accept(event(110, 6, 0x04, 320), nowMs = 20)
        receiver.accept(event(110, 7, 0x84, 480), nowMs = 40) // E bit set
        receiver.accept(event(110, 8, 0x84, 480), nowMs = 60)
        receiver.accept(event(110, 9, 0x84, 480), nowMs = 80)

        assertEquals(
            listOf(TelephoneToneEdge('4', true), TelephoneToneEdge('4', false)),
            edges
        )
    }

    @Test
    fun timeoutAndEventReplacementAlwaysStopThePreviousDigit() {
        val edges = mutableListOf<TelephoneToneEdge>()
        val receiver = TelephoneEventReceiver(101, edges::add, idleEndTimeoutMs = 100)

        receiver.accept(event(101, 1, 0x01, 80, timestamp = 1000), nowMs = 0)
        receiver.expire(nowMs = 99)
        receiver.expire(nowMs = 100)
        receiver.accept(event(101, 2, 0x0B, 80, timestamp = 2000), nowMs = 200) // #
        receiver.accept(event(101, 3, 0x8B, 80, timestamp = 2000), nowMs = 220)

        assertEquals(
            listOf(
                TelephoneToneEdge('1', true), TelephoneToneEdge('1', false),
                TelephoneToneEdge('#', true), TelephoneToneEdge('#', false)
            ),
            edges
        )
    }

    @Test
    fun malformedAndUnnegotiatedEventsAreIgnored() {
        val edges = mutableListOf<TelephoneToneEdge>()
        val receiver = TelephoneEventReceiver(101, edges::add)
        receiver.accept(event(101, 1, 0x40, 80)) // reserved bit
        receiver.accept(event(100, 2, 0x01, 80)) // different payload type
        receiver.accept(RtpPacket(101, 3, 1, 1, byteArrayOf(1, 2, 3)))
        receiver.accept(event(101, 4, 0x90, 80)) // unsupported event 16

        assertEquals(emptyList<TelephoneToneEdge>(), edges)
    }

    private fun event(
        payloadType: Int,
        sequence: Int,
        eventByte: Int,
        duration: Int,
        timestamp: Long = 1000
    ) = RtpPacket(
        payloadType,
        sequence,
        timestamp,
        42,
        byteArrayOf(
            eventByte.toByte(),
            10,
            (duration ushr 8).toByte(),
            duration.toByte()
        )
    )
}
