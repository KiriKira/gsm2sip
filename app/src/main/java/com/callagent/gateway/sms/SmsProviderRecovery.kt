package com.callagent.gateway.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.sim.SimRegistry
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Best-effort recovery from rows that Android has already retained in its SMS
 * inbox. This never asks for READ_SMS itself; the app UI owns runtime consent.
 */
object SmsProviderRecovery {
    const val MAX_PAGE_SIZE = 120
    const val BROADCAST_MATCH_WINDOW_MS = 10 * 1000L
    const val RECONCILIATION_INTERVAL_MS = 24 * 60 * 60 * 1000L

    enum class HistoryMode { SINCE_ENABLE, FULL_CURRENT_INBOX }
    enum class State {
        READY,
        PERMISSION_REQUIRED,
        NOT_PAIRED,
        NOT_CONFIGURED,
        MORE_PAGES,
        COMPLETE,
        STALE_SCOPE,
        PROVIDER_UNAVAILABLE,
        ERROR
    }

    enum class PermissionState { AVAILABLE, REQUIRED }

    data class Status(
        val state: State,
        val configured: Boolean,
        val fullHistory: Boolean = false,
        val sessionScope: String? = null,
        val scanStartAt: Long? = null,
        val minimumProviderId: Long? = null,
        val lastRowId: Long? = null,
        val reconcileRowId: Long? = null,
        val reconciliationActive: Boolean = false,
        val lastScanAt: Long? = null,
        val importedRows: Long = 0,
        val matchedBroadcasts: Long = 0,
        val lastOutcome: String? = null,
        val detail: String? = null
    )

    data class Preview(
        val state: State,
        val mode: HistoryMode,
        val firstPageRows: Int = 0,
        val moreRows: Boolean = false,
        val earliestReceivedAt: Long? = null,
        val latestReceivedAt: Long? = null,
        val existingRowsExcluded: Boolean = false,
        val detail: String? = null
    )

    data class ScanResult(
        val state: State,
        val scannedRows: Int = 0,
        val importedRows: Int = 0,
        val matchedBroadcasts: Int = 0,
        val duplicateRows: Int = 0,
        val checkpoint: GatewayDatabase.SmsProviderCheckpoint? = null,
        val detail: String? = null
    )

    internal data class RawRow(
        val rowId: Long,
        val sender: String,
        val body: String,
        val date: Long,
        val dateSent: Long,
        val subscriptionId: Int,
        val serviceCenter: String,
        val protocol: Int
    )

    internal interface InboxSource {
        fun maxInboxRowId(): Long
        fun page(
            afterRowId: Long,
            minimumProviderId: Long,
            scanStartAt: Long,
            limit: Int,
            targetMaxRowId: Long? = null
        ): List<RawRow>
    }

    fun permissionState(context: Context): PermissionState =
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
            PermissionState.AVAILABLE else PermissionState.REQUIRED

    /** Call only after an explicit user opt-in. Repeated calls preserve the
     * first checkpoint and do not expand the imported history window. */
    fun configure(
        context: Context,
        mode: HistoryMode = HistoryMode.SINCE_ENABLE,
        expectedGatewayId: String? = null,
        expectedControlBaseUrl: String? = null
    ): Status {
        val session = CredentialStore.load(context) ?: return Status(State.NOT_PAIRED, configured = false)
        if ((expectedGatewayId != null && expectedGatewayId != session.gatewayId) ||
            (expectedControlBaseUrl != null && expectedControlBaseUrl != session.controlBaseUrl)) {
            return Status(State.STALE_SCOPE, configured = false)
        }
        return CredentialStore.withCurrentIdentity(
            context, session.gatewayId, session.controlBaseUrl
        ) { current ->
            val scope = sessionScope(current)
            if (permissionState(context) != PermissionState.AVAILABLE) {
                val existing = GatewayDatabase.get(context).smsProviderCheckpoint(current.gatewayId, scope)
                return@withCurrentIdentity existing?.toStatus(State.PERMISSION_REQUIRED)
                    ?: Status(State.PERMISSION_REQUIRED, configured = false, sessionScope = scope)
            }
            configure(context.applicationContext, current, mode,
                ContentResolverInboxSource(context), System.currentTimeMillis())
        } ?: Status(State.STALE_SCOPE, configured = false, sessionScope = sessionScope(session))
    }

    /** Read-only status for UI. It includes the saved scope and paging progress. */
    fun status(context: Context): Status {
        val session = CredentialStore.load(context) ?: return Status(State.NOT_PAIRED, configured = false)
        val checkpoint = GatewayDatabase.get(context).smsProviderCheckpoint(session.gatewayId, sessionScope(session))
            ?: return Status(State.NOT_CONFIGURED, configured = false, sessionScope = sessionScope(session))
        if (permissionState(context) != PermissionState.AVAILABLE) {
            return checkpoint.toStatus(State.PERMISSION_REQUIRED)
        }
        val currentState = if (checkpoint.reconciliationActive || checkpoint.lastOutcome in
            setOf("reconcile_more", "incremental_more")) State.MORE_PAGES else State.READY
        return checkpoint.toStatus(currentState)
    }

    /**
     * Preview never persists message bodies. The normal choice previews no old
     * rows; full-current-inbox mode shows at most one bounded page count.
     */
    fun preview(
        context: Context,
        mode: HistoryMode = HistoryMode.SINCE_ENABLE,
        pageSize: Int = MAX_PAGE_SIZE
    ): Preview {
        if (permissionState(context) != PermissionState.AVAILABLE)
            return Preview(State.PERMISSION_REQUIRED, mode)
        val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val source = ContentResolverInboxSource(context)
        return try {
            if (mode == HistoryMode.SINCE_ENABLE) {
                Preview(State.READY, mode, existingRowsExcluded = true)
            } else {
                val rows = source.page(0, 0, 0, size)
                Preview(State.READY, mode, firstPageRows = rows.size,
                    moreRows = rows.size == size,
                    earliestReceivedAt = rows.map { it.date }.filter { it > 0 }.minOrNull(),
                    latestReceivedAt = rows.map { it.date }.filter { it > 0 }.maxOrNull())
            }
        } catch (_: SecurityException) {
            Preview(State.PERMISSION_REQUIRED, mode)
        } catch (e: Exception) {
            Preview(State.PROVIDER_UNAVAILABLE, mode, detail = e.javaClass.simpleName)
        }
    }

    /** Import exactly one bounded page. Call again while the result is MORE_PAGES. */
    fun scan(context: Context, pageSize: Int = MAX_PAGE_SIZE): ScanResult {
        if (permissionState(context) != PermissionState.AVAILABLE) return ScanResult(State.PERMISSION_REQUIRED)
        val session = CredentialStore.load(context) ?: return ScanResult(State.NOT_PAIRED)
        // SIM registry calls take their own lock and may read CredentialStore.
        // Resolve before leasing the credential monitor; only the later SQLite
        // checkpoint/event commit runs under the identity lease.
        val observedSnapshot = runCatching { SimRegistry.snapshot(context) }.getOrNull()
        val current = CredentialStore.load(context)
        val currentMatchesExpected = current?.let {
            it.gatewayId == session.gatewayId && it.controlBaseUrl == session.controlBaseUrl
        } == true
        val simSnapshot = observedSnapshot?.takeIf {
            it.ownerGatewayId == session.gatewayId &&
                it.ownerControlBaseUrl == session.controlBaseUrl &&
                currentMatchesExpected
        }
        return scan(context.applicationContext, session, ContentResolverInboxSource(context),
            pageSize.coerceIn(1, MAX_PAGE_SIZE), System.currentTimeMillis(),
            simSnapshot = simSnapshot, enforceCurrentIdentity = true)
    }

    fun sessionScope(session: CredentialStore.Session): String = digestFields(
        "gsm2sip.sms.provider.scope.v1",
        session.gatewayId,
        session.controlBaseUrl.trimEnd('/')
    )

    internal fun configureForTest(
        context: Context,
        session: CredentialStore.Session,
        mode: HistoryMode,
        source: InboxSource,
        now: Long
    ): Status = configure(context, session, mode, source, now)

    internal fun scanForTest(
        context: Context,
        session: CredentialStore.Session,
        source: InboxSource,
        pageSize: Int,
        now: Long
    ): ScanResult = scan(context, session, source, pageSize.coerceIn(1, MAX_PAGE_SIZE), now,
        simSnapshot = null, enforceCurrentIdentity = false)

    private fun configure(
        context: Context,
        session: CredentialStore.Session,
        mode: HistoryMode,
        source: InboxSource,
        now: Long
    ): Status {
        val database = GatewayDatabase.get(context)
        val scope = sessionScope(session)
        if (!database.isActiveGateway(session.gatewayId)) {
            return Status(State.STALE_SCOPE, configured = false, sessionScope = scope)
        }
        return try {
            database.smsProviderCheckpoint(session.gatewayId, scope)?.let { existing ->
                if (mode == HistoryMode.FULL_CURRENT_INBOX &&
                    (existing.minimumProviderId != 0L || existing.scanStartAt != 0L)) {
                    val maximumNow = source.maxInboxRowId()
                    val expanded = database.promoteSmsProviderRecoveryToFullHistory(
                        session.gatewayId, scope, maximumNow
                    ) ?: return Status(State.STALE_SCOPE, configured = false, sessionScope = scope)
                    return expanded.toStatus(State.READY)
                }
                return existing.toStatus(State.READY)
            }
            val maximumAtEnable = source.maxInboxRowId()
            val firstProviderId = if (mode == HistoryMode.FULL_CURRENT_INBOX) 0L else maximumAtEnable + 1L
            val startAt = if (mode == HistoryMode.FULL_CURRENT_INBOX) 0L else now
            val checkpoint = database.configureSmsProviderRecovery(
                session.gatewayId, scope, firstProviderId, startAt,
                initialLastRowId = if (mode == HistoryMode.FULL_CURRENT_INBOX) 0L else maximumAtEnable,
                reconciliationTargetMaxId = maximumAtEnable
            ) ?: return Status(State.STALE_SCOPE, configured = false, sessionScope = scope)
            checkpoint.toStatus(State.READY)
        } catch (_: SecurityException) {
            Status(State.PERMISSION_REQUIRED, configured = false, sessionScope = scope)
        } catch (e: Exception) {
            Status(State.PROVIDER_UNAVAILABLE, configured = false, sessionScope = scope,
                detail = e.javaClass.simpleName)
        }
    }

    private fun scan(
        context: Context,
        session: CredentialStore.Session,
        source: InboxSource,
        pageSize: Int,
        now: Long,
        simSnapshot: SimRegistry.SimRegistrySnapshot?,
        enforceCurrentIdentity: Boolean
    ): ScanResult {
        val database = GatewayDatabase.get(context)
        val scope = sessionScope(session)
        if (!database.isActiveGateway(session.gatewayId)) return ScanResult(State.STALE_SCOPE)
        var checkpoint = database.smsProviderCheckpoint(session.gatewayId, scope)
            ?: return ScanResult(State.NOT_CONFIGURED)
        return try {
            val currentMax = source.maxInboxRowId()
            if (checkpoint.reconciliationActive && currentMax < checkpoint.reconcileRowId) {
                checkpoint = withScanIdentity(context, session, enforceCurrentIdentity) {
                    database.restartSmsProviderReconciliation(
                        session.gatewayId, scope, checkpoint.reconcileRowId, currentMax
                    )
                } ?: return ScanResult(State.STALE_SCOPE)
            }
            val reconciliationDue = !checkpoint.reconciliationActive &&
                (checkpoint.lastReconciledAt == 0L || now - checkpoint.lastReconciledAt >= RECONCILIATION_INTERVAL_MS ||
                    currentMax < checkpoint.lastRowId)
            if (reconciliationDue) {
                checkpoint = withScanIdentity(context, session, enforceCurrentIdentity) {
                    database.beginSmsProviderReconciliation(
                        session.gatewayId, scope, currentMax, now
                    )
                } ?: return ScanResult(State.STALE_SCOPE)
            }
            val scanMode = if (checkpoint.reconciliationActive)
                GatewayDatabase.SmsProviderScanMode.RECONCILE else GatewayDatabase.SmsProviderScanMode.INCREMENTAL
            val cursor = if (scanMode == GatewayDatabase.SmsProviderScanMode.RECONCILE)
                checkpoint.reconcileRowId else checkpoint.lastRowId
            val targetMax = if (scanMode == GatewayDatabase.SmsProviderScanMode.RECONCILE)
                checkpoint.reconcileTargetMaxId else null
            val rawRows = source.page(cursor, checkpoint.minimumProviderId,
                checkpoint.scanStartAt, pageSize, targetMax)
            val complete = rawRows.size < pageSize
            val rows = rawRows.map { raw -> toDatabaseRow(context, raw, simSnapshot) }
            val committed = withScanIdentity(context, session, enforceCurrentIdentity) {
                database.importSmsProviderPage(
                    session.gatewayId, scope, scanMode, cursor, rows, complete, now
                )
            } ?: return ScanResult(State.STALE_SCOPE, rawRows.size, checkpoint = checkpoint)
            when (committed.state) {
                GatewayDatabase.SmsProviderPageState.COMMITTED -> ScanResult(
                    if (complete) State.COMPLETE else State.MORE_PAGES,
                    rawRows.size, committed.importedRows, committed.matchedBroadcasts,
                    committed.duplicateRows, committed.checkpoint
                )
                GatewayDatabase.SmsProviderPageState.STALE_OWNER,
                GatewayDatabase.SmsProviderPageState.STALE_CHECKPOINT -> ScanResult(
                    State.STALE_SCOPE, rawRows.size, checkpoint = committed.checkpoint
                )
                GatewayDatabase.SmsProviderPageState.NOT_CONFIGURED -> ScanResult(State.NOT_CONFIGURED)
            }
        } catch (_: SecurityException) {
            ScanResult(State.PERMISSION_REQUIRED)
        } catch (e: Exception) {
            ScanResult(State.PROVIDER_UNAVAILABLE, detail = e.javaClass.simpleName)
        }
    }

    private fun <T> withScanIdentity(
        context: Context,
        session: CredentialStore.Session,
        enforceCurrentIdentity: Boolean,
        block: () -> T
    ): T? = if (enforceCurrentIdentity) {
        CredentialStore.withCurrentIdentity(context, session.gatewayId, session.controlBaseUrl) { block() }
    } else {
        block()
    }

    private fun toDatabaseRow(
        context: Context,
        raw: RawRow,
        snapshot: SimRegistry.SimRegistrySnapshot?
    ): GatewayDatabase.SmsProviderRow {
        val subId = raw.subscriptionId.takeIf { it >= 0 } ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val candidate = if (subId >= 0) snapshot?.subscriptions?.firstOrNull { it.subscriptionId == subId } else null
        val resolved = if (subId >= 0) snapshot?.mappings?.firstOrNull { it.subscriptionId == subId }
            ?.let { mapping -> runCatching {
                SimRegistry.resolve(context, mapping.simId, mapping.mappingRevision, mapping.localRevisionBarrier)
            }.getOrNull() }
        else null
        return GatewayDatabase.SmsProviderRow(
            rowId = raw.rowId,
            providerIdentity = providerIdentity(raw),
            sender = raw.sender,
            body = raw.body,
            receivedAt = raw.date,
            subscriptionId = subId,
            slotIndex = resolved?.slotIndex ?: candidate?.slotIndex ?: -1,
            recipient = candidate?.phoneNumber.orEmpty(),
            simId = resolved?.simId,
            mappingRevision = resolved?.mappingRevision,
            resolution = if (resolved != null) "known" else "unknown"
        )
    }

    private fun providerIdentity(row: RawRow): String = digestFields(
        "gsm2sip.sms.provider.row.v1",
        row.rowId.toString(), row.date.toString(), row.dateSent.toString(), row.sender,
        row.body, row.subscriptionId.toString(), row.serviceCenter, row.protocol.toString()
    )

    private fun digestFields(vararg fields: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun GatewayDatabase.SmsProviderCheckpoint.toStatus(state: State) = Status(
        state = state, configured = true, fullHistory = minimumProviderId == 0L && scanStartAt == 0L,
        sessionScope = sessionScope,
        scanStartAt = scanStartAt, minimumProviderId = minimumProviderId,
        lastRowId = lastRowId, reconcileRowId = reconcileRowId,
        reconciliationActive = reconciliationActive, lastScanAt = lastScanAt,
        importedRows = importedRows, matchedBroadcasts = matchedBroadcasts,
        lastOutcome = lastOutcome
    )

    private class ContentResolverInboxSource(context: Context) : InboxSource {
        private val resolver = context.applicationContext.contentResolver
        private val inboxUri: Uri = Telephony.Sms.Inbox.CONTENT_URI

        override fun maxInboxRowId(): Long {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "type=?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()))
                putStringArrayList(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayListOf(Telephony.Sms._ID))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
            }
            return resolver.query(inboxUri, arrayOf(Telephony.Sms._ID), args, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }

        override fun page(
            afterRowId: Long,
            minimumProviderId: Long,
            scanStartAt: Long,
            limit: Int,
            targetMaxRowId: Long?
        ): List<RawRow> {
            val selection = buildString {
                append("_id>? AND type=? AND (_id>=? OR date>=?)")
                if (targetMaxRowId != null) append(" AND _id<=?")
            }
            val selectionArgs = buildList {
                add(afterRowId.toString())
                add(Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
                add(minimumProviderId.toString())
                add(scanStartAt.toString())
                targetMaxRowId?.let { add(it.toString()) }
            }.toTypedArray()
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                putStringArrayList(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayListOf(Telephony.Sms._ID))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            val extendedProjection = arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, "date_sent", "sub_id", "service_center", "protocol"
            )
            val requiredProjection = arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
            )
            val cursor = try {
                resolver.query(inboxUri, extendedProjection, args, null)
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
                resolver.query(inboxUri, requiredProjection, args, null)
            } ?: throw IllegalStateException("SMS inbox provider returned no cursor")
            return cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val dateSentCol = c.getColumnIndex("date_sent")
                val subCol = c.getColumnIndex("sub_id")
                val centerCol = c.getColumnIndex("service_center")
                val protocolCol = c.getColumnIndex("protocol")
                val result = ArrayList<RawRow>(minOf(limit, c.count))
                while (c.moveToNext() && result.size < limit) {
                    val subId = if (subCol >= 0 && !c.isNull(subCol)) c.getInt(subCol)
                        else SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    result += RawRow(
                        rowId = c.getLong(idCol),
                        sender = addressCol.takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString).orEmpty(),
                        body = bodyCol.takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString).orEmpty(),
                        date = dateCol.takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong) ?: 0L,
                        dateSent = dateSentCol.takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong) ?: 0L,
                        subscriptionId = subId,
                        serviceCenter = centerCol.takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString).orEmpty(),
                        protocol = protocolCol.takeIf { it >= 0 && !c.isNull(it) }?.let(c::getInt) ?: -1
                    )
                }
                result
            }
        }
    }
}
