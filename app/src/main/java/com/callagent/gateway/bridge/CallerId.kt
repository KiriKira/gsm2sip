package com.callagent.gateway.bridge

import android.telecom.TelecomManager

/**
 * The GSM caller's number as the gateway log describes it.
 *
 * Android hands the number over as the network wrote it, which can include
 * spaces, dashes or brackets. A caller with no number is shown as
 * "anonymous" rather than an empty string.
 */
internal object CallerId {
    const val ANONYMOUS = "anonymous"

    /** Digits, a leading + and the * # keys; null when no number is left. */
    fun normalized(number: String?): String? {
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

    fun displayName(number: String): String = if (number == ANONYMOUS) "Anonymous" else number

    /**
     * For the gateway's own log: the number exactly as Android gave it, its
     * normalized form, and the network's presentation flag.
     */
    fun describe(number: String?, presentation: Int?): String {
        val sent = normalized(number) ?: ANONYMOUS
        return "from \"${number.orEmpty()}\", normalized $sent, presentation ${presentationName(presentation)}"
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
