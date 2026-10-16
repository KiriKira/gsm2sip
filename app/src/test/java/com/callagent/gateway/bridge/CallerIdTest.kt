package com.callagent.gateway.bridge

import android.telecom.TelecomManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerIdTest {
    @Test
    fun keepsAPlainNumber() {
        assertEquals("09012345678", CallerId.sipUser("09012345678"))
        assertEquals("+819012345678", CallerId.sipUser("+819012345678"))
    }

    @Test
    fun dropsFormattingThatCannotGoInASipUri() {
        assertEquals("+819012345678", CallerId.sipUser(" +81 90-1234-5678 "))
        assertEquals("0312345678", CallerId.sipUser("(03) 1234.5678"))
    }

    @Test
    fun noNumberBecomesNull() {
        assertNull(CallerId.sipUser(null))
        assertNull(CallerId.sipUser(""))
        assertNull(CallerId.sipUser("unknown"))
        assertNull(CallerId.sipUser("Anonymous"))
        assertNull(CallerId.sipUser("+"))
    }

    @Test
    fun anonymousCallerGetsAnAnonymousDisplayName() {
        assertEquals("Anonymous", CallerId.displayName(CallerId.ANONYMOUS))
        assertEquals("0312345678", CallerId.displayName("0312345678"))
    }

    @Test
    fun descriptionNeverContainsTheNumber() {
        assertEquals("caller number shown", CallerId.describe("09012345678", TelecomManager.PRESENTATION_ALLOWED))
        assertEquals("caller withheld the number", CallerId.describe("", TelecomManager.PRESENTATION_RESTRICTED))
        assertEquals("the network sent no caller number", CallerId.describe(null, TelecomManager.PRESENTATION_UNKNOWN))
    }
}
