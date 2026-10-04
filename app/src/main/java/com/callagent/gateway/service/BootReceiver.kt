package com.callagent.gateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.callagent.gateway.background.GatewayBackgroundPolicy
import com.callagent.gateway.background.GatewayBackgroundRuntime
import com.callagent.gateway.data.CredentialStore

/** Starts the paired HTTPS control plane after boot. Voice setup stays behind
 * the visible app flow so Android can grant microphone foreground capability
 * only when the user starts call service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val paired = CredentialStore.load(context) != null
        val allowed = GatewayBackgroundRuntime.allowedRecovery(context)
        val mayStart = GatewayBackgroundPolicy.mayRestoreControl(
            sessionPresent = paired,
            optedIn = allowed,
            userStopped = GatewayBackgroundRuntime.userStopped(context)
        )
        if (!mayStart) {
            if (!allowed) Log.i(TAG, "Background gateway was disabled or stopped by the user")
            else if (!paired) Log.i(TAG, "No paired HTTPS gateway; open the app to configure")
            return
        }
        if (!paired) {
            Log.i(TAG, "No paired HTTPS gateway; open the app to configure")
            return
        }
        try {
            if (GatewayService.startControl(context)) {
                Log.i(TAG, "Starting paired control service")
            } else {
                GatewayBackgroundRuntime.recordIssue(context,
                    "Android did not allow the background service to start; open the app and retry")
                Log.w(TAG, "Android declined the paired control service start")
            }
        } catch (_: Exception) {
            GatewayBackgroundRuntime.recordIssue(context,
                "Android did not allow the background service to start; open the app and retry")
            Log.w(TAG, "Could not start paired control service")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
