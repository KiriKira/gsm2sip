package com.callagent.gateway.bridge

import com.callagent.rtcmedia.MediaApi
import com.callagent.rtcmedia.RtcAudioSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RtcAudioSessionCloseOrderingTest {
    private data class Request(val method: String, val path: String)

    private class RecordingFailingApi : MediaApi {
        val requests = CopyOnWriteArrayList<Request>()

        override fun request(method: String, path: String, body: JSONObject?): JSONObject {
            requests += Request(method, path)
            throw IllegalStateException("fake_transport_failure")
        }
    }

    private fun newSession(api: MediaApi): RtcAudioSession {
        val admission = JSONObject()
            .put("call_control", "rtc")
            .put("binding", JSONObject()
                .put("call_id", "10000000-0000-4000-8000-000000000001")
                .put("leg_id", "10000000-0000-4000-8000-000000000002")
                .put("attempt_id", "10000000-0000-4000-8000-000000000003")
                .put("gateway_id", "10000000-0000-4000-8000-000000000004")
                .put("client_id", "10000000-0000-4000-8000-000000000005"))
            .put("ice_servers", JSONArray())
        return RtcAudioSession(
            RuntimeEnvironment.getApplication(), admission, api, true,
            onFailure = {}, onLog = {}
        )
    }

    @Test fun controlTerminalCloseDoesNotSendCompetingSignalingClose() {
        val api = RecordingFailingApi()
        val session = newSession(api)

        session.closeAfterControlEnd()

        assertTrue(session.isClosed)
        assertTrue(session.awaitShutdown(2_000))
        assertTrue("durable control end owns peer teardown", api.requests.isEmpty())
    }

    @Test fun defaultCloseAttemptsSignalingOnceAndIsIdempotent() {
        val api = RecordingFailingApi()
        val session = newSession(api)

        session.close()
        session.close()

        assertTrue(session.isClosed)
        assertTrue(session.awaitShutdown(2_000))
        assertEquals(listOf(Request("POST", "/rtc/legs/10000000-0000-4000-8000-000000000002")), api.requests.toList())
    }
}
