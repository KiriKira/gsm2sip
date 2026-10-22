package com.callagent.gateway.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RtcAuthorizationClosurePolicyTest {
    @Test fun readsFreshEndRequestedSnapshotAndRoutesItAsRemoteEnd() {
        val reads = AtomicInteger()
        val remoteEnds = AtomicInteger()
        val mediaFailures = AtomicInteger()
        val remoteEnd = rtcCallHasTerminalIntent {
            reads.incrementAndGet()
            JSONObject().put("state", "active").put("end_requested", true)
        }

        dispatchRtcClosureIfCurrent(
            isCurrent = { true },
            remoteEnd = remoteEnd,
            onRemoteEnd = { remoteEnds.incrementAndGet() },
            onMediaFailure = { mediaFailures.incrementAndGet() }
        )

        assertEquals("must query the authoritative control snapshot", 1, reads.get())
        assertEquals(1, remoteEnds.get())
        assertEquals(0, mediaFailures.get())
    }

    @Test fun endedStateIsNormalButActiveWithoutEndIntentRemainsFailure() {
        assertTrue(rtcCallHasTerminalIntent {
            JSONObject().put("state", "ended").put("end_requested", false)
        })
        val remoteEnd = rtcCallHasTerminalIntent {
            JSONObject().put("state", "active").put("end_requested", false)
        }
        assertFalse(remoteEnd)
        val mediaFailures = AtomicInteger()
        dispatchRtcClosureIfCurrent(
            isCurrent = { true },
            remoteEnd = remoteEnd,
            onRemoteEnd = {},
            onMediaFailure = { mediaFailures.incrementAndGet() }
        )
        assertEquals(1, mediaFailures.get())
    }

    @Test fun unreadableControlStateFailsClosedInsteadOfSwallowingMediaFailure() {
        assertFalse(rtcCallHasTerminalIntent { throw IllegalStateException("control unavailable") })
        val mediaFailures = AtomicInteger()
        dispatchRtcClosureIfCurrent(
            isCurrent = { true },
            remoteEnd = false,
            onRemoteEnd = {},
            onMediaFailure = { mediaFailures.incrementAndGet() }
        )
        assertEquals(1, mediaFailures.get())
    }

    @Test fun staleClosureResultDoesNotReachEitherCallback() {
        val remoteEnds = AtomicInteger()
        val mediaFailures = AtomicInteger()
        dispatchRtcClosureIfCurrent(
            isCurrent = { false },
            remoteEnd = true,
            onRemoteEnd = { remoteEnds.incrementAndGet() },
            onMediaFailure = { mediaFailures.incrementAndGet() }
        )
        assertEquals(0, remoteEnds.get())
        assertEquals(0, mediaFailures.get())
    }
}
