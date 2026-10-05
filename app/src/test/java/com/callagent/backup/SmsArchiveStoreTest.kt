package com.callagent.backup

import android.content.Context
import android.database.Cursor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmsArchiveStoreTest {
    private lateinit var context: Context
    private lateinit var store: SmsArchiveStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("sms-archive.db")
        store = SmsArchiveStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("sms-archive.db")
        context.deleteDatabase("execution.db")
    }

    @Test
    fun repeatedImportDeduplicatesAndOnlyNewerObservedStateUpdates() {
        val queued = record(status = "queued", observedAt = 100, simId = "old-sim")
        val delivered = queued.copy(status = "delivered", observedAt = 200, simId = "new-sim", simLabel = "Travel")
        val first = store.importArchive(inputOf(queued))
        assertEquals(ImportResult(total = 1, inserted = 1, duplicates = 0, updated = 0), first)

        val repeated = store.importArchive(inputOf(queued))
        assertEquals(ImportResult(total = 1, inserted = 0, duplicates = 1, updated = 0), repeated)

        val updated = store.importArchive(inputOf(delivered))
        assertEquals(ImportResult(total = 1, inserted = 0, duplicates = 0, updated = 1), updated)

        val stale = store.importArchive(inputOf(queued.copy(status = "stale", observedAt = 150)))
        assertEquals(ImportResult(total = 1, inserted = 0, duplicates = 1, updated = 0), stale)
        val stored = store.latest().single()
        assertEquals("delivered", stored.status)
        assertEquals("new-sim", stored.simId)
        assertEquals("Travel", stored.simLabel)
        assertEquals(200L, stored.observedAt)
    }

    @Test
    fun idConflictCorruptTailWrongPasswordAndPreviewMismatchCommitNothing() {
        val original = record(body = "original")
        store.importArchive(inputOf(original))

        val conflict = original.copy(body = "different immutable body")
        val additional = record(id = idFor(2))
        // The same id cannot be attached to another message body; prior ledger contents remain.
        val conflictArchive = archiveBytes(listOf(additional, conflict))
        assertThrows(ArchiveConflictException::class.java) {
            store.importArchive(ByteArrayInputStream(conflictArchive))
        }
        assertEquals(1L, store.count())
        assertEquals("original", store.latest().single().body)

        val truncated = archiveBytes(listOf(additional)).dropLast(18).toByteArray()
        assertThrows(IOException::class.java) {
            store.importArchive(ByteArrayInputStream(truncated))
        }
        assertEquals(1L, store.count())

        val encrypted = archiveBytes(listOf(additional), SmsArchiveCodec.Format.ENCRYPTED, "correct horse".toCharArray())
        assertThrows(IOException::class.java) {
            store.importArchive(ByteArrayInputStream(encrypted), "wrong password".toCharArray())
        }
        assertEquals(1L, store.count())

        val previewed = store.previewArchive(ByteArrayInputStream(archiveBytes(listOf(additional))))
        val changed = archiveBytes(listOf(record(id = idFor(3))))
        assertThrows(ArchivePreviewMismatchException::class.java) {
            store.importArchive(ByteArrayInputStream(changed), expectedSha256 = previewed.sha256)
        }
        assertEquals(1L, store.count())
    }

    @Test
    fun cancellationAfterARecordRollsBackTheWholeImportAndProviderSequencesCloseOnFailure() {
        var cancelChecks = 0
        assertThrows(CancellationException::class.java) {
            store.importArchive(
                inputOf(record(id = idFor(31)), record(id = idFor(32))),
                shouldCancel = { ++cancelChecks >= 2 },
            )
        }
        assertEquals(0L, store.count())

        var closed = false
        val closeableRecords = object : Sequence<SmsArchiveRecord>, Closeable {
            override fun iterator(): Iterator<SmsArchiveRecord> = sequenceOf(record(id = "not-an-id")).iterator()
            override fun close() { closed = true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.exportArchive(
                output = ByteArrayOutputStream(),
                format = SmsArchiveCodec.Format.JSON,
                extraRecords = closeableRecords,
            )
        }
        assertTrue(closed)
        assertEquals(0L, store.count())
    }

    @Test
    fun sourceAndAccountIdentityStaySeparateAndOperationalDatabaseIsUntouched() {
        val executionDb = context.openOrCreateDatabase("execution.db", Context.MODE_PRIVATE, null)
        executionDb.execSQL("CREATE TABLE work_queue (id INTEGER PRIMARY KEY, payload TEXT)")
        executionDb.execSQL("INSERT INTO work_queue VALUES (7, 'untouched')")
        executionDb.close()

        val hostAccount = record(id = idFor(11), source = "host", ownerId = "account-a", body = "same text")
        val otherAccount = record(id = idFor(12), source = "host", ownerId = "account-b", body = "same text")
        val gatewayCopy = record(id = idFor(13), source = "gateway", ownerId = "account-a", body = "same text")
        store.importArchive(inputOf(hostAccount, otherAccount, gatewayCopy))

        assertEquals(3L, store.count())
        assertEquals(setOf("account-a", "account-b"), store.latest().filter { it.source == "host" }.map { it.ownerId }.toSet())
        assertNotEquals(hostAccount.id, otherAccount.id)

        assertTrue(context.databaseList().contains("sms-archive.db"))
        val operationalCheck = context.openOrCreateDatabase("execution.db", Context.MODE_PRIVATE, null)
        try {
            operationalCheck.rawQuery("SELECT payload FROM work_queue WHERE id = 7", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("untouched", cursor.getString(0))
            }
        } finally {
            operationalCheck.close()
        }
        val archiveCheck = context.openOrCreateDatabase("sms-archive.db", Context.MODE_PRIVATE, null)
        try {
            archiveCheck.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { cursor ->
                val tables = cursor.collectStrings()
                assertTrue(tables.contains("archive_records"))
                assertFalse(tables.any { it != "archive_records" && it != "android_metadata" })
            }
        } finally {
            archiveCheck.close()
        }
    }

    @Test
    fun latestArchivePageUsesBoundedOffsetOrdering() {
        val records = (1..3).map { number ->
            record(id = idFor(40 + number), body = "body-$number", observedAt = number.toLong()).copy(createdAt = number.toLong())
        }
        store.importArchive(inputOf(*records.toTypedArray()))

        assertEquals(3L, store.count())
        assertEquals(listOf(idFor(43), idFor(42)), store.latest(limit = 2).map { it.id })
        assertEquals(listOf(idFor(41)), store.latest(limit = 2, offset = 2).map { it.id })
    }

    @Test
    fun snapshotExportDeduplicatesIdsButKeepsSameTextWithDifferentIds() {
        val archived = record(id = idFor(20), status = "sent", observedAt = 20)
        val separateIdenticalMessage = record(id = idFor(21), status = "sent", observedAt = 20)
        store.importArchive(inputOf(archived, separateIdenticalMessage))
        val liveCopy = archived.copy(status = "delivered", observedAt = 30)

        val exportedBytes = ByteArrayOutputStream()
        val written = store.exportArchive(
            output = exportedBytes,
            format = SmsArchiveCodec.Format.JSON,
            extraRecords = sequenceOf(liveCopy),
            exportedAt = 500,
        )
        assertEquals(2L, written)
        val restored = ArrayList<SmsArchiveRecord>()
        val readCount = SmsArchiveCodec.read(ByteArrayInputStream(exportedBytes.toByteArray())) { restored += it }
        assertEquals(2L, readCount)
        assertEquals(setOf(archived.id, separateIdenticalMessage.id), restored.map { it.id }.toSet())
        assertEquals(2, restored.size)
        assertEquals(setOf("same text"), restored.map { it.body }.toSet())
        assertEquals("delivered", restored.single { it.id == archived.id }.status)
    }

    private fun inputOf(vararg records: SmsArchiveRecord): ByteArrayInputStream =
        ByteArrayInputStream(archiveBytes(records.toList()))

    private fun archiveBytes(
        records: List<SmsArchiveRecord>,
        format: SmsArchiveCodec.Format = SmsArchiveCodec.Format.JSON,
        password: CharArray? = null,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        SmsArchiveCodec.write(output, format, password, records.asSequence(), exportedAt = 1_000)
        password?.fill('\u0000')
    }.toByteArray()

    private fun record(
        id: String = idFor(1),
        source: String = "gateway",
        ownerId: String? = "owner",
        gatewayId: String? = "gateway-1",
        body: String = "same text",
        status: String = "received",
        observedAt: Long = 100,
        simId: String? = "sim-1",
        simLabel: String? = "Primary",
    ) = SmsArchiveRecord(
        id = id,
        source = source,
        ownerId = ownerId,
        gatewayId = gatewayId,
        messageId = "message-1",
        simId = simId,
        simLabel = simLabel,
        direction = "inbound",
        from = "+15550000001",
        to = "+15550000002",
        body = body,
        createdAt = 50,
        status = status,
        observedAt = observedAt,
        slotIndex = 0,
        subscriptionId = 1,
        partCount = 1,
    )

    private fun idFor(value: Int): String = value.toString(16).padStart(64, '0')

    private fun Cursor.collectStrings(): List<String> = buildList {
        while (moveToNext()) add(getString(0))
    }
}
