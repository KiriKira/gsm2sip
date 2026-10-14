package com.callagent.gateway.sip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A challenge answering the INVITE that already carried credentials is a
 * rejection: the call ends at once instead of waiting out the answer timer,
 * while a retransmission of the first challenge is still ignored.
 */
@RunWith(RobolectricTestRunner::class)
class SipInviteAuthRejectedTest {
    @Test
    fun aChallengeToTheAuthenticatedInviteEndsTheCall() {
        DatagramSocket(null).use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            server.soTimeout = 3000
            val localPort = DatagramSocket(0).use { it.localPort }
            val existingMonitors = Thread.getAllStackTraces().keys.filter { it.name == "SIP-Monitor" }.toSet()
            val client = SipClient(
                username = "gateway", password = "secret",
                serverDomain = "127.0.0.1", serverPort = server.localPort,
                localIp = "127.0.0.1", localPort = localPort, publicIp = "127.0.0.1",
                useTls = false, allowInsecureSignalling = true
            )
            val terminated = LinkedBlockingQueue<SipCall>()
            client.listener = object : SipClient.Listener {
                override fun onRegistered() = Unit
                override fun onRegistrationFailed() = Unit
                override fun onIncomingCall(call: SipCall) = Unit
                override fun onCallTerminated(call: SipCall) { terminated.add(call) }
            }
            var monitor: Thread? = null
            try {
                client.start()
                monitor = Thread.getAllStackTraces().keys.firstOrNull { it.name == "SIP-Monitor" && it !in existingMonitors }
                // The caller's number as the From user, as for a real GSM call.
                val call = client.makeCall("s", 40000, callerIdNumber = "13800138000")

                val first = receiveInvite(server)
                assertTrue(first.text.contains("CSeq: 1 INVITE"))
                server.send(challenge(first, 1), first.from)

                val authenticated = receiveInvite(server)
                assertTrue("credentials are sent once", authenticated.text.contains("Authorization: Digest"))
                assertTrue(authenticated.text.contains("CSeq: 2 INVITE"))

                // A retransmitted first challenge changes nothing.
                server.send(challenge(first, 1), first.from)
                assertNull(terminated.poll(500, TimeUnit.MILLISECONDS))
                assertEquals(SipCall.State.TRYING, call.state)

                server.send(challenge(authenticated, 2), authenticated.from)
                assertNotNull("the rejected call ends at once", terminated.poll(3, TimeUnit.SECONDS))
                assertEquals(SipCall.State.TERMINATED, call.state)
            } finally {
                client.stop()
                monitor?.interrupt()
            }
        }
    }

    private class Received(val text: String, val from: InetSocketAddress)

    private fun receiveInvite(socket: DatagramSocket): Received {
        val buffer = ByteArray(65535)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (timeout: SocketTimeoutException) {
                throw AssertionError("no INVITE arrived", timeout)
            }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (text.startsWith("INVITE ")) return Received(text, packet.socketAddress as InetSocketAddress)
        }
    }

    private fun DatagramSocket.send(text: String, to: InetSocketAddress) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        send(DatagramPacket(bytes, bytes.size, to))
    }

    private fun challenge(invite: Received, cseq: Int): String {
        fun header(name: String) = invite.text.lineSequence()
            .first { it.startsWith("$name:", ignoreCase = true) }.trimEnd('\r')
        return "SIP/2.0 401 Unauthorized\r\n" +
            header("Via") + "\r\n" +
            header("From") + "\r\n" +
            header("To") + ";tag=srv\r\n" +
            header("Call-ID") + "\r\n" +
            "CSeq: $cseq INVITE\r\n" +
            "WWW-Authenticate: Digest realm=\"gsm2sip\", nonce=\"n$cseq\", algorithm=MD5, qop=\"auth\"\r\n" +
            "Content-Length: 0\r\n\r\n"
    }
}
