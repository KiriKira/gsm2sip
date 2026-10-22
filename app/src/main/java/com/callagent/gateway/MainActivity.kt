package com.callagent.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.callagent.gateway.background.BackgroundStatus
import com.callagent.gateway.background.GatewayBackgroundRuntime
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.mesh.JoinLink
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.net.ControlApiException
import com.callagent.gateway.net.MappingProposal
import com.callagent.gateway.net.ServerMapping
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.service.GatewayService
import com.callagent.gateway.sim.SimRegistry
import com.callagent.gateway.sms.SmsOutbox
import com.callagent.gateway.sms.SmsProviderRecovery
import com.callagent.gateway.ui.GatewaySetupCard
import com.callagent.gateway.ui.GatewaySetupChecklist
import com.callagent.gateway.ui.GatewayUi
import com.callagent.gateway.ui.MeshNodesSection
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.google.android.material.R as MaterialR

/**
 * Homura's only screen: three pages behind a bottom navigation bar (status,
 * call and SMS records, settings) plus the run log, built in code in the
 * same style as the Madoka host app.
 *
 * Pages are built once. Status broadcasts and timers refresh only the parts
 * that change, so a refresh never takes focus away from a field being edited.
 */
class MainActivity : AppCompatActivity() {

    // ── Page structure ───────────────────────────────────
    private lateinit var rootWindow: LinearLayout
    private lateinit var tabHome: ScrollView
    private lateinit var tabTraffic: ScrollView
    private lateinit var tabConfig: ScrollView
    private lateinit var tabLogs: LinearLayout
    private lateinit var bottomNavigation: BottomNavigationView
    private var currentTab = ""
    private var logsReturnTab = "config"

    // ── Status page ──────────────────────────────────────
    private lateinit var statusPill: TextView
    private lateinit var homeCallCard: MaterialCardView
    private lateinit var callCardState: TextView
    private lateinit var callCardTimer: TextView
    private lateinit var callCardNumber: TextView
    private lateinit var callCardDetail: TextView
    private lateinit var btnHomeMute: MaterialButton
    private lateinit var btnHomeSnoop: MaterialButton
    private lateinit var btnHomeEnd: MaterialButton
    private lateinit var setupCard: GatewaySetupCard
    private lateinit var homeStatusTiles: LinearLayout
    private lateinit var netMobileSummary: TextView
    private lateinit var netWifiSummary: TextView
    private lateinit var simSummary: TextView
    private var permissionNotice: String? = null

    // ── Records page ─────────────────────────────────────
    private lateinit var trafficList: LinearLayout
    /** "all" | "in" | "out" — which calls the records list shows.  A missed call
     *  is an inbound one that never carried audio, so it belongs under
     *  Incoming rather than in a category of its own. */
    private var callFilter = "all"

    // ── Settings page ────────────────────────────────────
    private lateinit var pairedSummary: LinearLayout
    private lateinit var pairingForm: LinearLayout
    private lateinit var controlUrl: TextInputEditText
    private lateinit var controlDeviceName: TextInputEditText
    private lateinit var controlPairingCode: TextInputEditText
    private lateinit var controlPairButton: MaterialButton
    private lateinit var controlStatus: TextView
    private var pairingFormOpen = false
    private lateinit var meshNodesSection: MeshNodesSection
    private lateinit var simProposeTile: View
    private lateinit var simStatus: TextView
    private lateinit var simBindings: LinearLayout
    private lateinit var simConfirm: MaterialButton
    private lateinit var voiceTileContainer: LinearLayout
    private lateinit var smsSettings: LinearLayout
    private lateinit var backgroundSettings: LinearLayout

    // ── Logs page ────────────────────────────────────────
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    // ── Gateway state from the service ───────────────────
    private var bridgeState = "IDLE"
    /** Whether server call control is ready, as reported by the service. */
    private var callControlReady = false
    /** Whether the user started the voice runtime and it is still up. */
    private var voiceRunning = false
    private var callIncoming = true
    private var agentMuted = false
    private var monitoring = false
    /** When the service says the bridge came up.  Authoritative: the activity
     *  can be started, stopped and restarted several times during one call. */
    private var serviceCallStart = 0L
    private var callStartTime = 0L

    // ── SIM binding and SMS recovery ─────────────────────
    private val selectedSimBindings = mutableMapOf<String, SimRegistry.SimSubscription>()
    private var pendingMappingProposal: MappingProposal? = null
    private var simProposalSubscriptions: List<SimRegistry.SimSubscription> = emptyList()
    private var controlBusy = false
    private var setupActiveSimCount = 0
    private var setupDetectedSimCount = 0
    private var pendingRecoveryMode: SmsProviderRecovery.HistoryMode? = null
    private var pendingRecoveryGatewayId: String? = null
    private var pendingRecoveryControlBaseUrl: String? = null
    private var recoveryPermissionRequestInFlight = false
    private val recoveryBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Editor restore across recreation ─────────────────
    private data class ConfigEditorRestore(
        val fieldId: Int,
        val selectionStart: Int,
        val selectionEnd: Int,
        val imeVisible: Boolean
    )
    private var pendingConfigEditorRestore: ConfigEditorRestore? = null
    private var configEditorRestorePosted = false
    private var activityWindowHasFocus = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val netRunnable = object : Runnable {
        override fun run() {
            refreshNetworkInfo()
            // Signal and link speed drift constantly; five seconds is often
            // enough to be useful without being a battery drain.
            mainHandler.postDelayed(this, 5000)
        }
    }
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime <= 0) return
            val elapsedMs = System.currentTimeMillis() - callStartTime
            callCardTimer.text = formatElapsed(elapsedMs / 1000)
            // Tick on the second boundary of the call, not 1000ms after
            // whenever this happened to run: a flat delay accumulates the
            // handler's own latency, so the display drifts off the real
            // elapsed time and eventually skips or repeats a second.
            mainHandler.postDelayed(this, 1000 - (elapsedMs % 1000))
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GatewayService.STATUS_ACTION -> {
                    val state = intent.getStringExtra("state") ?: return
                    val info = intent.getStringExtra("info") ?: ""
                    callControlReady = intent.getBooleanExtra("registered", callControlReady)
                    voiceRunning = intent.getBooleanExtra("voice_running", voiceRunning)
                    serviceCallStart = intent.getLongExtra("call_start", 0L)
                    updateCallState(state, info)
                    refreshBackgroundStatus()
                    appendLog("[$state] $info")
                }
                GatewayService.LOG_ACTION -> appendLog(intent.getStringExtra("msg") ?: return)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        voiceRunning = GatewayService.voiceRuntimeActive
        buildWindow()
        restoreControlUiState()
        if (savedInstanceState == null) handleJoinLink(intent)
        restorePendingSimProposal()

        // Only prompt on a fresh launch. The system permission sheet can
        // recreate this Activity on rotation; asking again would cover a
        // settings form the user is already editing.
        if (savedInstanceState == null) requestSmsPermissions()

        if (savedInstanceState != null) restoreUiState(savedInstanceState)
        restorePendingSmsRecovery(savedInstanceState)
        val restoredTab = savedInstanceState?.getString(STATE_CURRENT_TAB)
            ?.takeIf { it in setOf("home", "traffic", "config", "logs") } ?: "home"
        switchTab(restoredTab)
        if (savedInstanceState != null && restoredTab == "config") queueConfigEditorRestore(savedInstanceState)
        refreshBackgroundStatus()
        refreshSimSummary()

        // Restore only paired HTTPS control sync; voice needs an explicit
        // user-triggered diagnostic and confirmation.
        autoStartGateway()
        if (savedInstanceState != null && pendingRecoveryMode != null && !recoveryPermissionRequestInFlight) {
            window.decorView.post { if (!isFinishing && !isDestroyed) requestRecoveryPermissionOrConfigure() }
        }
    }

    // ── Window and pages ─────────────────────────────────

    private fun buildWindow() {
        val pages = FrameLayout(this)
        tabHome = pageScroll(R.id.tabHome).also { it.addView(centered(buildHomePage())) }
        tabTraffic = pageScroll(R.id.tabTraffic).also { it.addView(centered(buildTrafficPage())) }
        tabConfig = pageScroll(R.id.tabConfig).also { it.addView(centered(buildSettingsPage())) }
        tabLogs = buildLogsPage()
        listOf(tabHome, tabTraffic, tabConfig, tabLogs).forEach {
            it.visibility = View.GONE
            pages.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        bottomNavigation = BottomNavigationView(this).apply {
            id = R.id.bottomNavigation
            inflateMenu(R.menu.main_navigation)
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            // The root already pads for system bars and the keyboard; Material
            // would add the same bottom inset again and stretch the bar.
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> insets }
            setOnItemSelectedListener { item ->
                val destination = when (item.itemId) {
                    R.id.navHome -> "home"
                    R.id.navTraffic -> "traffic"
                    R.id.navSettings -> "config"
                    else -> return@setOnItemSelectedListener false
                }
                if (destination != currentTab) switchTab(destination)
                true
            }
        }
        rootWindow = LinearLayout(this).apply {
            id = R.id.rootWindow
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(MaterialR.attr.colorSurface))
            addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomNavigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootWindow) { view, insets ->
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
        setContentView(rootWindow)
        ViewCompat.requestApplyInsets(rootWindow)
    }

    private fun pageScroll(viewId: Int) = ScrollView(this).apply {
        id = viewId
        isFillViewport = true
        clipToPadding = false
    }

    /** Page content, width-capped and centered on tablets. */
    private fun centered(content: LinearLayout): FrameLayout = FrameLayout(this).apply {
        val widthDp = resources.configuration.screenWidthDp
        val width = if (widthDp >= 720) dp((widthDp - 48).coerceAtMost(760)) else ViewGroup.LayoutParams.MATCH_PARENT
        addView(content, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    private fun verticalRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
    }

    private fun buildHomePage(): LinearLayout {
        val content = verticalRoot()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title("Homura"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = MaterialTextView(this).apply {
            id = R.id.tvHomeStatusPill
            setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelLarge)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onStatusPillClicked() }
        }
        header.addView(statusPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        content.addView(header)

        content.addView(buildCallCard())

        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupCard = GatewaySetupCard(setup, ::handleSetupAction)
        content.addView(setup)

        content.addView(GatewayUi.groupHeader(this, "网关状态"))
        homeStatusTiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(homeStatusTiles)

        content.addView(GatewayUi.groupHeader(this, "网络"))
        val mobile = summaryTile(GatewayUi.Position.FIRST, "移动网络", R.drawable.ic_ui_cellular) { showLinkDetails(mobile = true) }
        netMobileSummary = mobile.second.apply { id = R.id.tvNetMobile }
        content.addView(mobile.first)
        val wifi = summaryTile(GatewayUi.Position.LAST, "Wi-Fi", R.drawable.ic_ui_wifi) { showLinkDetails(mobile = false) }
        netWifiSummary = wifi.second.apply { id = R.id.tvNetWifi }
        content.addView(wifi.first)

        content.addView(GatewayUi.groupHeader(this, "SIM 卡与短信"))
        val sim = summaryTile(GatewayUi.Position.FIRST, "SIM 卡", R.drawable.ic_ui_sim) { openSettingsSection(simProposeTile) }
        simSummary = sim.second.apply { id = R.id.tvSimSummary }
        content.addView(sim.first)
        content.addView(smsBackupTile(GatewayUi.Position.LAST, R.id.btnHomeSmsBackup))
        return content
    }

    private fun buildCallCard(): MaterialCardView {
        homeCallCard = MaterialCardView(this).apply {
            id = R.id.homeCallCard
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(MaterialR.attr.colorPrimaryContainer))
            layoutParams = fullWidthParams(top = 4, bottom = 8)
            visibility = View.GONE
        }
        val onContainer = color(MaterialR.attr.colorOnPrimaryContainer)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        callCardState = MaterialTextView(this).apply {
            setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelLarge)
            setTextColor(onContainer)
        }
        top.addView(callCardState, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        callCardTimer = MaterialTextView(this).apply {
            setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleMedium)
            setTextColor(onContainer)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        top.addView(callCardTimer)
        body.addView(top)
        callCardNumber = MaterialTextView(this).apply {
            setTextAppearance(MaterialR.style.TextAppearance_Material3_HeadlineSmall)
            setTextColor(onContainer)
            setPadding(0, dp(8), 0, 0)
            setTextIsSelectable(true)
        }
        body.addView(callCardNumber)
        callCardDetail = MaterialTextView(this).apply {
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onContainer)
            setPadding(0, dp(2), 0, dp(12))
        }
        body.addView(callCardDetail)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnHomeMute = callButton(R.id.btnHomeMute, "静音", R.drawable.ic_ui_mic_off, MaterialR.attr.colorSurfaceContainerLowest) { toggleAgentMute() }
        btnHomeSnoop = callButton(R.id.btnHomeSnoop, "监听", R.drawable.call_ui_speaker, MaterialR.attr.colorSurfaceContainerLowest) { toggleMonitor() }
        btnHomeEnd = callButton(R.id.btnHomeEnd, "挂断", R.drawable.call_ui_end, androidx.appcompat.R.attr.colorError) { endCall() }
        btnHomeEnd.setTextColor(color(MaterialR.attr.colorOnError))
        btnHomeEnd.iconTint = ColorStateList.valueOf(color(MaterialR.attr.colorOnError))
        listOf(btnHomeMute, btnHomeSnoop, btnHomeEnd).forEachIndexed { index, button ->
            buttons.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        body.addView(buttons)
        homeCallCard.addView(body)
        return homeCallCard
    }

    private fun callButton(viewId: Int, label: String, icon: Int, fillAttr: Int, onClick: () -> Unit) =
        MaterialButton(this).apply {
            id = viewId
            text = label
            isAllCaps = false
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            cornerRadius = dp(26)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(color(fillAttr))
            setTextColor(color(MaterialR.attr.colorOnSurface))
            iconTint = ColorStateList.valueOf(color(MaterialR.attr.colorOnSurface))
            setOnClickListener { onClick() }
        }

    private fun buildTrafficPage(): LinearLayout {
        val content = verticalRoot()
        content.addView(title("记录"))
        val filters = ChipGroup(this).apply {
            id = R.id.trafficFilter
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = fullWidthParams(bottom = 8)
        }
        listOf(Triple(R.id.btnFilterAll, "全部", "all"), Triple(R.id.btnFilterIncoming, "呼入", "in"),
            Triple(R.id.btnFilterOutgoing, "呼出", "out")).forEach { (viewId, label, filter) ->
            filters.addView(Chip(this, null, MaterialR.attr.chipStyle).apply {
                id = viewId
                text = label
                isCheckable = true
                isChecked = filter == callFilter
                setOnCheckedChangeListener { _, checked -> if (checked) setCallFilter(filter) }
            })
        }
        content.addView(filters)
        trafficList = LinearLayout(this).apply {
            id = R.id.homeTrafficList
            orientation = LinearLayout.VERTICAL
        }
        content.addView(trafficList)
        content.addView(GatewayUi.groupHeader(this, "管理"))
        content.addView(GatewayUi.tile(this, GatewayUi.Position.SINGLE, "清除最近记录",
            summary = "从列表移除全部通话和短信记录", icon = R.drawable.ic_ui_delete,
            onClick = { confirmClearRecents() }).apply { id = R.id.btnCfgClearRecents })
        return content
    }

    private fun buildSettingsPage(): LinearLayout {
        val content = verticalRoot()
        content.addView(title("设置"))

        content.addView(GatewayUi.groupHeader(this, "服务器与配对"))
        pairedSummary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(pairedSummary)
        pairingForm = buildPairingForm()
        content.addView(pairingForm)

        content.addView(GatewayUi.groupHeader(this, "其他节点"))
        content.addView(body("可再连接几台独立服务器（如家里的局域网机器和 VPS）。每条短信都会同时送到所有节点，主手机按节点优先级自动切换且不会重复收到；从任何节点发来的同一条短信只会发送一次。SIM 只需在主节点确认一次。"))
        val mesh = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(mesh)
        meshNodesSection = MeshNodesSection(this, mesh,
            deviceName = { controlDeviceName.text.toString().trim().ifEmpty { Build.MODEL ?: "旧手机网关" } },
            onChanged = { GatewayService.startControl(this) })

        content.addView(GatewayUi.groupHeader(this, "SIM 卡"))
        content.addView(body("SIM 身份由服务器分配。每张卡只有在这里核对卡槽并确认后才会启用；ICCID 与 IMSI 不会上传。"))
        simProposeTile = GatewayUi.tile(this, GatewayUi.Position.SINGLE, "同步 SIM 列表",
            summary = "读取本机 SIM，向服务器申请 SIM 身份", icon = R.drawable.ic_ui_sim,
            trailing = GatewayUi.chevron(this), onClick = { proposeSimBindings() }).apply { id = R.id.btnSimPropose }
        content.addView(simProposeTile)
        simStatus = body("").apply { id = R.id.tvSimStatus; visibility = View.GONE }
        content.addView(simStatus)
        simBindings = LinearLayout(this).apply {
            id = R.id.llSimBindings
            orientation = LinearLayout.VERTICAL
        }
        content.addView(simBindings)
        simConfirm = button("确认所选 SIM").apply {
            id = R.id.btnSimConfirm
            visibility = View.GONE
            setOnClickListener { confirmSimBindings() }
        }
        content.addView(simConfirm)

        content.addView(GatewayUi.groupHeader(this, "远程通话"))
        voiceTileContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(voiceTileContainer)
        content.addView(agentVolumeTile())
        content.addView(silenceHandsetTile())

        content.addView(GatewayUi.groupHeader(this, "短信"))
        smsSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(smsSettings)

        content.addView(GatewayUi.groupHeader(this, "后台运行与权限"))
        backgroundSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(backgroundSettings)

        content.addView(GatewayUi.groupHeader(this, "关于"))
        content.addView(GatewayUi.tile(this, GatewayUi.Position.FIRST, "Homura",
            summary = "旧手机网关 · v${BuildConfig.VERSION_NAME}", icon = R.drawable.ic_ui_info))
        content.addView(GatewayUi.tile(this, GatewayUi.Position.LAST, "运行日志",
            summary = "网关、通话与同步的最近事件", icon = R.drawable.ic_ui_logs,
            trailing = GatewayUi.chevron(this), onClick = { switchTab("logs") }).apply { id = R.id.btnCfgLogs })
        return content
    }

    private fun buildPairingForm(): LinearLayout {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = GatewayUi.tileBackground(this@MainActivity, GatewayUi.Position.SINGLE)
            layoutParams = GatewayUi.tileParams(this@MainActivity, GatewayUi.Position.SINGLE)
        }
        form.addView(body("在服务器上为这台旧手机生成一次性 gateway 配对码，再填写服务器地址完成配对。", selectable = false))
        val urlField = inputField("服务器地址（http:// 或 https://）", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        controlUrl = (urlField.editText as TextInputEditText).apply {
            id = R.id.etControlUrl
            isSaveEnabled = false
            setSingleLine(true)
        }
        form.addView(urlField)
        val nameField = inputField("设备名称", InputType.TYPE_CLASS_TEXT)
        controlDeviceName = (nameField.editText as TextInputEditText).apply {
            id = R.id.etControlDeviceName
            isSaveEnabled = false
            setSingleLine(true)
        }
        form.addView(nameField)
        val codeField = inputField("一次性配对码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        controlPairingCode = (codeField.editText as TextInputEditText).apply {
            id = R.id.etControlPairingCode
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            // Not setSingleLine(): it replaces the password transformation
            // and would show the one-time code in clear text.
            maxLines = 1
        }
        form.addView(codeField)
        controlPairButton = button("配对网关").apply {
            id = R.id.btnControlPair
            setOnClickListener { pairControlGateway() }
        }
        form.addView(controlPairButton)
        controlStatus = body(UNPAIRED_STATUS).apply { id = R.id.tvControlStatus }
        form.addView(controlStatus)
        return form
    }

    private fun agentVolumeTile(): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = GatewayUi.tileBackground(this@MainActivity, GatewayUi.Position.MIDDLE)
            layoutParams = GatewayUi.tileParams(this@MainActivity, GatewayUi.Position.MIDDLE)
        }
        val step = getSharedPreferences("gateway", MODE_PRIVATE).getInt("agent_vol_step", 0).coerceIn(-3, 3)
        val value = GatewayUi.subtitle(this, stepText(step))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(GatewayUi.iconBadge(this, R.drawable.ic_ui_volume), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
        head.addView(GatewayUi.title(this, "对方听到的音量"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(value)
        tile.addView(head)
        tile.addView(Slider(this).apply {
            id = R.id.sbCfgAgentVolume
            valueFrom = -3f
            valueTo = 3f
            stepSize = 1f
            this.value = step.toFloat()
            contentDescription = "对方听到的音量"
            addOnChangeListener { _, newValue, fromUser ->
                value.text = stepText(newValue.toInt())
                if (fromUser) getSharedPreferences("gateway", MODE_PRIVATE).edit()
                    .putInt("agent_vol_step", newValue.toInt()).apply()
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) = applyCallAudioSettings()
            })
        })
        tile.addView(GatewayUi.subtitle(this, "蜂窝通话另一方听到的音量。0 为设备默认值，每一级约增减三分之一。对方说声音小就调高，出现失真就调低。", lines = 4))
        return tile
    }

    private fun silenceHandsetTile(): View {
        val switch = MaterialSwitch(this).apply {
            id = R.id.swCfgSilenceHandset
            isChecked = getSharedPreferences("gateway", MODE_PRIVATE).getBoolean("silence_handset", true)
            isSaveEnabled = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return switchTile(GatewayUi.Position.LAST, "通话时静音本机",
            "关闭本机麦克风并把听筒调到最低，避免回音。若开启后对方听不到主手机的声音，请关闭此项。",
            R.drawable.call_ui_mic, switch) {
            switch.isChecked = !switch.isChecked
            getSharedPreferences("gateway", MODE_PRIVATE).edit().putBoolean("silence_handset", switch.isChecked).apply()
            applyCallAudioSettings()
        }
    }

    private fun buildLogsPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            id = R.id.tabLogs
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
        }
        bar.addView(iconButton(R.id.btnLogsBack, R.drawable.call_ui_back, "返回") { switchTab(logsReturnTab) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(MaterialTextView(this).apply {
            text = "运行日志"
            setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleLarge)
            setTextColor(color(MaterialR.attr.colorOnSurface))
            setPadding(dp(12), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(R.id.btnLogsCopy, R.drawable.ic_ui_copy, "复制日志") { copyLog() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(iconButton(R.id.btnLogsClear, R.drawable.ic_ui_delete, "清除日志") { clearLog() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(bar)
        tvLog = MaterialTextView(this).apply {
            id = R.id.tvLog
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(color(MaterialR.attr.colorOnSurface))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        svLog = ScrollView(this).apply {
            id = R.id.svLog
            background = GatewayUi.tileBackground(this@MainActivity, GatewayUi.Position.SINGLE)
            addView(tvLog)
        }
        page.addView(svLog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    private fun iconButton(viewId: Int, icon: Int, description: String, onClick: () -> Unit) =
        MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            id = viewId
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(color(MaterialR.attr.colorOnSurface))
            iconSize = dp(24)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            contentDescription = description
            cornerRadius = dp(24)
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

    // ── Navigation ───────────────────────────────────────

    /** Show one of the pages and keep the bottom navigation in sync. */
    private fun switchTab(tab: String) {
        if (tab == currentTab) return
        if (tab == "logs" && currentTab.isNotBlank() && currentTab != "logs") logsReturnTab = currentTab
        currentTab = tab
        tabHome.visibility = if (tab == "home") View.VISIBLE else View.GONE
        tabTraffic.visibility = if (tab == "traffic") View.VISIBLE else View.GONE
        tabConfig.visibility = if (tab == "config") View.VISIBLE else View.GONE
        tabLogs.visibility = if (tab == "logs") View.VISIBLE else View.GONE
        val selectedId = when (tab) {
            "traffic" -> R.id.navTraffic
            "config", "logs" -> R.id.navSettings
            else -> R.id.navHome
        }
        if (bottomNavigation.selectedItemId != selectedId) bottomNavigation.selectedItemId = selectedId
        when (tab) {
            "home" -> refreshNetworkInfo()
            "traffic" -> renderTraffic()
            "config" -> {
                meshNodesSection.render()
                refreshSettings()
            }
            // The view scrolls as lines arrive, but only while it is visible;
            // opening it has to jump to the newest entry itself.
            "logs" -> svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** Open settings and bring one section into view, so a setup step lands on its controls. */
    private fun openSettingsSection(target: View) {
        switchTab("config")
        tabConfig.post {
            val bounds = android.graphics.Rect()
            target.getDrawingRect(bounds)
            runCatching { tabConfig.offsetDescendantRectToMyCoords(target, bounds) }
            tabConfig.smoothScrollTo(0, (bounds.top - dp(24)).coerceAtLeast(0))
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            currentTab == "logs" -> switchTab(logsReturnTab)
            currentTab != "home" -> switchTab("home")
            else -> super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJoinLink(intent)
    }

    // ── Saved state ──────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT_TAB, currentTab)
        outState.putString(STATE_LOGS_RETURN_TAB, logsReturnTab)
        outState.putString(STATE_CALL_FILTER, callFilter)
        // The pairing code is a one-time secret and is never saved.
        outState.putString(STATE_CONTROL_URL, controlUrl.text.toString())
        outState.putString(STATE_CONTROL_DEVICE_NAME, controlDeviceName.text.toString())
        outState.putBoolean(STATE_PAIRING_FORM_OPEN, pairingFormOpen)
        outState.putInt(STATE_CFG_SCROLL_Y, tabConfig.scrollY)
        val focusedField = rootWindow.findFocus() as? EditText
        if (currentTab == "config" && focusedField != null && focusedField.id in CONFIG_RESTORABLE_FOCUS_FIELD_IDS) {
            outState.putInt(STATE_CONFIG_FOCUSED_FIELD_ID, focusedField.id)
            outState.putInt(STATE_CONFIG_SELECTION_START, focusedField.selectionStart.coerceAtLeast(0))
            outState.putInt(STATE_CONFIG_SELECTION_END, focusedField.selectionEnd.coerceAtLeast(0))
            outState.putBoolean(STATE_CONFIG_IME_VISIBLE,
                ViewCompat.getRootWindowInsets(rootWindow)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        }
        outState.putString(STATE_RECOVERY_MODE, pendingRecoveryMode?.name)
        outState.putString(STATE_RECOVERY_GATEWAY_ID, pendingRecoveryGatewayId)
        outState.putString(STATE_RECOVERY_CONTROL_BASE_URL, pendingRecoveryControlBaseUrl)
        outState.putBoolean(STATE_RECOVERY_PERMISSION_IN_FLIGHT, recoveryPermissionRequestInFlight)
    }

    private fun restoreUiState(state: Bundle) {
        logsReturnTab = state.getString(STATE_LOGS_RETURN_TAB)?.takeIf { it in setOf("home", "traffic", "config") } ?: "config"
        callFilter = state.getString(STATE_CALL_FILTER)?.takeIf { it in setOf("all", "in", "out") } ?: "all"
        listOf(R.id.btnFilterAll to "all", R.id.btnFilterIncoming to "in", R.id.btnFilterOutgoing to "out")
            .forEach { (viewId, filter) -> findViewById<Chip>(viewId).isChecked = filter == callFilter }
        state.getString(STATE_CONTROL_URL)?.let { controlUrl.setText(it) }
        state.getString(STATE_CONTROL_DEVICE_NAME)?.let { controlDeviceName.setText(it) }
        pairingFormOpen = state.getBoolean(STATE_PAIRING_FORM_OPEN)
        tabConfig.post { tabConfig.scrollTo(0, state.getInt(STATE_CFG_SCROLL_Y, 0)) }
    }

    private fun queueConfigEditorRestore(state: Bundle) {
        val fieldId = state.getInt(STATE_CONFIG_FOCUSED_FIELD_ID, View.NO_ID)
        if (fieldId !in CONFIG_RESTORABLE_FOCUS_FIELD_IDS) return
        pendingConfigEditorRestore = ConfigEditorRestore(
            fieldId = fieldId,
            selectionStart = state.getInt(STATE_CONFIG_SELECTION_START),
            selectionEnd = state.getInt(STATE_CONFIG_SELECTION_END),
            imeVisible = state.getBoolean(STATE_CONFIG_IME_VISIBLE)
        )
    }

    private fun scheduleConfigEditorRestoreWhenReady() {
        val restore = pendingConfigEditorRestore ?: return
        if (!activityWindowHasFocus || configEditorRestorePosted) return
        if (!rootWindow.isAttachedToWindow) return
        configEditorRestorePosted = true
        rootWindow.post {
            configEditorRestorePosted = false
            if (pendingConfigEditorRestore !== restore || !activityWindowHasFocus ||
                isFinishing || isDestroyed || !rootWindow.isAttachedToWindow
            ) return@post
            val field = findViewById<EditText>(restore.fieldId)
            if (!field.isAttachedToWindow || !field.isShown || !field.isEnabled || !field.requestFocus()) return@post
            val length = field.text.length
            field.setSelection(restore.selectionStart.coerceIn(0, length), restore.selectionEnd.coerceIn(0, length))
            pendingConfigEditorRestore = null
            if (restore.imeVisible) {
                WindowCompat.getInsetsController(window, rootWindow).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        scheduleConfigEditorRestoreWhenReady()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        activityWindowHasFocus = hasFocus
        if (hasFocus) scheduleConfigEditorRestoreWhenReady()
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

    // ── Lifecycle ────────────────────────────────────────

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
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Re-render from the service's buffer rather than consuming it: the
        // lines are already stamped with when each event happened, and
        // replacing the view means a recreated activity shows the full recent
        // history instead of whatever it happened to witness.
        val buffered = GatewayService.logSnapshot()
        if (buffered.isNotEmpty()) {
            tvLog.text = buffered.joinToString("\n", postfix = "\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        voiceRunning = GatewayService.voiceRuntimeActive
        if (callStartTime > 0) {
            mainHandler.removeCallbacks(callTimerRunnable)
            callTimerRunnable.run()
        }
        if (currentTab == "traffic") renderTraffic()
        if (currentTab == "config") {
            meshNodesSection.render()
            refreshSettings()
        }
        mainHandler.removeCallbacks(netRunnable)
        netRunnable.run()
        refreshBackgroundStatus()
        refreshSimSummary()

        // Ask the service where it is.  Status is only pushed on change, so
        // opening the app onto an already-running gateway would otherwise show
        // a stale state until something happened.
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_STATUS
        })
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(callTimerRunnable)
        mainHandler.removeCallbacks(netRunnable)
        unregisterReceiver(statusReceiver)
    }

    // ── Status page ──────────────────────────────────────

    private fun refreshBackgroundStatus() {
        val status = runCatching { GatewayBackgroundRuntime.snapshot(this) }.getOrNull()
        renderStatusPill(status)
        renderHomeStatusTiles(status)
        refreshSetupChecklist(status)
        if (currentTab == "config") refreshSettings(status)
    }

    private fun renderStatusPill(status: BackgroundStatus?) {
        val paired = CredentialStore.load(this) != null
        val (text, good) = when {
            !paired -> "● 未配对" to false
            callControlReady -> "● 通话就绪" to true
            voiceRunning -> "● 通话连接中" to false
            status?.running == true -> "● 短信在线" to true
            else -> "● 未运行" to false
        }
        statusPill.text = text
        statusPill.setTextColor(color(if (good) MaterialR.attr.colorOnTertiaryContainer else MaterialR.attr.colorOnErrorContainer))
        statusPill.background = GatewayUi.pill(this,
            if (good) MaterialR.attr.colorTertiaryContainer else MaterialR.attr.colorErrorContainer)
    }

    private fun onStatusPillClicked() {
        when {
            CredentialStore.load(this) == null -> openSettingsSection(pairingForm)
            callControlReady -> Toast.makeText(this, "通话控制已就绪", Toast.LENGTH_SHORT).show()
            !voiceRunning -> Toast.makeText(this, "语音未启动。可在“网关状态”中运行设备诊断后启动。", Toast.LENGTH_LONG).show()
            !GatewayBackgroundRuntime.allowedRecovery(this) ->
                Toast.makeText(this, "请先在设置中重新启用后台运行", Toast.LENGTH_LONG).show()
            else -> {
                appendLog("Reconnect requested")
                Toast.makeText(this, "正在重新连接…", Toast.LENGTH_SHORT).show()
                startService(Intent(this, GatewayService::class.java).apply { action = GatewayService.ACTION_RECONNECT })
            }
        }
    }

    private fun renderHomeStatusTiles(status: BackgroundStatus?) {
        homeStatusTiles.removeAllViews()
        val connection = if (status == null) "暂时无法读取，请稍后重试或查看运行日志" else buildString {
            append(when {
                status.running -> "正在运行"
                status.enabled -> "已启用，等待系统启动"
                else -> "未运行"
            })
            append(" · ")
            append(localizedBackgroundConnection(status.connectionLabel))
            status.lastSyncAt?.let {
                append("\n最近同步：")
                append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)))
            }
        }
        homeStatusTiles.addView(GatewayUi.tile(this, GatewayUi.Position.FIRST, "后台同步", summary = connection,
            icon = R.drawable.ic_ui_refresh, trailing = GatewayUi.chevron(this),
            onClick = { openSettingsSection(backgroundSettings) }))
        val voiceSummary = when {
            callControlReady -> "通话控制已就绪 · WebRTC 语音"
            voiceRunning -> "通话控制正在连接；若长时间未就绪，请查看运行日志"
            CredentialStore.load(this) == null -> "配对后可启用远程通话"
            else -> "未启动。需要 root/Magisk 与可用的通话音频路由；仅收发短信可跳过"
        }
        val start = if (!voiceRunning && CredentialStore.load(this) != null) {
            MaterialButton(this, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                id = R.id.btnStartGatewayDiagnostics
                text = "诊断并启动"
                isAllCaps = false
                cornerRadius = dp(24)
                setOnClickListener { openGatewayDiagnostics() }
            }
        } else null
        val issue = status?.issue?.takeIf { it.isNotBlank() }?.let(::localizedBackgroundIssue)
        homeStatusTiles.addView(GatewayUi.tile(this,
            if (issue == null && permissionNotice == null) GatewayUi.Position.LAST else GatewayUi.Position.MIDDLE,
            "远程通话", summary = voiceSummary, icon = R.drawable.call_ui_phone, trailing = start))
        val notes = listOfNotNull(issue, permissionNotice)
        notes.forEachIndexed { index, note ->
            homeStatusTiles.addView(GatewayUi.tile(this,
                if (index == notes.lastIndex) GatewayUi.Position.LAST else GatewayUi.Position.MIDDLE,
                note, icon = R.drawable.ic_ui_info).apply {
                ((getChildAt(1) as? LinearLayout)?.getChildAt(0) as? TextView)?.apply {
                    maxLines = 6
                    setTextColor(color(androidx.appcompat.R.attr.colorError))
                }
            })
        }
    }

    /** Rebuild the guided setup card from local state; cheap enough to run on every status refresh. */
    private fun refreshSetupChecklist(background: BackgroundStatus? =
        runCatching { GatewayBackgroundRuntime.snapshot(this) }.getOrNull()) {
        val smsGranted = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        setupCard.render(GatewaySetupChecklist.build(GatewaySetupChecklist.Input(
            paired = CredentialStore.load(this) != null,
            smsPermissionsGranted = smsGranted,
            activeSimCount = setupActiveSimCount,
            detectedSimCount = setupDetectedSimCount,
            proposalPending = pendingMappingProposal != null,
            backgroundEnabled = background?.enabled == true,
            backgroundRunning = background?.running == true,
            notificationsEnabled = background?.notificationsEnabled == true,
            batteryExempt = background?.batteryExempt == true,
            voiceRunning = voiceRunning,
            callControlReady = callControlReady
        )))
    }

    private fun handleSetupAction(action: GatewaySetupChecklist.Action) {
        when (action) {
            GatewaySetupChecklist.Action.OPEN_PAIRING -> {
                pairingFormOpen = true
                openSettingsSection(pairingForm)
            }
            GatewaySetupChecklist.Action.OPEN_SIMS -> {
                requestSmsPermissions()
                openSettingsSection(simProposeTile)
            }
            GatewaySetupChecklist.Action.ENABLE_BACKGROUND -> setBackgroundEnabled(true)
            GatewaySetupChecklist.Action.OPEN_BATTERY -> GatewayBackgroundRuntime.openBatterySettings(this)
            GatewaySetupChecklist.Action.OPEN_NOTIFICATIONS -> openNotificationPermission()
            GatewaySetupChecklist.Action.START_VOICE -> openGatewayDiagnostics()
            GatewaySetupChecklist.Action.NONE -> Unit
        }
    }

    // ── Live call ────────────────────────────────────────

    private fun updateCallState(state: String, info: String) {
        val previous = bridgeState
        bridgeState = state
        when (state) {
            "GSM_RINGING" -> callIncoming = true
            "GSM_DIALING" -> callIncoming = false
        }
        renderStatusPill(runCatching { GatewayBackgroundRuntime.snapshot(this) }.getOrNull())
        val inCall = state in CALL_STATES
        if (!inCall) {
            homeCallCard.visibility = View.GONE
            callStartTime = 0L
            mainHandler.removeCallbacks(callTimerRunnable)
            // Mute and the monitor are per call; do not carry them into the next one.
            agentMuted = false
            monitoring = false
            updateCallButtons()
            if (previous in CALL_STATES) renderTraffic()
            return
        }
        homeCallCard.visibility = View.VISIBLE
        val number = GsmCallManager.currentNumber?.takeIf { it.isNotBlank() } ?: info
        callCardNumber.text = number.ifBlank { "未知号码" }
        val direction = if (callIncoming) "呼入" else "呼出"
        callCardState.text = when (state) {
            "GSM_RINGING" -> "● 来电 · 等待主手机接听"
            "GSM_DIALING" -> "● 正在呼叫"
            "BRIDGED" -> "● 正在通话 · $direction"
            else -> "● 正在结束"
        }
        callCardDetail.text = "WebRTC 语音 · 服务器通话控制"
        if (state == "BRIDGED") {
            callStartTime = if (serviceCallStart > 0) serviceCallStart else System.currentTimeMillis()
            mainHandler.removeCallbacks(callTimerRunnable)
            callTimerRunnable.run()
        } else {
            callCardTimer.text = ""
        }
        updateCallButtons()
    }

    private fun updateCallButtons() {
        val live = bridgeState == "BRIDGED"
        btnHomeMute.isEnabled = live
        btnHomeSnoop.isEnabled = live
        btnHomeMute.text = if (agentMuted) "取消静音" else "静音"
        btnHomeSnoop.text = if (monitoring) "停止监听" else "监听"
    }

    /** Cut the agent's audio to the caller.  The call stays up; this only
     *  silences what the agent is sending, for when it says something wrong. */
    private fun toggleAgentMute() {
        agentMuted = !agentMuted
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MUTE_AGENT
            putExtra(GatewayService.EXTRA_MUTE_ON, agentMuted)
        })
        updateCallButtons()
        appendLog(if (agentMuted) "Agent muted to caller" else "Agent unmuted")
    }

    /** Listen in on the live call through the speaker.  The microphone is not
     *  touched — it stays muted, so the room is never audible to either side. */
    private fun toggleMonitor() {
        monitoring = !monitoring
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MONITOR
            putExtra(GatewayService.EXTRA_MONITOR_ON, monitoring)
        })
        updateCallButtons()
        appendLog(if (monitoring) "Snoop on — both sides on the speaker" else "Snoop off")
    }

    private fun endCall() {
        if (GsmCallManager.activeCall != null) {
            callCardState.text = "● 正在结束"
            GsmCallManager.hangupCall()
        }
    }

    // ── Network ──────────────────────────────────────────

    /**
     * Signal strength in dBm, or null when the modem has no usable reading.
     *
     * SignalStrength.getCellSignalStrengths() is API 29 and minSdk here is 26,
     * so below Q the method does not exist and calling it throws
     * NoSuchMethodError, which is an Error that no catch around it contains.
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
        netMobileSummary.text = try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val name = tm.networkOperatorName?.ifEmpty { "无服务" } ?: "无服务"
            val type = when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> "—"
                else -> "?"
            }
            val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signalDbm(tm.signalStrength) else null
            if (dbm != null && dbm != Int.MAX_VALUE) "$type · $name · ${dbm} dBm" else "$type · $name"
        } catch (_: Exception) {
            "无法读取"
        }

        netWifiSummary.text = try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            when {
                !wm.isWifiEnabled -> "已关闭"
                // networkId is NOT a connectivity test: without location
                // permission getConnectionInfo() comes back redacted with
                // networkId = -1 even on a healthy connection.  Connectivity
                // comes from NetworkCapabilities; link metrics are not
                // location-gated and are still readable here.
                !onWifi -> "未连接"
                else -> {
                    @Suppress("DEPRECATION")
                    val raw = info?.ssid?.trim('"').orEmpty()
                    val ssid = if (raw.isEmpty() || raw.contains("unknown", true)) "Wi-Fi" else raw
                    @Suppress("DEPRECATION")
                    val freq = info?.frequency ?: 0
                    @Suppress("DEPRECATION")
                    val speed = info?.linkSpeed ?: -1
                    @Suppress("DEPRECATION")
                    val rssi = info?.rssi ?: 0
                    val band = if (freq > 4000) "5 GHz" else "2.4 GHz"
                    if (speed > 0) "$ssid · $band · ${speed} Mbps · ${rssi} dBm" else "$ssid · $band · ${rssi} dBm"
                }
            }
        } catch (_: Exception) {
            "无法读取"
        }
    }

    /**
     * Everything known about one of the two links.  What the framework can
     * answer is shown immediately; the cell list and the pings are appended
     * as they arrive, so the dialog is never blank while a ping runs.
     */
    private fun showLinkDetails(mobile: Boolean) {
        val body = monospace(if (mobile) mobileDetailsFast() else wifiDetailsFast())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (mobile) "移动网络" else "Wi-Fi")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("关闭", null)
            .show()

        fun append(line: String) = runOnUiThread { if (dialog.isShowing) body.append(line) }

        Thread({
            if (mobile) {
                // Cell identity is location data as far as Android is
                // concerned: the only way to read it is getAllCellInfo() with
                // an explicitly granted ACCESS_FINE_LOCATION.
                append("\n" + describeCells())
            } else {
                val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val gw = connectivity.getLinkProperties(connectivity.activeNetwork)?.routes
                    ?.firstOrNull { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }?.gateway?.hostAddress.orEmpty()
                // MAC addresses are privacy restricted; diagnostics must not open su.
                append("Router IP    : ${gw.ifEmpty { "—" }}\n")
                append("\n— reachability —\n")
                if (gw.isNotEmpty()) append("Router  : ${pingAvg(gw)}\n")
                val server = CredentialStore.load(this)?.controlBaseUrl
                    ?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty()
                if (server.isNotEmpty()) append("Server  : ${pingAvg(server)}  ($server)\n")
            }
        }, "link-details").start()
    }

    /** Mobile facts the framework answers instantly. */
    @SuppressLint("MissingPermission")
    private fun mobileDetailsFast(): String = buildString {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
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
                    appendLine("Signal[$i]    : ${c.dbm} dBm, level ${c.level}/4 " +
                        "(${c.javaClass.simpleName.removePrefix("CellSignalStrength")})")
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
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            appendLine("Enabled      : ${if (wm.isWifiEnabled) "yes" else "no"}")
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            @Suppress("DEPRECATION")
            val ssidRaw = info?.ssid?.trim('"').orEmpty()
            appendLine("SSID         : " + if (ssidRaw.isEmpty() || ssidRaw.contains("unknown", true)) "—" else ssidRaw)
            @Suppress("DEPRECATION")
            val bssid = info?.bssid.orEmpty()
            appendLine("BSSID (AP)   : " + if (bssid.isEmpty() || bssid.startsWith("02:00:00")) "—" else bssid)
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
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return "needs location permission (granted by the Magisk module on boot)"
        return try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
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
        // does not have is itself the hazard.
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
                    id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }?.let { row("Carrier", it) }
                } else {
                    row("MCC/MNC", "${num(id.mcc)}/${num(id.mnc)}")
                }
                row("Signal", sig(info.cellSignalStrength))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is android.telephony.CellInfoNr -> {
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
                // The CellInfo base class only grew getCellSignalStrength() in API 30.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) row("Signal", sig(info.cellSignalStrength))
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
        // getCurrentSecurityType() is API 31; a missing method raises
        // NoSuchMethodError, which no catch below would contain.
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
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3-Enterprise 192-bit"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
                else -> "unknown"
            }
        } catch (_: Exception) {
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
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
        else -> "unknown($state)"
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

    @SuppressLint("MissingPermission")
    private fun refreshSimSummary() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            simSummary.text = "未获电话状态权限，暂时无法读取"
            refreshSetupChecklist()
            return
        }
        val subscriptions = runCatching {
            getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                .orEmpty()
                .sortedBy { it.simSlotIndex }
        }.getOrElse {
            simSummary.text = "读取失败，请检查系统权限"
            return
        }
        if (subscriptions.isEmpty()) {
            simSummary.text = "未检测到已启用的 SIM；插入 SIM 后可在设置中绑定"
            setupDetectedSimCount = 0
            setupActiveSimCount = 0
            refreshSetupChecklist()
            return
        }
        val labels = subscriptions.map { info ->
            val slot = if (info.simSlotIndex >= 0) "SIM ${info.simSlotIndex + 1}" else "SIM"
            val carrier = info.carrierName?.toString()?.trim().orEmpty()
            if (carrier.isBlank()) slot else "$slot · $carrier"
        }
        setupDetectedSimCount = subscriptions.size
        setupActiveSimCount = runCatching {
            SimRegistry.snapshot(this).mappings.count { it.identityState != SimRegistry.IdentityState.UNVERIFIED }
        }.getOrDefault(0)
        simSummary.text = "检测到 ${subscriptions.size} 张（${labels.joinToString("，")}）· 已绑定 $setupActiveSimCount 张"
        refreshSetupChecklist()
    }

    // ── Records page ─────────────────────────────────────

    private fun setCallFilter(filter: String) {
        callFilter = filter
        renderTraffic()
    }

    /** Recent calls and messages, newest first. */
    private fun renderTraffic() {
        val all = try { CallLogStore.getEntries(this) } catch (_: Exception) { emptyList() }
        val entries = when (callFilter) {
            "in" -> all.filter { it.direction == "IN" }
            "out" -> all.filter { it.direction != "IN" }
            else -> all
        }.sortedByDescending { it.timestamp }.take(MAX_TRAFFIC_ROWS)
        trafficList.removeAllViews()
        if (entries.isEmpty()) {
            trafficList.addView(GatewayUi.tile(this, GatewayUi.Position.SINGLE, "暂无通话或短信", icon = R.drawable.nav_phone))
            return
        }
        var group = ""
        var groupStart = 0
        entries.forEachIndexed { index, entry ->
            val day = dayGroup(entry.timestamp)
            if (day != group) {
                group = day
                groupStart = index
                trafficList.addView(GatewayUi.groupHeader(this, day))
            }
            val groupSize = entries.drop(groupStart).takeWhile { dayGroup(it.timestamp) == day }.size
            trafficList.addView(trafficTile(entry, GatewayUi.position(index - groupStart, groupSize)))
        }
    }

    private fun trafficTile(entry: CallLogEntry, position: GatewayUi.Position): View {
        val incoming = entry.direction == "IN"
        val sms = entry.type == CallLogStore.TYPE_SMS
        val title = entry.number.ifEmpty { if (sms) "短信 · 正文已清除" else "未知号码" }
        val summary = when {
            sms -> entry.text.replace('\n', ' ').trim().ifEmpty { "服务器同步后已清除短信详情" }
            entry.durationSec > 0 -> if (incoming) "呼入 · 已接通" else "呼出 · 已接通"
            else -> if (incoming) "呼入 · 未接通" else "呼出 · 未接通"
        }
        val icon = when {
            sms -> R.drawable.ic_ui_sms
            incoming && entry.durationSec <= 0 -> R.drawable.ic_call_missed
            incoming -> R.drawable.ic_call_received
            else -> R.drawable.ic_call_made
        }
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(GatewayUi.subtitle(this@MainActivity, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp))))
            addView(GatewayUi.subtitle(this@MainActivity, when {
                sms -> "短信"
                entry.durationSec > 0 -> String.format(Locale.US, "%02d:%02d", entry.durationSec / 60, entry.durationSec % 60)
                else -> "—"
            }, colorAttr = when {
                sms -> MaterialR.attr.colorTertiary
                entry.durationSec > 0 -> androidx.appcompat.R.attr.colorPrimary
                else -> androidx.appcompat.R.attr.colorError
            }))
        }
        return GatewayUi.tile(this, position, title, summary = summary, icon = icon, trailing = trailing,
            onClick = if (sms) ({ showSmsDetails(entry) }) else null)
    }

    private fun dayGroup(timestamp: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val date = Date(timestamp)
        val today = day.format(Date())
        val yesterday = day.format(Date(System.currentTimeMillis() - 24 * 3600 * 1000L))
        return when (day.format(date)) {
            today -> "今天"
            yesterday -> "昨天"
            else -> SimpleDateFormat("M 月 d 日", Locale.CHINA).format(date)
        }
    }

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
        val live = entry.smsId.takeIf { it.isNotEmpty() }?.let { runCatching { SmsOutbox.get(this, it) }.getOrNull() }
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
        fun row(label: String, value: String) = label.padEnd(9) + ": " + value.ifEmpty { "—" } + "\n"
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(entry.timestamp))
        val header = StringBuilder()
            .append(row("Direction", if (outgoing) "Outgoing" else "Incoming"))
            .append(row(if (outgoing) "To" else "From", entry.number))
            .append(row("SMSC", entry.smsc))
            .append(row("Date", stamp))
            .append(row("Format", entry.encoding))
            .append(row("Length", "${entry.text.length} chars" +
                if (parts > 0) ", $parts part${if (parts == 1) "" else "s"}" else ""))
        // Delivery status is the outbound half of the story; an inbound
        // message has already arrived by definition.
        if (outgoing) {
            header.append(row("Status", status))
            if (error.isNotEmpty()) header.append(row("Error", error))
        }
        val body = monospace(header.toString() + "\n" + entry.text.ifEmpty { "(no text)" }).apply { setTextIsSelectable(true) }
        MaterialAlertDialogBuilder(this)
            .setTitle("短信详情")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("SMS detail", body.text))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Empty the records list — calls and messages together, since both are
     * rows in the same log.  Confirmed first: it is not recoverable, and the
     * log is the only record the gateway keeps of what it has handled.
     */
    private fun confirmClearRecents() {
        val count = try { CallLogStore.getEntries(this).size } catch (_: Exception) { 0 }
        if (count == 0) {
            Toast.makeText(this, "没有可清除的记录", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("清除最近记录？")
            .setMessage("将从列表移除全部 $count 条通话和短信记录，无法撤销。")
            .setPositiveButton("清除") { _, _ ->
                // Off the UI thread: clearing rewrites the stored blob, and
                // the list is rebuilt from disk straight afterwards.
                Thread {
                    try { CallLogStore.clear(this) } catch (_: Exception) {}
                    runOnUiThread {
                        renderTraffic()
                        Toast.makeText(this, "已清除最近记录", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── Settings page ────────────────────────────────────

    /** Rebuild the settings rows that follow live state; input fields are left alone. */
    private fun refreshSettings(status: BackgroundStatus? = runCatching { GatewayBackgroundRuntime.snapshot(this) }.getOrNull()) {
        renderPairedSummary()
        renderVoiceSettings()
        renderSmsSettings()
        renderBackgroundSettings(status)
    }

    private fun renderPairedSummary() {
        val session = CredentialStore.load(this)
        pairedSummary.removeAllViews()
        if (session == null) {
            pairingForm.visibility = View.VISIBLE
            return
        }
        pairedSummary.addView(GatewayUi.tile(this, GatewayUi.Position.FIRST, session.controlBaseUrl,
            summary = "$PAIRED_STATUS_PREFIX ${session.gatewayId.take(8)}… · ${backgroundSyncLabel()}",
            icon = R.drawable.ic_ui_server))
        pairedSummary.addView(GatewayUi.tile(this, GatewayUi.Position.LAST,
            if (pairingFormOpen) "收起重新配对" else "重新配对",
            summary = "与另一台服务器配对会替换当前配对身份",
            icon = R.drawable.ic_ui_link_off, onClick = {
                pairingFormOpen = !pairingFormOpen
                renderPairedSummary()
            }).apply { id = R.id.btnControlRepair })
        pairingForm.visibility = if (pairingFormOpen) View.VISIBLE else View.GONE
    }

    private fun renderVoiceSettings() {
        voiceTileContainer.removeAllViews()
        val summary = when {
            callControlReady -> "通话控制已就绪。主手机经服务器接打电话，音频走 WebRTC。"
            voiceRunning -> "通话控制正在连接；若长时间未就绪，请查看运行日志。"
            else -> "通话需要 root/Magisk 与可用的通话音频路由。先运行设备诊断，确认后启动；仅收发短信可跳过。"
        }
        voiceTileContainer.addView(GatewayUi.tile(this, GatewayUi.Position.FIRST,
            if (voiceRunning) "语音已启动" else "设备诊断并启动语音", summary = summary,
            icon = R.drawable.call_ui_phone,
            trailing = if (voiceRunning) null else GatewayUi.chevron(this),
            onClick = if (voiceRunning) null else ({ openGatewayDiagnostics() })))
    }

    private fun renderSmsSettings() {
        smsSettings.removeAllViews()
        val status = runCatching { SmsProviderRecovery.status(this) }.getOrNull()
        val recovery = when {
            status == null -> "暂时无法读取"
            status.state == SmsProviderRecovery.State.NOT_PAIRED -> "请先配对中转服务器。"
            !status.configured -> "未启用。允许读取系统收件箱后，可补回应用未运行时已存入系统的短信。"
            status.state == SmsProviderRecovery.State.PERMISSION_REQUIRED -> "需要允许读取短信。广播收发不受此选项影响。"
            else -> "已启用 · 补入 ${status.importedRows} 条 · 已匹配 ${status.matchedBroadcasts} 条广播"
        }
        val configured = status?.configured == true
        smsSettings.addView(GatewayUi.tile(this, GatewayUi.Position.FIRST, "短信补收", summary = recovery,
            icon = R.drawable.ic_ui_sms, trailing = if (configured) null else GatewayUi.chevron(this),
            onClick = if (configured) null else ({ offerSmsRecovery() })).apply { id = R.id.btnEnableSmsRecovery })
        smsSettings.addView(GatewayUi.tile(this, GatewayUi.Position.MIDDLE, "立即补收并同步",
            summary = if (configured) "补收系统收件箱中的新短信并上报服务器" else "启用短信补收后可用",
            icon = R.drawable.ic_ui_refresh,
            onClick = { if (configured && !recoveryBusy.get()) scanSmsRecovery() }).apply {
            id = R.id.btnScanSmsRecovery
            isEnabled = configured && !recoveryBusy.get()
            alpha = if (isEnabled) 1f else 0.5f
        })
        smsSettings.addView(smsBackupTile(GatewayUi.Position.LAST, R.id.btnCfgSmsBackup))
    }

    private fun renderBackgroundSettings(status: BackgroundStatus?) {
        backgroundSettings.removeAllViews()
        val switch = MaterialSwitch(this).apply {
            id = R.id.swBackgroundEnabled
            isChecked = status?.enabled == true
            isSaveEnabled = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        backgroundSettings.addView(switchTile(GatewayUi.Position.FIRST, "后台运行",
            "开机及系统停止后恢复 HTTPS 控制同步；语音需在诊断后手动启动", R.drawable.ic_ui_refresh, switch) {
            setBackgroundEnabled(!switch.isChecked)
        })
        backgroundSettings.addView(GatewayUi.tile(this, GatewayUi.Position.MIDDLE, "通知",
            summary = if (status?.notificationsEnabled == true) "已允许" else "未允许，后台运行状态可能无法显示在通知栏",
            icon = R.drawable.ic_ui_notifications, trailing = GatewayUi.chevron(this),
            onClick = { openNotificationPermission() }).apply { id = R.id.btnOpenNotificationSettings })
        backgroundSettings.addView(GatewayUi.tile(this, GatewayUi.Position.LAST, "电池优化",
            summary = if (status?.batteryExempt == true) "已豁免" else "未豁免，系统可能限制后台运行",
            icon = R.drawable.ic_ui_battery, trailing = GatewayUi.chevron(this),
            onClick = { GatewayBackgroundRuntime.openBatterySettings(this) }).apply { id = R.id.btnOpenBatterySettings })
    }

    private fun setBackgroundEnabled(enabled: Boolean) {
        val changed = runCatching { GatewayBackgroundRuntime.setEnabled(this, enabled) }.getOrDefault(false)
        if (!changed) Toast.makeText(this, "后台运行设置未能保存", Toast.LENGTH_LONG).show()
        refreshBackgroundStatus()
        if (currentTab != "config") refreshSettings()
    }

    private fun openNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) GatewayBackgroundRuntime.requestNotificationPermission(this)
        else GatewayBackgroundRuntime.openNotificationSettings(this)
    }

    /** Call audio settings apply to a running voice runtime from its next call. */
    private fun applyCallAudioSettings() {
        if (!GatewayService.voiceRuntimeActive) return
        startService(Intent(this, GatewayService::class.java).apply { action = GatewayService.ACTION_APPLY_CONFIG })
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
        "call control connecting · https polling" -> "通话控制连接中 · HTTPS 轮询"
        "unknown" -> "未知"
        else -> label.ifBlank { "未知" }
    }

    private fun localizedBackgroundIssue(issue: String): String = when {
        issue.startsWith("Android stopped the gateway from Task Manager", ignoreCase = true) ->
            "Android 任务管理器停止了网关。请在设置中重新启用后台运行后恢复。"
        issue.startsWith("Pair this device with the control server", ignoreCase = true) ->
            "尚未配对控制服务器。请先在“设置”中完成设备配对。"
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

    // ── Pairing ──────────────────────────────────────────

    private fun autoStartGateway() {
        if (!GatewayBackgroundRuntime.allowedRecovery(this)) {
            appendLog("Auto-start paused after a system stop; enable background running again in settings")
            return
        }
        if (CredentialStore.load(this) != null && GatewayService.startControl(this)) {
            appendLog("Auto-starting paired HTTPS control gateway; voice waits for explicit diagnostics")
        }
    }

    private fun restoreControlUiState() {
        val session = CredentialStore.load(this)
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        if (session != null) {
            controlUrl.setText(session.controlBaseUrl)
            controlStatus.text = "$PAIRED_STATUS_PREFIX ${session.gatewayId.take(8)}… · ${backgroundSyncLabel()}"
        } else {
            prefs.getString("control_base_url", "").orEmpty().takeIf { it.isNotEmpty() }?.let { controlUrl.setText(it) }
            controlStatus.text = UNPAIRED_STATUS
        }
        controlDeviceName.setText(prefs.getString("control_device_name", Build.MODEL ?: "旧手机"))
        renderPairedSummary()
    }

    private fun setControlBusy(busy: Boolean, pairingStatus: String? = null, simMessage: String? = null) {
        controlBusy = busy
        controlPairButton.isEnabled = !busy
        simProposeTile.isEnabled = !busy
        simProposeTile.alpha = if (busy) 0.5f else 1f
        simConfirm.isEnabled = !busy
        pendingMappingProposal?.let { renderSimProposal(it, simProposalSubscriptions) }
        if (pairingStatus != null) controlStatus.text = pairingStatus
        if (simMessage != null) {
            simStatus.text = simMessage
            simStatus.visibility = View.VISIBLE
        }
        if (!busy) refreshSimSummary()
    }

    private fun backgroundSyncLabel(): String = if (GatewayBackgroundRuntime.allowedRecovery(this)) {
        "HTTPS 同步已启用"
    } else {
        "HTTPS 同步已暂停 · 请在设置中启用后台运行"
    }

    /**
     * A join link lists one-time gateway pairing codes for several server
     * nodes. Unpaired, the first entry is the primary pairing (SIMs are then
     * confirmed as usual) and the rest become other nodes; paired, every
     * entry is added as another node.
     */
    private fun handleJoinLink(source: Intent?) {
        val link = source?.dataString
        if (!JoinLink.isJoinLink(link)) return
        // One-time secrets: never keep them in the intent for a recreate.
        source?.data = null
        val entries = JoinLink.parse(link!!, "gateway")
        if (entries == null) {
            Toast.makeText(this, "这个加入链接无效，或不是给旧手机用的。", Toast.LENGTH_LONG).show()
            return
        }
        val paired = CredentialStore.load(this) != null
        val hosts = entries.joinToString("\n") { "• ${it.serverUrl}" }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (paired) "添加 ${entries.size} 个节点？" else "加入 ${entries.size} 个服务器？")
            .setMessage(if (paired) hosts else "先与第一个服务器配对（随后确认 SIM），其余作为其他节点：\n$hosts")
            .setNegativeButton("取消", null)
            .setPositiveButton("加入") { _, _ ->
                if (paired) {
                    meshNodesSection.addAll(entries)
                } else {
                    controlUrl.setText(entries.first().serverUrl)
                    controlPairingCode.setText(entries.first().pairingCode)
                    pairControlGateway { meshNodesSection.addAll(entries.drop(1)) }
                }
            }
            .show()
    }

    private fun pairControlGateway(onPaired: (() -> Unit)? = null) {
        val base = controlUrl.text.toString().trim()
        val code = controlPairingCode.text.toString().trim()
        val deviceName = controlDeviceName.text.toString().trim().ifEmpty { Build.MODEL ?: "旧手机网关" }
        if (base.isEmpty() || code.isEmpty()) {
            Toast.makeText(this, "请填写服务器地址（http:// 或 https://）和一次性配对码", Toast.LENGTH_LONG).show()
            return
        }
        val normalizedBase = base.trimEnd('/')
        setControlBusy(true, "正在配对…")
        Thread({
            try {
                val result = ControlApiClient(this, normalizedBase).pair(code, deviceName)
                val saved = getSharedPreferences("gateway", MODE_PRIVATE).edit()
                    .putString("control_base_url", normalizedBase)
                    .putString("control_device_name", deviceName)
                    .commit()
                if (!saved) throw IllegalStateException("无法保存服务器设置")
                runOnUiThread {
                    controlPairingCode.text?.clear()
                    val syncEnabled = GatewayBackgroundRuntime.allowedRecovery(this)
                    val syncStarted = syncEnabled && GatewayService.startControl(this)
                    if (syncStarted) startService(Intent(this, GatewayService::class.java).apply {
                        action = GatewayService.ACTION_APPLY_CONFIG
                    })
                    pairingFormOpen = false
                    setControlBusy(false, "$PAIRED_STATUS_PREFIX ${result.gatewayId.take(8)}… · ${backgroundSyncLabel()}")
                    val message = when {
                        syncStarted -> "已配对。请核对并确认本地 SIM 插槽。"
                        !syncEnabled -> "已配对，后台同步仍暂停。请在设置中启用后台运行，再确认本地 SIM 插槽。"
                        else -> "已配对，但后台服务未能启动。请查看后台状态后重试。"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    appendLog(when {
                        syncStarted -> "网关已配对，后台同步已启用"
                        !syncEnabled -> "网关已配对；后台同步仍暂停，请在设置中启用"
                        else -> "网关已配对，但后台服务未能启动"
                    })
                    refreshBackgroundStatus()
                    refreshSettings()
                    openSettingsSection(simProposeTile)
                    onPaired?.invoke()
                }
            } catch (e: Exception) {
                val detail = (e as? ControlApiException)?.code ?: "PAIRING_FAILED"
                runOnUiThread {
                    setControlBusy(false, "配对失败：$detail")
                    Toast.makeText(this, "配对失败：$detail", Toast.LENGTH_LONG).show()
                }
            }
        }, "gateway-pair").start()
    }

    // ── SIM binding ──────────────────────────────────────

    private fun proposeSimBindings() {
        val session = CredentialStore.load(this)
        if (session == null) {
            Toast.makeText(this, "请先配对网关", Toast.LENGTH_LONG).show()
            return
        }
        if (controlBusy) return
        val operationId = UUID.randomUUID().toString()
        val controlBase = session.controlBaseUrl
        setControlBusy(true, simMessage = "正在读取本机 SIM…")
        Thread({
            try {
                // Subscription reads stay off the UI thread; SMS never queries the voice broker.
                val snapshot = SimRegistry.snapshot(this)
                val subscriptions = snapshot.subscriptions
                if (subscriptions.isEmpty()) {
                    throw ControlApiException("SIM_UNAVAILABLE", "本机没有已启用的 SIM")
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
                    setControlBusy(false, simMessage = "服务器已分配 SIM 身份。请为每一行选择对应卡槽的本机 SIM，然后确认。")
                }
            } catch (e: Exception) {
                val detail = (e as? ControlApiException)?.code ?: "SIM_PROPOSAL_FAILED"
                runOnUiThread { setControlBusy(false, simMessage = "SIM 同步失败：$detail") }
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
                ServerMapping(m.getString("sim_id"), m.getInt("slot_index"), m.getString("state"), m.getLong("mapping_revision"))
            }
            val proposal = MappingProposal(saved.first, revision, mappings)
            val snapshot = runCatching { SimRegistry.snapshot(this) }.getOrNull() ?: return
            val selected = json.optJSONObject("selected") ?: JSONObject()
            val restoredSelections = mutableMapOf<String, SimRegistry.SimSubscription>()
            selected.keys().forEach { simId ->
                val subId = selected.optInt(simId, Int.MIN_VALUE)
                snapshot.subscriptions.firstOrNull { it.subscriptionId == subId }?.let { restoredSelections[simId] = it }
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
                refreshSetupChecklist()
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
        val selected = JSONObject().apply { selectedSimBindings.forEach { (simId, sub) -> put(simId, sub.subscriptionId) } }
        GatewayDatabase.get(this).saveSimProposal(proposal.operationId,
            JSONObject().put("mapping_revision", proposal.mappingRevision)
                .put("mappings", mappings).put("selected", selected).toString())
    }

    private fun renderSimProposal(proposal: MappingProposal, subscriptions: List<SimRegistry.SimSubscription>) {
        simBindings.removeAllViews()
        simConfirm.visibility = View.VISIBLE
        val used = selectedSimBindings.values.map { it.subscriptionId }.toMutableSet()
        proposal.mappings.forEachIndexed { index, mapping ->
            val position = GatewayUi.position(index, proposal.mappings.size)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(8))
                background = GatewayUi.tileBackground(this@MainActivity, position)
                layoutParams = GatewayUi.tileParams(this@MainActivity, position)
            }
            tile.addView(GatewayUi.title(this, "服务器 SIM ${mapping.slotIndex + 1}"))
            tile.addView(GatewayUi.subtitle(this, "${mapping.simId} · ${mapping.state}", lines = 2))
            val group = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
            val options = subscriptions.filter { it.slotIndex == mapping.slotIndex }.sortedBy { it.slotIndex }
            if (options.isEmpty()) {
                tile.addView(GatewayUi.subtitle(this, "卡槽 ${mapping.slotIndex + 1} 没有已启用的本机 SIM",
                    colorAttr = androidx.appcompat.R.attr.colorError))
            }
            options.forEach { sub ->
                val chosen = selectedSimBindings[mapping.simId]?.subscriptionId == sub.subscriptionId
                val radio = MaterialRadioButton(this).apply {
                    id = View.generateViewId()
                    minimumHeight = dp(48)
                    text = buildString {
                        append("本机 SIM ${sub.slotIndex + 1}")
                        if (sub.carrierName.isNotBlank()) append(" · ${sub.carrierName}")
                        if (sub.displayName.isNotBlank() && sub.displayName != sub.carrierName) append(" · ${sub.displayName}")
                        if (!sub.phoneNumber.isNullOrBlank()) append(" · ${sub.phoneNumber}")
                    }
                    isEnabled = !controlBusy && (sub.subscriptionId !in used || chosen)
                }
                group.addView(radio)
                if (chosen) radio.isChecked = true
                radio.setOnClickListener {
                    selectedSimBindings[mapping.simId] = sub
                    persistSimProposalSelections(proposal)
                    renderSimProposal(proposal, subscriptions)
                }
            }
            tile.addView(group)
            simBindings.addView(tile)
        }
        val selected = proposal.mappings.mapNotNull { mapping ->
            selectedSimBindings[mapping.simId]?.takeIf { it.slotIndex == mapping.slotIndex }
        }
        simConfirm.isEnabled = !controlBusy && proposal.mappings.isNotEmpty() &&
            selected.size == proposal.mappings.size && selected.map { it.subscriptionId }.distinct().size == selected.size
    }

    private fun confirmSimBindings() {
        val proposal = pendingMappingProposal ?: return
        val chosenBySimId = linkedMapOf<String, SimRegistry.SimSubscription>()
        proposal.mappings.forEach { mapping ->
            val selected = selectedSimBindings[mapping.simId]
            if (selected == null || selected.slotIndex != mapping.slotIndex) {
                Toast.makeText(this, "请为每一行选择对应卡槽的本机 SIM", Toast.LENGTH_LONG).show()
                return
            }
            chosenBySimId[mapping.simId] = selected
        }
        if (chosenBySimId.values.map { it.subscriptionId }.distinct().size != chosenBySimId.size) {
            Toast.makeText(this, "每一行必须选择不同的本机 SIM", Toast.LENGTH_LONG).show()
            return
        }
        val controlBase = CredentialStore.load(this)?.controlBaseUrl ?: run {
            Toast.makeText(this, "请先配对网关", Toast.LENGTH_LONG).show()
            return
        }
        val confirmations = JSONArray()
        proposal.mappings.forEach { mapping ->
            confirmations.put(JSONObject().put("sim_id", mapping.simId).put("confirmed", true).put("slot_index", mapping.slotIndex))
        }
        setControlBusy(true, simMessage = "正在向服务器确认 SIM 绑定…")
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
                val results = ControlApiClient(this, controlBase).confirmSimBindings(proposal.operationId, confirmations)
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
                    simBindings.removeAllViews()
                    simConfirm.visibility = View.GONE
                    val syncEnabled = GatewayBackgroundRuntime.allowedRecovery(this)
                    val syncStarted = syncEnabled && GatewayService.startControl(this)
                    val status = when {
                        syncStarted -> "SIM 映射已确认；HTTPS 同步可使用已激活的 SIM。"
                        !syncEnabled -> "SIM 映射已确认；后台同步已暂停，请在设置中启用后台运行。"
                        else -> "SIM 映射已确认；后台服务未能启动，请查看后台状态后重试。"
                    }
                    setControlBusy(false, simMessage = status)
                    refreshBackgroundStatus()
                }
            } catch (e: Exception) {
                val detail = (e as? ControlApiException)?.code ?: "SIM_CONFIRM_FAILED"
                runOnUiThread { setControlBusy(false, simMessage = "SIM 确认失败：$detail") }
            }
        }, "gateway-sim-confirm").start()
    }

    // ── SMS recovery and backup ──────────────────────────

    private fun smsBackupTile(position: GatewayUi.Position, viewId: Int): View =
        GatewayUi.tile(this, position, "短信备份与归档",
            summary = "导出加密备份、导入 SMS Backup & Restore 归档",
            icon = R.drawable.ic_ui_archive, trailing = GatewayUi.chevron(this),
            onClick = { startActivity(Intent(this, com.callagent.gateway.backup.GatewaySmsBackupActivity::class.java)) }
        ).apply {
            id = viewId
            contentDescription = "短信备份与归档"
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
        if (currentSession?.gatewayId != expectedGatewayId || currentSession.controlBaseUrl != expectedControlBaseUrl) {
            clearPendingSmsRecovery()
            Toast.makeText(this, "配对账户已变化，请重新选择补收范围", Toast.LENGTH_LONG).show(); return
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
                        Toast.makeText(this, "无法读取系统收件箱，请检查权限", Toast.LENGTH_LONG).show(); return@runOnUiThread
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
            }, "sms-recovery-preview").start()
        } else {
            clearPendingSmsRecovery()
            configureSmsRecovery(mode, expectedGatewayId, expectedControlBaseUrl)
        }
    }

    private fun configureSmsRecovery(mode: SmsProviderRecovery.HistoryMode, expectedGatewayId: String, expectedControlBaseUrl: String?) {
        Thread({
            val result = SmsProviderRecovery.configure(this, mode, expectedGatewayId = expectedGatewayId,
                expectedControlBaseUrl = expectedControlBaseUrl)
            runOnUiThread {
                renderSmsSettings()
                if (result.configured) scanSmsRecovery()
                else Toast.makeText(this, "短信补收未启用，请检查权限及配对状态", Toast.LENGTH_LONG).show()
            }
        }, "sms-recovery-configure").start()
    }

    private fun scanSmsRecovery() {
        if (!recoveryBusy.compareAndSet(false, true)) return
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
                runOnUiThread { if (!isDestroyed) renderSmsSettings() }
            }
        }, "sms-recovery-scan").start()
    }

    // ── Voice diagnostics ────────────────────────────────

    private fun openGatewayDiagnostics() {
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        content.addView(body("此检查只验证本机权限、音频采集能力和系统环境，不会拨打真实电话，也不代表完整通话链路已验证。检查通过后还需再次确认，才会启动语音服务。", selectable = false))
        content.addView(results)
        val scroll = ScrollView(this).apply {
            addView(content)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.configuration.screenHeightDp.coerceAtMost(760) * 0.55f * resources.displayMetrics.density).toInt())
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("语音本机诊断")
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
                    if (GsmCallManager.activeCall != null || bridgeState in CALL_STATES) {
                        Toast.makeText(this, "通话进行中，结束后再启动语音", Toast.LENGTH_LONG).show()
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
                    action.text = if (ready) "确认启动语音" else "重新运行诊断"
                }
            }
        }
        dialog.show()
    }

    private fun runGatewayChecks(container: LinearLayout, onDone: (Boolean) -> Unit) {
        val greenColor = color(androidx.appcompat.R.attr.colorPrimary)
        val redColor = color(androidx.appcompat.R.attr.colorError)
        val grayColor = color(MaterialR.attr.colorOnSurfaceVariant)

        fun addSectionHeader(title: String) {
            container.addView(MaterialTextView(this).apply {
                text = title
                setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
                setTextColor(greenColor)
                setPadding(0, dp(10), 0, dp(2))
            })
        }

        fun addResultRow(label: String, passed: Boolean, detail: String = "") {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(MaterialTextView(this).apply {
                text = if (passed) "✓" else "✗"
                textSize = 14f
                setTextColor(if (passed) greenColor else redColor)
            }, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(MaterialTextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (passed) greenColor else redColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (detail.isNotEmpty()) {
                row.addView(MaterialTextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(grayColor)
                })
            }
            container.addView(row)
        }

        Thread {
            data class CheckResult(val label: String, val passed: Boolean, val detail: String = "")
            fun granted(permission: String) =
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            val hasRecordAudio = granted(Manifest.permission.RECORD_AUDIO)
            val hasCaptureOutput = granted("android.permission.CAPTURE_AUDIO_OUTPUT")
            val results = listOf(
                CheckResult("录音权限", hasRecordAudio),
                CheckResult("系统通话音频采集", hasCaptureOutput, if (hasCaptureOutput) "Magisk" else "needs Magisk"),
                CheckResult("接听电话权限", granted(Manifest.permission.ANSWER_PHONE_CALLS)),
                CheckResult("拨打电话权限", granted(Manifest.permission.CALL_PHONE)),
                CheckResult("电话状态读取权限", granted(Manifest.permission.READ_PHONE_STATE)),
            )
            val telecomMgr = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefaultDialer = packageName == telecomMgr.defaultDialerPackage

            data class SourceTest(val source: Int, val name: String, val rate: Int)
            val sources = listOf(
                SourceTest(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_UPLINK, "VOICE_UPLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000),
                SourceTest(MediaRecorder.AudioSource.MIC, "MIC", 8000)
            )
            val sourceResults = sources.map { src ->
                var ok = false
                var detail = ""
                try {
                    val minBuf = AudioRecord.getMinBufferSize(src.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBuf > 0) {
                        @SuppressLint("MissingPermission")
                        val rec = AudioRecord(src.source, src.rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, minBuf.coerceAtLeast(4096))
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
                CheckResult(src.name, ok, detail)
            }
            val aecAvail = AcousticEchoCanceler.isAvailable()
            val nsAvail = NoiseSuppressor.isAvailable()

            val propResults = listOf(
                "voice.record.conc.disabled" to "Concurrent recording",
                "voice.playback.conc.disabled" to "Concurrent playback",
                "voice.voip.conc.disabled" to "Concurrent VoIP"
            ).map { (prop, label) ->
                val value = try {
                    @Suppress("PrivateApi")
                    val cls = Class.forName("android.os.SystemProperties")
                    cls.getMethod("get", String::class.java, String::class.java).invoke(null, prop, "") as String
                } catch (_: Exception) { "" }
                CheckResult(label, value == "false", value.ifEmpty { "not set" })
            }

            val hasRoot = try {
                // Modern Magisk doesn't place su at fixed paths — try executing it.
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val exitCode = proc.waitFor()
                proc.destroy()
                exitCode == 0
            } catch (_: Exception) {
                try {
                    java.io.File("/system/bin/su").exists() || java.io.File("/system/xbin/su").exists() ||
                        java.io.File("/sbin/su").exists()
                } catch (_: Exception) { false }
            }

            val hasUsableSource = sourceResults.any { it.passed }
            val hasDownlink = sourceResults.firstOrNull { it.label == "VOICE_DOWNLINK" }?.passed == true

            runOnUiThread {
                addSectionHeader("权限")
                results.forEach { addResultRow(it.label, it.passed, it.detail) }
                addResultRow("默认拨号器", isDefaultDialer, if (isDefaultDialer) "" else "required for InCallService")
                addSectionHeader("音频采集源（仅本机短时探测）")
                sourceResults.forEach { addResultRow(it.label, it.passed, it.detail) }
                addSectionHeader("音频处理")
                addResultRow("回声消除", aecAvail)
                addResultRow("降噪", nsAvail)
                addSectionHeader("系统属性")
                propResults.forEach { addResultRow(it.label, it.passed, it.detail) }
                addSectionHeader("系统环境")
                addResultRow("Magisk root", hasRoot, if (hasRoot) "" else "需要 Magisk")

                val gatewayReady = hasRecordAudio && isDefaultDialer && hasUsableSource && hasCaptureOutput
                container.addView(MaterialTextView(this).apply {
                    text = if (gatewayReady) {
                        val src = if (hasDownlink) "VOICE_DOWNLINK" else sourceResults.firstOrNull { it.passed }?.label ?: "?"
                        "✓ 本机检查通过（采集源：$src），可启动语音服务；尚未验证完整通话链路"
                    } else {
                        val missing = mutableListOf<String>()
                        if (!hasRecordAudio) missing.add("录音权限")
                        if (!hasCaptureOutput) missing.add("系统通话音频采集权限")
                        if (!isDefaultDialer) missing.add("默认拨号器角色")
                        if (!hasUsableSource) missing.add("可用音频采集源")
                        "✗ 暂不能启动语音服务：缺少 ${missing.joinToString("、")}"
                    }
                    setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
                    setTextColor(if (gatewayReady) greenColor else redColor)
                    setPadding(0, dp(12), 0, dp(4))
                })
                onDone(gatewayReady)
            }
        }.start()
    }

    private fun startGateway(): Boolean {
        if (CredentialStore.load(this) == null) {
            Toast.makeText(this, "请先配对中转服务器", Toast.LENGTH_LONG).show()
            openSettingsSection(pairingForm)
            return false
        }
        // The service checks the server's call control capability off the
        // main thread before it starts call control.
        val started = GatewayService.start(this)
        if (started) {
            voiceRunning = true
            appendLog("User confirmed voice start after device diagnostics")
            Toast.makeText(this, "正在启动语音服务", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Android 未能启动语音服务，请查看后台状态和运行日志", Toast.LENGTH_LONG).show()
        }
        return started
    }

    // ── Logs ─────────────────────────────────────────────

    private fun appendLog(msg: String) {
        appendLogRaw("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $msg")
    }

    /** Append a line that already carries its own timestamp. */
    private fun appendLogRaw(line: String) {
        runOnUiThread {
            tvLog.append("$line\n")
            if (currentTab == "logs") svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        tvLog.text = ""
        GatewayService.clearLogBuffer()
        Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show()
    }

    private fun copyLog() {
        val logText = tvLog.text.toString()
        if (logText.isEmpty()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
            return
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("homura log", logText))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    // ── Permissions ──────────────────────────────────────

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        return requestMissingPermissions(permissions, REQ_VOICE_PERMS)
    }

    private fun requestMissingPermissions(permissions: List<String>, requestCode: Int): Boolean {
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) return false
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), requestCode)
        return true
    }

    private fun requestDefaultDialerRole() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (packageName == tm.defaultDialerPackage) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_DEFAULT_DIALER)
            }
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }, REQ_DEFAULT_DIALER)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEFAULT_DIALER) {
            appendLog(if (resultCode == RESULT_OK) "Set as default phone app"
                else "WARN: Not set as default phone app — GSM call handling disabled")
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
                renderSmsSettings()
            }
        } else if (requestCode == REQ_SMS_PERMS || requestCode == REQ_VOICE_PERMS) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            permissionNotice = if (denied.isNotEmpty()) {
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
                "已拒绝：${affected.joinToString("、")}。相关功能会受限；可到系统设置的应用权限中更改。"
            } else null
            refreshSimSummary()
            refreshBackgroundStatus()
        }
    }

    // ── View helpers ─────────────────────────────────────

    /** A tile whose summary line is updated in place, e.g. by the network timer. */
    private fun summaryTile(position: GatewayUi.Position, title: String, icon: Int, onClick: () -> Unit): Pair<View, TextView> {
        val tile = GatewayUi.tile(this, position, title, summary = "—", icon = icon,
            trailing = GatewayUi.chevron(this), onClick = onClick)
        val summary = (tile.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        return tile to summary
    }

    private fun switchTile(position: GatewayUi.Position, title: String, summary: String, icon: Int,
                           switch: MaterialSwitch, onToggle: () -> Unit): View =
        GatewayUi.tile(this, position, title, summary = summary, icon = icon, trailing = switch, onClick = onToggle).apply {
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                }
            }
        }

    private fun title(text: String): TextView = MaterialTextView(this).apply {
        this.text = text
        setTextAppearance(MaterialR.style.TextAppearance_Material3_DisplaySmall)
        setTextColor(color(MaterialR.attr.colorOnSurface))
        setPadding(dp(4), dp(32), dp(8), dp(12))
    }

    /** Body text; [selectable] must be false inside clickable rows, where selection would swallow the tap. */
    private fun body(text: String, selectable: Boolean = true): TextView = MaterialTextView(this).apply {
        this.text = text
        setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
        setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
        setPadding(dp(4), dp(4), dp(4), dp(8))
        setTextIsSelectable(selectable)
    }

    private fun monospace(text: String): TextView = MaterialTextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(color(MaterialR.attr.colorOnSurface))
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }

    private fun inputField(hint: String, inputTypeValue: Int): TextInputLayout = TextInputLayout(this).apply {
        this.hint = hint
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
        layoutParams = fullWidthParams(top = 4, bottom = 8)
        addView(TextInputEditText(this@MainActivity).apply {
            inputType = inputTypeValue
            textSize = 16f
            minHeight = dp(56)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun button(text: String): MaterialButton = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(48)
        cornerRadius = dp(24)
        layoutParams = fullWidthParams(top = 6, bottom = 6)
    }

    private fun fullWidthParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun stepText(step: Int) = if (step > 0) "+$step" else step.toString()

    private fun formatElapsed(elapsed: Long): String = if (elapsed >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
    } else {
        String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
    }

    private fun color(attr: Int): Int = GatewayUi.color(this, attr)

    private fun dp(value: Int): Int = GatewayUi.dp(this, value)

    companion object {
        private const val PAIRED_STATUS_PREFIX = "已配对网关"
        private const val UNPAIRED_STATUS = "未配对"
        private val CALL_STATES = setOf("GSM_RINGING", "GSM_DIALING", "BRIDGED", "TEARING_DOWN")
        private const val MAX_TRAFFIC_ROWS = 50
        private const val STATE_CURRENT_TAB = "ui.current_tab"
        private const val STATE_LOGS_RETURN_TAB = "ui.logs_return_tab"
        private const val STATE_CALL_FILTER = "ui.call_filter"
        private const val STATE_CONTROL_URL = "ui.control_url"
        private const val STATE_CONTROL_DEVICE_NAME = "ui.control_device_name"
        private const val STATE_PAIRING_FORM_OPEN = "ui.pairing_form_open"
        private const val STATE_CFG_SCROLL_Y = "ui.cfg_scroll_y"
        private const val STATE_CONFIG_FOCUSED_FIELD_ID = "ui.config_focused_field_id"
        private const val STATE_CONFIG_SELECTION_START = "ui.config_selection_start"
        private const val STATE_CONFIG_SELECTION_END = "ui.config_selection_end"
        private const val STATE_CONFIG_IME_VISIBLE = "ui.config_ime_visible"
        private const val STATE_RECOVERY_MODE = "ui.recovery_mode"
        private const val STATE_RECOVERY_GATEWAY_ID = "ui.recovery_gateway_id"
        private const val STATE_RECOVERY_CONTROL_BASE_URL = "ui.recovery_control_base_url"
        private const val STATE_RECOVERY_PERMISSION_IN_FLIGHT = "ui.recovery_permission_in_flight"
        private const val REQ_SMS_PERMS = 100
        private const val REQ_DEFAULT_DIALER = 101
        private const val REQ_VOICE_PERMS = 102
        private const val REQ_SMS_RECOVERY = 103
        private val CONFIG_RESTORABLE_FOCUS_FIELD_IDS = setOf(R.id.etControlUrl, R.id.etControlDeviceName)
    }
}
