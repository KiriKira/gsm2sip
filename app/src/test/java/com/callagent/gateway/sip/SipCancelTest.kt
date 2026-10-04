package com.callagent.gateway.sip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SipCancelTest {
    @Test
    fun cancelMustMatchInviteTransactionAndCannotTearDownAnsweredCall() {
        DatagramSocket(null).use { peer ->
            peer.reuseAddress = true
            peer.bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            peer.soTimeout = 2500
            val localPort = DatagramSocket(0).use { it.localPort }
            val existingMonitorThreads = Thread.getAllStackTraces().keys
                .filter { it.name == "SIP-Monitor" }
                .toSet()
            val client = SipClient(
                username = "gateway",
                password = "secret",
                serverDomain = "127.0.0.1",
                serverPort = peer.localPort,
                localIp = "127.0.0.1",
                localPort = localPort,
                publicIp = "127.0.0.1",
                useTls = false,
                allowInsecureSignalling = true
            )
            var monitorThread: Thread? = null
            val receivedCall = AtomicReference<SipCall?>()
            val callReady = CountDownLatch(1)
            client.listener = object : SipClient.Listener {
                override fun onRegistered() = Unit
                override fun onRegistrationFailed() = Unit
                override fun onIncomingCall(call: SipCall) {
                    receivedCall.set(call)
                    callReady.countDown()
                }
                override fun onCallTerminated(call: SipCall) = Unit
            }

            try {
                client.start()
                monitorThread = Thread.getAllStackTraces().keys
                    .firstOrNull { it.name == "SIP-Monitor" && it !in existingMonitorThreads }

                send(peer, localPort, invite("pending-call", 41, "z9hG4bK-pending", peer.localPort))
                assertNotNull("INVITE should produce 100 Trying", awaitResponse(peer, "pending-call", "41 INVITE", 100))
                assertTrue("INVITE should reach the call listener", callReady.await(2, TimeUnit.SECONDS))
                assertNotNull(receivedCall.get())
                val pending = receivedCall.get()!!
                val terminations = AtomicInteger()
                val terminationReady = CountDownLatch(1)
                pending.listener = testListener(onTerminated = {
                    terminations.incrementAndGet()
                    terminationReady.countDown()
                })

                send(peer, localPort, cancel("unknown-call", 41, "z9hG4bK-pending"))
                assertNotNull(awaitResponse(peer, "unknown-call", "41 CANCEL", 481))
                assertEquals(SipCall.State.TRYING, pending.state)

                // Both required transaction fields are checked independently.
                send(peer, localPort, cancel("pending-call", 42, "z9hG4bK-pending"))
                assertNotNull(awaitResponse(peer, "pending-call", "42 CANCEL", 481))
                assertEquals(SipCall.State.TRYING, pending.state)

                send(peer, localPort, cancel("pending-call", 41, "z9hG4bK-wrong"))
                assertNotNull(awaitResponse(peer, "pending-call", "41 CANCEL", 481))
                assertEquals(SipCall.State.TRYING, pending.state)

                send(peer, localPort, cancel("pending-call", 41, "z9hG4bK-pending", viaHost = "192.0.2.3"))
                assertNotNull(awaitResponse(peer, "pending-call", "41 CANCEL", 481))
                assertEquals(SipCall.State.TRYING, pending.state)

                send(peer, localPort, cancel("pending-call", 41, "z9hG4bK-pending"))
                assertNotNull(awaitResponse(peer, "pending-call", "41 CANCEL", 200))
                val inviteTerminated = awaitResponse(peer, "pending-call", "41 INVITE", 487)
                assertNotNull("matching CANCEL must terminate the INVITE with 487", inviteTerminated)
                assertEquals(SipCall.State.TERMINATED, pending.state)
                // Receiving the UDP response does not synchronize with the
                // listener callback that follows it on the receiver thread.
                assertTrue("termination listener should complete", terminationReady.await(2, TimeUnit.SECONDS))
                assertEquals("termination listener fires once", 1, terminations.get())
                val lateDispatches = AtomicInteger()
                assertFalse(pending.withPendingInvite { lateDispatches.incrementAndGet(); true })
                assertEquals("a delayed worker must not dispatch after CANCEL", 0, lateDispatches.get())

                // A matching CANCEL after the INVITE is answered is acknowledged,
                // but it cannot terminate the established dialog.
                val secondCallReady = CountDownLatch(1)
                val secondCallRef = AtomicReference<SipCall?>()
                client.listener = object : SipClient.Listener {
                    override fun onRegistered() = Unit
                    override fun onRegistrationFailed() = Unit
                    override fun onIncomingCall(call: SipCall) {
                        secondCallRef.set(call)
                        secondCallReady.countDown()
                    }
                    override fun onCallTerminated(call: SipCall) = Unit
                }
                send(peer, localPort, invite("answered-call", 0, "z9hG4bK-answered", peer.localPort))
                assertNotNull(awaitResponse(peer, "answered-call", "0 INVITE", 100))
                assertTrue(secondCallReady.await(2, TimeUnit.SECONDS))
                assertNotNull(secondCallRef.get())
                val answered = secondCallRef.get()!!
                val answeredTerminations = AtomicInteger()
                answered.listener = testListener(onTerminated = { answeredTerminations.incrementAndGet() })
                answered.accept(40000)
                assertNotNull(awaitResponse(peer, "answered-call", "0 INVITE", 200))
                send(peer, localPort, cancel("answered-call", 0, "z9hG4bK-answered"))
                assertNotNull(awaitResponse(peer, "answered-call", "0 CANCEL", 200))
                assertEquals(SipCall.State.ANSWERED, answered.state)
                assertEquals(0, answeredTerminations.get())
                assertNull(awaitResponse(peer, "answered-call", "0 INVITE", 487, timeoutMs = 200))
            } finally {
                client.stop()
                // SipClient's monitor waits 15 seconds before its first pass.
                // Interrupt only the monitor created by this test, if any.
                monitorThread?.interrupt()
            }
        }
    }

    private fun send(socket: DatagramSocket, destinationPort: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), destinationPort))
    }

    private fun awaitResponse(
        socket: DatagramSocket,
        callId: String,
        cseq: String,
        status: Int,
        timeoutMs: Int = 2500
    ): SipMessage? {
        val endAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val buffer = ByteArray(8192)
        while (System.nanoTime() < endAt) {
            socket.soTimeout = maxOf(1, TimeUnit.NANOSECONDS.toMillis(endAt - System.nanoTime()).toInt())
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                return null
            }
            val message = SipMessage.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            if (message.isResponse && message.statusCode == status &&
                message.callId == callId && message.cseq == cseq
            ) return message
        }
        return null
    }

    private fun invite(callId: String, cseq: Int, branch: String, contactPort: Int): String =
        "INVITE sip:gateway@127.0.0.1 SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP 127.0.0.1;branch=$branch;rport\r\n" +
            "From: <sip:caller@127.0.0.1>;tag=remote-tag\r\n" +
            "To: <sip:gateway@127.0.0.1>\r\n" +
            "Call-ID: $callId\r\n" +
            "Contact: <sip:caller@127.0.0.1:$contactPort>\r\n" +
            "CSeq: $cseq INVITE\r\n" +
            "Content-Length: 0\r\n\r\n"

    private fun cancel(callId: String, cseq: Int, branch: String, viaHost: String = "127.0.0.1"): String =
        "CANCEL sip:gateway@127.0.0.1 SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP $viaHost;branch=$branch;rport\r\n" +
            "From: <sip:caller@127.0.0.1>;tag=remote-tag\r\n" +
            "To: <sip:gateway@127.0.0.1>\r\n" +
            "Call-ID: $callId\r\n" +
            "CSeq: $cseq CANCEL\r\n" +
            "Content-Length: 0\r\n\r\n"

    private fun testListener(onTerminated: () -> Unit) = object : SipCall.Listener {
        override fun onCallAnswered(call: SipCall) = Unit
        override fun onCallTerminated(call: SipCall) = onTerminated()
        override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) = Unit
    }
}
