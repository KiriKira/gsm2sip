package com.callagent.gateway.sip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A gateway→server INVITE that lost an inbound race is CANCELled, not BYEd. */
@RunWith(RobolectricTestRunner::class)
class SipOutgoingCancelTest {
    @Test
    fun pendingInviteIsCancelledInItsOwnTransactionAndACrossing200IsClosed() {
        DatagramSocket(null).use { peer ->
            peer.reuseAddress = true
            peer.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val localPort = DatagramSocket(0).use { it.localPort }
            val existingMonitors = Thread.getAllStackTraces().keys.filter { it.name == "SIP-Monitor" }.toSet()
            val client = SipClient(
                username = "gateway", password = "secret",
                serverDomain = "127.0.0.1", serverPort = peer.localPort,
                localIp = "127.0.0.1", localPort = localPort, publicIp = "127.0.0.1",
                useTls = false, allowInsecureSignalling = true, nodeGatewayId = "node-1"
            )
            client.listener = object : SipClient.Listener {
                override fun onRegistered() = Unit
                override fun onRegistrationFailed() = Unit
                override fun onIncomingCall(call: SipCall) = Unit
                override fun onCallTerminated(call: SipCall) = Unit
            }
            var monitor: Thread? = null
            try {
                client.start()
                monitor = Thread.getAllStackTraces().keys.firstOrNull { it.name == "SIP-Monitor" && it !in existingMonitors }
                val call = client.makeCall(targetExtension = "+15550001111", localRtpPort = 40000)
                assertEquals("node-1", call.client.nodeGatewayId)
                val terminated = CountDownLatch(1)
                call.listener = object : SipCall.Listener {
                    override fun onCallAnswered(call: SipCall) = Unit
                    override fun onCallTerminated(call: SipCall) = terminated.countDown()
                    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) = Unit
                }

                val invite = awaitRequest(peer, "INVITE", call.callId)
                assertNotNull("the INVITE reaches the server", invite)
                val (inviteCseq, branch) = invite!!.cseq!!.substringBefore(" ") to branchOf(invite.via!!)
                send(peer, localPort, ringing(invite, peer.localPort))

                assertTrue(call.cancelPendingInvite())
                assertTrue(terminated.await(2, TimeUnit.SECONDS))
                assertEquals(SipCall.State.TERMINATED, call.state)
                val cancel = awaitRequest(peer, "CANCEL", call.callId)
                assertNotNull("a CANCEL is sent for the pending INVITE", cancel)
                assertEquals("CANCEL uses the INVITE's CSeq number", "$inviteCseq CANCEL", cancel!!.cseq)
                assertEquals("CANCEL belongs to the INVITE transaction", branch, branchOf(cancel.via!!))
                assertFalse("nothing is left to cancel", call.cancelPendingInvite())

                // The server answered before it saw the CANCEL: ACK, then BYE.
                send(peer, localPort, ok200(invite, peer.localPort))
                assertNotNull("the crossing 200 is acknowledged", awaitRequest(peer, "ACK", call.callId))
                assertNotNull("the dialog it created is closed", awaitRequest(peer, "BYE", call.callId))
            } finally {
                client.stop()
                monitor?.interrupt()
            }
        }
    }

    private fun branchOf(via: String): String = via.substringAfter("branch=").substringBefore(";").trim()

    private fun send(socket: DatagramSocket, port: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
    }

    private fun awaitRequest(socket: DatagramSocket, method: String, callId: String, timeoutMs: Long = 3000): SipMessage? {
        val endAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val buffer = ByteArray(16384)
        while (System.nanoTime() < endAt) {
            socket.soTimeout = maxOf(1, TimeUnit.NANOSECONDS.toMillis(endAt - System.nanoTime()).toInt())
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { return null }
            val message = SipMessage.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            if (message.isRequest && message.method == method && message.callId == callId) return message
        }
        return null
    }

    private fun ringing(invite: SipMessage, port: Int): String = response(invite, "180 Ringing", sdp = false, port = port)

    private fun ok200(invite: SipMessage, port: Int): String = response(invite, "200 OK", sdp = true, port = port)

    private fun response(invite: SipMessage, status: String, sdp: Boolean, port: Int): String {
        val body = if (!sdp) "" else "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n" +
            "m=audio 41000 RTP/AVP 9\r\na=rtpmap:9 G722/8000\r\n"
        return "SIP/2.0 $status\r\n" +
            "Via: ${invite.via}\r\n" +
            "From: ${invite.from}\r\n" +
            "To: ${invite.to};tag=server-tag\r\n" +
            "Call-ID: ${invite.callId}\r\n" +
            "CSeq: ${invite.cseq}\r\n" +
            "Contact: <sip:asterisk@127.0.0.1:$port>\r\n" +
            (if (sdp) "Content-Type: application/sdp\r\n" else "") +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n" + body
    }
}
