package com.callagent.gateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import android.provider.Settings
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.service.GatewayService
import com.callagent.gateway.sim.SimRegistry
import java.util.UUID

/** Save each received SMS and its sequence event atomically before waking the
 * control service. The normal SMS app remains the owner of read/unread state. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
        catch (_: Exception) { null } ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress.orEmpty()
        val text = messages.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
        val receivedAt = messages[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID))
        val snapshot = runCatching { SimRegistry.snapshot(context) }.getOrNull()
        val candidate = snapshot?.subscriptions?.firstOrNull { it.subscriptionId == subId }
        val resolved = snapshot?.mappings?.firstOrNull { it.subscriptionId == subId }
            ?.let { runCatching { SimRegistry.resolve(context, it.simId, it.mappingRevision) }.getOrNull() }
        val simId = resolved?.simId
        val revision = resolved?.mappingRevision
        val mappingState = if (resolved != null) "known" else "unknown"
        val slot = resolved?.slotIndex ?: candidate?.slotIndex ?: -1
        val ownNumber = candidate?.phoneNumber.orEmpty()
        val gatewayIdentity = CredentialStore.load(context)?.gatewayId
            ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val messageId = stableMessageId(intent, gatewayIdentity, subId) ?: UUID.randomUUID().toString()
        val now = if (messageId.isBlank()) UUID.randomUUID().toString() else messageId

        // This transaction writes both the local inbox row and its immutable upload event.
        val event = GatewayDatabase.get(context).recordIncomingSms(
            messageId = now, sender = sender, recipient = ownNumber, body = text,
            receivedAt = receivedAt, partCount = messages.size, subId = subId, slotIndex = slot,
            simId = simId, mappingRevision = revision, resolution = mappingState
        )
        CallLogStore.addEntry(context, CallLogEntry(
            direction = "IN", number = sender, timestamp = receivedAt, durationSec = 0,
            type = CallLogStore.TYPE_SMS, text = text, smsId = now, parts = messages.size
        ))
        Log.i(TAG, "SMS received and journaled (parts=${messages.size}, sim=$mappingState, sequence=${event.sequence})")
        GatewayService.deliverQueuedSms(context)
    }

    /** Android may redeliver the same broadcast after process death. Derive its
     * identity from the actual transport PDUs rather than matching sender/text/time. */
    private fun stableMessageId(intent: Intent, gatewayIdentity: String, subId: Int): String? {
        return try {
            val extras = intent.extras ?: return null
            @Suppress("DEPRECATION")
            val raw = extras.get("pdus") as? Array<*> ?: return null
            val pdus = raw.mapNotNull { it as? ByteArray }
            if (pdus.isEmpty()) return null
            SmsIdentity.fromPdus(pdus, gatewayIdentity, subId,
                extras.getString("format").orEmpty())
        } catch (_: Exception) { null }
    }

    companion object { private const val TAG = "SmsReceiver" }
}
