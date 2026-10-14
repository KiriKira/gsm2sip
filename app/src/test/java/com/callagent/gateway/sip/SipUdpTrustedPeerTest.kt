package com.callagent.gateway.sip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Over plain UDP there is no verified session, so routing metadata is trusted
 * only when the INVITE comes from the registrar's own address and port.
 */
@RunWith(RobolectricTestRunner::class)
class SipUdpTrustedPeerTest {
    @Test
    fun metadataIsTrustedFromTheRegistrarOnly() {
        DatagramSocket(null).use { server ->
            DatagramSocket(null).use { stranger ->
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                stranger.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                val localPort = DatagramSocket(0).use { it.localPort }
                val existingMonitors = Thread.getAllStackTraces().keys.filter { it.name == "SIP-Monitor" }.toSet()
                val client = SipClient(
                    username = "gateway", password = "secret",
                    serverDomain = "127.0.0.1", serverPort = server.localPort,
                    localIp = "127.0.0.1", localPort = localPort, publicIp = "127.0.0.1",
                    useTls = false, allowInsecureSignalling = true
                )
                val incoming = LinkedBlockingQueue<SipCall>()
                client.listener = object : SipClient.Listener {
                    override fun onRegistered() = Unit
                    override fun onRegistrationFailed() = Unit
                    override fun onIncomingCall(call: SipCall) { incoming.add(call) }
                    override fun onCallTerminated(call: SipCall) = Unit
                }
                var monitor: Thread? = null
                try {
                    client.start()
                    monitor = Thread.getAllStackTraces().keys.firstOrNull { it.name == "SIP-Monitor" && it !in existingMonitors }

                    send(server, localPort, invite("from-server", server.localPort))
                    val fromServer = incoming.poll(3, TimeUnit.SECONDS)
                    assertNotNull("the registrar's INVITE is delivered", fromServer)
                    assertNotNull("metadata from the registrar is trusted", fromServer!!.trustedGsmMetadata)

                    send(stranger, localPort, invite("from-stranger", stranger.localPort))
                    val fromStranger = incoming.poll(3, TimeUnit.SECONDS)
                    assertNotNull("another peer's INVITE is still parsed", fromStranger)
                    assertNull("metadata from any other address is ignored", fromStranger!!.trustedGsmMetadata)
                } finally {
                    client.stop()
                    monitor?.interrupt()
                }
            }
        }
    }

    private fun send(socket: DatagramSocket, port: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
    }

    private fun invite(callId: String, fromPort: Int): String {
        val sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n" +
            "m=audio 40002 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000\r\n"
        return "INVITE sip:gateway@127.0.0.1 SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP 127.0.0.1:$fromPort;branch=z9hG4bK$callId\r\n" +
            "From: <sip:asterisk@127.0.0.1>;tag=srv$callId\r\n" +
            "To: <sip:gateway@127.0.0.1>\r\n" +
            "Call-ID: $callId\r\n" +
            "CSeq: 1 INVITE\r\n" +
            "Contact: <sip:asterisk@127.0.0.1:$fromPort>\r\n" +
            "X-GSM-Protocol-Version: 1\r\n" +
            "X-GSM-Call-Id: d7a8d17e-0f0d-4c23-9cc5-6f3897f9b0a1\r\n" +
            "X-GSM-Sim-Id: 13000bda-2989-481f-93db-5b25a6e4bd98\r\n" +
            "X-GSM-Mapping-Revision: 42\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: ${sdp.toByteArray().size}\r\n\r\n" + sdp
    }
}
