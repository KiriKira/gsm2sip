package com.callagent.gateway.sms

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Deterministic identity for a redelivered Android SMS broadcast. The SIM
 * scope keeps identical transport PDUs received on two subscriptions distinct. */
object SmsIdentity {
    fun fromPdus(
        pdus: List<ByteArray>,
        gatewayIdentity: String,
        subscriptionId: Int,
        format: String
    ): String? {
        if (pdus.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
            digest.update(value)
        }
        field("gsm2sip.sms.pdu.v1".toByteArray(Charsets.UTF_8))
        field(gatewayIdentity.toByteArray(Charsets.UTF_8))
        field(subscriptionId.toString().toByteArray(Charsets.UTF_8))
        field(format.toByteArray(Charsets.UTF_8))
        pdus.forEach(::field)
        return UUID.nameUUIDFromBytes(digest.digest()).toString()
    }
}
