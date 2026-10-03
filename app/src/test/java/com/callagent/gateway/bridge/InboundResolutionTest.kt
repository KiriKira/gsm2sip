package com.callagent.gateway.bridge

import android.Manifest
import android.os.Looper
import android.telecom.Call
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.sip.SipClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class InboundResolutionTest {
    @Test fun slowBrokerDoesNotBlockTelecomAndDuplicateCallDoesNotRestartResolution() {
        withBlockedResolver { orchestrator, _, started, invocations ->
            val call = ringingCall()
            orchestrator.onIncomingGsmCall(call, "redacted")
            assertTrue("Resolver never ran off the callback thread", started.await(2, TimeUnit.SECONDS))
            assertEquals(CallOrchestrator.BridgeState.GSM_RINGING, orchestrator.bridgeState)
            orchestrator.onIncomingGsmCall(call, "redacted")
            orchestrator.onIncomingGsmCall(ringingCall(), "redacted")
            assertEquals(1, invocations.get())
            assertEquals(CallOrchestrator.BridgeState.GSM_RINGING, orchestrator.bridgeState)
        }
    }

    @Test fun validMappingReturnedAfterStopCannotCreateDispatchOrReportASecondFailure() {
        withBlockedResolver { orchestrator, errors, started, _ ->
            orchestrator.onIncomingGsmCall(ringingCall(), "redacted")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            orchestrator.stop()
            assertEquals(CallOrchestrator.BridgeState.IDLE, orchestrator.bridgeState)
            assertTrue(errors.isEmpty())
            // The fixture releases the non-cooperating resolver after stop,
            // joins the executor, then drains all late Handler deliveries.
        }
    }

    private fun withBlockedResolver(
        test: (CallOrchestrator, MutableList<String>, CountDownLatch, AtomicInteger) -> Unit
    ) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val invocations = AtomicInteger()
        val errors = mutableListOf<String>()
        val orchestrator = CallOrchestrator(context, SipClient("test", "unused", "invalid.example")) { _, _ ->
            invocations.incrementAndGet()
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (release.count > 0 && System.nanoTime() < deadline) {
                try { release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            }
            SimRegistry.SimMapping(
                "00000000-0000-0000-0000-000000000001", 14, 0, 1, 0,
                SimRegistry.IdentityState.LOCAL_CONFIRMED, null, true, true
            )
        }
        orchestrator.listener = object : CallOrchestrator.OrchestratorListener {
            override fun onStateChanged(state: CallOrchestrator.BridgeState, info: String) {}
            override fun onError(error: String) { errors += error }
        }
        try { test(orchestrator, errors, started, invocations) }
        finally {
            orchestrator.stop()
            release.countDown()
            val executor = ReflectionHelpers.getField<ExecutorService>(orchestrator, "inboundWorker")
            assertTrue("Resolver worker leaked after stop", executor.awaitTermination(2, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CallOrchestrator.BridgeState.IDLE, orchestrator.bridgeState)
            assertTrue("A late broker response escaped cancellation", errors.isEmpty())
        }
    }

    private fun ringingCall(): Call = ReflectionHelpers.newInstance(Call::class.java).also {
        ReflectionHelpers.setField(it, "mState", Call.STATE_RINGING)
    }
}
