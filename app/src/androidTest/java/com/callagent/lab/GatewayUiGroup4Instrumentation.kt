package com.callagent.lab

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiModeManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.callagent.gateway.data.CredentialStore
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

/** Disposable AVD supplement for Group 4 UI state and layout checks. */
@android.annotation.TargetApi(26)
class GatewayUiGroup4Instrumentation : Instrumentation() {
    private val resumedActivity = AtomicReference<MainActivity?>()
    private val checks = JSONArray()
    private val screenshots = JSONArray()
    private val targetRows = JSONArray()
    private val passed = mutableListOf<Boolean>()
    private var automation: android.app.UiAutomation? = null
    private lateinit var report: JSONObject

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val app = targetContext.applicationContext as Application
        val reportFile = File(targetContext.filesDir, "ui-group4-report.json")
        var lifecycleCallback: Application.ActivityLifecycleCallbacks? = null
        var originalNightMode: Int? = null
        var initialTabId = R.id.navHome
        var initialLogs = false
        var initialFilterId = R.id.btnFilterAll
        var initialConfigScroll = 0
        var initialUrl = ""
        var initialDeviceName = ""
        var initialPairingCode = ""
        var activityWasModified = false
        report = JSONObject()
            .put("scope", "group4_unpaired_ui_state_layout_and_dark_screenshots")
            .put("status", "FAIL")
            .put("checks", checks)
            .put("screenshots", screenshots)
            .put("visible_click_targets", targetRows)

        try {
            val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
                android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY else 0
            automation = getUiAutomation(flags).also { it.adoptShellPermissionIdentity() }
            check(CredentialStore.load(targetContext) == null) {
                "requires a disposable unpaired AVD; MainActivity was not launched"
            }

            val callback = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) {
                    if (activity is MainActivity) resumedActivity.set(activity)
                }
                override fun onActivityPaused(activity: Activity) {
                    if (activity === resumedActivity.get()) resumedActivity.set(null)
                }
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === resumedActivity.get()) resumedActivity.set(null)
                }
            }
            lifecycleCallback = callback
            app.registerActivityLifecycleCallbacks(callback)

            val launched = startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ) as? MainActivity ?: error("MainActivity launch failed")
            resumedActivity.compareAndSet(null, launched)
            await { currentActivity()?.let { !it.isFinishing && !it.isDestroyed } == true }
            val first = currentActivity() ?: error("MainActivity did not resume")

            val original = ui {
                val nav = first.findViewById<BottomNavigationView>(R.id.bottomNavigation)
                val tabLogs = first.findViewById<View>(R.id.tabLogs)
                initialTabId = nav.selectedItemId
                initialLogs = tabLogs.visibility == View.VISIBLE
                initialFilterId = listOf(R.id.btnFilterAll, R.id.btnFilterIncoming, R.id.btnFilterOutgoing)
                    .firstOrNull { first.findViewById<Chip>(it).isChecked } ?: R.id.btnFilterAll
                initialConfigScroll = first.findViewById<ScrollView>(R.id.tabConfig).scrollY
                initialUrl = first.findViewById<EditText>(R.id.etControlUrl).text.toString()
                initialDeviceName = first.findViewById<EditText>(R.id.etControlDeviceName).text.toString()
                initialPairingCode = first.findViewById<EditText>(R.id.etControlPairingCode).text.toString()
                Triple(first.getSystemService(UiModeManager::class.java).nightMode,
                    initialUrl.isNotEmpty() || initialDeviceName.isNotEmpty() || initialPairingCode.isNotEmpty(),
                    first.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            }
            originalNightMode = original.first
            report.put("initial_input_present", original.second)
                .put("initial_night_mode", original.first)
                .put("initial_rendered_night_mask", original.third)
                .put("initial_unpaired", true)
            activityWasModified = true

            runCheck("current_tab_recreation") {
                val before = currentActivity() ?: error("MainActivity not resumed")
                ui { before.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navTraffic }
                val restored = recreateCurrent(before)
                ui {
                    check(restored.findViewById<View>(R.id.tabTraffic).visibility == View.VISIBLE) {
                        "traffic tab was not visible after Activity recreation"
                    }
                    check(restored.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId == R.id.navTraffic) {
                        "bottom navigation did not restore the traffic selection"
                    }
                }
                "recreated Activity retained traffic tab and selected navigation item"
            }

            runCheck("settings_scroll_recreation") {
                var before = currentActivity() ?: error("MainActivity not resumed")
                ui { before.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navSettings }
                awaitLayout()
                val scroll = before.findViewById<ScrollView>(R.id.tabConfig)
                val range = ui { (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0) }
                check(range >= dp(before, 48)) { "settings page has no useful scroll range ($range px)" }
                val expected = ui {
                    val target = (range / 2).coerceAtLeast(dp(before, 48))
                    scroll.scrollTo(0, target)
                    scroll.scrollY
                }
                before = currentActivity() ?: before
                val restored = recreateCurrent(before)
                awaitLayout()
                val afterScroll = restored.findViewById<ScrollView>(R.id.tabConfig)
                val clamped = ui { min(expected, (afterScroll.getChildAt(0).height - afterScroll.height).coerceAtLeast(0)) }
                await { abs(afterScroll.scrollY - clamped) <= dp(restored, 2) }
                ui {
                    check(restored.findViewById<View>(R.id.tabConfig).visibility == View.VISIBLE) {
                        "settings tab was not visible after recreation"
                    }
                }
                "settings scroll restored to ${afterScroll.scrollY}px (expected $clamped px)"
            }

            runCheck("editor_text_selection_ime_and_secret") {
                var activity = currentActivity() ?: error("MainActivity not resumed")
                ui {
                    activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navSettings
                    activity.findViewById<ScrollView>(R.id.tabConfig).scrollTo(0, 0)
                }
                awaitLayout()
                await { currentActivity()?.hasWindowFocus() == true }
                activity = currentActivity() ?: error("MainActivity lost focus")
                val urlValue = "https://ui-restore.invalid"
                val nameValue = "Gateway Restore Device"
                val selectionStart = 8
                val selectionEnd = 21
                ui {
                    val url = activity.findViewById<EditText>(R.id.etControlUrl)
                    val name = activity.findViewById<EditText>(R.id.etControlDeviceName)
                    val code = activity.findViewById<EditText>(R.id.etControlPairingCode)
                    url.setText(urlValue)
                    name.setText(nameValue)
                    code.setText("synthetic-one-time-code")
                    check(name.requestFocus()) { "device name field would not accept focus" }
                    name.setSelection(selectionStart, selectionEnd)
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(name, InputMethodManager.SHOW_IMPLICIT)
                }
                await { isImeVisible(currentActivity()) }
                val focused = currentActivity() ?: error("MainActivity not resumed")
                val restored = recreateCurrent(focused)
                await { isImeVisible(restored) }
                ui {
                    val restoredUrl = restored.findViewById<EditText>(R.id.etControlUrl)
                    val restoredName = restored.findViewById<EditText>(R.id.etControlDeviceName)
                    val restoredCode = restored.findViewById<EditText>(R.id.etControlPairingCode)
                    check(restored.findViewById<View>(R.id.tabConfig).visibility == View.VISIBLE) {
                        "settings tab was not visible after editor recreation"
                    }
                    check(restoredUrl.text.toString() == urlValue) { "server URL text did not restore" }
                    check(restoredName.text.toString() == nameValue) { "device name text did not restore" }
                    check(restoredName.hasFocus()) { "device name focus did not restore" }
                    check(restoredName.selectionStart == selectionStart && restoredName.selectionEnd == selectionEnd) {
                        "device name selection did not restore (${restoredName.selectionStart}, ${restoredName.selectionEnd})"
                    }
                    check(restoredCode.text.isEmpty()) { "one-time pairing code was restored" }
                    check(isImeVisible(restored)) { "soft keyboard was not visible after recreation" }
                }
                "URL and device name text, name selection, focus and visible IME restored; pairing code remained empty"
            }

            runCheck("wide_pages_centered_and_capped") {
                var activity = currentActivity() ?: error("MainActivity not resumed")
                val widthDp = activity.resources.configuration.screenWidthDp
                check(widthDp >= 720) { "unfolded wide state required; screenWidthDp=$widthDp" }
                val rows = JSONArray()
                for (id in listOf(R.id.tabHome, R.id.tabTraffic, R.id.tabConfig)) {
                    activity = currentActivity() ?: error("MainActivity not resumed")
                    val navId = when (id) {
                        R.id.tabTraffic -> R.id.navTraffic
                        R.id.tabConfig -> R.id.navSettings
                        else -> R.id.navHome
                    }
                    ui { activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = navId }
                    awaitLayout()
                    activity = currentActivity() ?: activity
                    val scroll = activity.findViewById<ScrollView>(id)
                    val frame = scroll.getChildAt(0) as? FrameLayout ?: error("page $id lacks centered FrameLayout")
                    val content = frame.getChildAt(0) ?: error("page $id lacks content")
                    val width = content.width / activity.resources.displayMetrics.density
                    val parentWidth = frame.width / activity.resources.displayMetrics.density
                    val left = content.left / activity.resources.displayMetrics.density
                    val centered = abs(left - (parentWidth - width) / 2f) <= 2f
                    check(width <= 760.5f) { "page $id content is ${"%.1f".format(width)}dp wide" }
                    check(centered) { "page $id content is not horizontally centered (left=${"%.1f".format(left)}dp)" }
                    rows.put(JSONObject().put("page_id", id).put("content_width_dp", width)
                        .put("parent_width_dp", parentWidth).put("centered", centered))
                }
                report.put("wide_page_layout", rows)
                "screenWidthDp=$widthDp; home, records and settings content are centered and at most 760dp"
            }

            runCheck("dark_mode_screenshots") {
                val uiMode = currentActivity()?.getSystemService(UiModeManager::class.java)
                    ?: error("UiModeManager unavailable")
                if (originalNightMode == null) originalNightMode = uiMode.nightMode
                shell("cmd uimode night yes")
                await {
                    currentActivity()?.resources?.configuration?.let {
                        (it.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                    } == true
                }
                currentActivity()?.let { activity ->
                    ui {
                        activity.findViewById<EditText>(R.id.etControlUrl).setText("")
                        activity.findViewById<EditText>(R.id.etControlDeviceName).setText("")
                        activity.findViewById<EditText>(R.id.etControlPairingCode).setText("")
                    }
                }
                capturePageScreenshots()
                "captured full settings (top/middle/bottom), records filters and logs in dark mode"
            }

            runCheck("visible_click_targets_48dp") {
                val failures = JSONArray()
                val pages = listOf(R.id.tabHome, R.id.tabTraffic, R.id.tabConfig, R.id.tabLogs)
                for (pageId in pages) {
                    val activity = currentActivity() ?: error("MainActivity not resumed")
                    if (pageId != R.id.tabLogs) {
                        val navId = when (pageId) {
                            R.id.tabTraffic -> R.id.navTraffic
                            R.id.tabConfig -> R.id.navSettings
                            else -> R.id.navHome
                        }
                        ui { activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = navId }
                        awaitLayout()
                    } else {
                        ui { activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navSettings }
                        awaitLayout()
                        val current = currentActivity() ?: activity
                        ui { current.findViewById<View>(R.id.btnCfgLogs).performClick() }
                        awaitLayout()
                    }
                    val shownPage = currentActivity() ?: error("MainActivity not resumed")
                    val root = shownPage.findViewById<View>(pageId)
                    collectTargets(shownPage, root, failures)
                    if (pageId == R.id.tabConfig) {
                        val scroll = shownPage.findViewById<ScrollView>(R.id.tabConfig)
                        val range = ui { (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0) }
                        for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                            val y = (range * fraction).toInt()
                            ui { scroll.scrollTo(0, y) }
                            SystemClock.sleep(150)
                            collectTargets(shownPage, root, failures)
                        }
                    }
                }
                check(failures.length() == 0) {
                    "visible clickable views below 48dp or without a provable target: $failures"
                }
                "${targetRows.length()} visible clickable view measurements met 48dp minimums"
            }
        } catch (error: Throwable) {
            report.put("runner_error", "${error.javaClass.simpleName}: ${error.message}")
            appendCheck("runner", "fail", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            if (activityWasModified) {
                runCatching {
                    val activity = currentActivity() ?: return@runCatching
                    ui {
                        val url = activity.findViewById<EditText>(R.id.etControlUrl)
                        val name = activity.findViewById<EditText>(R.id.etControlDeviceName)
                        val code = activity.findViewById<EditText>(R.id.etControlPairingCode)
                        url.setText(initialUrl)
                        name.setText(initialDeviceName)
                        code.setText(initialPairingCode)
                        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNavigation)
                        if (initialLogs) {
                            nav.selectedItemId = R.id.navSettings
                            activity.findViewById<View>(R.id.btnCfgLogs).performClick()
                        } else {
                            nav.selectedItemId = initialTabId
                        }
                        activity.findViewById<ScrollView>(R.id.tabConfig).scrollTo(0, initialConfigScroll)
                        val filters = listOf(R.id.btnFilterAll, R.id.btnFilterIncoming, R.id.btnFilterOutgoing)
                        if (initialFilterId in filters) activity.findViewById<Chip>(initialFilterId).performClick()
                        activity.currentFocus?.clearFocus()
                        (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    }
                }
            }
            originalNightMode?.let { mode ->
                runCatching { shell("cmd uimode night ${nightModeArg(mode)}") }
            }
            runCatching { automation?.dropShellPermissionIdentity() }
            lifecycleCallback?.let { runCatching { app.unregisterActivityLifecycleCallbacks(it) } }

            val allPassed = passed.isNotEmpty() && passed.all { it }
            report.put("status", if (allPassed) "PASS" else "FAIL")
                .put("checks", checks)
                .put("screenshots", screenshots)
                .put("visible_click_targets", targetRows)
            runCatching { reportFile.writeText(report.toString(2)) }
            finish(if (allPassed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("ui_group4_result", report.toString()) })
        }
    }

    private fun runCheck(name: String, action: () -> String) {
        try {
            appendCheck(name, "pass", action())
        } catch (error: Throwable) {
            appendCheck(name, "fail", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun appendCheck(name: String, status: String, detail: String) {
        checks.put(JSONObject().put("name", name).put("status", status).put("detail", detail))
        passed += status == "pass"
    }

    private fun capturePageScreenshots() {
        var activity = currentActivity() ?: error("MainActivity not resumed")
        ui { activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navSettings }
        awaitLayout()
        activity = currentActivity() ?: activity
        hideIme(activity)
        val settings = activity.findViewById<ScrollView>(R.id.tabConfig)
        val range = ui { (settings.getChildAt(0).height - settings.height).coerceAtLeast(0) }
        val positions = sortedSetOf(0, range / 2, range)
        val step = (settings.height * 0.8f).toInt().coerceAtLeast(1)
        var next = step
        while (next < range) {
            positions.add(next)
            next += step
        }
        val ordered = positions.toList()
        val offsets = JSONArray()
        ordered.forEachIndexed { index, position ->
            val name = when (position) {
                0 -> "settings-top"
                range -> "settings-bottom"
                range / 2 -> "settings-middle"
                else -> "settings-part-${(index + 1).toString().padStart(2, '0')}"
            }
            ui { settings.scrollTo(0, position) }
            SystemClock.sleep(180)
            capture(name)
            offsets.put(position)
        }
        report.put("settings_screenshot_offsets_px", offsets)
            .put("settings_viewport_height_px", settings.height)
            .put("settings_scroll_range_px", range)

        activity = currentActivity() ?: activity
        ui { activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navTraffic }
        awaitLayout()
        activity = currentActivity() ?: activity
        for ((name, id) in listOf(
            "records-all" to R.id.btnFilterAll,
            "records-incoming" to R.id.btnFilterIncoming,
            "records-outgoing" to R.id.btnFilterOutgoing
        )) {
            ui { activity.findViewById<Chip>(id).performClick() }
            SystemClock.sleep(180)
            capture(name)
            activity = currentActivity() ?: activity
        }

        activity = currentActivity() ?: activity
        ui { activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navSettings }
        awaitLayout()
        activity = currentActivity() ?: activity
        ui { activity.findViewById<View>(R.id.btnCfgLogs).performClick() }
        awaitLayout()
        SystemClock.sleep(200)
        capture("logs")
    }

    private fun capture(name: String) {
        val bitmap = automation?.takeScreenshot() ?: error("UiAutomation screenshot unavailable")
        val width = bitmap.width
        val height = bitmap.height
        val directory = File(targetContext.filesDir, "ui-group4").apply { mkdirs() }
        val file = File(directory, "$name.png")
        try {
            FileOutputStream(file).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG compression failed for $name" }
            }
        } finally {
            bitmap.recycle()
        }
        screenshots.put(JSONObject().put("name", name).put("file", file.absolutePath)
            .put("width_px", width).put("height_px", height).put("bytes", file.length()))
    }

    private fun collectTargets(activity: MainActivity, root: View, failures: JSONArray) {
        val density = activity.resources.displayMetrics.density
        val visible = Rect()
        fun visit(view: View) {
            if (view.isShown && view.isEnabled && view.isClickable && view.getGlobalVisibleRect(visible)) {
                val widthDp = view.width / density
                val heightDp = view.height / density
                val hasTouchDelegate = (view.parent as? ViewGroup)?.touchDelegate != null
                val id = if (view.id == View.NO_ID) "no-id" else runCatching {
                    activity.resources.getResourceEntryName(view.id)
                }.getOrDefault(view.id.toString())
                val row = JSONObject().put("id", id).put("class", view.javaClass.simpleName)
                    .put("width_dp", widthDp).put("height_dp", heightDp)
                    .put("visible_bounds_px", JSONArray().put(visible.left).put(visible.top).put(visible.right).put(visible.bottom))
                    .put("parent_touch_delegate", hasTouchDelegate)
                targetRows.put(row)
                if (widthDp + 0.5f < 48f || heightDp + 0.5f < 48f) {
                    failures.put(JSONObject().put("id", id).put("width_dp", widthDp).put("height_dp", heightDp)
                        .put("parent_touch_delegate", hasTouchDelegate))
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    private fun recreateCurrent(activity: MainActivity): MainActivity {
        ui { activity.recreate() }
        await { currentActivity()?.let { it !== activity && !it.isDestroyed && !it.isFinishing } == true }
        return currentActivity() ?: error("recreated MainActivity did not resume")
    }

    private fun currentActivity(): MainActivity? = resumedActivity.get()

    private fun isImeVisible(activity: MainActivity?): Boolean {
        if (activity == null) return false
        val insets = activity.window.decorView.rootWindowInsets ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 30) insets.isVisible(WindowInsets.Type.ime())
        else activity.window.decorView.height < activity.resources.displayMetrics.heightPixels
    }

    private fun hideIme(activity: MainActivity) {
        ui {
            activity.currentFocus?.clearFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        await { !isImeVisible(currentActivity()) }
    }

    private fun awaitLayout() {
        await {
            currentActivity()?.window?.decorView?.let { it.isLaidOut && it.width > 0 && it.height > 0 } == true
        }
        SystemClock.sleep(120)
    }

    private fun await(timeoutMs: Long = 15000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        error("condition timed out after ${timeoutMs}ms")
    }

    private fun <T> ui(action: () -> T): T {
        var value: Any? = null
        var failure: Throwable? = null
        runOnMainSync {
            try {
                value = action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun shell(command: String): String {
        val pipe = automation?.executeShellCommand(command) ?: error("UiAutomation unavailable")
        return try {
            pipe.fileDescriptor?.let { java.io.FileInputStream(it).bufferedReader().use { reader -> reader.readText() } }.orEmpty()
        } finally {
            pipe.close()
        }
    }

    private fun nightModeArg(mode: Int): String = when (mode) {
        UiModeManager.MODE_NIGHT_YES -> "yes"
        UiModeManager.MODE_NIGHT_NO -> "no"
        UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
        else -> "auto"
    }
}
