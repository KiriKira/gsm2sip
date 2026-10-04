package com.callagent.gateway.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SIP secrets and retry keys are isolated from the SMS pairing credentials. */
object VoiceCredentialStore {
    private const val ALIAS = "gsm2sip.voice.credentials.v1"
    private const val PREFS = "voice-secrets"
    private const val UNPAIRED = "local-unpaired-profile"

    @Synchronized fun password(context: Context, expectedServer: String? = null, expectedUser: String? = null): String {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val legacy = prefs.getString("pass", null)
        if (legacy != null) {
            savePassword(context, legacy)
            check(prefs.edit().remove("pass").commit()) { "Could not remove legacy SIP secret" }
        }
        val json = read(context) ?: return ""
        if (expectedServer != null && json.optString("server") != expectedServer) return ""
        if (expectedUser != null && json.optString("user") != expectedUser) return ""
        if (json.optString("server") != prefs.getString("server", "") || json.optString("user") != prefs.getString("user", "")) return ""
        val gatewayId = json.optString("gateway_id")
        if (gatewayId != (CredentialStore.load(context)?.gatewayId ?: UNPAIRED)) return ""
        return json.optString("password")
    }

    @Synchronized fun savePassword(context: Context, password: String, expectedGatewayId: String? = CredentialStore.load(context)?.gatewayId) {
        val currentGatewayId = CredentialStore.load(context)?.gatewayId
        check(currentGatewayId == expectedGatewayId) { "Pairing changed while editing SIP settings" }
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val json = read(context) ?: JSONObject()
        val server = prefs.getString("server", "").orEmpty()
        val user = prefs.getString("user", "").orEmpty()
        if (json.optString("server") != server || json.optString("user") != user) {
            json.remove("ca_pem")
        }
        json.put("server",server).put("user",user).put("password",password)
            .put("gateway_id",currentGatewayId ?: UNPAIRED)
        write(context,json)
    }

    @Synchronized fun beginProvisioning(context: Context, gatewayId: String): String {
        val json = read(context) ?: JSONObject()
        if (json.optString("pending_gateway_id") != gatewayId) {
            json.put("pending_gateway_id",gatewayId).put("pending_key",UUID.randomUUID().toString())
            write(context,json)
        }
        return json.getString("pending_key")
    }

    @Synchronized fun saveProvisioned(context: Context, gatewayId: String, config: JSONObject) {
        check(CredentialStore.load(context)?.gatewayId == gatewayId) { "Pairing changed during SIP provisioning" }
        val server = config.getString("server_name")
        val user = config.getString("auth_username")
        val json = JSONObject().put("server",server).put("user",user).put("password",config.getString("password"))
            .put("gateway_id",gatewayId).put("ca_pem",config.optString("ca_pem"))
        val pending = read(context)
        if (pending?.optString("pending_gateway_id") == gatewayId) {
            json.put("pending_gateway_id",gatewayId).put("pending_key",pending.getString("pending_key"))
        }
        // Commit the encrypted secret first. A settings failure can be retried
        // using the same endpoint identity without exposing the password.
        write(context,json)
        val registrar = java.net.URI(config.getString("registrar_uri").replaceFirst("sips:","https://"))
        val port = if (registrar.port > 0) registrar.port else 5061
        check(context.getSharedPreferences("gateway",Context.MODE_PRIVATE).edit()
            .putString("server",server).putInt("port",port).putString("user",user)
            .putBoolean("sip_tls",true).putBoolean("srtp_enabled",true).remove("pass").commit())
        json.remove("pending_gateway_id"); json.remove("pending_key")
        write(context,json)
    }

    @Synchronized fun expireProvisioning(context: Context, gatewayId: String) {
        val json = read(context) ?: return
        if (json.optString("pending_gateway_id") != gatewayId) return
        json.remove("pending_gateway_id"); json.remove("pending_key")
        write(context,json)
    }

    @Synchronized fun clear(context: Context) {
        check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().commit())
        check(context.getSharedPreferences("gateway",Context.MODE_PRIVATE).edit().remove("pass").commit())
    }

    @Synchronized fun invalidatePreviousIdentity(context: Context, gatewayId: String) {
        val previous = read(context)
        if (previous?.optString("gateway_id") != gatewayId) {
            check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().commit())
        }
        check(context.getSharedPreferences("gateway",Context.MODE_PRIVATE).edit().remove("pass").commit())
    }

    @Synchronized fun caPem(context: Context, server: String): String? {
        val json = read(context) ?: return null
        if (json.optString("gateway_id") != CredentialStore.load(context)?.gatewayId || json.optString("server") != server) return null
        return json.optString("ca_pem").takeIf { it.isNotBlank() }
    }

    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun write(context: Context, json: JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val packed=cipher.iv+cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("blob",Base64.encodeToString(packed,Base64.NO_WRAP)).commit())
    }
    private fun read(context: Context): JSONObject? {
        val blob=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("blob",null) ?: return null
        return try {
            val packed=Base64.decode(blob,Base64.NO_WRAP)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,packed.copyOfRange(0,12)))}
            JSONObject(String(cipher.doFinal(packed.copyOfRange(12,packed.size)),Charsets.UTF_8))
        } catch (_: Exception) { null }
    }
}
