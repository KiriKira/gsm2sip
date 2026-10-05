package com.callagent.gateway.backup

import android.widget.Toast
import com.callagent.backup.RetentionToggleConfig
import com.callagent.backup.SmsArchiveRecordProvider
import com.callagent.backup.SmsBackupActivity
import com.callagent.gateway.data.GatewayDatabase

/** Gateway-specific entry points around the shared archive UI and import/export workflow. */
class GatewaySmsBackupActivity : SmsBackupActivity() {
    protected override fun recordProvider(): SmsArchiveRecordProvider = GatewayRecordProvider

    protected override fun retentionToggleConfig(): RetentionToggleConfig = RetentionToggleConfig(
        label = "保留本机短信归档",
        description = "开启后，新收到的短信和服务器下发的短信正文会在本机长期保存。服务器 ACK 仍会清除运行队列中的正文，但不会删除此归档。之前已经 ACK 并清除的正文无法恢复；可从主机导入 XML 或 JSON 归档。",
        checked = GatewayDatabase.get(applicationContext).smsArchiveRetentionEnabled()
    )

    protected override fun onRetentionChanged(enabled: Boolean) {
        try {
            GatewayDatabase.get(applicationContext).setSmsArchiveRetentionEnabled(enabled)
        } catch (_: Exception) {
            Toast.makeText(this, "短信归档设置未能保存", Toast.LENGTH_LONG).show()
        }
    }
}
