package com.callagent.gateway.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinLinkTest {
    private val entries = listOf(
        JoinEntry("https://home.example.lan:8443/v1", "AB12-CD34"),
        JoinEntry("https://vps.example.com", "ZXCV-9876")
    )

    @Test
    fun roundTripsSeveralNodesInPriorityOrder() {
        val link = JoinLink.build(entries)
        assertTrue(JoinLink.isJoinLink(link))
        assertEquals(entries, JoinLink.parse(link))
    }

    @Test
    fun refusesOtherVersionsAndUnsafeOrMalformedEntries() {
        assertNull(JoinLink.parse("mahogsm://join?v=1&role=client&n=https%3A%2F%2Fa%23C"))
        assertNull("the old scheme is not opened", JoinLink.parse("gsm2sip://join?v=2&n=https%3A%2F%2Fa%23C"))
        assertNull("only http(s) servers are accepted", JoinLink.parse(JoinLink.build(listOf(JoinEntry("ftp://a.example", "C")))))
        assertNull("a query is refused", JoinLink.parse(JoinLink.build(listOf(JoinEntry("http://a.example/?x=1", "C")))))
        assertNull("a code is required", JoinLink.parse("mahogsm://join?v=2&n=https%3A%2F%2Fa.example"))
        assertNull("credentials in the URL are refused", JoinLink.parse(JoinLink.build(listOf(JoinEntry("https://u:p@a.example", "C")))))
        assertNull("duplicate servers are refused", JoinLink.parse(JoinLink.build(entries + entries.first())))
        assertNull(JoinLink.parse("mahogsm://other?v=2"))
        assertFalse(JoinLink.isJoinLink("https://example.com"))
    }

    @Test
    fun acceptsPlainHttpNodesOnALanOrTailnet() {
        val lan = listOf(JoinEntry("http://192.168.1.10:8080", "AB12-CD34"), JoinEntry("https://vps.example.com", "ZXCV-9876"))
        assertEquals(lan, JoinLink.parse(JoinLink.build(lan)))
    }

    @Test
    fun parsesTheAdminCommandOutput() {
        // Produced by `mahogsm-admin join create --url https://home.example.lan:8443/v1 --also https://vps.example.com=ZXCV-9876`.
        val link = "mahogsm://join?v=2&n=https%3A%2F%2Fhome.example.lan%3A8443%2Fv1%23AB12-CD34" +
            "&n=https%3A%2F%2Fvps.example.com%23ZXCV-9876"
        assertEquals(entries, JoinLink.parse(link))
    }
}
