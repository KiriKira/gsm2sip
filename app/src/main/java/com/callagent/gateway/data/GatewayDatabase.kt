package com.callagent.gateway.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
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
        resolution: String
    ): Event {
        CredentialStore.load(appContext)?.let { adoptGatewayId(it.gatewayId) }
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
                if (simId == null) putNull("sim_id") else put("sim_id", simId)
                if (mappingRevision == null) putNull("mapping_revision") else put("mapping_revision", mappingRevision)
                put("sub_id", subId); put("slot_index", slotIndex); put("resolution", resolution)
            }
            db.insertOrThrow("inbox", null, values)
            db.setTransactionSuccessful()
            return event
        } finally { db.endTransaction() }
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
        private const val VERSION = 4
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
