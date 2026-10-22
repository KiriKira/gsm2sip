package com.callagent.gateway.bridge

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.callagent.gateway.data.GatewayDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

/** A call left `unknown` by a lost process or expired call control must not
 * hold the cellular slot, and the servers' copies of it, forever. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnresolvedCellularReconcileTest {
    private lateinit var context: Context
    private lateinit var db: GatewayDatabase
    private val runId = UUID.randomUUID().toString()
    private val gatewayA = "gateway-A-$runId"
    private val gatewayB = "gateway-B-$runId"
    private var cellularCall = false
    private lateinit var orchestrator: CallOrchestrator

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        GatewayDatabase.get(context).close()
        context.deleteDatabase("gateway-data.db")
        db = GatewayDatabase.get(context)
        db.writableDatabase.delete("rtc_control_terminals", null, null)
        db.writableDatabase.delete("rtc_cellular_slot", null, null)
        db.writableDatabase.delete("call_winners", null, null)
        db.adoptGatewayId(gatewayA)
        db.addMeshNode(gatewayB, "http://backup.invalid/v1", "backup")
        orchestrator = CallOrchestrator(context, cellularCallPresent = { cellularCall })
    }

    @After
    fun tearDown() {
        orchestrator.stop()
        shadowOf(Looper.getMainLooper()).idle()
        db.writableDatabase.delete("rtc_control_terminals", null, null)
        db.writableDatabase.delete("rtc_cellular_slot", null, null)
        db.writableDatabase.delete("call_winners", null, null)
        db.close()
        context.deleteDatabase("gateway-data.db")
    }

    @Test
    fun connectedCallLostWithTheProcessIsReleasedOnceTelecomHasNoCall() {
        val call = id("incoming")
        assertTrue(db.beginCallDispatch(call, "sim", 1, "incoming"))
        assertTrue(db.reserveRtcCellularSlot(call))
        assertTrue(db.claimCallWinner(call, gatewayA, "host-a"))
        db.recoverDispatchingCallsToUnknown()

        cellularCall = true
        reconcile()
        assertEquals("a live cellular call may still be this one", listOf(call), db.unresolvedRtcCellularCalls())
        assertFalse(db.beginRtcCallDispatch(id("blocked"), "sim", 1))

        cellularCall = false
        reconcile()
        assertTrue(db.unresolvedRtcCellularCalls().isEmpty())
        assertEquals(listOf(call to "ended"), db.rtcTerminals(gatewayA))
        assertEquals("every node may hold a copy", listOf(call to "ended"), db.rtcTerminals(gatewayB))
        assertTrue(db.beginRtcCallDispatch(id("next"), "sim", 1))
        assertFalse("the released call is never replayed", db.beginRtcCallDispatch(call, "sim", 1))
    }

    @Test
    fun callsInProgressAreNeverReleased() {
        val live = id("live")
        assertTrue(db.beginRtcCallDispatch(live, "sim", 1))
        reconcile()
        assertFalse("a dispatching call keeps its slot", db.beginRtcCallDispatch(id("second"), "sim", 1))

        val unknown = id("unknown")
        db.completeRtcCellular(live, "completed", emptyList())
        assertTrue(db.beginRtcCallDispatch(unknown, "sim", 1))
        assertTrue(db.markCallTerminal(unknown, "unknown"))
        ReflectionHelpers.setField(orchestrator, "bridgeState", CallOrchestrator.BridgeState.BRIDGED)
        reconcile()
        assertEquals("only an idle bridge can rule the call out", listOf(unknown), db.unresolvedRtcCellularCalls())
        ReflectionHelpers.setField(orchestrator, "bridgeState", CallOrchestrator.BridgeState.IDLE)
    }

    @Test
    fun dispatchTelecomNeverReportedEndsTheTeardownAfterItSettles() {
        val call = id("unseen")
        assertTrue(db.beginRtcCallDispatch(call, "sim", 1))
        assertTrue(db.markCallTerminal(call, "unknown"))
        ReflectionHelpers.setField(orchestrator, "bridgeState", CallOrchestrator.BridgeState.TEARING_DOWN)
        ReflectionHelpers.setField(orchestrator, "rtcIncoming", true)
        ReflectionHelpers.setField(orchestrator, "rtcCellularDispatched", true)
        ReflectionHelpers.setField(orchestrator, "activeBusinessCallId", call)
        ReflectionHelpers.setField(orchestrator, "dispatchUnseenSince", SystemClock.elapsedRealtime())

        reconcile()
        assertEquals(CallOrchestrator.BridgeState.TEARING_DOWN, orchestrator.bridgeState)
        assertEquals(listOf(call), db.unresolvedRtcCellularCalls())

        ReflectionHelpers.setField(orchestrator, "dispatchUnseenSince", SystemClock.elapsedRealtime() - 31_000L)
        reconcile()
        assertEquals(CallOrchestrator.BridgeState.IDLE, orchestrator.bridgeState)
        assertTrue(db.unresolvedRtcCellularCalls().isEmpty())
        assertEquals(listOf(call to "ended"), db.rtcTerminals(gatewayA))
    }

    private fun reconcile() {
        orchestrator.reconcileUnresolvedCellular()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun id(suffix: String) = "$runId-$suffix"
}
