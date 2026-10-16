package com.callagent.gateway.bridge

import android.telecom.TelecomManager

/**
 * The GSM caller's number as it goes into the SIP From header.
 *
 * Android hands the number over as the network wrote it, which can include
 * spaces, dashes or brackets that a SIP URI user part cannot carry. A caller
 * with no number becomes "anonymous" rather than an empty user part, which
 * leaves the server with no number at all.
 */
internal object CallerId {
    const val ANONYMOUS = "anonymous"

    /** Digits, a leading + and the * # keys; null when no number is left. */
    fun sipUser(number: String?): String? {
        val raw = number?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("unknown", ignoreCase = true)) return null
        val cleaned = buildString {
            for (c in raw) {
                when {
                    c in '0'..'9' || c == '*' || c == '#' -> append(c)
                    c == '+' && isEmpty() -> append(c)
                }
            }
        }
        return cleaned.takeIf { value -> value.count { it in '0'..'9' } >= 2 }
    }

    fun displayName(sipUser: String): String = if (sipUser == ANONYMOUS) "Anonymous" else sipUser

    /**
     * For the gateway's own log: the number exactly as Android gave it, what
     * goes into the From header, and the network's presentation flag.
     */
    fun describe(number: String?, presentation: Int?): String {
        val sent = sipUser(number) ?: ANONYMOUS
        return "from \"${number.orEmpty()}\", sent as $sent, presentation ${presentationName(presentation)}"
    }

    private fun presentationName(presentation: Int?): String = when (presentation) {
        TelecomManager.PRESENTATION_ALLOWED -> "allowed"
        TelecomManager.PRESENTATION_RESTRICTED -> "restricted"
        TelecomManager.PRESENTATION_UNKNOWN -> "unknown"
        TelecomManager.PRESENTATION_PAYPHONE -> "payphone"
        null -> "missing"
        else -> presentation.toString()
    }
}
