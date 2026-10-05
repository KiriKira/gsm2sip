package com.callagent.backup

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.SequenceInputStream
import java.io.StringWriter
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Invalid, unsupported, or damaged SMS archive data. Messages never include archive contents. */
class SmsArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Streaming codecs for the versioned JSON archive, password-encrypted archive, and SMS Backup XML. */
object SmsArchiveCodec {
    enum class Format { ENCRYPTED, JSON, XML }

    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val MAX_RECORDS = 100_000
    private const val MAX_BODY_BYTES = 1_048_576
    private const val MAX_METADATA_BYTES = 2_048
    private const val PBKDF2_ITERATIONS = 310_000
    private const val MAX_PASSWORD_CHARS = 1_024
    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_ITERATION_BYTES = 4
    private const val FORMAT_NAME = "gsm2sip-sms-archive"
    private const val FORMAT_VERSION = 1L
    private const val XML_EXTERNAL_SOURCE = "sms-backup-restore+xml"
    private val MAGIC = "GSM2SMS1\n".toByteArray(StandardCharsets.US_ASCII)
    private val ID_PATTERN = Regex("[0-9a-f]{64}")
    private val random = SecureRandom()

    /**
     * Stable metadata identity: SHA-256(source NUL ownerId NUL gatewayId NUL nativeId).
     * These values are metadata and therefore cannot contain NUL.
     */
    fun stableId(source: String, ownerId: String?, gatewayId: String?, nativeId: String): String {
        validateMetadata("source", source, required = true)
        validateMetadata("ownerId", ownerId)
        validateMetadata("gatewayId", gatewayId)
        validateMetadata("nativeId", nativeId, required = true)
        val digest = MessageDigest.getInstance("SHA-256")
        val values = arrayOf(source, ownerId.orEmpty(), gatewayId.orEmpty(), nativeId)
        for (index in values.indices) {
            if (index > 0) digest.update(0.toByte())
            digest.update(strictUtf8(values[index], "identity metadata"))
        }
        return digest.digest().toLowerHex()
    }

    /**
     * Writes one archive and returns its record count. ENCRYPTED always wraps the JSON format.
     * The input sequence is consumed once; output is capped at 64 MiB (and encrypted plaintext
     * is independently capped at 64 MiB). The caller retains ownership of [output] and [password].
     */
    @JvmOverloads
    fun write(
        output: OutputStream,
        format: Format,
        password: CharArray? = null,
        records: Sequence<SmsArchiveRecord>,
        exportedAt: Long = System.currentTimeMillis(),
        scratchDirectory: File? = null,
    ): Long {
        if (exportedAt < 0L) throw IllegalArgumentException("exportedAt must be a non-negative epoch timestamp")
        val boundedOutput = LimitOutputStream(output, MAX_ARCHIVE_BYTES, "Archive output exceeds 64 MiB")
        return when (format) {
            Format.JSON -> writeJson(boundedOutput, records, exportedAt)
            Format.XML -> writeXml(boundedOutput, records, exportedAt, scratchDirectory)
            Format.ENCRYPTED -> {
                val secret = password ?: throw IllegalArgumentException("A password is required for encrypted archives")
                validatePassword(secret)
                writeEncrypted(boundedOutput, secret, records, exportedAt)
            }
        }
    }

    /**
     * Reads JSON, XML, or encrypted JSON by its content and returns the number of records.
     * Records are delivered as they are parsed. Import callers must begin a database transaction
     * before calling this method and commit only after it returns successfully; malformed tails,
     * authentication failures, size limits, and interruption must roll that transaction back.
     * The input and password remain owned by the caller. A thread interrupt aborts between records.
     */
    fun read(input: InputStream, password: CharArray? = null, onRecord: (SmsArchiveRecord) -> Unit): Long {
        val boundedInput = LimitInputStream(input, MAX_ARCHIVE_BYTES, "Archive input exceeds 64 MiB")
        val prefix = readAtMost(boundedInput, MAGIC.size)
        if (prefix.contentEquals(MAGIC)) {
            val encryptedPassword = password
                ?: throw SmsArchiveException("A password is required to open this encrypted archive")
            validatePasswordForRead(encryptedPassword)
            return readEncrypted(boundedInput, encryptedPassword, onRecord)
        }
        val replay = SequenceInputStream(ByteArrayInputStream(prefix), boundedInput)
        return try {
            parsePlainArchive(replay) { record -> invokeCallback(onRecord, record) }
        } catch (failure: CallbackFailure) {
            throw failure.original
        }
    }

    /** Reads the magic prefix to identify an encrypted archive. This consumes up to nine bytes. */
    fun isEncrypted(input: InputStream): Boolean = readAtMost(input, MAGIC.size).contentEquals(MAGIC)

    private fun writeEncrypted(
        output: LimitOutputStream,
        password: CharArray,
        records: Sequence<SmsArchiveRecord>,
        exportedAt: Long,
    ): Long {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteArrayOutputStream(MAGIC.size + SALT_BYTES + NONCE_BYTES + HEADER_ITERATION_BYTES).apply {
            write(MAGIC)
            write(salt)
            write(nonce)
            write(ByteBuffer.allocate(4).putInt(PBKDF2_ITERATIONS).array())
        }.toByteArray()
        output.write(header)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, password, salt, nonce, header)
        val encryptedOutput = CipherUpdatingOutputStream(output, cipher)
        val cleartextOutput = LimitOutputStream(encryptedOutput, MAX_ARCHIVE_BYTES, "Archive plaintext exceeds 64 MiB")
        try {
            val count = writeJson(cleartextOutput, records, exportedAt)
            cleartextOutput.flush()
            encryptedOutput.finish()
            output.flush()
            return count
        } catch (error: Throwable) {
            encryptedOutput.abort()
            throw error
        }
    }

    private fun readEncrypted(
        input: InputStream,
        password: CharArray,
        onRecord: (SmsArchiveRecord) -> Unit,
    ): Long {
        val rest = ByteArray(SALT_BYTES + NONCE_BYTES + HEADER_ITERATION_BYTES)
        readFully(input, rest, "Encrypted archive header is truncated")
        val salt = rest.copyOfRange(0, SALT_BYTES)
        val nonce = rest.copyOfRange(SALT_BYTES, SALT_BYTES + NONCE_BYTES)
        val encodedIterations = ByteBuffer.wrap(rest, SALT_BYTES + NONCE_BYTES, HEADER_ITERATION_BYTES).int
        if (encodedIterations != PBKDF2_ITERATIONS) {
            throw SmsArchiveException("Unsupported encrypted archive parameters")
        }
        val header = MAGIC + rest
        val cipher = try {
            newCipher(Cipher.DECRYPT_MODE, password, salt, nonce, header)
        } catch (error: Exception) {
            throw SmsArchiveException("Could not unlock encrypted archive; check the password", error)
        }
        val cipherInput = CipherInputStream(input, cipher)
        val clearInput = LimitInputStream(cipherInput, MAX_ARCHIVE_BYTES, "Archive plaintext exceeds 64 MiB")
        try {
            val count = parsePlainArchive(clearInput, encryptedJsonOnly = true) { record -> invokeCallback(onRecord, record) }
            drainToEof(clearInput)
            return count
        } catch (error: Throwable) {
            if (error is CallbackFailure) throw error.original
            if (error is CancellationException) throw error
            if (error is InterruptedIOException || Thread.currentThread().isInterrupted) throw error
            val authenticationFailure = drainForAuthentication(clearInput)
            if (authenticationFailure != null || error.hasCause<AEADBadTagException>()) {
                throw SmsArchiveException("Encrypted archive authentication failed; check the password or file", error)
            }
            if (error is SmsArchiveException) throw error
            if (error is IOException) throw SmsArchiveException("Encrypted archive is damaged or has an unsupported format", error)
            throw error
        }
    }

    private fun newCipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        val keySpec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        var encodedKey: ByteArray? = null
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
            encodedKey = key
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return cipher
        } finally {
            encodedKey?.fill(0)
            keySpec.clearPassword()
        }
    }

    private fun writeJson(output: OutputStream, records: Sequence<SmsArchiveRecord>, exportedAt: Long): Long {
        val writer = JsonWriter(java.io.OutputStreamWriter(output, StandardCharsets.UTF_8)).apply {
            isLenient = false
            setIndent("  ")
        }
        var count = 0L
        writer.beginObject()
        writer.name("format").value(FORMAT_NAME)
        writer.name("version").value(FORMAT_VERSION)
        writer.name("exported_at").value(exportedAt)
        writer.name("messages").beginArray()
        for (record in records) {
            checkNotInterrupted()
            if (count >= MAX_RECORDS) throw IllegalArgumentException("Archive contains more than $MAX_RECORDS records")
            validateRecord(record, forXml = false)
            writer.beginObject()
            writer.name("id").value(record.id)
            writer.name("source").value(record.source)
            writer.name("owner_id").nullable(record.ownerId)
            writer.name("gateway_id").nullable(record.gatewayId)
            writer.name("message_id").nullable(record.messageId)
            writer.name("sim_id").nullable(record.simId)
            writer.name("sim_label").nullable(record.simLabel)
            writer.name("direction").value(record.direction)
            writer.name("from").nullable(record.from)
            writer.name("to").nullable(record.to)
            writer.name("body").value(record.body)
            writer.name("created_at").value(record.createdAt)
            writer.name("status").value(record.status)
            writer.name("observed_at").value(record.observedAt)
            writer.name("slot_index").nullable(record.slotIndex)
            writer.name("subscription_id").nullable(record.subscriptionId)
            writer.name("part_count").nullable(record.partCount)
            writer.endObject()
            count++
        }
        writer.endArray()
        writer.endObject()
        writer.flush()
        return count
    }

    private fun writeXml(output: OutputStream, records: Sequence<SmsArchiveRecord>, exportedAt: Long, scratchDirectory: File?): Long {
        val directory = scratchDirectory ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        if (!directory.isDirectory || !directory.canWrite()) throw IOException("XML scratch directory is unavailable")
        val serializerFactory = XmlPullParserFactory.newInstance()
        val fragmentsFile = createPrivateTempFile(directory)
        var failure: Throwable? = null
        var count = 0L
        try {
            FileOutputStream(fragmentsFile).use { fileOutput ->
                val fragments = LimitOutputStream(fileOutput, MAX_ARCHIVE_BYTES, "XML fragments exceed 64 MiB")
                for (record in records) {
                    checkNotInterrupted()
                    if (count >= MAX_RECORDS) throw IllegalArgumentException("Archive contains more than $MAX_RECORDS records")
                    validateRecord(record, forXml = true)
                    fragments.write(serializeXmlSms(record, serializerFactory))
                    fragments.write('\n'.code)
                    count++
                }
                fragments.flush()
            }
            val header = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>\n" +
                "<smses count=\"$count\" backup_date=\"$exportedAt\">"
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            FileInputStream(fragmentsFile).use { it.copyTo(output, 8_192) }
            output.write("</smses>".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            return count
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (fragmentsFile.exists() && !fragmentsFile.delete()) {
                val cleanupError = IOException("Could not remove temporary SMS XML data")
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    private fun serializeXmlSms(record: SmsArchiveRecord, factory: XmlPullParserFactory): ByteArray {
        val output = StringWriter()
        val serializer = factory.newSerializer()
        serializer.setOutput(output)
        serializer.startTag(null, "sms")
        val address = if (record.direction == "inbound") record.from else record.to
        attr(serializer, "address", address.orEmpty())
        attr(serializer, "date", record.createdAt.toString())
        attr(serializer, "type", xmlType(record).toString())
        attr(serializer, "body", record.body)
        attr(serializer, "protocol", "0")
        attr(serializer, "read", "1")
        attr(serializer, "status", "-1")
        attr(serializer, "locked", "0")
        attr(serializer, "date_sent", record.observedAt.toString())
        if (record.subscriptionId != null) attr(serializer, "sub_id", record.subscriptionId.toString())

        attr(serializer, "gsm2sip_archive_id", record.id)
        attr(serializer, "gsm2sip_source", record.source)
        nullableAttr(serializer, "gsm2sip_owner_id", record.ownerId)
        nullableAttr(serializer, "gsm2sip_gateway_id", record.gatewayId)
        nullableAttr(serializer, "gsm2sip_message_id", record.messageId)
        nullableAttr(serializer, "gsm2sip_sim_id", record.simId)
        nullableAttr(serializer, "gsm2sip_sim_label", record.simLabel)
        nullableAttr(serializer, "gsm2sip_from", record.from)
        nullableAttr(serializer, "gsm2sip_to", record.to)
        attr(serializer, "gsm2sip_status", record.status)
        attr(serializer, "gsm2sip_observed_at", record.observedAt.toString())
        if (record.slotIndex != null) attr(serializer, "gsm2sip_slot_index", record.slotIndex.toString())
        if (record.partCount != null) attr(serializer, "gsm2sip_part_count", record.partCount.toString())
        serializer.endTag(null, "sms")
        serializer.flush()
        return output.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun createPrivateTempFile(directory: File): File {
        val file = File.createTempFile("sms-archive-", ".xmlparts", directory)
        val secured = file.setReadable(false, false) && file.setWritable(false, false) &&
            file.setExecutable(false, false) && file.setReadable(true, true) && file.setWritable(true, true)
        if (!secured) {
            file.delete()
            throw IOException("Could not secure temporary SMS XML data")
        }
        return file
    }

    private fun parsePlainArchive(
        input: InputStream,
        encryptedJsonOnly: Boolean = false,
        onRecord: (SmsArchiveRecord) -> Unit,
    ): Long {
        val sniff = PushbackInputStream(input, 64)
        val consumed = ByteArrayOutputStream()
        var firstMeaningful = -1
        var isInitialBom = true
        while (consumed.size() < 64) {
            val next = sniff.read()
            if (next < 0) break
            consumed.write(next)
            val bytes = consumed.toByteArray()
            if (isInitialBom && bytes.size <= 3 && isUtf8BomPrefix(bytes)) continue
            isInitialBom = false
            if (next == ' '.code || next == '\n'.code || next == '\r'.code || next == '\t'.code) continue
            firstMeaningful = next
            break
        }
        sniff.unread(consumed.toByteArray())
        return when (firstMeaningful) {
            '{'.code -> {
                val bytes = consumed.toByteArray()
                if (bytes.size >= 3 && isUtf8BomPrefix(bytes.copyOfRange(0, 3))) {
                    val bom = ByteArray(3)
                    readFully(sniff, bom, "Incomplete UTF-8 BOM")
                }
                readJson(sniff, onRecord)
            }
            '<'.code -> {
                if (encryptedJsonOnly) throw SmsArchiveException("Encrypted archives must contain JSON")
                readXml(sniff, onRecord)
            }
            else -> throw SmsArchiveException("Unrecognized SMS archive format")
        }
    }

    private fun isUtf8BomPrefix(bytes: ByteArray): Boolean = when (bytes.size) {
        1 -> bytes[0] == 0xEF.toByte()
        2 -> bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte()
        3 -> bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        else -> false
    }

    private fun readJson(input: InputStream, onRecord: (SmsArchiveRecord) -> Unit): Long {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = JsonReader(InputStreamReader(input, decoder)).apply { isLenient = false }
        try {
        val seenRoot = HashSet<String>()
        var format: String? = null
        var version: Long? = null
        var exportedAt: Long? = null
        var sawMessages = false
        var count = 0L
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!seenRoot.add(name)) throw SmsArchiveException("Duplicate JSON field '$name'")
            when (name) {
                "format" -> format = readString(reader, name)
                "version" -> version = readLong(reader, name)
                "exported_at" -> exportedAt = readLong(reader, name).also {
                    if (it < 0L) throw SmsArchiveException("Invalid exported_at timestamp")
                }
                "messages" -> {
                    sawMessages = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        checkNotInterrupted()
                        if (count >= MAX_RECORDS) throw SmsArchiveException("Archive contains more than $MAX_RECORDS records")
                        val record = readJsonRecord(reader)
                        onRecord(record)
                        count++
                    }
                    reader.endArray()
                }
                else -> throw SmsArchiveException("Unknown JSON field '$name'")
            }
        }
        reader.endObject()
        if (format != FORMAT_NAME) throw SmsArchiveException("Unsupported SMS archive format")
        if (version != FORMAT_VERSION) throw SmsArchiveException("Unsupported SMS archive version")
        if (exportedAt == null || !sawMessages) throw SmsArchiveException("SMS archive is missing required fields")
        if (reader.peek() != JsonToken.END_DOCUMENT) throw SmsArchiveException("Unexpected data after JSON archive")
        return count
        } catch (error: CharacterCodingException) {
            throw SmsArchiveException("JSON archive is not valid UTF-8", error)
        }
    }

    private fun readJsonRecord(reader: JsonReader): SmsArchiveRecord {
        reader.beginObject()
        val seen = HashSet<String>()
        var id: String? = null
        var source: String? = null
        var ownerId: String? = null
        var gatewayId: String? = null
        var messageId: String? = null
        var simId: String? = null
        var simLabel: String? = null
        var direction: String? = null
        var from: String? = null
        var to: String? = null
        var body: String? = null
        var createdAt: Long? = null
        var status: String? = null
        var observedAt: Long? = null
        var slotIndex: Int? = null
        var subscriptionId: Int? = null
        var partCount: Int? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!seen.add(name)) throw SmsArchiveException("Duplicate message field '$name'")
            when (name) {
                "id" -> id = readString(reader, name)
                "source" -> source = readString(reader, name)
                "owner_id" -> ownerId = readNullableString(reader, name)
                "gateway_id" -> gatewayId = readNullableString(reader, name)
                "message_id" -> messageId = readNullableString(reader, name)
                "sim_id" -> simId = readNullableString(reader, name)
                "sim_label" -> simLabel = readNullableString(reader, name)
                "direction" -> direction = readString(reader, name)
                "from" -> from = readNullableString(reader, name)
                "to" -> to = readNullableString(reader, name)
                "body" -> body = readString(reader, name)
                "created_at" -> createdAt = readLong(reader, name)
                "status" -> status = readString(reader, name)
                "observed_at" -> observedAt = readLong(reader, name)
                "slot_index" -> slotIndex = readNullableInt(reader, name)
                "subscription_id" -> subscriptionId = readNullableInt(reader, name)
                "part_count" -> partCount = readNullableInt(reader, name)
                else -> throw SmsArchiveException("Unknown message field '$name'")
            }
        }
        reader.endObject()
        val required = setOf("id", "source", "direction", "body", "created_at", "status", "observed_at")
        if (!seen.containsAll(required)) throw SmsArchiveException("Message is missing required fields")
        val record = SmsArchiveRecord(
            id = id!!,
            source = source!!,
            ownerId = ownerId,
            gatewayId = gatewayId,
            messageId = messageId,
            simId = simId,
            simLabel = simLabel,
            direction = direction!!,
            from = from,
            to = to,
            body = body!!,
            createdAt = createdAt!!,
            status = status!!,
            observedAt = observedAt!!,
            slotIndex = slotIndex,
            subscriptionId = subscriptionId,
            partCount = partCount,
        )
        try {
            validateRecord(record, forXml = false)
        } catch (error: IllegalArgumentException) {
            throw SmsArchiveException("Invalid SMS message record", error)
        }
        return record
    }

    private fun readXml(input: InputStream, onRecord: (SmsArchiveRecord) -> Unit): Long {
        val parser = try {
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(input, null)
            }
        } catch (error: Exception) {
            throw SmsArchiveException("Secure XML parsing is unavailable", error)
        }
        var rootSeen = false
        var rootClosed = false
        var rootDepth = -1
        var smsDepth = -1
        var declaredCount: Int? = null
        var count = 0
        val occurrences = HashMap<String, Int>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL -> throw SmsArchiveException("DTD declarations are not allowed in SMS XML")
                    XmlPullParser.START_TAG -> {
                        val depth = parser.depth
                        if (!rootSeen) {
                            if (parser.name != "smses" || depth != 1) throw SmsArchiveException("Expected an SMS 'smses' XML root")
                            rootSeen = true
                            rootDepth = depth
                            parser.getAttributeValue(null, "count")?.let { declaredCount = parseXmlInt(it, "count", allowMinusOne = false) }
                        } else if (rootClosed) {
                            throw SmsArchiveException("Unexpected content after SMS XML root")
                        } else if (depth == rootDepth + 1) {
                            val name = parser.name.lowercase(Locale.ROOT)
                            if (name == "mms" || name.startsWith("mms-")) {
                                throw SmsArchiveException("MMS entries are unsupported")
                            }
                            if (parser.name != "sms") throw SmsArchiveException("Unsupported XML entry '${parser.name}'")
                            if (count >= MAX_RECORDS) throw SmsArchiveException("Archive contains more than $MAX_RECORDS records")
                            checkNotInterrupted()
                            val record = parseXmlSms(parser, occurrences)
                            onRecord(record)
                            count++
                            smsDepth = depth
                        } else {
                            throw SmsArchiveException("Nested XML elements are not allowed in SMS entries")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (smsDepth >= 0 && parser.depth == smsDepth) smsDepth = -1
                        else if (rootSeen && parser.depth == rootDepth && parser.name == "smses") rootClosed = true
                    }
                    XmlPullParser.TEXT -> {
                        if (parser.isWhitespace.not()) throw SmsArchiveException("Unexpected text in SMS XML")
                    }
                    XmlPullParser.ENTITY_REF -> throw SmsArchiveException("Custom XML entities are not allowed")
                }
                event = parser.next()
            }
        } catch (error: SmsArchiveException) {
            throw error
        } catch (error: XmlPullParserException) {
            throw SmsArchiveException("Malformed SMS XML", error)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw SmsArchiveException("Malformed SMS XML", error)
        }
        if (!rootSeen || !rootClosed) throw SmsArchiveException("SMS XML document is incomplete")
        if (declaredCount != null && declaredCount != count) throw SmsArchiveException("SMS XML count does not match its entries")
        return count.toLong()
    }

    private fun parseXmlSms(parser: XmlPullParser, occurrences: MutableMap<String, Int>): SmsArchiveRecord {
        fun attr(name: String): String? = parser.getAttributeValue(null, name)
        fun required(name: String): String = attr(name) ?: throw SmsArchiveException("SMS XML entry is missing '$name'")
        val address = attr("address")
        val date = parseXmlLong(required("date"), "date")
        val type = parseXmlInt(required("type"), "type", allowMinusOne = false)
        if (type !in 1..6) throw SmsArchiveException("Unsupported SMS XML type")
        val body = required("body")
        val direction = if (type == 1) "inbound" else "outbound"
        val source = attr("gsm2sip_source") ?: XML_EXTERNAL_SOURCE
        val ownerId = attr("gsm2sip_owner_id")
        val gatewayId = attr("gsm2sip_gateway_id")
        val messageId = attr("gsm2sip_message_id")
        val simId = attr("gsm2sip_sim_id")
        val simLabel = attr("gsm2sip_sim_label")
        val from = attr("gsm2sip_from") ?: if (direction == "inbound") address else null
        val to = attr("gsm2sip_to") ?: if (direction == "outbound") address else null
        val observedAt = attr("gsm2sip_observed_at")?.let { parseXmlLong(it, "gsm2sip_observed_at") }
            ?: attr("date_sent")?.let { parseXmlLong(it, "date_sent") }
            ?: date
        val status = attr("gsm2sip_status") ?: xmlStatus(type)
        val slotIndex = attr("gsm2sip_slot_index")?.let { parseXmlInt(it, "gsm2sip_slot_index", allowMinusOne = false) }
        val subscriptionId = attr("sub_id")?.let { parseXmlInt(it, "sub_id", allowMinusOne = true).takeIf { value -> value >= 0 } }
        val partCount = attr("gsm2sip_part_count")?.let { parseXmlInt(it, "gsm2sip_part_count", allowMinusOne = false) }
        val standardFields = listOf(
            address, attr("date"), attr("type"), body, attr("read"), attr("status"), attr("locked"),
            attr("date_sent"), attr("protocol"), attr("toa"), attr("sc_toa"), attr("service_center"), attr("sub_id"),
        )
        val fingerprint = fingerprint(standardFields)
        val occurrence = occurrences[fingerprint] ?: 0
        if (occurrence >= MAX_RECORDS) throw SmsArchiveException("Too many identical SMS entries")
        occurrences[fingerprint] = occurrence + 1
        val id = attr("gsm2sip_archive_id") ?: stableId(source, ownerId, gatewayId, "$fingerprint:$occurrence")
        val record = SmsArchiveRecord(
            id = id,
            source = source,
            ownerId = ownerId,
            gatewayId = gatewayId,
            messageId = messageId,
            simId = simId,
            simLabel = simLabel,
            direction = direction,
            from = from,
            to = to,
            body = body,
            createdAt = date,
            status = status,
            observedAt = observedAt,
            slotIndex = slotIndex,
            subscriptionId = subscriptionId,
            partCount = partCount,
        )
        try {
            validateRecord(record, forXml = true)
        } catch (error: IllegalArgumentException) {
            throw SmsArchiveException("Invalid SMS XML record", error)
        }
        return record
    }

    private fun validateRecord(record: SmsArchiveRecord, forXml: Boolean) {
        validateId(record.id)
        validateMetadata("source", record.source, required = true)
        validateMetadata("ownerId", record.ownerId)
        validateMetadata("gatewayId", record.gatewayId)
        validateMetadata("messageId", record.messageId)
        validateMetadata("simId", record.simId)
        validateMetadata("simLabel", record.simLabel)
        validateMetadata("direction", record.direction, required = true)
        validateMetadata("from", record.from)
        validateMetadata("to", record.to)
        validateMetadata("status", record.status, required = true)
        if (record.direction != "inbound" && record.direction != "outbound") {
            throw IllegalArgumentException("direction must be inbound or outbound")
        }
        if (record.createdAt < 0L || record.observedAt < 0L) throw IllegalArgumentException("timestamps must be non-negative epoch milliseconds")
        if (record.slotIndex != null && record.slotIndex < 0) throw IllegalArgumentException("slotIndex must be non-negative")
        if (record.subscriptionId != null && record.subscriptionId < 0) throw IllegalArgumentException("subscriptionId must be non-negative")
        if (record.partCount != null && record.partCount <= 0) throw IllegalArgumentException("partCount must be positive")
        val bodyBytes = strictUtf8(record.body, "body")
        if (bodyBytes.size > MAX_BODY_BYTES) throw IllegalArgumentException("body exceeds 1 MiB")
        if (forXml) {
            val xmlText = listOfNotNull(
                record.id, record.source, record.ownerId, record.gatewayId, record.messageId,
                record.simId, record.simLabel, record.direction, record.from, record.to,
                record.body, record.status,
            )
            for (value in xmlText) validateXmlCharacters(value)
        }
    }

    private fun validateXmlCharacters(value: String) {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val valid = codePoint == 0x9 || codePoint == 0xa || codePoint == 0xd ||
                codePoint in 0x20..0xd7ff || codePoint in 0xe000..0xfffd || codePoint in 0x10000..0x10ffff
            if (!valid) throw IllegalArgumentException("XML cannot represent one or more record characters")
            index += Character.charCount(codePoint)
        }
    }

    private fun validateId(id: String) {
        if (!ID_PATTERN.matches(id)) throw IllegalArgumentException("id must be 64 lowercase hexadecimal characters")
    }

    private fun validateMetadata(name: String, value: String?, required: Boolean = false) {
        if (value == null) {
            if (required) throw IllegalArgumentException("$name is required")
            return
        }
        if (required && value.isEmpty()) throw IllegalArgumentException("$name is required")
        if ('\u0000' in value) throw IllegalArgumentException("$name metadata cannot contain NUL")
        if (strictUtf8(value, name).size > MAX_METADATA_BYTES) throw IllegalArgumentException("$name exceeds 2048 UTF-8 bytes")
    }

    private fun strictUtf8(value: String, name: String): ByteArray {
        try {
            val buffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            return ByteArray(buffer.remaining()).also(buffer::get)
        } catch (error: Exception) {
            throw IllegalArgumentException("$name contains invalid Unicode", error)
        }
    }

    private fun validatePassword(password: CharArray) {
        if (password.size < 8) throw IllegalArgumentException("Password must contain at least 8 characters")
        if (password.size > MAX_PASSWORD_CHARS) throw IllegalArgumentException("Password is too long")
    }

    private fun validatePasswordForRead(password: CharArray) {
        if (password.size > MAX_PASSWORD_CHARS) throw SmsArchiveException("Password is too long")
    }

    private fun xmlType(record: SmsArchiveRecord): Int = if (record.direction == "inbound") 1 else when (record.status.lowercase(Locale.ROOT)) {
        "draft" -> 3
        "outbox" -> 4
        "failed" -> 5
        "queued", "queue" -> 6
        else -> 2
    }

    private fun xmlStatus(type: Int): String = when (type) {
        1 -> "received"
        2 -> "sent"
        3 -> "draft"
        4 -> "outbox"
        5 -> "failed"
        6 -> "queued"
        else -> "unknown"
    }

    private fun fingerprint(fields: List<String?>): String {
        val bytes = ByteArrayOutputStream()
        for (field in fields) {
            if (field == null) {
                bytes.write(ByteBuffer.allocate(4).putInt(-1).array())
            } else {
                val encoded = strictUtf8(field, "SMS identity field")
                bytes.write(ByteBuffer.allocate(4).putInt(encoded.size).array())
                bytes.write(encoded)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toLowerHex()
    }

    private fun parseXmlLong(value: String, field: String): Long {
        if (!INTEGER_PATTERN.matches(value)) throw SmsArchiveException("Invalid XML integer '$field'")
        val parsed = value.toLongOrNull() ?: throw SmsArchiveException("Invalid XML integer '$field'")
        if (parsed < 0L) throw SmsArchiveException("Invalid XML timestamp '$field'")
        return parsed
    }

    private fun parseXmlInt(value: String, field: String, allowMinusOne: Boolean): Int {
        if (!INTEGER_PATTERN.matches(value)) throw SmsArchiveException("Invalid XML integer '$field'")
        val parsed = value.toIntOrNull() ?: throw SmsArchiveException("Invalid XML integer '$field'")
        if (parsed < 0 && !(allowMinusOne && parsed == -1)) throw SmsArchiveException("Invalid XML count or type '$field'")
        if (parsed > MAX_RECORDS && field == "count") throw SmsArchiveException("XML count exceeds $MAX_RECORDS")
        return parsed
    }

    private fun readString(reader: JsonReader, field: String): String {
        if (reader.peek() != JsonToken.STRING) throw SmsArchiveException("JSON field '$field' must be a string")
        return reader.nextString()
    }

    private fun readNullableString(reader: JsonReader, field: String): String? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.STRING -> reader.nextString()
        else -> throw SmsArchiveException("JSON field '$field' must be a string or null")
    }

    private fun readLong(reader: JsonReader, field: String): Long {
        if (reader.peek() != JsonToken.NUMBER) throw SmsArchiveException("JSON field '$field' must be an integer")
        val raw = reader.nextString()
        if (!INTEGER_PATTERN.matches(raw)) throw SmsArchiveException("JSON field '$field' must be an integer")
        return raw.toLongOrNull() ?: throw SmsArchiveException("JSON integer '$field' is out of range")
    }

    private fun readNullableInt(reader: JsonReader, field: String): Int? {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null }
        val value = readLong(reader, field)
        if (value !in 0..Int.MAX_VALUE.toLong()) throw SmsArchiveException("JSON count '$field' is out of range")
        return value.toInt()
    }

    private fun JsonWriter.nullable(value: String?) { if (value == null) nullValue() else value(value) }
    private fun JsonWriter.nullable(value: Int?) { if (value == null) nullValue() else value(value.toLong()) }

    private fun attr(serializer: XmlSerializer, name: String, value: String) {
        serializer.attribute(null, name, value)
    }

    private fun nullableAttr(serializer: XmlSerializer, name: String, value: String?) {
        if (value != null) serializer.attribute(null, name, value)
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SMS archive operation interrupted")
    }

    private fun invokeCallback(callback: (SmsArchiveRecord) -> Unit, record: SmsArchiveRecord) {
        try {
            callback(record)
        } catch (error: Throwable) {
            throw CallbackFailure(error)
        }
    }

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val buffer = ByteArray(max)
        var position = 0
        while (position < max) {
            val count = input.read(buffer, position, max - position)
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                buffer[position++] = one.toByte()
            } else position += count
        }
        return buffer.copyOf(position)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, errorMessage: String) {
        var position = 0
        while (position < buffer.size) {
            val count = input.read(buffer, position, buffer.size - position)
            if (count < 0) throw SmsArchiveException(errorMessage)
            if (count == 0) {
                val one = input.read()
                if (one < 0) throw SmsArchiveException(errorMessage)
                buffer[position++] = one.toByte()
            } else position += count
        }
    }

    private fun drainToEof(input: InputStream) {
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) {
                if (input.read() < 0) return
            }
            checkNotInterrupted()
        }
    }

    private fun drainForAuthentication(input: InputStream): Throwable? {
        return try {
            drainToEof(input)
            null
        } catch (error: Throwable) {
            error
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun ByteArray.toLowerHex(): String {
        val chars = "0123456789abcdef"
        val result = CharArray(size * 2)
        for (i in indices) {
            val value = this[i].toInt() and 0xff
            result[i * 2] = chars[value ushr 4]
            result[i * 2 + 1] = chars[value and 0x0f]
        }
        return String(result)
    }

    private class LimitInputStream(input: InputStream, private val limit: Long, private val message: String) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            checkNotInterrupted()
            val value = super.read()
            if (value >= 0) addCount(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkNotInterrupted()
            val size = super.read(buffer, offset, length)
            if (size > 0) addCount(size.toLong())
            return size
        }
        private fun addCount(amount: Long) {
            count += amount
            if (count > limit) throw SmsArchiveException(message)
        }
    }

    private class LimitOutputStream(output: OutputStream, private val limit: Long, private val message: String) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) {
            checkNotInterrupted()
            addCount(1)
            out.write(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkNotInterrupted()
            addCount(length.toLong())
            out.write(buffer, offset, length)
        }
        private fun addCount(amount: Long) {
            count += amount
            if (count > limit) throw SmsArchiveException(message)
        }
    }

    private class CipherUpdatingOutputStream(output: OutputStream, private val cipher: Cipher) : FilterOutputStream(output) {
        private var finished = false
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (finished) throw IOException("Cipher stream is already finished")
            if (length == 0) return
            cipher.update(buffer, offset, length)?.let { out.write(it) }
        }
        override fun flush() = out.flush()
        override fun close() { finish() }
        fun finish() {
            if (finished) return
            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) out.write(finalBytes)
            finished = true
            out.flush()
        }
        fun abort() { finished = true }
    }

    private class CallbackFailure(val original: Throwable) : RuntimeException(original)

    private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
}
