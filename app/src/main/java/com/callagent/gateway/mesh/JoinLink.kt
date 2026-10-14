package com.callagent.gateway.mesh

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** One server and its one-time pairing code from a join link. */
data class JoinEntry(val serverUrl: String, val pairingCode: String)

/**
 * `gsm2sip://join?v=1&role=client&n=<server>%23<code>&n=...` — pairing codes
 * for several server nodes in one link, so one QR scan (the phone's camera app
 * opens the link) joins every node. The first entry is the primary when the
 * phone is not paired yet. Codes are one-time and short-lived; a link is a
 * secret until they are used or expire.
 */
object JoinLink {
    const val SCHEME = "gsm2sip"
    const val HOST = "join"
    private const val MAX_ENTRIES = 8
    private const val MAX_CODE_LENGTH = 128

    fun isJoinLink(link: String?): Boolean =
        link != null && link.startsWith("$SCHEME://$HOST", ignoreCase = true)

    /** The entries for [expectedRole], or null when the link is malformed or for the other role. */
    fun parse(link: String, expectedRole: String): List<JoinEntry>? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, true) || !uri.host.equals(HOST, true)) return null
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            runCatching { name to URLDecoder.decode(value, "UTF-8") }.getOrNull()
        }
        if (params.firstOrNull { it.first == "v" }?.second != "1") return null
        if (params.firstOrNull { it.first == "role" }?.second != expectedRole) return null
        val entries = params.filter { it.first == "n" }.map { (_, value) ->
            val server = value.substringBeforeLast('#').trim()
            val code = value.substringAfterLast('#', "").trim()
            if (code.isEmpty() || code.length > MAX_CODE_LENGTH || code.any(Char::isWhitespace)) return null
            val serverUri = runCatching { URI(server) }.getOrNull() ?: return null
            val scheme = serverUri.scheme?.lowercase()
            if ((scheme != "https" && scheme != "http") || serverUri.host.isNullOrBlank() ||
                serverUri.userInfo != null || serverUri.rawQuery != null) return null
            JoinEntry(server.trimEnd('/'), code)
        }
        if (entries.isEmpty() || entries.size > MAX_ENTRIES) return null
        if (entries.map { it.serverUrl.lowercase() }.toSet().size != entries.size) return null
        return entries
    }

    fun build(role: String, entries: List<JoinEntry>): String =
        "$SCHEME://$HOST?v=1&role=$role" + entries.joinToString("") { entry ->
            "&n=" + URLEncoder.encode("${entry.serverUrl}#${entry.pairingCode}", "UTF-8")
        }
}
