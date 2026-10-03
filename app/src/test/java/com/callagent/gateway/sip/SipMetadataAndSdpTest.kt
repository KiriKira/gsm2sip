package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMetadataAndSdpTest {
    @Test
    fun acceptsOnlyOneCompleteSupportedV1MetadataSet() {
        val valid = invite(metadataHeaders())
        assertEquals(
            GsmCallMetadata(1, CALL_ID, SIM_ID, 42),
            valid.gsmCallMetadata()
        )
        assertNull(invite(metadataHeaders().dropLast(1)).gsmCallMetadata())
        assertNull(invite(metadataHeaders() + "X-GSM-Sim-Id: $SIM_ID").gsmCallMetadata())
        assertNull(invite(metadataHeaders().mapIndexed { index, line ->
            if (index == 0) "X-GSM-Protocol-Version: 2" else line
        }).gsmCallMetadata())
        assertNull(invite(metadataHeaders().mapIndexed { index, line ->
            if (index == 0) "X-GSM-Protocol-Version: +1" else line
        }).gsmCallMetadata())
        assertNull(invite(metadataHeaders().mapIndexed { index, line ->
            if (index == 3) "X-GSM-Mapping-Revision: -1" else line
        }).gsmCallMetadata())
    }

    @Test
    fun metadataHeadersAreStableAndExtraHeaderInjectionIsRejected() {
        val metadata = GsmCallMetadata(1, CALL_ID, SIM_ID, 42)
        assertEquals(metadata, invite(metadata.sipHeaders()).gsmCallMetadata())

        try {
            SipBuilder.invite(
                "sip:target@server", "gateway", "server", "127.0.0.1", 5060,
                "sip-call", 1, 4000, extraHeaders = listOf("X-GSM-Sim-Id: $SIM_ID\r\nInjected: yes")
            )
            throw AssertionError("CRLF header injection should fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun telephoneEventPayloadIsReadFromRemoteSdpAndEchoedInAnswer() {
        val offerSdp =
            "v=0\r\no=a 1 1 IN IP4 192.0.2.2\r\ns=call\r\nc=IN IP4 192.0.2.2\r\n" +
                "t=0 0\r\nm=audio 4000 RTP/SAVP 9 110\r\n" +
                "a=rtpmap:9 G722/8000\r\na=rtpmap:110 telephone-event/8000\r\n"
        val request = SipMessage.parse(
            "INVITE sip:gateway@server SIP/2.0\r\n" +
                "Via: SIP/2.0/TLS server\r\nFrom: <sip:a@server>;tag=a\r\n" +
                "To: <sip:gateway@server>\r\nCall-ID: c1\r\nCSeq: 1 INVITE\r\n" +
                "Contact: <sip:a@server>\r\nContent-Type: application/sdp\r\n" +
                "Content-Length: ${offerSdp.toByteArray(Charsets.UTF_8).size}\r\n\r\n" + offerSdp
        )!!

        assertEquals(110, request.sdpTelephoneEventPayloadType)
        val answer = SipBuilder.ok200(
            request, "gateway", "127.0.0.1", 5060,
            localRtpPort = 5000,
            srtp = com.callagent.gateway.rtp.SrtpKeys.generate(
                com.callagent.gateway.rtp.SrtpCryptoSuite.offered
            ),
            codecPayloadType = 9,
            telephoneEventPayloadType = request.sdpTelephoneEventPayloadType
        )
        assertTrue(answer.contains("m=audio 5000 RTP/SAVP 9 110\r\n"))
        assertTrue(answer.contains("a=rtpmap:110 telephone-event/8000\r\n"))
        assertFalse(answer.contains("a=rtpmap:101 telephone-event/8000"))
    }

    private fun invite(headers: List<String>) = SipMessage.parse(
        "INVITE sip:gateway@server SIP/2.0\r\n" +
            headers.joinToString("\r\n") + "\r\nContent-Length: 0\r\n\r\n"
    )!!

    private fun metadataHeaders() = listOf(
        "X-GSM-Protocol-Version: 1",
        "X-GSM-Call-Id: $CALL_ID",
        "X-GSM-Sim-Id: $SIM_ID",
        "X-GSM-Mapping-Revision: 42"
    )

    private companion object {
        const val CALL_ID = "d7a8d17e-0f0d-4c23-9cc5-6f3897f9b0a1"
        const val SIM_ID = "13000bda-2989-481f-93db-5b25a6e4bd98"
    }
}
