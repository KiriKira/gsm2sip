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
        val result = JSONObject().put("scope", "synthetic_webrtc_pcm")
            .put("cellular_audio", "NOT_RUN").put("turn", "NOT_RUN")
        try {
            result.put("observed", SyntheticPeerPair(targetContext).use { it.run() })
            result.put("status", "PASS")
            finish(Activity.RESULT_OK, Bundle().apply { putString("probe_result", result.toString()) })
        } catch (failure: Throwable) {
            // Do not log SDP, candidates, audio data or native credential material.
            result.put("status", "FAIL").put("failure", failure.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("probe_result", result.toString()) })
        }
    }
}
