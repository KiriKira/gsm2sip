package com.callagent.gateway.ui

import android.app.Activity
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import com.callagent.gateway.R
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.mesh.JoinEntry
import com.callagent.gateway.mesh.MeshPairing
import com.callagent.gateway.net.ControlApiException
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings rows for secondary mesh nodes: status, add (server URL plus a
 * gateway pairing code from that node) and remove. Network calls run on a
 * background thread; [onChanged] wakes the control service.
 */
class MeshNodesSection(
    private val activity: Activity,
    private val container: LinearLayout,
    private val deviceName: () -> String,
    private val onChanged: () -> Unit
) {
    fun render() {
        val nodes = runCatching { GatewayDatabase.get(activity).meshNodes() }.getOrDefault(emptyList())
        container.removeAllViews()
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val count = nodes.size + 1
        nodes.forEachIndexed { index, node ->
            val sync = when {
                node.lastError.isNotEmpty() -> "同步失败（${node.lastError}）"
                node.lastSyncAt != null -> "已同步 · ${time.format(Date(node.lastSyncAt))}"
                else -> "等待首次同步"
            }
            val remove = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "移除"
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { confirmRemove(node) }
            }
            container.addView(GatewayUi.tile(activity, GatewayUi.position(index, count), node.label,
                summary = sync, icon = R.drawable.ic_ui_server, trailing = remove))
        }
        container.addView(GatewayUi.tile(activity, GatewayUi.position(count - 1, count), "添加节点",
            summary = if (nodes.isEmpty()) "尚未添加其他节点" else null,
            icon = R.drawable.ic_ui_add, onClick = { showAddDialog() }).apply { id = R.id.btnMeshAdd })
    }

    private fun showAddDialog() {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val url = field("节点地址（http:// 或 https://）", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val code = field("该节点生成的 gateway 配对码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(url.first)
        form.addView(code.first)
        MaterialAlertDialogBuilder(activity)
            .setTitle("添加节点")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                val base = url.second.text?.toString()?.trim().orEmpty()
                val pairing = code.second.text?.toString()?.trim().orEmpty()
                if (base.isEmpty() || pairing.isEmpty()) {
                    Toast.makeText(activity, "请填写节点地址和配对码", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Thread({
                    val result = runCatching { MeshPairing.add(activity, base, pairing, deviceName()) }
                    activity.runOnUiThread {
                        result.onSuccess {
                            Toast.makeText(activity, "已添加节点 ${it.host}，将自动绑定 SIM", Toast.LENGTH_LONG).show()
                            onChanged()
                        }.onFailure { error ->
                            val detail = (error as? ControlApiException)?.message ?: "无法连接该节点"
                            Toast.makeText(activity, "添加失败：$detail", Toast.LENGTH_LONG).show()
                        }
                        render()
                    }
                }, "mesh-add").start()
            }
            .show()
    }

    /** Add several nodes from a join link; reports one line per node. */
    fun addAll(entries: List<JoinEntry>) {
        if (entries.isEmpty()) return
        Toast.makeText(activity, "正在添加 ${entries.size} 个节点…", Toast.LENGTH_SHORT).show()
        Thread({
            val lines = entries.map { entry ->
                runCatching { MeshPairing.add(activity, entry.serverUrl, entry.pairingCode, deviceName()) }.fold(
                    onSuccess = { "${it.host}：已添加" },
                    onFailure = { error -> "${entry.serverUrl}：未添加（${(error as? ControlApiException)?.message ?: "无法连接该节点"}）" }
                )
            }
            activity.runOnUiThread {
                MaterialAlertDialogBuilder(activity).setTitle("加入节点").setMessage(lines.joinToString("\n"))
                    .setPositiveButton("好", null).show()
                render()
                onChanged()
            }
        }, "mesh-join").start()
    }

    private fun confirmRemove(node: GatewayDatabase.MeshNode) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("移除节点 ${node.label}？")
            .setMessage("旧手机将不再向该节点上报短信或接受其命令。尚未上传到该节点的短信不会转给其他节点。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                Thread({
                    runCatching { MeshPairing.remove(activity, node.gatewayId) }
                    activity.runOnUiThread { render(); onChanged() }
                }, "mesh-remove").start()
            }
            .show()
    }

    private fun field(hint: String, type: Int): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(activity).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            inputType = type
            isSaveEnabled = false
            // Not setSingleLine(): it would replace the password
            // transformation of the pairing code field.
            maxLines = 1
        }
        layout.addView(edit)
        return layout to edit
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
