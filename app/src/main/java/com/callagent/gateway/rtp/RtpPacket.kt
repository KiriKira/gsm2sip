package com.callagent.gateway.rtp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RTP packet structure (RFC 3550).
 * Handles packing/unpacking of RTP headers.
 */
class RtpPacket(
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray,
    val marker: Boolean = false
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(12 + payload.size)
        buf.order(ByteOrder.BIG_ENDIAN)

        // Byte 0: V=2, P=0, X=0, CC=0
        buf.put(0x80.toByte())
        // Byte 1: M + PT
        buf.put(((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte())
        // Bytes 2-3: sequence number
        buf.putShort(sequenceNumber.toShort())
        // Bytes 4-7: timestamp
        buf.putInt(timestamp.toInt())
        // Bytes 8-11: SSRC
        buf.putInt(ssrc.toInt())
        // Payload
        buf.put(payload)

        return buf.array()
    }

    companion object {
        /** Parse raw UDP packet into RtpPacket */
        fun decode(data: ByteArray, length: Int = data.size): RtpPacket? {
            if (length < RTP_HEADER_LEN || length > data.size) return null

            val buf = ByteBuffer.wrap(data, 0, length)
            buf.order(ByteOrder.BIG_ENDIAN)

            val b0 = buf.get().toInt() and 0xFF
            val version = (b0 shr 6) and 0x03
            if (version != 2) return null
            val csrcCount = b0 and 0x0F

            val b1 = buf.get().toInt() and 0xFF
            val marker = (b1 and 0x80) != 0
            val pt = b1 and 0x7F

            val seq = buf.short.toInt() and 0xFFFF
            val ts = buf.int.toLong() and 0xFFFFFFFFL
            val ssrc = buf.int.toLong() and 0xFFFFFFFFL

            val headerSize = headerLength(data, length) ?: return null
            val padding = (b0 and 0x20) != 0
            var payloadEnd = length
            if (padding) {
                val paddingBytes = data[length - 1].toInt() and 0xFF
                if (paddingBytes == 0 || paddingBytes > length - headerSize) return null
                payloadEnd -= paddingBytes
            }
            if (payloadEnd < headerSize) return null

            val payload = ByteArray(payloadEnd - headerSize)
            System.arraycopy(data, headerSize, payload, 0, payload.size)

            return RtpPacket(pt, seq, ts, ssrc, payload, marker)
        }

        /** Return the full fixed/CSRC/extension header length, or null if malformed. */
        fun headerLength(data: ByteArray, length: Int = data.size): Int? {
            if (length < RTP_HEADER_LEN || length > data.size) return null
            val first = data[0].toInt() and 0xFF
            if ((first ushr 6) != 2) return null
            val csrcCount = first and 0x0F
            var headerSize = RTP_HEADER_LEN + csrcCount * 4
            if (length < headerSize) return null
            if ((first and 0x10) != 0) {
                if (length < headerSize + 4) return null
                val extensionWords = (((data[headerSize + 2].toInt() and 0xFF) shl 8) or
                    (data[headerSize + 3].toInt() and 0xFF))
                val extensionBytes = extensionWords.toLong() * 4L
                if (extensionBytes > length - headerSize - 4L) return null
                headerSize += 4 + extensionBytes.toInt()
            }
            return headerSize
        }

        // Payload types
        const val PT_PCMU = 0
        const val PT_G722 = 9
        const val PT_PCMA = 8
        const val RTP_HEADER_LEN = 12
    }
}
