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
        val plainBody = client.heartbeatBody(1, false, 50, true, listOf(plain))
        assertFalse(plainBody.getJSONArray("sim_states").getJSONObject(0).has("radio"))
        val radio = client.heartbeatBody(2, false, 50, true, listOf(withRadio))
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
    fun countryIsoIsUppercasedAndInvalidValuesAreDropped() {
        assertEquals("CN", RadioTelemetry.sanitizeCountry("cn"))
        assertEquals("DE", RadioTelemetry.sanitizeCountry(" de "))
        assertNull(RadioTelemetry.sanitizeCountry(""))
        assertNull(RadioTelemetry.sanitizeCountry("  "))
        assertNull(RadioTelemetry.sanitizeCountry(null))
        assertNull(RadioTelemetry.sanitizeCountry("chn"))
        assertNull(RadioTelemetry.sanitizeCountry("c1"))
        assertNull(RadioTelemetry.sanitizeCountry("中国"))
        // The SIM's home country wins over the serving network while roaming.
        assertEquals("CN", RadioTelemetry.countryIso("cn", "hk"))
        assertEquals("HK", RadioTelemetry.countryIso("", "hk"))
        assertEquals("HK", RadioTelemetry.countryIso(null, "HK"))
        assertNull(RadioTelemetry.countryIso(" ", ""))
        assertNull(RadioTelemetry.countryIso(null, null))
        assertEquals("CN", RadioTelemetry.snapshot(2, null, null, null, countryIso = "cn").countryIso)
        assertNull(RadioTelemetry.snapshot(2, null, null, null, countryIso = "xx1").countryIso)
        assertNull(RadioTelemetry.snapshot(2, null, null, null).countryIso)
    }

    @Test
    fun heartbeatRadioCarriesCountryOnlyWhenSet() {
        val client = ControlApiClient(RuntimeEnvironment.getApplication(), "https://control.invalid")
        val sim = HeartbeatSim("sim-a", 3, "in_service", true,
            radio = RadioTelemetry.snapshot(2, TelephonyManager.NETWORK_TYPE_LTE, "Carrier", true, countryIso = "cn"))
        val radio = client.heartbeatBody(1, false, 50, true, listOf(sim))
            .getJSONArray("sim_states").getJSONObject(0).getJSONObject("radio")
        assertEquals("CN", radio.getString("country_iso"))
        assertEquals(setOf("signal_level", "network_type", "network_operator", "roaming", "country_iso"),
            radio.keys().asSequence().toSet())
        val unknown = client.heartbeatBody(2, false, 50, true,
            listOf(sim.copy(radio = sim.radio!!.copy(countryIso = null))))
            .getJSONArray("sim_states").getJSONObject(0).getJSONObject("radio")
        assertFalse(unknown.has("country_iso"))
    }

    @Test
    fun readFillsCountryOnlyWhenTheServerAcceptsIt() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(TelephonyManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setTelephonyManagerForSubscriptionId(7, manager)
        shadow.setSimCountryIso("cn")
        shadow.setNetworkCountryIso("hk")
        assertNull(RadioTelemetry.read(context, 7)!!.countryIso)
        assertFalse(RadioTelemetry.read(context, 7, includeCountry = false)!!.toJson().has("country_iso"))
        assertEquals("CN", RadioTelemetry.read(context, 7, includeCountry = true)!!.countryIso)
        shadow.setSimCountryIso("")
        assertEquals("HK", RadioTelemetry.read(context, 7, includeCountry = true)!!.countryIso)
        shadow.setNetworkCountryIso("")
        assertNull(RadioTelemetry.read(context, 7, includeCountry = true)!!.countryIso)
    }

    @Test
    fun countryExtensionIsTrackedPerServerNode() {
        val context = RuntimeEnvironment.getApplication()
        ControlExtensions.record(context, "gw-a", "https://a.example", setOf(RadioTelemetry.EXTENSION))
        ControlExtensions.record(context, "gw-b", "https://b.example",
            setOf(RadioTelemetry.EXTENSION, RadioTelemetry.COUNTRY_EXTENSION))
        assertFalse(ControlExtensions.accepts(context, "gw-a", "https://a.example", RadioTelemetry.COUNTRY_EXTENSION))
        assertTrue(ControlExtensions.accepts(context, "gw-b", "https://b.example", RadioTelemetry.COUNTRY_EXTENSION))
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
