package com.callagent.gateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.callagent.gateway.data.CredentialStore

/** Starts the paired HTTPS control plane after boot. Voice setup stays behind
 * the visible app flow so Android can grant microphone foreground capability
 * only when the user starts call service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autoconnect", true)) {
            Log.i(TAG, "Autoconnect disabled")
            return
        }
        if (CredentialStore.load(context) == null) {
            Log.i(TAG, "No paired HTTPS gateway; open the app to configure")
            return
        }
        try {
            GatewayService.startControl(context)
            Log.i(TAG, "Starting paired control service")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start paired control service (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
