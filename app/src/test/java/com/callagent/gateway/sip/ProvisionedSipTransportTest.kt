package com.callagent.gateway.sip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProvisionedSipTransportTest {
    @Test
    fun registrarPortHandlesSipAndSipsWithParamsAndDefaults() {
        assertEquals(5061, ProvisionedSipTransport.registrarPort("sips:sip.example.test", tls = true))
        assertEquals(5071, ProvisionedSipTransport.registrarPort("sips:sip.example.test:5071", tls = true))
        assertEquals(5060, ProvisionedSipTransport.registrarPort("sip:192.168.1.10", tls = false))
        assertEquals(5060, ProvisionedSipTransport.registrarPort("sip:192.168.1.10:5060", tls = false))
        assertEquals(5080, ProvisionedSipTransport.registrarPort("sip:192.168.1.10:5080;transport=udp;lr", tls = false))
        assertEquals(5090, ProvisionedSipTransport.registrarPort("SIP:dev_x@192.168.1.10:5090", tls = false))
        assertEquals(5060, ProvisionedSipTransport.registrarPort("sip:[fd7a:115c::1]", tls = false))
        assertEquals(5070, ProvisionedSipTransport.registrarPort("sip:[fd7a:115c::1]:5070;transport=udp", tls = false))
        assertTrue(runCatching { ProvisionedSipTransport.registrarPort("https://sip.example.test", true) }.isFailure)
        assertTrue(runCatching { ProvisionedSipTransport.registrarPort("sip:host:99999", false) }.isFailure)
    }

    @Test
    fun absentTransportFieldsMeanTlsWithSdes() {
        val legacy = ProvisionedSipTransport.from(JSONObject().put("registrar_uri", "sips:sip.example.test"))
        assertEquals(ProvisionedSipTransport(tls = true, srtp = true, port = 5061), legacy)
    }

    @Test
    fun udpConfigurationIsPlainSipOnItsOwnPort() {
        val udp = ProvisionedSipTransport.from(JSONObject()
            .put("transport", "udp").put("media_encryption", "none")
            .put("registrar_uri", "sip:192.168.1.10:5060"))
        assertEquals(ProvisionedSipTransport(tls = false, srtp = false, port = 5060), udp)
        // SDES keys ride in the SDP, so they are never claimed over UDP.
        val udpSdes = ProvisionedSipTransport.from(JSONObject()
            .put("transport", "udp").put("registrar_uri", "sip:192.168.1.10"))
        assertEquals(ProvisionedSipTransport(tls = false, srtp = false, port = 5060), udpSdes)
    }

    @Test
    fun unknownTransportValuesAreRejected() {
        assertTrue(runCatching {
            ProvisionedSipTransport.from(JSONObject().put("transport", "tcp").put("registrar_uri", "sip:h"))
        }.isFailure)
        assertTrue(runCatching {
            ProvisionedSipTransport.from(JSONObject().put("media_encryption", "zrtp").put("registrar_uri", "sips:h"))
        }.isFailure)
    }
}
