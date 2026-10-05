package com.callagent.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A private archive ledger. It never opens or modifies the app's operational database. */
class SmsArchiveStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val operationLock = ReentrantLock()
    private val archiveContext = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE archive_records (
                id TEXT NOT NULL PRIMARY KEY,
                source TEXT NOT NULL,
                owner_id TEXT,
                gateway_id TEXT,
                message_id TEXT,
                sim_id TEXT,
                sim_label TEXT,
                direction TEXT NOT NULL,
                from_address TEXT,
                to_address TEXT,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                observed_at INTEGER NOT NULL,
                slot_index INTEGER,
                subscription_id INTEGER,
                part_count INTEGER
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX archive_records_created ON archive_records(created_at DESC, id DESC)")
        db.execSQL("CREATE INDEX archive_records_source ON archive_records(source, created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 1 is intentionally reserved. Future upgrades add only archive-local data.
        }
    }

    /** Adds a live record at the explicit retention point chosen by the gateway. */
    fun put(record: SmsArchiveRecord): PutResult = operationLock.withLock {
        validateRecord(record)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = findById(db, record.id)
            val result = when {
                existing == null -> {
                    insert(db, record)
                    PutResult.INSERTED
                }
                !sameImmutableFields(existing, record) -> throw ArchiveConflictException(record.id)
                record.observedAt > existing.observedAt -> {
                    updateMutableFields(db, record)
                    PutResult.UPDATED
                }
                else -> PutResult.DUPLICATE
            }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    /** Count only reads SQLite metadata; it does not materialize message bodies. */
    fun count(): Long = operationLock.withLock {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM archive_records", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** Returns at most [limit] latest archived rows for the read-only history screen. */
    fun latest(limit: Int = 100, offset: Int = 0): List<SmsArchiveRecord> = operationLock.withLock {
        require(limit in 1..1000) { "limit must be between 1 and 1000" }
        require(offset >= 0) { "offset must not be negative" }
        val rows = ArrayList<SmsArchiveRecord>(limit)
        readableDatabase.rawQuery(
            "SELECT $COLUMN_LIST FROM archive_records ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toRecord()
        }
        rows
    }

    /**
     * Scans a selected archive without touching the database. The returned digest identifies the
     * exact bytes that were previewed; pass it to [importArchive] for a second-pass check.
     */
    fun previewArchive(
        input: InputStream,
        password: CharArray? = null,
        shouldCancel: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: ((Long) -> Unit)? = null,
    ): ArchivePreview {
        val digest = MessageDigest.getInstance("SHA-256")
        val sourceCounts = LinkedHashMap<String, Long>()
        var progress = 0L
        val wrapped = DigestInputStream(input, digest)
        val total = SmsArchiveCodec.read(wrapped, password) { record ->
            if (shouldCancel()) throw CancellationException("Import preview cancelled")
            validateRecord(record)
            progress++
            sourceCounts[record.source] = (sourceCounts[record.source] ?: 0L) + 1L
            if (progress % PROGRESS_INTERVAL == 0L) onProgress?.invoke(progress)
        }
        onProgress?.invoke(progress)
        return ArchivePreview(total, sourceCounts.toMap(), digest.digest().toHex())
    }

    /**
     * Imports one complete archive in a single SQLite transaction. The transaction is marked
     * successful only after the codec reaches EOF and validates any encrypted-file tag.
     * [expectedSha256] binds this pass to a previously displayed preview.
     */
    fun importArchive(
        input: InputStream,
        password: CharArray? = null,
        expectedSha256: String? = null,
        shouldCancel: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: ((Long) -> Unit)? = null,
    ): ImportResult = operationLock.withLock {
        val db = writableDatabase
        val result = ImportAccumulator()
        val digest = MessageDigest.getInstance("SHA-256")
        val wrapped = DigestInputStream(input, digest)
        db.beginTransaction()
        try {
            val total = SmsArchiveCodec.read(wrapped, password) { record ->
                if (shouldCancel()) throw CancellationException("Import cancelled")
                validateRecord(record)
                result.total++
                val existing = findById(db, record.id)
                when {
                    existing == null -> {
                        insert(db, record)
                        result.inserted++
                    }
                    !sameImmutableFields(existing, record) -> throw ArchiveConflictException(record.id)
                    record.observedAt > existing.observedAt -> {
                        updateMutableFields(db, record)
                        result.updated++
                    }
                    else -> result.duplicates++
                }
                if (result.total % PROGRESS_INTERVAL == 0L) onProgress?.invoke(result.total)
            }
            result.total = total
            onProgress?.invoke(total)
            val actualDigest = digest.digest().toHex()
            if (expectedSha256 != null && !actualDigest.equals(expectedSha256, ignoreCase = true)) {
                throw ArchivePreviewMismatchException()
            }
            if (shouldCancel()) throw CancellationException("Import cancelled")
            db.setTransactionSuccessful()
            ImportResult(result.total, result.inserted, result.duplicates, result.updated)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Exports a consistent archive snapshot plus the caller's current live ledger. Live rows are
     * emitted first, so a same-ID archive copy cannot replace fresher display metadata.
     */
    fun exportArchive(
        output: OutputStream,
        format: SmsArchiveCodec.Format,
        password: CharArray? = null,
        extraRecords: Sequence<SmsArchiveRecord> = emptySequence(),
        exportedAt: Long = System.currentTimeMillis(),
        shouldCancel: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): Long {
        val extraCloseable = extraRecords as? Closeable
        try {
            return withSnapshot { archived ->
                val seen = HashMap<String, ByteArray>()
                val combined = sequence {
                    for (record in extraRecords) {
                        if (shouldCancel()) throw CancellationException("Export cancelled")
                        validateRecord(record)
                        val fingerprint = immutableFingerprint(record)
                        val previous = seen.putIfAbsent(record.id, fingerprint)
                        if (previous == null) {
                            yield(record)
                        } else if (!MessageDigest.isEqual(previous, fingerprint)) {
                            throw ArchiveConflictException(record.id)
                        }
                    }
                    for (record in archived) {
                        if (shouldCancel()) throw CancellationException("Export cancelled")
                        validateRecord(record)
                        val fingerprint = immutableFingerprint(record)
                        val previous = seen.putIfAbsent(record.id, fingerprint)
                        if (previous == null) {
                            yield(record)
                        } else if (!MessageDigest.isEqual(previous, fingerprint)) {
                            throw ArchiveConflictException(record.id)
                        }
                    }
                }
                SmsArchiveCodec.write(
                    output = output,
                    format = format,
                    password = password,
                    records = combined,
                    exportedAt = exportedAt,
                    scratchDirectory = archiveContext.cacheDir,
                )
            }
        } finally {
            extraCloseable?.close()
        }
    }

    /**
     * Runs [block] over a lazy cursor inside one read transaction. The sequence is valid only for
     * the duration of the block, which keeps an export snapshot consistent without loading bodies
     * into memory.
     */
    fun <T> withSnapshot(block: (Sequence<SmsArchiveRecord>) -> T): T = operationLock.withLock {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        var active = true
        try {
            val cursor = db.rawQuery(
                "SELECT $COLUMN_LIST FROM archive_records ORDER BY created_at ASC, id ASC",
                null,
            )
            try {
                val rows = sequence {
                    check(active) { "Snapshot sequence must be consumed inside withSnapshot" }
                    while (cursor.moveToNext()) {
                        check(active) { "Snapshot has ended" }
                        yield(cursor.toRecord())
                    }
                }
                val value = block(rows)
                db.setTransactionSuccessful()
                value
            } finally {
                active = false
                cursor.close()
            }
        } finally {
            active = false
            db.endTransaction()
        }
    }

    private fun findById(db: SQLiteDatabase, id: String): SmsArchiveRecord? = db.rawQuery(
        "SELECT $COLUMN_LIST FROM archive_records WHERE id = ? LIMIT 1",
        arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    private fun insert(db: SQLiteDatabase, record: SmsArchiveRecord) {
        db.insertOrThrow(TABLE, null, record.toContentValues())
    }

    private fun updateMutableFields(db: SQLiteDatabase, record: SmsArchiveRecord) {
        val values = ContentValues().apply {
            put("sim_id", record.simId)
            put("sim_label", record.simLabel)
            put("status", record.status)
            put("observed_at", record.observedAt)
            putNullableInt("slot_index", record.slotIndex)
            putNullableInt("subscription_id", record.subscriptionId)
            putNullableInt("part_count", record.partCount)
        }
        check(db.update(TABLE, values, "id = ?", arrayOf(record.id)) == 1) {
            "Archive row disappeared during update"
        }
    }

    private fun SmsArchiveRecord.toContentValues() = ContentValues().apply {
        put("id", id)
        put("source", source)
        put("owner_id", ownerId)
        put("gateway_id", gatewayId)
        put("message_id", messageId)
        put("sim_id", simId)
        put("sim_label", simLabel)
        put("direction", direction)
        put("from_address", from)
        put("to_address", to)
        put("body", body)
        put("created_at", createdAt)
        put("status", status)
        put("observed_at", observedAt)
        putNullableInt("slot_index", slotIndex)
        putNullableInt("subscription_id", subscriptionId)
        putNullableInt("part_count", partCount)
    }

    private fun ContentValues.putNullableInt(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.toRecord(): SmsArchiveRecord = SmsArchiveRecord(
        id = getString(getColumnIndexOrThrow("id")),
        source = getString(getColumnIndexOrThrow("source")),
        ownerId = getNullableString("owner_id"),
        gatewayId = getNullableString("gateway_id"),
        messageId = getNullableString("message_id"),
        simId = getNullableString("sim_id"),
        simLabel = getNullableString("sim_label"),
        direction = getString(getColumnIndexOrThrow("direction")),
        from = getNullableString("from_address"),
        to = getNullableString("to_address"),
        body = getString(getColumnIndexOrThrow("body")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        status = getString(getColumnIndexOrThrow("status")),
        observedAt = getLong(getColumnIndexOrThrow("observed_at")),
        slotIndex = getNullableInt("slot_index"),
        subscriptionId = getNullableInt("subscription_id"),
        partCount = getNullableInt("part_count"),
    )

    private fun Cursor.getNullableString(name: String): String? {
        val column = getColumnIndexOrThrow(name)
        return if (isNull(column)) null else getString(column)
    }

    private fun Cursor.getNullableInt(name: String): Int? {
        val column = getColumnIndexOrThrow(name)
        return if (isNull(column)) null else getInt(column)
    }

    private fun sameImmutableFields(a: SmsArchiveRecord, b: SmsArchiveRecord): Boolean =
        a.id == b.id &&
            a.source == b.source &&
            a.ownerId == b.ownerId &&
            a.gatewayId == b.gatewayId &&
            a.messageId == b.messageId &&
            a.direction == b.direction &&
            a.from == b.from &&
            a.to == b.to &&
            a.body == b.body &&
            a.createdAt == b.createdAt

    private fun immutableFingerprint(record: SmsArchiveRecord): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String?) {
            if (value == null) {
                digest.update(0xff.toByte())
            } else {
                digest.update(0x01)
                val bytes = value.toByteArray(Charsets.UTF_8)
                val size = bytes.size
                digest.update((size ushr 24).toByte())
                digest.update((size ushr 16).toByte())
                digest.update((size ushr 8).toByte())
                digest.update(size.toByte())
                digest.update(bytes)
            }
        }
        add(record.source)
        add(record.ownerId)
        add(record.gatewayId)
        add(record.messageId)
        add(record.direction)
        add(record.from)
        add(record.to)
        add(record.body)
        digest.update(byteArrayOf(
            (record.createdAt ushr 56).toByte(),
            (record.createdAt ushr 48).toByte(),
            (record.createdAt ushr 40).toByte(),
            (record.createdAt ushr 32).toByte(),
            (record.createdAt ushr 24).toByte(),
            (record.createdAt ushr 16).toByte(),
            (record.createdAt ushr 8).toByte(),
            record.createdAt.toByte(),
        ))
        return digest.digest()
    }

    private fun validateRecord(record: SmsArchiveRecord) {
        require(record.id.length == 64 && record.id.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid archive record id" }
        validateMetadata(record.source, required = true)
        validateMetadata(record.ownerId)
        validateMetadata(record.gatewayId)
        validateMetadata(record.messageId)
        validateMetadata(record.simId)
        validateMetadata(record.simLabel)
        validateMetadata(record.direction, required = true)
        validateMetadata(record.from)
        validateMetadata(record.to)
        validateMetadata(record.status, required = true)
        require(record.direction == "inbound" || record.direction == "outbound") {
            "Invalid archive direction"
        }
        require(record.createdAt >= 0L && record.observedAt >= 0L) { "Archive timestamps must be non-negative" }
        require(record.slotIndex == null || record.slotIndex >= 0) { "Archive slotIndex must be non-negative" }
        require(record.subscriptionId == null || record.subscriptionId >= 0) { "Archive subscriptionId must be non-negative" }
        require(record.partCount == null || record.partCount > 0) { "Archive partCount must be positive" }
        require(strictUtf8(record.body).size <= MAX_BODY_BYTES) {
            "Archive message body exceeds 1 MiB"
        }
    }

    private fun validateMetadata(value: String?, required: Boolean = false) {
        if (value == null) {
            require(!required) { "Archive metadata is required" }
            return
        }
        require(!required || value.isNotEmpty()) { "Archive metadata is required" }
        require('\u0000' !in value) { "Archive metadata cannot contain NUL" }
        require(strictUtf8(value).size <= MAX_METADATA_BYTES) { "Archive metadata exceeds 2048 UTF-8 bytes" }
    }

    private fun strictUtf8(value: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    companion object {
        private const val DATABASE_NAME = "sms-archive.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "archive_records"
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_METADATA_BYTES = 2 * 1024
        private const val PROGRESS_INTERVAL = 250L
        private const val COLUMN_LIST = "id, source, owner_id, gateway_id, message_id, sim_id, sim_label, " +
            "direction, from_address, to_address, body, created_at, status, observed_at, slot_index, " +
            "subscription_id, part_count"
    }
}

enum class PutResult { INSERTED, UPDATED, DUPLICATE }

data class ImportResult(
    val total: Long,
    val inserted: Long,
    val duplicates: Long,
    val updated: Long,
)

data class ArchivePreview(
    val total: Long,
    val sourceCounts: Map<String, Long>,
    val sha256: String,
)

class ArchiveConflictException(id: String) : IllegalStateException("Archive record id conflict: $id")
class ArchivePreviewMismatchException : IllegalStateException("The selected file changed after preview")

private data class ImportAccumulator(
    var total: Long = 0,
    var inserted: Long = 0,
    var duplicates: Long = 0,
    var updated: Long = 0,
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
