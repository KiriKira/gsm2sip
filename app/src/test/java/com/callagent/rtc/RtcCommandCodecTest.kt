package com.callagent.rtc

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RtcCommandCodecTest {
    private val kinds = listOf("offer", "answer", "candidate", "end_of_candidates", "restart", "close")
    private fun fixture(kind: String) = javaClass.getResourceAsStream("/rtc-v1/$kind.json")!!.use { it.readBytes() }

    @Test fun canonicalServerFixturesDecodeAndRoundTripWithoutIdentityOverride() {
        for (kind in kinds) {
            val decoded = RtcCommandCodec.decode(fixture(kind))
            assertEquals(kind, decoded.kind.wire)
            assertEquals("call-test", decoded.callId)
            assertEquals(decoded, RtcCommandCodec.decode(RtcCommandCodec.encode(decoded)))
        }
    }

    @Test fun duplicateUnknownNullAndWrongNumericTypesAreRejected() {
        val base = fixture("candidate").toString(Charsets.UTF_8)
        val invalid = listOf(
            base.replace("\"version\": 1", "\"version\": 1, \"version\": 2"),
            base.replace("\"version\": 1", "\"version\": 1, \"VERSION\": 1"),
            base.replace("\"version\": 1", "\"version\": 1, \"sender_id\": \"other\""),
            base.replace("\"revision\": 0,", ""),
            base.replace("\"sdp_mline_index\": 0", "\"sdp_mline_index\": 0, \"sdp_mline_index\": 1"),
            base.replace("\"sdp_mline_index\": 0", "\"sdp_mline_index\": null"),
            base.replace("\"sdp_mline_index\": 0", "\"sdp_mline_index\": \"0\""),
            base.replace("\"sdp_mline_index\": 0", "\"sdp_mline_index\": 0.0"),
            base.replace("\"generation\": 1", "\"generation\": 9"),
            base.replace("\"revision\": 0", "\"revision\": 0, \"sdp\": \"\""),
            base + "{}", "[]", "null"
        )
        for (value in invalid) rejected { RtcCommandCodec.decode(value.toByteArray()) }
    }

    @Test fun permissiveJsonFormsAndTrailingDataAreNotAccepted() {
        for (value in listOf("{'version':1}", "{version:1}", "{\"version\":1,}", "/*comment*/{}", "{} true")) {
            rejected { RtcCommandCodec.decode(value.toByteArray()) }
        }
    }

    @Test fun utf8ByteBudgetsAreEnforcedBeforeNativeReceivesAnySDP() {
        val offer = RtcCommandCodec.decode(fixture("offer"))
        rejected { RtcCommandCodec.encode(offer.copy(sdp = "中".repeat(22_000))) }
        rejected { RtcCommandCodec.encode(offer.copy(sdp = "\u0000")) }
        val candidate = RtcCommandCodec.decode(fixture("candidate"))
        rejected { RtcCommandCodec.encode(candidate.copy(ice = candidate.ice!!.copy(candidate = "中".repeat(700)))) }
        rejected { RtcCommandCodec.decode(ByteArray(RtcCommandCodec.MAX_FRAME_BYTES + 1) { 32 }) }
        rejected { RtcCommandCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        val source = fixture("offer").toString(Charsets.UTF_8)
        rejected { RtcCommandCodec.decode(source.replace("v=0", "v=0\\ud800").toByteArray()) }
        rejected { RtcCommandCodec.decode(source.replace("v=0", "v=0\\udc00").toByteArray()) }
        assertEquals(offer.copy(sdp = "v=0😀"), RtcCommandCodec.decode(RtcCommandCodec.encode(offer.copy(sdp = "v=0😀"))))
    }

    @Test fun typedEncoderCannotEmitTheWrongPayloadOrRevision() {
        val offer = RtcCommandCodec.decode(fixture("offer"))
        rejected { RtcCommandCodec.encode(offer.copy(kind = RtcCommandKind.CLOSE)) }
        rejected { RtcCommandCodec.encode(offer.copy(revision = 0)) }
        rejected { RtcCommandCodec.encode(offer.copy(operationId = "operation\n")) }
        rejected { RtcCommandCodec.encode(offer.copy(ice = RtcIce("candidate:1", "0", 0))) }
    }

    private fun rejected(operation: () -> Unit) {
        try { operation(); fail("Unsafe RTC frame was accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: java.io.IOException) { }
    }
}
