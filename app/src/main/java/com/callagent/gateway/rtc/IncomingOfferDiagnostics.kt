package com.callagent.gateway.rtc

import android.util.Log
import com.callagent.rtcmedia.MediaApiException

/** Offer diagnostics contain only identifiers, timing and known error codes. */
internal object IncomingOfferDiagnostics {
    fun started(callId: String, nodeId: String, remainingRingMs: Long) {
        Log.i("GsmRtc", "${fields(callId, nodeId, remainingRingMs)} event=incoming_offer")
    }

    fun failed(callId: String, nodeId: String, remainingRingMs: Long, error: Throwable?) {
        // Codes arrive from the server. An exact allowlist prevents arbitrary
        // response text, credentials or phone numbers entering this log.
        val code = (error as? MediaApiException)?.code?.takeIf { it in trustedCodes } ?: "UNKNOWN"
        Log.w("GsmRtc", "${fields(callId, nodeId, remainingRingMs)} event=incoming_offer_failed " +
            "error=${error?.javaClass?.simpleName ?: "CreateRejected"} code=$code")
    }

    private fun fields(callId: String, nodeId: String, remainingRingMs: Long) =
        "call_id=$callId node=${nodeId.take(8)} remaining_ring_ms=$remainingRingMs"

    private val trustedCodes = setOf(
        "NO_INCOMING_SUBSCRIBERS", "GATEWAY_UNAVAILABLE", "NETWORK_UNAVAILABLE",
        "CALL_ID_CONFLICT", "CALL_STATE_CONFLICT"
    )
}
