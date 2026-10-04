package com.callagent.gateway.background

import android.app.NotificationManager
import com.callagent.gateway.net.WakeConnectionPolicy
import com.callagent.gateway.net.WakeSessionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayBackgroundPolicyTest {
    @Test
    fun explicitStopBlocksBothBootAndStickyControlRecovery() {
        assertFalse(GatewayBackgroundPolicy.mayRestoreControl(
            sessionPresent = true, optedIn = true, userStopped = true
        ))
        assertFalse(GatewayBackgroundPolicy.mayRestoreControl(
            sessionPresent = false, optedIn = true, userStopped = false
        ))
        assertTrue(GatewayBackgroundPolicy.mayRestoreControl(
            sessionPresent = true, optedIn = true, userStopped = false
        ))
        assertEquals("Pair this device with the control server", GatewayBackgroundPolicy.pairingIssue(false))
        assertEquals(null, GatewayBackgroundPolicy.pairingIssue(true))
    }

    @Test
    fun stickyRestartCannotResumeVoiceAndTaskManagerStopNeedsNewEnable() {
        assertFalse(GatewayBackgroundPolicy.mayRestoreVoice(
            intentAction = null, sipConfigured = true, explicitStartAction = "voice-start"
        ))
        assertTrue(GatewayBackgroundPolicy.mayRestoreVoice(
            intentAction = "voice-start", sipConfigured = true, explicitStartAction = "voice-start"
        ))
        assertFalse(GatewayBackgroundPolicy.mayRestoreVoice(
            intentAction = "voice-start", sipConfigured = false, explicitStartAction = "voice-start"
        ))
        assertTrue(GatewayBackgroundPolicy.isTaskManagerStopAfterEnable(200, 100, 10, 10))
        assertFalse(GatewayBackgroundPolicy.isTaskManagerStopAfterEnable(99, 100, 10, 10))
        assertFalse(GatewayBackgroundPolicy.isTaskManagerStopAfterEnable(200, 100, 5, 10))
    }

    @Test
    fun staleWakeCallbacksAreFencedAfterStopOrTokenRotation() {
        val firstToken = WakeSessionIdentity("gateway-a", "https://control.example", "token-generation-a")
        val rotatedToken = firstToken.copy(tokenGeneration = "token-generation-b")
        assertTrue(WakeConnectionPolicy.callbackIsCurrent(4, 4, firstToken, firstToken))
        assertFalse(WakeConnectionPolicy.callbackIsCurrent(4, 5, firstToken, firstToken))
        assertFalse(WakeConnectionPolicy.callbackIsCurrent(4, 4, firstToken, rotatedToken))
        assertTrue(WakeConnectionPolicy.requiresHttpsRefresh(401, null))
        assertTrue(WakeConnectionPolicy.requiresHttpsRefresh(null, 1008))
        assertFalse(WakeConnectionPolicy.requiresHttpsRefresh(403, 1000))
    }

    @Test
    fun wakeUrlIsWssOnlyAndDoesNotDuplicateTheVersionSegment() {
        assertEquals("wss://control.example/v1/ws",
            WakeConnectionPolicy.webSocketUrl("https://control.example"))
        assertEquals("wss://control.example/v1/ws",
            WakeConnectionPolicy.webSocketUrl("https://control.example/v1/"))
        assertEquals("wss://control.example/edge/v1/ws",
            WakeConnectionPolicy.webSocketUrl("https://control.example/edge"))
        val rejected = runCatching { WakeConnectionPolicy.webSocketUrl("http://control.example") }.exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        val queryRejected = runCatching {
            WakeConnectionPolicy.webSocketUrl("https://control.example?token=not-allowed")
        }.exceptionOrNull()
        assertTrue(queryRejected is IllegalArgumentException)
    }

    @Test
    fun deniedNotificationsAndControlOnlyDoNotClaimLongLivedWakeLeases() {
        assertFalse(BackgroundNotificationPolicy.enabled(false, NotificationManager.IMPORTANCE_LOW))
        assertFalse(BackgroundNotificationPolicy.enabled(true, NotificationManager.IMPORTANCE_NONE))
        assertTrue(BackgroundNotificationPolicy.enabled(true, NotificationManager.IMPORTANCE_MIN))

        assertFalse(GatewayBackgroundPolicy.holdVoiceLease(
            voiceRuntimeStarted = false, callActive = true
        ))
        assertTrue(GatewayBackgroundPolicy.holdVoiceLease(
            voiceRuntimeStarted = true, callActive = true
        ))
        assertFalse(GatewayBackgroundPolicy.holdVoiceLease(
            voiceRuntimeStarted = true, callActive = false
        ))
        assertEquals(90_000L, GatewayBackgroundPolicy.SYNC_WAKE_TIMEOUT_MS)
        assertTrue(GatewayBackgroundPolicy.VOICE_WAKE_LEASE_TIMEOUT_MS >
            GatewayBackgroundPolicy.VOICE_WAKE_RENEW_MS)
        assertTrue(GatewayBackgroundPolicy.WIFI_LOCK_MAX_LEASE_MS <=
            GatewayBackgroundPolicy.VOICE_WAKE_LEASE_TIMEOUT_MS)
    }
}
