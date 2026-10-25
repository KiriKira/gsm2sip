package com.callagent.gateway

import android.app.Application
import com.callagent.gateway.BuildConfig
import android.os.StrictMode
import android.util.Log
import com.google.android.material.color.DynamicColors

class GatewayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Allow network on main thread — this is a headless gateway service,
        // not a UI app.  GSM callbacks arrive on the main thread and need to
        // trigger short UDP sends (call control and media).
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().permitAll().build()
        )
        Log.i("GatewayApp", "Homura v${BuildConfig.VERSION_NAME} started")
        // A frozen main thread holds calls until someone closes Android's ANR dialog.
        com.callagent.gateway.background.MainThreadWatchdog.start(this)
    }
}
