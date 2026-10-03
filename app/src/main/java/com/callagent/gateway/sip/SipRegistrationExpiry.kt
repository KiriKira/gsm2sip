package com.callagent.gateway.sip

/** Resolves the effective registration lifetime from a SIP 200 REGISTER response. */
object SipRegistrationExpiry {
    private val contactExpiry = Regex("(?i)(?:^|;)\\s*expires\\s*=\\s*([^;,\\s]+)")

    /** Contact expires is per binding and takes precedence over Expires. */
    fun grantedSeconds(message: SipMessage, requestedSeconds: Long): Long? {
        if (requestedSeconds <= 0) return null
        val expiresHeader = uniqueNumericHeader(message.headerValues("expires")) ?: run {
            if (message.headerValues("expires").isNotEmpty()) return null
            null
        }

        val contactValues = message.headerValues("contact")
        val parsedContactValues = mutableListOf<Long>()
        for (contact in contactValues) {
            for (match in contactExpiry.findAll(contact)) {
                val value = match.groupValues[1]
                if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
                parsedContactValues.add(value.toLongOrNull() ?: return null)
            }
            if (contact.contains("expires", ignoreCase = true) &&
                contactExpiry.find(contact) == null
            ) return null
        }
        if (parsedContactValues.distinct().size > 1) return null
        val effective = parsedContactValues.firstOrNull() ?: expiresHeader ?: requestedSeconds
        return effective.takeIf { it > 0 }
    }

    /** Refresh at half-life, leaving time for registration retry before expiry. */
    fun refreshAfterMillis(grantedSeconds: Long): Long? {
        if (grantedSeconds <= 0) return null
        return (grantedSeconds.coerceAtMost(Long.MAX_VALUE / 500L) * 500L).coerceAtLeast(1_000L)
    }

    private fun uniqueNumericHeader(values: List<String>): Long? {
        if (values.isEmpty()) return null
        val parsed = values.map { value ->
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.any { it !in '0'..'9' }) return null
            trimmed.toLongOrNull() ?: return null
        }
        return parsed.distinct().singleOrNull()
    }
}
