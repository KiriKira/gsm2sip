package com.callagent.gateway.rtp

/** Cellular audio lifecycle owned by the call orchestrator. Production uses
 * RtpSession; instrumentation can replace physical PCM hardware for RTC while
 * retaining the exact orchestrator, Telecom and native transport lifecycle. */
interface CallAudioSession {
    var listener: RtpSession.Listener?
    var srtpSend: SrtpContext?
    var srtpRecv: SrtpContext?
    fun start()
    fun stop()
    fun setAgentMuted(on: Boolean)
    fun setMonitorEnabled(on: Boolean)
}

data class CallAudioConfiguration(
    val context: android.content.Context,
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val payloadType: Int,
    val telephoneEventPayloadType: Int?,
    val requireSrtp: Boolean,
    val externalPcm: com.callagent.rtcmedia.PcmBridge?
) {
    fun realSession(): CallAudioSession = RtpSession(context, localPort,
        remoteAddress, remotePort, payloadType, telephoneEventPayloadType,
        requireSrtp, externalPcm)
}
