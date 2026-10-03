package com.callagent.gateway.rtp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SrtpProtocolTest {
    @Test
    fun derivesRfc3711SessionEncryptionKey() {
        val masterKey = hex("e1f97a0d3e018be0d64fa32c06de4139")
        val masterSalt = hex("0ec675ad498afeebb6960b3aabe6")

        val sessionKey = SrtpKdf.derive(
            masterKey, masterSalt, SrtpKdf.LABEL_RTP_ENCR, 16
        )

        assertArrayEquals(hex("c61e7a93744f39ee10734afe3ff7a087"), sessionKey)
    }

    @Test
    fun authenticatesBeforeAcceptingRolloverAndRejectsReplay() {
        val keys = keys()
        val sender = SrtpContext(keys)
        val receiver = SrtpContext(keys)
        val received = mutableListOf<Int>()
        var lastProtected: ByteArray? = null

        for (sequence in listOf(65534, 65535, 0, 1)) {
            val plaintext = RtpPacket(8, sequence, sequence.toLong(), 1234, byteArrayOf(1, 2, 3)).encode()
            val protected = sender.protect(plaintext)!!
            assertArrayEquals(plaintext, receiver.unprotect(protected))
            received += sequence
            lastProtected = protected
        }
        assertEquals(listOf(65534, 65535, 0, 1), received)
        assertNull(receiver.unprotect(lastProtected!!))

        val rolloverSender = SrtpContext(keys)
        val rolloverReceiver = SrtpContext(keys)
        for (sequence in listOf(65534, 65535, 0)) {
            val packet = RtpPacket(8, sequence, sequence.toLong(), 4321, byteArrayOf(4)).encode()
            assertArrayEquals(packet, rolloverReceiver.unprotect(rolloverSender.protect(packet)!!))
        }
        val nextPlain = RtpPacket(8, 1, 1, 4321, byteArrayOf(5)).encode()
        val nextProtected = rolloverSender.protect(nextPlain)!!

        // A forged high sequence number must not move the receive ROC/window
        // before its tag is checked, or the valid rollover packet is lost.
        val forgedHeader = nextProtected.copyOf().also {
            it[2] = 0x80.toByte()
            it[3] = 0x00
        }
        assertNull(rolloverReceiver.unprotect(forgedHeader))
        assertArrayEquals(nextPlain, rolloverReceiver.unprotect(nextProtected))

        val tagSender = SrtpContext(keys)
        val tagReceiver = SrtpContext(keys)
        val tagPlain = RtpPacket(8, 1, 1, 9876, byteArrayOf(6)).encode()
        val tagProtected = tagSender.protect(tagPlain)!!
        val tamperedTag = tagProtected.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertNull(tagReceiver.unprotect(tamperedTag))
        assertArrayEquals(tagPlain, tagReceiver.unprotect(tagProtected))
    }

    @Test
    fun leavesCsrcAndExtensionHeaderClearAndDecryptsAfterFullHeader() {
        val keys = keys()
        val plaintext = rtpWithOneExtension()
        val sender = SrtpContext(keys)
        val receiver = SrtpContext(keys)

        val protected = sender.protect(plaintext)!!

        assertArrayEquals(plaintext.copyOfRange(0, 20), protected.copyOfRange(0, 20))
        assertArrayEquals(plaintext, receiver.unprotect(protected))
        assertEquals(20, RtpPacket.headerLength(plaintext))
        assertArrayEquals(byteArrayOf(7, 8, 9), RtpPacket.decode(plaintext)?.payload)
    }

    @Test
    fun sdesInlineRequiresExactlyOneSupportedKeyAndSalt() {
        val key = keys()
        val inline = key.toInline()
        assertArrayEquals(key.masterKey, SrtpKeys.fromInline(key.suite, inline)?.masterKey)
        assertNull(SrtpKeys.fromInline(key.suite, "$inline|2^20"))
        val tooLong = Base64.getEncoder().encodeToString(key.masterKey + key.masterSalt + byteArrayOf(0))
        assertNull(SrtpKeys.fromInline(key.suite, tooLong))
    }

    private fun rtpWithOneExtension(): ByteArray {
        val out = ByteBuffer.allocate(23).order(ByteOrder.BIG_ENDIAN)
        out.put(0x90.toByte()) // V=2, X=1, CC=0
        out.put(8.toByte())
        out.putShort(1)
        out.putInt(1000)
        out.putInt(1234)
        out.putShort(0xBEDE.toShort())
        out.putShort(1) // one 32-bit extension word
        out.put(byteArrayOf(0, 1, 2, 3))
        out.put(byteArrayOf(7, 8, 9))
        return out.array()
    }

    private fun keys() = SrtpKeys(
        SrtpCryptoSuite.AES_CM_128_HMAC_SHA1_80,
        hex("00112233445566778899aabbccddeeff"),
        hex("0102030405060708090a0b0c0d0e")
    )

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
