package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipRegistrationExpiryTest {
    @Test
    fun honorsGrantedExpiresAndPerContactExpiry() {
        for (seconds in listOf(300L, 600L, 1800L, 3600L)) {
            assertEquals(seconds, granted("Expires: $seconds"))
        }
        assertEquals(300L, granted("Expires: 600", "Contact: <sip:u@host>;expires=300"))
        assertEquals(600L, granted("Expires: 600", "Contact: <sip:u@host>"))
    }

    @Test
    fun usesRequestedExpiryOnlyWhenServerOmitsGrant() {
        val message = SipMessage.parse("SIP/2.0 200 OK\r\nContent-Length: 0\r\n\r\n")!!
        assertEquals(3600L, SipRegistrationExpiry.grantedSeconds(message, 3600))
    }

    @Test
    fun rejectsZeroMalformedAndConflictingGrantedValues() {
        assertNull(granted("Expires: 0"))
        assertNull(granted("Expires: 300", "Expires: 600"))
        assertNull(granted("Expires: 300", "Contact: <sip:u@host>;expires=unknown"))
        assertNull(granted("Expires: 300", "Contact: <sip:u@host>;expires=300, <sip:u@other>;expires=600"))
    }

    @Test
    fun refreshesAtHalfTheGrantedLifetime() {
        assertEquals(150_000L, SipRegistrationExpiry.refreshAfterMillis(300))
        assertEquals(300_000L, SipRegistrationExpiry.refreshAfterMillis(600))
        assertEquals(900_000L, SipRegistrationExpiry.refreshAfterMillis(1800))
        assertEquals(1_800_000L, SipRegistrationExpiry.refreshAfterMillis(3600))
    }

    private fun granted(vararg headers: String): Long? {
        val response = SipMessage.parse(
            "SIP/2.0 200 OK\r\n${headers.joinToString("\r\n")}\r\nContent-Length: 0\r\n\r\n"
        )!!
        return SipRegistrationExpiry.grantedSeconds(response, 3600)
    }
}
