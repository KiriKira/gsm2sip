package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipAuthTest {
    @Test
    fun rfc2617QopAuthVectorMatchesAndIncludesNonceFields() {
        val params = SipAuth.AuthParams(
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            opaque = "5ccc069c403ebaf9f0171e9517f40e41",
            qop = "auth,auth-int"
        )

        val header = SipAuth.buildAuthHeader(
            method = "GET",
            uri = "/dir/index.html",
            username = "Mufasa",
            password = "Circle Of Life",
            params = params,
            nonceCount = 1,
            cnonce = "0a4f113b"
        )

        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test
    fun parsesQuotedCommasAndSelectsProxyAuthorizationFor407() {
        val response = SipMessage.parse(
            "SIP/2.0 407 Proxy Authentication Required\r\n" +
                "Proxy-Authenticate: Digest realm=\"proxy, east\", nonce=\"n1\", " +
                "qop=\"auth,auth-int\", algorithm=SHA-256\r\n" +
                "Content-Length: 0\r\n\r\n"
        )!!

        val params = SipAuth.parseChallenge(response)
        assertNotNull(params)
        assertEquals("proxy, east", params!!.realm)
        assertEquals("SHA-256", params.algorithm)
        assertTrue(params.isProxy)
        assertTrue(SipAuth.buildAuthHeader("REGISTER", "sip:proxy", "u", "p", params)
            .startsWith("Proxy-Authorization: Digest "))
    }

    @Test
    fun rejectsAmbiguousUnsupportedAndDuplicateChallenges() {
        assertNull(SipAuth.parseChallenge(response(
            "WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\""
        )))
        assertNull(SipAuth.parseChallenge(response(
            "WWW-Authenticate: Digest realm=\"r\", nonce=\"n\", algorithm=MD5-sess"
        )))
        assertNull(SipAuth.parseChallenge(response(
            "WWW-Authenticate: Digest realm=\"r\", nonce=\"n\"\r\n" +
                "WWW-Authenticate: Digest realm=\"r2\", nonce=\"n2\""
        )))
    }

    @Test
    fun nonceCountIncrementsAndStaleNonceRestartsAtOne() {
        val state = SipAuth.DigestState()
        val params = SipAuth.AuthParams("r", "n", null, "auth")
        val first = state.authorization("REGISTER", "sip:host", "u", "p", params)
        val second = state.authorization("REGISTER", "sip:host", "u", "p", params)
        val stale = state.authorization("REGISTER", "sip:host", "u", "p", params.copy(stale = true))

        assertTrue(first.contains("nc=00000001"))
        assertTrue(second.contains("nc=00000002"))
        assertTrue(stale.contains("nc=00000001"))
        assertFalse(first.contains("qop=auth-int"))
    }

    private fun response(challenge: String): SipMessage = SipMessage.parse(
        "SIP/2.0 401 Unauthorized\r\n$challenge\r\nContent-Length: 0\r\n\r\n"
    )!!
}
