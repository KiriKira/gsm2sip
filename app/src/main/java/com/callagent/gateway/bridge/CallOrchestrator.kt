package com.callagent.gateway.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.callagent.gateway.audio.CellularAudioSession
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtc.GatewayCallControl
import com.callagent.gateway.service.GatewayService
import com.callagent.gateway.sim.SimRegistry
import com.callagent.rtcmedia.PcmBridge
import com.callagent.rtcmedia.RtcAudioSession
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Use fresh call-control authority before classifying a closed RTC leg as a media failure. */
internal fun rtcCallHasTerminalIntent(readControlSnapshot: () -> JSONObject?): Boolean {
    val snapshot = runCatching(readControlSnapshot).getOrNull() ?: return false
    return snapshot.optBoolean("end_requested") || snapshot.optString("state") == "ended"
}

/** Deliver the reconciled result only while it still belongs to the active call generation. */
internal fun dispatchRtcClosureIfCurrent(
    isCurrent: () -> Boolean,
    remoteEnd: Boolean,
    onRemoteEnd: () -> Unit,
    onMediaFailure: () -> Unit
) {
    if (!isCurrent()) return
    if (remoteEnd) onRemoteEnd() else onMediaFailure()
}

/**
 * Bridges one cellular call at a time to a host through server call control
 * and native WebRTC audio.
 *
 * INBOUND (someone calls a SIM in this phone):
 *   1. GSM rings → the call is offered to every paired server node
 *   2. A host accepts → WebRTC SDP is committed → this phone commits the
 *      host as the durable winner and answers GSM while ICE connects
 *   3. GSM and WebRTC both go active → cellular PCM flows over WebRTC
 *
 * OUTBOUND (a host dials through a SIM here):
 *   1. A host reserves the call; its control snapshot reaches this phone
 *   2. WebRTC media connects → this phone records the dispatch and dials
 *   3. GSM answers → cellular PCM flows over WebRTC
 *
 * Either side hanging up ends both.
 */
class CallOrchestrator(
    private val context: Context,
    private val outboundMappingResolver: (Context, String, Long) -> SimRegistry.SimMapping =
        { appContext, simId, revision -> SimRegistry.resolveForVoice(appContext, simId, revision) },
    private val outboundAudioPreparation: () -> Boolean = { GsmCallManager.prepareVoiceCallDispatch() },
    private val outboundCallDispatcher: (
        Context,
        String,
        SimRegistry.SimMapping,
        ((() -> Boolean) -> Boolean)
    ) -> Boolean = { appContext, destination, mapping, guardedDispatch ->
        GsmCallManager.dispatchPreparedCall(appContext, destination, mapping, guardedDispatch)
    },
    /**
     * A call authorized by a mesh node carries that node's SIM revision. It is
     * accepted only while the node's binding of the SIM is current, and then
     * resolves the same physical SIM at the primary's current revision.
     */
    private val nodeRevisionResolver: (Context, String, String, Long) -> Long? = { appContext, node, simId, revision ->
        if (GatewayDatabase.get(appContext).meshRevisionIsCurrent(node, simId.lowercase(), revision)) {
            SimRegistry.snapshot(appContext).mappingRevision
        } else null
    },
    /** Whether Telecom still has a cellular call on this phone, ours or not. */
    private val cellularCallPresent: (Context) -> Boolean = { appContext ->
        (GsmCallManager.activeCall != null && GsmCallManager.activeCallState != Call.STATE_DISCONNECTED) ||
            appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
            try { appContext.getSystemService(TelecomManager::class.java).isInCall } catch (_: SecurityException) { true }
    },
    private val audioFactory: (com.callagent.gateway.audio.CallAudioConfiguration) -> com.callagent.gateway.audio.CallAudioSession = { it.realSession() },
    private val incomingAccountResolver: (Context, PhoneAccountHandle?) -> SimRegistry.SimMapping? =
        { appContext, handle -> SimRegistry.resolvePhoneAccount(appContext, handle) }
) : GsmCallManager.Listener {

    /** Native media of the current call and the PCM bridge it shares with the cellular audio. */
    data class PreparedMedia(val session: RtcAudioSession, val pcm: PcmBridge)

    private data class RtcControlLeg(val node: GatewayCallControl.Node, val snapshot: JSONObject)
    @Volatile private var rtcControl: GatewayCallControl? = null
    @Volatile private var activeRtcLeg: RtcControlLeg? = null
    @Volatile private var rtcIncoming = false
    @Volatile private var rtcCellularDispatched = false
    @Volatile private var rtcAudioStarting = false
    /** When teardown began for a dispatch Telecom has not reported yet. */
    @Volatile private var dispatchUnseenSince = 0L
    private val incomingRtcLegs = ConcurrentHashMap<String, RtcControlLeg>()
    val rtcControlReady: Boolean get() = rtcControl?.ready == true

    fun onRtcNodeReady() {
        telecomHandler.post { if (!stopped && bridgeState == BridgeState.IDLE) listener?.onStateChanged(BridgeState.IDLE, "Call control ready") }
    }

    fun startRtcControl() {
        check(rtcControl == null)
        rtcControl = GatewayCallControl(context, this).also { it.start() }
    }

    fun onRtcControlLost(node: GatewayCallControl.Node) {
        telecomHandler.post {
            if (activeRtcLeg?.node === node) tearDown("Call control authorization expired")
        }
    }

    /** Ends calls the ledger left `unknown` once no cellular call can still exist.
     *
     *  The server keeps such a call, and this gateway keeps the cellular slot,
     *  until the gateway reports the cellular END. When the process died or
     *  call control expired mid-call, nothing else ever reports it, and the
     *  held slot then refuses every later call in both directions. */
    fun reconcileUnresolvedCellular() {
        if (stopped) return
        if (bridgeState == BridgeState.IDLE && GatewayDatabase.get(context).unresolvedRtcCellularCalls().isEmpty()) return
        telecomHandler.post { try {
            if (stopped || cellularCallPresent(context)) return@post
            synchronized(this) {
                if (bridgeState == BridgeState.TEARING_DOWN && rtcCellularDispatched && activeGsmCall == null &&
                    SystemClock.elapsedRealtime() - dispatchUnseenSince >= UNSEEN_DISPATCH_SETTLE_MS) {
                    tearDown("Dispatched call never reached Telecom", ledgerStateOverride = "unknown", dispatchUnseen = true)
                }
                if (bridgeState != BridgeState.IDLE) return@post
                val db = GatewayDatabase.get(context)
                val unresolved = db.unresolvedRtcCellularCalls()
                if (unresolved.isEmpty()) return@post
                val nodes = db.callControlNodeIds()
                unresolved.forEach { id ->
                    db.completeRtcCellular(id, "failed", nodes)
                    Log.i("GsmRtc", "call_id=$id event=unknown_call_released nodes=${nodes.size}")
                }
            }
        } catch (e: Exception) {
            Log.w("GsmRtc", "event=unknown_call_reconcile_failed error=${e.javaClass.simpleName}")
        } }
    }

    fun onRtcControlSnapshot(node: GatewayCallControl.Node, snapshot: JSONObject) {
        if (stopped) return
        val id = snapshot.getString("call_id")
        val leg = RtcControlLeg(node, snapshot)
        synchronized(this) {
            if (snapshot.getString("direction") == "incoming") {
                if (activeBusinessCallId != id || !rtcIncoming) return
                incomingRtcLegs[node.gatewayId] = leg
                if (activeRtcLeg?.node === node) activeRtcLeg = leg
                if (snapshot.optBoolean("end_requested") || snapshot.getString("state") in setOf("ended", "unknown")) {
                    if (activeRtcLeg?.node === node || (activeRtcLeg == null && incomingRtcLegs.values.all { it.snapshot.optBoolean("end_requested") || it.snapshot.getString("state") in setOf("ended", "unknown") })) {
                        telecomHandler.post { if (activeBusinessCallId == id) tearDown("Incoming call ended") }
                    }
                    return
                }
                if (snapshot.optString("candidate_client_id").isBlank() || activeRtcLeg != null) return
                activeRtcLeg = leg
                val gen = generation
                mediaWorker.execute { prepareRtcLeg(leg, gen, true) }
                return
            }
            if (activeRtcLeg?.snapshot?.getString("call_id") == id) {
                activeRtcLeg = leg
                if (snapshot.optBoolean("end_requested") || snapshot.getString("state") in setOf("ended", "unknown")) {
                    telecomHandler.post { if (activeBusinessCallId == id) tearDown("Remote ended") }
                }
                return
            }
            if (snapshot.getString("state") != "connecting" || snapshot.optBoolean("end_requested")) return
            // Existing dispatch rows, including recovered unknown, are never
            // replayed into another modem operation after reconnect/restart.
            if (bridgeState != BridgeState.IDLE) return
            activeRtcLeg = leg; activeBusinessCallId = id; activeGsmCall = null; activeGsmMapping = null
            mediaBridgeEstablished = false; bridgeState = BridgeState.GSM_DIALING
            val gen = generation
            telecomHandler.post { if (generation == gen) listener?.onStateChanged(BridgeState.GSM_DIALING, "Preparing call") }
            mediaWorker.execute { prepareRtcLeg(leg, gen, false) }
        }
    }

    private fun rtcLegCurrent(id: String, gen: Long) = !stopped && generation == gen && activeBusinessCallId == id &&
        bridgeState !in setOf(BridgeState.IDLE, BridgeState.TEARING_DOWN) && activeRtcLeg?.snapshot?.getString("call_id") == id

    private fun prepareRtcLeg(leg: RtcControlLeg, gen: Long, incoming: Boolean) {
        val snapshot = leg.snapshot; val id = snapshot.getString("call_id")
        try {
            val revision = if (leg.node.primary) snapshot.getLong("mapping_revision") else
                checkNotNull(nodeRevisionResolver(context, leg.node.gatewayId, snapshot.getString("sim_id"), snapshot.getLong("mapping_revision"))) { "RTC_NODE_SIM_CHANGED" }
            val mapping = outboundMappingResolver(context, snapshot.getString("sim_id"), revision)
            check(mapping.voiceAvailable && mapping.phoneAccountHandle != null)
            check(rtcLegCurrent(id, gen))
            val pcm = PcmBridge()
            var admission: JSONObject? = null
            val until = System.nanoTime() + 8_000_000_000L
            while (rtcLegCurrent(id, gen) && System.nanoTime() < until) {
                val next = leg.node.api.request("GET", "/calls/$id/media", null)
                if (next.getString("media_transport") == "webrtc") { admission = next; break }
                Thread.sleep(150)
            }
            val authorized = checkNotNull(admission) { "RTC_HOST_MEDIA_NOT_PREPARED" }
            check(authorized.getString("call_control") == "rtc")
            val binding = authorized.getJSONObject("binding")
            check(binding.getString("call_id") == id && binding.getString("gateway_id") == leg.node.gatewayId &&
                binding.getString("sim_id") == snapshot.getString("sim_id") && binding.getLong("mapping_revision") == snapshot.getLong("mapping_revision")) { "RTC_MEDIA_BINDING_CHANGED" }
            val media = RtcAudioSession(context, authorized, leg.node.api, true, pcm,
                onDigit = { digit -> receiveRtcDigit(digit, gen) }, onFailure = { reason ->
                    if (reason == "server_authorization_closed") {
                        // Control state owns the cellular call; media closure can race ahead of
                        // its 750 ms poll. Read that authority off Telecom's thread and outside
                        // the orchestrator lock, then fence delivery to this call generation.
                        val queued = runCatching {
                            terminalReconcileWorker.execute {
                                if (rtcLegCurrent(id, gen)) {
                                    val remoteEnd = rtcCallHasTerminalIntent { leg.node.control.get(id) }
                                    telecomHandler.post {
                                        dispatchRtcClosureIfCurrent(
                                            isCurrent = { rtcLegCurrent(id, gen) },
                                            remoteEnd = remoteEnd,
                                            onRemoteEnd = { tearDown("Remote ended") },
                                            onMediaFailure = { tearDown("Media failed: $reason") }
                                        )
                                    }
                                }
                            }
                        }.isSuccess
                        if (!queued) {
                            telecomHandler.post {
                                dispatchRtcClosureIfCurrent(
                                    isCurrent = { rtcLegCurrent(id, gen) },
                                    remoteEnd = false,
                                    onRemoteEnd = {},
                                    onMediaFailure = { tearDown("Media failed: $reason") }
                                )
                            }
                        }
                    } else {
                        telecomHandler.post { if (rtcLegCurrent(id, gen)) tearDown("Media failed: $reason") }
                    }
                }, onLog = { line -> listener?.onAudioStats(line) })
            synchronized(this) {
                check(rtcLegCurrent(id, gen))
                activeMedia = PreparedMedia(media, pcm)
            }
            media.start()
            val connectedUntil = System.nanoTime() + 20_000_000_000L
            if (incoming) {
                while (rtcLegCurrent(id, gen) && System.nanoTime() < connectedUntil && !media.isClosed) {
                    if (media.awaitNegotiated(250)) break
                }
                check(rtcLegCurrent(id, gen) && !media.isClosed && media.awaitNegotiated(0)) { "RTC_MEDIA_NOT_NEGOTIATED" }
                val fresh = outboundMappingResolver(context, snapshot.getString("sim_id"), revision)
                check(fresh == mapping) { "RTC_SIM_CHANGED_BEFORE_DISPATCH" }
                val candidate = snapshot.getString("candidate_client_id")
                // Committed SDP authorizes winner confirmation. Waiting for ICE
                // here can consume the cellular ringing window. PCM still waits
                // for both peer connectivity and the real GSM ACTIVE callback.
                check(GatewayDatabase.get(context).claimCallWinner(id, leg.node.gatewayId, candidate)) { "RTC_ANSWERED_ELSEWHERE" }
                leg.node.control.operation(id, "winner", candidate)
                rtcControl?.winner(id, leg.node.gatewayId)
                telecomHandler.post {
                    if (rtcLegCurrent(id, gen)) {
                        activeGsmCall?.let { call ->
                            Log.i("GsmRtc", "media=webrtc call_control=rtc call_id=$id event=cellular_answer_requested")
                            call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                        }
                    }
                }
            }
            while (rtcLegCurrent(id, gen) && System.nanoTime() < connectedUntil && !media.isClosed) {
                if (media.awaitConnected(250)) break
            }
            check(rtcLegCurrent(id, gen) && media.isConnected) { "RTC_MEDIA_NOT_CONNECTED" }
            if (!incoming) {
                val fresh = outboundMappingResolver(context, snapshot.getString("sim_id"), revision)
                check(fresh == mapping) { "RTC_SIM_CHANGED_BEFORE_DISPATCH" }
                check(outboundAudioPreparation()) { "RTC_CELLULAR_CONFIGURATION_UNAVAILABLE" }
                leg.node.control.operation(id, "dispatch")
                check(rtcLegCurrent(id, gen))
                val placed = outboundCallDispatcher(context, snapshot.getString("to"), fresh) { placeCall ->
                    synchronized(this) {
                        if (!rtcLegCurrent(id, gen)) false
                        else if (!GatewayDatabase.get(context).beginRtcCallDispatch(id, fresh.simId, fresh.mappingRevision)) false
                        else { activeGsmMapping = fresh; rtcCellularDispatched = true; placeCall() }
                    }
                }
                check(placed) { "RTC_CELLULAR_DISPATCH_UNCERTAIN" }
            }
            Log.i("GsmRtc", "media=webrtc call_control=rtc call_id=$id event=prepared")
        } catch (_: Throwable) {
            telecomHandler.post { if (rtcLegCurrent(id, gen)) tearDown("Call preparation failed") }
        }
    }

    private fun startRtcCellularAudio(call: Call) {
        val leg = activeRtcLeg ?: return
        synchronized(this) { if (rtcAudioStarting) return; rtcAudioStarting = true }
        val id = leg.snapshot.getString("call_id"); val gen = generation
        activeGsmCall = call
        mediaWorker.execute {
            if (!rtcLegCurrent(id, gen)) return@execute
            startAudio()
            val until = System.nanoTime() + 5_000_000_000L
            while (rtcLegCurrent(id, gen) && System.nanoTime() < until && activeMedia?.session?.cellularActive != true) Thread.sleep(50)
            if (!rtcLegCurrent(id, gen)) return@execute
            try {
                check(activeMedia?.session?.cellularActive == true && mediaBridgeEstablished)
                leg.node.control.operation(id, "active")
                telecomHandler.post {
                    if (rtcLegCurrent(id, gen)) { bridgeState = BridgeState.BRIDGED; listener?.onStateChanged(bridgeState, "Bridged") }
                }
            } catch (_: Throwable) { telecomHandler.post { if (rtcLegCurrent(id, gen)) tearDown("Cellular ACTIVE report failed") } }
        }
    }

    private var activeAudioSession: com.callagent.gateway.audio.CallAudioSession? = null
    @Volatile private var activeMedia: PreparedMedia? = null
    private val mediaWorker = Executors.newSingleThreadExecutor()
    private val terminalReconcileWorker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rtc-terminal-reconcile").apply { isDaemon = true }
    }
    @Volatile private var dtmfQueuedUntil = 0L
    @Volatile private var activeGsmCall: Call? = null
    @Volatile private var activeGsmMapping: SimRegistry.SimMapping? = null
    @Volatile private var activeBusinessCallId: String? = null
    /**
     * Incoming cellular calls this gateway gave up on without answering.
     * They are left ringing, like an unanswered phone, so the caller hears
     * ringback until they or the network give up instead of a hang-up.
     * Their later callbacks are not ours to act on.
     */
    private val releasedCalls = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Call, Boolean>())
    private var pendingInboundCall: Call? = null
    private var pendingInboundCallId: String? = null
    private var pendingInboundGeneration = -1L
    private var pendingInboundResolution: Future<*>? = null
    private var inboundFlowTask: Future<*>? = null
    @Volatile private var stopped = false
    @Volatile private var mediaBridgeEstablished = false
    private val telecomHandler = Handler(Looper.getMainLooper())
    // Main-thread only: the tone currently playing on the GSM call, if any.
    private var dtmfToneGeneration = 0L
    private var dtmfToneStartedAt = 0L

    /** SIM-account resolution can cross the Magisk broker and take seconds.
     *  Keep that IPC off Telecom's callback thread and make it interruptible
     *  when the call or this orchestrator is torn down. */
    private val inboundWorker: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "inbound-sim-resolver").apply { isDaemon = true }
    }

    /** One thread for every deferred bridge action. */
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bridge-timers").apply { isDaemon = true }
        }

    /** Bumped every time the bridge returns to IDLE.
     *
     *  Timers used to check nothing but [bridgeState], which is shared by every
     *  call there has ever been.  A timer of a call that already ended would
     *  then tear down a newer call.  Each timer now captures the generation it
     *  was scheduled in and does nothing if the bridge has moved on since. */
    @Volatile private var generation = 0L

    /** Run [action] after [delayMs], unless the bridge has moved on. */
    private fun schedule(delayMs: Long, action: () -> Unit) {
        val gen = generation
        timers.schedule(timerTask@{
            if (generation != gen) return@timerTask
            try { action() } catch (e: Exception) {
                Log.w(TAG, "Timer action failed: ${e.javaClass.simpleName}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Current bridge state */
    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onAudioStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,         // Incoming GSM, offered to the hosts
        GSM_DIALING,         // Outbound: preparing media or dialing the GSM number
        BRIDGED,             // Both sides active, audio flowing
        TEARING_DOWN         // Hanging up
    }

    fun start() {
        stopped = false
        synchronized(dispatchRecoveryLock) {
            if (!dispatchRecoveryComplete) {
                try {
                    val recovered = GatewayDatabase.get(context).recoverDispatchingCallsToUnknown()
                    Log.i(TAG, "Recovered $recovered interrupted call dispatch(es) as unknown")
                    dispatchRecoveryComplete = true
                } catch (e: Exception) {
                    // A new dispatch still fails closed if the durable ledger
                    // is unavailable; leave recovery retryable on next start.
                    Log.e(TAG, "Could not recover interrupted call dispatches: ${e.javaClass.simpleName}")
                }
            }
        }
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    @Synchronized
    fun stop() {
        stopped = true
        rtcControl?.close(); rtcControl = null
        cancelPendingInboundWork()
        inboundWorker.shutdownNow()
        mediaWorker.shutdownNow()
        terminalReconcileWorker.shutdownNow()
        tearDown("Orchestrator stopped", ledgerStateOverride = "unknown")
        GsmCallManager.listener = null
        timers.shutdownNow()
    }

    /** Cut the host's audio to the caller, leaving the call itself up. */
    fun setAgentMuted(on: Boolean) {
        val session = activeAudioSession
        if (session == null) {
            listener?.onError("No active call to mute")
            return
        }
        session.setAgentMuted(on)
    }

    /** Listen in on the active call through the phone's speaker.
     *
     *  Plays both sides mixed; the microphone stays muted, so nothing the room
     *  says reaches either the caller or the host. */
    fun setMonitorEnabled(on: Boolean) {
        val session = activeAudioSession
        if (session == null) {
            Log.w(TAG, "Monitor requested with no active call")
            listener?.onError("No active call to monitor")
            return
        }
        session.setMonitorEnabled(on)
    }

    // ── GsmCallManager.Listener ─────────────────────────

    /** Incoming GSM call — this is the INBOUND flow trigger */
    @Synchronized
    override fun onIncomingGsmCall(call: Call, number: String) {
        Log.i(TAG, "Incoming GSM call detected")

        // InCallService callbacks normally arrive on the main looper. Keep the
        // reservation there even on OEMs which invoke the listener elsewhere.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            telecomHandler.post { onIncomingGsmCall(call, number) }
            return
        }

        // Telecom can report the same Call through more than one callback.
        // Once reserved, it is already being resolved and must not be treated
        // as a competing call.
        if (activeGsmCall === call && bridgeState != BridgeState.IDLE) {
            Log.d(TAG, "Ignoring duplicate incoming callback for the reserved GSM call")
            return
        }
        if (call in releasedCalls) {
            Log.d(TAG, "Ignoring callback for an incoming call left unanswered")
            return
        }

        if (stopped || !isRingingOrActive(call)) {
            Log.w(TAG, "Ignoring incoming callback for a stopped or ended GSM call")
            return
        }

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting GSM call")
            rejectOrDisconnect(call)
            return
        }

        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "Leaving GSM call unanswered because microphone permission is unavailable")
            leaveUnanswered(call)
            listener?.onError("Call not offered: microphone permission is not granted")
            return
        }

        val callId = UUID.randomUUID().toString()
        val callGeneration = generation
        val accountHandle = call.details?.accountHandle

        // Reserve the one-call bridge before any slow broker work. This keeps
        // a second incoming call from taking over while account mapping runs.
        activeGsmMapping = null
        activeBusinessCallId = null
        mediaBridgeEstablished = false
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        pendingInboundCall = call
        pendingInboundCallId = callId
        pendingInboundGeneration = callGeneration
        listener?.onStateChanged(
            bridgeState,
            "Incoming GSM call (${CallerId.describe(number, call.details?.handlePresentation)})"
        )

        try {
            pendingInboundResolution = inboundWorker.submit {
                val mapping = try {
                    incomingAccountResolver(context, accountHandle)
                } catch (e: Exception) {
                    Log.w(TAG, "SIM account resolution failed: ${e.javaClass.simpleName}")
                    null
                }
                telecomHandler.post {
                    completeInboundSimResolution(call, number, callId, callGeneration, mapping)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule SIM account resolution: ${e.javaClass.simpleName}")
            failPendingInbound(call, callId, callGeneration,
                "Call not offered: SIM account mapping is unavailable")
        }
    }

    /** Runs on the main looper after the slow SIM lookup has returned. */
    @Synchronized
    private fun completeInboundSimResolution(
        call: Call,
        number: String,
        callId: String,
        callGeneration: Long,
        mapping: SimRegistry.SimMapping?
    ) {
        if (!isPendingInbound(call, callId, callGeneration)) return
        pendingInboundResolution = null

        if (!isRingingOrActive(call)) {
            Log.i(TAG, "Incoming GSM call ended before SIM mapping completed")
            tearDown("GSM call ended during SIM mapping")
            return
        }
        if (mapping == null) {
            Log.w(TAG, "Incoming GSM call has no verified SIM account mapping; leaving it unanswered")
            failPendingInbound(call, callId, callGeneration,
                "Call not offered: SIM account mapping is unavailable")
            return
        }

        // Persist dispatching before any network send. This write is small and
        // local; the potentially slow root/broker lookup above never runs here.
        val recorded = try {
            GatewayDatabase.get(context).beginCallDispatch(
                callId, mapping.simId, mapping.mappingRevision, "incoming"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Call ledger unavailable; leaving incoming GSM call unanswered")
            false
        }
        if (!recorded) {
            failPendingInbound(call, callId, callGeneration,
                "Call not offered: durable call ledger is unavailable")
            return
        }

        activeGsmMapping = mapping
        activeBusinessCallId = callId
        clearPendingInbound()

        Log.i(TAG, "GSM call is ringing; offering it to the hosts")
        if (!submitInboundFlow(call, mapping, callId, number, callGeneration)) {
            tearDown("Could not offer the incoming call", leaveRinging = true)
        }
    }

    @Synchronized
    private fun failPendingInbound(
        call: Call,
        callId: String,
        callGeneration: Long,
        error: String
    ) {
        if (!isPendingInbound(call, callId, callGeneration)) return
        listener?.onError(error)
        tearDown(error, leaveRinging = true)
    }

    private fun isPendingInbound(call: Call, callId: String, callGeneration: Long): Boolean =
        !stopped && generation == callGeneration && bridgeState == BridgeState.GSM_RINGING &&
            activeGsmCall === call && pendingInboundCall === call &&
            pendingInboundCallId == callId && pendingInboundGeneration == callGeneration

    private fun clearPendingInbound() {
        pendingInboundResolution?.cancel(true)
        pendingInboundResolution = null
        pendingInboundCall = null
        pendingInboundCallId = null
        pendingInboundGeneration = -1L
    }

    private fun cancelPendingInboundWork() {
        clearPendingInbound()
        inboundFlowTask?.cancel(true)
        inboundFlowTask = null
    }

    /** Stops handling [call] without rejecting it; it rings on until the caller or network ends it. */
    @android.annotation.SuppressLint("MissingPermission") // The default dialer may silence the ringer.
    private fun leaveUnanswered(call: Call) {
        releasedCalls.add(call)
        Log.i(TAG, "Leaving the GSM call unanswered")
        // This phone sits unattended; it should not ring out loud in the drawer.
        runCatching { context.getSystemService(TelecomManager::class.java)?.silenceRinger() }
    }

    private fun rejectOrDisconnect(call: Call) {
        try {
            when (call.state) {
                Call.STATE_RINGING -> GsmCallManager.rejectCall(call)
                Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING -> call.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not reject competing GSM call: ${e.javaClass.simpleName}")
        }
    }

    private fun isRingingOrActive(call: Call): Boolean = try {
        call.state == Call.STATE_RINGING || call.state == Call.STATE_ACTIVE
    } catch (_: Exception) {
        false
    }

    /** GSM call is now active (answered) */
    override fun onGsmCallActive(call: Call) {
        if (call in releasedCalls) return
        Log.i(TAG, "GSM call active")
        activeGsmCall = call
        if (activeRtcLeg != null) startRtcCellularAudio(call)
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        val stateStr = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "OTHER($state)"
        }
        Log.d(TAG, "GSM state: $stateStr")
        if (call in releasedCalls) {
            if (state == Call.STATE_DISCONNECTED) releasedCalls.remove(call)
            return
        }

        // Track the GSM call object as soon as we see it, so teardown works
        // even if the call never reaches ACTIVE (e.g. wrong number, rejected)
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
            // Teardown of this dispatch began before Telecom reported the call.
            if (bridgeState == BridgeState.TEARING_DOWN && state != Call.STATE_DISCONNECTED) {
                runCatching { call.disconnect() }
            }
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE && call === activeGsmCall) {
            tearDown("GSM call disconnected (${describeDisconnect(GsmCallManager.lastDisconnectCause)})")
        }
    }

    override fun onGsmCallEnded(call: Call) {
        if (releasedCalls.remove(call)) return
        Log.i(TAG, "GSM call ended")
        if (call === activeGsmCall) {
            tearDown("GSM call ended (${describeDisconnect(GsmCallManager.lastDisconnectCause)})")
        }
    }

    /** Who ended the GSM call, for the status line: the caller, this phone or the network. */
    private fun describeDisconnect(cause: DisconnectCause?): String {
        val who = when (cause?.code) {
            DisconnectCause.REMOTE -> "caller hung up"
            DisconnectCause.LOCAL -> "ended on this phone"
            DisconnectCause.REJECTED -> "rejected on this phone"
            DisconnectCause.MISSED -> "missed"
            DisconnectCause.BUSY -> "busy"
            DisconnectCause.CANCELED -> "cancelled"
            DisconnectCause.ERROR -> "network error"
            DisconnectCause.RESTRICTED -> "restricted"
            null -> "cause unknown"
            else -> "cause ${cause.code}"
        }
        val detail = cause?.reason?.takeIf { it.isNotBlank() }
        return if (detail != null) "$who, $detail" else who
    }

    private fun receiveRtcDigit(digit: Char, gen: Long) {
        telecomHandler.post {
            val session = activeAudioSession ?: return@post
            if (generation != gen || bridgeState != BridgeState.BRIDGED) return@post
            val now = SystemClock.elapsedRealtime()
            val delay = (dtmfQueuedUntil - now).coerceAtLeast(0)
            if (delay > 5000) return@post
            dtmfQueuedUntil = now + delay + 200
            telecomHandler.postDelayed({
                if (generation != gen || activeAudioSession !== session) return@postDelayed
                session.listener?.onDtmfTone(digit, true)
                telecomHandler.postDelayed({
                    if (generation == gen && activeAudioSession === session) session.listener?.onDtmfTone(digit, false)
                }, 120)
            }, delay)
        }
    }

    // ── Inbound flow (GSM → hosts) ─────────────────────

    private fun handleInboundFlow(
        gsmCall: Call,
        mapping: SimRegistry.SimMapping,
        callId: String,
        callerNumber: String,
        callGeneration: Long
    ) {
        try {
            SimRegistry.resolveForVoice(
                context, mapping.simId, mapping.mappingRevision, mapping.localRevisionBarrier
            )
        } catch (e: SimRegistry.SimMappingException) {
            Log.w(TAG, "Inbound call mapping changed before it was offered (${e.code})")
            postInboundFlowFailure(gsmCall, callId, mapping.simId, callGeneration,
                "SIM mapping changed before the call was offered")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not revalidate inbound SIM mapping: ${e.javaClass.simpleName}")
            postInboundFlowFailure(gsmCall, callId, mapping.simId, callGeneration,
                "SIM mapping could not be revalidated")
            return
        }
        if (!isInboundFlowIdentityCurrent(gsmCall, callId, mapping.simId, callGeneration)) return

        try {
            // Once a create is attempted, an error ends this call; it is
            // never offered a second time.
            val manager = checkNotNull(rtcControl) { "RTC_CONTROL_NOT_STARTED" }
            rtcIncoming = true
            manager.offerIncoming(mapping, callId, callerNumber) { isInboundFlowIdentityCurrent(gsmCall, callId, mapping.simId, callGeneration) }
            schedule(INCOMING_OFFER_TIMEOUT_MS) {
                if (activeBusinessCallId == callId && bridgeState != BridgeState.BRIDGED) tearDown("Incoming call was not answered in time", leaveRinging = true)
            }
        } catch (_: Throwable) {
            postInboundFlowFailure(gsmCall, callId, mapping.simId, callGeneration, "Incoming call could not be offered")
        }
    }

    @Synchronized
    private fun submitInboundFlow(
        gsmCall: Call,
        mapping: SimRegistry.SimMapping,
        callId: String,
        callerNumber: String,
        callGeneration: Long
    ): Boolean {
        if (stopped || inboundWorker.isShutdown ||
            !isInboundFlowIdentityCurrent(gsmCall, callId, mapping.simId, callGeneration)) return false
        return try {
            inboundFlowTask = inboundWorker.submit {
                handleInboundFlow(gsmCall, mapping, callId, callerNumber, callGeneration)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule inbound flow: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun isInboundFlowIdentityCurrent(
        gsmCall: Call,
        callId: String,
        simId: String,
        callGeneration: Long
    ): Boolean = !stopped && generation == callGeneration && activeGsmCall === gsmCall &&
        activeBusinessCallId == callId && activeGsmMapping?.simId == simId &&
        bridgeState != BridgeState.IDLE && bridgeState != BridgeState.TEARING_DOWN

    private fun postInboundFlowFailure(
        gsmCall: Call,
        callId: String,
        simId: String,
        callGeneration: Long,
        reason: String
    ) {
        telecomHandler.post {
            synchronized(this) {
                if (!isInboundFlowIdentityCurrent(gsmCall, callId, simId, callGeneration)) return@synchronized
                tearDown(reason, leaveRinging = true)
            }
        }
    }

    // ── Cellular audio ──────────────────────────────────

    private fun startAudio() {
        // Not @Synchronized, deliberately: tearDown() is, and it is reached
        // from the Telecom callback on the main thread, so sharing a monitor
        // with this method — which blocks for as long as AudioRecord takes to
        // come up — would hold the UI thread for seconds.  The generation
        // counter gives the same protection without the lock.
        val gen = generation
        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "RECORD_AUDIO is not granted; refusing to start call audio capture")
            listener?.onError("Call audio unavailable: microphone permission is not granted")
            tearDown("RECORD_AUDIO permission unavailable")
            return
        }
        if (!hasCallMicrophoneForeground()) {
            Log.e(TAG, "Call microphone foreground service is not active; refusing call audio capture")
            listener?.onError("Call audio unavailable: microphone foreground service is not active")
            tearDown("Microphone foreground service unavailable")
            return
        }
        val media = activeMedia
        if (generation != gen || media == null) {
            Log.w(TAG, "Bridge torn down before audio setup — not starting")
            return
        }

        activeAudioSession?.stop()
        val session = audioFactory(com.callagent.gateway.audio.CallAudioConfiguration(context, media.pcm))
        session.listener = object : CellularAudioSession.Listener {
            override fun onAudioStarted() {
                if (activeAudioSession === session) mediaBridgeEstablished = true
                activeMedia?.session?.let { rtc ->
                    if (!GsmCallManager.isCallActive) {
                        tearDown("Audio cannot start before cellular ACTIVE")
                    } else {
                        rtc.enableAudio(true)
                        rtc.markCellularActive()
                        listener?.onAudioStats("media=webrtc call_control=rtc call_id=${rtc.callId} event=cellular_pcm_started rate=16000")
                    }
                }
                Log.i(TAG, "Cellular audio started")
            }
            override fun onAudioStopped() {
                Log.i(TAG, "Cellular audio stopped")
            }
            override fun onAudioError(error: String) {
                if (activeAudioSession !== session) return
                Log.e(TAG, "Cellular audio error; ending call")
                listener?.onError("Call audio failed")
                tearDown("Audio error")
            }
            override fun onDtmfTone(digit: Char, active: Boolean) {
                // The digit is never logged: it may be a PIN.
                val targetCall = activeGsmCall ?: run {
                    if (active) listener?.onAudioStats("DTMF received but there is no GSM call to play it on")
                    return
                }
                telecomHandler.post {
                    if (activeGsmCall !== targetCall || targetCall.state != Call.STATE_ACTIVE) {
                        if (active) listener?.onAudioStats("DTMF received but the GSM call is not active")
                        return@post
                    }
                    try {
                        if (active) {
                            targetCall.playDtmfTone(digit)
                            val tone = ++dtmfToneGeneration
                            dtmfToneStartedAt = SystemClock.elapsedRealtime()
                            listener?.onAudioStats("DTMF received and played on the GSM call")
                            // Many modems mute the downlink while a tone plays, so a
                            // tone left on silences the far end for the rest of the call.
                            telecomHandler.postDelayed({
                                if (dtmfToneGeneration != tone || dtmfToneStartedAt == 0L) return@postDelayed
                                dtmfToneStartedAt = 0L
                                runCatching { targetCall.stopDtmfTone() }
                                listener?.onAudioStats("DTMF tone had no end after ${MAX_DTMF_TONE_MS} ms; stopped it")
                            }, MAX_DTMF_TONE_MS)
                        } else {
                            targetCall.stopDtmfTone()
                            val startedAt = dtmfToneStartedAt
                            dtmfToneStartedAt = 0L
                            if (startedAt != 0L) {
                                listener?.onAudioStats("DTMF tone stopped after ${SystemClock.elapsedRealtime() - startedAt} ms")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Telecom DTMF dispatch failed: ${e.javaClass.simpleName}")
                        if (active) listener?.onAudioStats("DTMF could not be played on the GSM call: ${e.javaClass.simpleName}")
                    }
                }
            }
            override fun onAudioTimeout() {
                if (activeAudioSession !== session) return
                Log.w(TAG, "Audio timeout — no frames from the host, tearing down")
                tearDown("Audio timeout")
            }
            override fun onAudioStats(stats: String) {
                if (activeAudioSession === session) listener?.onAudioStats(stats)
            }
        }
        // Published before start() so a teardown arriving mid-setup can find
        // and stop it rather than leaving an orphaned session holding the
        // audio devices.
        activeAudioSession = session
        session.start()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down during audio start — stopping orphaned session")
            session.stop()
            if (activeAudioSession === session) activeAudioSession = null
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCallMicrophoneForeground(): Boolean =
        (context as? GatewayService)?.hasCallMicrophoneForeground() == true

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    /**
     * [leaveRinging]: the incoming call was not answered here (no host took
     * it in time, or it could not be offered). If it still rings it is left
     * ringing rather than rejected, so the caller hears an unanswered phone.
     */
    private fun tearDown(
        reason: String,
        ledgerStateOverride: String? = null,
        dispatchUnseen: Boolean = false,
        leaveRinging: Boolean = false
    ) {
        if (bridgeState == BridgeState.IDLE) return
        val unanswered = activeGsmCall?.takeIf {
            leaveRinging && !mediaBridgeEstablished && runCatching { it.state == Call.STATE_RINGING }.getOrDefault(false)
        }
        if (unanswered != null) {
            leaveUnanswered(unanswered)
            activeGsmCall = null
        }
        val rtcCall = activeRtcLeg != null || rtcIncoming
        if (!dispatchUnseen) {
            if (bridgeState == BridgeState.TEARING_DOWN && (!rtcCall || activeGsmCall?.state != Call.STATE_DISCONNECTED)) return
            if (rtcCall && rtcCellularDispatched && activeGsmCall == null) {
                bridgeState = BridgeState.TEARING_DOWN
                dispatchUnseenSince = SystemClock.elapsedRealtime()
                activeMedia?.session?.close(); activeAudioSession?.stop()
                GatewayDatabase.get(context).markCallTerminal(checkNotNull(activeBusinessCallId), "unknown")
                return // A possibly dispatched modem operation cannot be guessed ended.
            }
        }
        if (rtcCall && activeGsmCall != null && activeGsmCall?.state != Call.STATE_DISCONNECTED) {
            bridgeState = BridgeState.TEARING_DOWN
            activeMedia?.session?.close(); activeAudioSession?.stop()
            cancelPendingInboundWork()
            val call = activeGsmCall
            telecomHandler.post { runCatching { call?.disconnect() } }
            return
        }
        val businessCallId = activeBusinessCallId
        val terminalState = ledgerStateOverride ?: if (mediaBridgeEstablished) "completed" else "failed"
        val controlEndQueued = rtcCall && businessCallId != null && terminalState != "unknown"
        bridgeState = BridgeState.TEARING_DOWN
        if (controlEndQueued) rtcControl?.ended(checkNotNull(businessCallId), terminalState)
        activeRtcLeg = null; rtcIncoming = false; rtcCellularDispatched = false; rtcAudioStarting = false; incomingRtcLegs.clear()
        cancelPendingInboundWork()
        Log.i(TAG, "Tearing down bridge: $reason")

        try {
            val mediaSession = activeMedia?.session
            if (controlEndQueued) mediaSession?.closeAfterControlEnd() else mediaSession?.close()
            activeMedia = null
            dtmfQueuedUntil = 0L
            activeAudioSession?.stop()
            activeAudioSession = null

            activeGsmCall?.let { call ->
                try {
                    // Always disconnect — not just when ACTIVE.  A ringing GSM
                    // call left behind was never answered or ended otherwise.
                    // Call.disconnect() works for RINGING, DIALING, and ACTIVE.
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.javaClass.simpleName}")
                }
            }
            activeGsmCall = null
        } finally {
            if (businessCallId != null) {
                try {
                    GatewayDatabase.get(context).markCallTerminal(businessCallId, terminalState)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not persist terminal call state: ${e.javaClass.simpleName}")
                }
            }
            bridgeState = BridgeState.IDLE
            generation++
            activeBusinessCallId = null
            activeGsmMapping = null
            mediaBridgeEstablished = false
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    companion object {
        private val dispatchRecoveryLock = Any()
        private var dispatchRecoveryComplete = false
        private const val TAG = "CallOrchestrator"
        /** The server invites the hosts for 25 seconds. */
        private const val INCOMING_OFFER_TIMEOUT_MS = 25_000L
        /** Longest a single keypress may hold a tone on the GSM call. */
        private const val MAX_DTMF_TONE_MS = 2_000L
        /** How long a dispatch may stay unreported by Telecom before it counts as never placed. */
        private const val UNSEEN_DISPATCH_SETTLE_MS = 30_000L
    }
}
