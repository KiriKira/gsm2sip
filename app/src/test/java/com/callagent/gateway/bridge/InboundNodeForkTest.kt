package com.callagent.gateway.bridge

import android.Manifest
import android.telecom.Call
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.sip.GsmCallMetadata
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import com.callagent.gateway.sip.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * An inbound GSM call is offered through the primary server and every mesh
 * node that is registered and has the SIM bound; the first leg to answer wins
 * and the others are cancelled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InboundNodeForkTest {
    private val simId = "00000000-0000-0000-0000-000000000001"
    private val callId = "11111111-2222-3333-4444-555555555555"

    @Test
    fun nodeLegAnswersFirstAndThePrimaryLegIsCancelled() = withServers { orchestrator, primaryPeer, nodePeer, primaryPort, nodePort ->
        dispatch(orchestrator)
        val primaryInvite = await(primaryPeer, "INVITE")
        val nodeInvite = await(nodePeer, "INVITE")
        assertNotNull("the primary server is offered the call", primaryInvite)
        assertNotNull("the node is offered the call", nodeInvite)
        assertEquals("primary leg carries the primary's revision", "3", primaryInvite!!.header("X-GSM-Mapping-Revision"))
        assertEquals("node leg carries the node's revision", "7", nodeInvite!!.header("X-GSM-Mapping-Revision"))
        assertEquals("both legs are the same business call", callId, nodeInvite.header("X-GSM-Call-Id"))

        send(nodePeer, nodePort, response(nodeInvite, "200 OK", nodePeer.localPort, sdp = true))
        val cancel = await(primaryPeer, "CANCEL")
        assertNotNull("the losing primary leg is cancelled", cancel)
        assertEquals(primaryInvite.callId, cancel!!.callId)
        val active = ReflectionHelpers.getField<SipCall?>(orchestrator, "activeSipCall")
        assertEquals("the node leg is the call now", "node-1", active?.client?.nodeGatewayId)
    }

    @Test
    fun aFailedLegDoesNotEndTheCallWhileAnotherNodeIsRinging() = withServers { orchestrator, primaryPeer, nodePeer, primaryPort, _ ->
        dispatch(orchestrator)
        val primaryInvite = await(primaryPeer, "INVITE")!!
        assertNotNull(await(nodePeer, "INVITE"))
        // The primary server is unreachable for calls; the node keeps ringing.
        send(primaryPeer, primaryPort, response(primaryInvite, "480 Temporarily Unavailable", primaryPeer.localPort, sdp = false))
        awaitCondition { ReflectionHelpers.getField<SipCall?>(orchestrator, "activeSipCall")?.client?.nodeGatewayId == "node-1" }
        assertEquals(CallOrchestrator.BridgeState.SIP_CALLING, orchestrator.bridgeState)
    }

    @Test
    fun aNodeDeclineKeepsThePrimaryLegRinging() = withServers { orchestrator, primaryPeer, nodePeer, _, nodePort ->
        dispatch(orchestrator)
        assertNotNull(await(primaryPeer, "INVITE"))
        val nodeInvite = await(nodePeer, "INVITE")!!
        // A node where the host is not reachable turns the call down at once.
        send(nodePeer, nodePort, response(nodeInvite, "603 Decline", nodePeer.localPort, sdp = false))
        awaitCondition { ReflectionHelpers.getField<List<SipCall>>(orchestrator, "inboundForkLegs").size == 1 }
        assertNull("the primary leg is still the call", ReflectionHelpers.getField<SipCall?>(orchestrator, "activeSipCall")?.client?.nodeGatewayId)
        assertEquals(CallOrchestrator.BridgeState.SIP_CALLING, orchestrator.bridgeState)
        assertNull("the primary leg is not cancelled", await(primaryPeer, "CANCEL", timeoutMs = 500))
    }

    @Test
    fun whenTheGsmCallerHangsUpEveryRingingLegIsCancelled() = withServers { orchestrator, primaryPeer, nodePeer, primaryPort, _ ->
        dispatch(orchestrator)
        val primaryInvite = await(primaryPeer, "INVITE")!!
        assertNotNull(await(nodePeer, "INVITE"))
        send(primaryPeer, primaryPort, response(primaryInvite, "183 Session Progress", primaryPeer.localPort, sdp = false))
        val gsmCall = ReflectionHelpers.getField<Call>(orchestrator, "activeGsmCall")
        orchestrator.onGsmCallEnded(gsmCall)
        // Neither INVITE was answered, so only CANCEL withdraws it; a BYE
        // outside a dialog is ignored and the host would keep ringing.
        val primaryCancel = await(primaryPeer, "CANCEL")
        assertNotNull("the ringing primary leg is cancelled", primaryCancel)
        assertEquals(primaryInvite.callId, primaryCancel!!.callId)
        assertNotNull("the ringing node leg is cancelled", await(nodePeer, "CANCEL"))
        assertEquals(CallOrchestrator.BridgeState.IDLE, orchestrator.bridgeState)
    }

    private fun dispatch(orchestrator: CallOrchestrator) {
        val gsmCall = ReflectionHelpers.newInstance(Call::class.java).also {
            ReflectionHelpers.setField(it, "mState", Call.STATE_RINGING)
        }
        val mapping = SimRegistry.SimMapping(simId, 14, 0, 3, 0, SimRegistry.IdentityState.LOCAL_CONFIRMED, null, true, true)
        ReflectionHelpers.setField(orchestrator, "activeGsmCall", gsmCall)
        ReflectionHelpers.setField(orchestrator, "activeGsmMapping", mapping)
        ReflectionHelpers.setField(orchestrator, "activeBusinessCallId", callId)
        ReflectionHelpers.setField(orchestrator, "bridgeState", CallOrchestrator.BridgeState.GSM_RINGING)
        val generation = ReflectionHelpers.getField<Long>(orchestrator, "generation")
        val method = CallOrchestrator::class.java.getDeclaredMethod("dispatchInboundFlow",
            Call::class.java, GsmCallMetadata::class.java, String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(orchestrator, gsmCall, GsmCallMetadata(GsmCallMetadata.SUPPORTED_PROTOCOL_VERSION, callId, simId, 3),
            "+15550002222", "+15550003333", 40000, generation)
    }

    private fun withServers(test: (CallOrchestrator, DatagramSocket, DatagramSocket, Int, Int) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val primaryPeer = peer()
        val nodePeer = peer()
        val primaryPort = DatagramSocket(0).use { it.localPort }
        val nodePort = DatagramSocket(0).use { it.localPort }
        val monitors = Thread.getAllStackTraces().keys.filter { it.name == "SIP-Monitor" }.toSet()
        val primary = client(primaryPeer, primaryPort, null)
        val node = client(nodePeer, nodePort, "node-1")
        val orchestrator = CallOrchestrator(
            context, primary,
            nodeSimRevision = { _, nodeId, sim -> if (nodeId == "node-1" && sim == simId) 7L else null }
        )
        try {
            primary.start()
            node.start()
            orchestrator.start()
            orchestrator.attachNodeClient(node)
            ReflectionHelpers.setField(node, "registered", true)
            test(orchestrator, primaryPeer, nodePeer, primaryPort, nodePort)
        } finally {
            orchestrator.stop()
            primary.stop()
            node.stop()
            primaryPeer.close()
            nodePeer.close()
            Thread.getAllStackTraces().keys.filter { it.name == "SIP-Monitor" && it !in monitors }.forEach { it.interrupt() }
        }
    }

    private fun peer() = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }

    private fun client(peer: DatagramSocket, localPort: Int, nodeId: String?) = SipClient(
        username = "gateway", password = "secret", serverDomain = "127.0.0.1", serverPort = peer.localPort,
        localIp = "127.0.0.1", localPort = localPort, publicIp = "127.0.0.1",
        useTls = false, allowInsecureSignalling = true, nodeGatewayId = nodeId
    )

    private fun send(socket: DatagramSocket, port: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
    }

    private fun await(socket: DatagramSocket, method: String, timeoutMs: Long = 4000): SipMessage? {
        val endAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val buffer = ByteArray(16384)
        while (System.nanoTime() < endAt) {
            socket.soTimeout = maxOf(1, TimeUnit.NANOSECONDS.toMillis(endAt - System.nanoTime()).toInt())
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { return null }
            val message = SipMessage.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            if (message.isRequest && message.method == method) return message
        }
        return null
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) {
            assertTrue("condition not reached in time", System.nanoTime() < endAt)
            Thread.sleep(20)
        }
    }

    private fun response(invite: SipMessage, status: String, port: Int, sdp: Boolean): String {
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
