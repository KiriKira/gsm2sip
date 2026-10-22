package com.callagent.gateway.bridge

import com.callagent.rtcmedia.MediaApi
import com.callagent.rtcmedia.MediaApiException
import com.callagent.rtcmedia.RtcAudioSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IncomingRtcNegotiationTest {
    @Test fun acknowledgedAnswerAllowsWinnerBeforeIceButDoesNotActivateAudio() {
        val requests = mutableListOf<String>()
        session(MediaApi { _, _, body ->
            requests += body!!.getString("kind")
            JSONObject().put("ack", JSONObject().put("revision", 2))
        }).use { media ->
            assertFalse(media.awaitNegotiated(0))
            commit(media, "offer")
            assertFalse("An offer cannot authorize cellular answer", media.awaitNegotiated(0))
            commit(media, "answer")
            assertTrue("Committed answer SDP must not wait for ICE gathering", media.awaitNegotiated(0))
            assertFalse("SDP acknowledgement is not connected media", media.awaitConnected(0))
            assertFalse(media.isConnected)
            assertFalse("Only real GSM ACTIVE may authorize PCM", media.cellularActive)
            assertEquals(listOf("offer", "answer"), requests)
        }
    }

    @Test fun failedAnswerCommitCannotAuthorizeWinner() {
        session(MediaApi { _, _, _ -> throw MediaApiException(403, "revoked") }).use { media ->
            val error = runCatching { commit(media, "answer") }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(media.awaitNegotiated(0))
            assertFalse(media.awaitConnected(0))
        }
    }

    @Test fun closedLegCannotReuseCommittedAnswerAuthorization() {
        val media = session(MediaApi { _, _, _ -> JSONObject().put("ack", JSONObject().put("revision", 2)) })
        commit(media, "answer")
        assertTrue(media.awaitNegotiated(0))
        media.close()
        assertFalse(media.awaitNegotiated(0))
    }

    private fun commit(media: RtcAudioSession, kind: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(media, "send",
            ReflectionHelpers.ClassParameter.from(String::class.java, kind),
            ReflectionHelpers.ClassParameter.from(String::class.java, "fixture-sdp"),
            ReflectionHelpers.ClassParameter.from(JSONObject::class.java, null),
            ReflectionHelpers.ClassParameter.from(String::class.java, "fixture-operation"))
    }

    private fun session(api: MediaApi) = RtcAudioSession(
        RuntimeEnvironment.getApplication(),
        JSONObject().put("call_control", "rtc").put("ice_servers", JSONArray()).put("binding",
            JSONObject().put("call_id", "fixture-call").put("leg_id", "fixture-leg")
                .put("attempt_id", "fixture-attempt").put("gateway_id", "fixture-gateway")
                .put("client_id", "fixture-client")),
        api, true, onFailure = { fail(it) }
    )
}
