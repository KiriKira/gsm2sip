package com.callagent.gateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** SMS dispatch helpers. Every manager is explicitly bound to a validated
 * subscription; inability to bind is an error and never selects Android's default SIM. */
object SmsSender {
    const val ACTION_SENT = "com.callagent.gateway.SMS_SENT"
    const val ACTION_DELIVERED = "com.callagent.gateway.SMS_DELIVERED"
    const val EXTRA_ID = "sms_command_id"
    const val EXTRA_MESSAGE_ID = "sms_message_id"
    const val EXTRA_PART = "sms_part"

    data class Prepared(val manager: SmsManager, val parts: ArrayList<String>)

    fun prepare(context: Context, text: String, validatedSubId: Int): Prepared {
        require(text.isNotEmpty()) { "SMS body is empty" }
        val manager = managerFor(context, validatedSubId)
        val parts = manager.divideMessage(text)
        require(parts.isNotEmpty()) { "SmsManager produced no parts" }
        return Prepared(manager, parts)
    }

    /** Invoke the modem only after the caller has durably written dispatching.
     * A synchronous binder exception is reported as ambiguous; caller must not retry. */
    fun dispatch(context: Context, commandId: String, messageId: String, to: String,
                 prepared: Prepared): Boolean {
        val sent = ArrayList<PendingIntent>(prepared.parts.size)
        val delivered = ArrayList<PendingIntent>(prepared.parts.size)
        for (i in prepared.parts.indices) {
            sent += pendingIntent(context, ACTION_SENT, commandId, messageId, i)
            delivered += pendingIntent(context, ACTION_DELIVERED, commandId, messageId, i)
        }
        return try {
            prepared.manager.sendMultipartTextMessage(to, null, prepared.parts, sent, delivered)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun managerFor(context: Context, subscriptionId: Int): SmsManager {
        require(subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && subscriptionId >= 0) {
            "No validated SIM subscription"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
                ?: throw IllegalStateException("SmsManager service unavailable")
            base.createForSubscriptionId(subscriptionId)
                ?: throw IllegalStateException("Could not bind SmsManager to SIM subscription")
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                ?: throw IllegalStateException("Could not bind SmsManager to SIM subscription")
        }
    }

    /** Each part/type has a stable PendingIntent identity independent of the
     * request-code hash. Repeated callback delivery updates the same data URI. */
    private fun pendingIntent(context: Context, action: String, commandId: String,
                              messageId: String, part: Int): PendingIntent {
        val kind = if (action == ACTION_SENT) "sent" else "delivered"
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            setClass(context, SmsSendReceiver::class.java)
            data = Uri.parse("gsm2sip://sms/$commandId/$part/$kind")
            putExtra(EXTRA_ID, commandId)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_PART, part)
        }
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }
}
