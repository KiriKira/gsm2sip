package com.callagent.webrtcprobe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject

/** A separate APK, with no Activity, root, microphone permission or production app integration. */
class WebRtcProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        targetContext.filesDir.resolve("webrtc-probe-state.json")
            .writeText(JSONObject().put("stage", "instrumentation_started").toString())
        val result = JSONObject().put("scope", "synthetic_webrtc_pcm")
            .put("cellular_audio", "NOT_RUN").put("turn", "NOT_RUN")
        val pair = SyntheticPeerPair(targetContext)
        try {
            result.put("observed", pair.use { it.run() })
            result.put("diagnostics", pair.diagnostics())
            result.put("status", "PASS")
            finish(Activity.RESULT_OK, Bundle().apply { putString("probe_result", result.toString()) })
        } catch (failure: Throwable) {
            // Do not log SDP, candidates, audio data or native credential material.
            result.put("status", "FAIL").put("failure", failure.javaClass.simpleName)
            val reasons = mapOf(
                "Native audio or ICE failed" to "native_audio_or_ice_failed",
                "A default Android audio device was active" to "default_audio_device_active",
                "Timed out waiting for bidirectional decoded PCM" to "pcm_timeout",
                "Timed out waiting for ICE and DTLS" to "ice_timeout",
                "SDK has no Opus encoder" to "opus_unavailable",
                "SDP create failed" to "sdp_create_failed",
                "SDP set failed" to "sdp_set_failed",
                "Audio scheduler did not stop" to "audio_scheduler_shutdown_failed",
                "ICE relay did not stop" to "ice_relay_shutdown_failed")
            result.put("failure_code", reasons[failure.message]
                ?: if (failure is java.util.concurrent.TimeoutException) "operation_timeout" else "unexpected_exception")
            result.put("diagnostics", pair.diagnostics())
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("probe_result", result.toString()) })
        }
    }
}
