package com.callagent.backup

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Export and restore of a whole app: settings, pairings and local data in one
 * password-sealed file. The app supplies its [FullBackupProfile].
 */
abstract class FullBackupActivity : AppCompatActivity() {
    protected abstract fun profile(): FullBackupProfile
    protected abstract val appName: String

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var exportButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private var pendingPassword: CharArray? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = pendingPassword.also { pendingPassword = null }
        if (uri != null && password != null) export(uri, password) else password?.fill(' ')
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askPassword(confirm = false) { password -> stage(uri, password) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        column.addView(TextView(this).apply {
            text = "全量备份包含${appName}的全部设置、所有节点的配对凭据和本机数据（短信、通话记录等），" +
                "整个文件用你设置的密码加密，请妥善保管文件和密码，忘记密码将无法恢复。\n\n" +
                "恢复会覆盖此手机上${appName}的全部数据，然后自动重启。恢复到另一台手机后，" +
                "原手机上的同一配对会在下次刷新登录时失效，不能两台手机同时使用。"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        })
        exportButton = MaterialButton(this).apply {
            text = "导出全量备份"
            setOnClickListener { askPassword(confirm = true) { password -> pendingPassword = password; createDocument.launch(defaultFileName()) } }
        }
        importButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "从备份恢复"
            setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        }
        progress = LinearProgressIndicator(this).apply { isIndeterminate = true; visibility = android.view.View.GONE }
        status = TextView(this).apply {
            gravity = Gravity.START
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        listOf(exportButton, importButton, progress, status).forEach { view ->
            column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = "全量备份与恢复"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })
        root.addView(ScrollView(this).apply { addView(column) })
        setContentView(root)
    }

    override fun onDestroy() {
        worker.shutdown()
        pendingPassword?.fill(' ')
        super.onDestroy()
    }

    private fun askPassword(confirm: Boolean, onPassword: (CharArray) -> Unit) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        fun field(hint: String) = TextInputLayout(this).apply {
            this.hint = hint
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(TextInputEditText(context).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
        }
        val first = field(if (confirm) "设置备份密码（至少 ${FullBackupCodec.MIN_PASSWORD_CHARS} 位）" else "备份密码")
        val second = field("再次输入密码").takeIf { confirm }
        form.addView(first); second?.let(form::addView)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (confirm) "设置备份密码" else "输入备份密码")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                val password = first.editText?.text?.toString().orEmpty()
                when {
                    password.length < FullBackupCodec.MIN_PASSWORD_CHARS && confirm -> show("密码至少 ${FullBackupCodec.MIN_PASSWORD_CHARS} 位。")
                    second != null && second.editText?.text?.toString() != password -> show("两次输入的密码不一致。")
                    password.isEmpty() -> show("请输入密码。")
                    else -> onPassword(password.toCharArray())
                }
            }
            .show()
    }

    private fun export(uri: Uri, password: CharArray) = run("正在导出（加密需要几秒）…") {
        try {
            val output = contentResolver.openOutputStream(uri, "w") ?: throw FullBackupException("无法写入所选文件")
            FullBackup.export(this, profile(), output, password)
            "已导出全量备份。"
        } finally {
            password.fill(' ')
        }
    }

    private fun stage(uri: Uri, password: CharArray) = run("正在校验备份…") {
        val staged = try {
            val input = contentResolver.openInputStream(uri) ?: throw FullBackupException("无法读取所选文件")
            input.use { FullBackup.stage(this, profile(), it, password) }
        } finally {
            password.fill(' ')
        }
        main.post { confirmRestore(staged) }
        "备份校验通过。"
    }

    private fun confirmRestore(staged: FullBackup.Staged) {
        val created = staged.manifest.optLong("created_at").takeIf { it > 0 }
            ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) } ?: "未知时间"
        val version = staged.manifest.optString("app_version").ifBlank { "未知版本" }
        MaterialAlertDialogBuilder(this)
            .setTitle("用这份备份覆盖本机数据？")
            .setMessage("备份时间：$created\n备份版本：$version\n${staged.description}\n\n" +
                "此手机上${appName}现有的设置、配对和数据都会被替换，随后${appName}会自动重启。")
            .setNegativeButton("取消") { _, _ -> FullBackup.discardStaged(this) }
            .setPositiveButton("恢复并重启") { _, _ ->
                FullBackup.commit(this)
                FullBackup.restart(this)
            }
            .setCancelable(false)
            .show()
    }

    private fun run(working: String, task: () -> String) {
        setBusy(true, working)
        worker.execute {
            val result = runCatching(task)
            main.post {
                setBusy(false, result.getOrElse { failure ->
                    (failure as? FullBackupException)?.message ?: "操作失败：${failure.javaClass.simpleName}"
                })
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        exportButton.isEnabled = !busy
        importButton.isEnabled = !busy
        progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        show(message)
    }

    private fun show(message: String) { status.text = message }

    private fun defaultFileName(): String =
        "${profile().appId}-full-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.mahogsm"

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
