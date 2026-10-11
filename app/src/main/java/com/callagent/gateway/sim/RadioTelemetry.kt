package com.callagent.gateway.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * Per-SIM radio observation shown on paired hosts. It is display telemetry
 * only: it never resolves, verifies or routes a SIM, and it deliberately
 * carries no ICCID, IMSI, cell identity or location.
 */
data class RadioSnapshot(
    val signalLevel: Int?,
    val networkType: String?,
    val networkOperator: String?,
    val roaming: Boolean?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("signal_level", signalLevel ?: JSONObject.NULL)
        .put("network_type", networkType ?: JSONObject.NULL)
        .put("network_operator", networkOperator ?: JSONObject.NULL)
        .put("roaming", roaming ?: JSONObject.NULL)
}

object RadioTelemetry {
    /** Heartbeat extension name the server advertises when it accepts [RadioSnapshot]. */
    const val EXTENSION = "sim_radio"

    private val networkTypePattern = Regex("^[A-Za-z0-9_+ -]{1,16}$")

    /** Reads what Android exposes without extra permissions beyond READ_PHONE_STATE. */
    fun read(context: Context, subscriptionId: Int): RadioSnapshot? {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return null
        val manager = runCatching { base.createForSubscriptionId(subscriptionId) }.getOrNull() ?: return null
        val phoneState = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager.signalStrength?.level }.getOrNull()
        } else {
            null
        }
        val type = if (phoneState) {
            runCatching {
                manager.voiceNetworkType.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                    ?: manager.dataNetworkType
            }.getOrNull()
        } else {
            null
        }
        return snapshot(
            signalLevel = level,
            networkType = type,
            networkOperator = runCatching { manager.networkOperatorName }.getOrNull(),
            roaming = runCatching { manager.isNetworkRoaming }.getOrNull()
        )
    }

    /** Normalizes raw Android values into the bounded wire form the server accepts. */
    fun snapshot(signalLevel: Int?, networkType: Int?, networkOperator: String?, roaming: Boolean?): RadioSnapshot =
        RadioSnapshot(
            signalLevel = signalLevel?.takeIf { it in 0..4 },
            networkType = networkType?.let(::networkTypeName)?.takeIf { networkTypePattern.matches(it) },
            networkOperator = sanitizeOperator(networkOperator),
            roaming = roaming
        )

    fun sanitizeOperator(value: String?): String? {
        val cleaned = value?.filterNot { Character.isISOControl(it) }?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val end = cleaned.offsetByCodePoints(0, minOf(64, cleaned.codePointCount(0, cleaned.length)))
        return cleaned.substring(0, end).trim().ifEmpty { null }
    }

    fun networkTypeName(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EVDO"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        else -> null
    }
}
