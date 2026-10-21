package com.callagent.gateway.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlRetryScheduleTest {
    private fun schedule() = ControlRetrySchedule(intervalMs = 30_000L, maxRetryMs = 5 * 60_000L)

    private fun failWithUnavailableSecondary(retry: ControlRetrySchedule): Long {
        retry.primaryFailed(retryable = true)
        return syncSecondaryNodesAndRetryAfter(
            retrySchedule = retry,
            withWakeLock = { block -> block(); true },
            syncAll = { false },
            onBackoffReset = { error("an unavailable secondary cannot reset backoff") }
        )
    }

    @Test fun healthySecondaryKeepsPrimaryRetriesAtNormalInterval() {
        val retry = schedule()

        assertEquals(60_000L, retry.primaryFailed(retryable = true))
        var resetLogged = 0
        val afterMesh = syncSecondaryNodesAndRetryAfter(
            retrySchedule = retry,
            withWakeLock = { block -> block(); true },
            syncAll = { true },
            onBackoffReset = { resetLogged++ }
        )
        assertEquals(30_000L, afterMesh)
        assertEquals(1, resetLogged)
        assertEquals(60_000L, retry.primaryFailed(retryable = true))
        assertEquals(30_000L, syncSecondaryNodesAndRetryAfter(
            retrySchedule = retry,
            withWakeLock = { block -> block(); true },
            syncAll = { true },
            onBackoffReset = { resetLogged++ }
        ))
        assertEquals(30_000L, retry.currentDelayMs)
        assertEquals(2, resetLogged)
        assertEquals(30_000L, syncSecondaryNodesAndRetryAfter(
            retrySchedule = retry,
            withWakeLock = { block -> block(); true },
            syncAll = { true },
            onBackoffReset = { resetLogged++ }
        ))
        assertEquals("already-normal polling does not generate a reset log", 2, resetLogged)
    }

    @Test fun allNodesUnavailablePreservesExponentialBackoffAndCap() {
        val retry = schedule()

        assertEquals(60_000L, failWithUnavailableSecondary(retry))
        assertEquals(120_000L, failWithUnavailableSecondary(retry))
        assertEquals(240_000L, failWithUnavailableSecondary(retry))
        assertEquals(300_000L, failWithUnavailableSecondary(retry))
        assertEquals(300_000L, failWithUnavailableSecondary(retry))
    }

    @Test fun primaryRecoveryAndNonRetryableFailureUseNormalInterval() {
        val retry = schedule()

        assertEquals(60_000L, retry.primaryFailed(retryable = true))
        assertEquals(30_000L, retry.primarySucceeded())
        assertEquals(30_000L, retry.primaryFailed(retryable = false))
    }

    @Test fun onlyAnActuallySyncedSecondaryResetsBackoff() {
        val retry = schedule()
        assertEquals(60_000L, retry.primaryFailed(retryable = true))
        var syncCalls = 0
        var resetLogged = false

        val delay = syncSecondaryNodesAndRetryAfter(
            retrySchedule = retry,
            withWakeLock = { block -> block(); true },
            syncAll = { syncCalls++; false },
            onBackoffReset = { resetLogged = true }
        )

        assertEquals(1, syncCalls)
        assertEquals(60_000L, delay)
        assertEquals(60_000L, retry.currentDelayMs)
        assertEquals(false, resetLogged)
    }

    @Test fun aWakeLockThatDoesNotRunWorkCannotImplySecondarySuccess() {
        val retry = schedule()
        assertEquals(60_000L, retry.primaryFailed(retryable = true))
        var syncCalls = 0

        val delay = syncSecondaryNodesAndRetryAfter(
            retrySchedule = retry,
            withWakeLock = { false },
            syncAll = { syncCalls++; true },
            onBackoffReset = { error("must not reset without running sync") }
        )

        assertEquals(0, syncCalls)
        assertEquals(60_000L, delay)
        assertEquals(60_000L, retry.currentDelayMs)
    }
}
