package com.callagent.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FullBackupTest {
    private val password = "correct horse".toCharArray()

    private fun seal(plain: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        FullBackupCodec.seal(out, password.copyOf(), iterations = 100_000).use { it.write(plain) }
    }.toByteArray()

    @Test
    fun sealedDataRoundTripsAcrossChunksAndRejectsWrongPasswordsAndTampering() {
        val plain = Random(7).nextBytes(200_000)
        val sealed = seal(plain)
        assertArrayEquals(plain, FullBackupCodec.open(ByteArrayInputStream(sealed), password.copyOf()).readBytes())

        assertThrows(FullBackupException::class.java) { FullBackupCodec.open(ByteArrayInputStream(sealed), "wrong password".toCharArray()) }
        val cut = sealed.copyOf(sealed.size - 100)
        assertThrows(FullBackupException::class.java) { FullBackupCodec.open(ByteArrayInputStream(cut), password.copyOf()).readBytes() }
        val flipped = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertThrows(FullBackupException::class.java) { FullBackupCodec.open(ByteArrayInputStream(flipped), password.copyOf()).readBytes() }
        assertThrows(FullBackupException::class.java) { FullBackupCodec.open(ByteArrayInputStream("not a backup".toByteArray()), password.copyOf()) }
    }

    private class Profile(override val appId: String = "test") : FullBackupProfile {
        var secrets: Map<String, JSONObject> = emptyMap()
        var imported: Map<String, JSONObject>? = null
        override fun includePreferences(name: String) = name.startsWith("app-")
        override fun exportSecrets(context: Context) = secrets
        override fun importSecrets(context: Context, secrets: Map<String, JSONObject>) { imported = secrets }
    }

    @Test
    fun aBackupReplacesPreferencesDatabasesAndSecretsAtTheNextStart() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        prefs.edit().putString("server", "https://a.example").putInt("count", 3).putStringSet("ids", setOf("x", "y")).commit()
        context.getSharedPreferences("other", Context.MODE_PRIVATE).edit().putString("kept", "yes").commit()
        val dbPath = context.getDatabasePath("data.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { it.execSQL("CREATE TABLE t(v TEXT)"); it.execSQL("INSERT INTO t VALUES('backed up')") }
        val profile = Profile().apply { secrets = mapOf("pairing" to JSONObject().put("token", "secret")) }

        val archive = ByteArrayOutputStream().also { FullBackup.export(context, profile, it, password.copyOf()) }.toByteArray()

        // The phone changes after the backup.
        prefs.edit().clear().putString("server", "https://b.example").putBoolean("later", true).commit()
        SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM t") }

        assertThrows("another app's backup is refused", FullBackupException::class.java) {
            FullBackup.stage(context, Profile("other-app"), ByteArrayInputStream(archive), password.copyOf())
        }
        val staged = FullBackup.stage(context, profile, ByteArrayInputStream(archive), password.copyOf())
        assertEquals("test", staged.manifest.getString("app"))
        FullBackup.applyPending(context, profile)
        assertEquals("staging alone changes nothing", "https://b.example", prefs.getString("server", null))

        FullBackup.stage(context, profile, ByteArrayInputStream(archive), password.copyOf())
        FullBackup.commit(context)
        FullBackup.applyPending(context, profile)
        assertEquals("https://a.example", prefs.getString("server", null))
        assertEquals(3, prefs.getInt("count", 0))
        assertEquals(setOf("x", "y"), prefs.getStringSet("ids", null))
        assertTrue("values added after the backup are gone", !prefs.contains("later"))
        assertEquals("unlisted preferences are untouched", "yes", context.getSharedPreferences("other", Context.MODE_PRIVATE).getString("kept", null))
        SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT v FROM t", null).use { assertTrue(it.moveToFirst()); assertEquals("backed up", it.getString(0)) }
        }
        assertEquals("secret", profile.imported?.get("pairing")?.getString("token"))
        profile.imported = null
        FullBackup.applyPending(context, profile)
        assertNull("a restore applies once", profile.imported)
    }
}
