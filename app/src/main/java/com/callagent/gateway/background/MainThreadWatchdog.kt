package com.callagent.gateway.background

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.callagent.gateway.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Restarts Homura when its main thread stops responding. Telecom callbacks
 * and the call bridge run there, so a stuck main thread holds every call and
 * SMS until someone dismisses Android's "not responding" dialog by hand.
 *
 * A background thread posts a heartbeat to the main thread. After [STALL_MS]
 * without one, every thread's stack is saved to
 * `files/watchdog/stall-*.txt` (the newest [KEEP_REPORTS] are kept) and the
 * process exits; Android then restarts the sticky gateway service.
 */
object MainThreadWatchdog {
    private const val TAG = "MainThreadWatchdog"
    private const val CHECK_MS = 5_000L
    /** Far beyond any legitimate main-thread work, including a slow network call. */
    internal const val STALL_MS = 60_000L
    private const val KEEP_REPORTS = 5
    private const val DIR = "watchdog"

    // Uptime pauses in deep sleep like both threads do, so waking up is never a stall.
    @Volatile private var lastBeat = 0L
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        reportPreviousStall(app)
        val main = Handler(Looper.getMainLooper())
        lastBeat = SystemClock.uptimeMillis()
        Thread({
            while (true) {
                main.post { lastBeat = SystemClock.uptimeMillis() }
                try { Thread.sleep(CHECK_MS) } catch (_: InterruptedException) { return@Thread }
                val stalledFor = SystemClock.uptimeMillis() - lastBeat
                if (stalled(stalledFor, Debug.isDebuggerConnected())) {
                    runCatching { record(app, stalledFor) }
                    Log.e(TAG, "Main thread stalled for ${stalledFor / 1000}s; restarting the process")
                    Process.killProcess(Process.myPid())
                }
            }
        }, "main-watchdog").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    internal fun stalled(stalledForMs: Long, debuggerAttached: Boolean): Boolean =
        !debuggerAttached && stalledForMs >= STALL_MS

    private fun record(context: Context, stalledFor: Long) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val main = Looper.getMainLooper().thread
        val threads = Thread.getAllStackTraces().entries
            .sortedWith(compareBy({ it.key !== main }, { it.key.name }))
        File(dir, "stall-$stamp.txt").writeText(buildString {
            appendLine("Homura ${BuildConfig.VERSION_NAME} main thread stalled ${stalledFor / 1000}s at $stamp")
            threads.forEach { (thread, stack) ->
                appendLine()
                appendLine("\"${thread.name}\" state=${thread.state}")
                stack.forEach { appendLine("  at $it") }
            }
        })
        // Logcat keeps the gist even when nobody fetches the file.
        main.stackTrace.take(12).forEach { Log.e(TAG, "  main at $it") }
        dir.listFiles { file -> file.name.startsWith("stall-") }?.sortedByDescending { it.name }
            ?.drop(KEEP_REPORTS)?.forEach { it.delete() }
    }

    /** Says once, after the restart, that the previous process was ended for a stall. */
    private fun reportPreviousStall(context: Context) {
        val dir = File(context.filesDir, DIR)
        val newest = dir.listFiles { file -> file.name.startsWith("stall-") }?.maxByOrNull { it.name } ?: return
        val seen = File(dir, "reported")
        if (runCatching { seen.readText() }.getOrNull() == newest.name) return
        Log.w(TAG, "Restarted after a main-thread stall; report ${newest.absolutePath}: ${newest.useLines { it.firstOrNull() }}")
        runCatching { seen.writeText(newest.name) }
    }
}
