package com.callagent.gateway.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.callagent.gateway.bridge.CallOrchestrator
import com.callagent.gateway.data.GatewayDatabase
import com.callagent.gateway.net.ControlApiClient
import com.callagent.gateway.sip.SipClient
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SIP endpoint of this gateway on one mesh node. */
data class NodeSipAccount(
    val nodeGatewayId: String,
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
    val caPem: String?
)

/**
 * Encrypted SIP credentials for each mesh node, separate from the primary
 * pairing's [com.callagent.gateway.data.VoiceCredentialStore]. A provisioning
 * retry key is persisted before the rotate request so a lost response is
 * recovered with the same key instead of minting a second endpoint secret.
 */
object MeshVoiceStore {
    private const val PREFS = "mesh-voice"
    private const val ALIAS = "gsm2sip.mesh.voice.v1"

    @Synchronized fun account(context: Context, nodeGatewayId: String): NodeSipAccount? {
        val json = read(context, nodeGatewayId) ?: return null
        val password = json.optString("password").takeIf { it.isNotBlank() } ?: return null
        return NodeSipAccount(nodeGatewayId, json.getString("server"), json.optInt("port", 5061),
            json.getString("user"), password, json.optString("ca_pem").takeIf { it.isNotBlank() })
    }

    @Synchronized fun beginProvisioning(context: Context, nodeGatewayId: String): String {
        val json = read(context, nodeGatewayId) ?: JSONObject()
        json.optString("pending_key").takeIf { it.isNotBlank() }?.let { return it }
        val key = UUID.randomUUID().toString()
        write(context, nodeGatewayId, json.put("pending_key", key))
        return key
    }

    @Synchronized fun saveProvisioned(context: Context, nodeGatewayId: String, config: JSONObject) {
        val registrar = URI(config.getString("registrar_uri").replaceFirst("sips:", "https://"))
        write(context, nodeGatewayId, JSONObject()
            .put("server", config.getString("server_name"))
            .put("port", if (registrar.port > 0) registrar.port else 5061)
            .put("user", config.getString("auth_username"))
            .put("password", config.getString("password"))
            .put("ca_pem", config.optString("ca_pem")))
    }

    @Synchronized fun expireProvisioning(context: Context, nodeGatewayId: String) {
        val json = read(context, nodeGatewayId) ?: return
        json.remove("pending_key")
        write(context, nodeGatewayId, json)
    }

    @Synchronized fun clear(context: Context, nodeGatewayId: String) {
        check(prefs(context).edit().remove(slot(nodeGatewayId)).commit())
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun slot(nodeGatewayId: String) = "node_${nodeGatewayId.lowercase()}"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun write(context: Context, nodeGatewayId: String, json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs(context).edit().putString(slot(nodeGatewayId), Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
    }

    private fun read(context: Context, nodeGatewayId: String): JSONObject? {
        val blob = prefs(context).getString(slot(nodeGatewayId), null) ?: return null
        return try {
            val packed = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12))) }
            JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
        } catch (_: Exception) { null }
    }
}

/** Provision this gateway's SIP endpoint on a node, once voice is set up on the primary. */
object MeshVoiceProvisioning {
    /** Returns true when the node has a usable SIP account afterwards. Blocking. */
    fun ensure(context: Context, node: GatewayDatabase.MeshNode, client: ControlApiClient): Boolean {
        if (MeshVoiceStore.account(context, node.gatewayId) != null) return true
        val available = client.getSipConfiguration(node.gatewayId).optBoolean("available", false)
        if (!available) return false
        val key = MeshVoiceStore.beginProvisioning(context, node.gatewayId)
        val config = try {
            client.rotateSipCredentials(key, node.gatewayId)
        } catch (e: com.callagent.gateway.net.ControlApiException) {
            if (e.code in setOf("SIP_BOOTSTRAP_EXPIRED", "IDEMPOTENCY_KEY_REUSED")) {
                MeshVoiceStore.expireProvisioning(context, node.gatewayId)
            }
            throw e
        }
        MeshVoiceStore.saveProvisioned(context, node.gatewayId, config)
        return true
    }
}

/**
 * The gateway's SIP registrations with mesh nodes, next to the primary's.
 * Each runs its own TLS connection and is attached to the one
 * [CallOrchestrator], which still bridges one call at a time.
 */
class MeshVoiceClients(private val context: Context, private val log: (String) -> Unit) {
    private val clients = mutableMapOf<String, SipClient>()

    @Synchronized
    fun reconcile(orchestrator: CallOrchestrator, localIp: String, publicIp: String) {
        val database = GatewayDatabase.get(context)
        val wanted = database.meshNodes().mapNotNull { node -> MeshVoiceStore.account(context, node.gatewayId) }
            .associateBy { it.nodeGatewayId }
        (clients.keys - wanted.keys).toList().forEach { nodeId -> stop(orchestrator, nodeId) }
        wanted.values.forEachIndexed { index, account ->
            val existing = clients[account.nodeGatewayId]
            if (existing != null && existing.serverDomain == account.server && existing.username == account.username &&
                existing.localIp == localIp) return@forEachIndexed
            existing?.let { stop(orchestrator, account.nodeGatewayId) }
            val client = SipClient(
                username = account.username,
                password = account.password,
                serverDomain = account.server,
                serverPort = account.port,
                localIp = localIp,
                localPort = NODE_SIP_PORT_BASE + 2 * index,
                publicIp = publicIp,
                useTls = true,
                srtpRequested = true,
                caPem = account.caPem,
                nodeGatewayId = account.nodeGatewayId
            )
            client.logListener = { line -> log("SIP[${account.server}]: $line") }
            orchestrator.attachNodeClient(client)
            try {
                client.start()
                clients[account.nodeGatewayId] = client
                log("MESH: SIP started for ${account.server}")
            } catch (e: Exception) {
                orchestrator.detachNodeClient(client)
                log("MESH: SIP start failed for ${account.server}")
            }
        }
    }

    @Synchronized
    fun stopAll(orchestrator: CallOrchestrator?) {
        clients.keys.toList().forEach { stop(orchestrator, it) }
    }

    @Synchronized
    fun registered(nodeGatewayId: String): Boolean = clients[nodeGatewayId]?.registered == true

    private fun stop(orchestrator: CallOrchestrator?, nodeId: String) {
        val client = clients.remove(nodeId) ?: return
        orchestrator?.detachNodeClient(client)
        runCatching { client.stop() }
    }

    private companion object {
        const val NODE_SIP_PORT_BASE = 5062
    }
}
