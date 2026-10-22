package com.callagent.gateway.audio

/** Cellular audio lifecycle owned by the call orchestrator. Production uses
 * CellularAudioSession; instrumentation can replace physical PCM hardware
 * while retaining the exact orchestrator, Telecom and native transport lifecycle. */
interface CallAudioSession {
    var listener: CellularAudioSession.Listener?
    fun start()
    fun stop()
    fun setAgentMuted(on: Boolean)
    fun setMonitorEnabled(on: Boolean)
}

data class CallAudioConfiguration(
    val context: android.content.Context,
    val pcm: com.callagent.rtcmedia.PcmBridge
) {
    fun realSession(): CallAudioSession = CellularAudioSession(context, pcm)
}
