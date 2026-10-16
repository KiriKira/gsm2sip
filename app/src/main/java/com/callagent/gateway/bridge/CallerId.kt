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

    /** For the gateway's own log: whether a number came in, never the number itself. */
    fun describe(number: String?, presentation: Int?): String = when {
        sipUser(number) != null -> "caller number shown"
        presentation == TelecomManager.PRESENTATION_RESTRICTED -> "caller withheld the number"
        presentation == TelecomManager.PRESENTATION_PAYPHONE -> "payphone, no number"
        else -> "the network sent no caller number"
    }
}
