package com.callagent.gateway.background

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.callagent.gateway.RootShell
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps a paired gateway reachable with the screen off and the charger out.
 *
 * Heartbeats, the WSS wake channel and SIP registration all run on ordinary
 * threads. Once Android suspends the CPU none of them run, the server stops
 * hearing heartbeats and shows the gateway offline after 90 seconds, and
 * Doze cuts the network for everything that is not exempt. The gateway is a
 * dedicated phone, so it trades battery for staying online: a CPU wake lock
 * (renewed, so a lost renewal ends in a bounded lease rather than a stuck
 * lock), a Wi-Fi lock against Wi-Fi power save, and, with root, a Doze and
 * App Standby exemption.
 */
internal class StayAwake(
    private val context: Context,
    private val scheduler: ScheduledExecutorService,
    private val exemptFromDoze: Boolean = true,
    private val log: (String) -> Unit
) {
    private val guard = Any()
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var renewal: ScheduledFuture<*>? = null
    private var exemptionRequested = false

    fun hold() {
        synchronized(guard) {
            if (renewal != null) return
            acquireLocked()
            renewal = try {
                scheduler.scheduleWithFixedDelay({
                    synchronized(guard) { if (renewal != null) acquireLocked() }
                }, RENEW_MS, RENEW_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
            if (exemptFromDoze && !exemptionRequested) {
                exemptionRequested = true
                Thread({ requestPowerExemption() }, "gateway-power-exemption").start()
            }
        }
        log("POWER: keeping the CPU and Wi-Fi awake while paired")
    }

    fun release() {
        synchronized(guard) {
            renewal?.cancel(false)
            renewal = null
            cpu?.let { if (it.isHeld) runCatching { it.release() } }
            wifi?.let { if (it.isHeld) runCatching { it.release() } }
            cpu = null
            wifi = null
        }
    }

    private fun acquireLocked() {
        try {
            val lock = cpu ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gateway:stay-awake")
                .apply { setReferenceCounted(false) }
            // Re-acquiring a non-counted lock restarts its timeout.
            lock.acquire(LEASE_MS)
            cpu = lock
        } catch (e: Exception) {
            Log.w(TAG, "CPU wake lock refused: ${e.javaClass.simpleName}")
        }
        if (wifi?.isHeld == true) return
        try {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gateway:stay-awake-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi lock refused: ${e.javaClass.simpleName}")
        }
    }

    /** Doze and App Standby still cut the network of a wake-locked app that is not exempt. */
    private fun requestPowerExemption() {
        if (RootShell.rootState() == "denied") return
        val pkg = context.packageName
        val results = POWER_EXEMPTION_COMMANDS.map { command ->
            runCatching { RootShell.exec(command.replace("{pkg}", pkg), timeoutMs = 10_000) }.getOrDefault(-1)
        }
        if (results.all { it == 0 }) log("POWER: exempt from Doze and App Standby (root)")
        else log("POWER: could not set the Doze exemption (exit codes $results); turn off battery optimization for this app")
    }

    companion object {
        private const val TAG = "StayAwake"
        const val LEASE_MS = 15 * 60_000L
        const val RENEW_MS = 5 * 60_000L

        val POWER_EXEMPTION_COMMANDS = listOf(
            "dumpsys deviceidle whitelist +{pkg}",
            "cmd appops set {pkg} RUN_ANY_IN_BACKGROUND allow",
            "am set-standby-bucket {pkg} active"
        )
    }
}
