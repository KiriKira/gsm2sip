package com.callagent.gateway.net

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControlHeartbeatResultTest {
    private val client = ControlApiClient(RuntimeEnvironment.getApplication(), "https://control.example")

    @Test fun unboundGatewayAcceptsRevisionZeroAndLearnsExtensions() {
        val result = client.parseHeartbeatResult(JSONObject("""{
            "mapping_revision":0,"invalidated_sim_ids":[],"extensions":["mesh"]
        }"""))
        assertEquals(0L, result.mappingRevision)
        assertTrue(result.invalidatedSimIds.isEmpty())
        assertEquals(setOf("mesh"), result.extensions)
    }

    @Test fun negativeOrMissingRevisionIsStillRejected() {
        listOf(JSONObject(), JSONObject().put("mapping_revision", -1)).forEach { body ->
            val failure = runCatching { client.parseHeartbeatResult(body) }.exceptionOrNull()
            assertEquals("INVALID_HEARTBEAT_RESPONSE", (failure as? ControlApiException)?.code)
        }
    }
}
