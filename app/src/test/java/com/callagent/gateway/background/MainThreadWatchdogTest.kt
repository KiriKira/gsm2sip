package com.callagent.gateway.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadWatchdogTest {
    @Test
    fun onlyALongStallWithoutADebuggerRestarts() {
        assertFalse(MainThreadWatchdog.stalled(MainThreadWatchdog.STALL_MS - 1, debuggerAttached = false))
        assertTrue(MainThreadWatchdog.stalled(MainThreadWatchdog.STALL_MS, debuggerAttached = false))
        assertFalse("a paused debugger is not a stall", MainThreadWatchdog.stalled(10 * MainThreadWatchdog.STALL_MS, debuggerAttached = true))
    }
}
