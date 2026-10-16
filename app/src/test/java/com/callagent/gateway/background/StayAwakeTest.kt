package com.callagent.gateway.background

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
class StayAwakeTest {
    @Test
    fun holdsTheCpuAwakeUntilReleased() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val logs = mutableListOf<String>()
            val stayAwake = StayAwake(RuntimeEnvironment.getApplication(), scheduler, exemptFromDoze = false) { logs += it }
            stayAwake.hold()
            val lock = ShadowPowerManager.getLatestWakeLock()
            assertTrue("partial wake lock is held while paired", lock.isHeld)
            stayAwake.hold()
            assertEquals("a second hold does not log again", 1, logs.size)
            stayAwake.release()
            assertFalse("wake lock is released on stop", lock.isHeld)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun leaseOutlivesSeveralRenewals() {
        assertTrue(StayAwake.LEASE_MS >= 2 * StayAwake.RENEW_MS)
    }
}
