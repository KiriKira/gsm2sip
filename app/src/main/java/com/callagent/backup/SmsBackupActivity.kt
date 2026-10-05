package com.callagent.backup

import android.content.Context
import android.content.DialogInterface
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import androidx.core.util.Consumer
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

data class RetentionToggleConfig(
    val label: String,
    val description: String,
    val checked: Boolean,
)

/**
 * Shared Material 3 backup/restore screen. Subclasses provide only a stateless app-level live
 * record provider and may add their gateway-specific retention preference.
 */
abstract class SmsBackupActivity : AppCompatActivity() {
    private enum class PickerAction { NONE, IMPORT, EXPORT_ENCRYPTED, EXPORT_JSON, EXPORT_XML }

    private var pendingPickerAction = PickerAction.NONE
    private lateinit var viewModel: SmsBackupViewModel
    private lateinit var paneHost: PaneHostFrameLayout
    private lateinit var formScroll: ScrollView
    private lateinit var statusText: TextView
    private lateinit var archiveSummary: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: MaterialButton
    private lateinit var previewCard: LinearLayout
    private lateinit var archiveRowsCard: LinearLayout
    private lateinit var archiveToggleButton: MaterialButton
    private lateinit var loadMoreButton: MaterialButton
    private val actionButtons = ArrayList<MaterialButton>()
    private var passwordDialogKind: PasswordPromptKind? = null
    private var currentFold: Rect? = null
    private var currentFoldOrientation: FoldingFeature.Orientation? = null
    private var listeningForWindowInfo = false
    private val maxFormWidthPx by lazy { dp(MAX_FORM_WIDTH_DP) }

    /** Return a singleton/stateless provider. Do not return a closure over this Activity. */
    protected open fun recordProvider(): SmsArchiveRecordProvider = EmptySmsArchiveRecordProvider

    /** Override for a gateway-only receive-retention switch; hosts can leave this unset. */
    protected open fun retentionToggleConfig(): RetentionToggleConfig? = null

    /** Called on the main thread after the optional retention switch changes. */
    protected open fun onRetentionChanged(enabled: Boolean) = Unit

    private val encryptedCreate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        onExportDocumentResult(SmsBackupExportFormat.ENCRYPTED, uri)
    }
    private val jsonCreate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        onExportDocumentResult(SmsBackupExportFormat.JSON, uri)
    }
    private val xmlCreate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        onExportDocumentResult(SmsBackupExportFormat.XML, uri)
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPickerAction = PickerAction.NONE
        if (uri == null) {
            showMessage("已取消选择文件。", isError = false)
        } else {
            viewModel.inspectImport(uri)
        }
    }

    private val windowInfoTracker by lazy {
        WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this))
    }
    private val windowLayoutConsumer = Consumer<WindowLayoutInfo> { info ->
        if (!listeningForWindowInfo || isDestroyed || isFinishing) return@Consumer
        val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull { it.isSeparating }
        currentFold = feature?.bounds?.let { Rect(it) }
        currentFoldOrientation = feature?.orientation
        paneHost.post { updatePaneLayout() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPickerAction = savedInstanceState?.getString(STATE_PICKER_ACTION)
            ?.let { runCatching { PickerAction.valueOf(it) }.getOrNull() }
            ?: PickerAction.NONE
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val provider = recordProvider()
                return SmsBackupViewModel(
                    context = applicationContext,
                    store = SmsArchiveStore(applicationContext),
                    recordProvider = provider,
                ) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[SmsBackupViewModel::class.java]
        buildScreen()
        viewModel.state.observe(this, Observer(::renderState))
        ViewCompat.requestApplyInsets(paneHost)
    }

    override fun onStart() {
        super.onStart()
        listeningForWindowInfo = true
        windowInfoTracker.addWindowLayoutInfoListener(
            this,
            ContextCompat.getMainExecutor(this),
            windowLayoutConsumer,
        )
    }

    override fun onStop() {
        listeningForWindowInfo = false
        windowInfoTracker.removeWindowLayoutInfoListener(windowLayoutConsumer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PICKER_ACTION, pendingPickerAction.name)
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        paneHost = PaneHostFrameLayout(this)
        formScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        formScroll.addView(form, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paneHost.addView(formScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(paneHost)

        ViewCompat.setOnApplyWindowInsetsListener(paneHost) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            view.post { updatePaneLayout() }
            insets
        }
        paneHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePaneLayout() }

        addText(form, "短信备份与归档", 28, bold = true)
        addText(
            form,
            "备份包含本机已同步记录和此设备上的归档。服务器尚未同步的历史短信不会出现在文件中。",
            14,
            top = 6,
        )

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "备份处理中"
        }
        form.addView(progressBar, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            topMargin = dp(18)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        statusText = addText(form, "", 14, top = 8)
        cancelButton = button(form, "取消当前操作", allowWhileBusy = true, track = false) {
            viewModel.cancelCurrentOperation()
        }.apply { visibility = View.GONE }

        addText(form, "导出", 20, bold = true, top = 20)
        button(form, "加密备份") {
            viewModel.beginEncryptedExport()
        }
        addText(form, "使用便携密码加密。密码至少 8 个字符，遗失后无法恢复。", 13, top = 0)
        button(form, "导出 JSON") {
            confirmPlaintextExport(SmsBackupExportFormat.JSON)
        }
        button(form, "导出 XML") {
            confirmPlaintextExport(SmsBackupExportFormat.XML)
        }
        addText(form, "JSON 和 XML 会包含短信正文。保存明文文件前请确认存放位置安全。", 13, top = 0)

        addText(form, "导入", 20, bold = true, top = 20)
        button(form, "选择备份文件并预览") {
            pendingPickerAction = PickerAction.IMPORT
            openDocument.launch(arrayOf("*/*"))
        }
        addText(form, "导入只写入此设备的只读归档，不会创建发送任务、确认短信或写入系统短信。", 13, top = 0)

        previewCard = verticalCard(form, top = 14)
        setCardVisibility(previewCard, visible = false)

        addText(form, "导入归档", 20, bold = true, top = 22)
        addText(form, "这里只展示导入的历史；导出文件还包含本机运行缓存和网关已保留的短信。", 13, top = 4)
        archiveSummary = addText(form, "已导入归档 0 条", 14, top = 4)
        archiveToggleButton = button(form, "查看归档") {
            viewModel.toggleArchiveRows()
        }
        archiveRowsCard = verticalCard(form, top = 8)
        setCardVisibility(archiveRowsCard, visible = false)
        loadMoreButton = button(form, "加载更多") {
            viewModel.loadMoreArchiveRows()
        }.apply { visibility = View.GONE }

        retentionToggleConfig()?.let { config ->
            addText(form, "接收时保留", 20, bold = true, top = 22)
            val switch = MaterialSwitch(this).apply {
                text = config.label
                isChecked = config.checked
                setOnCheckedChangeListener { _, checked -> onRetentionChanged(checked) }
            }
            form.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
            addText(form, config.description, 13, top = 2)
        }
    }

    private fun renderState(state: SmsBackupUiState) {
        progressBar.visibility = if (state.busy) View.VISIBLE else View.GONE
        cancelButton.visibility = if (state.busy) View.VISIBLE else View.GONE
        cancelButton.isEnabled = state.busy
        actionButtons.forEach { it.isEnabled = !state.busy }
        statusText.text = buildString {
            state.message?.let { append(it) }
            if (state.busy && state.progress > 0L) {
                if (isNotEmpty()) append('\n')
                append("已处理 ${state.progress} 条…")
            }
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) View.GONE else View.VISIBLE
        statusText.setTextColor(resolveColor(if (state.messageIsError) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))

        archiveSummary.text = "已导入归档 ${state.archiveCount} 条"
        archiveToggleButton.text = if (state.showArchiveRows) "隐藏归档" else "查看归档"
        setCardVisibility(archiveRowsCard, state.showArchiveRows)
        loadMoreButton.visibility = if (state.showArchiveRows && state.hasMoreArchiveRows) View.VISIBLE else View.GONE
        loadMoreButton.isEnabled = !state.busy && state.hasMoreArchiveRows
        loadMoreButton.text = "加载更多（已显示 ${state.archiveRows.size} 条，共 ${state.archiveCount} 条）"
        renderArchiveRows(state.archiveRows)
        renderImportPreview(state.preview, state.busy)
        maybeShowPasswordDialog(state.passwordPrompt)
    }

    private fun renderImportPreview(preview: ArchivePreview?, busy: Boolean) {
        previewCard.removeAllViews()
        if (preview == null) {
            setCardVisibility(previewCard, visible = false)
            return
        }
        setCardVisibility(previewCard, visible = true)
        addText(previewCard, "导入预览", 18, bold = true)
        addText(previewCard, "记录 ${preview.total} 条", 14, top = 4)
        if (preview.sourceCounts.isEmpty()) {
            addText(previewCard, "来源：无记录", 13, top = 2)
        } else {
            val sources = preview.sourceCounts.entries
                .sortedByDescending { it.value }
                .take(MAX_PREVIEW_SOURCES)
                .joinToString("、") { "${it.key} ${it.value} 条" }
            addText(previewCard, "来源：$sources", 13, top = 2)
            if (preview.sourceCounts.size > MAX_PREVIEW_SOURCES) {
                addText(previewCard, "另有 ${preview.sourceCounts.size - MAX_PREVIEW_SOURCES} 个来源", 13, top = 2)
            }
        }
        addText(previewCard, "确认后将原子合并到本机只读归档；来源、账户与 SIM 不会绑定到当前设置。", 13, top = 6)
        button(previewCard, if (busy) "正在合并…" else "确认导入", track = false) { viewModel.applyImport() }.apply { isEnabled = !busy }
        button(previewCard, "取消导入", track = false) { viewModel.cancelImportPreview() }.apply { isEnabled = !busy }
    }

    private fun renderArchiveRows(records: List<SmsArchiveRecord>) {
        archiveRowsCard.removeAllViews()
        if (records.isEmpty()) {
            addText(archiveRowsCard, "归档中没有记录。", 14)
            return
        }
        records.forEachIndexed { index, record ->
            if (index > 0) addDivider(archiveRowsCard)
            val sim = record.simLabel?.takeIf { it.isNotBlank() }
                ?: record.simId?.takeIf { it.isNotBlank() }
                ?: "SIM 未知"
            val direction = if (record.direction == "inbound") "收件" else "发件"
            val occurredAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.createdAt))
            addText(archiveRowsCard, "${record.source} · $sim · $direction", 14, bold = true)
            addText(archiveRowsCard, "${record.from ?: "—"} → ${record.to ?: "—"}", 14, top = 2)
            addText(archiveRowsCard, "$occurredAt · ${record.status} · 只读归档", 13, top = 2)
            val previewBody = if (record.body.length > 1000) record.body.take(1000) + "\n…" else record.body
            addText(archiveRowsCard, previewBody, 14, top = 6).setTextIsSelectable(true)
            if (record.body.length > 1000) {
                button(archiveRowsCard, "查看完整短信", track = false) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("只读短信归档")
                        .setMessage(record.body)
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }
        }
    }

    private fun confirmPlaintextExport(format: SmsBackupExportFormat) {
        MaterialAlertDialogBuilder(this)
            .setTitle("导出明文短信？")
            .setMessage("${format.name} 文件会包含短信正文。请确认你愿意把这些私人内容写入所选位置。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续选择位置") { _, _ -> launchCreateDocument(format) }
            .show()
    }

    private fun maybeShowPasswordDialog(kind: PasswordPromptKind?) {
        if (kind == null) {
            passwordDialogKind = null
            return
        }
        if (passwordDialogKind == kind || isFinishing || isDestroyed) return
        passwordDialogKind = kind
        val isExport = kind == PasswordPromptKind.ENCRYPTED_EXPORT
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val passwordField = passwordInput(if (isExport) "设置密码（至少 8 个字符）" else "备份密码")
        fields.addView(passwordField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val confirmationField = if (isExport) passwordInput("再次输入密码") else null
        confirmationField?.let { fields.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isExport) "设置加密备份密码" else "输入备份密码")
            .setMessage(if (isExport) "密码只保留在内存中，遗失后无法恢复。" else "密码用于验证此备份文件，不会写入应用数据。")
            .setView(fields)
            .setNegativeButton("取消") { _, _ -> cancelPasswordPrompt(kind) }
            .setPositiveButton(if (isExport) "继续" else "读取并预览", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = passwordField.text?.let { it.copyChars() } ?: CharArray(0)
                if (isExport && value.size < SmsBackupViewModel.MIN_PASSWORD_LENGTH) {
                    passwordField.error = "密码至少需要 8 个字符"
                    value.fill('\u0000')
                    return@setOnClickListener
                }
                if (confirmationField != null) {
                    val confirmation = confirmationField.text?.let { it.copyChars() } ?: CharArray(0)
                    if (!value.contentEquals(confirmation)) {
                        confirmationField.error = "两次输入的密码不一致"
                        value.fill('\u0000')
                        confirmation.fill('\u0000')
                        return@setOnClickListener
                    }
                    confirmation.fill('\u0000')
                }
                passwordField.text?.clear()
                confirmationField?.text?.clear()
                if (isExport) {
                    val destinationAlreadyChosen = viewModel.hasPendingExportDestination()
                    if (viewModel.submitEncryptedExportPassword(value)) {
                        passwordDialogKind = null
                        dialog.dismiss()
                        if (!destinationAlreadyChosen) launchCreateDocument(SmsBackupExportFormat.ENCRYPTED)
                    }
                } else {
                    viewModel.submitImportPassword(value)
                    passwordDialogKind = null
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnCancelListener {
            cancelPasswordPrompt(kind)
        }
        dialog.setOnDismissListener {
            passwordField.text?.clear()
            confirmationField?.text?.clear()
        }
        dialog.show()
        passwordField.requestFocus()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    private fun passwordInput(hint: String) = TextInputEditText(this).apply {
        this.hint = hint
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // setSingleLine can install a plain-text transformation. Keep masking last.
        transformationMethod = PasswordTransformationMethod.getInstance()
        isSaveEnabled = false
        setSaveFromParentEnabled(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun cancelPasswordPrompt(kind: PasswordPromptKind) {
        passwordDialogKind = null
        if (kind == PasswordPromptKind.ENCRYPTED_EXPORT) viewModel.cancelPendingExport()
        else viewModel.cancelImportPreview()
    }

    private fun launchCreateDocument(format: SmsBackupExportFormat) {
        val action = when (format) {
            SmsBackupExportFormat.ENCRYPTED -> PickerAction.EXPORT_ENCRYPTED
            SmsBackupExportFormat.JSON -> PickerAction.EXPORT_JSON
            SmsBackupExportFormat.XML -> PickerAction.EXPORT_XML
        }
        pendingPickerAction = action
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val filename = "sms-backup-$stamp.${format.extension}"
        when (format) {
            SmsBackupExportFormat.ENCRYPTED -> encryptedCreate.launch(filename)
            SmsBackupExportFormat.JSON -> jsonCreate.launch(filename)
            SmsBackupExportFormat.XML -> xmlCreate.launch(filename)
        }
    }

    private fun onExportDocumentResult(format: SmsBackupExportFormat, uri: android.net.Uri?) {
        pendingPickerAction = PickerAction.NONE
        if (uri == null) {
            if (format == SmsBackupExportFormat.ENCRYPTED) viewModel.cancelPendingExport()
            showMessage("已取消保存位置选择。", isError = false)
            return
        }
        viewModel.export(uri, format)
    }

    private fun showMessage(message: String, isError: Boolean) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        statusText.setTextColor(resolveColor(if (isError) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun updatePaneLayout() {
        val host = if (::paneHost.isInitialized) paneHost else return
        val scroll = if (::formScroll.isInitialized) formScroll else return
        if (host.width <= 0 || host.height <= 0) return

        val safeLeft = host.paddingLeft
        val safeTop = host.paddingTop
        val safeRight = host.width - host.paddingRight
        val safeBottom = host.height - host.paddingBottom
        if (safeRight <= safeLeft || safeBottom <= safeTop) return
        val hostLocation = IntArray(2)
        host.getLocationInWindow(hostLocation)
        val fold = currentFold
        val clearance = dp(HINGE_CLEARANCE_DP)

        var paneLeft = safeLeft
        var paneTop = safeTop
        var paneRight = safeRight
        var paneBottom = safeBottom
        if (fold != null && currentFoldOrientation != null) {
            val left = fold.left - hostLocation[0]
            val right = fold.right - hostLocation[0]
            val top = fold.top - hostLocation[1]
            val bottom = fold.bottom - hostLocation[1]
            if (currentFoldOrientation == FoldingFeature.Orientation.VERTICAL) {
                val leftEnd = min(safeRight, left - clearance)
                val rightStart = maxOf(safeLeft, right + clearance)
                val leftWidth = (leftEnd - safeLeft).coerceAtLeast(0)
                val rightWidth = (safeRight - rightStart).coerceAtLeast(0)
                if (leftWidth >= rightWidth) {
                    paneRight = leftEnd
                } else {
                    paneLeft = rightStart
                }
            } else {
                val topEnd = min(safeBottom, top - clearance)
                val bottomStart = maxOf(safeTop, bottom + clearance)
                val topHeight = (topEnd - safeTop).coerceAtLeast(0)
                val bottomHeight = (safeBottom - bottomStart).coerceAtLeast(0)
                if (topHeight >= bottomHeight) {
                    paneBottom = topEnd
                } else {
                    paneTop = bottomStart
                }
            }
        }

        val contentWidth = (paneRight - paneLeft).coerceAtLeast(1)
        val contentHeight = (paneBottom - paneTop).coerceAtLeast(1)
        val childWidth = min(maxFormWidthPx, contentWidth)
        val params = FrameLayout.LayoutParams(childWidth, contentHeight).apply {
            leftMargin = (paneLeft - safeLeft + (contentWidth - childWidth) / 2).coerceAtLeast(0)
            topMargin = (paneTop - safeTop).coerceAtLeast(0)
            gravity = Gravity.TOP or Gravity.LEFT
        }
        val old = scroll.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != params.width || old.height != params.height || old.leftMargin != params.leftMargin || old.topMargin != params.topMargin) {
            scroll.layoutParams = params
        }
    }

    private fun button(
        parent: LinearLayout,
        title: String,
        allowWhileBusy: Boolean = false,
        track: Boolean = true,
        action: () -> Unit,
    ): MaterialButton =
        MaterialButton(this).apply {
            text = title
            minHeight = dp(56)
            setAllCaps(false)
            setOnClickListener {
                if (allowWhileBusy || !viewModel.state.value?.busy.orFalse()) action()
            }
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            if (track) actionButtons += this
        }

    private fun verticalCard(parent: LinearLayout, top: Int): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(1).toFloat()
            useCompatPadding = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        })
        return content
    }

    private fun setCardVisibility(content: LinearLayout, visible: Boolean) {
        content.visibility = View.VISIBLE
        (content.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun addText(parent: LinearLayout, value: String, sizeSp: Int, bold: Boolean = false, top: Int = 0): TextView =
        MaterialTextView(this).apply {
            text = value
            textSize = sizeSp.toFloat()
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(top)
            })
        }

    private fun addDivider(parent: LinearLayout) {
        View(this).apply {
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            })
        }
    }

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Boolean?.orFalse(): Boolean = this ?: false

    private fun CharSequence.copyChars(): CharArray = CharArray(length) { index -> this[index] }

    private class PaneHostFrameLayout(context: Context) : FrameLayout(context) {
        class LayoutParams(width: Int, height: Int) : FrameLayout.LayoutParams(width, height)
    }

    companion object {
        private const val STATE_PICKER_ACTION = "smsBackup.pickerAction"
        private const val MAX_FORM_WIDTH_DP = 840
        private const val HINGE_CLEARANCE_DP = 12
        private const val MAX_PREVIEW_SOURCES = 8
    }
}
