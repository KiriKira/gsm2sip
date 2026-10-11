package com.callagent.gateway.sim

import android.telephony.TelephonyManager
import org.robolectric.RuntimeEnvironment
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.net.ControlExtensions
import com.callagent.gateway.net.HeartbeatSim
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioTelemetryTest {
    @Test
    fun snapshotKeepsOnlyBoundedDisplayValues() {
        val radio = RadioTelemetry.snapshot(
            signalLevel = 7,
            networkType = TelephonyManager.NETWORK_TYPE_LTE,
            networkOperator = "  中国移动\n",
            roaming = false
        )
        assertNull(radio.signalLevel)
        assertEquals("LTE", radio.networkType)
        assertEquals("中国移动", radio.networkOperator)
        assertEquals(false, radio.roaming)
        assertNull(RadioTelemetry.snapshot(3, TelephonyManager.NETWORK_TYPE_UNKNOWN, " ", null).networkType)
        assertNull(RadioTelemetry.sanitizeOperator("\u0000 \t"))
        assertEquals(64, RadioTelemetry.sanitizeOperator("运".repeat(80))!!.codePointCount(0, 64))
    }

    @Test
    fun heartbeatBodyOmitsRadioUntilTheServerAcceptsIt() {
        val client = ControlApiClient(RuntimeEnvironment.getApplication(), "https://control.invalid")
        val plain = HeartbeatSim("sim-a", 3, "in_service", true)
        val withRadio = plain.copy(radio = RadioSnapshot(2, "NR", "Carrier", null))
        val plainBody = client.heartbeatBody(1, false, false, 50, true, listOf(plain))
        assertFalse(plainBody.getJSONArray("sim_states").getJSONObject(0).has("radio"))
        val radio = client.heartbeatBody(2, false, false, 50, true, listOf(withRadio))
            .getJSONArray("sim_states").getJSONObject(0).getJSONObject("radio")
        assertEquals(2, radio.getInt("signal_level"))
        assertEquals("NR", radio.getString("network_type"))
        assertEquals("Carrier", radio.getString("network_operator"))
        assertTrue(radio.isNull("roaming"))
        assertEquals(setOf("signal_level", "network_type", "network_operator", "roaming"),
            radio.keys().asSequence().toSet())
        JSONObject(radio.toString())
    }

    @Test
    fun extensionsAreScopedToGatewayAndServerAndCanBeForgotten() {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://control.example/"
        assertFalse(ControlExtensions.accepts(context, "gw-1", url, RadioTelemetry.EXTENSION))
        ControlExtensions.record(context, "gw-1", url, setOf(RadioTelemetry.EXTENSION))
        assertTrue(ControlExtensions.accepts(context, "gw-1", "https://control.example", RadioTelemetry.EXTENSION))
        assertFalse(ControlExtensions.accepts(context, "gw-2", url, RadioTelemetry.EXTENSION))
        assertFalse(ControlExtensions.accepts(context, "gw-1", "https://other.example", RadioTelemetry.EXTENSION))
        ControlExtensions.forget(context, "gw-1", url)
        assertFalse(ControlExtensions.accepts(context, "gw-1", url, RadioTelemetry.EXTENSION))
    }
}
