package com.callagent.gateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.service.GatewayService

/** Stable explicit PendingIntent callbacks. The SQLite part row makes duplicate
 * callback delivery idempotent across receiver/process restarts. */
class SmsSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val commandId = intent.getStringExtra(SmsSender.EXTRA_ID) ?: return
        val messageId = intent.getStringExtra(SmsSender.EXTRA_MESSAGE_ID) ?: return
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, -1)
        if (part < 0) return

        val database = GatewayDatabase.get(context)
        when (intent.action) {
            SmsSender.ACTION_SENT -> {
                val result = resultCode
                val cause = intent.getIntExtra("errorCode", -1)
                val ok = result == Activity.RESULT_OK
                val error = if (ok) "" else sentErrorName(result, cause)
                val changed = database.recordPartState(commandId, part,
                    if (ok) "submitted" else "failed", result, error)
                if (changed) refreshCallLog(context, database, commandId, messageId, error = error)
                Log.i(TAG, "SMS sent callback: command=$commandId part=$part state=${if (ok) "submitted" else "failed"}")
            }
            SmsSender.ACTION_DELIVERED -> {
                val parsed = parseDelivery(intent)
                val disposition = SmsDeliveryClassifier.classify(parsed.status)
                if (disposition == SmsDeliveryClassifier.Disposition.TEMPORARY_FAILURE) {
                    Log.i(TAG, "SMS delivery callback temporary status: command=$commandId part=$part")
                    return
                }
                val state = when (disposition) {
                    SmsDeliveryClassifier.Disposition.DELIVERED -> "delivered"
                    SmsDeliveryClassifier.Disposition.PERMANENT_FAILURE -> "failed"
                    SmsDeliveryClassifier.Disposition.UNKNOWN -> "unknown"
                    SmsDeliveryClassifier.Disposition.TEMPORARY_FAILURE -> return
                }
                val error = if (state == "failed") "status_${parsed.status}" else ""
                val changed = database.recordPartState(commandId, part, state,
                    resultCode = parsed.status, error = error, smsc = parsed.smsc)
                if (changed) refreshCallLog(context, database, commandId, messageId, error, parsed.smsc)
                Log.i(TAG, "SMS delivery callback: command=$commandId part=$part state=$state")
            }
            else -> return
        }
        GatewayService.reportSmsProgress(context, commandId)
    }

    private fun refreshCallLog(context: Context, database: GatewayDatabase, commandId: String,
                               messageId: String, error: String = "", smsc: String = "") {
        val aggregate = database.commandSmsAggregate(commandId) ?: return
        val status = when (aggregate.state) {
            "dispatching" -> "pending"
            "submitted" -> "sent"
            "delivered" -> "delivered"
            "failed", "expired" -> "failed"
            else -> "unknown"
        }
        CallLogStore.updateSms(context, messageId) {
            it.copy(status = status, parts = aggregate.partCount,
                error = if (status == "failed" || status == "unknown") error.ifEmpty { it.error } else "",
                smsc = smsc.ifEmpty { it.smsc })
        }
    }

    private data class Delivery(val status: Int?, val smsc: String)

    private fun parseDelivery(intent: Intent): Delivery = try {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        val parsed = if (pdu != null) SmsMessage.createFromPdu(pdu, format) else null
        Delivery(parsed?.status, parsed?.serviceCenterAddress.orEmpty())
    } catch (_: Exception) { Delivery(null, "") }

    private fun sentErrorName(result: Int, cause: Int): String {
        val name = when (result) {
            SmsManagerResult.GENERIC_FAILURE -> "generic_failure"
            SmsManagerResult.NO_SERVICE -> "no_service"
            SmsManagerResult.NULL_PDU -> "null_pdu"
            SmsManagerResult.RADIO_OFF -> "radio_off"
            SmsManagerResult.LIMIT_EXCEEDED -> "limit_exceeded"
            else -> "error_$result"
        }
        return if (cause >= 0) "$name/cause_$cause" else name
    }

    private object SmsManagerResult {
        const val GENERIC_FAILURE = 1
        const val NO_SERVICE = 4
        const val NULL_PDU = 3
        const val RADIO_OFF = 2
        const val LIMIT_EXCEEDED = 5
    }

    companion object { private const val TAG = "SmsSendReceiver" }
}

/** TP-Status 0..31 is final success, 32..63 is temporary, 64..127 is final
 * failure, and reserved/out-of-range values cannot establish delivery. */
object SmsDeliveryClassifier {
    enum class Disposition { DELIVERED, TEMPORARY_FAILURE, PERMANENT_FAILURE, UNKNOWN }
    fun classify(status: Int?): Disposition = when (status) {
        null -> Disposition.UNKNOWN
        in 0..31 -> Disposition.DELIVERED
        in 32..63 -> Disposition.TEMPORARY_FAILURE
        in 64..127 -> Disposition.PERMANENT_FAILURE
        else -> Disposition.UNKNOWN
    }
}
