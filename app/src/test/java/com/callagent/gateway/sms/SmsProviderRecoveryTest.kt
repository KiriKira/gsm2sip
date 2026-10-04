package com.callagent.gateway.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SmsProviderRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: GatewayDatabase
    private val session = CredentialStore.Session(
        gatewayId = "gateway-${UUID.randomUUID()}",
        controlBaseUrl = "https://control.example.test/v1/",
        accessToken = "access-test",
        accessExpiresAt = "2030-01-01T00:00:00Z",
        refreshToken = "refresh-test",
        refreshExpiresAt = "2031-01-01T00:00:00Z"
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        GatewayDatabase.get(context).close()
        context.deleteDatabase("gateway-data.db")
        database = GatewayDatabase.get(context)
        database.adoptGatewayId(session.gatewayId)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("gateway-data.db")
    }

    @Test
    fun boundedPagesResumeAfterReopenAndRepeatedRowsCreateNoSecondEvent() {
        val source = FakeInbox((1L..121L).map { providerRow(it, "body-$it", 1_000_000L + it) })
        val configured = SmsProviderRecovery.configureForTest(
            context, session, SmsProviderRecovery.HistoryMode.FULL_CURRENT_INBOX, source, 2_000_000L
        )
        assertTrue(configured.configured)
        assertEquals(0L, configured.minimumProviderId)

        val first = SmsProviderRecovery.scanForTest(context, session, source, 120, 2_000_001L)
        assertEquals(SmsProviderRecovery.State.MORE_PAGES, first.state)
        assertEquals(120, first.scannedRows)
        assertEquals(120, first.importedRows)
        assertEquals(120L, first.checkpoint!!.reconcileRowId)
        assertEquals(120, receivedEventCount())

        database.close()
        database = GatewayDatabase.get(context)
        val second = SmsProviderRecovery.scanForTest(context, session, source, 120, 2_000_002L)
        assertEquals(SmsProviderRecovery.State.COMPLETE, second.state)
        assertEquals(1, second.importedRows)
        assertEquals(0L, second.checkpoint!!.reconcileRowId)
        assertEquals(121L, second.checkpoint!!.lastRowId)
        assertEquals(121, receivedEventCount())

        val retryFirstPage = SmsProviderRecovery.scanForTest(
            context, session, source, 120,
            2_000_002L + SmsProviderRecovery.RECONCILIATION_INTERVAL_MS
        )
        assertEquals(SmsProviderRecovery.State.MORE_PAGES, retryFirstPage.state)
        assertEquals(120, retryFirstPage.duplicateRows)
        assertEquals(121, receivedEventCount())
    }

    @Test
    fun ambiguousIdenticalRowsAreKeptAsRecoveredInsteadOfCollapsingRealMessages() {
        val receivedAt = 3_000_000L
        repeat(2) { index ->
            database.recordIncomingSms(
                messageId = UUID.randomUUID().toString(), sender = "+15550001111", recipient = "",
                body = "same text", receivedAt = receivedAt, partCount = 1, subId = -1,
                slotIndex = -1, simId = null, mappingRevision = null, resolution = "unknown"
            )
        }
        val source = FakeInbox((1L..2L).map { providerRow(it, "same text", receivedAt, "+15550001111") })
        SmsProviderRecovery.configureForTest(
            context, session, SmsProviderRecovery.HistoryMode.FULL_CURRENT_INBOX, source, receivedAt
        )
        // This row arrives after reconciliation captured max id 2; it must be
        // left for the incremental high-water scan after the bounded sweep.
        source.rows = source.rows + providerRow(3, "same text", receivedAt, "+15550001111")

        val twoMatches = SmsProviderRecovery.scanForTest(context, session, source, 10, receivedAt + 10)
        assertEquals(SmsProviderRecovery.State.COMPLETE, twoMatches.state)
        assertEquals(0, twoMatches.matchedBroadcasts)
        assertEquals(2, twoMatches.importedRows)
        assertEquals(4, receivedEventCount())

        val thirdMessage = SmsProviderRecovery.scanForTest(context, session, source, 10, receivedAt + 20)
        assertEquals(1, thirdMessage.importedRows)
        assertEquals(0, thirdMessage.duplicateRows)
        assertEquals(5, receivedEventCount())
        val sources = database.writableDatabase.rawQuery(
            "SELECT i.source FROM inbox i JOIN events e ON e.event_id=i.event_id WHERE e.owner_gateway_id=? AND e.type='sms.received' ORDER BY i.received_at",
            arrayOf(session.gatewayId)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(listOf("broadcast", "broadcast", "recovered", "recovered", "recovered"), sources)
    }

    @Test
    fun oneUniqueBroadcastCandidateIsAssociatedWithoutAddingAnotherEvent() {
        val receivedAt = 4_000_000L
        database.recordIncomingSms(
            messageId = UUID.randomUUID().toString(), sender = "+15550001111", recipient = "",
            body = "single text", receivedAt = receivedAt, partCount = 1, subId = -1,
            slotIndex = -1, simId = null, mappingRevision = null, resolution = "unknown"
        )
        val source = FakeInbox(listOf(providerRow(1, "single text", receivedAt, "+15550001111")))
        SmsProviderRecovery.configureForTest(
            context, session, SmsProviderRecovery.HistoryMode.FULL_CURRENT_INBOX, source, receivedAt
        )
        val result = SmsProviderRecovery.scanForTest(context, session, source, 10, receivedAt + 1)

        assertEquals(1, result.matchedBroadcasts)
        assertEquals(0, result.importedRows)
        assertEquals(1, receivedEventCount())
    }

    @Test
    fun revokedReadSmsPermissionIsReportedAsUnavailable() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        assertEquals(SmsProviderRecovery.PermissionState.AVAILABLE,
            SmsProviderRecovery.permissionState(context))

        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_SMS)
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.READ_SMS))
        assertEquals(SmsProviderRecovery.PermissionState.REQUIRED,
            SmsProviderRecovery.permissionState(context))
    }

    @Test
    fun historyCutoffAndSessionScopeStayBoundToThePairing() {
        val scopeA = SmsProviderRecovery.sessionScope(session)
        val refreshed = session.copy(accessToken = "rotated", refreshToken = "rotated-refresh")
        assertEquals(scopeA, SmsProviderRecovery.sessionScope(refreshed))
        assertNotEquals(scopeA, SmsProviderRecovery.sessionScope(session.copy(
            controlBaseUrl = "https://other.example.test/v1/"
        )))

        val source = FakeInbox(listOf(providerRow(10, "old inbox row", 1_000L)))
        val configured = SmsProviderRecovery.configureForTest(
            context, session, SmsProviderRecovery.HistoryMode.SINCE_ENABLE, source, 5_000L
        )
        assertEquals(11L, configured.minimumProviderId)
        assertEquals(5_000L, configured.scanStartAt)
        val repeatedConfigure = SmsProviderRecovery.configureForTest(
            context, session, SmsProviderRecovery.HistoryMode.SINCE_ENABLE, source, 9_000L
        )
        assertEquals(11L, repeatedConfigure.minimumProviderId)
        assertEquals(5_000L, repeatedConfigure.scanStartAt)

        val explicitlyExpanded = SmsProviderRecovery.configureForTest(
            context, session, SmsProviderRecovery.HistoryMode.FULL_CURRENT_INBOX, source, 10_000L
        )
        assertEquals(0L, explicitlyExpanded.minimumProviderId)
        assertEquals(0L, explicitlyExpanded.scanStartAt)
        assertTrue(explicitlyExpanded.fullHistory)

        val otherPair = session.copy(gatewayId = "other-${UUID.randomUUID()}")
        database.adoptGatewayId(otherPair.gatewayId)
        val staleConfigure = SmsProviderRecovery.configureForTest(
            context, session, SmsProviderRecovery.HistoryMode.FULL_CURRENT_INBOX, source, 10_000L
        )
        assertEquals(SmsProviderRecovery.State.STALE_SCOPE, staleConfigure.state)
        assertEquals("A stale recovery snapshot must never roll the database owner backward",
            otherPair.gatewayId, database.activeGatewayId())
        val otherSessionScan = SmsProviderRecovery.scanForTest(
            context, otherPair, source, 10, 10_000L
        )
        assertEquals(SmsProviderRecovery.State.NOT_CONFIGURED, otherSessionScan.state)
        val staleScan = SmsProviderRecovery.scanForTest(context, session, source, 10, 10_000L)
        assertEquals(SmsProviderRecovery.State.STALE_SCOPE, staleScan.state)
        assertEquals(otherPair.gatewayId, database.activeGatewayId())
        assertNotNull(database.smsProviderCheckpoint(session.gatewayId, scopeA))
        assertTrue(database.pendingEvents(session.gatewayId, 20).isEmpty())
    }

    private fun providerRow(
        id: Long,
        body: String,
        date: Long,
        sender: String = "+15550002222"
    ) = SmsProviderRecovery.RawRow(
        rowId = id, sender = sender, body = body, date = date, dateSent = date,
        subscriptionId = -1, serviceCenter = "+15550000001", protocol = 0
    )

    private fun receivedEventCount(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE owner_gateway_id=? AND type='sms.received'",
        arrayOf(session.gatewayId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private class FakeInbox(var rows: List<SmsProviderRecovery.RawRow>) : SmsProviderRecovery.InboxSource {
        override fun maxInboxRowId(): Long = rows.maxOfOrNull { it.rowId } ?: 0L

        override fun page(
            afterRowId: Long,
            minimumProviderId: Long,
            scanStartAt: Long,
            limit: Int,
            targetMaxRowId: Long?
        ): List<SmsProviderRecovery.RawRow> = rows.asSequence()
            .filter { it.rowId > afterRowId }
            .filter { targetMaxRowId == null || it.rowId <= targetMaxRowId }
            .filter { it.rowId >= minimumProviderId || it.date >= scanStartAt }
            .sortedBy { it.rowId }
            .take(limit)
            .toList()
    }
}
