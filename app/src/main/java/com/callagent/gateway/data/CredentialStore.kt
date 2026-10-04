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

/** Small encrypted token store backed by a non-exportable Android Keystore key. */
object CredentialStore {
    data class Session(
        val gatewayId: String,
        val controlBaseUrl: String,
        val accessToken: String,
        val accessExpiresAt: String,
        val refreshToken: String,
        val refreshExpiresAt: String,
        val pendingRefreshKey: String? = null
    )

    private const val PREFS = "control-session"
    private const val KEY_BLOB = "session_v1"
    private const val KEY_ALIAS = "gsm2sip.control.session.v1"

    @Synchronized
    fun save(context: Context, session: Session) {
        persist(context, session)
    }

    /** Replace a rotated session only if the session that triggered the
     * refresh is still current. A late HTTP response from an old pairing must
     * never overwrite credentials saved by a later pairing. */
    @Synchronized
    fun saveIfCurrent(context: Context, expected: Session, replacement: Session): Boolean {
        val current = load(context) ?: return false
        if (!sameVersion(current, expected)) return false
        persist(context, replacement)
        return true
    }

    /** Persist a retry key before sending refresh. The key remains attached to
     * the current refresh token until its rotated replacement is committed. */
    @Synchronized
    fun beginRefresh(context: Context, expected: Session): Session? {
        val current = load(context) ?: return null
        if (!sameVersion(current, expected)) return null
        if (current.pendingRefreshKey != null) return current
        val pending = current.copy(pendingRefreshKey = UUID.randomUUID().toString())
        persist(context, pending)
        return pending
    }

    /** Clear credentials only if they still match the request that failed. */
    @Synchronized
    fun clearIfCurrent(context: Context, expected: Session): Boolean {
        val current = load(context) ?: return false
        if (!sameVersion(current, expected)) return false
        val removed = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_BLOB).commit()
        check(removed) { "Credential storage clear failed" }
        return true
    }

    private fun persist(context: Context, session: Session) {
        val plain = JSONObject().put("gateway_id", session.gatewayId)
            .put("control_base_url", session.controlBaseUrl)
            .put("access_token", session.accessToken).put("access_expires_at", session.accessExpiresAt)
            .put("refresh_token", session.refreshToken).put("refresh_expires_at", session.refreshExpiresAt)
        session.pendingRefreshKey?.let { plain.put("pending_refresh_key", it) }
        val plaintext = plain.toString().toByteArray(Charsets.UTF_8)
        val (iv, encrypted) = try { encrypt(plaintext, key()) } catch (_: Exception) {
            deleteKey()
            encrypt(plaintext, key())
        }
        val packed = iv + encrypted
        val persisted = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BLOB, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
        check(persisted) { "Credential storage commit failed" }
    }

    @Synchronized
    fun load(context: Context): Session? {
        val encoded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOB, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12)
            val iv = packed.copyOfRange(0, 12)
            val body = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
            Session(json.getString("gateway_id"), json.getString("control_base_url"), json.getString("access_token"),
                json.getString("access_expires_at"), json.getString("refresh_token"),
                json.getString("refresh_expires_at"),
                json.optString("pending_refresh_key").takeIf { it.isNotBlank() && it != "null" })
        } catch (_: Exception) {
            // A restored backup or invalidated Keystore key cannot authenticate.
            clear(context)
            null
        }
    }

    /** Run an operation while the paired gateway identity is still current.
     * Pairing and token refresh writes share this monitor, so a result cannot
     * be committed under a replacement gateway/base URL after the caller's
     * identity check. */
    @Synchronized
    fun <T> withCurrentIdentity(
        context: Context,
        expectedGatewayId: String,
        expectedBaseUrl: String,
        block: (Session) -> T
    ): T? {
        val current = load(context) ?: return null
        if (current.gatewayId != expectedGatewayId || current.controlBaseUrl != expectedBaseUrl) return null
        return block(current)
    }

    @Synchronized
    fun clear(context: Context) {
        val removed = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_BLOB).commit()
        check(removed) { "Credential storage clear failed" }
    }

    private fun sameVersion(a: Session, b: Session): Boolean =
        a.gatewayId == b.gatewayId && a.controlBaseUrl == b.controlBaseUrl &&
            a.accessToken == b.accessToken && a.refreshToken == b.refreshToken

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try { (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it } }
        catch (_: Exception) { store.deleteEntry(KEY_ALIAS) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain)
        return cipher.iv to encrypted
    }

    private fun deleteKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }
}
