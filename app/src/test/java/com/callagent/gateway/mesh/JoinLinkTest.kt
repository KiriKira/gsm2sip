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
    fun parsesTheLinkTheServerPrints() {
        // mahogsm-admin join create, with Go's url.QueryEscape.
        val link = "mahogsm://join?v=2&n=http%3A%2F%2F10.0.2.2%3A18099%23ploJ03-uNhC72fo_2eJ5UmmXraYDke_5fOovll7J2YY"
        assertEquals(listOf(JoinEntry("http://10.0.2.2:18099", "ploJ03-uNhC72fo_2eJ5UmmXraYDke_5fOovll7J2YY")), JoinLink.parse(link))
    }

    @Test
    fun deviceNamesAreClampedInCharacters() {
        assertEquals("Pixel 3", com.callagent.gateway.net.clampDeviceName("  Pixel 3 "))
        assertEquals("网".repeat(100), com.callagent.gateway.net.clampDeviceName("网".repeat(150)))
        val emoji = "\uD83D\uDCF1".repeat(120)
        assertEquals(100, com.callagent.gateway.net.clampDeviceName(emoji).let { it.codePointCount(0, it.length) })
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
