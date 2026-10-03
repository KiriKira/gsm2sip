package com.callagent.gateway.bridge

import android.Manifest
import android.content.ComponentName
import android.os.Looper
import android.os.UserHandle
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.sip.GsmCallMetadata
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import com.callagent.gateway.sip.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SipOutboundResolutionTest {
    @Test
    fun slowResolverReturnsFromSipCallbackAndRunsOffReceiveThread() {
        withBlockedResolver { orchestrator, call, started, _, _, _, resolverCalls, _ ->
            val before = System.nanoTime()
            orchestrator.onIncomingCall(call)
            val callbackMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)

            assertTrue("SIP callback waited for the broker: ${callbackMs}ms", callbackMs < 500)
            assertTrue("Outbound resolver did not start", started.await(2, TimeUnit.SECONDS))
            assertEquals(1, resolverCalls.get())
            assertEquals(CallOrchestrator.BridgeState.GSM_DIALING, orchestrator.bridgeState)
        }
    }

    @Test
    fun lateMappingAfterStopCannotWriteLedgerOrPlaceCall() {
        withBlockedResolver { orchestrator, call, started, release, ledgerWrites, placeCalls, _, _ ->
            orchestrator.onIncomingCall(call)
            assertTrue(started.await(2, TimeUnit.SECONDS))

            orchestrator.stop()
            assertEquals(CallOrchestrator.BridgeState.IDLE, orchestrator.bridgeState)
            release.countDown()
            awaitWorker(orchestrator)

            assertEquals(0, ledgerWrites.get())
            assertEquals(0, placeCalls.get())
        }
    }

    @Test
    fun matchingCancelDuringMappingPreventsLateDispatch() {
        withBlockedResolver { orchestrator, call, started, release, ledgerWrites, placeCalls, _, _ ->
            orchestrator.onIncomingCall(call)
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val cancel = SipMessage.parse(cancelFor(call.originalInvite!!))
            assertTrue("CANCEL was not handled", call.handleMessage(cancel!!))
            orchestrator.onCallTerminated(call)
            assertEquals(CallOrchestrator.BridgeState.IDLE, orchestrator.bridgeState)

            release.countDown()
            awaitWorker(orchestrator)
            assertEquals(0, ledgerWrites.get())
            assertEquals(0, placeCalls.get())
        }
    }

    @Test
    fun successfulDispatchRevalidatesAfterPreparationAndWritesLedgerFirst() {
        withBlockedResolver { orchestrator, call, started, release, ledgerWrites, placeCalls, resolverCalls, events ->
            orchestrator.onIncomingCall(call)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            orchestrator.onIncomingCall(call)
            orchestrator.onIncomingCall(newPendingCall("sip-outbound-2"))
            assertEquals("Duplicate/competing INVITE started another mapping lookup", 1, resolverCalls.get())
            release.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (placeCalls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(2, resolverCalls.get())
            assertEquals(1, ledgerWrites.get())
            assertEquals(1, placeCalls.get())
            assertEquals(listOf("ledger", "place"), events.toList())
        }
    }

    @Test
    fun changedFreshMappingFailsClosedBeforeLedgerOrTelecomDispatch() {
        val changed = testMapping().copy(localRevisionBarrier = 8)
        withBlockedResolver(
            test = { orchestrator, call, started, release, ledgerWrites, placeCalls, resolverCalls, _ ->
                orchestrator.onIncomingCall(call)
                assertTrue(started.await(2, TimeUnit.SECONDS))
                release.countDown()

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (orchestrator.bridgeState != CallOrchestrator.BridgeState.IDLE &&
                    System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(2, resolverCalls.get())
                assertEquals(0, ledgerWrites.get())
                assertEquals(0, placeCalls.get())
            },
            secondMapping = changed
        )
    }

    private fun withBlockedResolver(
        secondMapping: SimRegistry.SimMapping? = null,
        test: (
            CallOrchestrator,
            SipCall,
            CountDownLatch,
            CountDownLatch,
            AtomicInteger,
            AtomicInteger,
            AtomicInteger,
            CopyOnWriteArrayList<String>
        ) -> Unit
    ) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ledgerWrites = AtomicInteger()
        val placeCalls = AtomicInteger()
        val resolverCalls = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        val call = newPendingCall()
        val mapping = testMapping()
        val orchestrator = CallOrchestrator(
            context = context,
            sipClient = SipClient("test", "unused", "invalid.example"),
            outboundMappingResolver = { _, _, _ ->
                val invocation = resolverCalls.incrementAndGet()
                started.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (release.count > 0 && System.nanoTime() < deadline) {
                    try { release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                }
                if (invocation > 1) secondMapping ?: mapping else mapping
            },
            outboundAudioPreparation = { true },
            outboundCallDispatcher = { _, _, _, guarded ->
                guarded {
                    events += "place"
                    placeCalls.incrementAndGet()
                    true
                }
            },
            callDispatchLedger = { _, _, _, _, _ ->
                events += "ledger"
                ledgerWrites.incrementAndGet()
                true
            }
        )
        try {
            test(orchestrator, call, started, release, ledgerWrites, placeCalls, resolverCalls, events)
        } finally {
            orchestrator.stop()
            release.countDown()
            awaitWorker(orchestrator)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CallOrchestrator.BridgeState.IDLE, orchestrator.bridgeState)
        }
    }

    private fun awaitWorker(orchestrator: CallOrchestrator) {
        val executor = ReflectionHelpers.getField<ExecutorService>(orchestrator, "inboundWorker")
        executor.shutdownNow()
        assertTrue("Outbound resolver worker did not terminate", executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun newPendingCall(sipCallId: String = "sip-outbound-1"): SipCall {
        val client = SipClient("test", "unused", "invalid.example")
        val call = SipCall(sipCallId, SipCall.Direction.INBOUND, client)
        call.trustedGsmMetadata = GsmCallMetadata(
            GsmCallMetadata.SUPPORTED_PROTOCOL_VERSION,
            CALL_ID,
            SIM_ID,
            1
        )
        call.gsmForwardNumber = "+491512345678"
        call.originalInvite = SipMessage.parse(
            "INVITE sip:+491512345678@pbx.example SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-invite\r\n" +
                "From: <sip:caller@example>;tag=from-a\r\n" +
                "To: <sip:gateway@example>\r\n" +
                "Call-ID: $sipCallId\r\n" +
                "CSeq: 42 INVITE\r\n" +
                "Contact: <sip:server.example:5060>\r\n" +
                "Content-Length: 0\r\n\r\n"
        )
        return call
    }

    private fun cancelFor(invite: SipMessage): String =
        "CANCEL ${invite.requestUri} SIP/2.0\r\n" +
            "Via: ${invite.via}\r\n" +
            "From: ${invite.from}\r\n" +
            "To: ${invite.to}\r\n" +
            "Call-ID: ${invite.callId}\r\n" +
            "CSeq: 42 CANCEL\r\n" +
            "Content-Length: 0\r\n\r\n"

    private fun testMapping(localRevisionBarrier: Long = 7): SimRegistry.SimMapping =
        SimRegistry.SimMapping(
            SIM_ID, 14, 0, 1, localRevisionBarrier,
            SimRegistry.IdentityState.LOCAL_CONFIRMED,
            android.telecom.PhoneAccountHandle(
                ComponentName("com.android.phone", "com.android.services.telephony.TelephonyConnectionService"),
                "opaque-account",
                UserHandle.getUserHandleForUid(1000)
            ),
            smsAvailable = true,
            voiceAvailable = true
        )

    companion object {
        private const val CALL_ID = "00000000-0000-0000-0000-000000000001"
        private const val SIM_ID = "00000000-0000-0000-0000-000000000002"
    }
}
