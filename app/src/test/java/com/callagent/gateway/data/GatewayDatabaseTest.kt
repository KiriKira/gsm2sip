package com.callagent.gateway.data

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.Config
import java.util.UUID

/** SQLite-backed recovery checks for the SMS journal and modem dispatch boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GatewayDatabaseTest {
    private lateinit var context: Context
    private lateinit var db: GatewayDatabase
    private val runId = UUID.randomUUID().toString()
    private val gatewayA = "gateway-A-$runId"
    private val gatewayB = "gateway-B-$runId"

    @Before
    fun openFreshDatabase() {
        context = RuntimeEnvironment.getApplication()
        GatewayDatabase.get(context).close()
        context.deleteDatabase("gateway-data.db")
        db = GatewayDatabase.get(context)
        // The helper is process-wide and Robolectric may retain its original
        // application context between method sandboxes. Clear old handoffs
        // through the real recovery path before this method creates its fixture.
        db.recoverUnknownDispatches()
        // The helper can retain its first application's DB path across native
        // SQLite sandboxes. Reset this fixture's RTC rows at the real helper.
        db.writableDatabase.delete("rtc_control_terminals",null,null)
        db.writableDatabase.delete("rtc_cellular_slot",null,null)
        db.writableDatabase.delete("call_winners",null,null)
        db.adoptGatewayId(gatewayA)
    }

    @After
    fun closeDatabase() {
        db.writableDatabase.delete("rtc_control_terminals",null,null)
        db.writableDatabase.delete("rtc_cellular_slot",null,null)
        db.writableDatabase.delete("call_winners",null,null)
        db.close()
        context.deleteDatabase("gateway-data.db")
    }

    @Test
    fun supersededSimProposalCannotReappearAfterConfirmationAndRestart() {
        db.saveSimProposal(id("old-proposal"), """{"mapping_revision":8}""")
        val latest = id("new-proposal")
        db.saveSimProposal(latest, """{"mapping_revision":17}""")
        reopen()
        assertEquals(latest, db.pendingSimProposal(16)!!.first)
        db.clearSimProposal(latest)
        reopen()
        assertNull(db.pendingSimProposal(17))
        assertEquals(gatewayA, db.activeGatewayId())
    }

    @Test
    fun legacyStaleSimProposalsAreRemovedWithoutRollingBackLocalRevision() {
        listOf(3L, 8L).forEach { revision ->
            db.writableDatabase.execSQL(
                "INSERT INTO sim_proposals(operation_id,payload,updated_at) VALUES(?,?,?)",
                arrayOf(id("legacy-$revision"), """{"mapping_revision":$revision}""", revision)
            )
        }
        assertNull(db.pendingSimProposal(16))
        reopen()
        assertNull(db.pendingSimProposal())
        assertEquals(gatewayA, db.activeGatewayId())
    }

    @Test
    fun equalRevisionProposalRemainsAvailableForInterruptedConfirmationRecovery() {
        val operation = id("equal-proposal")
        db.saveSimProposal(operation, """{"mapping_revision":16}""")
        reopen()
        assertEquals(operation, db.pendingSimProposal(16)!!.first)
    }

    @Test
    fun rtcWinnerAndUncertainPhysicalOccupancySurviveProcessRestart() {
        val call=id("rtc-incoming")
        assertTrue(db.beginCallDispatch(call,"sim",1,"incoming"))
        assertTrue(db.reserveRtcCellularSlot(call))
        db.addMeshNode(gatewayB,"http://backup.invalid/v1","backup")
        assertTrue(db.claimCallWinner(call,gatewayA,"host-a"))
        assertFalse(db.claimCallWinner(call,gatewayB,"host-b"))
        reopen()
        assertTrue(db.claimCallWinner(call,gatewayA,"host-a"))
        assertFalse(db.claimCallWinner(call,gatewayB,"host-b"))
        db.recoverDispatchingCallsToUnknown()
        assertFalse("SIP must not bypass uncertain RTC physical occupancy",db.beginCallDispatch(id("sip-second"),"sim",1,"outgoing"))
        assertFalse("RTC must not redial during uncertain occupancy",db.beginRtcCallDispatch(id("rtc-second"),"sim",1))
        db.completeRtcCellular(call,"completed",listOf(gatewayA,gatewayB))
        reopen()
        assertEquals(listOf(call to "ended"),db.rtcTerminals(gatewayA))
        assertEquals(listOf(call to "ended"),db.rtcTerminals(gatewayB))
        assertTrue(db.beginRtcCallDispatch(id("rtc-second"),"sim",1))
        assertFalse("the same business call is never replayed",db.beginRtcCallDispatch(call,"sim",1))
    }

    @Test
    fun hostDeclineAndAnswerExcludeEachOtherAndTheEndReportsDeclined() {
        db.addMeshNode(gatewayB,"http://backup.invalid/v1","backup")
        val declined=id("rtc-declined")
        assertTrue(db.beginCallDispatch(declined,"sim",1,"incoming"))
        assertTrue(db.reserveRtcCellularSlot(declined))
        assertTrue(db.claimCallDecline(declined,gatewayB))
        assertTrue("a repeated decline is the same decision",db.claimCallDecline(declined,gatewayB))
        reopen()
        assertFalse("no host can answer a declined call",db.claimCallWinner(declined,gatewayA,"host-a"))
        db.completeRtcCellular(declined,"failed",listOf(gatewayA,gatewayB))
        assertEquals(listOf(declined to "declined"),db.rtcTerminals(gatewayA))
        assertEquals(listOf(declined to "declined"),db.rtcTerminals(gatewayB))
        db.acknowledgeRtcTerminal(declined,gatewayA);db.acknowledgeRtcTerminal(declined,gatewayB)

        assertNull("an ended decline is finished",db.unfinishedDecline(40_000L))

        val interrupted=id("rtc-decline-interrupted")
        assertTrue(db.beginCallDispatch(interrupted,"sim",1,"incoming"))
        assertTrue(db.claimCallDecline(interrupted,gatewayA))
        db.recoverDispatchingCallsToUnknown()
        assertEquals(interrupted,db.unfinishedDecline(40_000L))
        assertNull("a decline past the ring window is not this call",db.unfinishedDecline(40_000L,System.currentTimeMillis()+41_000L))
        db.completeRtcCellular(interrupted,"failed",listOf(gatewayA))
        assertNull(db.unfinishedDecline(40_000L))
        db.acknowledgeRtcTerminal(interrupted,gatewayA)

        val answered=id("rtc-answered")
        assertTrue(db.beginCallDispatch(answered,"sim",1,"incoming"))
        assertTrue(db.reserveRtcCellularSlot(answered))
        assertTrue(db.claimCallWinner(answered,gatewayA,"host-a"))
        assertFalse("a decline after the answer loses",db.claimCallDecline(answered,gatewayB))
        assertTrue(db.claimCallWinner(answered,gatewayA,"host-a"))
        db.completeRtcCellular(answered,"completed",listOf(gatewayA))
        assertEquals(listOf(answered to "ended"),db.rtcTerminals(gatewayA))
    }

    @Test
    fun losingNodeTerminalIsDurableWithoutEndingThePhysicalCall() {
        val call=id("rtc-winner")
        assertTrue(db.beginCallDispatch(call,"sim",1,"incoming"))
        assertTrue(db.reserveRtcCellularSlot(call))
        db.queueRtcTerminal(call,gatewayB,"answered_elsewhere")
        reopen()
        assertEquals(listOf(call to "answered_elsewhere"),db.rtcTerminals(gatewayB))
        db.acknowledgeRtcTerminal(call,gatewayB)
        assertTrue(db.rtcTerminals(gatewayB).isEmpty())
        assertFalse(db.beginCallDispatch(id("blocked"),"sim",1,"outgoing"))
    }

    @Test
    fun claimedCommandAndPreparedPartCountSurviveCloseAndReopen() {
        insertClaimed(id("cmd-prepare"), id("msg-prepare"), parts = 0)

        assertTrue(db.prepareCommandParts(id("cmd-prepare"), 3))
        assertTrue("same prepare must be idempotent", db.prepareCommandParts(id("cmd-prepare"), 3))
        assertFalse("a different split after prepare must conflict", db.prepareCommandParts(id("cmd-prepare"), 2))

        reopen()
        val restored = db.command(id("cmd-prepare"), gatewayA)
        assertNotNull(restored)
        assertEquals("claimed", restored!!.state)
        assertEquals(3, restored.partCount)
        assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch(id("cmd-prepare")))
    }

    @Test
    fun rebootRecoveryMakesDispatchUnknownAndAllowsEvidenceOnlyLateCallback() {
        insertClaimed(id("cmd-recover"), id("msg-recover"), parts = 1)
        assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch(id("cmd-recover")))

        reopen()
        assertEquals(1, db.recoverUnknownDispatches())
        assertEquals("unknown", db.commandSmsAggregate(id("cmd-recover"))!!.state)
        assertEquals(GatewayDatabase.DispatchStartResult.NOT_READY, db.beginDispatch(id("cmd-recover")))
        assertEquals(0, db.recoverUnknownDispatches())

        assertTrue("a real late delivery callback can resolve unknown", db.recordPartState(
            id("cmd-recover"), 0, "delivered"
        ))
        val eventCountAfterCallback = db.pendingEvents(gatewayA).size
        assertFalse("duplicate callback must not add an event", db.recordPartState(
            id("cmd-recover"), 0, "delivered"
        ))
        assertEquals(eventCountAfterCallback, db.pendingEvents(gatewayA).size)
        assertEquals("delivered", db.commandSmsAggregate(id("cmd-recover"))!!.state)
        assertEquals(GatewayDatabase.DispatchStartResult.NOT_READY, db.beginDispatch(id("cmd-recover")))
    }

    @Test
    fun duplicateCallbacksDoNotCreateEventsAndDeliveryAdvancesSubmitted() {
        insertClaimed(id("cmd-callback"), id("msg-callback"), parts = 1)
        assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch(id("cmd-callback")))

        assertTrue(db.recordPartState(id("cmd-callback"), 0, "submitted", resultCode = -1))
        val afterSubmit = db.pendingEvents(gatewayA).size
        assertFalse(db.recordPartState(id("cmd-callback"), 0, "submitted", resultCode = -1))
        assertEquals(afterSubmit, db.pendingEvents(gatewayA).size)

        assertTrue(db.recordPartState(id("cmd-callback"), 0, "delivered"))
        val afterDelivery = db.pendingEvents(gatewayA).size
        assertFalse(db.recordPartState(id("cmd-callback"), 0, "delivered"))
        assertEquals(afterDelivery, db.pendingEvents(gatewayA).size)
        assertEquals("delivered", db.commandSmsAggregate(id("cmd-callback"))!!.state)
        assertEquals(3, db.pendingEvents(gatewayA).size) // dispatching + submitted + delivered
    }

    @Test
    fun oldCommandLateCallbackRemainsOwnedByOriginalGatewayAfterRepairing() {
        insertClaimed(id("cmd-owner"), id("msg-owner"), parts = 1)
        assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch(id("cmd-owner")))
        val crossOwnerCountBeforeSwitch = db.pendingEventsForOtherGateways()
        assertTrue(db.adoptGatewayId(gatewayB))
        assertEquals(gatewayB, db.activeGatewayId())

        assertTrue(db.recordPartState(id("cmd-owner"), 0, "delivered"))
        assertNull("old command is not visible through the new gateway scope", db.command(id("cmd-owner"), gatewayB))
        assertNotNull(db.command(id("cmd-owner"), gatewayA))

        val originalOwnerEvents = db.pendingEvents(gatewayA)
        assertEquals(listOf("sms.dispatching", "sms.part_state"), originalOwnerEvents.map { it.type })
        assertTrue(originalOwnerEvents.all { it.ownerGatewayId == gatewayA })
        assertTrue("late callback must never enter the newly paired account", db.pendingEvents(gatewayB).isEmpty())
        assertEquals(crossOwnerCountBeforeSwitch + 2, db.pendingEventsForOtherGateways())
    }

    @Test
    fun perSimRateBudgetsAreDurableAcrossReopenAndEnforceMinuteAndHourCaps() {
        repeat(6) { i ->
            val commandId = id("minute-$i")
            insertClaimed(commandId, id("minute-msg-$i"), parts = 1)
            val result = db.beginDispatch(commandId)
            if (i < 5) assertEquals("minute allowance $i", GatewayDatabase.DispatchStartResult.STARTED, result)
            else assertEquals("sixth send inside one minute", GatewayDatabase.DispatchStartResult.RATE_LIMITED, result)
        }
        insertClaimed(id("other-sim"), id("other-sim-msg"), parts = 1, simId = "sim-B-$runId")
        assertEquals("rate limits are independent for each SIM", GatewayDatabase.DispatchStartResult.STARTED,
            db.beginDispatch(id("other-sim")))

        // Age accepted entries out of the minute window while retaining them in
        // the hour window. This advances the clock window without sleeping.
        val agedAt = System.currentTimeMillis() - 120_000L
        db.writableDatabase.execSQL("UPDATE sms_dispatch_budget SET dispatched_at=?", arrayOf(agedAt))

        repeat(25) { i ->
            val commandId = id("hour-$i")
            insertClaimed(commandId, id("hour-msg-$i"), parts = 1)
            assertEquals(GatewayDatabase.DispatchStartResult.STARTED, db.beginDispatch(commandId))
            db.writableDatabase.execSQL(
                "UPDATE sms_dispatch_budget SET dispatched_at=? WHERE command_id=?",
                arrayOf(agedAt, commandId)
            )
        }
        insertClaimed(id("hour-30"), id("hour-msg-30"), parts = 1)
        assertEquals("31st send inside one hour", GatewayDatabase.DispatchStartResult.RATE_LIMITED,
            db.beginDispatch(id("hour-30")))

        reopen()
        assertEquals("claimed", db.command(id("minute-5"), gatewayA)!!.state)
        assertEquals(GatewayDatabase.DispatchStartResult.RATE_LIMITED, db.beginDispatch(id("hour-30")))
    }

    @Test
    fun sqliteConnectionUsesFullSynchronousMode() {
        val database = db.writableDatabase
        val pooledReadMode = database.rawQuery("PRAGMA synchronous", null).use {
            assertTrue(it.moveToFirst())
            it.getInt(0)
        }
        database.beginTransaction()
        val primaryWriterMode = try {
            database.rawQuery("PRAGMA synchronous", null).use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
        } finally {
            database.setTransactionSuccessful()
            database.endTransaction()
        }
        val journal = db.writableDatabase.rawQuery("PRAGMA journal_mode", null).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        assertEquals("primary writer must use SQLite FULL synchronous mode (journal=$journal, pooledRead=$pooledReadMode)",
            2, primaryWriterMode)
        assertEquals("WAL journal mode", "wal", journal.lowercase())
    }

    private fun insertClaimed(commandId: String, messageId: String, parts: Int, simId: String = "sim-A-$runId") {
        val command = GatewayDatabase.Command(
            commandId = commandId,
            messageId = messageId,
            simId = simId,
            mappingRevision = 7,
            to = "+15550001234",
            text = "test payload",
            partCount = parts,
            expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
            state = "queued",
            payloadHash = "payload-$commandId",
            ownerGatewayId = gatewayA
        )
        assertEquals(GatewayDatabase.InsertCommandResult.INSERTED,
            db.insertClaimedCommand(command, gatewayA))
    }

    private fun id(suffix: String): String = "$runId-$suffix"

    private fun reopen() {
        db.close()
        db = GatewayDatabase.get(context)
    }
}
