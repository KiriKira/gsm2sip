package com.callagent.gateway.sip

import com.callagent.gateway.rtp.SrtpCryptoSuite
import com.callagent.gateway.rtp.SrtpKeys
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipSrtpNegotiationTest {
    @Test
    fun inboundAcceptsOnlySavpWithSupportedSuiteAndExactKeyMaterial() {
        val valid = call()
        assertTrue(valid.absorbRemoteSrtp(sdp("RTP/SAVP", cryptoLine())))
        assertNotNull(valid.remoteSrtpKeys)

        val plainProfile = call()
        assertFalse(plainProfile.absorbRemoteSrtp(sdp("RTP/AVP", cryptoLine())))
        assertNull(plainProfile.remoteSrtpKeys)

        val wrongSuite = call()
        assertFalse(wrongSuite.absorbRemoteSrtp(sdp(
            "RTP/SAVP",
            cryptoLine(suite = SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_32.sdpName)
        )))
        assertNull(wrongSuite.remoteSrtpKeys)

        val unsupportedKeyParams = call()
        assertFalse(unsupportedKeyParams.absorbRemoteSrtp(sdp(
            "RTP/SAVP", cryptoLine() + "|2^20"
        )))
        assertNull(unsupportedKeyParams.remoteSrtpKeys)

        val duplicateTag = call()
        assertFalse(duplicateTag.absorbRemoteSrtp(sdp(
            "RTP/SAVP", cryptoLine() + "\r\n" + cryptoLine()
        )))
        assertNull(duplicateTag.remoteSrtpKeys)

        val malformedExtraLine = call()
        assertFalse(malformedExtraLine.absorbRemoteSrtp(sdp(
            "RTP/SAVP", cryptoLine() + "\r\na=crypto:2 malformed"
        )))
        assertNull(malformedExtraLine.remoteSrtpKeys)
    }

    @Test
    fun outboundAnswerMustMatchOfferedTagAndSuite() {
        val call = call(SipCall.Direction.OUTBOUND).apply {
            localSrtpKeys = SrtpKeys.generate(SrtpCryptoSuite.offered)
        }
        assertFalse(call.absorbRemoteSrtp(
            sdp("RTP/SAVP", cryptoLine(tag = 2)), expectedTag = 1,
            expectedSuite = SrtpCryptoSuite.offered
        ))
        assertNull(call.remoteSrtpKeys)
        assertFalse(call.absorbRemoteSrtp(
            sdp(
                "RTP/SAVP",
                cryptoLine(suite = SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_32.sdpName)
            ), expectedTag = 1, expectedSuite = SrtpCryptoSuite.offered
        ))
        assertNull(call.remoteSrtpKeys)
        assertTrue(call.absorbRemoteSrtp(
            sdp("RTP/SAVP", cryptoLine()), expectedTag = 1,
            expectedSuite = SrtpCryptoSuite.offered
        ))
    }

    private fun call(direction: SipCall.Direction = SipCall.Direction.INBOUND) =
        SipCall("test-call", direction, SipClient("user", "secret", "sip.example.test", useTls = true))

    private fun sdp(profile: String, cryptoLine: String): SipMessage {
        val body = "v=0\r\no=peer 1 1 IN IP4 192.0.2.10\r\ns=call\r\n" +
            "c=IN IP4 192.0.2.10\r\nt=0 0\r\nm=audio 4000 $profile 9\r\n" +
            "a=rtpmap:9 G722/8000\r\n$cryptoLine\r\n"
        return SipMessage.parse(
            "INVITE sip:user@sip.example.test SIP/2.0\r\n" +
                "Via: SIP/2.0/TLS sip.example.test\r\n" +
                "From: <sip:peer@sip.example.test>;tag=peer\r\n" +
                "To: <sip:user@sip.example.test>\r\n" +
                "Call-ID: test-call\r\nCSeq: 1 INVITE\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
        )!!
    }

    private fun cryptoLine(
        tag: Int = 1,
        suite: String = SrtpCryptoSuite.offered.sdpName
    ): String = "a=crypto:$tag $suite inline:$inlineKey"

    private companion object {
        val inlineKey = Base64.getEncoder().encodeToString(ByteArray(30) { it.toByte() })
    }
}
