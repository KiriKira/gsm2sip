package com.callagent.webrtcprobe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import org.json.JSONObject

/** Debug-only entry for coexistence tests; uses the exact native pair and assertions. */
class WebRtcProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val nonce = intent.getStringExtra("run_id") ?: "missing"
        val label = TextView(this).apply {
            text = "Native WebRTC synthetic PCM probe\nRunning ICE / DTLS-SRTP / Opus"
            textSize = 20f; setPadding(24, 40, 24, 24)
        }
        setContentView(label)
        Thread({
            val result = JSONObject().put("run_id", nonce).put("driver", "debug_activity")
                .put("scope", "synthetic_webrtc_pcm").put("cellular_audio", "NOT_RUN").put("turn", "NOT_RUN")
            val pair = SyntheticPeerPair(applicationContext)
            var code = Activity.RESULT_CANCELED
            try {
                result.put("observed", pair.use { it.run() })
                    .put("diagnostics", pair.diagnostics()).put("status", "PASS")
                code = Activity.RESULT_OK
            } catch (failure: Throwable) {
                result.put("status", "FAIL").put("failure", failure.javaClass.simpleName)
                    .put("diagnostics", pair.diagnostics())
            }
            result.put("activity_result_code", code)
            val temporary = filesDir.resolve("webrtc-probe-activity-result.new")
            temporary.writeText(result.toString())
            check(temporary.renameTo(filesDir.resolve("webrtc-probe-activity-result.json")))
            runOnUiThread { setResult(code); finish() }
        }, "webrtc-probe-activity").start()
    }
}
