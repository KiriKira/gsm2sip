package com.callagent.gateway.background

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.service.GatewayService

/** A small, UI-safe view of the gateway's background service state. */
data class BackgroundStatus(
    val enabled: Boolean,
    val running: Boolean,
    val notificationsEnabled: Boolean,
    val batteryExempt: Boolean,
    val connectionLabel: String,
    val lastSyncAt: Long?,
    val issue: String?
)

/**
 * Owns the user-visible background opt-in and the diagnostic state exposed to
 * settings.  The foreground service remains the only owner of network work.
 */
object GatewayBackgroundRuntime {
    private const val PREFS = "gateway"
    private const val KEY_AUTOCONNECT = "autoconnect"
    private const val KEY_OPTED_IN = "background_opted_in"
    private const val KEY_USER_STOPPED = "background_user_stopped"
    private const val KEY_LAST_EXPLICIT_ENABLE = "background_last_explicit_enable_at"
    private const val KEY_TASK_MANAGER_STOPPED = "background_task_manager_stopped"
    private const val KEY_RUNNING = "background_service_running"
    private const val KEY_RUNNING_PID = "background_service_pid"
    private const val KEY_CONNECTION = "background_connection_label"
    private const val KEY_LAST_SYNC = "background_last_sync_at"
    private const val KEY_ISSUE = "background_issue"

    const val NOTIFICATION_PERMISSION_REQUEST_CODE = 0x4757

    @JvmStatic
    fun snapshot(context: Context): BackgroundStatus {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = allowedRecovery(app)
        val sessionExists = CredentialStore.load(app) != null
        val processId = android.os.Process.myPid()
        val running = prefs.getBoolean(KEY_RUNNING, false) &&
            prefs.getInt(KEY_RUNNING_PID, -1) == processId
        val notificationsEnabled = try {
            val manager = app.getSystemService(NotificationManager::class.java)
            val appEnabled = manager?.areNotificationsEnabled() ?: false
            val channelImportance = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                NotificationManager.IMPORTANCE_DEFAULT
            } else {
                manager?.getNotificationChannel(GatewayService.CHANNEL_ID_QUIET)?.importance
                    ?: NotificationManager.IMPORTANCE_DEFAULT
            }
            BackgroundNotificationPolicy.enabled(appEnabled, channelImportance)
        } catch (_: Exception) {
            false
        }
        val batteryExempt = try {
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            power.isIgnoringBatteryOptimizations(app.packageName)
        } catch (_: Exception) {
            false
        }
        val label = when {
            !enabled && prefs.getBoolean(KEY_TASK_MANAGER_STOPPED, false) -> "Stopped in Android Task Manager"
            !enabled -> "Stopped"
            !sessionExists -> "Not paired"
            running -> prefs.getString(KEY_CONNECTION, "Starting") ?: "Starting"
            else -> "Paused"
        }
        val issue = when {
            !enabled && prefs.getBoolean(KEY_TASK_MANAGER_STOPPED, false) -> prefs.getString(KEY_ISSUE, null)
            !enabled -> null
            !sessionExists -> GatewayBackgroundPolicy.pairingIssue(sessionExists)
            else -> prefs.getString(KEY_ISSUE, null)
        }
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }
        return BackgroundStatus(enabled, running, notificationsEnabled, batteryExempt, label, lastSync, issue)
    }

    /**
     * Persist the opt-in before asking Android to start or stop the service.
     * This durable bit fences boot and sticky restarts even if the process dies
     * before Android delivers the stop request.
     */
    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTOCONNECT, enabled)
            .putBoolean(KEY_OPTED_IN, enabled)
            .putBoolean(KEY_USER_STOPPED, !enabled)
            .putBoolean(KEY_TASK_MANAGER_STOPPED, false)
            .apply {
                if (enabled) putLong(KEY_LAST_EXPLICIT_ENABLE, now) else remove(KEY_ISSUE)
            }
            .commit()
        if (!saved) return false

        if (enabled) {
            if (CredentialStore.load(app) != null) {
                val started = GatewayService.startControl(app)
                if (!started) recordIssue(app, "Android did not allow the foreground service to start; open the app and retry")
            } else {
                updateConnection(app, "Not paired", "Pair this device with the control server")
            }
        } else {
            app.stopService(Intent(app, GatewayService::class.java))
            markServiceStopped(app, userInitiated = true)
        }
        return true
    }

    @JvmStatic
    fun openBatterySettings(activity: Activity) {
        val app = activity.applicationContext
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }.onFailure {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
            )
        }
    }

    @JvmStatic
    fun openNotificationSettings(activity: Activity) {
        val app = activity.applicationContext
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
            )
        }
    }

    /** Ask only after a visible user action; no permission prompt is launched at boot. */
    @JvmStatic
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }

    internal fun isEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Existing installs may already have explicitly saved the legacy
        // autoconnect setting. Preserve that choice as an opt-in on upgrade,
        // while a fresh install (where the key is absent) remains off until
        // the user enables background operation in the visible UI.
        val legacyOptIn = prefs.contains(KEY_AUTOCONNECT) && prefs.getBoolean(KEY_AUTOCONNECT, false)
        val optedIn = prefs.getBoolean(KEY_OPTED_IN, legacyOptIn)
        return optedIn && prefs.getBoolean(KEY_AUTOCONNECT, false) && !userStopped(context) &&
            !observeTaskManagerStop(context)
    }

    /** User-visible opt-in used by activity auto-start and service restore gates. */
    @JvmStatic
    fun allowedRecovery(context: Context): Boolean = isEnabled(context)

    /** Called only from explicit, visible start paths. */
    @JvmStatic
    fun recordExplicitEnable(context: Context): Boolean {
        val app = context.applicationContext
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTOCONNECT, true)
            .putBoolean(KEY_OPTED_IN, true)
            .putBoolean(KEY_USER_STOPPED, false)
            .putBoolean(KEY_TASK_MANAGER_STOPPED, false)
            .putLong(KEY_LAST_EXPLICIT_ENABLE, System.currentTimeMillis())
            .putString(KEY_ISSUE, "")
            .commit()
    }

    private fun observeTaskManagerStop(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_USER_STOPPED, false)) return true
        // Updating an older installation is not a new explicit enable.
        val enabledAt = prefs.getLong(KEY_LAST_EXPLICIT_ENABLE, packageInstallTime(app))
        if (enabledAt <= 0L) return false
        val latestUserExit = try {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(app.packageName, 0, 16)
                .asSequence()
                .filter { it.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED }
                .maxByOrNull { it.timestamp }
        } catch (_: Exception) {
            null
        } ?: return false
        if (!GatewayBackgroundPolicy.isTaskManagerStopAfterEnable(
                latestUserExit.timestamp,
                enabledAt,
                latestUserExit.reason,
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED
            )
        ) return false
        prefs.edit()
            .putBoolean(KEY_AUTOCONNECT, false)
            .putBoolean(KEY_OPTED_IN, false)
            .putBoolean(KEY_USER_STOPPED, true)
            .putBoolean(KEY_TASK_MANAGER_STOPPED, true)
            .putString(KEY_CONNECTION, "Stopped")
            .putString(KEY_ISSUE, "Android stopped the gateway from Task Manager. Enable it again in the app to resume.")
            .commit()
        return true
    }

    private fun packageInstallTime(context: Context): Long = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    } catch (_: Exception) {
        0L
    }

    internal fun userStopped(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_STOPPED, false)

    internal fun persistUserStop(context: Context): Boolean {
        val app = context.applicationContext
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTOCONNECT, false)
            .putBoolean(KEY_OPTED_IN, false)
            .putBoolean(KEY_USER_STOPPED, true)
            .putBoolean(KEY_TASK_MANAGER_STOPPED, false)
            .putString(KEY_CONNECTION, "Stopped")
            .remove(KEY_ISSUE)
            .commit()
    }

    internal fun markServiceStarted(context: Context, label: String = "Starting") {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, true)
            .putInt(KEY_RUNNING_PID, android.os.Process.myPid())
            .putString(KEY_CONNECTION, label)
            .remove(KEY_ISSUE)
            .apply()
    }

    internal fun markServiceStopped(context: Context, userInitiated: Boolean = false) {
        val app = context.applicationContext
        val editor = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .putInt(KEY_RUNNING_PID, -1)
            .putString(KEY_CONNECTION, if (userInitiated) "Stopped" else "Paused")
        if (userInitiated) editor.remove(KEY_ISSUE)
        editor.apply()
    }

    internal fun updateConnection(context: Context, label: String, issue: String? = null) {
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONNECTION, label)
        if (issue != null) editor.putString(KEY_ISSUE, issue)
        editor.apply()
    }

    internal fun markSyncSucceeded(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .remove(KEY_ISSUE)
            .apply()
    }

    internal fun recordIssue(context: Context, issue: String) {
        updateConnection(context, "Retrying", issue)
    }
}

/** Pure restart rules shared by the receiver, service and regression tests. */
internal object GatewayBackgroundPolicy {
    fun mayRestoreControl(sessionPresent: Boolean, optedIn: Boolean, userStopped: Boolean): Boolean =
        sessionPresent && optedIn && !userStopped

    fun pairingIssue(sessionPresent: Boolean): String? =
        if (sessionPresent) null else "Pair this device with the control server"

    fun mayRestoreVoice(intentAction: String?, sipConfigured: Boolean, explicitStartAction: String): Boolean =
        intentAction == explicitStartAction && sipConfigured

    fun holdVoiceLease(voiceRuntimeStarted: Boolean, callActive: Boolean): Boolean =
        voiceRuntimeStarted && callActive

    fun isTaskManagerStopAfterEnable(
        exitAt: Long,
        explicitlyEnabledAt: Long,
        exitReason: Int,
        userRequestedReason: Int
    ): Boolean = exitReason == userRequestedReason && explicitlyEnabledAt > 0L && exitAt >= explicitlyEnabledAt

    const val SYNC_WAKE_TIMEOUT_MS = 90_000L
    const val VOICE_WAKE_LEASE_TIMEOUT_MS = 120_000L
    const val VOICE_WAKE_RENEW_MS = 60_000L
    const val WIFI_LOCK_MAX_LEASE_MS = 90_000L
}

internal object BackgroundNotificationPolicy {
    fun enabled(appNotificationsEnabled: Boolean, channelImportance: Int): Boolean =
        appNotificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE
}
