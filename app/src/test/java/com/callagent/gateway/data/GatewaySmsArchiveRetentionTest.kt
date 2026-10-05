package com.callagent.gateway.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.callagent.backup.SmsArchiveRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.RobolectricTestRunner
import java.io.Closeable
import java.lang.reflect.Modifier
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GatewaySmsArchiveRetentionTest {
    private lateinit var context: Context
    private lateinit var db: GatewayDatabase
    private val runId = UUID.randomUUID().toString()
    private val gatewayA = "archive-gateway-a-$runId"
    private val gatewayB = "archive-gateway-b-$runId"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        resetDatabaseSingleton()
        context.deleteDatabase("gateway-data.db")
        db = GatewayDatabase.get(context)
        db.adoptGatewayId(gatewayA)
    }

    @After
    fun tearDown() {
        resetDatabaseSingleton()
        context.deleteDatabase("gateway-data.db")
    }

    @Test
    fun retentionIsOffByDefaultAndAckOnlyKeepsExplicitlyArchivedBodies() {
        assertFalse(db.smsArchiveRetentionEnabled())
        val discarded = recordIncoming("off")
        db.acknowledgeEvents(listOf(discarded.eventId))
        assertTrue(snapshot().isEmpty())
        assertEquals("", inboxBody(discarded.eventId))

        db.setSmsArchiveRetentionEnabled(true)
        val retained = recordIncoming("on", body = "archived after ACK")
        db.acknowledgeEvents(listOf(retained.eventId))
        assertEquals("", inboxBody(retained.eventId))
        val row = snapshot().single()
        assertEquals("archived after ACK", row.body)
        assertNull(row.ownerId) // CredentialStore exposes a gateway ID, not a server account ID.
        assertEquals(gatewayA, row.gatewayId)
        assertEquals("inbound", row.direction)
        assertEquals("SIM at receipt", row.simLabel)

        // Replaying the same stable message identity cannot replace its body or add a row.
        recordIncoming("on", body = "replayed different body")
        val replayed = snapshot().single()
        assertEquals(row.id, replayed.id)
        assertEquals("archived after ACK", replayed.body)
    }

    @Test
    fun terminalCommandBodySurvivesRedactionAndDuplicateCallbacksNeverRedispatch() {
        db.setSmsArchiveRetentionEnabled(true)
        insertClaimed("terminal")
        assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch(id("cmd-terminal")))
        assertTrue(db.recordPartState(id("cmd-terminal"), 0, "delivered"))
        val archived = snapshot().single()
        assertEquals("outbound", archived.direction)
        assertEquals("outbound body", archived.body)
        assertEquals("delivered", archived.status)
        assertTrue("state timestamp advances", archived.observedAt >= archived.createdAt)
        assertFalse(db.recordPartState(id("cmd-terminal"), 0, "delivered"))
        assertEquals(GatewayDatabase.DispatchStartResult.NOT_READY, db.beginDispatch(id("cmd-terminal")))
        assertEquals("", db.command(id("cmd-terminal"))!!.text)
        assertEquals(1, snapshot().size)
    }

    @Test
    fun providerRecoveryMatchesBroadcastAndReplayDoesNotDuplicateArchive() {
        db.setSmsArchiveRetentionEnabled(true)
        val receivedAt = System.currentTimeMillis()
        val event = db.recordIncomingSms(
            messageId = id("provider-message"), sender = "+15550001234", recipient = "+15550005678",
            body = "one broadcast", receivedAt = receivedAt, partCount = 1,
            subId = 3, slotIndex = 0, simId = "sim-a", mappingRevision = 5,
            resolution = "known"
        )
        val scope = "a".repeat(64)
        assertTrue(db.configureSmsProviderRecovery(gatewayA, scope, 0, 0, 0, 1) != null)
        val providerRow = GatewayDatabase.SmsProviderRow(
            rowId = 1, providerIdentity = "b".repeat(64), sender = "+15550001234",
            body = "one broadcast", receivedAt = receivedAt, subscriptionId = 3,
            slotIndex = 0, recipient = "+15550005678", simId = "sim-a",
            mappingRevision = 5, resolution = "known"
        )
        val first = db.importSmsProviderPage(
            gatewayA, scope, GatewayDatabase.SmsProviderScanMode.RECONCILE, 0,
            listOf(providerRow), pageComplete = true
        )
        assertEquals(GatewayDatabase.SmsProviderPageState.COMMITTED, first.state)
        assertEquals(0, first.importedRows)
        assertEquals(1, first.matchedBroadcasts)

        assertNotNull(db.beginSmsProviderReconciliation(gatewayA, scope, 2, System.currentTimeMillis(), force = true))
        val replay = db.importSmsProviderPage(
            gatewayA, scope, GatewayDatabase.SmsProviderScanMode.RECONCILE, 0,
            listOf(providerRow), pageComplete = true
        )
        assertEquals(1, replay.duplicateRows)
        assertEquals(event.eventId, db.pendingEvents(gatewayA).first { it.type == "sms.received" }.eventId)
        assertEquals(1, snapshot().size)
        assertEquals("one broadcast", snapshot().single().body)
    }

    @Test
    fun readonlySnapshotCanBeClosedEarlyBeforeFurtherGatewayWrites() {
        db.setSmsArchiveRetentionEnabled(true)
        val archived = recordIncoming("snapshot")
        db.acknowledgeEvents(listOf(archived.eventId))

        val records = db.smsArchiveSnapshot()
        val iterator = records.iterator()
        assertTrue(iterator.hasNext())
        assertEquals("body-snapshot", iterator.next().body)
        (records as Closeable).close()

        // The read-only cursor has been released; normal event writes continue.
        assertEquals("sms.received", recordIncoming("after-snapshot-close").type)
    }

    @Test
    fun retentionToggleStopsNewRowsAndOwnerlessRecordsStayUnknownAfterPairing() {
        db.setSmsArchiveRetentionEnabled(true)
        val fromA = recordIncoming("account-a")
        db.acknowledgeEvents(listOf(fromA.eventId))

        // A pre-pair record has no owner. Later event adoption must not relabel its archive.
        db.writableDatabase.delete("gateway_identity", null, null)
        val unknown = recordIncoming("unpaired")
        db.acknowledgeEvents(listOf(unknown.eventId))
        db.adoptGatewayId(gatewayB)
        val fromB = recordIncoming("account-b")
        db.acknowledgeEvents(listOf(fromB.eventId))

        val byMessage = snapshot().associateBy { it.messageId }
        assertNull(byMessage[id("message-account-a")]!!.ownerId)
        assertEquals(gatewayA, byMessage[id("message-account-a")]!!.gatewayId)
        assertNull(byMessage[id("message-account-b")]!!.ownerId)
        assertEquals(gatewayB, byMessage[id("message-account-b")]!!.gatewayId)
        val unknownRecord = byMessage[id("message-unpaired")]!!
        assertNull(unknownRecord.ownerId)
        assertNull(unknownRecord.gatewayId)
        assertEquals("gsm2sip:server-api:unknown", unknownRecord.source)

        db.setSmsArchiveRetentionEnabled(false)
        val afterOff = recordIncoming("disabled")
        db.acknowledgeEvents(listOf(afterOff.eventId))
        assertFalse(db.smsArchiveRetentionEnabled())
        assertEquals(3, snapshot().size)
    }

    @Test
    fun pendingSmsCapturedBeforeFirstPairingKeepsUnknownArchiveOrigin() {
        db.setSmsArchiveRetentionEnabled(false)
        db.writableDatabase.delete("gateway_identity", null, null)
        val pending = recordIncoming("pending-before-pair")

        // Operational upload ownership is adopted, but the archive provenance is immutable.
        db.adoptGatewayId(gatewayB)
        val record = snapshot().single()
        assertEquals(id("message-pending-before-pair"), record.messageId)
        assertNull(record.ownerId)
        assertNull(record.gatewayId)
        assertEquals("gsm2sip:server-api:unknown", record.source)

        db.acknowledgeEvents(listOf(pending.eventId))
        assertTrue("an off-retention ACK cannot recover a redacted body", snapshot().isEmpty())
    }

    @Test
    fun versionFiveUpgradePreservesJournalUnknownTombstonesAndDefaultOffSetting() {
        val inbound = recordIncoming("pre-upgrade")
        insertClaimed("upgrade")
        assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch(id("cmd-upgrade")))
        assertEquals(1, db.recoverUnknownDispatches())

        db.close()
        resetDatabaseSingleton()
        val raw = context.openOrCreateDatabase("gateway-data.db", Context.MODE_PRIVATE, null)
        downgradeFixtureToVersionFive(raw)
        raw.close()

        db = GatewayDatabase.get(context)
        assertFalse(db.smsArchiveRetentionEnabled()) // opening performs the v5 -> v6 migration
        assertEquals("unknown", db.command(id("cmd-upgrade"))!!.state)
        assertEquals("", db.command(id("cmd-upgrade"))!!.text)
        assertEquals("unknown", db.readableDatabase.rawQuery(
            "SELECT final_state FROM tombstones WHERE entity_id=?", arrayOf(id("message-cmd-upgrade"))
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertTrue(db.readableDatabase.rawQuery("PRAGMA foreign_key_list(command_parts)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(2)) }.contains("commands")
        })
        assertTrue(db.pendingEvents(gatewayA).any { it.eventId == inbound.eventId })
        val stillPending = snapshot().single()
        assertEquals(id("message-pre-upgrade"), stillPending.messageId)
        assertEquals("body-pre-upgrade", stillPending.body)
    }

    private fun recordIncoming(suffix: String, body: String = "body-$suffix"): GatewayDatabase.Event =
        db.recordIncomingSms(
            messageId = id("message-$suffix"), sender = "+15550000001", recipient = "+15550000002",
            body = body, receivedAt = System.currentTimeMillis(), partCount = 1,
            subId = 1, slotIndex = 0, simId = "sim-a", mappingRevision = 7,
            resolution = "known", simLabel = "SIM at receipt"
        )

    private fun insertClaimed(suffix: String) {
        val commandId = id("cmd-$suffix")
        val messageId = id("message-cmd-$suffix")
        val command = GatewayDatabase.Command(
            commandId = commandId,
            messageId = messageId,
            simId = "sim-a",
            mappingRevision = 7,
            to = "+15550000003",
            text = "outbound body",
            partCount = 1,
            expiresAt = System.currentTimeMillis() + 60_000,
            state = "claimed",
            payloadHash = "hash-$suffix",
            ownerGatewayId = gatewayA,
        )
        assertEquals(GatewayDatabase.InsertCommandResult.INSERTED, db.insertClaimedCommand(command, gatewayA))
    }

    private fun inboxBody(eventId: String): String = db.readableDatabase.rawQuery(
        "SELECT body FROM inbox WHERE event_id=?", arrayOf(eventId)
    ).use { assertTrue(it.moveToFirst()); it.getString(0) }

    private fun snapshot(): List<SmsArchiveRecord> {
        val records = db.smsArchiveSnapshot()
        try {
            return records.toList()
        } finally {
            (records as? Closeable)?.close()
        }
    }

    private fun id(suffix: String): String = "$runId-$suffix"

    /** Robolectric keeps the process singleton across method sandboxes. */
    private fun resetDatabaseSingleton() {
        val databaseClass = GatewayDatabase::class.java
        val companion = databaseClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val field = runCatching { databaseClass.getDeclaredField("instance") }.getOrElse {
            companion.javaClass.getDeclaredField("instance")
        }.apply { isAccessible = true }
        val target = if (Modifier.isStatic(field.modifiers)) null else companion
        (field.get(target) as? GatewayDatabase)?.close()
        field.set(target, null)
    }

    /** Rebuild the two v5 tables because API 28 SQLite does not support DROP COLUMN. */
    private fun downgradeFixtureToVersionFive(raw: SQLiteDatabase) {
        raw.execSQL("PRAGMA foreign_keys=OFF")

        raw.execSQL("""CREATE TABLE inbox_v5 (
            message_id TEXT PRIMARY KEY, event_id TEXT NOT NULL UNIQUE,
            sender TEXT NOT NULL, recipient TEXT NOT NULL, body TEXT NOT NULL,
            received_at INTEGER NOT NULL, parts INTEGER NOT NULL,
            sim_id TEXT, mapping_revision INTEGER, sub_id INTEGER NOT NULL,
            slot_index INTEGER NOT NULL, resolution TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'broadcast',
            FOREIGN KEY(event_id) REFERENCES events(event_id)
        )""")
        raw.execSQL("""INSERT INTO inbox_v5(
            message_id,event_id,sender,recipient,body,received_at,parts,sim_id,
            mapping_revision,sub_id,slot_index,resolution,source
        ) SELECT message_id,event_id,sender,recipient,body,received_at,parts,sim_id,
            mapping_revision,sub_id,slot_index,resolution,source FROM inbox""")
        raw.execSQL("DROP TABLE inbox")
        raw.execSQL("ALTER TABLE inbox_v5 RENAME TO inbox")

        raw.execSQL("""CREATE TABLE command_parts_v5_copy (
            command_id TEXT NOT NULL, part_index INTEGER NOT NULL,
            state TEXT NOT NULL, sent_result_code INTEGER,
            delivery_status TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            smsc TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL,
            PRIMARY KEY(command_id,part_index)
        )""")
        raw.execSQL("""INSERT INTO command_parts_v5_copy
            SELECT command_id,part_index,state,sent_result_code,delivery_status,error,smsc,updated_at
            FROM command_parts""")
        raw.execSQL("DROP TABLE command_parts")

        raw.execSQL("""CREATE TABLE commands_v5 (
            command_id TEXT PRIMARY KEY, message_id TEXT NOT NULL UNIQUE,
            sim_id TEXT NOT NULL, mapping_revision INTEGER NOT NULL,
            recipient TEXT, body TEXT, part_count INTEGER NOT NULL,
            expires_at INTEGER NOT NULL, state TEXT NOT NULL,
            payload_hash TEXT NOT NULL, owner_gateway_id TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, last_error TEXT NOT NULL DEFAULT ''
        )""")
        raw.execSQL("""INSERT INTO commands_v5(
            command_id,message_id,sim_id,mapping_revision,recipient,body,part_count,
            expires_at,state,payload_hash,owner_gateway_id,created_at,updated_at,last_error
        ) SELECT command_id,message_id,sim_id,mapping_revision,recipient,body,part_count,
            expires_at,state,payload_hash,owner_gateway_id,created_at,updated_at,last_error FROM commands""")
        raw.execSQL("DROP TABLE commands")
        raw.execSQL("ALTER TABLE commands_v5 RENAME TO commands")

        raw.execSQL("""CREATE TABLE command_parts (
            command_id TEXT NOT NULL, part_index INTEGER NOT NULL,
            state TEXT NOT NULL, sent_result_code INTEGER,
            delivery_status TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            smsc TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL,
            PRIMARY KEY(command_id,part_index),
            FOREIGN KEY(command_id) REFERENCES commands(command_id)
        )""")
        raw.execSQL("""INSERT INTO command_parts
            SELECT command_id,part_index,state,sent_result_code,delivery_status,error,smsc,updated_at
            FROM command_parts_v5_copy""")
        raw.execSQL("DROP TABLE command_parts_v5_copy")

        raw.execSQL("DROP TABLE sms_archive_history")
        raw.execSQL("DROP TABLE sms_archive_options")
        raw.execSQL("PRAGMA user_version=5")
    }
}
