package com.callagent.gateway.sms

import android.content.Context
import android.util.Log
import com.callagent.gateway.data.GatewayDatabase

/** Idempotently imports the old SharedPreferences SMS data. Source preferences
 * remain untouched as a recovery copy; old outbound items are quarantined because
 * they contain no server-issued sim_id/mapping_revision. */
object LegacySmsMigration {
    data class Result(val inboundImported: Int, val outboundPreserved: Int, val error: Boolean)

    fun migrate(context: Context): Result {
        val db = GatewayDatabase.get(context)
        var inbound = 0
        var outbound = 0
        var error = false
        try {
            SmsStore.pending(context).forEach { sms ->
                try {
                    db.recordIncomingSms(
                        messageId = sms.id, sender = sms.from, recipient = sms.to, body = sms.text,
                        receivedAt = sms.receivedAt, partCount = sms.parts, subId = sms.subId,
                        slotIndex = sms.slot, simId = null, mappingRevision = null,
                        resolution = "legacy_unbound"
                    )
                    inbound++
                } catch (_: Exception) { error = true }
            }
        } catch (_: Exception) { error = true }
        try {
            SmsOutbox.all(context).forEach { sms ->
                try {
                    if (db.preserveLegacyOutbox(sms.id, sms.to, sms.text, sms.subId, sms.dispatched)) outbound++
                } catch (_: Exception) { error = true }
            }
        } catch (_: Exception) { error = true }
        if (error) Log.e(TAG, "Legacy SMS migration incomplete; source preferences retained")
        return Result(inbound, outbound, error)
    }

    private const val TAG = "LegacySmsMigration"
}
