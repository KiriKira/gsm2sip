package com.callagent.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/** What one app puts into a full backup beyond its plain preferences and databases. */
interface FullBackupProfile {
    /** Which app a backup belongs to; another app's backup is refused. */
    val appId: String

    /** Preferences files to carry. Keystore-sealed files are carried through [exportSecrets] instead. */
    fun includePreferences(name: String): Boolean

    fun includeDatabase(name: String): Boolean = true

    /** Values Android Keystore seals to this install (pairing credentials), as plaintext JSON by name. */
    fun exportSecrets(context: Context): Map<String, JSONObject>

    /** Seals [secrets] again with this install's keys, replacing any present. Runs before anything reads them. */
    fun importSecrets(context: Context, secrets: Map<String, JSONObject>)

    /** One line about what the backup holds, shown before restoring. */
    fun describe(manifest: JSONObject): String = ""

    /** Facts recorded in the manifest for [describe]. */
    fun manifestExtras(context: Context): JSONObject = JSONObject()
}

/**
 * A full, password-sealed backup of an app: its preferences, its SQLite
 * databases and its Keystore-sealed pairing credentials. Restoring stages the
 * backup, restarts the app, and applies it from [applyPending] before any
 * other code opens a database or reads a credential.
 */
object FullBackup {
    private const val FORMAT = "mahogsm-full-backup"
    private const val FORMAT_VERSION = 1
    private const val STAGING = "full-restore"
    private const val READY = "ready"
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private val entryName = Regex("^(manifest\\.json|prefs/[A-Za-z0-9._-]{1,120}\\.json|secrets/[A-Za-z0-9._-]{1,120}\\.json|db/[A-Za-z0-9._-]{1,120})$")

    data class Staged(val manifest: JSONObject, val description: String)

    fun export(context: Context, profile: FullBackupProfile, output: OutputStream, password: CharArray) {
        val app = context.applicationContext
        ZipOutputStream(FullBackupCodec.seal(output, password)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject()
                .put("format", FORMAT).put("version", FORMAT_VERSION).put("app", profile.appId)
                .put("package", app.packageName)
                .put("app_version", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull())
                .put("created_at", System.currentTimeMillis())
                .put("extras", profile.manifestExtras(app))
                .toString().toByteArray())
            zip.closeEntry()
            preferenceNames(app).filter { profile.includePreferences(it) && entryName.matches("prefs/$it.json") }.forEach { name ->
                zip.putNextEntry(ZipEntry("prefs/$name.json"))
                zip.write(encodePreferences(app.getSharedPreferences(name, Context.MODE_PRIVATE).all).toString().toByteArray())
                zip.closeEntry()
            }
            profile.exportSecrets(app).forEach { (name, value) ->
                require(entryName.matches("secrets/$name.json")) { "invalid secret name" }
                zip.putNextEntry(ZipEntry("secrets/$name.json"))
                zip.write(value.toString().toByteArray())
                zip.closeEntry()
            }
            databaseNames(app).filter { profile.includeDatabase(it) && entryName.matches("db/$it") }.forEach { name ->
                val path = app.getDatabasePath(name)
                // Fold the write-ahead log in so the main file is the whole database.
                runCatching {
                    SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    }
                }
                zip.putNextEntry(ZipEntry("db/$name"))
                path.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Decrypts and checks a backup into private staging without touching live
     * data. Returns what it holds; [commit] then makes it apply at next start.
     */
    fun stage(context: Context, profile: FullBackupProfile, input: InputStream, password: CharArray): Staged {
        val app = context.applicationContext
        val dir = stagingDir(app)
        dir.deleteRecursively()
        dir.mkdirs()
        var manifest: JSONObject? = null
        try {
            ZipInputStream(FullBackupCodec.open(input, password)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entryName.matches(entry.name)) throw FullBackupException("备份文件包含无法识别的内容")
                    val target = File(dir, entry.name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> copyLimited(zip, out) }
                    if (entry.name == "manifest.json") manifest = JSONObject(target.readText())
                }
            }
            val found = manifest ?: throw FullBackupException("备份文件缺少说明")
            if (found.optString("format") != FORMAT || found.optInt("version") != FORMAT_VERSION) {
                throw FullBackupException("备份文件版本不受支持")
            }
            if (found.optString("app") != profile.appId) throw FullBackupException("这不是本应用的备份")
            File(dir, "db").listFiles()?.forEach { db ->
                val ok = runCatching {
                    SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { opened ->
                        opened.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                    }
                }.getOrDefault(false)
                if (!ok) throw FullBackupException("备份中的数据库已损坏")
            }
            return Staged(found, profile.describe(found))
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        }
    }

    /** Marks the staged backup to be applied at the next start. */
    fun commit(context: Context) {
        File(stagingDir(context.applicationContext), READY).writeText("1")
    }

    fun discardStaged(context: Context) {
        stagingDir(context.applicationContext).deleteRecursively()
    }

    /** Restarts the app so the committed backup is applied before anything else runs. */
    fun restart(context: Context) {
        val app = context.applicationContext
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
            app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        exitProcess(0)
    }

    /**
     * Applies a committed backup. Call first in Application.onCreate: it
     * replaces preferences, databases and credentials wholesale.
     */
    fun applyPending(context: Context, profile: FullBackupProfile) {
        val app = context.applicationContext
        val dir = stagingDir(app)
        if (!File(dir, READY).exists()) {
            if (dir.exists()) dir.deleteRecursively()
            return
        }
        val prefsDir = File(dir, "prefs")
        val restored = prefsDir.listFiles()?.associate { it.name.removeSuffix(".json") to JSONObject(it.readText()) }.orEmpty()
        (preferenceNames(app).filter(profile::includePreferences) + restored.keys).toSet().forEach { name ->
            val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            restored[name]?.let { decodePreferences(it, editor) }
            editor.commit()
        }
        databaseNames(app).filter(profile::includeDatabase).forEach { app.deleteDatabase(it) }
        File(dir, "db").listFiles()?.forEach { db ->
            val target = app.getDatabasePath(db.name)
            target.parentFile?.mkdirs()
            db.copyTo(target, overwrite = true)
        }
        val secrets = File(dir, "secrets").listFiles()?.associate { it.name.removeSuffix(".json") to JSONObject(it.readText()) }.orEmpty()
        profile.importSecrets(app, secrets)
        dir.deleteRecursively()
    }

    private fun stagingDir(context: Context) = File(context.noBackupFilesDir, STAGING)

    private fun preferenceNames(context: Context): List<String> =
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }.orEmpty()

    private fun databaseNames(context: Context): List<String> =
        context.databaseList().filterNot { name -> listOf("-journal", "-wal", "-shm").any(name::endsWith) }

    private fun copyLimited(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            total += n
            if (total > MAX_ENTRY_BYTES) throw FullBackupException("备份文件中的条目过大")
            output.write(buffer, 0, n)
        }
    }

    internal fun encodePreferences(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            val typed = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                else -> null
            }
            typed?.let { put(key, it) }
        }
    }

    internal fun decodePreferences(json: JSONObject, editor: android.content.SharedPreferences.Editor) {
        json.keys().forEach { key ->
            val typed = json.getJSONObject(key)
            when (typed.getString("t")) {
                "s" -> editor.putString(key, typed.getString("v"))
                "b" -> editor.putBoolean(key, typed.getBoolean("v"))
                "i" -> editor.putInt(key, typed.getInt("v"))
                "l" -> editor.putLong(key, typed.getLong("v"))
                "f" -> editor.putFloat(key, typed.getDouble("v").toFloat())
                "ss" -> editor.putStringSet(key, typed.getJSONArray("v").let { array -> (0 until array.length()).map(array::getString).toSet() })
            }
        }
    }
}
