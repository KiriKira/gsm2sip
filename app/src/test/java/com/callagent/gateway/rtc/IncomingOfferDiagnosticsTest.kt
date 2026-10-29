package com.callagent.gateway.rtc

import android.util.Log
import com.callagent.rtcmedia.MediaApiException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IncomingOfferDiagnosticsTest {
    @Before fun clearLogs() = ShadowLog.clear()

    @Test fun eachNodeOfferLogsItsRemainingRingBudget() {
        IncomingOfferDiagnostics.started("call-id", "node-a-123456", remainingRingMs(1_000, 4_400))
        IncomingOfferDiagnostics.started("call-id", "node-b-123456", remainingRingMs(1_000, 8_100))

        val logs = ShadowLog.getLogsForTag("GsmRtc")
        assertEquals(2, logs.size)
        assertEquals(Log.INFO, logs[0].type)
        assertTrue(logs[0].msg.contains("event=incoming_offer"))
        assertTrue(logs[0].msg.contains("node=node-a-1 remaining_ring_ms=21600"))
        assertTrue(logs[1].msg.contains("node=node-b-1 remaining_ring_ms=17900"))
        assertTrue(logs.all { it.msg.contains("call_id=call-id") })
    }

    @Test fun subscriberRejectionLogsTheTrustedCodeAndAttemptBudget() {
        IncomingOfferDiagnostics.failed("call-id", "node-a", 20_000,
            MediaApiException(503, "NO_INCOMING_SUBSCRIBERS"))

        val log = ShadowLog.getLogsForTag("GsmRtc").single()
        assertEquals(Log.WARN, log.type)
        assertTrue(log.msg.contains("event=incoming_offer_failed"))
        assertTrue(log.msg.contains("remaining_ring_ms=20000"))
        assertTrue(log.msg.contains("error=MediaApiException code=NO_INCOMING_SUBSCRIBERS"))
    }

    @Test fun arbitraryServerCodesAndExceptionMessagesNeverEnterLogs() {
        val untrustedCodes = listOf(
            "Bearer secret-token +8613800000000", "NO_INCOMING_SUBSCRIBERS\nsecret-token",
            "UNKNOWN_SERVER_TEXT", "NO_INCOMING_SUBSCRIBERS_extra"
        )
        untrustedCodes.forEach { code ->
            IncomingOfferDiagnostics.failed("call-id", "node-a", 20_000, MediaApiException(503, code))
            val log = ShadowLog.getLogsForTag("GsmRtc").last()
            assertTrue(log.msg.endsWith("error=MediaApiException code=UNKNOWN"))
            assertFalse(log.msg.contains(code))
            assertNull(log.throwable)
        }
        IncomingOfferDiagnostics.failed("call-id", "node-a", 20_000,
            IllegalStateException("Bearer secret-token +8613800000000"))
        val log = ShadowLog.getLogsForTag("GsmRtc").last()
        assertTrue(log.msg.endsWith("error=IllegalStateException code=UNKNOWN"))
        assertFalse(log.msg.contains("secret-token"))
        assertFalse(log.msg.contains("+8613800000000"))
        assertNull(log.throwable)
    }
}
