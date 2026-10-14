package com.callagent.gateway.sip

import org.json.JSONObject

/**
 * Transport fields of a server-issued SIP configuration (GET sip config and the
 * rotate response). Older servers send neither `transport` nor
 * `media_encryption` and only ever offer `sips:` on 5061, so both default to
 * TLS with SDES. A self-hosted node on a LAN or tailnet may instead hand out
 * `sip:` URIs for plain UDP and unencrypted RTP.
 */
data class ProvisionedSipTransport(val tls: Boolean, val srtp: Boolean, val port: Int) {
    companion object {
        const val TLS_PORT = 5061
        const val UDP_PORT = 5060

        fun from(config: JSONObject): ProvisionedSipTransport {
            val tls = when (val transport = config.optString("transport", "tls").ifEmpty { "tls" }.lowercase()) {
                "tls" -> true
                "udp" -> false
                else -> throw IllegalArgumentException("Unsupported SIP transport: $transport")
            }
            val srtp = when (val media = config.optString("media_encryption", "sdes").ifEmpty { "sdes" }.lowercase()) {
                "sdes" -> true
                "none" -> false
                else -> throw IllegalArgumentException("Unsupported media encryption: $media")
            }
            // SDES keys travel in the SDP, so they are only offered over TLS.
            return ProvisionedSipTransport(tls, srtp && tls, registrarPort(config.getString("registrar_uri"), tls))
        }

        /** Port of a `sip:` or `sips:` registrar URI, ignoring user part and `;params`. */
        fun registrarPort(uri: String, tls: Boolean): Int {
            val default = if (tls) TLS_PORT else UDP_PORT
            val value = uri.trim()
            val rest = when {
                value.startsWith("sips:", ignoreCase = true) -> value.substring(5)
                value.startsWith("sip:", ignoreCase = true) -> value.substring(4)
                else -> throw IllegalArgumentException("Registrar URI must use sip: or sips:")
            }
            val hostPort = rest.substringBefore(';').substringBefore('?').substringAfterLast('@')
            require(hostPort.isNotBlank()) { "Registrar URI has no host" }
            val portText = when {
                hostPort.startsWith("[") -> hostPort.substringAfter(']', "").removePrefix(":")
                hostPort.count { it == ':' } == 1 -> hostPort.substringAfter(':')
                else -> ""
            }
            if (portText.isEmpty()) return default
            val port = portText.toIntOrNull()
            require(port != null && port in 1..65535) { "Registrar URI has an invalid port" }
            return port
        }
    }
}
