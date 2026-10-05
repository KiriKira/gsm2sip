package com.callagent.gateway.sip

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap

/** SIP Digest authentication with RFC 2617/7616 qop=auth support. */
object SipAuth {
    private val secureRandom = SecureRandom()

    /** Parse one unambiguous Digest challenge from a 401 or 407 response. */
    fun parseChallenge(msg: SipMessage): AuthParams? {
        val proxy = msg.statusCode == 407
        val headerName = if (proxy) "proxy-authenticate" else "www-authenticate"
        val candidates = msg.headerValues(headerName)
            .filter { it.trimStart().startsWith("Digest ", ignoreCase = true) }
        if (candidates.size != 1) return null

        val challenge = candidates.single().trim()
        val params = parseParameters(challenge.substringAfter(' ')) ?: return null
        val realm = params["realm"]?.takeIf { it.isNotEmpty() } ?: return null
        val nonce = params["nonce"]?.takeIf { it.isNotEmpty() } ?: return null
        val algorithm = normalizeAlgorithm(params["algorithm"] ?: "MD5") ?: return null
        val qop = params["qop"]
        if (qop != null && !qopOptions(qop).contains("auth")) return null

        return AuthParams(
            realm = realm,
            nonce = nonce,
            opaque = params["opaque"],
            qop = qop,
            algorithm = algorithm,
            isProxy = proxy,
            stale = params["stale"].equals("true", ignoreCase = true)
        )
    }

    /** Build a Digest header. Call [DigestState.authorization] for nonce-count lifecycle. */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams,
        nonceCount: Long = 1,
        cnonce: String? = null
    ): String {
        require(method.isNotBlank() && !method.contains('\r') && !method.contains('\n'))
        require(uri.isNotBlank() && !uri.contains('\r') && !uri.contains('\n'))
        require(nonceCount in 1..0xFFFF_FFFFL)

        val algorithm = normalizeAlgorithm(params.algorithm)
            ?: throw IllegalArgumentException("Unsupported Digest algorithm")
        val ha1 = digest(algorithm, "$username:${params.realm}:$password")
        val ha2 = digest(algorithm, "$method:$uri")
        val qop = params.qop?.let { offered ->
            if (!qopOptions(offered).contains("auth")) {
                throw IllegalArgumentException("Digest challenge does not offer qop=auth")
            }
            "auth"
        }
        val clientNonce = if (qop != null) cnonce?.takeIf { it.isNotBlank() } ?: randomHex(16) else null
        val nonceCountText = if (qop != null) "%08x".format(nonceCount) else null
        val response = if (qop == null) {
            digest(algorithm, "$ha1:${params.nonce}:$ha2")
        } else {
            digest(algorithm, "$ha1:${params.nonce}:$nonceCountText:$clientNonce:$qop:$ha2")
        }
        val headerName = if (params.isProxy) "Proxy-Authorization" else "Authorization"

        return buildString {
            append("$headerName: Digest username=\"${quoted(username)}\", ")
            append("realm=\"${quoted(params.realm)}\", ")
            append("nonce=\"${quoted(params.nonce)}\", ")
            append("uri=\"${quoted(uri)}\", ")
            append("response=\"$response\", ")
            append("algorithm=$algorithm")
            if (qop != null) {
                append(", qop=$qop, nc=$nonceCountText, cnonce=\"${quoted(clientNonce!!)}\"")
            }
            if (params.opaque != null) append(", opaque=\"${quoted(params.opaque)}\"")
            append("\r\n")
        }
    }

    /** Build Authorization header for INVITE. */
    fun buildInviteAuthHeader(
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String = buildAuthHeader("INVITE", uri, username, password, params)

    /** Per-client qop nonce counts; bounded so expired server nonces do not accumulate. */
    class DigestState {
        private val counts = LinkedHashMap<String, Long>(16, 0.75f, true)

        @Synchronized
        fun authorization(
            method: String,
            uri: String,
            username: String,
            password: String,
            params: AuthParams
        ): String {
            val qopEnabled = params.qop != null
            val key = "${params.isProxy}\u0000${params.realm}\u0000${params.nonce}\u0000${params.algorithm}"
            val count = if (qopEnabled) {
                if (params.stale) counts.remove(key)
                ((counts[key] ?: 0L) + 1L).also {
                    require(it <= 0xFFFF_FFFFL) { "Digest nonce count exhausted" }
                    counts[key] = it
                    while (counts.size > MAX_TRACKED_NONCES) {
                        counts.remove(counts.entries.iterator().next().key)
                    }
                }
            } else 1L
            return buildAuthHeader(method, uri, username, password, params, count)
        }
    }

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val qop: String?,
        val algorithm: String = "MD5",
        val isProxy: Boolean = false,
        val stale: Boolean = false
    )

    private fun parseParameters(input: String): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        var i = 0
        while (i < input.length) {
            while (i < input.length && (input[i].isWhitespace() || input[i] == ',')) i++
            if (i >= input.length) break
            val keyStart = i
            while (i < input.length && input[i] != '=' && input[i] != ',') i++
            if (i >= input.length || input[i] != '=') return null
            val key = input.substring(keyStart, i).trim().lowercase()
            if (key.isEmpty()) return null
            i++
            while (i < input.length && input[i].isWhitespace()) i++

            val value = if (i < input.length && input[i] == '"') {
                i++
                val parsed = StringBuilder()
                var closed = false
                while (i < input.length) {
                    val c = input[i++]
                    when {
                        c == '\\' && i < input.length -> parsed.append(input[i++])
                        c == '"' -> { closed = true; break }
                        c == '\r' || c == '\n' -> return null
                        else -> parsed.append(c)
                    }
                }
                if (!closed) return null
                parsed.toString()
            } else {
                val start = i
                while (i < input.length && input[i] != ',') {
                    if (input[i] == '\r' || input[i] == '\n') return null
                    i++
                }
                input.substring(start, i).trim()
            }
            if (result.put(key, value) != null) return null
            while (i < input.length && input[i].isWhitespace()) i++
            if (i < input.length && input[i] != ',') return null
            if (i < input.length) i++
        }
        return result
    }

    private fun qopOptions(value: String): Set<String> =
        value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun normalizeAlgorithm(value: String): String? = when (value.trim().lowercase()) {
        "md5" -> "MD5"
        "sha-256" -> "SHA-256"
        else -> null
    }

    private fun digest(algorithm: String, value: String): String {
        val jcaName = if (algorithm == "SHA-256") "SHA-256" else "MD5"
        return MessageDigest.getInstance(jcaName)
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun quoted(value: String): String {
        require(!value.contains('\r') && !value.contains('\n')) { "Invalid Digest quoted value" }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun randomHex(bytes: Int): String {
        val value = ByteArray(bytes)
        secureRandom.nextBytes(value)
        return value.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val MAX_TRACKED_NONCES = 32
}
