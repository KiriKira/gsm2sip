package com.callagent.gateway.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import com.callagent.gateway.sms.SmsProviderRecovery
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
        val ownerGatewayId: String? = null
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
        val resolution: String = "unknown"
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
            source TEXT NOT NULL DEFAULT 'broadcast',
            FOREIGN KEY(event_id) REFERENCES events(event_id)
        )""")
        db.execSQL("""CREATE TABLE commands (
            command_id TEXT PRIMARY KEY, message_id TEXT NOT NULL UNIQUE,
            sim_id TEXT NOT NULL, mapping_revision INTEGER NOT NULL,
            recipient TEXT, body TEXT, part_count INTEGER NOT NULL,
            expires_at INTEGER NOT NULL, state TEXT NOT NULL,
            payload_hash TEXT NOT NULL, owner_gateway_id TEXT,
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
        source: String = "broadcast"
    ): Event {
        require(source == "broadcast" || source == "recovered")
        fun persistForCurrentDatabaseOwner(): Event {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val prior = db.rawQuery("SELECT event_id FROM inbox WHERE message_id=?", arrayOf(messageId))
                if (prior.moveToFirst()) {
                    val event = getEvent(db, prior.getString(0))
                    prior.close()
                    if (event != null) {
                        db.setTransactionSuccessful()
                        return event
                    }
                } else prior.close()

                val eventId = UUID.randomUUID().toString()
                val payload = JSONObject().put("message_id", messageId).put("from", sender)
                    .put("text", body).put("parts", partCount).toString()
                val event = insertEvent(db, eventId, receivedAt, "sms.received", simId,
                    mappingRevision, payload)
                val values = ContentValues().apply {
                    put("message_id", messageId); put("event_id", eventId)
                    put("sender", sender); put("recipient", recipient); put("body", body)
                    put("received_at", receivedAt); put("parts", partCount)
                    put("source", source)
                    if (simId == null) putNull("sim_id") else put("sim_id", simId)
                    if (mappingRevision == null) putNull("mapping_revision") else put("mapping_revision", mappingRevision)
                    put("sub_id", subId); put("slot_index", slotIndex); put("resolution", resolution)
                }
                db.insertOrThrow("inbox", null, values)
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
                } else {
                    messageId = providerMessageId(ownerGatewayId, sessionScope, row.providerIdentity)
                    eventId = UUID.randomUUID().toString()
                    val at = row.receivedAt.takeIf { it > 0 } ?: now
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
                    })
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
        val db = writableDatabase
        db.beginTransaction()
        try {
            eventIds.forEach { id ->
                val messageId = db.rawQuery("SELECT type,payload FROM events WHERE event_id=?", arrayOf(id)).use { c ->
                    if (!c.moveToFirst()) null else {
                        val type = c.getString(0)
                        val payload = runCatching { JSONObject(c.getString(1)) }.getOrNull()
                        if (type in setOf("sms.received", "sms.dispatching", "sms.command_state", "sms.part_state"))
                            payload?.optString("message_id")?.takeIf { it.isNotBlank() }
                        else null
                    }
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
            val values = ContentValues().apply {
                put("command_id", command.commandId); put("message_id", command.messageId)
                put("sim_id", command.simId); put("mapping_revision", command.mappingRevision)
                put("recipient", command.to); put("body", command.text)
                put("part_count", command.partCount); put("expires_at", command.expiresAt)
                put("state", "claimed"); put("payload_hash", command.payloadHash)
                put("owner_gateway_id", requiredGatewayId)
                put("created_at", now); put("updated_at", now)
            }
            db.insertOrThrow("commands", null, values)
            db.setTransactionSuccessful()
            return InsertCommandResult.INSERTED
        } finally { db.endTransaction() }
    }

    fun command(commandId: String, requiredGatewayId: String? = null): Command? = readableDatabase.rawQuery(
        "SELECT command_id,message_id,sim_id,mapping_revision,recipient,body,part_count,expires_at,state,payload_hash,owner_gateway_id FROM commands WHERE command_id=?" +
            if (requiredGatewayId == null) "" else " AND owner_gateway_id=?",
        if (requiredGatewayId == null) arrayOf(commandId) else arrayOf(commandId, requiredGatewayId)
    ).use { c -> if (!c.moveToFirst()) null else Command(
        c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
        c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getInt(6),
        c.getLong(7), c.getString(8), c.getString(9), c.getStringOrNull(10)
    ) }

    fun commandForMessage(messageId: String, requiredGatewayId: String? = null): Command? = readableDatabase.rawQuery(
        "SELECT command_id,message_id,sim_id,mapping_revision,recipient,body,part_count,expires_at,state,payload_hash,owner_gateway_id FROM commands WHERE message_id=?" +
            if (requiredGatewayId == null) "" else " AND owner_gateway_id=?",
        if (requiredGatewayId == null) arrayOf(messageId) else arrayOf(messageId, requiredGatewayId)
    ).use { c -> if (!c.moveToFirst()) null else Command(
        c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
        c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getInt(6), c.getLong(7),
        c.getString(8), c.getString(9), c.getStringOrNull(10)
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
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** At process startup, any dispatching command has crossed the durable
     * handoff boundary. Whether binder reached the modem cannot be known. */
    fun recoverUnknownDispatches(): Int {
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
            }
            db.setTransactionSuccessful()
            return rows.size
        } finally { db.endTransaction() }
    }

    fun markCommandTerminal(commandId: String, state: String, error: String = ""): Boolean {
        require(state in setOf("failed", "expired", "unknown"))
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
        private const val VERSION = 5
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
