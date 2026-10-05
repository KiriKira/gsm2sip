package com.callagent.gateway.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import com.callagent.backup.SmsArchiveCodec
import com.callagent.backup.SmsArchiveRecord
import com.callagent.gateway.sms.SmsProviderRecovery
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID

/** Durable gateway data plane. SMS bodies live here only until the server's
 * durable event ACK; command IDs and compact tombstones remain after body cleanup. */
class GatewayDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "gateway-data.db", null, VERSION
) {
    private val appContext = context.applicationContext
    data class Event(
        val eventId: String,
        val sequence: Long,
        val occurredAt: Long,
        val type: String,
        val simId: String?,
        val mappingRevision: Long?,
        val payload: String,
        val ownerGatewayId: String?
    )

    data class Command(
        val commandId: String,
        val messageId: String,
        val simId: String,
        val mappingRevision: Long,
        val to: String,
        val text: String,
        val partCount: Int,
        val expiresAt: Long,
        val state: String,
        val payloadHash: String,
        val ownerGatewayId: String? = null,
        val createdAt: Long = 0L,
        /** Immutable API-origin label captured when the server command was claimed. */
        val archiveSource: String = "gsm2sip:server-api:unknown"
    )

    enum class InsertCommandResult { INSERTED, DUPLICATE, CONFLICT }
    enum class DispatchStartResult { STARTED, RATE_LIMITED, NOT_READY }
    data class SmsAggregate(val state: String, val partCount: Int)

    data class SmsProviderRow(
        val rowId: Long,
        val providerIdentity: String,
        val sender: String,
        val body: String,
        val receivedAt: Long,
        val subscriptionId: Int,
        val slotIndex: Int = -1,
        val recipient: String = "",
        val simId: String? = null,
        val mappingRevision: Long? = null,
        val resolution: String = "unknown",
        /** Historical recovery cannot safely infer the name of the SIM that was present then. */
        val simLabel: String? = null
    )

    data class SmsProviderCheckpoint(
        val ownerGatewayId: String,
        val sessionScope: String,
        val minimumProviderId: Long,
        val scanStartAt: Long,
        val lastRowId: Long,
        val reconcileRowId: Long,
        val reconcileTargetMaxId: Long,
        val reconciliationActive: Boolean,
        val lastReconciledAt: Long,
        val lastScanAt: Long?,
        val importedRows: Long,
        val matchedBroadcasts: Long,
        val lastOutcome: String
    )

    enum class SmsProviderPageState { COMMITTED, STALE_OWNER, STALE_CHECKPOINT, NOT_CONFIGURED }
    enum class SmsProviderScanMode { INCREMENTAL, RECONCILE }
    data class SmsProviderPageCommit(
        val state: SmsProviderPageState,
        val importedRows: Int = 0,
        val matchedBroadcasts: Int = 0,
        val duplicateRows: Int = 0,
        val checkpoint: SmsProviderCheckpoint? = null
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO meta(key,value) VALUES('sequence',0)")
        db.execSQL("INSERT INTO meta(key,value) VALUES('heartbeat_sequence',0)")
        db.execSQL("CREATE TABLE heartbeat_pending (sequence INTEGER PRIMARY KEY, owner_gateway_id TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE events (
            event_id TEXT PRIMARY KEY, sequence INTEGER NOT NULL UNIQUE,
            occurred_at INTEGER NOT NULL, type TEXT NOT NULL, sim_id TEXT,
            mapping_revision INTEGER, payload TEXT NOT NULL,
            acked INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
            owner_gateway_id TEXT
        )""")
        db.execSQL("CREATE INDEX events_pending_sequence ON events(acked,sequence)")
        db.execSQL("CREATE TABLE gateway_identity (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE inbox (
            message_id TEXT PRIMARY KEY, event_id TEXT NOT NULL UNIQUE,
            sender TEXT NOT NULL, recipient TEXT NOT NULL, body TEXT NOT NULL,
            received_at INTEGER NOT NULL, parts INTEGER NOT NULL,
            sim_id TEXT, mapping_revision INTEGER, sub_id INTEGER NOT NULL,
            slot_index INTEGER NOT NULL, resolution TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'broadcast', archive_gateway_id TEXT,
            archive_source TEXT NOT NULL DEFAULT 'gsm2sip:server-api:unknown', archive_sim_label TEXT,
            FOREIGN KEY(event_id) REFERENCES events(event_id)
        )""")
        db.execSQL("""CREATE TABLE commands (
            command_id TEXT PRIMARY KEY, message_id TEXT NOT NULL UNIQUE,
            sim_id TEXT NOT NULL, mapping_revision INTEGER NOT NULL,
            recipient TEXT, body TEXT, part_count INTEGER NOT NULL,
            expires_at INTEGER NOT NULL, state TEXT NOT NULL,
            payload_hash TEXT NOT NULL, owner_gateway_id TEXT,
            archive_source TEXT NOT NULL DEFAULT 'gsm2sip:server-api:unknown',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, last_error TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("""CREATE TABLE command_parts (
            command_id TEXT NOT NULL, part_index INTEGER NOT NULL,
            state TEXT NOT NULL, sent_result_code INTEGER,
            delivery_status TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            smsc TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL,
            PRIMARY KEY(command_id,part_index),
            FOREIGN KEY(command_id) REFERENCES commands(command_id)
        )""")
        db.execSQL("""CREATE TABLE tombstones (
            entity_id TEXT PRIMARY KEY, kind TEXT NOT NULL,
            final_state TEXT NOT NULL, created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE TABLE sim_proposals (operation_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sms_dispatch_budget (command_id TEXT PRIMARY KEY, owner_gateway_id TEXT NOT NULL, sim_id TEXT NOT NULL, dispatched_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX sms_dispatch_budget_window ON sms_dispatch_budget(owner_gateway_id,sim_id,dispatched_at)")
        db.execSQL("CREATE TABLE sms_redactions (message_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL)")
        createSmsArchiveTables(db)
        db.execSQL("""CREATE TABLE call_ledger (
            call_id TEXT PRIMARY KEY, sim_id TEXT NOT NULL, mapping_revision INTEGER NOT NULL,
            direction TEXT NOT NULL, state TEXT NOT NULL, owner_gateway_id TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE legacy_outbox (
            message_id TEXT PRIMARY KEY, recipient TEXT NOT NULL, body TEXT NOT NULL,
            old_sub_id INTEGER NOT NULL, old_dispatched INTEGER NOT NULL,
            state TEXT NOT NULL, imported_at INTEGER NOT NULL
        )""")
        createSmsProviderRecoveryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE commands ADD COLUMN owner_gateway_id TEXT")
            db.execSQL("ALTER TABLE call_ledger ADD COLUMN owner_gateway_id TEXT")
            db.execSQL("UPDATE commands SET owner_gateway_id=(SELECT value FROM gateway_identity WHERE key='gateway_id')")
            db.execSQL("UPDATE call_ledger SET owner_gateway_id=(SELECT value FROM gateway_identity WHERE key='gateway_id')")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE sms_dispatch_budget (command_id TEXT PRIMARY KEY, owner_gateway_id TEXT NOT NULL, sim_id TEXT NOT NULL, dispatched_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX sms_dispatch_budget_window ON sms_dispatch_budget(owner_gateway_id,sim_id,dispatched_at)")
            db.execSQL("CREATE TABLE sms_redactions (message_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL)")
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE heartbeat_pending (sequence INTEGER PRIMARY KEY, owner_gateway_id TEXT NOT NULL, payload TEXT NOT NULL)")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE inbox ADD COLUMN source TEXT NOT NULL DEFAULT 'broadcast'")
            createSmsProviderRecoveryTables(db)
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE inbox ADD COLUMN archive_gateway_id TEXT")
            db.execSQL("ALTER TABLE inbox ADD COLUMN archive_source TEXT NOT NULL DEFAULT 'gsm2sip:server-api:unknown'")
            db.execSQL("ALTER TABLE inbox ADD COLUMN archive_sim_label TEXT")
            db.execSQL("ALTER TABLE commands ADD COLUMN archive_source TEXT NOT NULL DEFAULT 'gsm2sip:server-api:unknown'")
            createSmsArchiveTables(db)
        }
    }

    private fun createSmsArchiveTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sms_archive_options (singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1), enabled INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT OR IGNORE INTO sms_archive_options(singleton_id,enabled) VALUES(1,0)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sms_archive_history (
            id TEXT PRIMARY KEY, source TEXT NOT NULL, owner_id TEXT, gateway_id TEXT,
            owner_gateway_id TEXT,
            message_id TEXT, sim_id TEXT, sim_label TEXT, direction TEXT NOT NULL,
            from_address TEXT, to_address TEXT, body TEXT NOT NULL,
            created_at INTEGER NOT NULL, status TEXT NOT NULL, observed_at INTEGER NOT NULL,
            slot_index INTEGER, subscription_id INTEGER, part_count INTEGER
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS sms_archive_history_message ON sms_archive_history(owner_gateway_id,message_id,direction)")
    }

    private fun createSmsProviderRecoveryTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS sms_provider_scan (
            owner_gateway_id TEXT NOT NULL, session_scope TEXT NOT NULL,
            minimum_provider_id INTEGER NOT NULL, scan_start_at INTEGER NOT NULL,
            last_row_id INTEGER NOT NULL DEFAULT 0,
            reconcile_row_id INTEGER NOT NULL DEFAULT 0,
            reconcile_target_max_id INTEGER NOT NULL DEFAULT 0,
            reconciliation_active INTEGER NOT NULL DEFAULT 1,
            last_reconciled_at INTEGER NOT NULL DEFAULT 0,
            last_scan_at INTEGER,
            imported_rows INTEGER NOT NULL DEFAULT 0,
            matched_broadcasts INTEGER NOT NULL DEFAULT 0,
            last_outcome TEXT NOT NULL DEFAULT 'configured',
            PRIMARY KEY(owner_gateway_id, session_scope)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sms_provider_imports (
            owner_gateway_id TEXT NOT NULL, session_scope TEXT NOT NULL,
            provider_identity TEXT NOT NULL, provider_row_id INTEGER NOT NULL,
            message_id TEXT NOT NULL, event_id TEXT NOT NULL,
            imported_at INTEGER NOT NULL,
            PRIMARY KEY(owner_gateway_id, session_scope, provider_identity),
            UNIQUE(owner_gateway_id, session_scope, event_id)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS sms_provider_imports_event ON sms_provider_imports(owner_gateway_id,session_scope,event_id)")
    }

    /** Insert the SMS and its upload event in one transaction. eventId is made
     * by the receiver once; every retry reuses this persisted value. */
    fun recordIncomingSms(
        messageId: String,
        sender: String,
        recipient: String,
        body: String,
        receivedAt: Long,
        partCount: Int,
        subId: Int,
        slotIndex: Int,
        simId: String?,
        mappingRevision: Long?,
        resolution: String,
        source: String = "broadcast",
        simLabel: String? = null
    ): Event {
        require(source == "broadcast" || source == "recovered")
        // Read credentials before taking SQLite's write lock. Other ingestion
        // paths hold the credential identity monitor before opening this DB.
        val archiveIdentity = archiveIdentity(appContext)
        fun persistForCurrentDatabaseOwner(): Event {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val prior = db.rawQuery("SELECT event_id FROM inbox WHERE message_id=?", arrayOf(messageId))
                if (prior.moveToFirst()) {
                    val event = getEvent(db, prior.getString(0))
                    prior.close()
                    if (event != null) {
                        archiveExistingInbox(db, archiveIdentity, event.eventId)
                        db.setTransactionSuccessful()
                        return event
                    }
                } else prior.close()

                val eventId = UUID.randomUUID().toString()
                val payload = JSONObject().put("message_id", messageId).put("from", sender)
                    .put("text", body).put("parts", partCount).toString()
                val event = insertEvent(db, eventId, receivedAt, "sms.received", simId,
                    mappingRevision, payload)
                val archiveSource = archiveSourceFor(archiveIdentity, event.ownerGatewayId, activeOwnerFor(db))
                val values = ContentValues().apply {
                    put("message_id", messageId); put("event_id", eventId)
                    put("sender", sender); put("recipient", recipient); put("body", body)
                    put("received_at", receivedAt); put("parts", partCount)
                    put("source", source)
                    if (simId == null) putNull("sim_id") else put("sim_id", simId)
                    if (mappingRevision == null) putNull("mapping_revision") else put("mapping_revision", mappingRevision)
                    put("sub_id", subId); put("slot_index", slotIndex); put("resolution", resolution)
                    if (event.ownerGatewayId == null) putNull("archive_gateway_id")
                    else put("archive_gateway_id", event.ownerGatewayId)
                    put("archive_source", archiveSource)
                    if (simLabel == null) putNull("archive_sim_label") else put("archive_sim_label", simLabel)
                }
                db.insertOrThrow("inbox", null, values)
                archiveInbound(
                    db, archiveIdentity, event.ownerGatewayId, messageId, sender, recipient,
                    body, receivedAt, partCount, simId, simLabel, subId, slotIndex,
                    "received", System.currentTimeMillis(), archiveSource
                )
                db.setTransactionSuccessful()
                return event
            } finally { db.endTransaction() }
        }

        // Pairing writes the session and then adopts it in this database. Hold
        // the same identity monitor across owner adoption and the journal
        // transaction, so a stale pre-pair snapshot can never move ownership
        // backward between those operations.
        repeat(2) {
            val snapshot = CredentialStore.load(appContext) ?: return persistForCurrentDatabaseOwner()
            val event = CredentialStore.withCurrentIdentity(
                appContext, snapshot.gatewayId, snapshot.controlBaseUrl
            ) { current ->
                if (!isActiveGateway(current.gatewayId)) adoptGatewayId(current.gatewayId)
                persistForCurrentDatabaseOwner()
            }
            if (event != null) return event
        }
        // If identity is changing continuously, preserve the received SMS
        // without adopting an unverified snapshot. The transaction assigns
        // the database's current active owner (or leaves it unresolved).
        return persistForCurrentDatabaseOwner()
    }

    /** First configuration wins for a gateway/session scope. A repeated UI or
     * service call cannot silently move the history boundary backwards. */
    fun configureSmsProviderRecovery(
        ownerGatewayId: String,
        sessionScope: String,
        minimumProviderId: Long,
        scanStartAt: Long,
        initialLastRowId: Long,
        reconciliationTargetMaxId: Long
    ): SmsProviderCheckpoint? {
        require(minimumProviderId >= 0 && scanStartAt >= 0 && initialLastRowId >= 0 && reconciliationTargetMaxId >= 0)
        require(sessionScope.matches(Regex("[0-9a-f]{64}")))
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (activeOwnerFor(db) != ownerGatewayId) return null
            db.execSQL("""INSERT OR IGNORE INTO sms_provider_scan(
                owner_gateway_id,session_scope,minimum_provider_id,scan_start_at,last_row_id,
                reconcile_target_max_id,reconciliation_active
            ) VALUES(?,?,?,?,?,?,1)""", arrayOf(ownerGatewayId, sessionScope, minimumProviderId,
                scanStartAt, initialLastRowId, reconciliationTargetMaxId))
            db.setTransactionSuccessful()
            return smsProviderCheckpoint(db, ownerGatewayId, sessionScope)
        } finally { db.endTransaction() }
    }

    fun smsProviderCheckpoint(ownerGatewayId: String, sessionScope: String): SmsProviderCheckpoint? =
        smsProviderCheckpoint(readableDatabase, ownerGatewayId, sessionScope)

    /** Expand to retained history only after the caller's explicit full-history choice. */
    fun promoteSmsProviderRecoveryToFullHistory(
        ownerGatewayId: String,
        sessionScope: String,
        currentMaxRowId: Long
    ): SmsProviderCheckpoint? {
        require(currentMaxRowId >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (activeOwnerFor(db) != ownerGatewayId) return null
            val checkpoint = smsProviderCheckpoint(db, ownerGatewayId, sessionScope) ?: return null
            if (checkpoint.minimumProviderId == 0L && checkpoint.scanStartAt == 0L) {
                db.setTransactionSuccessful()
                return checkpoint
            }
            db.execSQL("""UPDATE sms_provider_scan SET minimum_provider_id=0,scan_start_at=0,
                last_row_id=0,reconcile_row_id=0,reconcile_target_max_id=?,reconciliation_active=1,
                last_reconciled_at=0,last_outcome='configured_full_history'
                WHERE owner_gateway_id=? AND session_scope=?""",
                arrayOf(currentMaxRowId, ownerGatewayId, sessionScope))
            db.setTransactionSuccessful()
            return smsProviderCheckpoint(db, ownerGatewayId, sessionScope)
        } finally { db.endTransaction() }
    }

    private fun smsProviderCheckpoint(
        db: SQLiteDatabase,
        ownerGatewayId: String,
        sessionScope: String
    ): SmsProviderCheckpoint? = db.rawQuery(
        "SELECT owner_gateway_id,session_scope,minimum_provider_id,scan_start_at,last_row_id,reconcile_row_id,reconcile_target_max_id,reconciliation_active,last_reconciled_at,last_scan_at,imported_rows,matched_broadcasts,last_outcome FROM sms_provider_scan WHERE owner_gateway_id=? AND session_scope=?",
        arrayOf(ownerGatewayId, sessionScope)
    ).use { c ->
        if (!c.moveToFirst()) null else SmsProviderCheckpoint(
            c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4),
            c.getLong(5), c.getLong(6), c.getInt(7) != 0, c.getLong(8),
            c.getLongOrNull(9), c.getLong(10), c.getLong(11), c.getString(12)
        )
    }

    /** Start a bounded low-ID reconciliation pass if none is already running. */
    fun beginSmsProviderReconciliation(
        ownerGatewayId: String,
        sessionScope: String,
        targetMaxId: Long,
        now: Long,
        force: Boolean = false
    ): SmsProviderCheckpoint? {
        require(targetMaxId >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (activeOwnerFor(db) != ownerGatewayId) return null
            val checkpoint = smsProviderCheckpoint(db, ownerGatewayId, sessionScope) ?: return null
            if (checkpoint.reconciliationActive && !force) {
                db.setTransactionSuccessful()
                return checkpoint
            }
            db.execSQL("""UPDATE sms_provider_scan SET reconcile_row_id=0,
                reconcile_target_max_id=?,reconciliation_active=1,last_outcome='reconcile_more'
                WHERE owner_gateway_id=? AND session_scope=?""",
                arrayOf(targetMaxId, ownerGatewayId, sessionScope))
            db.setTransactionSuccessful()
            return smsProviderCheckpoint(db, ownerGatewayId, sessionScope)
        } finally { db.endTransaction() }
    }

    /** Restart only the active sweep cursor if the system provider reset while
     * the old target was being paged. */
    fun restartSmsProviderReconciliation(
        ownerGatewayId: String,
        sessionScope: String,
        expectedReconcileRowId: Long,
        newTargetMaxId: Long
    ): SmsProviderCheckpoint? {
        require(expectedReconcileRowId >= 0 && newTargetMaxId >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (activeOwnerFor(db) != ownerGatewayId) return null
            val checkpoint = smsProviderCheckpoint(db, ownerGatewayId, sessionScope) ?: return null
            if (!checkpoint.reconciliationActive || checkpoint.reconcileRowId != expectedReconcileRowId) {
                db.setTransactionSuccessful()
                return checkpoint
            }
            db.execSQL("""UPDATE sms_provider_scan SET reconcile_row_id=0,
                reconcile_target_max_id=?,last_outcome='reconcile_reset'
                WHERE owner_gateway_id=? AND session_scope=? AND reconciliation_active=1 AND reconcile_row_id=?""",
                arrayOf(newTargetMaxId, ownerGatewayId, sessionScope, expectedReconcileRowId))
            db.setTransactionSuccessful()
            return smsProviderCheckpoint(db, ownerGatewayId, sessionScope)
        } finally { db.endTransaction() }
    }

    /**
     * Apply one bounded provider page. Provider identity, any SMS event, and
     * the scan cursor commit together. Compare-and-set prevents two callers
     * from advancing over a page another caller has not imported.
     */
    fun importSmsProviderPage(
        ownerGatewayId: String,
        sessionScope: String,
        scanMode: SmsProviderScanMode,
        expectedCursor: Long,
        rows: List<SmsProviderRow>,
        pageComplete: Boolean,
        now: Long = System.currentTimeMillis()
    ): SmsProviderPageCommit {
        require(sessionScope.matches(Regex("[0-9a-f]{64}")))
        require(expectedCursor >= 0)
        require(rows.size <= SmsProviderRecovery.MAX_PAGE_SIZE)
        require(rows.all { it.rowId > expectedCursor && it.providerIdentity.matches(Regex("[0-9a-f]{64}")) })
        val archiveIdentity = archiveIdentity(appContext)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (activeOwnerFor(db) != ownerGatewayId) {
                return SmsProviderPageCommit(SmsProviderPageState.STALE_OWNER)
            }
            val checkpoint = smsProviderCheckpoint(db, ownerGatewayId, sessionScope)
                ?: return SmsProviderPageCommit(SmsProviderPageState.NOT_CONFIGURED)
            val checkpointCursor = when (scanMode) {
                SmsProviderScanMode.INCREMENTAL -> checkpoint.lastRowId
                SmsProviderScanMode.RECONCILE -> checkpoint.reconcileRowId
            }
            if (checkpointCursor != expectedCursor ||
                (scanMode == SmsProviderScanMode.RECONCILE) != checkpoint.reconciliationActive) {
                return SmsProviderPageCommit(SmsProviderPageState.STALE_CHECKPOINT, checkpoint = checkpoint)
            }
            if (scanMode == SmsProviderScanMode.RECONCILE && rows.any { it.rowId > checkpoint.reconcileTargetMaxId }) {
                return SmsProviderPageCommit(SmsProviderPageState.STALE_CHECKPOINT, checkpoint = checkpoint)
            }

            var imported = 0
            var matched = 0
            var duplicate = 0
            rows.forEach { row ->
                val prior = db.rawQuery(
                    "SELECT 1 FROM sms_provider_imports WHERE owner_gateway_id=? AND session_scope=? AND provider_identity=?",
                    arrayOf(ownerGatewayId, sessionScope, row.providerIdentity)
                ).use { it.moveToFirst() }
                if (prior) {
                    duplicate++
                    return@forEach
                }

                val broadcast = findBroadcastEvidence(db, ownerGatewayId, sessionScope, row)
                val eventId: String
                val messageId: String
                if (broadcast != null) {
                    eventId = broadcast.first
                    messageId = broadcast.second
                    matched++
                    archiveExistingInbox(db, archiveIdentity, eventId)
                } else {
                    messageId = providerMessageId(ownerGatewayId, sessionScope, row.providerIdentity)
                    eventId = UUID.randomUUID().toString()
                    val at = row.receivedAt.takeIf { it > 0 } ?: now
                    val archiveSource = archiveSourceFor(archiveIdentity, ownerGatewayId, activeOwnerFor(db))
                    val payload = JSONObject().put("message_id", messageId).put("from", row.sender)
                        .put("text", row.body).put("parts", 1).toString()
                    insertEvent(db, eventId, at, "sms.received", row.simId, row.mappingRevision,
                        payload, ownerGatewayId)
                    db.insertOrThrow("inbox", null, ContentValues().apply {
                        put("message_id", messageId); put("event_id", eventId)
                        put("sender", row.sender); put("recipient", row.recipient); put("body", row.body)
                        put("received_at", at); put("parts", 1)
                        if (row.simId == null) putNull("sim_id") else put("sim_id", row.simId)
                        if (row.mappingRevision == null) putNull("mapping_revision")
                        else put("mapping_revision", row.mappingRevision)
                        put("sub_id", row.subscriptionId); put("slot_index", row.slotIndex)
                        put("resolution", row.resolution); put("source", "recovered")
                        put("archive_gateway_id", ownerGatewayId)
                        put("archive_source", archiveSource)
                        if (row.simLabel == null) putNull("archive_sim_label")
                        else put("archive_sim_label", row.simLabel)
                    })
                    archiveInbound(
                        db, archiveIdentity, ownerGatewayId, messageId, row.sender, row.recipient,
                        row.body, at, 1, row.simId, row.simLabel, row.subscriptionId,
                        row.slotIndex, "received", now, archiveSource
                    )
                    imported++
                }
                db.insertOrThrow("sms_provider_imports", null, ContentValues().apply {
                    put("owner_gateway_id", ownerGatewayId); put("session_scope", sessionScope)
                    put("provider_identity", row.providerIdentity); put("provider_row_id", row.rowId)
                    put("message_id", messageId); put("event_id", eventId); put("imported_at", now)
                })
            }

            val nextIncrementalRowId = when (scanMode) {
                SmsProviderScanMode.INCREMENTAL -> rows.lastOrNull()?.rowId ?: checkpoint.lastRowId
                SmsProviderScanMode.RECONCILE -> if (pageComplete)
                    maxOf(checkpoint.lastRowId, checkpoint.reconcileTargetMaxId) else checkpoint.lastRowId
            }
            val nextReconcileRowId = when {
                scanMode != SmsProviderScanMode.RECONCILE -> checkpoint.reconcileRowId
                pageComplete -> 0L
                else -> rows.lastOrNull()?.rowId ?: expectedCursor
            }
            val stillReconciling = scanMode == SmsProviderScanMode.RECONCILE && !pageComplete
            val reconciledAt = if (scanMode == SmsProviderScanMode.RECONCILE && pageComplete)
                now else checkpoint.lastReconciledAt
            val outcome = when (scanMode) {
                SmsProviderScanMode.INCREMENTAL -> if (pageComplete) "incremental_complete" else "incremental_more"
                SmsProviderScanMode.RECONCILE -> if (pageComplete) "reconcile_complete" else "reconcile_more"
            }
            val changed = db.update("sms_provider_scan", ContentValues().apply {
                put("last_row_id", nextIncrementalRowId); put("reconcile_row_id", nextReconcileRowId)
                put("reconciliation_active", if (stillReconciling) 1 else 0)
                put("last_reconciled_at", reconciledAt); put("last_scan_at", now)
                put("last_outcome", outcome)
                put("imported_rows", checkpoint.importedRows + imported)
                put("matched_broadcasts", checkpoint.matchedBroadcasts + matched)
            }, "owner_gateway_id=? AND session_scope=? AND last_row_id=? AND reconcile_row_id=? AND reconcile_target_max_id=? AND reconciliation_active=?",
                arrayOf(ownerGatewayId, sessionScope, checkpoint.lastRowId.toString(),
                    checkpoint.reconcileRowId.toString(), checkpoint.reconcileTargetMaxId.toString(),
                    if (checkpoint.reconciliationActive) "1" else "0"))
            if (changed != 1) return SmsProviderPageCommit(
                SmsProviderPageState.STALE_CHECKPOINT,
                checkpoint = smsProviderCheckpoint(db, ownerGatewayId, sessionScope)
            )
            val updated = smsProviderCheckpoint(db, ownerGatewayId, sessionScope)
            db.setTransactionSuccessful()
            return SmsProviderPageCommit(SmsProviderPageState.COMMITTED, imported, matched, duplicate, updated)
        } finally { db.endTransaction() }
    }

    private fun findBroadcastEvidence(
        db: SQLiteDatabase,
        ownerGatewayId: String,
        sessionScope: String,
        row: SmsProviderRow
    ): Pair<String, String>? {
        // These fields are not enough to distinguish messages with an empty
        // body or a missing timestamp; preserve a possible duplicate instead.
        if (row.sender.isBlank() || row.body.isEmpty() || row.receivedAt <= 0) return null
        val query = if (row.subscriptionId >= 0) {
            """SELECT i.event_id,i.message_id FROM inbox i JOIN events e ON e.event_id=i.event_id
                WHERE e.owner_gateway_id=? AND e.type='sms.received' AND i.source='broadcast'
                AND i.sender=? AND i.body=? AND ABS(i.received_at-?)<=?
                AND (i.sub_id<0 OR i.sub_id=?)
                AND NOT EXISTS (SELECT 1 FROM sms_provider_imports p
                    WHERE p.owner_gateway_id=? AND p.session_scope=? AND p.event_id=i.event_id)
                ORDER BY ABS(i.received_at-?),i.received_at,i.event_id LIMIT 2"""
        } else {
            """SELECT i.event_id,i.message_id FROM inbox i JOIN events e ON e.event_id=i.event_id
                WHERE e.owner_gateway_id=? AND e.type='sms.received' AND i.source='broadcast'
                AND i.sender=? AND i.body=? AND ABS(i.received_at-?)<=?
                AND NOT EXISTS (SELECT 1 FROM sms_provider_imports p
                    WHERE p.owner_gateway_id=? AND p.session_scope=? AND p.event_id=i.event_id)
                ORDER BY ABS(i.received_at-?),i.received_at,i.event_id LIMIT 2"""
        }
        val args = if (row.subscriptionId >= 0) arrayOf(
            ownerGatewayId, row.sender, row.body, row.receivedAt.toString(),
            SmsProviderRecovery.BROADCAST_MATCH_WINDOW_MS.toString(), row.subscriptionId.toString(),
            ownerGatewayId, sessionScope, row.receivedAt.toString()
        ) else arrayOf(
            ownerGatewayId, row.sender, row.body, row.receivedAt.toString(),
            SmsProviderRecovery.BROADCAST_MATCH_WINDOW_MS.toString(), ownerGatewayId, sessionScope,
            row.receivedAt.toString()
        )
        return db.rawQuery(query, args).use { c ->
            if (!c.moveToFirst()) null else {
                val candidate = c.getString(0) to c.getString(1)
                // If more than one broadcast could describe this provider row,
                // its identity is ambiguous. Keep a possible duplicate instead
                // of collapsing two identical real SMS messages.
                if (c.moveToNext()) null else candidate
            }
        }
    }

    private fun providerMessageId(ownerGatewayId: String, sessionScope: String, providerIdentity: String): String {
        val input = "gsm2sip.sms.provider.message.v1\u0000$ownerGatewayId\u0000$sessionScope\u0000$providerIdentity"
        return UUID.nameUUIDFromBytes(MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))).toString()
    }

    fun pendingEvents(gatewayId: String, limit: Int = 50): List<Event> {
        val out = mutableListOf<Event>()
        readableDatabase.rawQuery(
            "SELECT event_id,sequence,occurred_at,type,sim_id,mapping_revision,payload,owner_gateway_id FROM events WHERE acked=0 AND owner_gateway_id=? ORDER BY sequence LIMIT ?",
            arrayOf(gatewayId, limit.coerceIn(1, 50).toString())
        ).use { c -> while (c.moveToNext()) out += Event(
            c.getString(0), c.getLong(1), c.getLong(2), c.getString(3),
            c.getStringOrNull(4), c.getLongOrNull(5), c.getString(6), c.getStringOrNull(7)
        ) }
        return out
    }

    /** On first pair, adopt unowned pre-pair events. After re-pair, events
     * owned by the prior server stay local and cannot be uploaded under a new ID. */
    fun adoptGatewayId(gatewayId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery("SELECT value FROM gateway_identity WHERE key='gateway_id'", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (previous == gatewayId) { db.setTransactionSuccessful(); return false }
            if (previous == null) {
                db.execSQL("UPDATE events SET owner_gateway_id=? WHERE owner_gateway_id IS NULL", arrayOf(gatewayId))
            }
            db.execSQL("DELETE FROM heartbeat_pending")
            db.execSQL("UPDATE meta SET value=0 WHERE key='heartbeat_sequence'")
            db.execSQL("INSERT OR REPLACE INTO gateway_identity(key,value) VALUES('gateway_id',?)", arrayOf(gatewayId))
            if (previous != null && previous != gatewayId) db.delete("sim_proposals", null, null)
            db.setTransactionSuccessful()
            return previous != null
        } finally { db.endTransaction() }
    }

    fun pendingEventsForOtherGateways(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE acked=0 AND owner_gateway_id IS NOT NULL AND owner_gateway_id!=(SELECT value FROM gateway_identity WHERE key='gateway_id')",
        null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Imported pre-v1 outbox rows remain quarantined because they have no
     * server SIM UUID/revision. Never auto-dispatch them through a default SIM. */
    fun preserveLegacyOutbox(messageId: String, recipient: String, body: String,
                             oldSubId: Int, wasDispatched: Boolean): Boolean {
        return writableDatabase.insertWithOnConflict("legacy_outbox", null, ContentValues().apply {
            put("message_id", messageId); put("recipient", recipient); put("body", body)
            put("old_sub_id", oldSubId); put("old_dispatched", if (wasDispatched) 1 else 0)
            put("state", if (wasDispatched) "unknown" else "needs_sim_binding")
            put("imported_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun legacyOutboxCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM legacy_outbox", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Mark only server-confirmed durable ACKs. Keep ID/sequence, drop SMS
     * content so retries can never resurrect a delivered message. */
    fun acknowledgeEvents(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        val archiveIdentity = archiveIdentity(appContext)
        val db = writableDatabase
        db.beginTransaction()
        try {
            eventIds.forEach { id ->
                val event = db.rawQuery(
                    "SELECT type,payload,owner_gateway_id,occurred_at,sim_id FROM events WHERE event_id=?",
                    arrayOf(id)
                ).use { c -> if (!c.moveToFirst()) null else Event(
                    id, 0L, c.getLong(3), c.getString(0), c.getStringOrNull(4), null,
                    c.getString(1), c.getStringOrNull(2)
                ) }
                val payload = event?.let { runCatching { JSONObject(it.payload) }.getOrNull() }
                val messageId = payload?.optString("message_id")?.takeIf { it.isNotBlank() }
                if (event?.type == "sms.received" && messageId != null) {
                    // A user may enable retention after receipt but before the server ACK.
                    // Preserve the still-present body just before normal redaction.
                    val inbox = db.rawQuery(
                        """SELECT sender,recipient,body,received_at,parts,sim_id,sub_id,slot_index,
                            archive_gateway_id,archive_source,archive_sim_label FROM inbox WHERE event_id=?""",
                        arrayOf(id)
                    ).use { c -> if (!c.moveToFirst()) null else IncomingArchiveData(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4),
                        c.getStringOrNull(5), c.getInt(6), c.getInt(7), c.getStringOrNull(8),
                        c.getString(9), c.getStringOrNull(10)
                    ) }
                    if (inbox != null && inbox.body.isNotEmpty()) archiveInbound(
                        db, archiveIdentity, inbox.archiveGatewayId, messageId, inbox.sender, inbox.recipient,
                        inbox.body, inbox.receivedAt, inbox.parts, inbox.simId, inbox.simLabel,
                        inbox.subscriptionId, inbox.slotIndex, "received", System.currentTimeMillis(), inbox.archiveSource
                    )
                }
                if (messageId != null) db.insertWithOnConflict("sms_redactions", null, ContentValues().apply {
                    put("message_id", messageId); put("created_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL("UPDATE events SET acked=1,payload='{}' WHERE event_id=?", arrayOf(id))
                db.execSQL("UPDATE inbox SET sender='',recipient='',body='' WHERE event_id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun pendingSmsRedactions(): List<String> = readableDatabase.rawQuery(
        "SELECT message_id FROM sms_redactions ORDER BY created_at LIMIT 100", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun completeSmsRedaction(messageId: String) {
        writableDatabase.delete("sms_redactions", "message_id=?", arrayOf(messageId))
    }

    /** The opt-in is persisted beside the archive table and defaults to off on every install. */
    fun smsArchiveRetentionEnabled(): Boolean = readableDatabase.rawQuery(
        "SELECT enabled FROM sms_archive_options WHERE singleton_id=1", null
    ).use { it.moveToFirst() && it.getInt(0) != 0 }

    fun setSmsArchiveRetentionEnabled(enabled: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE sms_archive_options SET enabled=? WHERE singleton_id=1", arrayOf(if (enabled) 1 else 0))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Stream one consistent, read-only snapshot without holding SQLite's writer transaction. */
    fun smsArchiveSnapshot(): Sequence<SmsArchiveRecord> {
        // Ensure first-install creation/migrations have run before opening the independent reader.
        readableDatabase
        val identity = archiveIdentity(appContext)
        val readOnly = SQLiteDatabase.openDatabase(
            appContext.getDatabasePath("gateway-data.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        return SmsArchiveSnapshotSequence(readOnly, identity)
    }

    private data class ArchiveIdentity(val currentOwner: String?, val currentSource: String)
    private data class IncomingArchiveData(
        val sender: String, val recipient: String, val body: String, val receivedAt: Long,
        val parts: Int, val simId: String?, val subscriptionId: Int, val slotIndex: Int,
        val archiveGatewayId: String?, val archiveSource: String, val simLabel: String?
    )

    private inner class SmsArchiveSnapshotSequence(
        private val db: SQLiteDatabase,
        private val identity: ArchiveIdentity
    ) : Sequence<SmsArchiveRecord>, Closeable {
        private var iteratorCreated = false
        private var cursor: Cursor? = null
        private var closed = false
        private var prefetched: SmsArchiveRecord? = null
        private var hasPrefetched = false

        override fun iterator(): Iterator<SmsArchiveRecord> {
            check(!iteratorCreated) { "Gateway archive snapshot is a one-shot sequence" }
            check(!closed) { "Gateway archive snapshot is closed" }
            iteratorCreated = true
            try {
                cursor = db.rawQuery(ARCHIVE_SNAPSHOT_QUERY, null)
            } catch (error: Throwable) {
                close()
                throw error
            }
            return object : Iterator<SmsArchiveRecord> {
                override fun hasNext(): Boolean = advance()
                override fun next(): SmsArchiveRecord {
                    if (!advance()) throw NoSuchElementException()
                    hasPrefetched = false
                    return checkNotNull(prefetched).also { prefetched = null }
                }
            }
        }

        private fun advance(): Boolean {
            if (hasPrefetched) return true
            if (closed) return false
            try {
                if (Thread.currentThread().isInterrupted) {
                    throw java.util.concurrent.CancellationException("Archive snapshot cancelled")
                }
                val current = checkNotNull(cursor)
                if (!current.moveToNext()) {
                    close()
                    return false
                }
                prefetched = when (current.getInt(18)) {
                    0 -> current.toSmsArchiveRecord()
                    1 -> archiveRecord(
                        identity, current.getStringOrNull(3), current.getStringOrNull(20),
                        current.getString(5), "inbound", current.getStringOrNull(9),
                        current.getStringOrNull(10), current.getString(11), current.getLong(12),
                        current.getString(13), current.getIntOrNull(17), current.getStringOrNull(6),
                        current.getStringOrNull(7), current.getIntOrNull(16), current.getIntOrNull(15),
                        current.getLong(14), current.getStringOrNull(1)
                    )
                    else -> archiveRecord(
                        identity, current.getStringOrNull(3), current.getStringOrNull(20),
                        current.getString(5), "outbound", null, current.getStringOrNull(10),
                        current.getString(11), current.getLong(12), current.getString(13),
                        current.getIntOrNull(17), current.getStringOrNull(6), null, null, null,
                        current.getLong(14), current.getStringOrNull(1)
                    )
                }
                hasPrefetched = true
                return true
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { cursor?.close() }
            cursor = null
            runCatching { db.close() }
        }
    }

    /** Capture identity before opening a DB transaction to avoid a credential-lock/DB-lock cycle. */
    private fun archiveIdentity(context: Context): ArchiveIdentity {
        val session = runCatching { CredentialStore.load(context) }.getOrNull()
        return ArchiveIdentity(session?.gatewayId, session?.let { sanitizedApiSource(it.controlBaseUrl) }
            ?: "gsm2sip:server-api:unknown")
    }

    private fun archiveSourceFor(
        identity: ArchiveIdentity,
        ownerGatewayId: String?,
        activeDatabaseOwner: String?
    ): String = if (ownerGatewayId != null && ownerGatewayId == identity.currentOwner &&
        ownerGatewayId == activeDatabaseOwner) identity.currentSource
    else "gsm2sip:server-api:unknown"

    /** Only the API origin is source metadata. Userinfo, paths, queries, and fragments are omitted. */
    private fun sanitizedApiSource(raw: String): String {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
            ?: return "gsm2sip:server-api:unknown"
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" }
            ?: return "gsm2sip:server-api:unknown"
        val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase()
            ?: return "gsm2sip:server-api:unknown"
        val authority = buildString {
            if (host.contains(':') && !host.startsWith("[")) append('[').append(host).append(']') else append(host)
            if (uri.port >= 0) append(":${uri.port}")
        }
        return "gsm2sip:server-api:$scheme://$authority"
    }

    private fun archiveRecord(
        identity: ArchiveIdentity,
        ownerGatewayId: String?,
        activeDatabaseOwner: String?,
        messageId: String,
        direction: String,
        from: String?,
        to: String?,
        body: String,
        createdAt: Long,
        status: String,
        partCount: Int?,
        simId: String?,
        simLabel: String?,
        subscriptionId: Int?,
        slotIndex: Int?,
        observedAt: Long,
        sourceOverride: String? = null,
    ): SmsArchiveRecord {
        val source = sourceOverride ?: archiveSourceFor(identity, ownerGatewayId, activeDatabaseOwner)
        return SmsArchiveRecord(
            id = SmsArchiveCodec.stableId(source, null, ownerGatewayId, "$direction:$messageId"),
            source = source,
            ownerId = null,
            gatewayId = ownerGatewayId,
            messageId = messageId,
            simId = simId,
            simLabel = simLabel,
            direction = direction,
            from = from,
            to = to,
            body = body,
            createdAt = createdAt,
            status = status,
            observedAt = observedAt,
            slotIndex = slotIndex?.takeIf { it >= 0 },
            subscriptionId = subscriptionId?.takeIf { it >= 0 },
            partCount = partCount,
        )
    }

    private fun archiveInbound(
        db: SQLiteDatabase,
        identity: ArchiveIdentity,
        ownerId: String?,
        messageId: String,
        sender: String,
        recipient: String,
        body: String,
        receivedAt: Long,
        partCount: Int,
        simId: String?,
        simLabel: String?,
        subscriptionId: Int?,
        slotIndex: Int?,
        status: String,
        observedAt: Long,
        sourceOverride: String? = null,
    ) = bestEffortArchive {
        upsertArchiveRecord(db, archiveRecord(
            identity, ownerId, activeOwnerFor(db), messageId, "inbound", sender, recipient, body, receivedAt, status,
            partCount, simId, simLabel, subscriptionId, slotIndex, observedAt, sourceOverride
        ))
    }

    private fun archiveOutbound(
        db: SQLiteDatabase,
        identity: ArchiveIdentity,
        ownerId: String?,
        messageId: String,
        sender: String?,
        recipient: String,
        body: String,
        createdAt: Long,
        status: String,
        partCount: Int,
        simId: String?,
        simLabel: String?,
        subscriptionId: Int?,
        slotIndex: Int?,
        observedAt: Long,
        sourceOverride: String? = null,
    ) = bestEffortArchive {
        upsertArchiveRecord(db, archiveRecord(
            identity, ownerId, activeOwnerFor(db), messageId, "outbound", sender, recipient, body, createdAt, status,
            partCount, simId, simLabel, subscriptionId, slotIndex, observedAt, sourceOverride
        ))
    }

    private inline fun bestEffortArchive(write: () -> Unit) {
        try {
            write()
        } catch (error: Exception) {
            // Retention is a side archive. It must not hold up ACK handling or command dispatch.
            Log.w(TAG, "Could not update retained SMS archive", error)
        }
    }

    /** Existing rows keep their first body and immutable origin; later transitions only advance status. */
    private fun upsertArchiveRecord(db: SQLiteDatabase, record: SmsArchiveRecord) {
        val priorQuery = if (record.gatewayId == null) {
            db.rawQuery(
                "SELECT id,observed_at FROM sms_archive_history WHERE owner_gateway_id IS NULL AND message_id=? AND direction=? LIMIT 1",
                arrayOf(record.messageId, record.direction)
            )
        } else {
            db.rawQuery(
                "SELECT id,observed_at FROM sms_archive_history WHERE owner_gateway_id=? AND message_id=? AND direction=? LIMIT 1",
                arrayOf(record.gatewayId, record.messageId, record.direction)
            )
        }
        val prior = priorQuery.use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null }
            ?: if (record.gatewayId != null) db.rawQuery(
                "SELECT id,observed_at FROM sms_archive_history WHERE owner_gateway_id IS NULL AND source='gsm2sip:server-api:unknown' AND message_id=? AND direction=? LIMIT 1",
                arrayOf(record.messageId, record.direction)
            ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null } else null
        if (prior != null) {
            if (record.observedAt >= prior.second) db.update("sms_archive_history", ContentValues().apply {
                put("status", record.status); put("observed_at", record.observedAt)
                put("part_count", record.partCount)
            }, "id=?", arrayOf(prior.first))
            return
        }
        // A blank Command.text from a body already cleared by the legacy retention boundary is
        // not recoverable. Never create a misleading empty archive row for it.
        if (record.direction == "outbound" && record.body.isEmpty()) return
        val enabled = db.rawQuery(
            "SELECT enabled FROM sms_archive_options WHERE singleton_id=1", null
        ).use { it.moveToFirst() && it.getInt(0) != 0 }
        if (!enabled) return
        db.insertOrThrow("sms_archive_history", null, record.toContentValues())
    }

    private fun archiveExistingInbox(db: SQLiteDatabase, identity: ArchiveIdentity, eventId: String) {
        try {
            val data = db.rawQuery(
                """SELECT i.message_id,i.sender,i.recipient,i.body,i.received_at,i.parts,i.sim_id,
                    i.sub_id,i.slot_index,i.archive_gateway_id,i.archive_source,i.archive_sim_label
                    FROM inbox i JOIN events e ON e.event_id=i.event_id
                    WHERE i.event_id=?""", arrayOf(eventId)
            ).use { c -> if (!c.moveToFirst()) null else ExistingInboxArchive(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4),
                c.getInt(5), c.getStringOrNull(6), c.getInt(7), c.getInt(8), c.getStringOrNull(9),
                c.getString(10), c.getStringOrNull(11)
            ) } ?: return
            if (data.body.isNotEmpty()) archiveInbound(
                db, identity, data.archiveGatewayId, data.messageId, data.sender, data.recipient, data.body,
                data.receivedAt, data.parts, data.simId, data.simLabel, data.subscriptionId, data.slotIndex,
                "received", System.currentTimeMillis(), data.archiveSource
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not archive recovered SMS", error)
        }
    }

    private data class ExistingInboxArchive(
        val messageId: String, val sender: String, val recipient: String, val body: String,
        val receivedAt: Long, val parts: Int, val simId: String?, val subscriptionId: Int,
        val slotIndex: Int, val archiveGatewayId: String?, val archiveSource: String, val simLabel: String?
    )

    private fun SmsArchiveRecord.toContentValues() = ContentValues().apply {
        put("id", id); put("source", source); putNull("owner_id"); put("gateway_id", gatewayId)
        put("owner_gateway_id", gatewayId)
        put("message_id", messageId); put("sim_id", simId); put("sim_label", simLabel)
        put("direction", direction); put("from_address", from); put("to_address", to); put("body", body)
        put("created_at", createdAt); put("status", status); put("observed_at", observedAt)
        if (slotIndex == null) putNull("slot_index") else put("slot_index", slotIndex)
        if (subscriptionId == null) putNull("subscription_id") else put("subscription_id", subscriptionId)
        if (partCount == null) putNull("part_count") else put("part_count", partCount)
    }

    private fun Cursor.toSmsArchiveRecord() = SmsArchiveRecord(
        id = getString(0), source = getString(1), ownerId = getStringOrNull(2),
        gatewayId = getStringOrNull(3), messageId = getStringOrNull(5), simId = getStringOrNull(6),
        simLabel = getStringOrNull(7), direction = getString(8), from = getStringOrNull(9),
        to = getStringOrNull(10), body = getString(11), createdAt = getLong(12),
        status = getString(13), observedAt = getLong(14), slotIndex = getIntOrNull(15),
        subscriptionId = getIntOrNull(16), partCount = getIntOrNull(17)
    )

    private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    fun nextSequence(): Long = readableDatabase.rawQuery(
        "SELECT value FROM meta WHERE key='sequence'", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun nextHeartbeatSequence(): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE meta SET value=value+1 WHERE key='heartbeat_sequence'")
            val next = db.rawQuery("SELECT value FROM meta WHERE key='heartbeat_sequence'", null)
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            db.setTransactionSuccessful()
            return next
        } finally { db.endTransaction() }
    }

    /** Allocate and persist one exact heartbeat payload. If the HTTP response
     * was lost, the next loop retries the identical sequence and bytes. */
    fun prepareHeartbeatPayload(gatewayId: String, build: (Long) -> String): Pair<Long, String> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (activeOwnerFor(db) != gatewayId) throw IllegalStateException("Gateway identity changed")
            val pending = db.rawQuery(
                "SELECT sequence,payload FROM heartbeat_pending WHERE owner_gateway_id=? LIMIT 1",
                arrayOf(gatewayId)
            ).use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
            if (pending != null) {
                db.setTransactionSuccessful()
                return pending
            }
            db.execSQL("UPDATE meta SET value=value+1 WHERE key='heartbeat_sequence'")
            val sequence = db.rawQuery("SELECT value FROM meta WHERE key='heartbeat_sequence'", null)
                .use { it.moveToFirst(); it.getLong(0) }
            val payload = build(sequence)
            db.insertOrThrow("heartbeat_pending", null, ContentValues().apply {
                put("sequence", sequence); put("owner_gateway_id", gatewayId); put("payload", payload)
            })
            db.setTransactionSuccessful()
            return sequence to payload
        } finally { db.endTransaction() }
    }

    fun completeHeartbeat(gatewayId: String, sequence: Long) {
        writableDatabase.delete("heartbeat_pending", "owner_gateway_id=? AND sequence=?",
            arrayOf(gatewayId, sequence.toString()))
    }

    fun discardHeartbeat(gatewayId: String, sequence: Long) = completeHeartbeat(gatewayId, sequence)

    fun saveSimProposal(operationId: String, payload: String) {
        writableDatabase.insertWithOnConflict("sim_proposals", null, ContentValues().apply {
            put("operation_id", operationId); put("payload", payload); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pendingSimProposal(): Pair<String, String>? = readableDatabase.rawQuery(
        "SELECT operation_id,payload FROM sim_proposals ORDER BY updated_at DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }

    fun clearSimProposal(operationId: String) {
        writableDatabase.delete("sim_proposals", "operation_id=?", arrayOf(operationId))
    }

    /** Commit call identity and dispatching state atomically before Telecom or
     * SIP work. Existing IDs, including recovered unknown rows, cannot dispatch again. */
    fun beginCallDispatch(callId: String, simId: String, mappingRevision: Long, direction: String): Boolean {
        require(direction == "incoming" || direction == "outgoing")
        val now = System.currentTimeMillis()
        val owner = activeGatewayId()
        return writableDatabase.insertWithOnConflict("call_ledger", null, ContentValues().apply {
            put("call_id", callId); put("sim_id", simId); put("mapping_revision", mappingRevision)
            put("direction", direction); put("state", "dispatching"); put("owner_gateway_id", owner)
            put("created_at", now); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun activeGatewayId(): String? = readableDatabase.rawQuery(
        "SELECT value FROM gateway_identity WHERE key='gateway_id'", null
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun isActiveGateway(gatewayId: String): Boolean = activeGatewayId() == gatewayId

    fun markCallTerminal(callId: String, state: String): Boolean {
        require(state in setOf("completed", "failed", "unknown", "cancelled"))
        val values = ContentValues().apply { put("state", state); put("updated_at", System.currentTimeMillis()) }
        return writableDatabase.update("call_ledger", values,
            "call_id=? AND state='dispatching'", arrayOf(callId)) == 1
    }

    fun recoverDispatchingCallsToUnknown(): Int {
        val values = ContentValues().apply { put("state", "unknown"); put("updated_at", System.currentTimeMillis()) }
        return writableDatabase.update("call_ledger", values, "state='dispatching'", null)
    }

    /** Persist an HTTP-claimed command before any modem interaction. */
    fun insertClaimedCommand(command: Command, requiredGatewayId: String): InsertCommandResult {
        if (command.ownerGatewayId != requiredGatewayId || !isActiveGateway(requiredGatewayId)) {
            return InsertCommandResult.CONFLICT
        }
        val archiveIdentity = archiveIdentity(appContext)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prior = db.rawQuery(
                "SELECT message_id,payload_hash,owner_gateway_id FROM commands WHERE command_id=? OR message_id=? LIMIT 1",
                arrayOf(command.commandId, command.messageId)
            )
            if (prior.moveToFirst()) {
                val same = prior.getString(0) == command.messageId && prior.getString(1) == command.payloadHash &&
                    prior.getString(2) == requiredGatewayId
                prior.close()
                db.setTransactionSuccessful()
                return if (same) InsertCommandResult.DUPLICATE else InsertCommandResult.CONFLICT
            }
            prior.close()
            val now = System.currentTimeMillis()
            val archiveSource = archiveSourceFor(archiveIdentity, requiredGatewayId, activeOwnerFor(db))
            val values = ContentValues().apply {
                put("command_id", command.commandId); put("message_id", command.messageId)
                put("sim_id", command.simId); put("mapping_revision", command.mappingRevision)
                put("recipient", command.to); put("body", command.text)
                put("part_count", command.partCount); put("expires_at", command.expiresAt)
                put("state", "claimed"); put("payload_hash", command.payloadHash)
                put("owner_gateway_id", requiredGatewayId)
                put("archive_source", archiveSource)
                put("created_at", now); put("updated_at", now)
            }
            db.insertOrThrow("commands", null, values)
            archiveOutbound(
                db, archiveIdentity, requiredGatewayId, command.messageId, null, command.to,
                command.text, now, "claimed", command.partCount, command.simId, null,
                null, null, now, archiveSource
            )
            db.setTransactionSuccessful()
            return InsertCommandResult.INSERTED
        } finally { db.endTransaction() }
    }

    fun command(commandId: String, requiredGatewayId: String? = null): Command? = readableDatabase.rawQuery(
        "SELECT command_id,message_id,sim_id,mapping_revision,recipient,body,part_count,expires_at,state,payload_hash,owner_gateway_id,created_at,archive_source FROM commands WHERE command_id=?" +
            if (requiredGatewayId == null) "" else " AND owner_gateway_id=?",
        if (requiredGatewayId == null) arrayOf(commandId) else arrayOf(commandId, requiredGatewayId)
    ).use { c -> if (!c.moveToFirst()) null else Command(
        c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
        c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getInt(6),
        c.getLong(7), c.getString(8), c.getString(9), c.getStringOrNull(10), c.getLong(11), c.getString(12)
    ) }

    fun commandForMessage(messageId: String, requiredGatewayId: String? = null): Command? = readableDatabase.rawQuery(
        "SELECT command_id,message_id,sim_id,mapping_revision,recipient,body,part_count,expires_at,state,payload_hash,owner_gateway_id,created_at,archive_source FROM commands WHERE message_id=?" +
            if (requiredGatewayId == null) "" else " AND owner_gateway_id=?",
        if (requiredGatewayId == null) arrayOf(messageId) else arrayOf(messageId, requiredGatewayId)
    ).use { c -> if (!c.moveToFirst()) null else Command(
        c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
        c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getInt(6), c.getLong(7),
        c.getString(8), c.getString(9), c.getStringOrNull(10), c.getLong(11), c.getString(12)
    ) }

    /** Set SmsManager.divideMessage's count before crossing the durable
     * dispatch boundary. A server may omit count because it cannot know SIM encoding. */
    fun prepareCommandParts(commandId: String, partCount: Int): Boolean {
        if (partCount !in 1..255) return false
        val existing = command(commandId) ?: return false
        if (existing.state != "claimed") return false
        if (existing.partCount == partCount) return true
        if (existing.partCount != 0) return false
        val values = ContentValues().apply { put("part_count", partCount); put("updated_at", System.currentTimeMillis()) }
        return writableDatabase.update("commands", values,
            "command_id=? AND state='claimed' AND part_count=0", arrayOf(commandId)) == 1
    }

    /** Atomic one-way transition. Once dispatching is committed, reboot
     * recovery marks the result unknown and never submits the same SMS again. */
    fun beginDispatch(commandId: String): DispatchStartResult {
        val archiveIdentity = archiveIdentity(appContext)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val command = command(commandId) ?: return DispatchStartResult.NOT_READY
            if (command.state != "claimed" || command.partCount < 1 || command.expiresAt <= System.currentTimeMillis()) return DispatchStartResult.NOT_READY
            val owner = command.ownerGatewayId ?: return DispatchStartResult.NOT_READY
            if (activeOwnerFor(db) != owner) return DispatchStartResult.NOT_READY
            val now = System.currentTimeMillis()
            val alreadyCounted = db.rawQuery("SELECT 1 FROM sms_dispatch_budget WHERE command_id=?", arrayOf(commandId))
                .use { it.moveToFirst() }
            if (alreadyCounted) return DispatchStartResult.NOT_READY
            val windowCount = db.rawQuery(
                "SELECT COUNT(*) FROM sms_dispatch_budget WHERE owner_gateway_id=? AND sim_id=? AND dispatched_at>?",
                arrayOf(owner, command.simId, (now - 60_000L).toString())
            ).use { it.moveToFirst(); it.getInt(0) }
            val hourCount = db.rawQuery(
                "SELECT COUNT(*) FROM sms_dispatch_budget WHERE owner_gateway_id=? AND sim_id=? AND dispatched_at>?",
                arrayOf(owner, command.simId, (now - 3_600_000L).toString())
            ).use { it.moveToFirst(); it.getInt(0) }
            if (windowCount >= SMS_PER_MINUTE_LIMIT || hourCount >= SMS_PER_HOUR_LIMIT) {
                db.setTransactionSuccessful()
                return DispatchStartResult.RATE_LIMITED
            }
            db.insertOrThrow("sms_dispatch_budget", null, ContentValues().apply {
                put("command_id", commandId); put("owner_gateway_id", owner)
                put("sim_id", command.simId); put("dispatched_at", now)
            })
            val values = ContentValues().apply { put("state", "dispatching"); put("updated_at", System.currentTimeMillis()) }
            if (db.update("commands", values, "command_id=? AND state='claimed'", arrayOf(commandId)) != 1) return DispatchStartResult.NOT_READY
            for (i in 0 until command.partCount.coerceAtLeast(1)) {
                db.insertWithOnConflict("command_parts", null, ContentValues().apply {
                    put("command_id", commandId); put("part_index", i); put("state", "dispatching")
                    put("updated_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            val dispatchPayload = JSONObject().put("message_id", command.messageId)
                .put("command_id", command.commandId).put("part_count", command.partCount).toString()
            insertEvent(db, UUID.randomUUID().toString(), System.currentTimeMillis(),
                "sms.dispatching", command.simId, command.mappingRevision, dispatchPayload, command.ownerGatewayId)
            archiveOutbound(
                db, archiveIdentity, command.ownerGatewayId, command.messageId, null, command.to,
                command.text, command.createdAt, "dispatching",
                command.partCount, command.simId, null, null, null, now, command.archiveSource
            )
            db.setTransactionSuccessful()
            return DispatchStartResult.STARTED
        } finally { db.endTransaction() }
    }

    fun commandSmsAggregate(commandId: String): SmsAggregate? {
        val row = readableDatabase.rawQuery("SELECT state,part_count FROM commands WHERE command_id=?", arrayOf(commandId))
            .use { if (!it.moveToFirst()) null else it.getString(0) to it.getInt(1) } ?: return null
        return SmsAggregate(row.first, row.second)
    }

    /** Record a per-part status exactly once. Duplicate Android callbacks do
     * not add events or advance aggregate counts. */
    fun recordPartState(
        commandId: String,
        partIndex: Int,
        state: String,
        resultCode: Int? = null,
        error: String = "",
        smsc: String = ""
    ): Boolean {
        val allowed = setOf("submitted", "delivered", "failed", "unknown", "expired")
        require(state in allowed)
        val archiveIdentity = archiveIdentity(appContext)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val command = command(commandId) ?: return false
            val part = db.rawQuery(
                "SELECT state FROM command_parts WHERE command_id=? AND part_index=?",
                arrayOf(commandId, partIndex.toString())
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (part == null || !canAdvance(part, state)) return false
            db.execSQL(
                "UPDATE command_parts SET state=?,sent_result_code=?,delivery_status=?,error=?,smsc=?,updated_at=? WHERE command_id=? AND part_index=?",
                arrayOf(state, resultCode, if (state == "delivered") "delivered" else "", error, smsc,
                    System.currentTimeMillis(), commandId, partIndex)
            )
            addPartEvent(db, command, partIndex, state, resultCode, error)
            val states = db.rawQuery("SELECT state FROM command_parts WHERE command_id=?", arrayOf(commandId))
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val terminal = when {
                states.any { it == "dispatching" } -> "dispatching"
                states.any { it == "unknown" } -> "unknown"
                states.any { it == "failed" || it == "expired" } -> "failed"
                states.isNotEmpty() && states.all { it == "delivered" } -> "delivered"
                states.isNotEmpty() && states.all { it == "submitted" || it == "delivered" } -> "submitted"
                else -> "unknown"
            }
            db.execSQL("UPDATE commands SET state=?,updated_at=? WHERE command_id=?",
                arrayOf(terminal, System.currentTimeMillis(), commandId))
            if (terminal != "dispatching") {
                db.execSQL("INSERT OR REPLACE INTO tombstones(entity_id,kind,final_state,created_at) VALUES(?,?,?,?)",
                    arrayOf(command.messageId, "sms.command", terminal, System.currentTimeMillis()))
                // Body may be removed after callbacks are durably represented by journal events.
                db.execSQL("UPDATE commands SET recipient=NULL,body=NULL WHERE command_id=?", arrayOf(commandId))
            }
            archiveOutbound(
                db, archiveIdentity, command.ownerGatewayId, command.messageId, null, command.to,
                command.text, command.createdAt, terminal, command.partCount, command.simId,
                null, null, null, System.currentTimeMillis(), command.archiveSource
            )
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** At process startup, any dispatching command has crossed the durable
     * handoff boundary. Whether binder reached the modem cannot be known. */
    fun recoverUnknownDispatches(): Int {
        val archiveIdentity = archiveIdentity(appContext)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val rows = mutableListOf<String>()
            db.rawQuery("SELECT command_id FROM commands WHERE state='dispatching'", null).use { c ->
                while (c.moveToNext()) rows += c.getString(0)
            }
            rows.forEach { id ->
                val cmd = command(id) ?: return@forEach
                val unresolved = db.rawQuery("SELECT part_index FROM command_parts WHERE command_id=? AND state='dispatching'",
                    arrayOf(id)).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
                db.execSQL("UPDATE command_parts SET state='unknown',updated_at=? WHERE command_id=? AND state='dispatching'",
                    arrayOf(System.currentTimeMillis(), id))
                db.execSQL("UPDATE commands SET state='unknown',updated_at=?,recipient=NULL,body=NULL WHERE command_id=?",
                    arrayOf(System.currentTimeMillis(), id))
                unresolved.forEach { addPartEvent(db, cmd, it, "unknown", null, "process_restarted_during_dispatch") }
                db.execSQL("INSERT OR REPLACE INTO tombstones(entity_id,kind,final_state,created_at) VALUES(?,?,?,?)",
                    arrayOf(cmd.messageId, "sms.command", "unknown", System.currentTimeMillis()))
                archiveOutbound(
                    db, archiveIdentity, cmd.ownerGatewayId, cmd.messageId, null, cmd.to,
                    cmd.text, cmd.createdAt, "unknown", cmd.partCount, cmd.simId,
                    null, null, null, System.currentTimeMillis(), cmd.archiveSource
                )
            }
            db.setTransactionSuccessful()
            return rows.size
        } finally { db.endTransaction() }
    }

    fun markCommandTerminal(commandId: String, state: String, error: String = ""): Boolean {
        require(state in setOf("failed", "expired", "unknown"))
        val archiveIdentity = archiveIdentity(appContext)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val c = command(commandId) ?: return false
            if (c.state in setOf("dispatching", "submitted", "delivered", "failed", "expired", "unknown")) return false
            db.execSQL("UPDATE commands SET state=?,last_error=?,updated_at=?,recipient=NULL,body=NULL WHERE command_id=?",
                arrayOf(state, error, System.currentTimeMillis(), commandId))
            db.execSQL("INSERT OR REPLACE INTO tombstones(entity_id,kind,final_state,created_at) VALUES(?,?,?,?)",
                arrayOf(c.messageId, "sms.command", state, System.currentTimeMillis()))
            val payload = JSONObject().put("message_id", c.messageId).put("command_id", c.commandId)
                .put("state", state).apply { if (error.isNotEmpty()) put("error", error) }.toString()
            insertEvent(db, UUID.randomUUID().toString(), System.currentTimeMillis(), "sms.command_state",
                c.simId, c.mappingRevision, payload, c.ownerGatewayId)
            archiveOutbound(
                db, archiveIdentity, c.ownerGatewayId, c.messageId, null, c.to,
                c.text, c.createdAt, state, c.partCount, c.simId, null,
                null, null, System.currentTimeMillis(), c.archiveSource
            )
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Hash the exact command identity fields before storing/dispatching it. */
    fun payloadHash(commandId: String, messageId: String, simId: String, mappingRevision: Long,
                    recipient: String, text: String, expiresAt: Long): String {
        val bytes = listOf(commandId, messageId, simId, mappingRevision.toString(), recipient, text, expiresAt.toString())
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun serverCommandHash(commandId: String, messageId: String, gatewayId: String, simId: String,
                          mappingRevision: Long, recipient: String, text: String, expiresAtWire: String): String {
        require(listOf(commandId, messageId, gatewayId, simId, recipient, text, expiresAtWire).none { '\u0000' in it })
        val bytes = listOf(commandId, messageId, gatewayId, simId, mappingRevision.toString(),
            recipient, text, expiresAtWire).joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun addPartEvent(db: SQLiteDatabase, command: Command, part: Int,
                             state: String, resultCode: Int?, error: String) {
        val payload = JSONObject().put("message_id", command.messageId)
            .put("command_id", command.commandId).put("part_index", part).put("state", state)
            .apply {
                if (resultCode != null) put("result_code", resultCode)
                if (error.isNotEmpty()) put("error", error)
            }
            .toString()
        insertEvent(db, UUID.randomUUID().toString(), System.currentTimeMillis(),
            "sms.part_state", command.simId, command.mappingRevision, payload, command.ownerGatewayId)
    }

    private fun insertEvent(db: SQLiteDatabase, id: String, at: Long, type: String,
                            simId: String?, revision: Long?, payload: String,
                            ownerGatewayId: String? = activeOwnerFor(db)): Event {
        db.execSQL("UPDATE meta SET value=value+1 WHERE key='sequence'")
        val seq = db.rawQuery("SELECT value FROM meta WHERE key='sequence'", null).use { it.moveToFirst(); it.getLong(0) }
        val v = ContentValues().apply {
            put("event_id", id); put("sequence", seq); put("occurred_at", at); put("type", type)
            if (simId == null) putNull("sim_id") else put("sim_id", simId)
            if (revision == null) putNull("mapping_revision") else put("mapping_revision", revision)
            put("payload", payload); put("created_at", System.currentTimeMillis())
        }
        val owner = ownerGatewayId
        if (owner == null) v.putNull("owner_gateway_id") else v.put("owner_gateway_id", owner)
        db.insertOrThrow("events", null, v)
        return Event(id, seq, at, type, simId, revision, payload, owner)
    }

    private fun activeOwnerFor(db: SQLiteDatabase): String? = db.rawQuery(
        "SELECT value FROM gateway_identity WHERE key='gateway_id'", null
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun getEvent(db: SQLiteDatabase, id: String): Event? = db.rawQuery(
        "SELECT event_id,sequence,occurred_at,type,sim_id,mapping_revision,payload,owner_gateway_id FROM events WHERE event_id=?",
        arrayOf(id)
    ).use { c -> if (!c.moveToFirst()) null else Event(c.getString(0), c.getLong(1), c.getLong(2),
        c.getString(3), c.getStringOrNull(4), c.getLongOrNull(5), c.getString(6), c.getStringOrNull(7)) }

    private fun canAdvance(old: String, next: String): Boolean = when (old) {
        "dispatching" -> next in setOf("submitted", "delivered", "failed", "unknown", "expired")
        "submitted" -> next in setOf("delivered", "failed", "unknown")
        "unknown" -> next in setOf("submitted", "delivered", "failed")
        else -> false
    }

    companion object {
        private const val SMS_PER_MINUTE_LIMIT = 5
        private const val SMS_PER_HOUR_LIMIT = 30
        private const val TAG = "GatewayDatabase"
        private const val VERSION = 6
        private const val ARCHIVE_COLUMNS = "id,source,owner_id,gateway_id,owner_gateway_id,message_id,sim_id,sim_label," +
            "direction,from_address,to_address,body,created_at,status,observed_at,slot_index,subscription_id,part_count"
        private val ARCHIVE_SNAPSHOT_QUERY = """
            SELECT id,source,owner_id,gateway_id,owner_gateway_id,message_id,sim_id,sim_label,
                direction,from_address,to_address,body,created_at,status,observed_at,slot_index,
                subscription_id,part_count,0 AS row_kind,owner_gateway_id AS row_owner,
                (SELECT value FROM gateway_identity WHERE key='gateway_id') AS database_owner
            FROM sms_archive_history
            UNION ALL
            SELECT NULL,i.archive_source,NULL,i.archive_gateway_id,i.archive_gateway_id,
                i.message_id,i.sim_id,i.archive_sim_label,
                'inbound',i.sender,i.recipient,i.body,i.received_at,'received',e.created_at,
                CASE WHEN i.slot_index>=0 THEN i.slot_index ELSE NULL END,
                CASE WHEN i.sub_id>=0 THEN i.sub_id ELSE NULL END,
                i.parts,1 AS row_kind,i.archive_gateway_id AS row_owner,
                (SELECT value FROM gateway_identity WHERE key='gateway_id') AS database_owner
            FROM inbox i JOIN events e ON e.event_id=i.event_id
            WHERE e.acked=0 AND i.body!=''
                AND NOT EXISTS (SELECT 1 FROM sms_archive_history h
                    WHERE h.message_id=i.message_id AND h.direction='inbound'
                    AND (h.owner_gateway_id IS i.archive_gateway_id OR
                        (h.owner_gateway_id IS NULL AND h.source='gsm2sip:server-api:unknown')))
            UNION ALL
            SELECT NULL,c.archive_source,NULL,c.owner_gateway_id,c.owner_gateway_id,c.message_id,c.sim_id,NULL,
                'outbound',NULL,c.recipient,c.body,c.created_at,c.state,c.updated_at,
                NULL,NULL,c.part_count,2 AS row_kind,c.owner_gateway_id AS row_owner,
                (SELECT value FROM gateway_identity WHERE key='gateway_id') AS database_owner
            FROM commands c WHERE c.recipient IS NOT NULL AND c.body IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM sms_archive_history h
                    WHERE h.message_id=c.message_id AND h.direction='outbound'
                    AND (h.owner_gateway_id IS c.owner_gateway_id OR
                        (h.owner_gateway_id IS NULL AND h.source='gsm2sip:server-api:unknown')))
            ORDER BY created_at,message_id,id
        """.trimIndent()
        @Volatile private var instance: GatewayDatabase? = null
        fun get(context: Context): GatewayDatabase = instance ?: synchronized(this) {
            instance ?: GatewayDatabase(context).also { instance = it }
        }
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)
