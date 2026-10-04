package com.callagent.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFormat
import android.media.AudioManager
import android.widget.SeekBar
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.wifi.WifiManager
import android.app.role.RoleManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RadioGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.R as AppCompatR
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.VoiceCredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.net.ControlApiException
import com.callagent.gateway.net.MappingProposal
import com.callagent.gateway.net.ServerMapping
import com.callagent.gateway.service.GatewayService
import com.callagent.gateway.background.GatewayBackgroundRuntime
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.sms.SmsOutbox
import com.callagent.gateway.sms.SmsProviderRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.radiobutton.MaterialRadioButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity() {

    // Settings-tab views
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    // Dialler-tab views

    // Calls-tab views
    private var callLogFilter = "IN"

    // Pre-cached call log lists (built once, swapped on filter change)
    private var cachedInEntries: List<CallLogEntry> = emptyList()
    private var cachedOutEntries: List<CallLogEntry> = emptyList()
    private var callLogBuiltIn = false
    private var callLogBuiltOut = false

    // Tab containers + bottom bar
    private lateinit var tabbedRoot: LinearLayout
    private lateinit var tabHome: View
    private lateinit var tabConfig: View
    private lateinit var tvHomeStatusPill: TextView
    private lateinit var tvHomeTlsBadge: TextView
    private lateinit var tvHomeSrtpBadge: TextView
    private lateinit var tvNetMobile: TextView
    private lateinit var tvNetWifi: TextView
    private lateinit var tvSimSummary: TextView
    private val netHandler = Handler(Looper.getMainLooper())
    private val netRunnable = object : Runnable {
        override fun run() {
            refreshNetworkInfo()
            // Signal and link speed drift constantly; five seconds is often
            // enough to be useful without being a battery drain.
            netHandler.postDelayed(this, 5000)
        }
    }
    private lateinit var homeCallCard: View
    private lateinit var tvHomeCallDirection: TextView
    private lateinit var tvHomeCallTimer: TextView
    private lateinit var tvHomeCallFrom: TextView
    private lateinit var tvHomeCallTo: TextView
    private lateinit var btnHomeMute: MaterialButton
    private lateinit var btnHomeSnoop: MaterialButton
    private lateinit var btnHomeEnd: MaterialButton
    private lateinit var homeTrafficList: LinearLayout
    private lateinit var tvHomeTrafficEmpty: TextView
    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterIncoming: Button
    private lateinit var btnFilterOutgoing: Button
    /** "all" | "in" | "out" — which calls the home list shows.  A missed call
     *  is an inbound one that never carried audio, so it belongs under
     *  Incoming rather than in a category of its own. */
    private var callFilter = "all"
    private var agentMuted = false
    private val selectedSimBindings = mutableMapOf<String, SimRegistry.SimSubscription>()
    private var pendingMappingProposal: MappingProposal? = null
    private var simProposalSubscriptions: List<SimRegistry.SimSubscription> = emptyList()
    private var controlBusy = false
    private var pendingRecoveryMode: SmsProviderRecovery.HistoryMode? = null
    private var pendingRecoveryGatewayId: String? = null
    private var pendingRecoveryControlBaseUrl: String? = null
    private var recoveryPermissionRequestInFlight = false
    private val recoveryBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var configGatewayIdAtOpen: String? = null
    private var configControlBaseUrlAtOpen: String? = null
    private var configServerAtOpen: String? = null
    private var configUserAtOpen: String? = null

    private lateinit var tabLogs: LinearLayout
    private lateinit var bottomNavigation: NavigationBarView
    private var currentTab = ""
    private var logsReturnTab = "home"

    private lateinit var tvBackgroundState: TextView
    private lateinit var tvBackgroundConnection: TextView
    private lateinit var tvBackgroundNotification: TextView
    private lateinit var tvBackgroundBattery: TextView
    private lateinit var tvBackgroundIssue: TextView
    private lateinit var tvVoiceStartExplanation: TextView
    private lateinit var btnStartGatewayDiagnostics: MaterialButton
    private lateinit var tvPermissionNotice: TextView
    private lateinit var swBackgroundEnabled: MaterialSwitch
    private var updatingBackgroundControls = false

    // In-call views
    private lateinit var inCallView: LinearLayout
    private lateinit var tvInCallStatus: TextView
    private lateinit var tvInCallNumber: TextView
    private lateinit var tvInCallTimer: TextView
    private lateinit var btnInCallEnd: Button
    private lateinit var btnInCallMonitor: Button
    private var monitoring = false
    /** True while a call is bridged, so SNOOP is only offered when it can work. */
    private var callLive = false
    /** Whether the gateway is actually registered, as opposed to merely
     *  running.  Drives the pill and gates the manual retry. */
    private var gatewayOnline = false
    /** Registration state as reported by the service, rather than guessed from
     *  the status text.  See GatewayService.broadcastStatus. */
    private var sipRegistered = false
    private var inCallOpen = false
    private var inCallOpenTime = 0L
    private var viewBeforeInCall = "dialer"
    private var callStartTime = 0L
    /** When the service says the bridge came up.  Authoritative: the activity
     *  can be started, stopped and restarted several times during one call. */
    private var serviceCallStart = 0L
    private var lastGsmPollState = -1
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0) {
                val elapsedMs = System.currentTimeMillis() - callStartTime
                val elapsed = elapsedMs / 1000
                val t = if (elapsed >= 3600) {
                    String.format(
                        "%d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60
                    )
                } else {
                    String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                }
                tvInCallTimer.text = t
                if (::tvHomeCallTimer.isInitialized) tvHomeCallTimer.text = t
                // Tick on the second boundary of the call, not 1000ms after
                // whenever this happened to run: a flat delay accumulates the
                // handler's own latency, so the display drifts off the real
                // elapsed time and eventually skips or repeats a second.
                callTimerHandler.postDelayed(this, 1000 - (elapsedMs % 1000))
            }
        }
    }
    /**
     * Arm the call timer against the service's start time.
     *
     * Two things were wrong with starting it from the UI's own clock.  The
     * activity only hears about a call when a state change is broadcast, so
     * reopening the app mid-call restarted the count at 00:00; and the ticker
     * is cancelled in onPause but was only ever re-armed when callStartTime
     * was still zero, so coming back to a live call showed a frozen number.
     * Re-arming unconditionally is safe — the pending callback is removed
     * first — and the elapsed time is now real in both cases.
     */
    /** Keep a call button's icon in step with its label — the two describe
     *  the same action, so they have to change together. */
    private fun setCallButtonState(button: Button, label: String, iconRes: Int) {
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, iconRes, 0, 0)
    }

    private fun startCallTimer() {
        callStartTime = if (serviceCallStart > 0) serviceCallStart else System.currentTimeMillis()
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerRunnable.run()
    }

    private val gsmPollRunnable = object : Runnable {
        override fun run() {
            if (!inCallOpen) return
            val call = com.callagent.gateway.gsm.GsmCallManager.activeCall
            val state = com.callagent.gateway.gsm.GsmCallManager.activeCallState
            if (call != null && state != lastGsmPollState) {
                lastGsmPollState = state
                when (state) {
                    android.telecom.Call.STATE_CONNECTING -> tvInCallStatus.text = "呼叫中"
                    android.telecom.Call.STATE_DIALING -> tvInCallStatus.text = "正在振铃"
                    android.telecom.Call.STATE_RINGING -> tvInCallStatus.text = "正在振铃"
                    android.telecom.Call.STATE_ACTIVE -> {
                        if (running) {
                            tvInCallStatus.text = "正在连接"
                        } else {
                            tvInCallStatus.text = "已接通"
                            if (callStartTime == 0L) {
                                callStartTime = System.currentTimeMillis()
                                tvInCallTimer.text = "00:00"
                                tvInCallTimer.visibility = View.VISIBLE
                                callTimerRunnable.run()
                            }
                        }
                    }
                    android.telecom.Call.STATE_DISCONNECTED -> {
                        scheduleInCallClose()
                        return
                    }
                }
            } else if (call == null && lastGsmPollState != -1) {
                // GSM call was seen by poll but is now gone — call ended
                scheduleInCallClose()
                return
            } else if (call == null && lastGsmPollState == -1) {
                // Never saw a GSM call — failed dial or slow setup
                // Safety timeout to avoid stuck screen
                if (System.currentTimeMillis() - inCallOpenTime > 8000) {
                    closeInCallScreen()
                    return
                }
            }
            callTimerHandler.postDelayed(this, 500)
        }
    }

    private var running = false
    private var gsmCallActive = false
    private var onlineSince = 0L

    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (onlineSince > 0) {
                val elapsed = (System.currentTimeMillis() - onlineSince) / 1000
                uptimeHandler.postDelayed(this, 1000)
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GatewayService.STATUS_ACTION -> {
                    val state = intent.getStringExtra("state") ?: return
                    val info = intent.getStringExtra("info") ?: ""
                    sipRegistered = intent.getBooleanExtra("registered", sipRegistered)
                    serviceCallStart = intent.getLongExtra("call_start", 0L)
                    updateStatus(state, info)
                    refreshBackgroundStatus()

                    val newOnlineSince = intent.getLongExtra("online_since", 0L)
                    if (newOnlineSince != onlineSince) {
                        onlineSince = newOnlineSince
                        uptimeHandler.removeCallbacks(uptimeRunnable)
                        if (onlineSince > 0) {
                            uptimeRunnable.run()
                        } else {
                        }
                    }

                    val wasRunning = running
                    running = state != "STOPPED" && state != "ERROR"

                    val callActive = state in listOf(
                        "GSM_RINGING", "GSM_ANSWERED", "SIP_CALLING",
                        "SIP_RINGING", "BRIDGED", "GSM_DIALING", "TEARING_DOWN"
                    )
                    if (callActive != gsmCallActive) {
                        gsmCallActive = callActive
                    }

                    // No pop-up on an incoming call: the home view's live call
                    // card is the in-call UI now, and the old full-screen view
                    // appearing over it was just confusing.

                    if (inCallOpen) {
                        when (state) {
                            "GSM_DIALING" -> tvInCallStatus.text = "呼叫中"
                            "GSM_ANSWERED", "SIP_CALLING" -> tvInCallStatus.text = "正在连接"
                            "SIP_RINGING" -> tvInCallStatus.text = "正在振铃"
                            "BRIDGED" -> {
                                tvInCallStatus.text = "已接通"
                                tvInCallTimer.visibility = View.VISIBLE
                                startCallTimer()
                            }
                            "TEARING_DOWN" -> tvInCallStatus.text = "正在结束"
                            "IDLE" -> {
                                scheduleInCallClose()
                            }
                        }
                    }

                    appendLog("[$state] $info")
                }
                GatewayService.LOG_ACTION -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    appendLog(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.rootWindow).let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val handledInsets = WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
                val safeArea = insets.getInsets(handledInsets)
                view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)

                // The root owns these insets. Passing them on lets Material's
                // BottomNavigationView apply the IME inset a second time,
                // stretching the bar to fill the keyboard height.
                WindowInsetsCompat.Builder(insets)
                    .setInsets(handledInsets, Insets.NONE)
                    .build()
            }
            ViewCompat.requestApplyInsets(root)
        }

        // Tab containers
        tabbedRoot = findViewById(R.id.tabbedRoot)
        tabHome = findViewById(R.id.tabHome)
        tabConfig = findViewById(R.id.tabConfig)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        bottomNavigation.setOnItemSelectedListener { item ->
            val destination = when (item.itemId) {
                R.id.navHome -> "home"
                R.id.navSettings -> "config"
                R.id.navLogs -> "logs"
                else -> null
            }
            if (destination == "config" && destination != currentTab) {
                if (currentTab == "logs" && logsReturnTab == "config") switchTab("config")
                else openConfigView()
            } else if (destination != null && destination != currentTab) switchTab(destination)
            destination != null
        }
        findViewById<View>(R.id.btnConfigBack).setOnClickListener { switchTab("home") }
        findViewById<View>(R.id.btnCfgSave).setOnClickListener { saveConfigFromView() }
        findViewById<MaterialButton>(R.id.btnStartGatewayDiagnostics)
            .setOnClickListener { openGatewayDiagnostics() }
        findViewById<View>(R.id.btnCfgClearRecents).setOnClickListener { confirmClearRecents() }
        findViewById<View>(R.id.btnControlPair).setOnClickListener { pairControlGateway() }
        findViewById<View>(R.id.btnEnableSmsRecovery).setOnClickListener { offerSmsRecovery() }
        findViewById<View>(R.id.btnScanSmsRecovery).setOnClickListener { scanSmsRecovery() }
        findViewById<View>(R.id.btnConfigureServerSip).setOnClickListener { configureServerSip() }
        findViewById<View>(R.id.btnSimPropose).setOnClickListener { proposeSimBindings() }
        findViewById<View>(R.id.btnSimConfirm).setOnClickListener { confirmSimBindings() }
        restoreControlUiState()
        restorePendingSimProposal()
        tvHomeStatusPill = findViewById(R.id.tvHomeStatusPill)
        tvHomeTlsBadge = findViewById(R.id.tvHomeTlsBadge)
        tvHomeSrtpBadge = findViewById(R.id.tvHomeSrtpBadge)
        tvNetMobile = findViewById(R.id.tvNetMobile)
        tvNetWifi = findViewById(R.id.tvNetWifi)
        tvSimSummary = findViewById(R.id.tvSimSummary)
        tvBackgroundState = findViewById(R.id.tvBackgroundState)
        tvBackgroundConnection = findViewById(R.id.tvBackgroundConnection)
        tvBackgroundNotification = findViewById(R.id.tvBackgroundNotification)
        tvBackgroundBattery = findViewById(R.id.tvBackgroundBattery)
        tvBackgroundIssue = findViewById(R.id.tvBackgroundIssue)
        tvVoiceStartExplanation = findViewById(R.id.tvVoiceStartExplanation)
        btnStartGatewayDiagnostics = findViewById(R.id.btnStartGatewayDiagnostics)
        swBackgroundEnabled = findViewById(R.id.swBackgroundEnabled)
        swBackgroundEnabled.setOnCheckedChangeListener { _, enabled ->
            if (updatingBackgroundControls) return@setOnCheckedChangeListener
            val changed = runCatching {
                GatewayBackgroundRuntime.setEnabled(this, enabled)
            }.getOrDefault(false)
            if (!changed) {
                updatingBackgroundControls = true
                swBackgroundEnabled.isChecked = !enabled
                updatingBackgroundControls = false
                Toast.makeText(this, "后台运行设置未能保存", Toast.LENGTH_LONG).show()
            }
            refreshBackgroundStatus()
        }
        findViewById<MaterialButton>(R.id.btnRequestNotificationPermission)
            .setOnClickListener {
                GatewayBackgroundRuntime.requestNotificationPermission(this)
                refreshBackgroundStatus()
            }
        findViewById<MaterialButton>(R.id.btnOpenNotificationSettings)
            .setOnClickListener { GatewayBackgroundRuntime.openNotificationSettings(this) }
        findViewById<MaterialButton>(R.id.btnOpenBatterySettings)
            .setOnClickListener { GatewayBackgroundRuntime.openBatterySettings(this) }
        homeCallCard = findViewById(R.id.homeCallCard)
        tvHomeCallDirection = findViewById(R.id.tvHomeCallDirection)
        tvHomeCallTimer = findViewById(R.id.tvHomeCallTimer)
        tvHomeCallFrom = findViewById(R.id.tvHomeCallFrom)
        tvHomeCallTo = findViewById(R.id.tvHomeCallTo)
        btnHomeMute = findViewById(R.id.btnHomeMute)
        btnHomeSnoop = findViewById(R.id.btnHomeSnoop)
        btnHomeEnd = findViewById(R.id.btnHomeEnd)
        homeTrafficList = findViewById(R.id.homeTrafficList)
        tvHomeTrafficEmpty = findViewById(R.id.tvHomeTrafficEmpty)
        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilterIncoming = findViewById(R.id.btnFilterIncoming)
        btnFilterOutgoing = findViewById(R.id.btnFilterOutgoing)
        btnFilterAll.setOnClickListener { setCallFilter("all") }
        btnFilterIncoming.setOnClickListener { setCallFilter("in") }
        btnFilterOutgoing.setOnClickListener { setCallFilter("out") }
        tvNetMobile.setOnClickListener { showLinkDetails(mobile = true) }
        tvNetWifi.setOnClickListener { showLinkDetails(mobile = false) }
        findViewById<View>(R.id.btnHomeMenu).setOnClickListener { openConfigView() }
        // Tapping the status pill retries the connection, the way the old
        // settings screen's reconnect button did.
        tvHomeStatusPill.setOnClickListener {
            if (gatewayOnline) {
                // Already registered — reconnecting would drop a working
                // registration and send SIP the server did not need.
                Toast.makeText(this, "SIP 已注册", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val background = runCatching { GatewayBackgroundRuntime.snapshot(this) }.getOrNull()
            val voiceStarted = background?.let {
                it.running && it.connectionLabel.startsWith("SIP connecting", ignoreCase = true)
            } == true
            if (!voiceStarted) {
                Toast.makeText(this, "SIP 语音尚未启动。请到设置运行设备诊断并确认启动。", Toast.LENGTH_LONG).show()
                openConfigView()
                return@setOnClickListener
            }
            if (!GatewayBackgroundRuntime.allowedRecovery(this)) {
                Toast.makeText(this, "请先在“后台运行”卡片中重新启用后台运行", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            appendLog("Reconnect requested")
            Toast.makeText(this, "正在重新连接…", Toast.LENGTH_SHORT).show()
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_RECONNECT
            })
        }
        btnHomeMute.setOnClickListener { toggleAgentMute() }
        btnHomeSnoop.setOnClickListener { toggleMonitor() }
        btnHomeEnd.setOnClickListener { endCallFromInCallScreen() }


        // Named and versioned at the top of settings.  It is the first thing
        // asked for when a change appears not to have taken, and this deploy
        // path can leave the running build and the file on disk disagreeing.
        findViewById<TextView>(R.id.tvCfgVersion).text = "v${BuildConfig.VERSION_NAME}"

        // Logs view — opened from the Settings header, back returns there
        // rather than to home, so the icon behaves like a drill-down.
        tabLogs = findViewById(R.id.tabLogs)
        constrainWideContent(tabHome)
        constrainWideContent(tabConfig)
        constrainWideContent(tabLogs)
        findViewById<View>(R.id.btnCfgLogs).setOnClickListener { switchTab("logs") }
        findViewById<View>(R.id.btnLogsBack).setOnClickListener { switchTab("config") }
        findViewById<View>(R.id.btnLogsCopy).setOnClickListener { copyLog() }
        findViewById<View>(R.id.btnLogsClear).setOnClickListener { clearLog() }

        // Logs-view views
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        tvPermissionNotice = findViewById(R.id.tvPermissionNotice)

        // Clear the view, but keep whatever the service has buffered: onResume
        // drains it into the view a moment later.  Discarding it here threw
        // away exactly the lines worth reading — the ones from before anyone
        // opened the app, which is when the gateway runs unattended.
        tvLog.text = ""

        // In-call views
        inCallView = findViewById(R.id.inCallView)
        tvInCallStatus = findViewById(R.id.tvInCallStatus)
        tvInCallNumber = findViewById(R.id.tvInCallNumber)
        tvInCallTimer = findViewById(R.id.tvInCallTimer)
        btnInCallEnd = findViewById(R.id.btnInCallEnd)
        btnInCallEnd.setOnClickListener { endCallFromInCallScreen() }
        btnInCallMonitor = findViewById(R.id.btnInCallMonitor)
        btnInCallMonitor.setOnClickListener { toggleMonitor() }

        requestSmsPermissions()

        // Rebuild the settings controls before restoring their non-secret
        // state. The password field is restored from encrypted storage only.
        val restoredTab = savedInstanceState?.getString(STATE_CURRENT_TAB)
            ?.takeIf { it in setOf("home", "config", "logs") } ?: "home"
        val restoreConfig = savedInstanceState?.getBoolean(STATE_CONFIG_OPEN) == true
        if (restoreConfig) {
            openConfigView()
            restoreConfigFormState(savedInstanceState!!)
        }
        restorePendingSmsRecovery(savedInstanceState)
        logsReturnTab = savedInstanceState?.getString(STATE_LOGS_RETURN_TAB)
            ?.takeIf { it == "home" || it == "config" } ?: "home"
        // Nothing is visible until a tab is selected — switchTab() returns
        // early when the requested tab is already current, so the initial
        // state has to be applied explicitly.
        switchTab(restoredTab)
        setCallFilter("all")
        refreshBackgroundStatus()
        refreshSimSummary()

        // Restore only paired HTTPS control sync; SIP voice needs an explicit
        // user-triggered diagnostic and confirmation below.
        autoStartGateway()
        if (savedInstanceState != null && pendingRecoveryMode != null && !recoveryPermissionRequestInFlight) {
            window.decorView.post { if (!isFinishing && !isDestroyed) requestRecoveryPermissionOrConfigure() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT_TAB, currentTab)
        outState.putString(STATE_LOGS_RETURN_TAB, logsReturnTab)
        val configOpen = currentTab == "config" || (currentTab == "logs" && logsReturnTab == "config")
        outState.putBoolean(STATE_CONFIG_OPEN, configOpen)
        if (configOpen) saveConfigFormState(outState)
        outState.putString(STATE_RECOVERY_MODE, pendingRecoveryMode?.name)
        outState.putString(STATE_RECOVERY_GATEWAY_ID, pendingRecoveryGatewayId)
        outState.putString(STATE_RECOVERY_CONTROL_BASE_URL, pendingRecoveryControlBaseUrl)
        outState.putBoolean(STATE_RECOVERY_PERMISSION_IN_FLIGHT, recoveryPermissionRequestInFlight)
    }

    /** Save editable settings except the password. It remains only in the
     * encrypted Keystore-backed credential store across activity recreation. */
    private fun saveConfigFormState(state: Bundle) {
        state.putString(STATE_CONFIG_GATEWAY_ID, configGatewayIdAtOpen)
        state.putString(STATE_CONFIG_CONTROL_BASE_URL, configControlBaseUrlAtOpen)
        state.putString(STATE_CONFIG_SERVER_AT_OPEN, configServerAtOpen)
        state.putString(STATE_CONFIG_USER_AT_OPEN, configUserAtOpen)
        listOf(
            R.id.etControlUrl to STATE_CONTROL_URL,
            R.id.etControlDeviceName to STATE_CONTROL_DEVICE_NAME,
            R.id.etCfgServer to STATE_CFG_SERVER,
            R.id.etCfgPort to STATE_CFG_PORT,
            R.id.etCfgUser to STATE_CFG_USER
        ).forEach { (id, key) ->
            state.putString(key, findViewById<EditText>(id).text.toString())
        }
        state.putIntArray(STATE_OWN_NUMBER_SLOTS, ownNumberFields.map { it.first }.toIntArray())
        state.putStringArray(STATE_OWN_NUMBER_VALUES,
            ownNumberFields.map { it.second.text.toString() }.toTypedArray())
        state.putBoolean(STATE_CFG_AUTOCONNECT, findViewById<MaterialSwitch>(R.id.cbCfgAutoconnect).isChecked)
        state.putBoolean(STATE_CFG_STUN, findViewById<MaterialSwitch>(R.id.cbCfgUseStun).isChecked)
        state.putBoolean(STATE_CFG_TRANSLIT, findViewById<MaterialSwitch>(R.id.cbCfgTranslit).isChecked)
        state.putInt(STATE_CFG_CODEC, findViewById<RadioGroup>(R.id.rgCfgCodec).checkedRadioButtonId)
        state.putInt(STATE_CFG_AGENT_VOLUME, findViewById<SeekBar>(R.id.sbCfgAgentVolume).progress)
        state.putInt(STATE_CFG_SCROLL_Y, findViewById<ScrollView>(R.id.svConfig).scrollY)
    }

    private fun restoreConfigFormState(state: Bundle) {
        configGatewayIdAtOpen = state.getString(STATE_CONFIG_GATEWAY_ID)
        configControlBaseUrlAtOpen = state.getString(STATE_CONFIG_CONTROL_BASE_URL)
        configServerAtOpen = state.getString(STATE_CONFIG_SERVER_AT_OPEN)
        configUserAtOpen = state.getString(STATE_CONFIG_USER_AT_OPEN)
        listOf(
            R.id.etControlUrl to STATE_CONTROL_URL,
            R.id.etControlDeviceName to STATE_CONTROL_DEVICE_NAME,
            R.id.etCfgServer to STATE_CFG_SERVER,
            R.id.etCfgPort to STATE_CFG_PORT,
            R.id.etCfgUser to STATE_CFG_USER
        ).forEach { (id, key) -> state.getString(key)?.let { findViewById<EditText>(id).setText(it) } }
        val slots = state.getIntArray(STATE_OWN_NUMBER_SLOTS) ?: intArrayOf()
        val values = state.getStringArray(STATE_OWN_NUMBER_VALUES) ?: emptyArray()
        val restored = slots.zip(values).toMap()
        ownNumberFields.forEach { (slot, field) -> restored[slot]?.let { field.setText(it) } }
        findViewById<MaterialSwitch>(R.id.cbCfgAutoconnect).isChecked = state.getBoolean(STATE_CFG_AUTOCONNECT)
        findViewById<MaterialSwitch>(R.id.cbCfgUseStun).isChecked = state.getBoolean(STATE_CFG_STUN)
        findViewById<MaterialSwitch>(R.id.cbCfgTranslit).isChecked = state.getBoolean(STATE_CFG_TRANSLIT)
        val codec = state.getInt(STATE_CFG_CODEC, R.id.rbCodecG722)
        findViewById<RadioGroup>(R.id.rgCfgCodec).check(codec)
        findViewById<SeekBar>(R.id.sbCfgAgentVolume).progress = state.getInt(STATE_CFG_AGENT_VOLUME, 3)
        findViewById<ScrollView>(R.id.svConfig).post {
            findViewById<ScrollView>(R.id.svConfig).scrollTo(0, state.getInt(STATE_CFG_SCROLL_Y, 0))
        }
    }

    private fun restorePendingSmsRecovery(state: Bundle?) {
        pendingRecoveryMode = state?.getString(STATE_RECOVERY_MODE)
            ?.let { runCatching { SmsProviderRecovery.HistoryMode.valueOf(it) }.getOrNull() }
        pendingRecoveryGatewayId = state?.getString(STATE_RECOVERY_GATEWAY_ID)
        pendingRecoveryControlBaseUrl = state?.getString(STATE_RECOVERY_CONTROL_BASE_URL)
        recoveryPermissionRequestInFlight = state?.getBoolean(STATE_RECOVERY_PERMISSION_IN_FLIGHT) == true
        val currentSession = CredentialStore.load(this)
        if (pendingRecoveryMode != null && (pendingRecoveryGatewayId != currentSession?.gatewayId ||
                pendingRecoveryControlBaseUrl != currentSession?.controlBaseUrl)) {
            clearPendingSmsRecovery()
            Toast.makeText(this, "配对账户已变化，短信补收确认已取消。", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearPendingSmsRecovery() {
        pendingRecoveryMode = null
        pendingRecoveryGatewayId = null
        pendingRecoveryControlBaseUrl = null
        recoveryPermissionRequestInFlight = false
    }

    private fun constrainWideContent(content: View) {
        if (resources.configuration.screenWidthDp < 720) return
        content.post {
            val density = resources.displayMetrics.density
            val widthDp = (resources.configuration.screenWidthDp - 48).coerceAtMost(760)
            content.layoutParams = (content.layoutParams as? android.widget.FrameLayout.LayoutParams
                ?: return@post).apply {
                width = (widthDp * density).toInt()
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
    }

    private fun refreshBackgroundStatus() {
        refreshSmsRecoveryStatus()
        if (!::tvBackgroundState.isInitialized) return
        val status = runCatching { GatewayBackgroundRuntime.snapshot(this) }.getOrNull()
        updatingBackgroundControls = true
        if (status == null) {
            tvBackgroundState.text = "服务状态：暂时无法读取"
            tvBackgroundConnection.text = "连接状态：—"
            tvBackgroundNotification.text = "通知权限：未知"
            tvBackgroundBattery.text = "电池优化：未知"
            tvBackgroundIssue.text = "请稍后重试，或查看运行日志。"
            tvBackgroundIssue.visibility = View.VISIBLE
            updateVoiceStartVisibility(voiceRunning = false)
            swBackgroundEnabled.isChecked = false
            updatingBackgroundControls = false
            refreshPairedControlStatus()
            return
        }

        swBackgroundEnabled.isChecked = status.enabled
        tvBackgroundState.text = "后台进程：" + when {
            status.running -> "正在运行"
            status.enabled -> "已启用，等待系统启动"
            else -> "未运行"
        }
        val syncedAt = status.lastSyncAt?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        }
        tvBackgroundConnection.text = buildString {
            append("连接状态：")
            append(localizedBackgroundConnection(status.connectionLabel))
            if (syncedAt != null) append("\n最近同步：$syncedAt")
        }
        tvBackgroundNotification.text = if (status.notificationsEnabled) {
            "通知：已允许"
        } else {
            "通知：未允许，后台运行状态可能无法显示在通知栏"
        }
        tvBackgroundBattery.text = if (status.batteryExempt) {
            "电池优化：已豁免"
        } else {
            "电池优化：未豁免，系统可能限制后台运行"
        }
        tvBackgroundIssue.text = status.issue?.let(::localizedBackgroundIssue).orEmpty()
        tvBackgroundIssue.visibility = if (status.issue.isNullOrBlank()) View.GONE else View.VISIBLE
        updateVoiceStartVisibility(
            voiceRunning = status.running && status.connectionLabel
                .startsWith("SIP connecting", ignoreCase = true)
        )
        findViewById<View>(R.id.btnRequestNotificationPermission).visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !status.notificationsEnabled) View.VISIBLE else View.GONE
        updatingBackgroundControls = false
        refreshPairedControlStatus()
    }

    private fun refreshSmsRecoveryStatus() {
        if (!::tabConfig.isInitialized) return
        val status = runCatching { SmsProviderRecovery.status(this) }.getOrNull() ?: return
        findViewById<TextView>(R.id.tvSmsRecoveryStatus).text = when {
            status.state == SmsProviderRecovery.State.NOT_PAIRED -> "短信补收：请先配对中转服务器。"
            !status.configured -> "短信补收：未启用，可恢复 Android 已保存的收件箱短信。"
            status.state == SmsProviderRecovery.State.PERMISSION_REQUIRED -> "短信补收：需要允许读取短信。广播收发不受此选项影响。"
            else -> "短信补收：已启用 · 补入 ${status.importedRows} 条 · 已匹配 ${status.matchedBroadcasts} 条广播。"
        }
        findViewById<View>(R.id.btnScanSmsRecovery).isEnabled = status.configured && !recoveryBusy.get()
    }

    private fun configureServerSip() {
        val session = CredentialStore.load(this) ?: run {
            Toast.makeText(this,"请先配对中转服务器",Toast.LENGTH_LONG).show();return
        }
        val button = findViewById<View>(R.id.btnConfigureServerSip)
        button.isEnabled = false
        Thread({
            try {
                val client = ControlApiClient(this,session.controlBaseUrl)
                val available = client.getSipConfiguration(session.gatewayId).optBoolean("available",false)
                check(available) { "SIP_NOT_CONFIGURED" }
                val retryKey = VoiceCredentialStore.beginProvisioning(this,session.gatewayId)
                val config = client.rotateSipCredentials(retryKey,session.gatewayId)
                VoiceCredentialStore.saveProvisioned(this,session.gatewayId,config)
                runOnUiThread {
                    findViewById<EditText>(R.id.etCfgServer).setText(config.getString("server_name"))
                    findViewById<EditText>(R.id.etCfgUser).setText(config.getString("auth_username"))
                    findViewById<EditText>(R.id.etCfgPort).setText(getSharedPreferences("gateway",MODE_PRIVATE).getInt("port",5061).toString())
                    findViewById<EditText>(R.id.etCfgPass).setText(VoiceCredentialStore.password(this))
                    refreshBackgroundStatus()
                    Toast.makeText(this,"SIP 已配置并加密保存。请运行设备诊断，再确认启动语音。",Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val code = (e as? ControlApiException)?.code ?: "SIP_CONFIGURATION_FAILED"
                if (code in setOf("SIP_BOOTSTRAP_EXPIRED", "IDEMPOTENCY_KEY_REUSED")) {
                    VoiceCredentialStore.expireProvisioning(this,session.gatewayId)
                }
                runOnUiThread {Toast.makeText(this,"SIP 配置未完成：$code。可重试；请检查服务器是否已启用语音。",Toast.LENGTH_LONG).show()}
            } finally {runOnUiThread {button.isEnabled=true}}
        },"sip-provisioning").start()
    }

    private fun offerSmsRecovery() {
        clearPendingSmsRecovery()
        val session = CredentialStore.load(this)
        if (session == null) {
            Toast.makeText(this, "请先配对中转服务器", Toast.LENGTH_LONG).show(); return
        }
        pendingRecoveryGatewayId = session.gatewayId
        pendingRecoveryControlBaseUrl = session.controlBaseUrl
        MaterialAlertDialogBuilder(this).setTitle("短信补收范围")
            .setMessage("补收需要读取系统短信权限，无需 root。默认只保护启用之后的短信；导入现有收件箱会将其中所有短信上传给当前配对的账户。")
            .setPositiveButton("只保护今后的短信") { _, _ ->
                pendingRecoveryMode = SmsProviderRecovery.HistoryMode.SINCE_ENABLE
                requestRecoveryPermissionOrConfigure()
            }.setNeutralButton("预览现有收件箱") { _, _ ->
                pendingRecoveryMode = SmsProviderRecovery.HistoryMode.FULL_CURRENT_INBOX
                requestRecoveryPermissionOrConfigure()
            }.setNegativeButton("取消") { _, _ -> clearPendingSmsRecovery() }
            .setOnCancelListener { clearPendingSmsRecovery() }
            .show()
    }

    private fun requestRecoveryPermissionOrConfigure() {
        val expectedGatewayId = pendingRecoveryGatewayId ?: return
        val expectedControlBaseUrl = pendingRecoveryControlBaseUrl ?: return
        val currentSession = CredentialStore.load(this)
        if (currentSession?.gatewayId != expectedGatewayId ||
            currentSession.controlBaseUrl != expectedControlBaseUrl) {
            clearPendingSmsRecovery()
            Toast.makeText(this,"配对账户已变化，请重新选择补收范围",Toast.LENGTH_LONG).show();return
        }
        if (SmsProviderRecovery.permissionState(this) != SmsProviderRecovery.PermissionState.AVAILABLE) {
            recoveryPermissionRequestInFlight = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), REQ_SMS_RECOVERY)
            return
        }
        val mode = pendingRecoveryMode ?: return
        if (mode == SmsProviderRecovery.HistoryMode.FULL_CURRENT_INBOX) {
            Thread({
                val preview = SmsProviderRecovery.preview(this, mode)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (preview.state != SmsProviderRecovery.State.READY) {
                        clearPendingSmsRecovery()
                        Toast.makeText(this,"无法读取系统收件箱，请检查权限",Toast.LENGTH_LONG).show();return@runOnUiThread
                    }
                    MaterialAlertDialogBuilder(this).setTitle("确认导入现有短信")
                        .setMessage("预览首页有 ${preview.firstPageRows} 条${if (preview.moreRows) "，还有更多" else ""}。继续会分批上传当前系统收件箱，包含过去的私人短信。")
                        .setPositiveButton("导入到当前账户") { _, _ ->
                            clearPendingSmsRecovery()
                            configureSmsRecovery(mode, expectedGatewayId, expectedControlBaseUrl)
                        }
                        .setNegativeButton("取消") { _, _ -> clearPendingSmsRecovery() }
                        .setOnCancelListener { clearPendingSmsRecovery() }
                        .show()
                }
            },"sms-recovery-preview").start()
        } else {
            clearPendingSmsRecovery()
            configureSmsRecovery(mode, expectedGatewayId, expectedControlBaseUrl)
        }
    }

    private fun configureSmsRecovery(
        mode: SmsProviderRecovery.HistoryMode,
        expectedGatewayId: String,
        expectedControlBaseUrl: String?
    ) {
        Thread({
            val result = SmsProviderRecovery.configure(this, mode, expectedGatewayId = expectedGatewayId,
                expectedControlBaseUrl = expectedControlBaseUrl)
            runOnUiThread {
                refreshSmsRecoveryStatus()
                if (result.configured) scanSmsRecovery()
                else Toast.makeText(this,"短信补收未启用，请检查权限及配对状态",Toast.LENGTH_LONG).show()
            }
        },"sms-recovery-configure").start()
    }

    private fun scanSmsRecovery() {
        if (!recoveryBusy.compareAndSet(false,true)) return
        Thread({
            try {
                // Bounded work per UI attempt; later service rounds resume the checkpoint.
                for (page in 0 until 4) {
                    val result = SmsProviderRecovery.scan(this)
                    if (result.state != SmsProviderRecovery.State.MORE_PAGES) break
                }
                if (GatewayBackgroundRuntime.allowedRecovery(this)) GatewayService.startControl(this)
            } finally {
                recoveryBusy.set(false)
                runOnUiThread { if (!isDestroyed) refreshSmsRecoveryStatus() }
            }
        },"sms-recovery-scan").start()
    }

    private fun refreshPairedControlStatus() {
        if (!::tabConfig.isInitialized) return
        val session = CredentialStore.load(this) ?: return
        val controlStatus = findViewById<TextView>(R.id.tvControlStatus)
        if (!controlStatus.text.toString().startsWith("Paired")) return
        controlStatus.text = "Paired gateway ${session.gatewayId.take(8)}… · ${backgroundSyncLabel()}"
    }

    private fun updateVoiceStartVisibility(voiceRunning: Boolean) {
        if (!::btnStartGatewayDiagnostics.isInitialized || !::tvVoiceStartExplanation.isInitialized) return
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val hasSipConfig = !prefs.getString("server", "").isNullOrBlank() &&
            !prefs.getString("user", "").isNullOrBlank()
        val visible = hasSipConfig && !voiceRunning
        btnStartGatewayDiagnostics.visibility = if (visible) View.VISIBLE else View.GONE
        tvVoiceStartExplanation.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun localizedBackgroundConnection(label: String): String = when (label.trim().lowercase(Locale.ROOT)) {
        "stopped in android task manager" -> "已被 Android 任务管理器停止"
        "stopped" -> "已停止"
        "not paired" -> "尚未配对"
        "starting" -> "正在启动"
        "paused" -> "已暂停"
        "retrying" -> "正在重试"
        "wss connected · https polling" -> "长连接已连接 · HTTPS 轮询"
        "wss connecting · https polling" -> "正在连接长连接 · HTTPS 轮询"
        "https reauth pending" -> "等待 HTTPS 重新认证"
        "https polling · wss retrying" -> "HTTPS 轮询中 · 长连接重试中"
        "https polling · wss connecting" -> "HTTPS 轮询中 · 长连接连接中"
        "https polling · wake auth pending" -> "HTTPS 轮询中 · 等待长连接重新认证"
        "sip connecting · https polling" -> "SIP 连接中 · HTTPS 轮询"
        "unknown" -> "未知"
        else -> label.ifBlank { "未知" }
    }

    private fun localizedBackgroundIssue(issue: String): String = when {
        issue.startsWith("Android stopped the gateway from Task Manager", ignoreCase = true) ->
            "Android 任务管理器停止了网关。请在此卡片中重新启用后台运行后恢复。"
        issue.startsWith("Pair this device with the control server", ignoreCase = true) ->
            "尚未配对控制服务器。请先打开“设置”完成设备配对。"
        issue.startsWith("Android did not allow the foreground service to start", ignoreCase = true) ->
            "Android 暂未允许启动后台服务。请返回此应用后重试。"
        issue.startsWith("Pair this device again", ignoreCase = true) ->
            "配对会话已失效，请在设置中重新配对控制服务器。"
        issue.startsWith("Wake channel could not be opened", ignoreCase = true) ->
            "长连接暂不可用，网关会继续通过 HTTPS 轮询。"
        issue.startsWith("Control sync failed", ignoreCase = true) ->
            "控制服务器同步暂时失败，网关会自动重试。"
        issue.startsWith("Android could not grant a temporary voice CPU lease", ignoreCase = true) ->
            "Android 未能授予临时通话处理权限，屏幕关闭时通话可能受系统限制。"
        else -> issue
    }

    @SuppressLint("MissingPermission")
    private fun refreshSimSummary() {
        if (!::tvSimSummary.isInitialized) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            tvSimSummary.text = "SIM 状态：未获电话状态权限，暂时无法读取"
            return
        }
        val subscriptions = runCatching {
            getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                .orEmpty()
                .sortedBy { it.simSlotIndex }
        }.getOrElse {
            tvSimSummary.text = "SIM 状态：读取失败，请检查系统权限"
            return
        }
        if (subscriptions.isEmpty()) {
            tvSimSummary.text = "SIM 状态：未检测到已启用的 SIM；插入 SIM 后可在设置中绑定"
            return
        }
        val labels = subscriptions.map { info ->
            val slot = if (info.simSlotIndex >= 0) "SIM ${info.simSlotIndex + 1}" else "SIM"
            val carrier = info.carrierName?.toString()?.trim().orEmpty()
            if (carrier.isBlank()) slot else "$slot · $carrier"
        }
        tvSimSummary.text = "SIM 状态：检测到 ${subscriptions.size} 张已启用 SIM（${labels.joinToString("，")}）"
    }

    private fun autoStartGateway() {
        if (!GatewayBackgroundRuntime.allowedRecovery(this)) {
            appendLog("Auto-start paused after a system stop; enable background running again from the home screen")
            return
        }
        if (CredentialStore.load(this) != null) {
            if (GatewayService.startControl(this)) {
                appendLog("Auto-starting paired HTTPS control gateway; SIP voice waits for explicit diagnostics")
            }
            return
        }
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        if (!prefs.getString("server", "").isNullOrBlank() &&
            !prefs.getString("user", "").isNullOrBlank()
        ) {
            appendLog("Saved SIP credentials found; voice startup waits for explicit diagnostics")
        }
    }

    private fun restoreControlUiState() {
        val session = CredentialStore.load(this)
        val url = findViewById<EditText>(R.id.etControlUrl)
        if (session != null) {
            url.setText(session.controlBaseUrl)
            findViewById<TextView>(R.id.tvControlStatus).text =
                "Paired gateway ${session.gatewayId.take(8)}… · ${backgroundSyncLabel()}"
        } else {
            val saved = getSharedPreferences("gateway", MODE_PRIVATE)
                .getString("control_base_url", "").orEmpty()
            if (saved.isNotEmpty()) url.setText(saved)
            findViewById<TextView>(R.id.tvControlStatus).text = "Not paired"
        }
        findViewById<EditText>(R.id.etControlDeviceName).setText(
            getSharedPreferences("gateway", MODE_PRIVATE)
                .getString("control_device_name", android.os.Build.MODEL ?: "Old Android phone")
        )
    }

    private fun setControlBusy(busy: Boolean, status: String? = null) {
        controlBusy = busy
        findViewById<View>(R.id.btnControlPair).isEnabled = !busy
        findViewById<View>(R.id.btnSimPropose).isEnabled = !busy
        findViewById<View>(R.id.btnSimConfirm).isEnabled = !busy
        pendingMappingProposal?.let { renderSimProposal(it, simProposalSubscriptions) }
        if (status != null) findViewById<TextView>(R.id.tvControlStatus).text = status
    }

    private fun backgroundSyncLabel(): String = if (GatewayBackgroundRuntime.allowedRecovery(this)) {
        "HTTPS 同步已启用"
    } else {
        "HTTPS 同步已暂停 · 请在首页启用后台运行"
    }

    private fun pairControlGateway() {
        val base = findViewById<EditText>(R.id.etControlUrl).text.toString().trim()
        val code = findViewById<EditText>(R.id.etControlPairingCode).text.toString().trim()
        val deviceName = findViewById<EditText>(R.id.etControlDeviceName).text.toString().trim()
            .ifEmpty { android.os.Build.MODEL ?: "Android gateway" }
        if (base.isEmpty() || code.isEmpty()) {
            Toast.makeText(this, "HTTPS server and one-time pairing code are required", Toast.LENGTH_LONG).show()
            return
        }
        val normalizedBase = base.trimEnd('/')
        setControlBusy(true, "Pairing over HTTPS…")
        Thread({
            try {
                val result = ControlApiClient(this, normalizedBase).pair(code, deviceName)
                val saved = getSharedPreferences("gateway", MODE_PRIVATE).edit()
                    .putString("control_base_url", normalizedBase)
                    .putString("control_device_name", deviceName)
                    .commit()
                if (!saved) throw IllegalStateException("Could not save control server settings")
                runOnUiThread {
                    findViewById<EditText>(R.id.etControlPairingCode).text.clear()
                    val syncEnabled = GatewayBackgroundRuntime.allowedRecovery(this)
                    val syncStarted = syncEnabled && GatewayService.startControl(this)
                    if (syncStarted) startService(Intent(this,GatewayService::class.java).apply {
                        action = GatewayService.ACTION_APPLY_CONFIG
                    })
                    findViewById<TextView>(R.id.tvControlStatus).text =
                        "Paired ${result.gatewayId.take(8)}… · ${if (result.sipAvailable) "SIP available" else "HTTPS control only"} · ${backgroundSyncLabel()}"
                    setControlBusy(false)
                    val message = when {
                        syncStarted -> "已配对。请核对并确认本地 SIM 插槽。"
                        !syncEnabled -> "已配对，后台同步仍暂停。请在首页显式启用后台运行，再确认本地 SIM 插槽。"
                        else -> "已配对，但后台服务未能启动。请查看首页后台状态后重试。"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    appendLog(when {
                        syncStarted -> "Control gateway paired; HTTPS sync enabled"
                        !syncEnabled -> "Control gateway paired; HTTPS sync remains paused until explicitly enabled"
                        else -> "Control gateway paired; background service could not start"
                    })
                    refreshBackgroundStatus()
                }
            } catch (e: Exception) {
                val detail = (e as? ControlApiException)?.code ?: "PAIRING_FAILED"
                runOnUiThread {
                    setControlBusy(false, "Pairing failed: $detail")
                    Toast.makeText(this, "Pairing failed: $detail", Toast.LENGTH_LONG).show()
                }
            }
        }, "gateway-pair").start()
    }

    private fun proposeSimBindings() {
        if (CredentialStore.load(this) == null) {
            Toast.makeText(this, "Pair the gateway first", Toast.LENGTH_LONG).show()
            return
        }
        val operationId = UUID.randomUUID().toString()
        val controlBase = findViewById<EditText>(R.id.etControlUrl).text.toString().trim()
        setControlBusy(true, "Reading local SIM capabilities…")
        Thread({
            try {
                // Subscription reads stay off the UI thread; SMS never queries the voice broker.
                val snapshot = SimRegistry.snapshot(this)
                val subscriptions = snapshot.subscriptions
                if (subscriptions.isEmpty()) {
                    throw ControlApiException("SIM_UNAVAILABLE", "No active SIM subscriptions are available")
                }
                val mappings = JSONArray()
                val existingBySubscription = snapshot.mappings.associateBy { it.subscriptionId }
                subscriptions.sortedBy { it.slotIndex }.forEach { sub ->
                    val row = JSONObject().put("slot_index", sub.slotIndex)
                        .put("label", sub.displayName.ifBlank { "SIM ${sub.slotIndex + 1}" })
                        .put("carrier_name", sub.carrierName)
                        .put("phone_number", JSONObject.NULL)
                    val known = existingBySubscription[sub.subscriptionId]
                    if (known != null && known.identityState != SimRegistry.IdentityState.UNVERIFIED) {
                        row.put("existing_sim_id", known.simId).put("same_sim_verified", true)
                    }
                    mappings.put(row)
                }
                val proposal = ControlApiClient(this, controlBase).proposeSimBindings(operationId, mappings)
                val encoded = JSONObject().put("mapping_revision", proposal.mappingRevision)
                    .put("mappings", JSONArray().apply {
                        proposal.mappings.forEach { m ->
                            put(JSONObject().put("sim_id", m.simId).put("slot_index", m.slotIndex)
                                .put("state", m.state).put("mapping_revision", m.mappingRevision))
                        }
                    }).put("selected", JSONObject()).toString()
                GatewayDatabase.get(this).saveSimProposal(operationId, encoded)
                runOnUiThread {
                    pendingMappingProposal = proposal
                    simProposalSubscriptions = subscriptions
                    selectedSimBindings.clear()
                    renderSimProposal(proposal, subscriptions)
                    setControlBusy(false, "Server assigned SIM IDs. Select the matching local SIM for each row, then confirm.")
                }
            } catch (e: Exception) {
                val detail = (e as? ControlApiException)?.code ?: "SIM_PROPOSAL_FAILED"
                runOnUiThread { setControlBusy(false, "SIM proposal failed: $detail") }
            }
        }, "gateway-sim-propose").start()
    }

    private fun restorePendingSimProposal() {
        val session = CredentialStore.load(this) ?: return
        Thread({ restorePendingSimProposalInBackground(session) }, "gateway-sim-restore").start()
    }

    private fun restorePendingSimProposalInBackground(expectedSession: CredentialStore.Session) {
        val saved = runCatching { GatewayDatabase.get(this).pendingSimProposal() }.getOrNull() ?: return
        try {
            val json = JSONObject(saved.second)
            val revision = json.getLong("mapping_revision")
            val mappingsJson = json.getJSONArray("mappings")
            val mappings = (0 until mappingsJson.length()).map { i ->
                val m = mappingsJson.getJSONObject(i)
                ServerMapping(m.getString("sim_id"), m.getInt("slot_index"),
                    m.getString("state"), m.getLong("mapping_revision"))
            }
            val proposal = MappingProposal(saved.first, revision, mappings)
            val snapshot = runCatching { SimRegistry.snapshot(this) }.getOrNull() ?: return
            val selected = json.optJSONObject("selected") ?: JSONObject()
            val restoredSelections = mutableMapOf<String, SimRegistry.SimSubscription>()
            selected.keys().forEach { simId ->
                val subId = selected.optInt(simId, Int.MIN_VALUE)
                snapshot.subscriptions.firstOrNull { it.subscriptionId == subId }
                    ?.let { restoredSelections[simId] = it }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || controlBusy || pendingMappingProposal != null) return@runOnUiThread
                val currentSession = CredentialStore.load(this) ?: return@runOnUiThread
                if (currentSession.gatewayId != expectedSession.gatewayId ||
                    currentSession.controlBaseUrl != expectedSession.controlBaseUrl) return@runOnUiThread
                pendingMappingProposal = proposal
                simProposalSubscriptions = snapshot.subscriptions
                selectedSimBindings.clear()
                selectedSimBindings.putAll(restoredSelections)
                renderSimProposal(proposal, snapshot.subscriptions)
            }
        } catch (_: Exception) {
            GatewayDatabase.get(this).clearSimProposal(saved.first)
        }
    }

    private fun persistSimProposalSelections(proposal: MappingProposal) {
        val mappings = JSONArray().apply {
            proposal.mappings.forEach { m ->
                put(JSONObject().put("sim_id", m.simId).put("slot_index", m.slotIndex)
                    .put("state", m.state).put("mapping_revision", m.mappingRevision))
            }
        }
        val selected = JSONObject().apply {
            selectedSimBindings.forEach { (simId, sub) -> put(simId, sub.subscriptionId) }
        }
        GatewayDatabase.get(this).saveSimProposal(proposal.operationId,
            JSONObject().put("mapping_revision", proposal.mappingRevision)
                .put("mappings", mappings).put("selected", selected).toString())
    }

    private fun renderSimProposal(proposal: MappingProposal, subscriptions: List<SimRegistry.SimSubscription>) {
        val container = findViewById<LinearLayout>(R.id.llSimBindings)
        container.removeAllViews()
        findViewById<View>(R.id.btnSimConfirm).visibility = View.VISIBLE
        val used = mutableSetOf<Int>()
        proposal.mappings.forEach { mapping ->
            val title = TextView(this).apply {
                text = "Server SIM ${mapping.slotIndex + 1} · ${mapping.simId} · ${mapping.state}"
                setTextColor(themeColor(MaterialR.attr.colorOnSurface))
                textSize = 13f
                setPadding(0, 12, 0, 4)
            }
            container.addView(title)
            val group = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
            val options = subscriptions.filter { it.slotIndex == mapping.slotIndex }.sortedBy { it.slotIndex }
            if (options.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "No active local SIM subscription in slot ${mapping.slotIndex + 1}"
                    setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
                    textSize = 12f
                    setPadding(0, 2, 0, 8)
                })
            }
            options.forEach { sub ->
                val radio = MaterialRadioButton(this).apply {
                    id = View.generateViewId()
                    minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    text = buildString {
                        append("Local SIM ${sub.slotIndex + 1}")
                        if (sub.carrierName.isNotBlank()) append(" · ${sub.carrierName}")
                        if (sub.displayName.isNotBlank() && sub.displayName != sub.carrierName) append(" · ${sub.displayName}")
                        if (!sub.phoneNumber.isNullOrBlank()) append(" · ${sub.phoneNumber}")
                    }
                    isEnabled = !controlBusy &&
                        (sub.subscriptionId !in used || selectedSimBindings[mapping.simId]?.subscriptionId == sub.subscriptionId)
                }
                group.addView(radio)
                if (selectedSimBindings[mapping.simId]?.subscriptionId == sub.subscriptionId) {
                    radio.isChecked = true
                    used += sub.subscriptionId
                }
                radio.setOnClickListener {
                    selectedSimBindings[mapping.simId] = sub
                    used.clear()
                    used.addAll(selectedSimBindings.values.map { it.subscriptionId })
                    persistSimProposalSelections(proposal)
                    renderSimProposal(proposal, subscriptions)
                }
            }
            container.addView(group)
        }
        val selected = proposal.mappings.mapNotNull { mapping ->
            selectedSimBindings[mapping.simId]?.takeIf { it.slotIndex == mapping.slotIndex }
        }
        findViewById<View>(R.id.btnSimConfirm).isEnabled = !controlBusy &&
            proposal.mappings.isNotEmpty() && selected.size == proposal.mappings.size &&
            selected.map { it.subscriptionId }.distinct().size == selected.size
    }

    private fun confirmSimBindings() {
        val proposal = pendingMappingProposal ?: return
        val chosenBySimId = linkedMapOf<String, SimRegistry.SimSubscription>()
        proposal.mappings.forEach { mapping ->
            val selected = selectedSimBindings[mapping.simId]
            if (selected == null || selected.slotIndex != mapping.slotIndex) {
                Toast.makeText(this, "Select the local SIM in the matching slot for every server row", Toast.LENGTH_LONG).show()
                return
            }
            chosenBySimId[mapping.simId] = selected
        }
        if (chosenBySimId.values.map { it.subscriptionId }.distinct().size != chosenBySimId.size) {
            Toast.makeText(this, "Each server SIM row must use a different local subscription", Toast.LENGTH_LONG).show()
            return
        }
        val controlBase = findViewById<EditText>(R.id.etControlUrl).text.toString().trim()
        val confirmations = JSONArray()
        proposal.mappings.forEach { mapping ->
            confirmations.put(JSONObject().put("sim_id", mapping.simId)
                .put("confirmed", true).put("slot_index", mapping.slotIndex))
        }
        setControlBusy(true, "Confirming SIM bindings with the server…")
        Thread({
            try {
                val initialSnapshot = SimRegistry.snapshot(this)
                if (chosenBySimId.values.any { chosen ->
                        initialSnapshot.subscriptions.none {
                            it.subscriptionId == chosen.subscriptionId && it.slotIndex == chosen.slotIndex
                        }
                    }) {
                    throw ControlApiException("SIM_SUBSCRIPTION_CHANGED", "SIM subscriptions changed. Refresh and select again.")
                }
                val results = ControlApiClient(this, controlBase)
                    .confirmSimBindings(proposal.operationId, confirmations)
                val proposedById = proposal.mappings.associateBy { it.simId }
                if (results.size != proposal.mappings.size ||
                    results.map { it.simId }.toSet() != proposedById.keys ||
                    results.any { it.mappingRevision != proposal.mappingRevision || proposedById[it.simId]?.slotIndex != it.slotIndex }) {
                    throw ControlApiException("MAPPING_REVISION_MISMATCH", "服务器映射版本不一致")
                }
                if (results.any { it.state != "active" }) {
                    throw ControlApiException("MAPPING_NOT_ACTIVE", "服务器未激活全部 SIM 映射")
                }
                val byId = results.associateBy { it.simId }
                val currentSnapshot = SimRegistry.snapshot(this)
                if (currentSnapshot.localRevisionBarrier != initialSnapshot.localRevisionBarrier) {
                    throw ControlApiException("SIM_SUBSCRIPTION_CHANGED", "SIM 身份在确认过程中发生变化，请重新核对")
                }
                val local = proposal.mappings.map { serverMapping ->
                    if (byId[serverMapping.simId]?.state != "active") {
                        throw ControlApiException("MAPPING_NOT_ACTIVE", "服务器未激活所选 SIM")
                    }
                    val sub = chosenBySimId[serverMapping.simId]
                        ?: throw ControlApiException("LOCAL_CONFIRMATION_MISSING", "服务器激活了未选择的本地 SIM")
                    if (sub.slotIndex != serverMapping.slotIndex || currentSnapshot.subscriptions.none {
                            it.subscriptionId == sub.subscriptionId && it.slotIndex == serverMapping.slotIndex
                        }) {
                        throw ControlApiException("SIM_SUBSCRIPTION_CHANGED", "SIM 插槽或订阅已变化，请重新核对")
                    }
                    SimRegistry.SimBindingConfirmation(serverMapping.simId, sub.subscriptionId)
                }
                SimRegistry.confirmMappings(this, local, proposal.mappingRevision)
                GatewayDatabase.get(this).clearSimProposal(proposal.operationId)
                runOnUiThread {
                    pendingMappingProposal = null
                    simProposalSubscriptions = emptyList()
                    selectedSimBindings.clear()
                    findViewById<LinearLayout>(R.id.llSimBindings).removeAllViews()
                    findViewById<View>(R.id.btnSimConfirm).visibility = View.GONE
                    val syncEnabled = GatewayBackgroundRuntime.allowedRecovery(this)
                    val syncStarted = syncEnabled && GatewayService.startControl(this)
                    val status = when {
                        syncStarted -> "SIM 映射已确认；HTTPS 同步可使用已激活的 SIM。"
                        !syncEnabled -> "SIM 映射已确认；后台同步已暂停，请在首页显式启用后台运行。"
                        else -> "SIM 映射已确认；后台服务未能启动，请查看首页状态后重试。"
                    }
                    setControlBusy(false, status)
                    refreshBackgroundStatus()
                }
            } catch (e: Exception) {
                val detail = (e as? ControlApiException)?.code ?: "SIM_CONFIRM_FAILED"
                runOnUiThread { setControlBusy(false, "SIM confirmation failed: $detail") }
            }
        }, "gateway-sim-confirm").start()
    }

    // ── Tab Navigation ───────────────────────────────────

    /** Show one of the top-level views and keep the bottom navigation in sync. */
    private fun switchTab(tab: String) {
        if (tab == currentTab) return
        if (tab == "logs" && currentTab.isNotBlank()) logsReturnTab = currentTab
        currentTab = tab

        tabHome.visibility = if (tab == "home") View.VISIBLE else View.GONE
        tabConfig.visibility = if (tab == "config") View.VISIBLE else View.GONE
        tabLogs.visibility = if (tab == "logs") View.VISIBLE else View.GONE
        val selectedId = when (tab) {
            "config" -> R.id.navSettings
            "logs" -> R.id.navLogs
            else -> R.id.navHome
        }
        if (bottomNavigation.selectedItemId != selectedId) {
            bottomNavigation.selectedItemId = selectedId
        }

        when (tab) {
            "home" -> refreshHome()
            // The view scrolls as lines arrive, but only while it is visible;
            // opening it has to jump to the newest entry itself.
            "logs" -> svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * Settings as a full screen rather than a dialog — there are enough fields
     * that a modal is cramped, and a screen gives room to explain them.
     */
    // ── Own number, per SIM ─────────────────────────────

    /** One active SIM, as Settings needs to describe it. */
    private data class SimInfo(val slot: Int, val subId: Int, val caption: String)

    /** Own-number fields on screen, paired with the SIM slot each belongs to.
     *  Slot -1 is the no-SIM-readable case, bound to the legacy key. */
    private val ownNumberFields = mutableListOf<Pair<Int, EditText>>()

    private fun ownNumberKey(slot: Int) =
        if (slot < 0) "own_number" else "own_number_slot_$slot"

    /**
     * The SIMs that are actually live, in slot order.
     *
     * Slots the hardware has but which hold no SIM are deliberately absent:
     * a triple-SIM handset carrying one SIM needs one number, not three
     * fields two of which can only ever be wrong.
     */
    @SuppressLint("MissingPermission")
    private fun activeSims(): List<SimInfo> = try {
        val sm = getSystemService(SubscriptionManager::class.java)
        val tm = getSystemService(TelephonyManager::class.java)
        (sm?.activeSubscriptionInfoList ?: emptyList()).map { info ->
            val slot = info.simSlotIndex
            val carrier = (info.carrierName ?: info.displayName ?: "").toString().trim()
            // The IMEI belongs to the radio, so it names the slot; the ICCID
            // names the card in it.  Either tells two otherwise identical
            // rows apart, so show whichever the platform will hand over.
            val ident = runCatching { tm?.getImei(slot) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { "IMEI $it" }
                ?: info.iccId?.takeIf { it.isNotBlank() }
                    ?.let { "ICCID \u2026${it.takeLast(6)}" }
                ?: ""
            SimInfo(
                slot = slot,
                subId = info.subscriptionId,
                caption = listOfNotNull(
                    "SIM ${slot + 1}",
                    carrier.ifEmpty { null },
                    ident.ifEmpty { null }
                ).joinToString("  \u00b7  ")
            )
        }.sortedBy { it.slot }
    } catch (e: Exception) {
        android.util.Log.w("MainActivity", "Could not enumerate SIMs: ${e.message}")
        emptyList()
    }

    private fun buildOwnNumberFields(prefs: android.content.SharedPreferences) {
        val container = findViewById<LinearLayout>(R.id.llCfgOwnNumbers)
        container.removeAllViews()
        ownNumberFields.clear()

        val sims = activeSims()
        if (sims.isEmpty()) {
            // Nothing readable — still offer one field on the legacy key, so a
            // device that will not describe its SIMs stays configurable.
            val row = layoutInflater.inflate(R.layout.item_sim_number, container, false)
            val et = row.findViewById<EditText>(R.id.etSimNumber)
            et.isSaveEnabled = false
            et.setText(prefs.getString("own_number", ""))
            row.findViewById<TextView>(R.id.tvSimCaption).text = "No active SIM detected"
            container.addView(row)
            ownNumberFields += -1 to et
            return
        }

        val lowest = sims.first().slot
        for (sim in sims) {
            val row = layoutInflater.inflate(R.layout.item_sim_number, container, false)
            val et = row.findViewById<EditText>(R.id.etSimNumber)
            et.isSaveEnabled = false
            val stored = prefs.getString(ownNumberKey(sim.slot), "").orEmpty()
            // First run after upgrading there are no per-slot values yet; the
            // one legacy number belongs to whichever SIM was in use, so offer
            // it on the lowest active slot rather than making it be retyped.
            et.setText(
                stored.ifEmpty {
                    if (sim.slot == lowest) prefs.getString("own_number", "").orEmpty() else ""
                }
            )
            row.findViewById<TextView>(R.id.tvSimCaption).text = sim.caption
            container.addView(row)
            ownNumberFields += sim.slot to et
        }
    }

    private fun openConfigView() {
        val session = CredentialStore.load(this)
        configGatewayIdAtOpen = session?.gatewayId
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        configControlBaseUrlAtOpen = session?.controlBaseUrl
        configServerAtOpen = prefs.getString("server", "")
        configUserAtOpen = prefs.getString("user", "")
        findViewById<EditText>(R.id.etCfgServer).setText(prefs.getString("server", ""))
        findViewById<EditText>(R.id.etCfgPort).setText(prefs.getInt("port", 5061).toString())
        findViewById<EditText>(R.id.etCfgUser).setText(prefs.getString("user", ""))
        findViewById<EditText>(R.id.etCfgPass).setText(VoiceCredentialStore.password(this))
        buildOwnNumberFields(prefs)
        findViewById<MaterialSwitch>(R.id.cbCfgAutoconnect).isChecked =
            GatewayBackgroundRuntime.allowedRecovery(this)
        findViewById<MaterialSwitch>(R.id.cbCfgUseStun).isChecked =
            prefs.getBoolean("use_stun", false)
        findViewById<MaterialSwitch>(R.id.cbCfgTranslit).isChecked =
            prefs.getBoolean("translit_ascii", false)
        val cbTls = findViewById<MaterialSwitch>(R.id.cbCfgTls)
        val cbSrtp = findViewById<MaterialSwitch>(R.id.cbCfgSrtp)
        cbTls.isChecked = true
        cbSrtp.isChecked = true
        cbTls.isEnabled = false

        // SRTP follows TLS in the UI as well as in the code.  The setting is
        // disabled rather than hidden so it is visible that audio encryption
        // exists and what it depends on -- a hidden control just looks like a
        // missing feature.
        fun syncSrtpEnabled() {
            cbSrtp.isEnabled = cbTls.isChecked
            findViewById<TextView>(R.id.tvCfgSrtpHint).text = if (cbTls.isChecked) {
                "SIP signalling uses certificate-validated TLS and calls require SRTP. " +
                    "A server that cannot negotiate SRTP will be rejected."
            } else {
                "TLS is required for SIP calls."
            }
        }
        syncSrtpEnabled()
        cbSrtp.isEnabled = false
        cbTls.setOnCheckedChangeListener { _, checked ->
            syncSrtpEnabled()
            // Move the port with the transport, the way every other SIP client
            // does.  TLS on 5060 does not fail cleanly: the plaintext port
            // never answers a handshake, so it hangs until "SSL handshake
            // timed out" -- which reads as a certificate or network fault and
            // sends you looking in entirely the wrong place.
            // Only the two well-known defaults are touched; a custom port is
            // left exactly as typed.
            val portField = findViewById<EditText>(R.id.etCfgPort)
            val current = portField.text.toString().trim().toIntOrNull()
            if (checked && current == 5060) {
                portField.setText("5061")
                Toast.makeText(this, "Port switched to 5061 for TLS", Toast.LENGTH_SHORT).show()
            } else if (!checked && current == 5061) {
                portField.setText("5060")
                Toast.makeText(this, "Port switched back to 5060", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialRadioButton>(
            when (prefs.getString("codec", "g722")) {
                "g711" -> R.id.rbCodecG711
                "both" -> R.id.rbCodecBoth
                else -> R.id.rbCodecG722
            }
        ).isChecked = true

        // -3..+3 as a 0..6 slider, so 0 sits in the middle.
        val agentVol = findViewById<SeekBar>(R.id.sbCfgAgentVolume)
        val agentVolLabel = findViewById<TextView>(R.id.tvCfgAgentVolume)
        fun stepText(step: Int) = if (step > 0) "+$step" else step.toString()
        agentVol.progress = prefs.getInt("agent_vol_step", 0).coerceIn(-3, 3) + 3
        agentVolLabel.text = stepText(agentVol.progress - 3)
        agentVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                agentVolLabel.text = stepText(value - 3)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        switchTab("config")
    }

    /**
     * Empty the traffic list — calls and messages together, since both are
     * rows in the same log.
     *
     * Confirmed first: it is not recoverable, and the log is the only record
     * the gateway keeps of what it has handled.
     */
    private fun confirmClearRecents() {
        val count = try { CallLogStore.getEntries(this).size } catch (_: Exception) { 0 }
        if (count == 0) {
            Toast.makeText(this, "Nothing to clear", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear recents?")
            .setMessage("Removes all $count calls and messages from the list. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                // Off the UI thread: clearing rewrites the stored blob, and
                // the list is rebuilt from disk straight afterwards.
                Thread {
                    try { CallLogStore.clear(this) } catch (_: Exception) {}
                    runOnUiThread {
                        if (currentTab == "home") refreshHome()
                        Toast.makeText(this, "Recents cleared", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveConfigFromView() {
        val currentSession = CredentialStore.load(this)
        val prefsAtSave = getSharedPreferences("gateway", MODE_PRIVATE)
        if (configGatewayIdAtOpen != currentSession?.gatewayId ||
            configControlBaseUrlAtOpen != currentSession?.controlBaseUrl ||
            configServerAtOpen != prefsAtSave.getString("server", "") ||
            configUserAtOpen != prefsAtSave.getString("user", "")) {
            findViewById<EditText>(R.id.etCfgPass).text.clear()
            Toast.makeText(this,"配对身份或 SIP 设置已变化，请重新打开设置。",Toast.LENGTH_LONG).show()
            return
        }
        val server = findViewById<EditText>(R.id.etCfgServer).text.toString().trim()
        val port = findViewById<EditText>(R.id.etCfgPort).text.toString().trim().toIntOrNull() ?: 5061
        val user = findViewById<EditText>(R.id.etCfgUser).text.toString().trim()
        val pass = findViewById<EditText>(R.id.etCfgPass).text.toString().trim()
        // The lowest active SIM's number is the one everything that does not
        // know which SIM it is dealing with will use.
        val own = ownNumberFields.firstOrNull()?.second?.text?.toString()?.trim().orEmpty()
        val auto = findViewById<MaterialSwitch>(R.id.cbCfgAutoconnect).isChecked
        val backgroundEnabledBeforeSave = GatewayBackgroundRuntime.allowedRecovery(this)
        val useStun = findViewById<MaterialSwitch>(R.id.cbCfgUseStun).isChecked
        val translit = findViewById<MaterialSwitch>(R.id.cbCfgTranslit).isChecked
        val tls = true
        val srtp = true
        val agentVolStep = findViewById<SeekBar>(R.id.sbCfgAgentVolume).progress - 3
        val codec = when (findViewById<RadioGroup>(R.id.rgCfgCodec).checkedRadioButtonId) {
            R.id.rbCodecG711 -> "g711"
            R.id.rbCodecBoth -> "both"
            else -> "g722"
        }

        if (server.isEmpty() != user.isEmpty()) {
            Toast.makeText(this, "Enter both SIP server and username, or leave both empty", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("gateway", MODE_PRIVATE).edit()
            .putString("server", server)
            .putInt("port", port)
            .putString("user", user)
            .remove("pass")
            .putString("own_number", own)
            .also { ed ->
                ownNumberFields.forEach { (slot, et) ->
                    ed.putString(ownNumberKey(slot), et.text.toString().trim())
                }
            }
            .putBoolean("autoconnect", auto)
            .putBoolean("use_stun", useStun)
            .putBoolean("translit_ascii", translit)
            .putBoolean("sip_tls", tls)
            .putBoolean("srtp_enabled", srtp)
            .putString("codec", codec)
            .putInt("agent_vol_step", agentVolStep)
            .commit()
        runCatching { VoiceCredentialStore.savePassword(this,pass,configGatewayIdAtOpen) }
            .onFailure {
                findViewById<EditText>(R.id.etCfgPass).text.clear()
                Toast.makeText(this,"配对身份已变化，密码未保存。请重新打开设置。",Toast.LENGTH_LONG).show()
                return
            }
        if (auto != backgroundEnabledBeforeSave &&
            !GatewayBackgroundRuntime.setEnabled(this, auto)
        ) {
            Toast.makeText(this, "后台运行设置未能保存，请在首页检查状态", Toast.LENGTH_LONG).show()
        }
        appendLog(
            "Config saved: ${if (server.isEmpty()) "HTTPS control only" else "$user@$server:$port"} (own=${own.ifEmpty { "auto" }}, " +
                "codec=$codec, stun=${if (useStun) "on" else "off"}, " +
                "tls=${if (tls) "on" else "off"}, " +
                "srtp=${if (srtp && tls) "on" else "off"}, " +
                "ascii=${if (translit) "on" else "off"}, " +
                    "agent volume ${if (agentVolStep > 0) "+$agentVolStep" else "$agentVolStep"})"
        )
        val background = runCatching { GatewayBackgroundRuntime.snapshot(this) }.getOrNull()
        val voiceRuntimeActive = background?.let {
            it.running && it.connectionLabel.startsWith("SIP connecting", ignoreCase = true)
        } == true
        if (voiceRuntimeActive) {
            Toast.makeText(this, "已保存，正在重新连接 SIP…", Toast.LENGTH_SHORT).show()
            // Apply immediately only to a voice runtime the user explicitly
            // started. Saving legacy settings must not launch microphone FGS.
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_APPLY_CONFIG
            })
        } else {
            Toast.makeText(this, "已保存；SIP 语音未启动。请运行诊断并确认启动。", Toast.LENGTH_LONG).show()
        }
        switchTab("home")
        refreshBackgroundStatus()
    }

    /** Cut the agent's audio to the caller.  The call stays up; this only
     *  silences what the agent is sending, for when it says something wrong. */
    private fun toggleAgentMute() {
        agentMuted = !agentMuted
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MUTE_AGENT
            putExtra(GatewayService.EXTRA_MUTE_ON, agentMuted)
        })
        setCallButtonState(
            btnHomeMute,
            if (agentMuted) "取消静音" else "静音",
            if (agentMuted) R.drawable.ic_fa_volume_high else R.drawable.ic_fa_volume_xmark
        )
        appendLog(if (agentMuted) "Agent muted to caller" else "Agent unmuted")
    }

    /**
     * Show or hide the active-call card and keep the header pill in step with
     * the gateway's state.
     */
    private fun updateHomeCall(state: String, info: String) {
        if (!::homeCallCard.isInitialized) return

        // "Online" means registered, not merely running: while connecting or
        // retrying the gateway cannot take a call, and the pill should say so
        // — it is also the tap target for a manual retry.
        val online = when (state) {
            "STOPPED", "ERROR", "STARTING" -> false
            "IDLE" -> sipRegistered
            else -> true            // any call state means registration held
        }
        gatewayOnline = online
        tvHomeStatusPill.text = if (online) "● SIP 已注册" else "● SIP 未注册"
        tvHomeStatusPill.setTextColor(
            themeColor(
                if (online) MaterialR.attr.colorOnTertiaryContainer
                else MaterialR.attr.colorOnErrorContainer
            )
        )
        ViewCompat.setBackgroundTintList(
            tvHomeStatusPill,
            ColorStateList.valueOf(
                themeColor(
                    if (online) MaterialR.attr.colorTertiaryContainer
                    else MaterialR.attr.colorErrorContainer
                )
            )
        )

        // The badge says what the signalling is carried over, which is a
        // property of the configuration rather than of the current state --
        // so it is shown whenever TLS is switched on, not only while
        // registered.  It reads from prefs each time because the setting can
        // change under the Activity while it is alive.
        val cfg = getSharedPreferences("gateway", MODE_PRIVATE)
        val tlsOn = cfg.getBoolean("sip_tls", false)
        tvHomeTlsBadge.visibility = if (tlsOn) View.VISIBLE else View.GONE

        // Shown only when SRTP can actually apply.  The setting is stored
        // independently of TLS so it survives toggling the transport, but a
        // badge claiming encrypted audio on a UDP gateway would be a lie --
        // the same AND the SIP client enforces.
        tvHomeSrtpBadge.visibility =
            if (tlsOn && cfg.getBoolean("srtp_enabled", false)) View.VISIBLE else View.GONE

        if (state == "BRIDGED") {
            homeCallCard.visibility = View.VISIBLE
            val number = com.callagent.gateway.gsm.GsmCallManager.currentNumber ?: info
            tvHomeCallFrom.text = number
            val dest = getSharedPreferences("gateway", MODE_PRIVATE)
                .getString("own_number", "") ?: ""
            tvHomeCallTo.text = if (dest.isNotEmpty()) "已连接到 $dest" else "已连接"
            // Inbound is the normal direction for a gateway; a dialler-initiated
            // call is the other way round.
            tvHomeCallDirection.text =
                if (com.callagent.gateway.gsm.GsmCallManager.activeCallState ==
                    android.telecom.Call.STATE_ACTIVE && gsmCallActive) "GSM → SIP" else "GSM → SIP"
            startCallTimer()
        } else {
            homeCallCard.visibility = View.GONE
            if (!inCallOpen) {
                callStartTime = 0L
                callTimerHandler.removeCallbacks(callTimerRunnable)
            }
            // Mute is per-call; do not carry it into the next one.
            if (agentMuted) {
                agentMuted = false
                if (::btnHomeMute.isInitialized) {
                    setCallButtonState(btnHomeMute, "静音", R.drawable.ic_fa_volume_xmark)
                }
            }
            // Snoop likewise — the monitor lives with the RTP session and dies
            // with the call, but the flag was only ever cleared by the old
            // full-screen in-call view, which nothing opens any more.  So the
            // button came up saying "Stop" on the next call and the first tap
            // turned off something that was already off.
            if (monitoring) {
                monitoring = false
                updateMonitorButtons()
            }
            renderHomeTraffic()
        }
    }

    /** Repaint the home view from current state. */
    private fun refreshHome() {
        renderHomeTraffic()
        refreshNetworkInfo()
    }

    /**
     * Both network legs the gateway depends on: the modem carries the GSM call,
     * WiFi carries SIP and RTP.  A problem on either shows up as a broken call,
     * so it is worth seeing them side by side.
     */
    /**
     * Signal strength in dBm, or null when the modem has no usable reading.
     *
     * SignalStrength.getCellSignalStrengths() is API 29 and minSdk here is 26,
     * so below Q the method does not exist and calling it throws
     * NoSuchMethodError.  That is an Error, not an Exception, so the try/catch
     * around the call sites never contained it — on Android 9 this took the
     * whole Activity down in onCreate, and the app could not be opened at all.
     *
     * The pre-Q reading is getGsmSignalStrength(), which reports ASU rather
     * than dBm: 0..31 maps linearly onto -113..-51 dBm, and 99 means unknown.
     */
    @Suppress("DEPRECATION")
    private fun signalDbm(ss: android.telephony.SignalStrength?): Int? {
        if (ss == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ss.cellSignalStrengths.firstOrNull()?.dbm
        }
        val asu = ss.gsmSignalStrength
        return if (asu in 0..31) -113 + 2 * asu else null
    }

    @SuppressLint("MissingPermission")
    private fun refreshNetworkInfo() {
        if (!::tvNetMobile.isInitialized) return

        tvNetMobile.text = try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val name = tm.networkOperatorName?.ifEmpty { "No service" } ?: "No service"
            val type = when (tm.dataNetworkType) {
                android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP,
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE,
                android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN -> "—"
                else -> "?"
            }
            val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signalDbm(tm.signalStrength)
            } else null
            if (dbm != null && dbm != Int.MAX_VALUE) "$type $name ${dbm}dBm"
            else "$type $name"
        } catch (e: Exception) {
            "mobile: n/a"
        }

        tvNetWifi.text = try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            when {
                !wm.isWifiEnabled -> "WiFi off"
                // networkId is NOT a connectivity test: without location
                // permission getConnectionInfo() comes back redacted with
                // networkId = -1 even on a healthy connection, which is what
                // made this read "WiFi off" while WiFi was up.  Connectivity
                // comes from NetworkCapabilities; link metrics are not
                // location-gated and are still readable here.
                !onWifi -> "WiFi not connected"
                else -> {
                    @Suppress("DEPRECATION")
                    val raw = info?.ssid?.trim('"').orEmpty()
                    val ssid =
                        if (raw.isEmpty() || raw.contains("unknown", true)) "WiFi" else raw
                    @Suppress("DEPRECATION")
                    val freq = info?.frequency ?: 0
                    @Suppress("DEPRECATION")
                    val speed = info?.linkSpeed ?: -1
                    @Suppress("DEPRECATION")
                    val rssi = info?.rssi ?: 0
                    val band = if (freq > 4000) "5G" else "2.4G"
                    if (speed > 0) "$ssid $band ${speed}Mbps ${rssi}dBm"
                    else "$ssid $band ${rssi}dBm"
                }
            }
        } catch (e: Exception) {
            "wifi: n/a"
        }
    }

    /**
     * Everything we can learn about one of the two links.
     *
     * What the framework can answer is shown immediately; the parts that need
     * a shell — MAC addresses, cell identity — and the pings are appended as
     * they arrive, so the dialog is never blank while a ping runs.
     *
     * SSID, BSSID and cell identity are gated behind location permission for
     * an ordinary app.  This one has root instead, so it reads them from the
     * system rather than holding a permission a gateway has no business with.
     */
    /**
     * Everything known about one message, on tapping its row.
     *
     * Read from the call-log entry rather than [SmsOutbox]: the outbox is
     * pruned as soon as a message is finally reported, so for exactly the
     * messages that succeeded it holds nothing.  The outbox is still consulted
     * for one that is mid-flight, and for rows written before the log carried
     * these fields.
     */
    private fun showSmsDetails(entry: CallLogEntry) {
        val outgoing = entry.direction != "IN"
        val own = getSharedPreferences("gateway", MODE_PRIVATE)
            .getString("own_number", "").orEmpty()

        // A message still in flight has fresher paperwork in the outbox.
        val live = entry.smsId.takeIf { it.isNotEmpty() }
            ?.let { runCatching { SmsOutbox.get(this, it) }.getOrNull() }

        val parts = maxOf(entry.parts, live?.parts ?: 0)
        val status = entry.status.ifEmpty {
            when {
                live == null -> ""
                live.deliveredOk > 0 -> "delivered"
                live.sentOk > 0 -> "sent"
                live.dispatched -> "pending"
                else -> "queued"
            }
        }
        val error = entry.error.ifEmpty { live?.lastError.orEmpty() }

        fun dash(v: String) = v.ifEmpty { "\u2014" }
        fun row(label: String, value: String) =
            label.padEnd(9) + ": " + dash(value) + "\n"

        val stamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))

        val header = StringBuilder()
            .append(row("Direction", if (outgoing) "Outgoing" else "Incoming"))
            .append(row("From", if (outgoing) own else entry.number))
            .append(row("To", if (outgoing) entry.number else own))
            .append(row("SMSC", entry.smsc))
            .append(row("Date", stamp))
            .append(row("Format", entry.encoding))
            .append(
                row(
                    "Length",
                    "${entry.text.length} chars" +
                        if (parts > 0) ", $parts part${if (parts == 1) "" else "s"}" else ""
                )
            )

        // Delivery status is the outbound half of the story; an inbound
        // message has already arrived by definition, so claiming a state for
        // it would be inventing one.
        if (outgoing) {
            header.append(row("Status", status))
            if (error.isNotEmpty()) header.append(row("Error", error))
        }

        val body = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            // A blank line is enough to separate the fields from the message;
            // the fields are aligned and the body is not, so the boundary
            // reads without a rule.
            text = header.toString() + "\n" + entry.text.ifEmpty { "(no text)" }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Message detail")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("SMS detail", body.text)
                )
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLinkDetails(mobile: Boolean) {
        val body = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
            setPadding(48, 24, 48, 24)
            text = if (mobile) mobileDetailsFast() else wifiDetailsFast()
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (mobile) "Mobile network" else "WiFi")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()

        fun append(line: String) = runOnUiThread {
            if (dialog.isShowing) body.append(line)
        }

        Thread({
            if (mobile) {
                // Cell identity is location data as far as Android is
                // concerned: dumpsys blanks it even for root, so the only way
                // to read it is getAllCellInfo() with ACCESS_FINE_LOCATION.
                // Without an explicitly granted location permission it stays unavailable.
                append("\n" + describeCells())
            } else {
                val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val gw = connectivity.getLinkProperties(connectivity.activeNetwork)?.routes
                    ?.firstOrNull { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }?.gateway?.hostAddress.orEmpty()
                // MAC addresses are privacy restricted; diagnostics must not open su.
                append("Router IP    : ${gw.ifEmpty { "—" }}\n")

                append("\n— reachability —\n")
                if (gw.isNotEmpty()) append("Router  : ${pingAvg(gw)}\n")
                val server = getSharedPreferences("gateway", MODE_PRIVATE)
                    .getString("server", "") ?: ""
                if (server.isNotEmpty()) append("SIP srv : ${pingAvg(server)}  ($server)\n")
            }
        }, "link-details").start()
    }

    /** Mobile facts the framework answers instantly. */
    @SuppressLint("MissingPermission")
    private fun mobileDetailsFast(): String = buildString {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            appendLine("Operator     : ${tm.networkOperatorName.ifEmpty { "—" }}")
            appendLine("MCC/MNC      : ${tm.networkOperator.ifEmpty { "—" }}")
            appendLine("Country      : ${tm.networkCountryIso.uppercase().ifEmpty { "—" }}")
            appendLine("SIM operator : ${tm.simOperatorName.ifEmpty { "—" }}")
            appendLine("SIM state    : ${simStateName(tm.simState)}")
            appendLine("Roaming      : ${if (tm.isNetworkRoaming) "yes" else "no"}")
            appendLine("Data network : ${networkTypeName(tm.dataNetworkType)}")
            appendLine("Voice network: ${networkTypeName(tm.voiceNetworkType)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tm.signalStrength?.cellSignalStrengths?.forEachIndexed { i, c ->
                    appendLine(
                        "Signal[$i]    : ${c.dbm} dBm, level ${c.level}/4 " +
                            "(${c.javaClass.simpleName.removePrefix("CellSignalStrength")})"
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signalDbm(tm.signalStrength)?.let { appendLine("Signal       : $it dBm") }
            }
        } catch (e: Exception) {
            appendLine("telephony: ${e.message}")
        }
    }

    /** WiFi facts the framework answers instantly. */
    private fun wifiDetailsFast(): String = buildString {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE)
                as android.net.wifi.WifiManager
            appendLine("Enabled      : ${if (wm.isWifiEnabled) "yes" else "no"}")
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            // Real SSID/BSSID: these read "<unknown ssid>" / 02:00:00:00:00:00
            // without location, which the Magisk module now grants on boot.
            @Suppress("DEPRECATION")
            val ssidRaw = info?.ssid?.trim('"').orEmpty()
            appendLine(
                "SSID         : " +
                    if (ssidRaw.isEmpty() || ssidRaw.contains("unknown", true)) "—" else ssidRaw
            )
            @Suppress("DEPRECATION")
            val bssid = info?.bssid.orEmpty()
            appendLine(
                "BSSID (AP)   : " +
                    if (bssid.isEmpty() || bssid.startsWith("02:00:00")) "—" else bssid
            )
            appendLine("Security     : ${wifiSecurityName(info)}")
            @Suppress("DEPRECATION")
            val freq = info?.frequency ?: 0
            @Suppress("DEPRECATION")
            appendLine("Link speed   : ${info?.linkSpeed ?: -1} Mbps")
            appendLine("Frequency    : $freq MHz (${if (freq > 4000) "5 GHz" else "2.4 GHz"})")
            @Suppress("DEPRECATION")
            appendLine("RSSI         : ${info?.rssi ?: 0} dBm")

            // IP and DNS come from LinkProperties: no root, no permission, and
            // it reports what the network actually resolved with.
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            val ip = lp?.linkAddresses?.firstOrNull { it.address.hostAddress?.contains('.') == true }
            appendLine("IP address   : ${ip?.address?.hostAddress ?: "—"}")
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(", ")
            appendLine("DNS          : ${dns?.ifEmpty { null } ?: "—"}")
        } catch (e: Exception) {
            appendLine("wifi: ${e.message}")
        }
    }

    /**
     * Serving cell and neighbours.  Can take a moment — the radio is polled —
     * so it is called off the main thread.
     */
    @SuppressLint("MissingPermission")
    private fun describeCells(): String {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "needs location permission (granted by the Magisk module on boot)"

        return try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val cells = tm.allCellInfo
            if (cells.isNullOrEmpty()) return "none reported"
            cells.take(6).joinToString("\n") { describeCell(it) }
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /**
     * One cell as aligned `name : value` lines, matching the rest of the
     * dialog.  Int.MAX_VALUE is the API's "unknown", not a real reading, so it
     * is shown as a dash rather than a nine-digit number.
     */
    @Suppress("DEPRECATION")
    private fun describeCell(info: android.telephony.CellInfo): String = buildString {
        fun row(name: String, value: String) = appendLine(name.padEnd(13) + ": " + value)
        fun num(x: Int) = if (x == Int.MAX_VALUE) "—" else x.toString()
        fun sig(s: android.telephony.CellSignalStrength) = "${s.dbm} dBm (${s.level}/4)"

        val role = if (info.isRegistered) "serving cell" else "neighbour"
        // Subject-less `when` so the CellInfoNr branch can carry an SDK guard.
        // The class is API 29, and an `is` test against a class the platform
        // does not have is itself the hazard — it resolves the type before any
        // guard inside the branch could run.
        when {
            info is android.telephony.CellInfoLte -> {
                val id = info.cellIdentity
                row("Type", "LTE ($role)")
                row("Cell ID", num(id.ci))
                row("PCI", num(id.pci))
                row("TAC", num(id.tac))
                row("EARFCN", num(id.earfcn))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                    id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                        ?.let { row("Carrier", it) }
                } else {
                    row("MCC/MNC", "${num(id.mcc)}/${num(id.mnc)}")
                }
                row("Signal", sig(info.cellSignalStrength))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                info is android.telephony.CellInfoNr -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityNr
                row("Type", "5G NR ($role)")
                row("NCI", id?.nci?.toString() ?: "—")
                row("PCI", num(id?.pci ?: Int.MAX_VALUE))
                row("TAC", num(id?.tac ?: Int.MAX_VALUE))
                row("NRARFCN", num(id?.nrarfcn ?: Int.MAX_VALUE))
                row("MCC/MNC", "${id?.mccString ?: "—"}/${id?.mncString ?: "—"}")
                row("Signal", sig(info.cellSignalStrength))
            }
            info is android.telephony.CellInfoWcdma -> {
                val id = info.cellIdentity
                row("Type", "WCDMA ($role)")
                row("Cell ID", num(id.cid))
                row("LAC", num(id.lac))
                row("PSC", num(id.psc))
                row("UARFCN", num(id.uarfcn))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                } else {
                    row("MCC/MNC", "${num(id.mcc)}/${num(id.mnc)}")
                }
                row("Signal", sig(info.cellSignalStrength))
            }
            info is android.telephony.CellInfoGsm -> {
                val id = info.cellIdentity
                row("Type", "GSM ($role)")
                row("Cell ID", num(id.cid))
                row("LAC", num(id.lac))
                row("ARFCN", num(id.arfcn))
                row("BSIC", num(id.bsic))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                } else {
                    row("MCC/MNC", "${num(id.mcc)}/${num(id.mnc)}")
                }
                row("Signal", sig(info.cellSignalStrength))
            }
            else -> {
                row("Type", "${info.javaClass.simpleName.removePrefix("CellInfo")} ($role)")
                // The CellInfo base class only grew getCellSignalStrength() in
                // API 30; every branch above reads it off its own subclass,
                // which has had it since 17.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    row("Signal", sig(info.cellSignalStrength))
                }
            }
        }
    }

    /**
     * The link's security type — the cipher in use, not the passphrase.
     * The stored key is deliberately not shown: it is the network's actual
     * credential and a diagnostics panel is the wrong place for it.
     */
    private fun wifiSecurityName(info: android.net.wifi.WifiInfo?): String {
        if (info == null) return "—"
        // getCurrentSecurityType() is API 31.  The catch below does not stand in
        // for a version check: a missing method raises NoSuchMethodError, which
        // is an Error rather than an Exception, so it would pass straight
        // through and take the dialog down.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "—"
        return try {
            when (info.currentSecurityType) {
                android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN -> "open (none)"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WEP -> "WEP"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2-PSK"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP -> "WPA-EAP"
                android.net.wifi.WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE"
                android.net.wifi.WifiInfo.SECURITY_TYPE_OWE -> "OWE (enhanced open)"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT ->
                    "WPA3-Enterprise 192-bit"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "—"
        }
    }

    /** Average round-trip to a host, or why it failed. */
    private fun pingAvg(host: String): String {
        val out = try {
            val process = ProcessBuilder("/system/bin/ping", "-c", "3", "-W", "2", "--", host)
                .redirectErrorStream(true).start()
            if (!process.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "timed out"
            }
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return "unavailable"
        }
        val avg = Regex("= [0-9.]+/([0-9.]+)/").find(out)?.groupValues?.getOrNull(1)
        val loss = Regex("([0-9]+)% packet loss").find(out)?.groupValues?.getOrNull(1)
        return when {
            avg != null -> "$avg ms avg" + (loss?.let { ", $it% loss" } ?: "")
            loss == "100" -> "no reply (100% loss)"
            else -> out.lines().firstOrNull { it.isNotBlank() } ?: "unreachable"
        }
    }

    private fun simStateName(state: Int): String = when (state) {
        android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
        android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
        android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
        else -> "unknown($state)"
    }

    private fun setCallFilter(filter: String) {
        callFilter = filter
        val on = themeColor(MaterialR.attr.colorPrimaryContainer)
        val onText = themeColor(MaterialR.attr.colorOnPrimaryContainer)
        val off = themeColor(MaterialR.attr.colorSurfaceContainerHigh)
        val offText = themeColor(MaterialR.attr.colorOnSurface)
        for ((btn, name) in listOf(
            btnFilterAll to "all", btnFilterIncoming to "in", btnFilterOutgoing to "out"
        )) {
            val active = name == filter
            btn.backgroundTintList = ColorStateList.valueOf(if (active) on else off)
            btn.setTextColor(if (active) onText else offText)
        }
        renderHomeTraffic()
    }

    /** Recent calls on the home view, newest first. */
    private fun renderHomeTraffic() {
        val all = try { CallLogStore.getEntries(this) } catch (_: Exception) { emptyList() }
        val entries = when (callFilter) {
            "in" -> all.filter { it.direction == "IN" }
            "out" -> all.filter { it.direction != "IN" }
            else -> all
        }
        homeTrafficList.removeAllViews()
        tvHomeTrafficEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

        for (e in entries.take(30)) {
            val row = layoutInflater.inflate(R.layout.item_call_row, homeTrafficList, false)
            val incoming = e.direction == "IN"
            row.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(
                if (incoming) R.drawable.ic_call_incoming else R.drawable.ic_call_outgoing
            )
            val sms = e.type == CallLogStore.TYPE_SMS
            row.findViewById<TextView>(R.id.tvRowNumber).text =
                e.number.ifEmpty { if (sms) "SMS · content cleared" else "Unknown number" }
            // The direction arrow and the card are the same as a call's — a
            // message is the same kind of traffic through the same gateway.
            // What changes is the second line, which carries the message
            // itself, and the slot where a call shows its duration.
            row.findViewById<TextView>(R.id.tvRowSub).text = when {
                sms -> e.text.replace('\n', ' ').trim().ifEmpty { "SMS details cleared after server sync" }
                e.durationSec > 0 -> if (incoming) "GSM → SIP" else "SIP → GSM"
                else -> "Not connected"
            }
            val dur = row.findViewById<TextView>(R.id.tvRowDuration)
            when {
                sms -> {
                    dur.text = "SMS"
                    dur.setTextColor(themeColor(MaterialR.attr.colorTertiary))
                }
                e.durationSec > 0 -> {
                    dur.text = String.format("%02d:%02d", e.durationSec / 60, e.durationSec % 60)
                    dur.setTextColor(themeColor(AppCompatR.attr.colorPrimary))
                }
                else -> {
                    dur.text = "—"
                    dur.setTextColor(themeColor(AppCompatR.attr.colorError))
                }
            }
            val d = java.util.Date(e.timestamp)
            val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(d) == today
            row.findViewById<TextView>(R.id.tvRowTime).text =
                if (sameDay) hhmm.format(d) else "Earlier"
            if (sms) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { showSmsDetails(e) }
            }
            homeTrafficList.addView(row)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inCallOpen) {
            return // must use END CALL
        } else if (currentTab == "logs") {
            switchTab(logsReturnTab)
        } else if (currentTab != "home") {
            switchTab("home")
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (SmsProviderRecovery.status(this).configured) scanSmsRecovery()
        val filter = IntentFilter().apply {
            addAction(GatewayService.STATUS_ACTION)
            addAction(GatewayService.LOG_ACTION)
        }
        // NOT_EXPORTED: the service sends these with setPackage(), so nothing
        // outside the app has any business delivering them — exported, any
        // installed app could feed the UI fabricated status and log lines.
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Replay any log messages buffered while activity was paused
        // Re-render from the service's buffer rather than consuming it: the
        // lines are already stamped with when each event happened, and
        // replacing the view means a recreated activity shows the full recent
        // history instead of whatever it happened to witness.
        val buffered = GatewayService.logSnapshot()
        if (buffered.isNotEmpty()) {
            tvLog.text = buffered.joinToString("\n", postfix = "\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        if (onlineSince > 0) {
            uptimeHandler.removeCallbacks(uptimeRunnable)
            uptimeRunnable.run()
        }

        // The old full-screen in-call view is superseded by the live call
        // card on the home view; it is no longer opened automatically.

        if (inCallOpen) {
            if (com.callagent.gateway.gsm.GsmCallManager.activeCall == null &&
                System.currentTimeMillis() - inCallOpenTime > 2000) {
                closeInCallScreen()
            } else {
                callTimerHandler.removeCallbacks(callTimerRunnable)
                if (callStartTime > 0) callTimerRunnable.run()
                callTimerHandler.removeCallbacks(gsmPollRunnable)
                callTimerHandler.postDelayed(gsmPollRunnable, 500)
            }
        }

        // Refresh the traffic list if it is the visible one.  This used to be
        // guarded on the old Calls tab, so after that tab went the home list
        // stopped being refreshed here at all and showed a stale view until
        // something else rebuilt it.
        if (currentTab == "home") {
            refreshHome()
        }

        // Re-apply the chip highlight: the visual state is set in code, so it
        // has to be restored whenever the view comes back.
        if (::btnFilterAll.isInitialized) setCallFilter(callFilter)

        netHandler.removeCallbacks(netRunnable)
        netRunnable.run()
        refreshBackgroundStatus()
        refreshSimSummary()

        // Ask the service where it is.  Status is only pushed on change, so
        // opening the app onto an already-running gateway would otherwise show
        // "Offline" until something happened.
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_STATUS
        })
    }

    override fun onPause() {
        super.onPause()
        uptimeHandler.removeCallbacks(uptimeRunnable)
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        netHandler.removeCallbacks(netRunnable)
        unregisterReceiver(statusReceiver)
    }

    // ── Config Dialog ────────────────────────────────────

    // ── Info Dialog ─────────────────────────────────────

    @SuppressLint("MissingPermission")
    // ── Gateway Support Checks ─────────────────────────

    private fun openGatewayDiagnostics() {
        val density = resources.displayMetrics.density
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        content.addView(TextView(this).apply {
            text = "此检查只验证本机权限、音频采集能力和系统环境，不会拨打真实电话，也不代表完整通话链路已验证。检查通过后还需再次确认，才会启动 SIP 语音服务。"
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            textSize = 14f
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        content.addView(results)
        val scroll = ScrollView(this).apply {
            addView(content)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.configuration.screenHeightDp.coerceAtMost(760) * 0.55f * density).toInt()
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("SIP 本机诊断")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .setPositiveButton("运行诊断", null)
            .create()
        dialog.setOnShowListener {
            val action = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            var checkComplete = false
            var readyToStart = false
            var checking = false
            action.setOnClickListener {
                if (checking) return@setOnClickListener
                if (checkComplete && readyToStart) {
                    if (com.callagent.gateway.gsm.GsmCallManager.activeCall != null || callLive) {
                        Toast.makeText(this, "通话进行中，结束后再启动 SIP 语音", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (startGateway()) {
                        dialog.dismiss()
                        refreshBackgroundStatus()
                    }
                    return@setOnClickListener
                }

                val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (telecom.defaultDialerPackage != packageName) {
                    requestDefaultDialerRole()
                    Toast.makeText(this, "语音需要默认电话角色；设置后再次运行诊断", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // READ_CALL_LOG is restricted for ordinary installations until
                // the phone role is held; request that role before voice permissions.
                if (requestVoicePermissions()) {
                    Toast.makeText(this, "授权后再次点击运行诊断；短信模式无需这些语音权限", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                results.removeAllViews()
                checking = true
                checkComplete = false
                readyToStart = false
                action.isEnabled = false
                action.text = "正在检查…"
                runGatewayChecks(results) { ready ->
                    if (!dialog.isShowing) return@runGatewayChecks
                    checking = false
                    checkComplete = true
                    readyToStart = ready
                    action.isEnabled = true
                    action.text = if (ready) "确认启动 SIP 语音" else "重新运行诊断"
                }
            }
        }
        dialog.show()
    }

    private fun runGatewayChecks(container: LinearLayout, onDone: (Boolean) -> Unit) {
        val dp = resources.displayMetrics.density
        val greenColor = themeColor(AppCompatR.attr.colorPrimary)
        val redColor = themeColor(AppCompatR.attr.colorError)
        val grayColor = themeColor(MaterialR.attr.colorOnSurfaceVariant)

        fun addSectionHeader(title: String) {
            val tv = TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(themeColor(AppCompatR.attr.colorPrimary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
            }
            container.addView(tv)
        }

        fun addResultRow(label: String, passed: Boolean, detail: String = "") {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            val icon = TextView(this).apply {
                text = if (passed) "\u2713" else "\u2717"
                textSize = 14f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tvLabel = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(icon)
            row.addView(tvLabel)
            if (detail.isNotEmpty()) {
                val tvDetail = TextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(grayColor)
                }
                row.addView(tvDetail)
            }
            container.addView(row)
        }

        Thread {
            data class CheckResult(val label: String, val passed: Boolean, val detail: String = "")
            val results = mutableListOf<CheckResult>()

            val hasRecordAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val hasPhoneState = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val hasAnswerCalls = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            val hasCallPhone = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val hasCaptureOutput = ContextCompat.checkSelfPermission(
                this, "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED

            results.add(CheckResult("RECORD_AUDIO", hasRecordAudio))
            results.add(CheckResult("CAPTURE_AUDIO_OUTPUT", hasCaptureOutput, if (hasCaptureOutput) "Magisk" else "needs Magisk"))
            results.add(CheckResult("ANSWER_PHONE_CALLS", hasAnswerCalls))
            results.add(CheckResult("CALL_PHONE", hasCallPhone))
            results.add(CheckResult("READ_PHONE_STATE", hasPhoneState))

            val telecomMgr = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefaultDialer = packageName == telecomMgr.defaultDialerPackage
            results.add(CheckResult("Default Dialer", isDefaultDialer, if (isDefaultDialer) "" else "required for InCallService"))

            data class SourceTest(val source: Int, val name: String, val rate: Int)
            val sources = listOf(
                SourceTest(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_UPLINK, "VOICE_UPLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000),
                SourceTest(MediaRecorder.AudioSource.MIC, "MIC", 8000)
            )

            val sourceResults = mutableListOf<CheckResult>()
            for (src in sources) {
                var ok = false
                var detail = ""
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        src.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf > 0) {
                        val rec = AudioRecord(
                            src.source, src.rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf.coerceAtLeast(4096)
                        )
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            try {
                                rec.startRecording()
                                val buf = ByteArray(320)
                                val read = rec.read(buf, 0, buf.size)
                                ok = read > 0
                                if (!ok) detail = "read=$read"
                                rec.stop()
                            } catch (e: Exception) {
                                detail = e.message?.take(30) ?: "start failed"
                            }
                        } else {
                            detail = "init failed"
                        }
                        rec.release()
                    } else {
                        detail = "invalid buffer"
                    }
                } catch (e: Exception) {
                    detail = e.message?.take(30) ?: "error"
                }
                sourceResults.add(CheckResult(src.name, ok, detail))
            }

            val aecAvail = AcousticEchoCanceler.isAvailable()
            val nsAvail = NoiseSuppressor.isAvailable()

            data class PropCheck(val prop: String, val expected: String, val label: String)
            val propChecks = listOf(
                PropCheck("voice.record.conc.disabled", "false", "Concurrent recording"),
                PropCheck("voice.playback.conc.disabled", "false", "Concurrent playback"),
                PropCheck("voice.voip.conc.disabled", "false", "Concurrent VoIP")
            )
            val propResults = mutableListOf<CheckResult>()
            for (pc in propChecks) {
                val value = try {
                    @Suppress("PrivateApi")
                    val cls = Class.forName("android.os.SystemProperties")
                    val get = cls.getMethod("get", String::class.java, String::class.java)
                    get.invoke(null, pc.prop, "") as String
                } catch (_: Exception) { "" }
                val ok = value == pc.expected
                propResults.add(CheckResult(pc.label, ok, if (value.isNotEmpty()) "$value" else "not set"))
            }

            val hasRoot = try {
                // Modern Magisk doesn't place su at fixed paths — try executing it.
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val exitCode = proc.waitFor()
                proc.destroy()
                exitCode == 0
            } catch (_: Exception) {
                // Fallback: check legacy paths
                try {
                    java.io.File("/system/bin/su").exists() ||
                        java.io.File("/system/xbin/su").exists() ||
                        java.io.File("/sbin/su").exists()
                } catch (_: Exception) { false }
            }

            val hasUsableSource = sourceResults.any { it.passed }
            val hasDownlink = sourceResults.firstOrNull { it.label == "VOICE_DOWNLINK" }?.passed == true

            runOnUiThread {
                addSectionHeader("权限")
                for (r in results) addResultRow(
                    when (r.label) {
                        "RECORD_AUDIO" -> "录音权限"
                        "CAPTURE_AUDIO_OUTPUT" -> "系统通话音频采集"
                        "ANSWER_PHONE_CALLS" -> "接听电话权限"
                        "CALL_PHONE" -> "拨打电话权限"
                        "READ_PHONE_STATE" -> "电话状态读取权限"
                        "Default Dialer" -> "默认拨号器"
                        else -> r.label
                    }, r.passed, r.detail
                )

                addSectionHeader("音频采集源（仅本机短时探测）")
                for (r in sourceResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("音频处理")
                addResultRow("回声消除", aecAvail)
                addResultRow("降噪", nsAvail)

                addSectionHeader("系统属性")
                for (r in propResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("系统环境")
                addResultRow("Magisk root", hasRoot, if (hasRoot) "" else "需要 Magisk")

                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).apply { topMargin = (8 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
                    setBackgroundColor(themeColor(MaterialR.attr.colorOutlineVariant))
                }
                container.addView(divider)

                val gatewayReady = hasRecordAudio && isDefaultDialer && hasUsableSource && hasCaptureOutput
                val verdict = TextView(this).apply {
                    text = if (gatewayReady) {
                        val src = if (hasDownlink) "VOICE_DOWNLINK" else
                            sourceResults.firstOrNull { it.passed }?.label ?: "?"
                        "\u2713 本机检查通过（采集源：$src），可启动 SIP 语音服务；尚未验证完整通话链路"
                    } else {
                        val missing = mutableListOf<String>()
                        if (!hasRecordAudio) missing.add("录音权限")
                        if (!hasCaptureOutput) missing.add("系统通话音频采集权限")
                        if (!isDefaultDialer) missing.add("默认拨号器角色")
                        if (!hasUsableSource) missing.add("可用音频采集源")
                        "\u2717 暂不能启动语音服务：缺少 ${missing.joinToString("、")}"
                    }
                    textSize = 13f
                    setTextColor(if (gatewayReady) greenColor else redColor)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                container.addView(verdict)

                onDone(gatewayReady)
            }
        }.start()
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> "Unknown"
    }

    // ── Call Log (Calls Tab) ─────────────────────────────

    /** Pre-load both IN and OUT lists off the main thread so tab switching is instant */
    /** Show the already-cached list for the current filter — runs on UI thread, no I/O */
    // ── Dialler ──────────────────────────────────────────

    // ── In-Call Screen ───────────────────────────────────

    private var inCallCloseScheduled = false

    /** Show "Call ended" briefly then close the in-call screen */
    private fun scheduleInCallClose() {
        if (!inCallOpen || inCallCloseScheduled) return
        inCallCloseScheduled = true
        tvInCallStatus.text = "通话已结束"
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed({ closeInCallScreen() }, 1500)
    }

    /** Listen in on the live call through the speaker.  The microphone is not
     *  touched — it stays muted, so the room is never audible to either side. */
    private fun toggleMonitor() {
        monitoring = !monitoring
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MONITOR
            putExtra(GatewayService.EXTRA_MONITOR_ON, monitoring)
        })
        updateMonitorButtons()
        appendLog(if (monitoring) "Snoop on — both sides on the speaker" else "Snoop off")
    }

    private fun updateMonitorButtons() {
        if (::btnInCallMonitor.isInitialized) {
            btnInCallMonitor.text = if (monitoring) "停止监听" else "开始监听"
        }
        if (::btnHomeSnoop.isInitialized) {
            setCallButtonState(
                btnHomeSnoop,
                if (monitoring) "停止" else "监听",
                if (monitoring) R.drawable.ic_fa_circle_stop else R.drawable.ic_fa_headphones
            )
        }
    }

    private fun openInCallScreen(number: String) {
        inCallOpen = true
        inCallOpenTime = System.currentTimeMillis()
        inCallCloseScheduled = false
        callStartTime = 0L
        lastGsmPollState = -1
        tvInCallNumber.text = number
        tvInCallStatus.text = "呼叫中"
        tvInCallTimer.visibility = View.GONE
        viewBeforeInCall = currentTab
        // Hide tabs, show in-call overlay
        tabbedRoot.visibility = View.GONE
        inCallView.visibility = View.VISIBLE
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed(gsmPollRunnable, 500)
    }

    private fun closeInCallScreen() {
        if (!inCallOpen) return
        inCallOpen = false
        // The monitor lives with the RTP session, which ends with the call, so
        // only the button label needs resetting for the next one.
        monitoring = false
        updateMonitorButtons()
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callStartTime = 0L
        lastGsmPollState = -1
        inCallView.visibility = View.GONE
        tabbedRoot.visibility = View.VISIBLE
        gsmCallActive = false
        // Refresh the traffic list on returning from a call, so the call that
        // just ended is there.
        if (currentTab == "home") {
            refreshHome()
        }
    }

    private fun endCallFromInCallScreen() {
        val call = com.callagent.gateway.gsm.GsmCallManager.activeCall
        if (call != null) {
            tvInCallStatus.text = "正在结束"
            com.callagent.gateway.gsm.GsmCallManager.hangupCall()
        } else {
            closeInCallScreen()
        }
    }

    // ── Gateway Control ──────────────────────────────────

    private fun startGateway(): Boolean {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val server = prefs.getString("server", "") ?: ""
        val port = prefs.getInt("port", 5061)
        val user = prefs.getString("user", "") ?: ""
        val pass = VoiceCredentialStore.password(this,server,user)

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "请先保存 SIP 服务器和用户名", Toast.LENGTH_LONG).show()
            openConfigView()
            return false
        }

        if (!GatewayService.start(this, server, port, user, pass)) {
            Toast.makeText(this, "Android 未能启动 SIP 语音服务，请查看后台状态和运行日志", Toast.LENGTH_LONG).show()
            refreshBackgroundStatus()
            return false
        }

        running = true
        appendLog("User confirmed SIP voice start after device diagnostics: $user@$server:$port")
        Toast.makeText(this, "正在启动 SIP 语音服务", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun stopGateway() {
        GatewayService.stop(this)
        running = false
    }

    private fun updateStatus(state: String, info: String) {
        // SNOOP only does anything while audio is flowing, so it follows the
        // bridge state rather than being permanently tappable.
        callLive = state == "BRIDGED"
        updateMonitorButtons()
        updateHomeCall(state, info)

    }

    private fun appendLog(msg: String) {
        appendLogRaw("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $msg")
    }

    /** Append a line that already carries its own timestamp. */
    private fun appendLogRaw(line: String) {
        runOnUiThread {
            tvLog.append("$line\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        tvLog.text = ""
        GatewayService.clearLogBuffer()
        Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
    }

    private fun copyLog() {
        val logText = tvLog.text.toString()
        if (logText.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("callagent log", logText))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ── Helpers ──────────────────────────────────────────

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatDurationCompact(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        check(theme.resolveAttribute(attr, value, true)) { "Theme does not define color attr $attr" }
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    // ── Permissions ─────────────────────────────────────

    private fun requestSmsPermissions() {
        requestMissingPermissions(listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        ), REQ_SMS_PERMS)
    }

    /** Called only from the visible, explicitly selected voice diagnostic. */
    private fun requestVoicePermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        return requestMissingPermissions(permissions, REQ_VOICE_PERMS)
    }

    private fun requestMissingPermissions(permissions: List<String>, requestCode: Int): Boolean {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) return false
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), requestCode)
        return true
    }

    private fun requestDefaultDialerRole() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (packageName == tm.defaultDialerPackage) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !rm.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_DEFAULT_DIALER)
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivityForResult(intent, REQ_DEFAULT_DIALER)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEFAULT_DIALER) {
            if (resultCode == RESULT_OK) {
                appendLog("Set as default phone app")
            } else {
                appendLog("WARN: Not set as default phone app — GSM call handling disabled")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == GatewayBackgroundRuntime.NOTIFICATION_PERMISSION_REQUEST_CODE) {
            refreshBackgroundStatus()
        } else if (requestCode == REQ_SMS_RECOVERY) {
            recoveryPermissionRequestInFlight = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestRecoveryPermissionOrConfigure()
            else {
                clearPendingSmsRecovery()
                refreshSmsRecoveryStatus()
            }
        } else if (requestCode == REQ_SMS_PERMS || requestCode == REQ_VOICE_PERMS) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                appendLog("WARN: Denied permissions: ${denied.joinToString()}")
                val affected = denied.mapNotNull { permission ->
                    when (permission) {
                        "SEND_SMS" -> "发送短信"
                        "RECEIVE_SMS" -> "接收短信"
                        "RECORD_AUDIO" -> "通话音频"
                        "READ_PHONE_STATE" -> "SIM 与通话状态读取"
                        "CALL_PHONE" -> "外拨电话"
                        "ANSWER_PHONE_CALLS" -> "接听电话"
                        "READ_CALL_LOG" -> "通话记录"
                        "READ_PHONE_NUMBERS" -> "读取本机号码"
                        "POST_NOTIFICATIONS" -> "后台运行通知"
                        else -> null
                    }
                }
                tvPermissionNotice.text = "已拒绝：${affected.joinToString("、")}。相关功能会受限；可到系统设置的应用权限中更改。"
            } else {
                tvPermissionNotice.text = "短信模式只需 SIM 与短信权限，无需 root、录音或默认电话角色。语音权限在显式运行 SIP 诊断时申请。"
            }
            refreshSimSummary()
            refreshBackgroundStatus()
        }
    }

    companion object {
        private const val STATE_CURRENT_TAB = "ui.current_tab"
        private const val STATE_LOGS_RETURN_TAB = "ui.logs_return_tab"
        private const val STATE_CONFIG_OPEN = "ui.config_open"
        private const val STATE_CONFIG_GATEWAY_ID = "ui.config_gateway_id"
        private const val STATE_CONFIG_CONTROL_BASE_URL = "ui.config_control_base_url"
        private const val STATE_CONFIG_SERVER_AT_OPEN = "ui.config_server_at_open"
        private const val STATE_CONFIG_USER_AT_OPEN = "ui.config_user_at_open"
        private const val STATE_CONTROL_URL = "ui.control_url"
        private const val STATE_CONTROL_DEVICE_NAME = "ui.control_device_name"
        private const val STATE_CFG_SERVER = "ui.cfg_server"
        private const val STATE_CFG_PORT = "ui.cfg_port"
        private const val STATE_CFG_USER = "ui.cfg_user"
        private const val STATE_OWN_NUMBER_SLOTS = "ui.own_number_slots"
        private const val STATE_OWN_NUMBER_VALUES = "ui.own_number_values"
        private const val STATE_CFG_AUTOCONNECT = "ui.cfg_autoconnect"
        private const val STATE_CFG_STUN = "ui.cfg_stun"
        private const val STATE_CFG_TRANSLIT = "ui.cfg_translit"
        private const val STATE_CFG_CODEC = "ui.cfg_codec"
        private const val STATE_CFG_AGENT_VOLUME = "ui.cfg_agent_volume"
        private const val STATE_CFG_SCROLL_Y = "ui.cfg_scroll_y"
        private const val STATE_RECOVERY_MODE = "ui.recovery_mode"
        private const val STATE_RECOVERY_GATEWAY_ID = "ui.recovery_gateway_id"
        private const val STATE_RECOVERY_CONTROL_BASE_URL = "ui.recovery_control_base_url"
        private const val STATE_RECOVERY_PERMISSION_IN_FLIGHT = "ui.recovery_permission_in_flight"
        private const val REQ_SMS_PERMS = 100
        private const val REQ_VOICE_PERMS = 102
        private const val REQ_SMS_RECOVERY = 103
        private const val REQ_DEFAULT_DIALER = 101
        private const val MAX_CALL_LOG = 20
    }
}
