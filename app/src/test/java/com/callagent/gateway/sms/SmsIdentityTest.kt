package com.callagent.gateway.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsIdentityTest {
    @Test fun samePduRedeliveryIsStableAndSubscriptionScoped() {
        val pdus = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        val first = SmsIdentity.fromPdus(pdus, "gateway-A", 10, "3gpp")
        assertEquals(first, SmsIdentity.fromPdus(pdus, "gateway-A", 10, "3gpp"))
        assertNotEquals(first, SmsIdentity.fromPdus(pdus, "gateway-A", 11, "3gpp"))
        assertNotEquals(first, SmsIdentity.fromPdus(pdus, "gateway-B", 10, "3gpp"))
        assertNotEquals(first, SmsIdentity.fromPdus(pdus, "gateway-A", 10, "3gpp2"))
        assertNull(SmsIdentity.fromPdus(emptyList(), "gateway-A", 10, "3gpp"))
    }
}
