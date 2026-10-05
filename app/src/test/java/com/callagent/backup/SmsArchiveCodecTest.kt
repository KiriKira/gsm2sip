package com.callagent.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmsArchiveCodecTest {
    private val record = SmsArchiveRecord(
        id = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        source = "https://sms.example.test/archive",
        ownerId = "owner-1",
        gatewayId = "gateway-2",
        messageId = "native-3",
        simId = "sim-4",
        simLabel = "SIM 一号",
        direction = "inbound",
        from = "+12025550123",
        to = null,
        body = "Line one\r\n\tLine two & <tag> 'single' \"quoted\" — 中文 🌙",
        createdAt = 1_735_689_600_123L,
        status = "received",
        observedAt = 1_735_689_601_234L,
        slotIndex = 0,
        subscriptionId = 2,
        partCount = 1,
    )

    @Test
    fun jsonRoundTripPreservesUnicodeMetadataAndRecordId() {
        assertEquals(record, roundTrip(SmsArchiveCodec.Format.JSON, password = null, record = record))
    }

    @Test
    fun encryptedRoundTripAndAuthenticationFailuresAreReported() {
        val password = "correct horse battery".toCharArray()
        val bytes = ByteArrayOutputStream()
        assertEquals(1L, SmsArchiveCodec.write(bytes, SmsArchiveCodec.Format.ENCRYPTED, password, sequenceOf(record), exportedAt = 1_700_000_000_000L))
        assertTrue(SmsArchiveCodec.isEncrypted(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(record, readAll(ByteArrayInputStream(bytes.toByteArray()), password).single())

        expectArchiveFailure { readAll(ByteArrayInputStream(bytes.toByteArray()), "incorrect password".toCharArray()) }
        val damaged = bytes.toByteArray().also { it[it.lastIndex] = (it.last().toInt() xor 0x40).toByte() }
        expectArchiveFailure { readAll(ByteArrayInputStream(damaged), password) }
        expectArchiveFailure { readAll(ByteArrayInputStream(bytes.toByteArray().copyOf(bytes.size() - 5)), password) }
    }

    @Test
    fun encryptedCallbackFailurePreservesCauseAndStopsFurtherCallbacks() {
        val password = "callback test password".toCharArray()
        val second = record.copy(
            id = "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            body = "long sms body ".repeat(2_000),
            createdAt = record.createdAt + 1,
            observedAt = record.observedAt + 1,
        )
        val bytes = ByteArrayOutputStream().also {
            SmsArchiveCodec.write(it, SmsArchiveCodec.Format.ENCRYPTED, password, sequenceOf(record, second))
        }.toByteArray()
        val input = ByteArrayInputStream(bytes)
        val callbackFailure = IllegalStateException("stop import")
        var thrown: Throwable? = null
        var callbacks = 0
        try {
            SmsArchiveCodec.read(input, password) { callbacks++; throw callbackFailure }
        } catch (error: Throwable) {
            thrown = error
        }
        assertSame(callbackFailure, thrown)
        assertEquals(1, callbacks)
        // Some GCM providers authenticate and buffer ciphertext before the first callback.
    }

    @Test
    fun xmlRoundTripPreservesUnicodeEntitiesAndNewlines() {
        val output = ByteArrayOutputStream()
        assertEquals(1L, SmsArchiveCodec.write(output, SmsArchiveCodec.Format.XML, null, sequenceOf(record), exportedAt = 1_700_000_000_000L))
        val xml = output.toString("UTF-8")
        assertTrue(xml.contains("<smses count=\"1\""))
        assertEquals(1, Regex("<sms\\b").findAll(xml).count())
        assertEquals(record, readAll(ByteArrayInputStream(output.toByteArray()), null).single())
    }

    @Test
    fun xmlRejectsNulWhileJsonRetainsIt() {
        val withNul = record.copy(body = "before\u0000after")
        assertEquals(withNul, roundTrip(SmsArchiveCodec.Format.JSON, password = null, record = withNul))
        expectIllegalArgument {
            SmsArchiveCodec.write(ByteArrayOutputStream(), SmsArchiveCodec.Format.XML, null, sequenceOf(withNul))
        }
    }

    @Test
    fun unsupportedVersionOversizedBodiesDtdAndMmsAreRejected() {
        val json = ByteArrayOutputStream().also {
            SmsArchiveCodec.write(it, SmsArchiveCodec.Format.JSON, null, sequenceOf(record), exportedAt = 1_700_000_000_000L)
        }.toString("UTF-8").replace("\"version\": 1", "\"version\": 2")
        expectArchiveFailure { readAll(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), null) }

        val multipartBody = record.copy(body = "é".repeat(9_000))
        assertEquals(multipartBody, roundTrip(SmsArchiveCodec.Format.JSON, null, multipartBody))
        val tooLong = record.copy(body = "é".repeat(524_289))
        expectIllegalArgument { SmsArchiveCodec.write(ByteArrayOutputStream(), SmsArchiveCodec.Format.JSON, null, sequenceOf(tooLong)) }

        val dtd = """<!DOCTYPE smses [<!ENTITY x SYSTEM "file:///etc/passwd">]><smses><sms date="1" type="1" address="x" body="&x;"/></smses>"""
        expectArchiveFailure { readAll(ByteArrayInputStream(dtd.toByteArray(Charsets.UTF_8)), null) }
        val mms = "<smses><mms address=\"x\"/></smses>"
        expectArchiveFailure { readAll(ByteArrayInputStream(mms.toByteArray(Charsets.UTF_8)), null) }
    }

    @Test
    fun identicalExternalSmsKeepDistinctDeterministicOccurrenceIds() {
        val xml = """<smses count="2"><sms address="+12025550123" date="1735689600123" type="1" body="same &amp; exact"/><sms address="+12025550123" date="1735689600123" type="1" body="same &amp; exact"/></smses>"""
        val first = readAll(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), null)
        val second = readAll(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), null)
        assertEquals(2, first.size)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertNotEquals(first[0].id, first[1].id)
        assertTrue(first.all { it.body == "same & exact" })
    }

    @Test
    fun malformedCountsAndDuplicateJsonKeysAreRejected() {
        val badCount = "<smses count=\"2\"><sms address=\"x\" date=\"1\" type=\"1\" body=\"a\"/></smses>"
        expectArchiveFailure { readAll(ByteArrayInputStream(badCount.toByteArray(Charsets.UTF_8)), null) }
        val duplicate = """{"format":"gsm2sip-sms-archive","version":1,"exported_at":1,"messages":[],"version":1}"""
        expectArchiveFailure { readAll(ByteArrayInputStream(duplicate.toByteArray(Charsets.UTF_8)), null) }

        val invalidUtf8 = """{"format":"gsm2sip-sms-archive","version":1,"exported_at":1,"messages":[{"id":"${record.id}","source":"source","direction":"inbound","body":"X","created_at":1,"status":"unknown","observed_at":1}]}"""
            .toByteArray(Charsets.UTF_8)
        invalidUtf8[invalidUtf8.indexOf('X'.code.toByte())] = 0xff.toByte()
        expectArchiveFailure { readAll(ByteArrayInputStream(invalidUtf8), null) }
    }

    private fun roundTrip(format: SmsArchiveCodec.Format, password: CharArray?, record: SmsArchiveRecord): SmsArchiveRecord {
        val output = ByteArrayOutputStream()
        assertEquals(1L, SmsArchiveCodec.write(output, format, password, sequenceOf(record), exportedAt = 1_700_000_000_000L))
        return readAll(ByteArrayInputStream(output.toByteArray()), password).single()
    }

    private fun readAll(input: ByteArrayInputStream, password: CharArray?): List<SmsArchiveRecord> {
        val result = mutableListOf<SmsArchiveRecord>()
        val count = SmsArchiveCodec.read(input, password) { result += it }
        assertEquals(result.size.toLong(), count)
        return result
    }

    private fun expectArchiveFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected SmsArchiveException")
        } catch (_: SmsArchiveException) {
            // Expected.
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
