package com.callagent.gateway.bridge

import android.telecom.TelecomManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerIdTest {
    @Test
    fun keepsAPlainNumber() {
        assertEquals("09012345678", CallerId.normalized("09012345678"))
        assertEquals("+819012345678", CallerId.normalized("+819012345678"))
    }

    @Test
    fun dropsFormatting() {
        assertEquals("+819012345678", CallerId.normalized(" +81 90-1234-5678 "))
        assertEquals("0312345678", CallerId.normalized("(03) 1234.5678"))
    }

    @Test
    fun noNumberBecomesNull() {
        assertNull(CallerId.normalized(null))
        assertNull(CallerId.normalized(""))
        assertNull(CallerId.normalized("unknown"))
        assertNull(CallerId.normalized("Anonymous"))
        assertNull(CallerId.normalized("+"))
    }

    @Test
    fun anonymousCallerGetsAnAnonymousDisplayName() {
        assertEquals("Anonymous", CallerId.displayName(CallerId.ANONYMOUS))
        assertEquals("0312345678", CallerId.displayName("0312345678"))
    }

    @Test
    fun descriptionShowsWhatCameInAndWhatIsSent() {
        assertEquals(
            "from \"090-1234-5678\", normalized 09012345678, presentation allowed",
            CallerId.describe("090-1234-5678", TelecomManager.PRESENTATION_ALLOWED)
        )
        assertEquals(
            "from \"\", normalized anonymous, presentation restricted",
            CallerId.describe("", TelecomManager.PRESENTATION_RESTRICTED)
        )
        assertEquals("from \"\", normalized anonymous, presentation missing", CallerId.describe(null, null))
    }
}
