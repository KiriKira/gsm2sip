package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SipStreamFramerTest {
    @Test
    fun utf8BodyMayBeSplitAcrossAnyReadBoundary() {
        val body = "status 🧪 中文 😀"
        val wire = message(body)
        val bytes = wire.toByteArray(Charsets.UTF_8)
        val framer = SipStreamFramer()
        val frames = mutableListOf<ByteArray>()

        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(3, bytes.size - offset)
            frames += framer.append(bytes, offset, count)
            offset += count
        }

        assertEquals(1, frames.size)
        assertEquals(wire, String(frames.single(), Charsets.UTF_8))
        assertEquals(body, SipMessage.parse(String(frames.single(), Charsets.UTF_8))?.body)
    }

    @Test
    fun pipelinesMessagesAndSkipsOnlyCrlfKeepalives() {
        val first = message("one\r\n\r\ninside")
        val second = message("二")
        val framer = SipStreamFramer()

        val frames = framer.append(("\r\n\r\n" + first + second).toByteArray(Charsets.UTF_8))

        assertEquals(listOf(first, second), frames.map { String(it, Charsets.UTF_8) })
    }

    @Test
    fun compactContentLengthFramesExactBodyBytes() {
        val body = "🛰"
        val wire = message(body, "l")
        val frames = SipStreamFramer().append(wire.toByteArray(Charsets.UTF_8))
        assertEquals(body, SipMessage.parse(String(frames.single(), Charsets.UTF_8))?.body)
    }

    @Test
    fun rejectsMalformedOrUnboundedLengthsAndHeaders() {
        fun frame(header: String, maxHeader: Int = 256, maxBody: Int = 8) =
            SipStreamFramer(maxHeader, maxBody).append(header.toByteArray(Charsets.US_ASCII))

        assertThrows(SipFrameException::class.java) {
            frame("MESSAGE sip:x SIP/2.0\r\nContent-Length: -1\r\n\r\n")
        }
        assertThrows(SipFrameException::class.java) {
            frame("MESSAGE sip:x SIP/2.0\r\nContent-Length: 9\r\n\r\n")
        }
        assertThrows(SipFrameException::class.java) {
            frame("MESSAGE sip:x SIP/2.0\r\nContent-Length: 1\r\nl: 2\r\n\r\na")
        }
        assertThrows(SipFrameException::class.java) {
            frame("MESSAGE sip:x SIP/2.0\r\nContent-Length: 1x\r\n\r\na")
        }
        assertThrows(SipFrameException::class.java) {
            frame("MESSAGE sip:x SIP/2.0\r\nX-Long: " + "x".repeat(40), maxHeader = 32)
        }
        assertThrows(SipFrameException::class.java) {
            frame("MESSAGE sip:x SIP/2.0\r\n\r\na")
        }
    }

    private fun message(body: String, lengthHeader: String = "Content-Length"): String {
        val length = body.toByteArray(Charsets.UTF_8).size
        return "MESSAGE sip:server SIP/2.0\r\n$lengthHeader: $length\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body"
    }
}
