package com.callagent.gateway.ui

import com.callagent.gateway.ui.GatewaySetupChecklist.Action
import com.callagent.gateway.ui.GatewaySetupChecklist.Input
import com.callagent.gateway.ui.GatewaySetupChecklist.Status
import com.callagent.gateway.ui.GatewaySetupChecklist.StepId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySetupChecklistTest {
    private val ready = Input(
        paired = true, smsPermissionsGranted = true, activeSimCount = 2, detectedSimCount = 2,
        proposalPending = false, backgroundEnabled = true, backgroundRunning = true,
        notificationsEnabled = true, batteryExempt = true, voiceConfigured = false,
        voiceRunning = false, sipRegistered = false
    )

    private fun step(input: Input, id: StepId) = GatewaySetupChecklist.build(input).steps.first { it.id == id }

    @Test
    fun freshInstallPointsOnlyAtPairing() {
        val summary = GatewaySetupChecklist.build(ready.copy(paired = false, activeSimCount = 0,
            backgroundEnabled = false, backgroundRunning = false))
        assertFalse(summary.requiredComplete)
        assertEquals(listOf(Status.NEXT, Status.BLOCKED, Status.BLOCKED, Status.BLOCKED), summary.steps.map { it.status })
        assertEquals(Action.OPEN_PAIRING, summary.steps.first().action)
        assertEquals("完成设置（还剩 3 步）", summary.headline)
    }

    @Test
    fun simStepFollowsPermissionProposalAndBindingState() {
        assertEquals(Action.OPEN_SIMS, step(ready.copy(smsPermissionsGranted = false), StepId.SIMS).action)
        assertEquals("去确认", step(ready.copy(proposalPending = true), StepId.SIMS).actionLabel)
        assertEquals(Status.NEXT, step(ready.copy(activeSimCount = 0), StepId.SIMS).status)
        assertEquals(Status.WARNING, step(ready.copy(activeSimCount = 1), StepId.SIMS).status)
        assertEquals(Status.DONE, step(ready, StepId.SIMS).status)
    }

    @Test
    fun backgroundWarningsDoNotBlockSmsReadiness() {
        val summary = GatewaySetupChecklist.build(ready.copy(batteryExempt = false))
        assertTrue(summary.requiredComplete)
        assertEquals(Action.OPEN_BATTERY, summary.steps.first { it.id == StepId.BACKGROUND }.action)
        assertEquals("网关可用 · 有 1 项建议", summary.headline)
        assertEquals(Status.NEXT, step(ready.copy(backgroundEnabled = false), StepId.BACKGROUND).status)
    }

    @Test
    fun voiceIsOptionalUntilRegistered() {
        assertEquals("网关已就绪 · 短信", GatewaySetupChecklist.build(ready).headline)
        assertEquals(Action.CONFIGURE_VOICE, step(ready, StepId.VOICE).action)
        assertEquals(Action.START_VOICE, step(ready.copy(voiceConfigured = true), StepId.VOICE).action)
        assertEquals(Status.WARNING, step(ready.copy(voiceConfigured = true, voiceRunning = true), StepId.VOICE).status)
        val full = GatewaySetupChecklist.build(ready.copy(voiceConfigured = true, voiceRunning = true, sipRegistered = true))
        assertEquals("网关已就绪 · 短信与通话", full.headline)
    }
}
