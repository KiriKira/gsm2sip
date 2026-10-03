package com.callagent.gateway.bridge

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.RtpPacket
import com.callagent.gateway.rtp.SrtpContext
import com.callagent.gateway.rtp.RtpSession
import com.callagent.gateway.service.GatewayService
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import com.callagent.gateway.sip.GsmCallMetadata
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the bidirectional GSM ↔ SIP bridge.
 *
 * Two call flows:
 *
 * INBOUND (someone calls the Israeli SIM):
 *   1. GSM rings → keep ringing, place SIP call to Asterisk
 *   2. Asterisk/agent answers SIP → answer GSM call
 *   3. GSM goes active → RTP starts immediately
 *   4. Audio flows: GSM speaker/mic ↔ RTP/SIP (shared hardware)
 *   5. Either side hangs up → terminate both
 *
 *   Caller hears normal ringing until the agent is ready, then
 *   picks up and hears the agent immediately — no dead air.
 *
 * OUTBOUND (Asterisk wants to call an Israeli number):
 *   1. SIP INVITE arrives with X-GSM-Forward header
 *   2. Dial GSM call to the destination
 *   3. GSM answers → SIP 200 OK
 *   4. Audio flows: SIP RTP ↔ GSM speaker/mic (shared hardware)
 *   5. Either side hangs up → terminate both
 */
class CallOrchestrator(
    private val context: Context,
    private val sipClient: SipClient,
    private val incomingAccountResolver: (Context, PhoneAccountHandle?) -> SimRegistry.SimMapping? =
        { appContext, handle -> SimRegistry.resolvePhoneAccount(appContext, handle) }
) : SipClient.Listener, GsmCallManager.Listener, SipCall.Listener {

    private var activeRtpSession: RtpSession? = null
    @Volatile private var activeSipCall: SipCall? = null
    @Volatile private var activeGsmCall: Call? = null
    @Volatile private var activeGsmMapping: SimRegistry.SimMapping? = null
    @Volatile private var activeBusinessCallId: String? = null
    @Volatile private var activeInboundCallerNumber: String? = null
    private var pendingInboundCall: Call? = null
    private var pendingInboundCallId: String? = null
    private var pendingInboundGeneration = -1L
    private var pendingInboundResolution: Future<*>? = null
    private var inboundFlowTask: Future<*>? = null
    @Volatile private var stopped = false
    @Volatile private var mediaBridgeEstablished = false
    private val telecomHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastStateChangeTime = 0L

    /** SIM-account resolution can cross the Magisk broker and take seconds.
     *  Keep that IPC off Telecom's callback thread and make it interruptible
     *  when the call or this orchestrator is torn down. */
    private val inboundWorker: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "inbound-sim-resolver").apply { isDaemon = true }
    }

    // Pending RTP info: saved when SIP answers before GSM is picked up.
    // onGsmCallActive reads these to start RTP immediately after GSM pickup.
    private var pendingRtpAddr: String? = null
    private var pendingRtpPort: Int = 0
    private var pendingPayloadType: Int = 0
    private var pendingLocalRtpPort: Int = 0

    // SIP call retry: if SIP fails while GSM is ringing, retry before giving up.
    // Transient network issues or socket races can kill the first attempt.
    private var sipCallRetries = 0
    private val MAX_SIP_RETRIES = 2

    /** One thread for every deferred bridge action, instead of a fresh Thread
     *  per dial, per INVITE and per retry. */
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bridge-timers").apply { isDaemon = true }
        }

    /** Bumped every time the bridge returns to IDLE.
     *
     *  Timers used to check nothing but [bridgeState], which is shared by every
     *  call there has ever been.  A dial that failed at t+5s left its 45s
     *  timeout running; a different call answered at t+20s was still setting up
     *  when that timer fired, saw a non-IDLE state and tore the new call down
     *  as "GSM dial timeout".  Each timer now captures the generation it was
     *  scheduled in and does nothing if the bridge has moved on since. */
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
        fun onRtpStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,        // Incoming GSM, waiting to answer
        GSM_ANSWERED,        // GSM answered, placing SIP call
        SIP_CALLING,         // SIP INVITE sent, waiting for answer
        SIP_RINGING,         // SIP ringing at Asterisk
        BRIDGED,             // Both sides active, audio flowing
        GSM_DIALING,         // Outbound: dialing GSM number
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
        sipClient.listener = this
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    @Synchronized
    fun stop() {
        stopped = true
        cancelPendingInboundWork()
        inboundWorker.shutdownNow()
        tearDown("Orchestrator stopped", ledgerStateOverride = "unknown")
        sipClient.listener = null
        GsmCallManager.listener = null
        timers.shutdownNow()
    }

    /**
     * The number this gateway answers on, sent as the SIP destination.
     *
     * The server maps an incoming number to an assistant the same way it does
     * a Fritz!Box DID, so it needs the MSISDN of our SIM here.  Addressing our
     * own SIP account instead makes the platform see account N calling account
     * N, match it as a local peer and ring us straight back — the INVITE comes
     * back, the orchestrator rejects it as busy, and the GSM leg is never
     * answered.
     *
     * Asks the platform first; many carriers do not publish the number on the
     * SIM, in which case the configured value is used.
     */
    private fun inboundSipDestination(mapping: SimRegistry.SimMapping): String {
        simNumber(mapping.subscriptionId)?.let {
            val intl = toInternational(it, mapping.subscriptionId)
            Log.i(TAG, "Using the mapped SIM's own number as SIP destination")
            return intl
        }
        val configured = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            .getString("own_number_${mapping.simId}", null)
            ?.trim()?.ifEmpty { null }
            ?: context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
                .getString("own_number", DEFAULT_OWN_NUMBER)?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            val intl = toInternational(configured)
            Log.i(TAG, "Using the configured own number as SIP destination")
            return intl
        }
        Log.w(TAG, "No own number known; SIP destination may loop back")
        return sipClient.username
    }

    /**
     * The destination always goes out in international form.  What we have to
     * start from varies: the platform may hand back E.164, the settings field
     * may hold a national number ("015112345678"), and either may use a 00
     * prefix.  PhoneNumberUtils resolves the national case against the SIM's
     * country rather than us guessing a dialling code.
     *
     * The caller number in From is deliberately NOT put through this — that one
     * is passed on exactly as the carrier delivered it.
     */
    private fun toInternational(number: String, subId: Int? = null): String {
        val trimmed = number.trim().filterNot { it == ' ' || it == '-' || it == '/' }
        if (trimmed.startsWith("+")) return trimmed
        if (trimmed.startsWith("00")) return "+" + trimmed.substring(2)
        val iso = try {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            (if (subId != null && subId >= 0) telephony?.createForSubscriptionId(subId) else telephony)
                ?.simCountryIso?.uppercase()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "SIM country unavailable")
            null
        }
        if (iso != null) {
            PhoneNumberUtils.formatNumberToE164(trimmed, iso)?.let { return it }
        }
        Log.w(TAG, "Cannot normalize mapped destination to E.164")
        return trimmed
    }

    /** The SIM's own number, when the carrier publishes it — many do not. */
    @SuppressLint("MissingPermission")
    private fun simNumber(subId: Int): String? = try {
        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(SubscriptionManager::class.java)?.getPhoneNumber(subId)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)
                ?.createForSubscriptionId(subId)?.line1Number
        }
        number?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        Log.w(TAG, "Mapped SIM number is unavailable")
        null
    }

    /** Cut the agent's audio to the caller, leaving the call itself up. */
    fun setAgentMuted(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            listener?.onError("No active call to mute")
            return
        }
        session.setAgentMuted(on)
    }

    /** Listen in on the active call through the phone's speaker.
     *
     *  Plays both sides mixed; the microphone stays muted, so nothing the room
     *  says reaches either the caller or the agent. */
    fun setMonitorEnabled(on: Boolean) {
        val session = activeRtpSession
        if (session == null) {
            Log.w(TAG, "Monitor requested with no active call")
            listener?.onError("No active call to monitor")
            return
        }
        session.setMonitorEnabled(on)
    }

    /**
     * Legacy local dialler entry point. A call without server-authenticated
     * sim_id/revision/call_id cannot safely choose one of two SIMs, so this
     * entry point fails closed. Production outbound calls arrive as an
     * authenticated SIP call intent handled below.
     */
    fun initiateDiallerCall(number: String) {
        Log.w(TAG, "Unrouted local dialler call rejected: no trusted SIM call metadata")
        listener?.onError("Call rejected: a server-authorized SIM mapping is required")
    }

    // ── SipClient.Listener ──────────────────────────────

    override fun onRegistered() {
        Log.i(TAG, "SIP registered — ready for calls")
        listener?.onStateChanged(BridgeState.IDLE, "SIP registered")
    }

    override fun onRegistrationFailed() {
        Log.e(TAG, "SIP registration failed")
        listener?.onError("SIP registration failed")
    }

    /** Incoming SIP INVITE from Asterisk */
    @Synchronized
    override fun onIncomingCall(call: SipCall) {
        Log.i(TAG, "Incoming SIP call: ${call.callId}")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting SIP call 486")
            call.reject(486, "Busy Here")
            sipClient.removeCall(call.callId)
            return
        }

        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "Rejecting SIP call because microphone permission is unavailable")
            call.reject(403, "Call Audio Permission Required")
            sipClient.removeCall(call.callId)
            listener?.onError("Call rejected: microphone permission is not granted")
            return
        }

        val metadata = call.trustedGsmMetadata
        if (metadata == null || metadata.protocolVersion != GsmCallMetadata.SUPPORTED_PROTOCOL_VERSION) {
            Log.w(TAG, "Rejecting SIP call with missing or untrusted GSM metadata")
            listener?.onError("Call rejected: authenticated SIM routing metadata is required")
            call.reject(403, "SIM Routing Metadata Required")
            sipClient.removeCall(call.callId)
            return
        }

        val mapping = try {
            SimRegistry.resolveForVoice(context, metadata.simId, metadata.mappingRevision)
        } catch (e: SimRegistry.SimMappingException) {
            val response = if (e.code == SimRegistry.ErrorCode.SIM_MAPPING_CHANGED) 409 else 480
            Log.w(TAG, "Rejecting call ${metadata.callId}: SIM mapping ${e.code}")
            listener?.onError("Call rejected: SIM mapping ${e.code}")
            call.reject(response, if (response == 409) "SIM Mapping Changed" else "SIM Unavailable")
            sipClient.removeCall(call.callId)
            return
        } catch (e: Exception) {
            Log.w(TAG, "SIM registry unavailable; rejecting call ${metadata.callId}")
            listener?.onError("Call rejected: SIM registry unavailable")
            call.reject(480, "SIM Unavailable")
            sipClient.removeCall(call.callId)
            return
        }

        val gsmDest = outboundDestination(call, mapping)
        if (gsmDest == null) {
            Log.w(TAG, "Trusted SIP call has no valid GSM destination")
            call.reject(488, "Not Acceptable Here")
            sipClient.removeCall(call.callId)
            return
        }

        val acceptedForDispatch = try {
            GatewayDatabase.get(context).beginCallDispatch(
                metadata.callId, metadata.simId, metadata.mappingRevision, "outgoing"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Call ledger unavailable; refusing GSM dispatch")
            false
        }
        if (!acceptedForDispatch) {
            Log.w(TAG, "Duplicate or unpersistable call_id ${metadata.callId}; refusing dispatch")
            call.reject(482, "Duplicate Call Id")
            sipClient.removeCall(call.callId)
            return
        }
        activeBusinessCallId = metadata.callId
        mediaBridgeEstablished = false
        activeGsmMapping = mapping
        handleOutboundFlow(call, gsmDest, mapping)
    }

    /**
     * The number an INVITE asks us to dial.
     *
     * X-GSM-Forward first, then the user part of the Request-URI — the same
     * order the SMS path resolves a recipient in, so that
     * Dial(SIP/<peer>/+49...) addresses a call the way it already addresses a
     * message, and a dialplan does not have to know that calls are the
     * exception.
     *
     * The Request-URI carries the account name whenever the server addresses
     * the peer rather than a number, and our own MSISDN when it routes the
     * SIM's DID back to us.  Dialling either would be a loop, so both are
     * ruled out before what is left is treated as a destination.
     */
    private fun outboundDestination(call: SipCall, mapping: SimRegistry.SimMapping): String? {
        call.gsmForwardNumber?.trim()?.ifEmpty { null }?.let { forwarded ->
            return forwarded.takeIf(::looksDialable)
        }

        val invite = call.originalInvite ?: return null
        val user = invite.requestUri
            ?.let { invite.extractUser(it) }
            ?.trim()?.ifEmpty { null } ?: return null

        if (user.equals(sipClient.username, ignoreCase = true)) return null
        if (digitsOf(user) == digitsOf(inboundSipDestination(mapping))) return null
        if (!looksDialable(user)) return null

        Log.i(TAG, "No X-GSM-Forward — destination taken from the Request-URI")
        return user
    }

    /**
     * Strict on purpose: a Request-URI user is a number only when that is all
     * it is.  An account name made of digits is already ruled out above; one
     * with a letter in it was never a number to begin with.
     */
    private fun looksDialable(user: String): Boolean =
        user.all { it.isDigit() || it in "+-.()" } &&
            digitsOf(user).length >= MIN_DIALABLE_DIGITS

    private fun digitsOf(value: String): String = value.filter { it.isDigit() }

    /** Handles termination from both SipClient.Listener and SipCall.Listener */
    override fun onCallTerminated(call: SipCall) {
        Log.i(TAG, "SIP call terminated: ${call.callId} (bridge=$bridgeState, retries=$sipCallRetries)")
        if (call != activeSipCall) return

        // If GSM is still ringing and we haven't exhausted retries, try again.
        // Transient network issues or socket races can kill the first SIP attempt.
        if ((bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)
            && sipCallRetries < MAX_SIP_RETRIES && activeGsmCall != null) {
            sipCallRetries++
            Log.w(TAG, "SIP call failed while GSM ringing — retrying ($sipCallRetries/$MAX_SIP_RETRIES)")
            listener?.onStateChanged(bridgeState, "SIP retry $sipCallRetries/$MAX_SIP_RETRIES")
            activeSipCall = null
            sipClient.removeCall(call.callId)
            // Retry after a short delay to let any transient issue settle
            schedule(1000) {
                if (bridgeState != BridgeState.SIP_CALLING &&
                    bridgeState != BridgeState.SIP_RINGING) return@schedule
                val mapping = activeGsmMapping
                val callId = activeBusinessCallId
                val gsmCall = activeGsmCall
                if (mapping != null && callId != null && gsmCall != null) {
                    val retryGeneration = generation
                    val callerNumber = activeInboundCallerNumber.orEmpty()
                    if (!submitInboundFlow(
                            gsmCall,
                            mapping,
                            GsmCallMetadata(
                                GsmCallMetadata.SUPPORTED_PROTOCOL_VERSION,
                                callId,
                                mapping.simId,
                                mapping.mappingRevision
                            ),
                            callerNumber,
                            retryGeneration
                        )) {
                        tearDown("Could not restart authenticated SIP call")
                    }
                } else {
                    Log.e(TAG, "SIP retry lacks the durable GSM call mapping; aborting")
                    tearDown("SIP retry mapping unavailable")
                }
            }
            return
        }

        tearDown("SIP call ended")
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
            Log.w(TAG, "Rejecting GSM call because microphone permission is unavailable")
            rejectOrDisconnect(call)
            listener?.onError("Call rejected: microphone permission is not granted")
            return
        }

        val callId = UUID.randomUUID().toString()
        val callGeneration = generation
        val accountHandle = call.details?.accountHandle

        // Reserve the one-call bridge before any slow broker work. This keeps
        // a second incoming call from taking over while account mapping runs.
        sipCallRetries = 0
        activeGsmMapping = null
        activeBusinessCallId = null
        activeInboundCallerNumber = number
        mediaBridgeEstablished = false
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        pendingInboundCall = call
        pendingInboundCallId = callId
        pendingInboundGeneration = callGeneration
        listener?.onStateChanged(bridgeState, "Incoming GSM call")

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
                "Call rejected: SIM account mapping is unavailable")
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
            Log.w(TAG, "Incoming GSM call has no verified SIM account mapping; rejecting")
            failPendingInbound(call, callId, callGeneration,
                "Call rejected: SIM account mapping is unavailable")
            return
        }

        // Persist dispatching before any network send. This write is small and
        // local; the potentially slow root/broker lookup above never runs here.
        val recorded = try {
            GatewayDatabase.get(context).beginCallDispatch(
                callId, mapping.simId, mapping.mappingRevision, "incoming"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Call ledger unavailable; rejecting incoming GSM call")
            false
        }
        if (!recorded) {
            failPendingInbound(call, callId, callGeneration,
                "Call rejected: durable call ledger is unavailable")
            return
        }

        activeGsmMapping = mapping
        activeBusinessCallId = callId
        activeInboundCallerNumber = number
        clearPendingInbound()

        val metadata = GsmCallMetadata(
            GsmCallMetadata.SUPPORTED_PROTOCOL_VERSION,
            callId,
            mapping.simId,
            mapping.mappingRevision
        )
        Log.i(TAG, "GSM call is ringing; resolving the confirmed SIM before SIP dispatch")
        if (!submitInboundFlow(call, mapping, metadata, number, callGeneration)) {
            tearDown("Could not start authenticated SIP call")
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
        tearDown(error)
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
        Log.i(TAG, "GSM call active")
        activeGsmCall = call

        when (bridgeState) {
            BridgeState.SIP_CALLING, BridgeState.SIP_RINGING -> {
                // INBOUND flow: GSM answered (triggered from onRtpReady).
                // SIP agent is ready — start RTP immediately so caller
                // hears the agent from the first moment.
                val addr = pendingRtpAddr
                val port = pendingRtpPort
                val pt = pendingPayloadType
                val localPort = pendingLocalRtpPort
                pendingRtpAddr = null

                if (addr != null && port > 0) {
                    Thread({
                        startRtp(localPort, addr, port, pt)
                        // Guard: tearDown may have run while startRtp was blocking
                        // (AudioRecord retries take 30+ seconds on cold boot).
                        // Don't overwrite IDLE — that causes "Busy" on next call.
                        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                            return@Thread
                        }
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (inbound)")
                        Log.i(TAG, "Inbound bridge established — zero dead air")
                    }, "RTP-Start").start()
                } else {
                    // Edge case: GSM answered but SIP RTP info not ready yet.
                    // This shouldn't happen in normal flow since we answer GSM
                    // from onRtpReady, but handle gracefully.
                    Log.w(TAG, "GSM active but no pending RTP info — waiting for SIP")
                    bridgeState = BridgeState.GSM_ANSWERED
                }
            }
            BridgeState.GSM_DIALING -> {
                // SIP-authorized OUTBOUND flow: GSM destination answered.
                bridgeState = BridgeState.BRIDGED
                listener?.onStateChanged(bridgeState, "Bridged (outbound)")

                // Answer the SIP call off the main thread
                Thread({
                    activeSipCall?.let { sipCall ->
                        val rtpPort = allocateRtpPort()
                        sipCall.listener = this
                        sipCall.accept(rtpPort)

                        val addr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
                        val port = sipCall.remoteRtpPort
                        val pt = sipCall.negotiatedPayloadType
                        if (port > 0) {
                            startRtp(rtpPort, addr, port, pt)
                        }
                    }
                    Log.i(TAG, "Outbound bridge established")
                }, "SIP-Bridge").start()
            }
            else -> {}
        }
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

        // Track the GSM call object as soon as we see it, so teardown works
        // even if the call never reaches ACTIVE (e.g. wrong number, rejected)
        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE && call === activeGsmCall) {
            tearDown("GSM call disconnected",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        }
    }

    /**
     * The SIP status that says why a GSM leg never connected.
     *
     * A provider that cannot complete a call answers the INVITE with a final
     * response the dialplan can branch on — busy, declined, unobtainable.
     * We used to send BYE instead, which is not a valid way to end an INVITE
     * that was never answered: the server replies 481 and then waits out its
     * own timer, so a busy number, a declined call and a dead SIM all looked
     * alike and all looked like a timeout.
     */
    private fun sipStatusFor(cause: DisconnectCause?): Pair<Int, String> =
        when (cause?.code) {
            DisconnectCause.BUSY -> 486 to "Busy Here"
            DisconnectCause.REJECTED -> 603 to "Decline"
            DisconnectCause.RESTRICTED -> 403 to "Forbidden"
            DisconnectCause.MISSED -> 480 to "Temporarily Unavailable"
            DisconnectCause.CANCELED, DisconnectCause.LOCAL -> 487 to "Request Terminated"
            DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> 503 to "Service Unavailable"
            DisconnectCause.ERROR -> 500 to "Server Internal Error"
            // REMOTE covers both "they hung up" and the causes the platform
            // does not break out, so it stays the generic unobtainable.
            else -> 480 to "Temporarily Unavailable"
        }

    override fun onGsmCallEnded(call: Call) {
        Log.i(TAG, "GSM call ended")
        // Tear down if this is our tracked call, OR if we're in a call state
        // but activeGsmCall was never set (call failed before going ACTIVE)
        if (call === activeGsmCall) {
            tearDown("GSM call ended",
                sipStatusFor(GsmCallManager.lastDisconnectCause))
        }
    }

    // ── SipCall.Listener ────────────────────────────────

    override fun onCallAnswered(call: SipCall) {
        Log.i(TAG, "SIP call answered: ${call.callId}")
    }

    // onCallTerminated is already implemented above (shared by SipClient.Listener and SipCall.Listener)

    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        if (call !== activeSipCall) {
            Log.d(TAG, "Ignoring RTP-ready callback from a stale SIP call")
            return
        }
        val codecName = when (payloadType) {
            RtpPacket.PT_G722 -> "G.722"
            RtpPacket.PT_PCMA -> "PCMA"
            RtpPacket.PT_PCMU -> "PCMU"
            else -> "PT$payloadType"
        }
        Log.i(TAG, "RTP ready: codec=$codecName bridgeState=$bridgeState")

        if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
            // Check if GSM is already active (dialler-initiated calls).
            // For inbound calls GSM is still ringing — answer it and wait for
            // onGsmCallActive to start RTP.  For dialler calls GSM is already
            // active so onGsmCallActive won't fire again — start RTP now.
            val gsmAlreadyActive = GsmCallManager.isCallActive

            if (gsmAlreadyActive) {
                Log.i(TAG, "SIP answered (codec=$codecName) — GSM already active, starting RTP now")
                val localRtpPort = call.localRtpPort
                Thread({
                    startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
                    if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                        Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                        return@Thread
                    }
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (dialler)")
                    Log.i(TAG, "Dialler bridge established (codec=$codecName)")
                }, "RTP-Start").start()
            } else {
                // INBOUND flow: SIP/agent answered — save RTP info and answer GSM.
                // When GSM goes active (onGsmCallActive), RTP starts immediately
                // so the caller hears the agent from the first moment.
                pendingRtpAddr = remoteRtpAddr
                pendingRtpPort = remoteRtpPort
                pendingPayloadType = payloadType
                pendingLocalRtpPort = call.localRtpPort

                val targetCall = activeGsmCall
                if (targetCall == null) {
                    Log.e(TAG, "SIP answered but no active GSM call to answer!")
                } else {
                    val callGeneration = generation
                    Log.i(TAG, "SIP answered (codec=$codecName) — checking GSM call before answering")
                    telecomHandler.post {
                        synchronized(this) {
                            if (stopped || generation != callGeneration ||
                                activeSipCall !== call || activeGsmCall !== targetCall ||
                                bridgeState !in setOf(BridgeState.SIP_CALLING, BridgeState.SIP_RINGING)) {
                                return@post
                            }
                            when (targetCall.state) {
                                Call.STATE_RINGING -> GsmCallManager.answerCall(targetCall)
                                Call.STATE_ACTIVE -> Unit
                                else -> tearDown("GSM call ended before answer")
                            }
                        }
                    }
                }
            }
        } else if (bridgeState == BridgeState.GSM_ANSWERED) {
            // Edge case: GSM was already answered (e.g. user picked up manually)
            // before SIP was ready.  Start RTP now.
            val localRtpPort = call.localRtpPort
            startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
            if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                return
            }
            bridgeState = BridgeState.BRIDGED
            listener?.onStateChanged(bridgeState, "Bridged (inbound)")
            Log.i(TAG, "Bridge established (codec=$codecName)")
        } else {
            Log.w(TAG, "onRtpReady ignored — bridgeState=$bridgeState (expected SIP_CALLING or SIP_RINGING)")
            listener?.onError("RTP ready but bridge state wrong: $bridgeState")
            Log.i(TAG, "Inbound bridge established — GSM was already active (codec=$codecName)")
        }
    }

    // ── Inbound flow (GSM → SIP) ───────────────────────

    private fun handleInboundFlow(
        gsmCall: Call,
        mapping: SimRegistry.SimMapping,
        metadata: GsmCallMetadata,
        callerNumber: String,
        callGeneration: Long
    ) {
        try {
            SimRegistry.resolveForVoice(
                context, mapping.simId, mapping.mappingRevision, mapping.localRevisionBarrier
            )
        } catch (e: SimRegistry.SimMappingException) {
            Log.w(TAG, "Inbound call mapping changed before SIP dispatch (${e.code})")
            postInboundFlowFailure(gsmCall, metadata, callGeneration,
                "SIM mapping changed before SIP dispatch")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not revalidate inbound SIM mapping: ${e.javaClass.simpleName}")
            postInboundFlowFailure(gsmCall, metadata, callGeneration,
                "SIM mapping could not be revalidated")
            return
        }
        if (!isInboundFlowIdentityCurrent(gsmCall, metadata, callGeneration)) return

        val sipDestination: String
        val rtpPort: Int
        try {
            // These may call into telephony and bind a socket; keep them away
            // from Telecom's callback looper as well.
            sipDestination = inboundSipDestination(mapping)
            rtpPort = allocateRtpPort()
        } catch (e: Exception) {
            Log.e(TAG, "Could not prepare inbound SIP call: ${e.javaClass.simpleName}")
            postInboundFlowFailure(gsmCall, metadata, callGeneration,
                "Could not prepare inbound SIP call")
            return
        }

        dispatchInboundFlow(
            gsmCall, metadata, callerNumber, sipDestination, rtpPort, callGeneration
        )
    }

    @Synchronized
    private fun submitInboundFlow(
        gsmCall: Call,
        mapping: SimRegistry.SimMapping,
        metadata: GsmCallMetadata,
        callerNumber: String,
        callGeneration: Long
    ): Boolean {
        if (stopped || inboundWorker.isShutdown ||
            !isInboundFlowIdentityCurrent(gsmCall, metadata, callGeneration)) return false
        return try {
            inboundFlowTask = inboundWorker.submit {
                handleInboundFlow(gsmCall, mapping, metadata, callerNumber, callGeneration)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule inbound flow: ${e.javaClass.simpleName}")
            false
        }
    }

    /** The last check and SIP send are serialized with tearDown(), so a
     *  delayed resolver cannot dispatch after the current call was retired.
     *  This runs on the inbound worker: makeCall sends an INVITE and must not
     *  run on Telecom's main callback thread. */
    @Synchronized
    private fun dispatchInboundFlow(
        gsmCall: Call,
        metadata: GsmCallMetadata,
        callerNumber: String,
        sipDestination: String,
        rtpPort: Int,
        callGeneration: Long
    ) {
        if (!isInboundFlowIdentityCurrent(gsmCall, metadata, callGeneration)) return
        if (!isRingingOrActive(gsmCall)) {
            tearDown("GSM call ended before SIP dispatch", sipStatusFor(GsmCallManager.lastDisconnectCause))
            return
        }
        if (bridgeState !in setOf(
                BridgeState.GSM_RINGING, BridgeState.GSM_ANSWERED,
                BridgeState.SIP_CALLING, BridgeState.SIP_RINGING
            )) return

        Log.i(TAG, "Inbound flow: placing authenticated SIP call")
        bridgeState = BridgeState.SIP_CALLING
        listener?.onStateChanged(bridgeState, "Calling Asterisk")

        try {
            val sipCall = sipClient.makeCall(
                targetExtension = sipDestination,
                localRtpPort = rtpPort,
                callerIdNumber = callerNumber,
                callerIdName = callerNumber,
                gsmMetadata = metadata
            )
            sipCall.listener = this
            activeSipCall = sipCall
            Log.i(TAG, "SIP INVITE sent to Asterisk")

            // Timeout: if Asterisk doesn't answer within 30s, tear down.
            schedule(SIP_CALL_TIMEOUT_MS) {
                if (generation == callGeneration &&
                    (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)) {
                    Log.w(TAG, "SIP call timeout — Asterisk didn't answer in ${SIP_CALL_TIMEOUT_MS / 1000}s")
                    tearDown("Asterisk not answering")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not send authenticated SIP call: ${e.javaClass.simpleName}")
            tearDown("SIP call setup failed")
        }
    }

    private fun isInboundFlowIdentityCurrent(
        gsmCall: Call,
        metadata: GsmCallMetadata,
        callGeneration: Long
    ): Boolean = !stopped && generation == callGeneration && activeGsmCall === gsmCall &&
        activeBusinessCallId == metadata.callId && activeGsmMapping?.simId == metadata.simId &&
        bridgeState != BridgeState.IDLE && bridgeState != BridgeState.TEARING_DOWN

    private fun postInboundFlowFailure(
        gsmCall: Call,
        metadata: GsmCallMetadata,
        callGeneration: Long,
        reason: String
    ) {
        telecomHandler.post {
            synchronized(this) {
                if (!isInboundFlowIdentityCurrent(gsmCall, metadata, callGeneration)) return@synchronized
                tearDown(reason, sipStatusFor(GsmCallManager.lastDisconnectCause))
            }
        }
    }

    // ── Outbound flow (SIP → GSM) ──────────────────────

    private fun handleOutboundFlow(
        sipCall: SipCall,
        gsmDestination: String,
        mapping: SimRegistry.SimMapping
    ) {
        Log.i(TAG, "Outbound flow: dialing through confirmed SIM mapping")

        bridgeState = BridgeState.GSM_DIALING
        activeSipCall = sipCall
        listener?.onStateChanged(bridgeState, "Dialing mapped SIM")

        // Send 180 Ringing to SIP caller while GSM dials
        sipCall.originalInvite?.let { invite ->
            val ringing = com.callagent.gateway.sip.SipBuilder.ringing180(invite, sipCall.localTag)
            sipClient.sendTo(ringing, sipCall.remoteContactAddress ?: sipClient.serverAddress)
        }

        // Dial via GSM SIM
        val destination = toInternational(gsmDestination, mapping.subscriptionId)
        val dispatched = GsmCallManager.makeCall(
            context,
            destination,
            mapping.simId,
            mapping.mappingRevision,
            mapping.localRevisionBarrier
        )
        if (!dispatched) {
            tearDown("SIM-directed Telecom dispatch failed", 480 to "SIM Unavailable")
            return
        }
        schedule(GSM_DIAL_TIMEOUT_MS) {
            if (bridgeState == BridgeState.GSM_DIALING) {
                Log.w(TAG, "GSM dial timeout — no call events in ${GSM_DIAL_TIMEOUT_MS / 1000}s")
                tearDown("GSM dial timeout", 480 to "Temporarily Unavailable")
            }
        }
    }

    // ── RTP ─────────────────────────────────────────────

    private fun startRtp(localPort: Int, remoteAddr: String, remotePort: Int,
                         payloadType: Int = RtpPacket.PT_G722) {
        // Not @Synchronized, deliberately: tearDown() is, and it is reached
        // from the Telecom callback on the main thread, so sharing a monitor
        // with this method — which blocks for as long as AudioRecord takes to
        // come up — would hold the UI thread for seconds.  The generation
        // counter gives the same protection without the lock.
        val gen = generation
        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "RECORD_AUDIO is not granted; refusing to start RTP capture")
            listener?.onError("Call audio unavailable: microphone permission is not granted")
            tearDown("RECORD_AUDIO permission unavailable")
            return
        }
        if (!hasCallMicrophoneForeground()) {
            Log.e(TAG, "Call microphone foreground service is not active; refusing RTP capture")
            listener?.onError("Call audio unavailable: microphone foreground service is not active")
            tearDown("Microphone foreground service unavailable")
            return
        }
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down before RTP setup — not starting")
            return
        }

        activeRtpSession?.stop()
        val session = RtpSession(
            context,
            localPort,
            remoteAddr,
            remotePort,
            payloadType,
            activeSipCall?.negotiatedTelephoneEventPayloadType,
            activeSipCall?.requiresSrtp == true
        )

        // Attach the negotiated SRTP keys, if this call has any.  Done before
        // start() so no packet is ever sent or accepted unprotected on a call
        // that agreed to be protected.
        activeSipCall?.let { call ->
            val local = call.localSrtpKeys
            val remote = call.remoteSrtpKeys
            if (local != null && remote != null) {
                session.srtpSend = SrtpContext(local)
                session.srtpRecv = SrtpContext(remote)
                Log.i(TAG, "SRTP enabled for this call (${local.suite.sdpName})")
            }
        }
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                if (activeRtpSession === session) mediaBridgeEstablished = true
                Log.i(TAG, "RTP session started")
            }
            override fun onRtpStopped() {
                Log.i(TAG, "RTP session stopped")
            }
            override fun onRtpError(error: String) {
                if (activeRtpSession !== session) return
                Log.e(TAG, "RTP media error; ending call")
                listener?.onError("Call audio failed")
                tearDown("RTP error")
            }
            override fun onDtmfTone(digit: Char, active: Boolean) {
                val targetCall = activeGsmCall ?: return
                telecomHandler.post {
                    if (activeGsmCall !== targetCall || targetCall.state != Call.STATE_ACTIVE) return@post
                    try {
                        if (active) targetCall.playDtmfTone(digit) else targetCall.stopDtmfTone()
                    } catch (e: Exception) {
                        Log.w(TAG, "Telecom DTMF dispatch failed: ${e.javaClass.simpleName}")
                    }
                }
            }
            override fun onRtpTimeout() {
                if (activeRtpSession !== session) return
                Log.w(TAG, "RTP timeout — no audio from Asterisk, tearing down")
                tearDown("RTP timeout")
            }
            override fun onRtpStats(stats: String) {
                if (activeRtpSession === session) listener?.onRtpStats(stats)
            }
        }
        // Published before start() so a teardown arriving mid-setup can find
        // and stop it rather than leaving an orphaned session holding the
        // audio devices and the RTP socket.
        activeRtpSession = session
        session.start()
        if (generation != gen) {
            Log.w(TAG, "Bridge torn down during RTP start — stopping orphaned session")
            session.stop()
            if (activeRtpSession === session) activeRtpSession = null
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCallMicrophoneForeground(): Boolean =
        (context as? GatewayService)?.hasCallMicrophoneForeground() == true

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    private fun tearDown(
        reason: String,
        sipStatus: Pair<Int, String>? = null,
        ledgerStateOverride: String? = null
    ) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        val businessCallId = activeBusinessCallId
        val terminalState = ledgerStateOverride ?: when {
            mediaBridgeEstablished -> "completed"
            sipStatus?.first == 487 -> "cancelled"
            else -> "failed"
        }
        bridgeState = BridgeState.TEARING_DOWN
        cancelPendingInboundWork()
        Log.i(TAG, "Tearing down bridge: $reason")

        try {
            activeRtpSession?.stop()
            activeRtpSession = null

            activeSipCall?.let {
                try {
                    if (it.state != SipCall.State.TERMINATED) {
                        // An INVITE we accepted is ended with BYE; one we never
                        // answered has to be turned down with a final response
                        // instead, which is also the only place the server ever
                        // learns why the GSM leg did not come up.
                        if (it.direction == SipCall.Direction.INBOUND &&
                            it.state != SipCall.State.ANSWERED) {
                            val (code, phrase) = sipStatus ?: (480 to "Temporarily Unavailable")
                            it.reject(code, phrase)
                        } else {
                            it.hangup()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error ending SIP call: ${e.javaClass.simpleName}")
                }
                sipClient.removeCall(it.callId)
            }
            activeSipCall = null

            activeGsmCall?.let { call ->
                try {
                    // Always disconnect — not just when ACTIVE.  If the SIP
                    // call fails before GSM is answered, the ringing GSM call
                    // was left dangling (S4 Mini: "second call never answered").
                    // Call.disconnect() works for RINGING, DIALING, and ACTIVE.
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.javaClass.simpleName}")
                }
            }
            activeGsmCall = null
            pendingRtpAddr = null
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
            lastStateChangeTime = System.currentTimeMillis()
            activeBusinessCallId = null
            activeGsmMapping = null
            activeInboundCallerNumber = null
            mediaBridgeEstablished = false
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    // ── Utility ─────────────────────────────────────────

    /**
     * Pick a free even UDP port for RTP.
     *
     * The scan starts at a random offset rather than always at 30000.  The
     * port is probed by binding and closing, so there is a gap before
     * RtpSession binds it for real; starting from the same place every time
     * meant consecutive calls raced each other for the very same port, which
     * is the one way that gap reliably loses.
     */
    private fun allocateRtpPort(): Int {
        val span = (RTP_PORT_MAX - RTP_PORT_MIN) / 2
        val start = (Math.random() * span).toInt()
        for (i in 0 until span) {
            val port = RTP_PORT_MIN + ((start + i) % span) * 2
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(port))
                    return port
                }
            } catch (_: Exception) {
                continue
            }
        }
        throw RuntimeException("No free RTP port available")
    }

    /** Force-reset bridge to IDLE, clearing all state.  Used to recover from
     *  stale states where the normal tearDown path was never triggered. */
    @Synchronized
    private fun forceReset(reason: String) {
        Log.w(TAG, "Force-resetting bridge: $reason")
        val businessCallId = activeBusinessCallId
        cancelPendingInboundWork()
        try {
            activeRtpSession?.stop()
        } catch (_: Exception) {}
        activeRtpSession = null
        try {
            activeSipCall?.let {
                if (it.state != SipCall.State.TERMINATED) it.hangup()
                sipClient.removeCall(it.callId)
            }
        } catch (_: Exception) {}
        activeSipCall = null
        try {
            activeGsmCall?.disconnect()
        } catch (_: Exception) {}
        activeGsmCall = null
        pendingRtpAddr = null
        if (businessCallId != null) {
            try {
                GatewayDatabase.get(context).markCallTerminal(businessCallId, "unknown")
            } catch (e: Exception) {
                Log.e(TAG, "Could not persist unknown call state: ${e.javaClass.simpleName}")
            }
        }
        activeBusinessCallId = null
        activeGsmMapping = null
        activeInboundCallerNumber = null
        mediaBridgeEstablished = false
        bridgeState = BridgeState.IDLE
        generation++
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(BridgeState.IDLE, reason)
        Log.i(TAG, "Bridge force-reset complete: $reason")
    }

    companion object {
        private val dispatchRecoveryLock = Any()
        private var dispatchRecoveryComplete = false
        private const val TAG = "CallOrchestrator"
        private const val SIP_CALL_TIMEOUT_MS = 30_000L
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        /** If bridge is non-IDLE for this long, consider it stale */
        private const val STALE_STATE_TIMEOUT_MS = 60_000L

        private const val RTP_PORT_MIN = 30000
        private const val RTP_PORT_MAX = 40000

        /** Placeholder shown in settings until a real MSISDN is entered. */
        private const val DEFAULT_OWN_NUMBER = "+49123123123123"

        /** Shortest Request-URI user we will believe is a number to dial. */
        private const val MIN_DIALABLE_DIGITS = 3
    }
}
