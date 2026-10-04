package com.callagent.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.BatteryManager
import android.telephony.TelephonyManager
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.background.GatewayBackgroundPolicy
import com.callagent.gateway.background.GatewayBackgroundRuntime
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.net.ControlApiException
import com.callagent.gateway.net.HeartbeatSim
import com.callagent.gateway.net.ServerCommand
import com.callagent.gateway.net.WakeConnection
import com.callagent.gateway.net.WakeConnectionPolicy
import com.callagent.gateway.net.WakeSessionIdentity
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.sms.LegacySmsMigration
import com.callagent.gateway.sms.SmsSender
import android.util.Log
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.GatewayApp
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.callagent.gateway.RootShell
import com.callagent.gateway.bridge.CallOrchestrator
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.net.StunClient
import com.callagent.gateway.sip.SipClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import kotlin.concurrent.thread

/**
 * User-enabled control service with an optional, explicitly started SIP voice runtime.
 * Control-only operation does not hold continuous CPU or Wi-Fi locks.
 */
class GatewayService : Service() {

    private var sipClient: SipClient? = null
    private var orchestrator: CallOrchestrator? = null
    private var voiceWakeLock: PowerManager.WakeLock? = null
    private var voiceWifiLock: WifiManager.WifiLock? = null
    private val powerLeaseScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gateway-power-leases").apply { isDaemon = true }
    }
    private val wakeRetryScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gateway-wake-retry").apply { isDaemon = true }
    }
    private val powerLeaseGuard = Any()
    private var voiceLeaseFuture: ScheduledFuture<*>? = null
    private var wifiLeaseFuture: ScheduledFuture<*>? = null
    @Volatile private var voiceRuntimeStarted = false
    @Volatile private var voiceLeaseWanted = false
    @Volatile private var voiceLeaseGeneration = 0L

    private val wakeConnectionGuard = Any()
    private var wakeConnection: WakeConnection? = null
    private var wakeIdentity: WakeSessionIdentity? = null
    private var wakeRetryFuture: ScheduledFuture<*>? = null
    private var wakeGeneration = 0L
    private var wakeRetryMs = WAKE_RETRY_INITIAL_MS
    @Volatile private var wakeAuthBlocked = false
    private var wakeAuthorizationTokenToRefresh: String? = null
    private val syncWakeLockGuard = Any()
    private var syncWakeLock: PowerManager.WakeLock? = null

    /** Saved config for reconnect */
    private var cfgServer = ""
    private var cfgPort = 5061
    private var cfgUser = ""
    private var cfgPass = ""
    private var currentLocalIp = ""

    // ── Call tracking ───────────────────────────────────
    private var onlineSince = 0L
    private var incomingCalls = 0
    private var incomingDurationSec = 0L
    private var outgoingCalls = 0
    private var outgoingDurationSec = 0L
    private var currentCallStart = 0L
    private var currentCallIncoming = true
    private var currentCallNumber = ""

    /** When the call first appeared, bridged or not.
     *
     *  currentCallStart only becomes non-zero once the bridge reaches BRIDGED,
     *  so every attempt that failed before that — an inbound call the SIP side
     *  rejected, an outbound number that never connected, a caller who hung up
     *  while it was ringing — used to leave no trace in the log at all.  Those
     *  are the calls most worth having a record of. */
    private var currentAttemptStart = 0L

    /** Prevents concurrent startGateway / reconnect threads */
    private val initializing = AtomicBoolean(false)
    private val controlLoopActive = AtomicBoolean(false)
    private val controlWakeSignal = Semaphore(0)
    @Volatile private var controlStop = false
    @Volatile private var foregroundStarted = false
    @Volatile private var callMicrophoneForegroundActive = false

    /** True only after this service successfully enters the call-media FGS mode. */
    fun hasCallMicrophoneForeground(): Boolean = foregroundStarted && callMicrophoneForegroundActive

    /**
     * Bumped on every bring-up.  A SIP init is slow — Magisk `su` can take
     * seconds, STUN can take seconds more — and the stale-init recovery
     * releases [initializing] after 90s whether or not that thread has
     * finished.  Without a generation the late thread published its own
     * SipClient over the newer one and the older client was never stopped:
     * it kept its socket on :5060 (SO_REUSEADDR lets several bind), kept its
     * monitor loop, and every REGISTER it sent timed out because the kernel
     * delivered the response to one socket only.  Each timeout escalated the
     * process-wide REGISTER backoff, so a healthy client ended up held off
     * for five minutes at a time.  The device had four live SipClients.
     */
    private val initGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** When [initializing] was last set, so a stuck flag can be detected.
     *
     *  If the init thread dies or hangs, this flag stays true forever, and then
     *  nothing can ever bring the gateway back: reconnect()'s compareAndSet
     *  always fails and startGateway()'s guard always skips.  The gateway sits
     *  silent — no REGISTER at all — until someone restarts the app by hand. */
    @Volatile private var initializingSince = 0L

    /** Whether the speaker monitor is on, for the notification's action label. */
    @Volatile private var monitorOn = false

    /** Whether the agent is muted towards the caller. */
    @Volatile private var agentMuted = false

    // ── Network change detection ────────────────────────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Last transport we logged, so the constant capability churn does not
     *  fill the log with identical lines. */
    private var lastTransport = ""

    /**
     * Describe the network actually carrying our traffic — "WiFi", "LTE",
     * "5G" — and log it when it changes.  A WiFi drop that hands over to
     * cellular, or an LTE re-attach, is exactly the kind of event that
     * explains a re-REGISTER after the fact, and none of it was visible:
     * transport changes only ever reached logcat.
     */
    private fun logTransportIfChanged() {
        val desc = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> mobileGeneration()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "other"
            }
        } catch (e: Exception) {
            "unknown (${e.message})"
        }
        if (desc == lastTransport) return
        val previous = lastTransport
        lastTransport = desc
        // First observation is the baseline, not a transition.
        if (previous.isEmpty()) broadcastLog("NET: on $desc")
        else broadcastLog("NET: $previous → $desc")
    }

    /** LTE / 5G / 3G for the data connection, mirroring the home view's label. */
    private fun mobileGeneration(): String = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> "Mobile"
        }
    } catch (_: SecurityException) {
        "Mobile"
    } catch (_: Exception) {
        "Mobile"
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                logTransportIfChanged()
                checkNetworkChanged()
                refreshVoicePowerLease()
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                refreshVoicePowerLease()
                // During an active GSM call, cellular data goes SUSPENDED which
                // fires onLost.  This is normal Android behavior — do NOT tear
                // down the bridge.  WiFi still carries SIP/RTP traffic.
                val busy = orchestrator?.bridgeState?.let {
                    it != CallOrchestrator.BridgeState.IDLE
                } ?: false
                if (busy) {
                    Log.i(TAG, "Skipping reconnect — call in progress (${orchestrator?.bridgeState})")
                    broadcastLog("NET: lost (ignored — call active)")
                    return
                }
                logTransportIfChanged()
                // Don't reconnect on the strength of onLost alone.  This
                // fires whenever any network goes away — cellular settling
                // after boot, mobile data dropping while WiFi carries the
                // registration perfectly well — and each one rebuilt the
                // socket and sent a fresh REGISTER for nothing.
                // checkNetworkChanged() reconnects only if the local IP
                // actually moved or the registration is genuinely gone.
                checkNetworkChanged()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                logTransportIfChanged()
                checkNetworkChanged()
                refreshVoicePowerLease()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun checkNetworkChanged() {
        // Skip if no prior IP (first start handles its own init)
        if (currentLocalIp.isEmpty()) return
        if (cfgServer.isEmpty()) return
        // During an active call, skip network checks — cellular SUSPENDED
        // is normal and WiFi handles SIP/RTP traffic.
        val busy = orchestrator?.bridgeState?.let {
            it != CallOrchestrator.BridgeState.IDLE
        } ?: false
        if (busy) return
        val newIp = getLocalIp()
        if (newIp == "0.0.0.0") return
        val ipChanged = newIp != currentLocalIp
        val notRegistered = sipClient?.registered != true
        if (ipChanged || notRegistered) {
            if (ipChanged) broadcastLog("NET: IP changed $currentLocalIp → $newIp, reconnecting")
            else broadcastLog("NET: registration lost, reconnecting")
            reconnect()
        }
    }

    private fun reconnect() {
        if (stopped || cfgServer.isEmpty()) return
        clearStaleInitializing()
        if (!initializing.compareAndSet(false, true)) {
            Log.i(TAG, "Reconnect skipped — already initializing")
            return
        }
        initializingSince = System.currentTimeMillis()
        onlineSince = 0L
        broadcastLog("SIP: reconnecting")
        broadcastStatus("STARTING", "Reconnecting...")
        updateNotification(NotifState.WARN, "Connecting")

        // Tear down existing client
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null

        val gen = initGeneration.incrementAndGet()
        thread(name = "gateway-reconnect") {
            try {
                initSipClient(gen)
            } finally {
                initializing.set(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
        RootShell.init()
        LegacySmsMigration.migrate(this)
        val recovered = GatewayDatabase.get(this).recoverUnknownDispatches()
        if (recovered > 0) broadcastLog("SMS: marked $recovered interrupted dispatch(es) unknown; no automatic retry")
        if (callRecoveryDone.compareAndSet(false, true)) {
            val recoveredCalls = GatewayDatabase.get(this).recoverDispatchingCallsToUnknown()
            if (recoveredCalls > 0) broadcastLog("CALL: marked $recoveredCalls interrupted call dispatch(es) unknown")
        }
        thread(name = "notif-setup") {
            applyNotificationVisibility()
        }
        Log.i(TAG, "GatewayService created")
    }

    /**
     * Report whether the gateway still holds the default-dialer role.
     *
     * Losing it is silent and total: Telecom stops binding GsmCallService, so
     * no incoming GSM call is ever seen, while SIP stays registered and the
     * app looks perfectly healthy.  Checked at every bring-up so the log
     * carries the answer without anyone having to go looking for it.
     */
    private fun checkDefaultDialer(): Boolean {
        return try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            val holder = tm.defaultDialerPackage
            val held = holder == packageName
            if (held) {
                broadcastLog("Default dialer: yes")
            } else {
                broadcastLog(
                    "WARNING: not the default dialer (${holder ?: "none"}) — " +
                        "incoming GSM calls will not reach the gateway"
                )
            }
            held
        } catch (e: Exception) {
            broadcastLog("Default dialer check failed: ${e.message}")
            false
        }
    }

    /**
     * Report whether incoming SMS can reach us at all.
     *
     * Without RECEIVE_SMS the broadcast is simply never delivered — no error,
     * no receiver call, nothing to notice — so the gateway would forward calls
     * perfectly while quietly dropping every message.
     */
    private fun checkSmsPermission() {
        val queued = GatewayDatabase.get(this).pendingEvents(CredentialStore.load(this)?.gatewayId.orEmpty()).size
        val legacy = GatewayDatabase.get(this).legacyOutboxCount()
        if (hasReceiveSms()) {
            broadcastLog("SMS receive: ready${if (queued > 0) " ($queued queued)" else ""}")
        } else {
            broadcastLog("WARNING: RECEIVE_SMS not granted — incoming SMS will be dropped")
        }
        if (hasSendSms()) {
            broadcastLog("SMS send: ready")
        } else {
            broadcastLog("WARNING: SEND_SMS not granted — send requests will be refused")
        }
        if (legacy > 0) broadcastLog("SMS: $legacy pre-v1 outbound item(s) preserved for manual review")
    }

    private fun hasReceiveSms(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Release the init flag if it has been held implausibly long. */
    private fun clearStaleInitializing() {
        if (initializing.get() &&
            System.currentTimeMillis() - initializingSince > INIT_STALE_MS
        ) {
            val heldFor = (System.currentTimeMillis() - initializingSince) / 1000
            Log.w(TAG, "init flag held ${heldFor}s — treating as stale and clearing")
            broadcastLog("Recovering from stuck initialisation (${heldFor}s)")
            initializing.set(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        clearStaleInitializing()
        if (intent == null) {
            val sessionPresent = CredentialStore.load(this) != null
            val mayRestore = GatewayBackgroundPolicy.mayRestoreControl(
                sessionPresent,
                GatewayBackgroundRuntime.allowedRecovery(this),
                GatewayBackgroundRuntime.userStopped(this)
            )
            if (mayRestore) {
                if (startControlGateway()) return START_STICKY
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (!sessionPresent && GatewayBackgroundRuntime.allowedRecovery(this)) {
                GatewayBackgroundRuntime.recordIssue(this, "Pair this device with the control server before background sync can resume")
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> startGateway(intent)
            ACTION_CONTROL_START -> startControlGateway()
            ACTION_STOP -> stopGateway(userInitiated = true)
            ACTION_RELOAD_STATS -> reloadStats()
            ACTION_STATUS -> broadcastCurrentStatus()
            ACTION_RECONNECT -> {
                if (!GatewayBackgroundRuntime.allowedRecovery(this)) {
                    broadcastStatus("STOPPED", "Gateway stopped by user")
                    if (sipClient == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                // Tapping the offline pill should act immediately.  If there is
                // a client, ask it to register now; if there is not, the
                // gateway is down and needs bringing up.
                val sip = sipClient
                broadcastStatus("STARTING", "Retrying…")
                if (sip != null && !stopped && voiceRuntimeStarted) {
                    sip.retryNow()
                } else if (voiceRuntimeStarted && !stopped) {
                    // Retry only a voice runtime already started by the visible diagnostics flow.
                    initializing.set(false)
                    reconnect()
                } else {
                    // The status pill retries control synchronization, never starts an idle microphone.
                    startControlGateway()
                }
            }
            ACTION_APPLY_CONFIG -> applyConfigChange()
            ACTION_SMS_SEND -> {
                if (GatewayBackgroundRuntime.allowedRecovery(this)) {
                    startControlGateway()
                }
            }
            ACTION_SMS_REPORT -> {
                if (GatewayBackgroundRuntime.allowedRecovery(this)) {
                    startControlGateway()
                }
            }
            ACTION_SMS_FLUSH -> {
                if (GatewayBackgroundRuntime.allowedRecovery(this)) {
                    startControlGateway()
                }
            }
            ACTION_DIAL -> dialFromDialler(intent)
            ACTION_MUTE_AGENT -> {
                agentMuted = if (intent.hasExtra(EXTRA_MUTE_ON)) {
                    intent.getBooleanExtra(EXTRA_MUTE_ON, false)
                } else {
                    !agentMuted
                }
                orchestrator?.setAgentMuted(agentMuted)
                broadcastStatus(
                    orchestrator?.bridgeState?.name ?: "IDLE",
                    if (agentMuted) "Agent muted" else "Agent unmuted"
                )
            }
            ACTION_MONITOR -> {
                // No extra means "toggle", which is what the notification
                // action sends; the in-call screen passes an explicit value.
                monitorOn = if (intent.hasExtra(EXTRA_MONITOR_ON)) {
                    intent.getBooleanExtra(EXTRA_MONITOR_ON, false)
                } else {
                    !monitorOn
                }
                orchestrator?.setMonitorEnabled(monitorOn)
                updateNotification(NotifState.OK)
            }
            else -> {
                Log.w(TAG, "Ignoring unknown service action")
            }
        }
        val mayRestoreControl = GatewayBackgroundPolicy.mayRestoreControl(
            CredentialStore.load(this) != null,
            GatewayBackgroundRuntime.allowedRecovery(this),
            GatewayBackgroundRuntime.userStopped(this)
        )
        return if (foregroundStarted && mayRestoreControl) START_STICKY else START_NOT_STICKY
    }

    /**
     * Re-read the saved configuration and rebuild the SIP client with it.
     *
     * ACTION_RECONNECT deliberately only asks the *existing* client to
     * register again, which is right for the status pill but wrong for a
     * settings save: server, port, credentials and the STUN choice are all
     * read once at bring-up, so editing them and pressing SAVE changed
     * nothing until the next restart.
     */
    private fun applyConfigChange() {
        if (!GatewayBackgroundRuntime.allowedRecovery(this)) {
            // A queued settings action must not undo an explicit stop or a
            // Task Manager stop by rebuilding the SIP runtime.
            stopGateway(userInitiated = false)
            return
        }
        if (!voiceRuntimeStarted) {
            // Saving SIP credentials configures the next visible voice start;
            // it does not itself grant permission to start microphone mode.
            if (CredentialStore.load(this) != null) {
                startControlGateway()
            } else {
                broadcastLog("SIP config saved; voice mode remains off until started from the app")
                broadcastStatus("PAIRING_REQUIRED", "Pair this phone with the control server")
            }
            return
        }

        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        // Re-post first, so toggling the status bar setting takes effect now
        // rather than at the next restart -- the channel is chosen when the
        // notification is built.  Done before the validity check below, since
        // the setting is independent of whether SIP is configured.
        // Remove before re-posting.  A notification's channel is fixed when it
        // is first posted: re-posting the same id on a different channel is
        // silently ignored, so toggling the status bar setting appeared to do
        // nothing until the service happened to restart.  Measured both ways
        // -- quiet to normal and back -- and neither moved without this.
        runCatching {
            startForegroundMode(
                callActive = orchestrator?.bridgeState?.let { it != CallOrchestrator.BridgeState.IDLE } ?: false,
                status = notifStatusText
            )
            cancelStaleNotification()
        }.onFailure { Log.w(TAG, "Could not re-post notification: ${it.message}") }
        thread(name = "notif-visibility") {
            applyNotificationVisibility()
        }
        cfgServer = prefs.getString("server", "") ?: ""
        cfgPort = prefs.getInt("port", 5061)
        cfgUser = prefs.getString("user", "") ?: ""
        cfgPass = prefs.getString("pass", "") ?: ""
        if (cfgServer.isEmpty() || cfgUser.isEmpty()) {
            orchestrator?.stop()
            sipClient?.stop()
            orchestrator = null
            sipClient = null
            if (CredentialStore.load(this) != null) {
                startControlGateway()
            } else {
                broadcastLog("SIP is unconfigured; pair this gateway with the HTTPS control server")
                broadcastStatus("PAIRING_REQUIRED", "Pair this phone with the control server")
            }
            return
        }
        broadcastLog("Config changed — rebuilding SIP client")
        // A save is an explicit instruction, so it outranks a bring-up that
        // is already in flight; the generation counter makes discarding that
        // one safe.
        stopped = false
        initializing.set(false)
        reconnect()
    }

    // ── HTTPS event journal and command ledger ───────────

    /** Wake an existing poller or launch it once. Network work always stays off UI/broadcast threads. */
    private fun startControlLoop() {
        if (!GatewayBackgroundRuntime.allowedRecovery(this) || CredentialStore.load(this) == null) return
        controlStop = false
        if (!controlLoopActive.compareAndSet(false, true)) {
            if (controlWakeSignal.availablePermits() == 0) controlWakeSignal.release()
            return
        }
        thread(name = "gateway-control") {
            var retryMs = CONTROL_INTERVAL_MS
            try {
                while (!controlStop && !stopped) {
                    if (!GatewayBackgroundRuntime.allowedRecovery(this@GatewayService)) break
                    if (CredentialStore.load(this@GatewayService) == null) {
                        GatewayBackgroundRuntime.updateConnection(this@GatewayService, "Not paired",
                            "Pair this device with the control server before background sync can resume")
                        break
                    }
                    var nextRetryMs = retryMs
                    try {
                        if (!withNetworkSyncWakeLock { syncControlPlaneOnce() }) break
                        GatewayBackgroundRuntime.markSyncSucceeded(this@GatewayService)
                        retryMs = CONTROL_INTERVAL_MS
                    } catch (e: ControlApiException) {
                        retryMs = if (e.retryable) (retryMs * 2).coerceAtMost(CONTROL_MAX_RETRY_MS)
                        else CONTROL_INTERVAL_MS
                        nextRetryMs = retryMs
                        val delaySeconds = (nextRetryMs / 1000).coerceAtLeast(1)
                        broadcastLog("CONTROL: ${e.code}; HTTPS retry in ${delaySeconds}s")
                        GatewayBackgroundRuntime.recordIssue(this@GatewayService,
                            if (e.code == "PAIRING_REQUIRED" || e.code == "SESSION_REVOKED")
                                "Pair this device again to resume background sync"
                            else "Control sync failed (${e.code}); retrying in ${delaySeconds}s")
                        updateNotification(NotifState.WARN, "Gateway · retrying")
                        if (e.code == "PAIRING_REQUIRED" || e.code == "SESSION_REVOKED") {
                            GatewayBackgroundRuntime.updateConnection(this@GatewayService, "Not paired",
                                "Pair this device again to resume background sync")
                            if (sipClient == null) stopSelf()
                            break
                        }
                    } catch (_: Exception) {
                        retryMs = (retryMs * 2).coerceAtMost(CONTROL_MAX_RETRY_MS)
                        nextRetryMs = retryMs
                        val delaySeconds = (nextRetryMs / 1000).coerceAtLeast(1)
                        broadcastLog("CONTROL: HTTPS sync failed; retry in ${delaySeconds}s")
                        GatewayBackgroundRuntime.recordIssue(this@GatewayService,
                            "Control sync failed; retrying in ${delaySeconds}s")
                        updateNotification(NotifState.WARN, "Gateway · retrying")
                    }
                    if (!controlStop && !stopped && GatewayBackgroundRuntime.allowedRecovery(this@GatewayService)) {
                        controlWakeSignal.tryAcquire(nextRetryMs, TimeUnit.MILLISECONDS)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                controlLoopActive.set(false)
            }
        }
    }

    private fun wakeControlLoop() {
        if (!GatewayBackgroundRuntime.allowedRecovery(this)) return
        if (controlLoopActive.get() && controlWakeSignal.availablePermits() == 0) controlWakeSignal.release()
        startControlLoop()
    }

    private fun reconcileWakeConnection(session: CredentialStore.Session) {
        if (!wakeRuntimeAllowed()) {
            closeWakeConnection()
            return
        }
        connectWakeSession(session)
    }

    private fun connectWakeSession(session: CredentialStore.Session) {
        val identity = WakeSessionIdentity.from(session)
        synchronized(wakeConnectionGuard) {
            if (!wakeRuntimeAllowed()) return
            val currentIdentity = wakeIdentity
            if (wakeAuthBlocked && wakeAuthorizationTokenToRefresh == identity.tokenGeneration) {
                GatewayBackgroundRuntime.updateConnection(this, "HTTPS polling · wake auth pending",
                    "Wake authorization failed; HTTPS polling is waiting for token refresh")
                return
            }
            if (currentIdentity == identity && wakeConnection != null) return

            val tokenChanged = currentIdentity != identity
            if (tokenChanged) {
                wakeGeneration++
                wakeIdentity = identity
                wakeRetryMs = WAKE_RETRY_INITIAL_MS
            }
            wakeRetryFuture?.cancel(false)
            wakeRetryFuture = null
            wakeAuthBlocked = false
            wakeAuthorizationTokenToRefresh = null
            wakeConnection?.close()
            wakeConnection = null
            startWakeConnectionLocked(session, identity, wakeGeneration)
        }
    }

    private fun startWakeConnectionLocked(
        session: CredentialStore.Session,
        identity: WakeSessionIdentity,
        generation: Long
    ) {
        try {
            wakeConnection = WakeConnection(session,
                isCurrent = { isCurrentWake(generation, identity) },
                listener = object : WakeConnection.Listener {
                    override fun onOpen() {
                        if (!isCurrentWake(generation, identity)) return
                        synchronized(wakeConnectionGuard) {
                            if (generation != wakeGeneration || identity != wakeIdentity) return
                            wakeRetryMs = WAKE_RETRY_INITIAL_MS
                            wakeRetryFuture?.cancel(false)
                            wakeRetryFuture = null
                        }
                        GatewayBackgroundRuntime.updateConnection(this@GatewayService, "WSS connected · HTTPS polling")
                        broadcastLog("CONTROL: WSS wake channel connected; HTTPS polling remains enabled")
                        updateNotification(NotifState.OK, "Gateway · WSS connected")
                    }

                    override fun onSyncRequired() {
                        if (isCurrentWake(generation, identity)) wakeControlLoop()
                    }

                    override fun onClosed(code: Int) {
                        handleWakeDisconnect(generation, identity, closeCode = code)
                    }

                    override fun onFailure(httpStatus: Int?) {
                        handleWakeDisconnect(generation, identity, httpStatus = httpStatus)
                    }

                    override fun onInvalidFrame() {
                        handleWakeDisconnect(generation, identity, invalidFrame = true)
                    }
                })
            GatewayBackgroundRuntime.updateConnection(this, "WSS connecting · HTTPS polling")
            updateNotification(NotifState.WARN, "Gateway · connecting")
        } catch (_: Exception) {
            wakeConnection = null
            GatewayBackgroundRuntime.recordIssue(this, "Wake channel could not be opened; HTTPS polling will continue")
            scheduleWakeRetryLocked(generation, identity)
        }
    }

    private fun isCurrentWake(generation: Long, identity: WakeSessionIdentity): Boolean {
        val fenced = synchronized(wakeConnectionGuard) {
            WakeConnectionPolicy.callbackIsCurrent(generation, wakeGeneration, identity, wakeIdentity)
        }
        if (!fenced || !wakeRuntimeAllowed()) return false
        val currentSession = CredentialStore.load(this) ?: return false
        return WakeSessionIdentity.from(currentSession) == identity
    }

    private fun handleWakeDisconnect(
        generation: Long,
        identity: WakeSessionIdentity,
        httpStatus: Int? = null,
        closeCode: Int? = null,
        invalidFrame: Boolean = false
    ) {
        if (!isCurrentWake(generation, identity)) return
        val authorizationFailure = WakeConnectionPolicy.requiresHttpsRefresh(httpStatus, closeCode)
        val (oldConnection, retryGeneration) = synchronized(wakeConnectionGuard) {
            if (generation != wakeGeneration || wakeIdentity != identity) return
            val old = wakeConnection
            wakeConnection = null
            wakeGeneration++
            if (authorizationFailure) {
                wakeAuthBlocked = true
                wakeAuthorizationTokenToRefresh = identity.tokenGeneration
                wakeRetryFuture?.cancel(false)
                wakeRetryFuture = null
            }
            old to wakeGeneration
        }
        oldConnection?.close()

        if (authorizationFailure) {
            GatewayBackgroundRuntime.updateConnection(this, "HTTPS reauth pending",
                "Wake channel authorization expired; refreshing through HTTPS")
            broadcastLog("CONTROL: WSS authorization expired; checking the session over HTTPS")
            updateNotification(NotifState.WARN, "Gateway · HTTPS fallback")
            wakeControlLoop()
            return
        }

        val detail = when {
            invalidFrame -> "Wake server sent an unsupported frame; HTTPS polling remains active"
            httpStatus == 403 -> "Wake server rejected the connection; HTTPS polling remains active"
            else -> "Wake channel disconnected; HTTPS polling remains active"
        }
        GatewayBackgroundRuntime.updateConnection(this, "HTTPS polling · WSS retrying", detail)
        broadcastLog("CONTROL: WSS disconnected; HTTPS polling continues")
        updateNotification(NotifState.WARN, "Gateway · retrying")
        scheduleWakeRetry(retryGeneration, identity)
    }

    private fun scheduleWakeRetry(generation: Long, identity: WakeSessionIdentity) {
        synchronized(wakeConnectionGuard) { scheduleWakeRetryLocked(generation, identity) }
    }

    private fun scheduleWakeRetryLocked(generation: Long, identity: WakeSessionIdentity) {
        if (generation != wakeGeneration || identity != wakeIdentity || wakeAuthBlocked ||
            wakeRetryFuture != null || !wakeRuntimeAllowed()
        ) return
        val baseDelay = wakeRetryMs
        wakeRetryMs = (wakeRetryMs * 2).coerceAtMost(WAKE_RETRY_MAX_MS)
        val jitteredDelay = (baseDelay * ThreadLocalRandom.current().nextDouble(0.8, 1.2)).toLong()
            .coerceAtLeast(1_000L)
        GatewayBackgroundRuntime.updateConnection(this, "HTTPS polling · WSS retrying")
        broadcastLog("CONTROL: WSS retry scheduled in ${jitteredDelay / 1000}s")
        wakeRetryFuture = try {
            wakeRetryScheduler.schedule({
                synchronized(wakeConnectionGuard) {
                    if (generation != wakeGeneration || identity != wakeIdentity) return@schedule
                    wakeRetryFuture = null
                }
                if (!wakeRuntimeAllowed()) return@schedule
                val session = CredentialStore.load(this) ?: return@schedule
                connectWakeSession(session)
            }, jitteredDelay, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
    }

    private fun closeWakeConnection() {
        synchronized(wakeConnectionGuard) {
            wakeGeneration++
            wakeRetryFuture?.cancel(false)
            wakeRetryFuture = null
            wakeAuthBlocked = false
            wakeAuthorizationTokenToRefresh = null
            wakeIdentity = null
            wakeConnection?.close()
            wakeConnection = null
        }
        GatewayBackgroundRuntime.updateConnection(this, "Stopped")
    }

    private fun wakeRuntimeAllowed(): Boolean =
        !controlStop && !stopped && foregroundStarted && GatewayBackgroundRuntime.allowedRecovery(this)

    private fun startControlGateway(): Boolean {
        if (!GatewayBackgroundRuntime.allowedRecovery(this)) {
            broadcastStatus("STOPPED", "Gateway stopped by user")
            if (sipClient == null) stopSelf()
            return false
        }
        val session = CredentialStore.load(this)
        if (session == null) {
            GatewayBackgroundRuntime.updateConnection(this, "Not paired",
                "Pair this device with the control server before background sync can resume")
            broadcastStatus("PAIRING_REQUIRED", "Pair this phone with the control server")
            if (sipClient == null) stopSelf()
            return false
        }
        stopped = false
        val wasForegroundStarted = foregroundStarted
        val callActive = orchestrator?.bridgeState?.let { it != CallOrchestrator.BridgeState.IDLE } ?: false
        try {
            val mediaModeAllowed = callActive && voiceRuntimeStarted
            val status = if (mediaModeAllowed) "In-Call · syncing" else "Gateway syncing"
            if (mediaModeAllowed && foregroundStarted) setForegroundCallMode(true, status)
            else startForegroundMode(callActive = false, status = status)
            GatewayBackgroundRuntime.markServiceStarted(this, "HTTPS polling · WSS connecting")
        } catch (e: Exception) {
            if (!wasForegroundStarted) {
                foregroundStarted = false
                callMicrophoneForegroundActive = false
            }
            broadcastLog("CONTROL: foreground service unavailable (${e.javaClass.simpleName})")
            GatewayBackgroundRuntime.recordIssue(this,
                "Android refused the foreground service start; open the app and retry")
            broadcastStatus("CONTROL_START_FAILED", "Foreground service start failed")
            if (sipClient == null) {
                stopSelf()
                GatewayBackgroundRuntime.markServiceStopped(this)
            }
            return false
        }
        broadcastLog("CONTROL: paired gateway session active")
        startControlLoop()
        return true
    }

    private fun startForegroundMode(callActive: Boolean, status: String) {
        notifStatusText = status
        val notification = buildNotification(if (callActive) NotifState.OK else NotifState.WARN, status)
        callMicrophoneForegroundActive = false
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (callActive) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(activeNotificationId(), notification, type)
        } else if (Build.VERSION.SDK_INT >= 30) {
            val type = if (callActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(activeNotificationId(), notification, type)
        } else if (Build.VERSION.SDK_INT >= 29) {
            val type = if (callActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
            startForeground(activeNotificationId(), notification, type)
        } else {
            startForeground(activeNotificationId(), notification)
        }
        foregroundStarted = true
        callMicrophoneForegroundActive = callActive
    }

    private fun setForegroundCallMode(callActive: Boolean, status: String) {
        if (!foregroundStarted) return
        if (!callActive) {
            startForegroundMode(false, status)
            return
        }
        val audioPermissionGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val ownsCallRole = checkSelfPermission(android.Manifest.permission.MANAGE_OWN_CALLS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!voiceRuntimeStarted || !audioPermissionGranted || !ownsCallRole) {
            startForegroundMode(false, status)
            val reason = when {
                !voiceRuntimeStarted -> "Open the app and start voice mode before calls can use the microphone"
                !audioPermissionGranted -> "Grant microphone permission in the app before voice calls"
                else -> "Restore the phone-call role or permission before voice calls"
            }
            GatewayBackgroundRuntime.recordIssue(this, reason)
            broadcastLog("CALL: microphone foreground mode is not permitted yet")
            return
        }
        startForegroundMode(true, status)
    }

    private fun syncControlPlaneOnce() {
        val session = CredentialStore.load(this)
            ?: throw ControlApiException("PAIRING_REQUIRED", "Pairing required")
        val client = ControlApiClient(this, session.controlBaseUrl)
        val database = GatewayDatabase.get(this)
        flushSmsRedactions(database)
        if (database.adoptGatewayId(session.gatewayId)) SimRegistry.resetForGatewayChange(this)
        val snapshot = SimRegistry.snapshot(this)
        val sims = snapshot.mappings
            .map { mapping ->
                HeartbeatSim(mapping.simId, mapping.mappingRevision,
                    serviceStateFor(mapping.subscriptionId),
                    identityVerified = mapping.identityState != SimRegistry.IdentityState.UNVERIFIED)
            }
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) (100 * level / scale).coerceIn(0, 100) else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val heartbeat = database.prepareHeartbeatPayload(session.gatewayId) { sequence ->
            client.heartbeatBody(sequence, RootShell.rootState() == "ok",
                sipClient?.registered == true, batteryPercent, charging, sims).toString()
        }
        val heartbeatResult = try {
            client.heartbeat(org.json.JSONObject(heartbeat.second))
        } catch (e: ControlApiException) {
            if (e.code == "SIM_MAPPING_CHANGED") {
                val remote = client.serverSimSnapshot()
                SimRegistry.invalidateAllAtServerRevision(this, remote.mappingRevision)
                database.discardHeartbeat(session.gatewayId, heartbeat.first)
            }
            throw e
        }
        SimRegistry.applyHeartbeatInvalidation(this, heartbeatResult.mappingRevision,
            heartbeatResult.invalidatedSimIds)
        database.completeHeartbeat(session.gatewayId, heartbeat.first)

        val events = database.pendingEvents(session.gatewayId, 50)
        if (events.isNotEmpty()) {
            val acked = client.uploadEvents(events)
            database.acknowledgeEvents(acked)
            flushSmsRedactions(database)
            broadcastLog("CONTROL: durably acknowledged ${acked.size} event(s)")
        }
        processCommands(client, database)
        val latest = CredentialStore.load(this)
        if (latest != null) reconcileWakeConnection(latest)
    }

    private fun flushSmsRedactions(database: GatewayDatabase) {
        database.pendingSmsRedactions().forEach { messageId ->
            CallLogStore.redactSms(this, messageId)
            database.completeSmsRedaction(messageId)
        }
    }

    private fun serviceStateFor(subscriptionId: Int): String {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return "unknown"
        return try {
            val base = getSystemService(TelephonyManager::class.java)
            val state = base?.createForSubscriptionId(subscriptionId)?.serviceState?.state
            when (state) {
                0 -> "in_service"
                1 -> "out_of_service"
                2 -> "emergency_only"
                3 -> "power_off"
                else -> "unknown"
            }
        } catch (_: SecurityException) {
            "unknown"
        } catch (_: RuntimeException) {
            "unknown"
        }
    }

    private fun processCommands(client: ControlApiClient, database: GatewayDatabase) {
        for (remote in client.listCommands()) {
            if (!database.isActiveGateway(remote.gatewayId)) {
                broadcastLog("SMS: command belongs to a previous gateway pairing; dispatch blocked")
                continue
            }
            var local = database.command(remote.commandId, remote.gatewayId)
                ?: database.commandForMessage(remote.messageId, remote.gatewayId)
            if (local == null) {
                if (remote.state == "dispatching") {
                    broadcastLog("SMS: server reports dispatching but local ledger is missing; refusing resend")
                    continue
                }
                try {
                    val claimed = client.claimCommand(remote)
                    if (claimed.state != "accepted_by_gateway") {
                        broadcastLog("SMS: server already reports dispatching; refusing resend")
                        continue
                    }
                } catch (e: ControlApiException) {
                    if (e.code !in setOf("COMMAND_EXPIRED", "SIM_MAPPING_CHANGED", "SIM_UNAVAILABLE")) throw e
                    val insert = database.insertClaimedCommand(remote.toLocalCommand(), remote.gatewayId)
                    if (insert == GatewayDatabase.InsertCommandResult.CONFLICT) {
                        broadcastLog("SMS: command identity conflict; dispatch blocked")
                        continue
                    }
                    local = database.command(remote.commandId, remote.gatewayId)
                    if (local != null) {
                        val terminal = if (e.code == "COMMAND_EXPIRED") "expired" else "failed"
                        database.markCommandTerminal(remote.commandId, terminal, e.code)
                    }
                    continue
                }
                val inserted = database.insertClaimedCommand(remote.toLocalCommand(), remote.gatewayId)
                if (inserted == GatewayDatabase.InsertCommandResult.CONFLICT) {
                    broadcastLog("SMS: command identity conflict; dispatch blocked")
                    continue
                }
                local = database.command(remote.commandId)
            }
            val pending = local ?: continue
            if (pending.payloadHash != remote.payloadSha256) {
                broadcastLog("SMS: persisted command hash conflict; dispatch blocked")
                continue
            }
            if (pending.state != "claimed") continue
            dispatchCommand(pending, database)
        }
    }

    private fun dispatchCommand(command: GatewayDatabase.Command, database: GatewayDatabase) {
        val owner = command.ownerGatewayId
        if (owner == null || !database.isActiveGateway(owner)) return
        if (command.expiresAt <= System.currentTimeMillis()) {
            database.markCommandTerminal(command.commandId, "expired", "COMMAND_EXPIRED")
            return
        }
        if (!hasSendSms()) {
            database.markCommandTerminal(command.commandId, "failed", "SEND_SMS_PERMISSION_DENIED")
            return
        }
        val mapping = try {
            SimRegistry.resolve(this, command.simId, command.mappingRevision)
        } catch (e: SimRegistry.SimMappingException) {
            database.markCommandTerminal(command.commandId, "failed", e.code.name)
            broadcastLog("SMS: SIM mapping validation failed (${e.code.name})")
            return
        }
        val prepared = try {
            SmsSender.prepare(this, command.text, mapping.subscriptionId)
        } catch (e: Exception) {
            database.markCommandTerminal(command.commandId, "failed", "SMS_MANAGER_UNAVAILABLE")
            broadcastLog("SMS: specified subscription could not be prepared")
            return
        }
        val beforeDispatch = try {
            SimRegistry.resolve(this, command.simId, command.mappingRevision, mapping.localRevisionBarrier)
        } catch (e: SimRegistry.SimMappingException) {
            database.markCommandTerminal(command.commandId, "failed", e.code.name)
            return
        }
        if (beforeDispatch.subscriptionId != mapping.subscriptionId || beforeDispatch.slotIndex != mapping.slotIndex) {
            database.markCommandTerminal(command.commandId, "failed", "SIM_MAPPING_CHANGED")
            return
        }
        if (!database.prepareCommandParts(command.commandId, prepared.parts.size)) {
            if (command.expiresAt <= System.currentTimeMillis()) {
                database.markCommandTerminal(command.commandId, "expired", "COMMAND_EXPIRED")
            } else {
                database.markCommandTerminal(command.commandId, "failed", "SMS_PART_COUNT_CHANGED")
            }
            return
        }
        if (command.expiresAt <= System.currentTimeMillis()) {
            database.markCommandTerminal(command.commandId, "expired", "COMMAND_EXPIRED")
            return
        }
        when (database.beginDispatch(command.commandId)) {
            GatewayDatabase.DispatchStartResult.RATE_LIMITED -> {
                database.markCommandTerminal(command.commandId, "failed", "SMS_RATE_LIMITED")
                CallLogStore.addEntry(this, CallLogEntry(
                    direction = "OUT", number = command.to, timestamp = System.currentTimeMillis(),
                    durationSec = 0, type = CallLogStore.TYPE_SMS, text = command.text,
                    smsId = command.messageId, parts = prepared.parts.size,
                    status = "failed", error = "SMS_RATE_LIMITED"
                ))
                return
            }
            GatewayDatabase.DispatchStartResult.NOT_READY -> {
                if (command.expiresAt <= System.currentTimeMillis()) {
                    database.markCommandTerminal(command.commandId, "expired", "COMMAND_EXPIRED")
                } else {
                    database.markCommandTerminal(command.commandId, "failed", "DISPATCH_LEDGER_CONFLICT")
                }
                return
            }
            GatewayDatabase.DispatchStartResult.STARTED -> Unit
        }
        CallLogStore.addEntry(this, CallLogEntry(
            direction = "OUT", number = command.to, timestamp = System.currentTimeMillis(),
            durationSec = 0, type = CallLogStore.TYPE_SMS, text = command.text,
            smsId = command.messageId, parts = prepared.parts.size, status = "pending"
        ))
        val finalMapping = try {
            SimRegistry.resolve(this, command.simId, command.mappingRevision, mapping.localRevisionBarrier)
        } catch (e: SimRegistry.SimMappingException) {
            for (part in prepared.parts.indices) {
                database.recordPartState(command.commandId, part, "failed", error = e.code.name)
            }
            CallLogStore.updateSms(this, command.messageId) {
                it.copy(status = "failed", error = e.code.name)
            }
            return
        }
        if (finalMapping.subscriptionId != mapping.subscriptionId || finalMapping.slotIndex != mapping.slotIndex) {
            for (part in prepared.parts.indices) {
                database.recordPartState(command.commandId, part, "failed", error = "SIM_MAPPING_CHANGED")
            }
            CallLogStore.updateSms(this, command.messageId) {
                it.copy(status = "failed", error = "SIM_MAPPING_CHANGED")
            }
            return
        }
        if (!database.isActiveGateway(owner)) return
        val invoked = SmsSender.dispatch(this, command.commandId, command.messageId,
            command.to, prepared)
        if (!invoked) {
            for (part in prepared.parts.indices) {
                database.recordPartState(command.commandId, part, "unknown",
                    error = "SMS_DISPATCH_RESULT_UNKNOWN")
            }
            CallLogStore.updateSms(this, command.messageId) {
                it.copy(status = "unknown", error = "SMS dispatch result unknown")
            }
        }
    }

    private fun ServerCommand.toLocalCommand() = GatewayDatabase.Command(
        commandId = commandId, messageId = messageId, simId = simId,
        mappingRevision = mappingRevision, to = to, text = text, partCount = 0,
        expiresAt = expiresAt, state = "claimed", payloadHash = payloadSha256,
        ownerGatewayId = gatewayId
    )

    private fun hasSendSms(): Boolean =
        checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun dialFromDialler(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: return
        orchestrator?.initiateDiallerCall(number)
            ?: broadcastLog("ERROR: Gateway not running — cannot bridge to SIP")
    }

    /**
     * Re-broadcast the current state.
     *
     * The UI is only ever told about state *changes*, so an Activity that
     * starts while the service is already running never hears anything and
     * shows its default "offline" until the next call.  This gives it a way
     * to ask.
     */
    private fun broadcastCurrentStatus() {
        val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
        val registered = sipClient?.registered == true
        val info = when {
            stopped -> "Stopped"
            state == CallOrchestrator.BridgeState.IDLE && registered -> "SIP registered"
            state == CallOrchestrator.BridgeState.IDLE -> "Connecting"
            else -> state.name
        }
        broadcastStatus(if (stopped) "STOPPED" else state.name, info)
    }

    private fun reloadStats() {
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        broadcastStatus(orchestrator?.bridgeState?.name ?: "IDLE", "Stats reloaded")
    }

    private fun startGateway(intent: Intent?) {
        if (!GatewayBackgroundPolicy.mayRestoreVoice(intent?.action, sipConfigured = true, explicitStartAction = ACTION_START) ||
            !GatewayBackgroundRuntime.allowedRecovery(this)
        ) {
            Log.w(TAG, "Ignoring voice startup without a current visible start opt-in")
            startControlGateway()
            return
        }
        // Guard: if the gateway is already running (SIP client exists and
        // we're not in stopped state), don't tear it down and restart.
        // This prevents redundant ACTION_START intents (e.g. from the
        // Activity opening, START_STICKY restart, or BootReceiver) from
        // killing an active SIP registration or call bridge.
        //
        // `initializing` covers the window before sipClient is assigned:
        // initSipClient() runs on the gateway-init thread, so two intents
        // arriving back to back (START_STICKY redelivery with a null intent
        // + the Activity's autoStartGateway) would both see a null sipClient
        // and each bring up their own client — two sockets on :5060 and two
        // REGISTERs.
        if (!stopped && (sipClient != null || initializing.get())) {
            Log.i(TAG, "startGateway: already running, skipping restart")
            // Broadcast current state so the Activity picks up the live status
            val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
            val registered = sipClient?.registered == true
            val info = when (state) {
                CallOrchestrator.BridgeState.IDLE ->
                    if (registered) "SIP registered" else "Connected"
                else -> state.name
            }
            broadcastStatus(state.name, info)
            return
        }

        // Clean up any existing client before starting a new one
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null
        stopped = false

        // Load call stats from persistent store so counters survive restarts
        onlineSince = 0L
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        currentCallStart = 0L
        currentAttemptStart = 0L

        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: prefs.getString("server", "") ?: ""
        val port = intent?.getIntExtra(EXTRA_PORT, 5061) ?: prefs.getInt("port", 5061)
        val username = intent?.getStringExtra(EXTRA_USER) ?: prefs.getString("user", "") ?: ""
        val password = intent?.getStringExtra(EXTRA_PASS) ?: prefs.getString("pass", "") ?: ""

        if (server.isEmpty() || username.isEmpty()) {
            voiceRuntimeStarted = false
            refreshVoicePowerLease()
            cfgServer = ""
            cfgUser = ""
            cfgPass = ""
            if (CredentialStore.load(this) != null) startControlGateway() else {
                broadcastLog("SIP is unconfigured; pair this phone with the HTTPS control server")
                broadcastStatus("PAIRING_REQUIRED", "Pair this phone with the control server")
                stopSelf()
            }
            return
        }

        // Save for restart
        prefs.edit()
            .putString("server", server)
            .putInt("port", port)
            .putString("user", username)
            .putString("pass", password)
            .apply()

        // Codec preference is a property of the SDP we build, so it has to be
        // in place before the first INVITE goes out.
        com.callagent.gateway.sip.SipBuilder.codecMode =
            prefs.getString("codec", "g722") ?: "g722"

        // The agent's level into the GSM uplink, as a step away from what the
        // device profile asks for.  Read here so a change applies on save
        // rather than waiting for the next call.
        com.callagent.gateway.gsm.GsmCallManager.agentVolumeStep =
            prefs.getInt("agent_vol_step", 0)

        cfgServer = server
        cfgPort = port
        cfgUser = username
        cfgPass = password
        voiceRuntimeStarted = true

        notifStatusText = "Connecting"
        try {
            startForegroundMode(callActive = false, status = "Connecting")
            GatewayBackgroundRuntime.markServiceStarted(this, "SIP connecting · HTTPS polling")
        } catch (e: Exception) {
            voiceRuntimeStarted = false
            releaseVoicePowerLeases()
            GatewayBackgroundRuntime.recordIssue(this,
                "Android refused the foreground service start; open the app and retry")
            broadcastStatus("VOICE_START_FAILED", "Foreground service start failed")
            stopGateway(userInitiated = false)
            return
        }
        refreshVoicePowerLease()
        startControlLoop()
        initializing.set(true)
        initializingSince = System.currentTimeMillis()

        // Run network I/O off the main thread (Android blocks sockets on main thread)
        val gen = initGeneration.incrementAndGet()
        thread(name = "gateway-init") {
            try {
                initSipClient(gen)
            } finally {
                initializing.set(false)
            }
        }
    }

    /** Shared SIP init — called from both startGateway and reconnect threads. */
    private fun initSipClient(gen: Int) {
        /** True while this thread is still the newest bring-up. */
        fun current() = gen == initGeneration.get() && !stopped &&
            GatewayBackgroundRuntime.allowedRecovery(this)

        if (!current()) {
            Log.w(TAG, "initSipClient: superseded before start (gen $gen)")
            return
        }

        // A previous client must be gone before another socket is bound to
        // :5060.  reconnect() already does this, but startGateway() and the
        // stale-init recovery reach here without it.
        sipClient?.let {
            Log.w(TAG, "initSipClient: stopping previous SIP client")
            broadcastLog("SIP: stopping previous client before rebind")
            orchestrator?.stop()
            it.stop()
            orchestrator = null
            sipClient = null
        }

        checkDefaultDialer()
        checkSmsPermission()

        val localIp = getLocalIp()
        currentLocalIp = localIp
        broadcastLog("Local IP: $localIp")

        // STUN: discover public IP for NAT traversal.  Optional, because a
        // SIP server on the same network needs no public address at all —
        // advertising one there would point the server at the far side of a
        // NAT it never has to cross.
        val useStun = getSharedPreferences("gateway", MODE_PRIVATE)
            .getBoolean("use_stun", false)
        val stunResult = if (!useStun) null else try {
            StunClient.discover()
        } catch (e: Exception) {
            Log.e(TAG, "STUN exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val publicIp = stunResult?.publicIp ?: localIp
        when {
            !useStun ->
                broadcastLog("STUN off — direct SIP, advertising $localIp")
            stunResult != null ->
                broadcastLog("STUN public IP: ${stunResult.publicIp}:${stunResult.publicPort}")
            else ->
                broadcastLog("STUN failed, using local IP for SDP")
        }

        if (stopped) return
        if (!current()) {
            Log.w(TAG, "initSipClient: superseded during STUN (gen $gen)")
            return
        }

        // TLS protects signalling only: REGISTER, INVITE and the SMS bodies
        // in MESSAGE.  RTP is deliberately left alone, so call audio is no
        // more or less private than it was on UDP.
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val useTls = true
        if (useTls) broadcastLog("SIP transport: TLS to $cfgServer:$cfgPort")

        val sip = SipClient(
            username = cfgUser,
            password = cfgPass,
            serverDomain = cfgServer,
            serverPort = cfgPort,
            localIp = localIp,
            localPort = 5060,
            publicIp = publicIp,
            useTls = useTls,
            srtpRequested = true
        )
        sipClient = sip

        val orch = CallOrchestrator(this, sip)
        orch.listener = object : CallOrchestrator.OrchestratorListener {
            override fun onStateChanged(state: CallOrchestrator.BridgeState, info: String) {
                Log.i(TAG, "Bridge: $state - $info")
                // The call lifecycle only ever reached logcat and the status
                // pill; the log viewer showed SIP and audio lines with no
                // record of the call they belonged to.
                broadcastLog("CALL: ${state.name}${if (info.isBlank()) "" else " — $info"}")
                val registered = sip.registered

                // Track online time
                if (registered && onlineSince == 0L) {
                    onlineSince = System.currentTimeMillis()
                    // Anything that arrived while SIP was down goes now, and
                    // any report the server never acknowledged goes again.
                    wakeControlLoop()
                } else if (!registered && state == CallOrchestrator.BridgeState.IDLE) {
                    onlineSince = 0L
                }

                // Track call direction and number
                when (state) {
                    CallOrchestrator.BridgeState.GSM_RINGING -> {
                        currentCallIncoming = true
                        currentCallNumber = info.removePrefix("GSM call from ")
                        if (currentAttemptStart == 0L) currentAttemptStart = System.currentTimeMillis()
                    }
                    CallOrchestrator.BridgeState.GSM_DIALING -> {
                        currentCallIncoming = false
                        currentCallNumber = info.removePrefix("Dialing ")
                        if (currentAttemptStart == 0L) currentAttemptStart = System.currentTimeMillis()
                    }
                    else -> {}
                }

                // Track call start / end
                if (state == CallOrchestrator.BridgeState.BRIDGED && currentCallStart == 0L) {
                    currentCallStart = System.currentTimeMillis()
                    if (currentCallIncoming) incomingCalls++ else outgoingCalls++
                }
                if (state == CallOrchestrator.BridgeState.IDLE &&
                    (currentCallStart != 0L || currentAttemptStart != 0L)
                ) {
                    // A zero duration is how the list already renders an
                    // unconnected call ("Not connected", red dash), so a failed
                    // attempt needs no new field — only an entry.
                    val dur =
                        if (currentCallStart != 0L)
                            (System.currentTimeMillis() - currentCallStart) / 1000
                        else 0L
                    if (currentCallStart != 0L) {
                        if (currentCallIncoming) incomingDurationSec += dur
                        else outgoingDurationSec += dur
                    }
                    CallLogStore.addEntry(this@GatewayService, CallLogEntry(
                        direction = if (currentCallIncoming) "IN" else "OUT",
                        // Timestamp the call from when it arrived or was dialled,
                        // not from when the bridge came up — an attempt that never
                        // bridged has no other time to show.
                        number = currentCallNumber,
                        timestamp = if (currentAttemptStart != 0L) currentAttemptStart
                                    else currentCallStart,
                        durationSec = dur
                    ))
                    currentCallStart = 0L
                    currentAttemptStart = 0L
                    currentCallNumber = ""
                }

                // Map bridge state to notification status text
                val (notifState, statusText) = when (state) {
                    CallOrchestrator.BridgeState.IDLE ->
                        if (registered) NotifState.OK to "Connected"
                        else NotifState.WARN to "Connecting"
                    CallOrchestrator.BridgeState.GSM_DIALING ->
                        NotifState.OK to "Dialing"
                    CallOrchestrator.BridgeState.BRIDGED ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.GSM_RINGING,
                    CallOrchestrator.BridgeState.GSM_ANSWERED,
                    CallOrchestrator.BridgeState.SIP_CALLING,
                    CallOrchestrator.BridgeState.SIP_RINGING ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.TEARING_DOWN ->
                        NotifState.OK to "In-Call"
                }
                updateNotification(notifState, statusText)
                try {
                    val callActive = state != CallOrchestrator.BridgeState.IDLE && voiceRuntimeStarted
                    setForegroundCallMode(callActive, statusText)
                } catch (e: Exception) {
                    broadcastLog("CALL: foreground service mode failed (${e.javaClass.simpleName})")
                    GatewayBackgroundRuntime.recordIssue(this@GatewayService,
                        "Android did not allow microphone foreground access; reopen the app to start voice calls")
                }
                refreshVoicePowerLease()
                broadcastStatus(state.name, info)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Orchestrator error: $error")
                broadcastLog("ERROR: $error")
                broadcastStatus("ERROR", error)
            }

            override fun onRtpStats(stats: String) {
                broadcastLog("RTP: $stats")
            }
        }
        orchestrator = orch
        orch.start()

        sip.logListener = { msg -> broadcastLog("SIP: $msg") }
        // Production SMS commands are claimed only over HTTPS. SIP MESSAGE is migration-only.
        sip.onSmsRequest = null
        GsmCallManager.logCallback = { msg -> broadcastLog("AUDIO: $msg") }
        RootShell.statusCallback = { msg -> broadcastLog("ROOT: $msg") }
        sip.onConnectionLost = { reconnect() }

        // Last check before anything binds a socket: if a newer bring-up has
        // started meanwhile, this client must not exist at all.
        if (!current() || sipClient !== sip) {
            Log.w(TAG, "initSipClient: superseded before start (gen $gen) — discarding")
            orch.stop()
            return
        }

        try {
            sip.start()
            broadcastLog("[v${BuildConfig.VERSION_NAME}] SIP client started, registering with $cfgServer:$cfgPort")
            broadcastStatus("STARTING", "Registering with $cfgServer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SIP client: ${e.message}", e)
            broadcastLog("ERROR: SIP start failed — ${e.message}")
            broadcastStatus("ERROR", "SIP start failed: ${e.message}")
        }
    }

    @Volatile private var stopped = false

    private fun stopGateway(userInitiated: Boolean = false) {
        if (userInitiated) GatewayBackgroundRuntime.persistUserStop(this)
        val wasAlreadyStopped = stopped
        stopped = true
        // Fence a SIP init/reconnect that is still doing blocking setup. A
        // later explicit start receives a new generation from startGateway.
        initGeneration.incrementAndGet()
        initializing.set(false)
        controlStop = true
        controlWakeSignal.release()
        releaseNetworkSyncWakeLock()
        closeWakeConnection()
        voiceRuntimeStarted = false
        releaseVoicePowerLeases()
        if (wasAlreadyStopped) {
            GatewayBackgroundRuntime.markServiceStopped(this,
                userInitiated = GatewayBackgroundRuntime.userStopped(this))
            return
        }
        onlineSince = 0L
        Log.i(TAG, "Stopping gateway")
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        callMicrophoneForegroundActive = false
        GatewayBackgroundRuntime.markServiceStopped(this,
            userInitiated = GatewayBackgroundRuntime.userStopped(this))
        stopSelf()
        broadcastStatus("STOPPED", if (GatewayBackgroundRuntime.userStopped(this)) "Gateway stopped by user" else "Gateway service stopped")
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        stopGateway(userInitiated = false)
        powerLeaseScheduler.shutdownNow()
        wakeRetryScheduler.shutdownNow()
        super.onDestroy()
    }

    // ── Notification ────────────────────────────────────

    /**
     * One channel, at minimum importance.
     *
     * A foreground service must keep a notification and Android will not let
     * that go, so the gateway always has one entry in the shade -- that part
     * is the platform's, not a setting.  Minimum importance keeps it silent
     * and at the bottom of the shade, which is as unobtrusive as it gets
     * while still showing the gateway's state.
     *
     * The old normal-importance channel is deleted rather than left behind,
     * so it stops appearing in the system's per-app notification settings on
     * devices that ran an earlier build.
     */
    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_QUIET,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
        )
        runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
    }

    /**
     * Undo the notification suspension an earlier build applied.
     *
     * Suspending the package's notifications did hide the icon, but it is a
     * blunt instrument -- it swallows everything the app might ever post --
     * and it is unnecessary now that the icon draws nothing.  That state
     * survives app updates, so clear it unconditionally on start.
     */
    private fun applyNotificationVisibility() {
        RootShell.exec("cmd notification unsuspend_package $packageName 2>/dev/null", 5000)
    }

    /** The notification id in use.  See [cancelStaleNotification]. */
    private fun activeNotificationId(): Int = NOTIFICATION_ID_QUIET

    /**
     * Drop the notification id an earlier build used.
     *
     * A notification's channel is fixed when it is first posted, so the id
     * that was bound to the normal-importance channel cannot be reused for a
     * silent one; it is abandoned and cancelled instead of carried forward.
     */
    private fun cancelStaleNotification() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    private fun activeChannelId(): String = CHANNEL_ID_QUIET

    private enum class NotifState { OK, WARN, ERROR }

    /** Current notification status text, kept in sync with bridge/SIP state. */
    private var notifStatusText = "Connecting"
    /** Last state the notification was built with, so re-entering the
     *  foreground for an SMS does not rewrite what the user sees. */
    @Volatile private var notifState = NotifState.WARN

    private fun buildNotification(state: NotifState = NotifState.ERROR, statusText: String = notifStatusText): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // An empty icon when the status bar is turned off.  The quiet channel
        // is not enough on its own: Android forces a foreground service's
        // notification up to at least LOW importance whatever the channel
        // says, and LOW still draws a glyph.  The icon is the part it does
        // not override, so the setting is honoured by drawing nothing.  The
        // notification itself stays, because the platform requires it, and
        // still carries the status text in the shade.
        // The state icon, in the status bar and in the shade.  An invisible
        // icon did keep the gateway out of the status bar, but the shade entry
        // is not removable either way -- Android requires a foreground service
        // to hold one -- and that entry with a blank space where its icon
        // belongs looks broken rather than discreet.  So it carries the real
        // icon, which also makes the gateway's state readable at a glance.
        // One dot for every state, coloured by state.
        //
        // The colour only shows in the notification shade: the status bar
        // draws small icons monochrome, tinting them to match the bar, so the
        // dot reads white up there no matter what colour is set.  Shape was
        // what distinguished the states in the status bar before; a dot trades
        // that for a quieter icon.
        val icon = R.drawable.ic_notif_dot
        val accent = when (state) {
            NotifState.OK -> 0xFF16A34A.toInt()      // green — registered
            NotifState.WARN -> 0xFFEAB308.toInt()    // amber — connecting or in transition
            NotifState.ERROR -> 0xFFDC2626.toInt()   // red — stopped or failed
        }
        // No actions.  The notification carries the gateway's status and
        // nothing else: it is a background service on an unattended handset,
        // and a button there is one nobody is present to press.  Listen-in is
        // reached from the app itself.  ACTION_MONITOR still exists and is
        // still handled, so anything already bound to it keeps working.

        return Notification.Builder(this, activeChannelId())
            .setContentTitle(statusText)
            .setSmallIcon(icon)
            .setColor(accent)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    private fun updateNotification(state: NotifState = NotifState.ERROR, statusText: String? = null) {
        if (statusText != null) notifStatusText = statusText
        notifState = state
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(activeNotificationId(), buildNotification(state))
    }

    // ── Wake / WiFi locks ───────────────────────────────

    /** A short, timed CPU lease around one blocking HTTPS synchronization. */
    private fun withNetworkSyncWakeLock(block: () -> Unit): Boolean {
        val lock = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gateway:control-sync")
                .apply { setReferenceCounted(false) }
        } catch (_: Exception) {
            null
        }
        var acquired = false
        var mayRun = false
        try {
            synchronized(syncWakeLockGuard) {
                if (!controlStop && !stopped && GatewayBackgroundRuntime.allowedRecovery(this)) {
                    mayRun = true
                    if (lock != null) {
                        try {
                            lock.acquire(GatewayBackgroundPolicy.SYNC_WAKE_TIMEOUT_MS)
                            syncWakeLock = lock
                            acquired = true
                        } catch (_: Exception) {
                            Log.w(TAG, "Could not acquire a short control-sync wake lease")
                        }
                    }
                }
            }
            if (mayRun) block()
        } finally {
            if (acquired) synchronized(syncWakeLockGuard) {
                if (syncWakeLock === lock) syncWakeLock = null
                if (lock?.isHeld == true) runCatching { lock.release() }
            }
        }
        return mayRun
    }

    private fun releaseNetworkSyncWakeLock() {
        synchronized(syncWakeLockGuard) {
            syncWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
            syncWakeLock = null
        }
    }

    /** Control-only polling takes no lease; active calls renew bounded leases. */
    private fun refreshVoicePowerLease() {
        val callActive = orchestrator?.bridgeState?.let { it != CallOrchestrator.BridgeState.IDLE } ?: false
        val wanted = GatewayBackgroundPolicy.holdVoiceLease(voiceRuntimeStarted, callActive)
        synchronized(powerLeaseGuard) {
            if (!wanted) {
                if (!voiceLeaseWanted && voiceWakeLock == null && voiceWifiLock == null) return
                voiceLeaseWanted = false
                voiceLeaseGeneration++
                voiceLeaseFuture?.cancel(false)
                voiceLeaseFuture = null
                wifiLeaseFuture?.cancel(false)
                wifiLeaseFuture = null
                releaseVoiceLocksLocked()
                return
            }
            val wifiWanted = isWifiTransportActive()
            val hasWifiLock = voiceWifiLock?.isHeld == true
            if (voiceLeaseFuture != null && voiceWakeLock?.isHeld == true && wifiWanted == hasWifiLock) return
            voiceLeaseWanted = true
            renewVoicePowerLeaseLocked()
        }
    }

    private fun renewVoicePowerLeaseLocked() {
        voiceLeaseGeneration++
        val generation = voiceLeaseGeneration
        voiceLeaseFuture?.cancel(false)
        wifiLeaseFuture?.cancel(false)
        releaseVoiceLocksLocked()
        if (!voiceLeaseWanted) return

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            voiceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gateway:voice-lease").apply {
                setReferenceCounted(false)
                acquire(GatewayBackgroundPolicy.VOICE_WAKE_LEASE_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            voiceWakeLock = null
            GatewayBackgroundRuntime.recordIssue(this, "Android could not grant a temporary voice CPU lease")
        }

        if (isWifiTransportActive()) {
            var acquiredWifiLock: WifiManager.WifiLock? = null
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                acquiredWifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gateway:voice-wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                voiceWifiLock = acquiredWifiLock
                // WifiLock has no timed acquire overload, so every acquisition
                // gets an explicit bounded release task immediately.
                wifiLeaseFuture = powerLeaseScheduler.schedule({
                    synchronized(powerLeaseGuard) {
                        if (generation == voiceLeaseGeneration) {
                            releaseWifiLockLocked()
                            wifiLeaseFuture = null
                        }
                    }
                }, GatewayBackgroundPolicy.WIFI_LOCK_MAX_LEASE_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                acquiredWifiLock?.let { if (it.isHeld) runCatching { it.release() } }
                voiceWifiLock = null
                wifiLeaseFuture = null
            }
        }

        voiceLeaseFuture = try {
            powerLeaseScheduler.schedule({
                synchronized(powerLeaseGuard) {
                    if (generation == voiceLeaseGeneration && voiceLeaseWanted) renewVoicePowerLeaseLocked()
                }
            }, GatewayBackgroundPolicy.VOICE_WAKE_RENEW_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseVoicePowerLeases() {
        synchronized(powerLeaseGuard) {
            voiceLeaseWanted = false
            voiceLeaseGeneration++
            voiceLeaseFuture?.cancel(false)
            voiceLeaseFuture = null
            wifiLeaseFuture?.cancel(false)
            wifiLeaseFuture = null
            releaseVoiceLocksLocked()
        }
    }

    private fun releaseVoiceLocksLocked() {
        voiceWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        voiceWifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        voiceWakeLock = null
        voiceWifiLock = null
    }

    private fun releaseWifiLockLocked() {
        voiceWifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        voiceWifiLock = null
    }

    private fun isWifiTransportActive(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) {
        false
    }

    // ── Broadcast to MainActivity ────────────────────────

    private fun broadcastStatus(state: String, info: String) {
        val intent = Intent(STATUS_ACTION).apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra("info", info)
            // Whether we are actually registered, as a fact rather than as a
            // string the UI has to recognise.  The pill used to infer this by
            // comparing info to the literal "SIP registered", so every state
            // change carrying any other text — "GSM call ended", for one —
            // read as offline while the registration was perfectly alive.
            putExtra("registered", sipClient?.registered == true)
            putExtra("online_since", onlineSince)
            // When the bridge came up, so the UI can show elapsed time rather
            // than time-since-it-noticed.  It only learns of a call when a
            // state change is broadcast, which is not when the call started.
            putExtra("call_start", currentCallStart)
            putExtra("in_calls", incomingCalls)
            putExtra("in_duration", incomingDurationSec)
            putExtra("out_calls", outgoingCalls)
            putExtra("out_duration", outgoingDurationSec)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(msg: String) {
        // Buffer for replay when activity resumes (receiver is only active in
        // foreground).  Stamped here, not on replay: the UI used to timestamp
        // as it appended, so every line buffered while the app was closed —
        // the whole unattended history, which is the part worth reading —
        // collapsed onto the moment the app was opened.
        // Mirrored to logcat as well: everything the Logs screen shows is then
        // greppable over adb, which is how these get read when something has
        // already gone wrong and the app is not in front of anyone.
        Log.i(TAG, "LOG: $msg")
        val stamped = "${bufferTimeFormat.format(java.util.Date())}  $msg"
        synchronized(logBuffer) {
            logBuffer.add(stamped)
            if (logBuffer.size > LOG_BUFFER_SIZE) logBuffer.removeAt(0)
        }
        val intent = Intent(LOG_ACTION).apply {
            setPackage(packageName)
            putExtra("msg", msg)
        }
        sendBroadcast(intent)
    }

    // ── Network ─────────────────────────────────────────

    private fun getLocalIp(): String {
        // Ask for the address of the network that actually carries our
        // traffic.  Enumerating interfaces and taking the first non-loopback
        // IPv4 could hand back the cellular rmnet address while SIP was
        // running over WiFi — so cellular attaching or detaching read as "the
        // local IP changed" and forced a reconnect and a fresh REGISTER that
        // WiFi never needed.  The active network's link address is the one the
        // socket will bind through.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val link = active?.let { cm.getLinkProperties(it) }
            link?.linkAddresses?.firstOrNull { la ->
                la.address is Inet4Address && !la.address.isLoopbackAddress
            }?.address?.hostAddress?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Active-network IP unavailable: ${e.message}")
        }

        // Fallback: interface scan, WiFi first for the same reason.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                .toList()
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .let { java.util.Collections.enumeration(it) }
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return "0.0.0.0"
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val LOG_BUFFER_SIZE = 200
        /** Ring buffer of recent log messages — survives activity pause/resume. */
        private val bufferTimeFormat =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        val logBuffer = mutableListOf<String>()

        /** Drain buffered logs.  Returns all messages and clears the buffer. */
        /**
         * Everything buffered, without consuming it.
         *
         * The old drain cleared as it read, so a second reader — an activity
         * recreated mid-session, say — got nothing, and the Logs screen came
         * up blank precisely when there was something to look at.
         */
        fun logSnapshot(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

        fun clearLogBuffer() = synchronized(logBuffer) { logBuffer.clear() }

        fun drainLogBuffer(): List<String> = synchronized(logBuffer) {
            val copy = logBuffer.toList()
            logBuffer.clear()
            copy
        }

        /** Longest plausible time to bring a SIP client up; past this the
         *  init flag is assumed stuck rather than genuinely in progress. */
        private const val INIT_STALE_MS = 90_000L

        const val CHANNEL_ID = "gateway_channel"
        const val CHANNEL_ID_QUIET = "gateway_channel_quiet"
        const val NOTIFICATION_ID = 1
        /** Same notification, silent channel — see [activeNotificationId]. */
        const val NOTIFICATION_ID_QUIET = 2
        const val ACTION_START = "com.callagent.gateway.START"
        const val ACTION_STOP = "com.callagent.gateway.STOP"
        const val ACTION_RELOAD_STATS = "com.callagent.gateway.RELOAD_STATS"
        const val ACTION_STATUS = "com.callagent.gateway.STATUS_REQUEST"
        const val ACTION_RECONNECT = "com.callagent.gateway.RECONNECT"
        const val ACTION_DIAL = "com.callagent.gateway.DIAL"
        const val ACTION_MONITOR = "com.callagent.gateway.MONITOR"
        const val EXTRA_MONITOR_ON = "monitor_on"
        const val ACTION_MUTE_AGENT = "com.callagent.gateway.MUTE_AGENT"
        const val EXTRA_MUTE_ON = "mute_on"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_NUMBER = "number"
        const val STATUS_ACTION = "com.callagent.gateway.STATUS"
        const val LOG_ACTION = "com.callagent.gateway.LOG"
        const val ACTION_APPLY_CONFIG = "com.callagent.gateway.APPLY_CONFIG"
        const val ACTION_CONTROL_START = "com.callagent.gateway.CONTROL_START"
        const val ACTION_SMS_FLUSH = "com.callagent.gateway.SMS_FLUSH"
        const val ACTION_SMS_SEND = "com.callagent.gateway.SMS_SEND"
        const val ACTION_SMS_REPORT = "com.callagent.gateway.SMS_REPORT"
        const val EXTRA_SMS_ID = "sms_id"

        private const val CONTROL_INTERVAL_MS = 30_000L
        private const val CONTROL_MAX_RETRY_MS = 5 * 60_000L
        private const val WAKE_RETRY_INITIAL_MS = 2_000L
        private const val WAKE_RETRY_MAX_MS = 5 * 60_000L
        private val callRecoveryDone = AtomicBoolean(false)

        /**
         * Ask the running gateway to forward whatever SMS are queued.
         *
         * Called from the SMS receiver, which has already put the message on
         * disk — so if the service is not up, or is killed on the way, nothing
         * is lost: the queue is flushed again as soon as SIP registers.
         */
        /** A send result or delivery report landed — let the service tell the
         *  server about it. */
        fun reportSmsProgress(context: Context, id: String) {
            if (!GatewayBackgroundRuntime.allowedRecovery(context) || CredentialStore.load(context) == null) return
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_SMS_REPORT
                putExtra(EXTRA_SMS_ID, id)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not wake control service for SMS callback")
            }
        }

        fun deliverQueuedSms(context: Context) {
            if (!GatewayBackgroundRuntime.allowedRecovery(context) || CredentialStore.load(context) == null) return
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_SMS_FLUSH
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not wake control service for SMS event")
            }
        }

        fun startControl(context: Context): Boolean {
            if (!GatewayBackgroundRuntime.allowedRecovery(context) || CredentialStore.load(context) == null) return false
            val intent = Intent(context, GatewayService::class.java).apply { action = ACTION_CONTROL_START }
            try {
                context.startForegroundService(intent)
                return true
            } catch (_: Exception) {
                Log.w(TAG, "Could not start control service")
                GatewayBackgroundRuntime.recordIssue(context,
                    "Android did not allow the foreground service to start; open the app and retry")
                return false
            }
        }

        fun start(context: Context, server: String, port: Int, user: String, pass: String): Boolean {
            if (!GatewayBackgroundRuntime.recordExplicitEnable(context)) return false
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
            }
            return try {
                context.startForegroundService(intent)
                true
            } catch (_: Exception) {
                GatewayBackgroundRuntime.recordIssue(context,
                    "Android did not allow the voice foreground service to start; open the app and retry")
                false
            }
        }

        fun stop(context: Context): Boolean {
            if (!GatewayBackgroundRuntime.persistUserStop(context)) return false
            return context.stopService(Intent(context, GatewayService::class.java))
        }

    }
}
