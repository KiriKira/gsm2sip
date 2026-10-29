package com.callagent.rtcmedia

import org.json.JSONObject
import java.util.UUID

/** Versioned business control, independent of SDP/ICE and SIP. API instances
 * remain bound to the existing paired node/session. Never retries a cellular
 * effect with a new call_id or converts an error into a SIP fallback. */
class RtcCallControl(private val api: MediaApi) {
    /** globalDecline: a gateway that decides decline_all, sent only to a
     * node advertising global_decline_v1. */
    fun capability(enabled: Boolean, globalDecline: Boolean = false) = api.request("POST", "/rtc/control/capability",
        JSONObject().put("version", 1).put("enabled", enabled).apply { if (globalDecline) put("global_decline", true) })
    fun calls(): List<JSONObject> {
        val array = api.request("GET", "/rtc/control/calls", null).getJSONArray("calls")
        return (0 until array.length()).map(array::getJSONObject)
    }
    fun get(id: String) = api.request("GET", "/rtc/control/calls/$id", null)
    /** remainingRingMs, for an incoming call to a node advertising
     * reception_v2 only, is what is left of the call's ring window. */
    fun create(id: String, gateway: String, sim: String, revision: Long, to: String,
               incoming: Boolean = false, from: String? = null, remainingRingMs: Long? = null): JSONObject {
        val body = JSONObject().put("version", 1).put("operation_id", UUID.randomUUID().toString())
            .put("call_id", id).put("gateway_id", gateway).put("sim_id", sim)
            .put("mapping_revision", revision).put("to", to)
        from?.let { body.put("from", it) }
        remainingRingMs?.let { body.put("remaining_ring_ms", it) }
        return committed("/rtc/control/${if (incoming) "incoming" else "calls"}", body)
    }
    fun operation(id: String, kind: String, candidate: String? = null): JSONObject {
        // A CAS conflict authorizes a new attempt only after an authoritative
        // read. Network errors retry the exact same operation id and payload.
        repeat(3) {
            val snapshot = get(id)
            val body = JSONObject().put("version", 1).put("operation_id", UUID.randomUUID().toString())
                .put("revision", snapshot.getLong("state_revision")).put("kind", kind)
            candidate?.let { body.put("candidate_client_id", it) }
            try { return committed("/rtc/control/calls/$id/operations", body) }
            catch (error: MediaApiException) { if (error.status != 409 || error.code != "CALL_STATE_CONFLICT") throw error }
        }
        throw MediaApiException(409, "CALL_STATE_CONFLICT")
    }
    private fun committed(path: String, body: JSONObject): JSONObject {
        var failure: MediaApiException? = null
        repeat(3) { attempt ->
            try { return api.request("POST", path, body) }
            catch (error: MediaApiException) {
                if (error.status < 500) throw error
                failure = error
                if (attempt < 2) Thread.sleep(150L * (attempt + 1))
            }
        }
        throw checkNotNull(failure)
    }
}
