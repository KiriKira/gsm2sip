package com.callagent.gateway.backup

import android.content.Context
import com.callagent.backup.FullBackupActivity
import com.callagent.backup.FullBackupProfile
import com.callagent.gateway.data.SessionVault
import com.callagent.gateway.data.decodeSession
import com.callagent.gateway.data.encodeSession
import org.json.JSONObject
import java.io.File

class GatewayFullBackupActivity : FullBackupActivity() {
    override fun profile(): FullBackupProfile = GatewayFullBackupProfile
    override val appName = "Homura"
}

/**
 * Homura's full backup: every preferences file, every database, and the
 * Keystore-sealed pairings of the primary and every other node. On another
 * phone the SIM bindings no longer match its SIM cards; they are confirmed
 * again as after any SIM change.
 */
internal object GatewayFullBackupProfile : FullBackupProfile {
    override val appId = "homura"
    /** Preferences files whose values are sealed by this install's Keystore key. */
    private val vaultFiles = setOf("control-session", "mesh-sessions")
    private const val SEPARATOR = "__"

    override fun includePreferences(name: String): Boolean = name !in vaultFiles

    override fun exportSecrets(context: Context): Map<String, JSONObject> = vaults(context).mapNotNull { (file, key) ->
        SessionVault(file, key).load(context)?.let { "$file$SEPARATOR$key" to encodeSession(it) }
    }.toMap()

    override fun importSecrets(context: Context, secrets: Map<String, JSONObject>) {
        vaultFiles.forEach { file -> context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit() }
        secrets.forEach { (name, json) ->
            val file = name.substringBefore(SEPARATOR)
            val key = name.substringAfter(SEPARATOR, "")
            if (file in vaultFiles && key.isNotEmpty()) SessionVault(file, key).save(context, decodeSession(json))
        }
    }

    override fun manifestExtras(context: Context): JSONObject {
        val pairings = vaults(context).count { (file, key) -> SessionVault(file, key).load(context) != null }
        return JSONObject().put("pairings", pairings)
    }

    override fun describe(manifest: JSONObject): String {
        val pairings = manifest.optJSONObject("extras")?.optInt("pairings") ?: 0
        val nodes = if (pairings > 0) "包含 $pairings 个服务器配对" else "不含服务器配对"
        return "$nodes。恢复到另一台手机后需要重新确认 SIM 卡。"
    }

    private fun vaults(context: Context): List<Pair<String, String>> = vaultFiles.flatMap { file ->
        if (!File(context.applicationInfo.dataDir, "shared_prefs/$file.xml").exists()) return@flatMap emptyList()
        context.getSharedPreferences(file, Context.MODE_PRIVATE).all.keys.map { file to it }
    }
}
