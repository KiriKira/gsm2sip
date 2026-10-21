package com.callagent.gateway.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.util.Base64
import com.callagent.gateway.mesh.MeshVoiceStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceCredentialStoreTest {
    private lateinit var context: Context
    private var replacedProvider: Provider? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("voice-secrets", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("control-session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mesh-voice", Context.MODE_PRIVATE).edit().clear().commit()
        replacedProvider = Security.getProvider(KEYSTORE_PROVIDER)
        Security.removeProvider(KEYSTORE_PROVIDER)
        Security.insertProviderAt(TestAndroidKeyStoreProvider(), 1)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("voice-secrets", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("control-session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mesh-voice", Context.MODE_PRIVATE).edit().clear().commit()
        Security.removeProvider(KEYSTORE_PROVIDER)
        replacedProvider?.let { Security.insertProviderAt(it, 1) }
    }

    @Test
    fun legacyPasswordMigratesToItsCurrentPairAndIsRejectedAfterRepairing() {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit()
            .putString("server", "sip.example.test")
            .putString("user", "gateway-user")
            .putString("pass", "legacy-password")
            .commit()
        CredentialStore.save(context, session("gateway-A"))

        assertEquals("legacy-password", VoiceCredentialStore.password(context))
        assertNull(context.getSharedPreferences("gateway", Context.MODE_PRIVATE).getString("pass", null))

        CredentialStore.save(context, session("gateway-B"))
        assertEquals("", VoiceCredentialStore.password(context))
    }

    @Test
    fun provisionedPasswordAndPrivateCaRemainOwnedByTheProvisioningPair() {
        val owner = session("gateway-A")
        CredentialStore.save(context, owner)
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit()
            .putString("server", "sip.example.test").putString("user", "gateway-user").commit()
        val config = JSONObject()
            .put("server_name", "sip.example.test")
            .put("auth_username", "gateway-user")
            .put("password", "provisioned-password")
            .put("registrar_uri", "sips:sip.example.test")
            .put("ca_pem", "test private CA")
        VoiceCredentialStore.beginProvisioning(context, owner.gatewayId)
        VoiceCredentialStore.saveProvisioned(context, owner.gatewayId, config)

        assertEquals("provisioned-password", VoiceCredentialStore.password(context))
        assertEquals("test private CA", VoiceCredentialStore.caPem(context, "sip.example.test"))

        CredentialStore.save(context, session("gateway-B"))
        assertEquals("", VoiceCredentialStore.password(context))
        assertNull(VoiceCredentialStore.caPem(context, "sip.example.test"))
    }

    @Test
    fun provisionedTransportSelectsTlsByDefaultAndUdpWhenTheServerSaysSo() {
        val owner = session("gateway-A")
        CredentialStore.save(context, owner)
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val legacy = JSONObject()
            .put("server_name", "sip.example.test")
            .put("auth_username", "gateway-user")
            .put("password", "provisioned-password")
            .put("registrar_uri", "sips:sip.example.test")
        VoiceCredentialStore.saveProvisioned(context, owner.gatewayId, legacy)
        assertTrue(prefs.getBoolean("sip_tls", false))
        assertTrue(prefs.getBoolean("srtp_enabled", false))
        assertEquals(5061, prefs.getInt("port", 0))

        val udp = JSONObject()
            .put("server_name", "192.168.1.10")
            .put("auth_username", "dev_x")
            .put("password", "lan-password")
            .put("transport", "udp")
            .put("media_encryption", "none")
            .put("registrar_uri", "sip:192.168.1.10:5060")
            .put("outbound_proxy_uri", "sip:192.168.1.10:5060;transport=udp;lr")
            .put("aor", "sip:dev_x@192.168.1.10:5060")
        VoiceCredentialStore.saveProvisioned(context, owner.gatewayId, udp)
        assertFalse(prefs.getBoolean("sip_tls", true))
        assertFalse(prefs.getBoolean("srtp_enabled", true))
        assertEquals(5060, prefs.getInt("port", 0))
        assertEquals("lan-password", VoiceCredentialStore.password(context))
        assertNull(VoiceCredentialStore.caPem(context, "192.168.1.10"))
    }

    @Test
    fun meshNodeAccountKeepsItsTransportAndOlderAccountsStayTls() {
        MeshVoiceStore.saveProvisioned(context, "node-udp", JSONObject()
            .put("server_name", "100.64.0.7")
            .put("auth_username", "dev_x")
            .put("password", "node-password")
            .put("transport", "udp")
            .put("media_encryption", "none")
            .put("registrar_uri", "sip:100.64.0.7:5060;transport=udp"))
        val udp = MeshVoiceStore.account(context, "node-udp")!!
        assertFalse(udp.tls)
        assertEquals(5060, udp.port)
        assertNull(udp.caPem)

        MeshVoiceStore.saveProvisioned(context, "node-tls", JSONObject()
            .put("server_name", "vps.example.test")
            .put("auth_username", "dev_y")
            .put("password", "node-password")
            .put("registrar_uri", "sips:vps.example.test")
            .put("ca_pem", "node CA"))
        val tls = MeshVoiceStore.account(context, "node-tls")!!
        assertTrue(tls.tls)
        assertEquals(5061, tls.port)
        assertEquals("node CA", tls.caPem)

        // An account stored before the tls flag existed has no such key.
        val stored = JSONObject().put("server", "old.example.test").put("port", 5061)
            .put("user", "dev_z").put("password", "old-password").put("ca_pem", "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(TEST_KEY, "AES")) }
        val packed = cipher.iv + cipher.doFinal(stored.toString().toByteArray(Charsets.UTF_8))
        context.getSharedPreferences("mesh-voice", Context.MODE_PRIVATE).edit()
            .putString("node_node-old", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
        val old = MeshVoiceStore.account(context, "node-old")!!
        assertTrue(old.tls)
        assertEquals("old-password", old.password)
    }

    @Test
    fun localUnpairedPasswordDoesNotBecomeUsableWhenAControlOwnerIsAdded() {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit()
            .putString("server", "sip.example.test")
            .putString("user", "local-user")
            .putString("pass", "local-password")
            .commit()

        assertEquals("local-password", VoiceCredentialStore.password(context))
        CredentialStore.save(context, session("gateway-B"))
        assertEquals("", VoiceCredentialStore.password(context))
    }

    @Test
    fun cachedControlSessionObservesAnotherVaultRotationAndClear() {
        val first = SessionVault("control-session", "session_v1")
        val second = SessionVault("control-session", "session_v1")
        val original = session("gateway-A")
        first.save(context, original)
        assertEquals(original, first.load(context))
        val rotated = original.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh")
        assertTrue(second.saveIfCurrent(context, original, rotated))
        assertEquals(rotated, first.load(context))
        assertFalse(first.saveIfCurrent(context, original, original))
        assertTrue(second.clearIfCurrent(context, rotated))
        assertNull(first.load(context))
    }

    @Test
    fun corruptedControlBlobCannotReturnCachedCredentials() {
        val vault = SessionVault("control-session", "session_v1")
        vault.save(context, session("gateway-A"))
        assertTrue(vault.load(context) != null)
        context.getSharedPreferences("control-session", 0).edit().putString("session_v1", "AA==").commit()
        assertNull(vault.load(context))
        assertFalse(context.getSharedPreferences("control-session", 0).contains("session_v1"))
    }

    private fun session(gatewayId: String) = CredentialStore.Session(
        gatewayId = gatewayId,
        controlBaseUrl = "https://control.example.test/v1",
        accessToken = "access-$gatewayId",
        accessExpiresAt = "2030-01-01T00:00:00Z",
        refreshToken = "refresh-$gatewayId",
        refreshExpiresAt = "2031-01-01T00:00:00Z"
    )

    private class TestAndroidKeyStoreProvider : Provider(
        KEYSTORE_PROVIDER, 1.0, "Test-only Android Keystore substitute"
    ) {
        init {
            put("KeyStore.AndroidKeyStore", TestKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", TestAesKeyGeneratorSpi::class.java.name)
        }
    }

    /** The implementation under test needs a stable non-exportable-key facade,
     * while the test still uses the platform AES-GCM cipher for its blobs. */
    public class TestKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = null
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineDeleteEntry(alias: String) = Unit
        override fun engineAliases(): java.util.Enumeration<String> = java.util.Collections.emptyEnumeration()
        override fun engineContainsAlias(alias: String): Boolean = false
        override fun engineSize(): Int = 0
        override fun engineIsKeyEntry(alias: String): Boolean = false
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    public class TestAesKeyGeneratorSpi : KeyGeneratorSpi() {
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            require(params is KeyGenParameterSpec)
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey = SecretKeySpec(TEST_KEY, "AES")
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        val TEST_KEY = ByteArray(32) { (it + 1).toByte() }
    }
}
