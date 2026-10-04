package com.callagent.gateway.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class GatewayConfigEditorRestoreTest {
    @Test
    fun restoresFocusedNonSecretFieldSelectionAndImeAfterActivityRecreation() {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit().clear().commit()
        Shadows.shadowOf(application).grantPermissions(
            "${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )

        val originalController = Robolectric.buildActivity(MainActivity::class.java).setup()
        var restoredController: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        var originalDestroyed = false
        try {
            val originalActivity = originalController.get()
            originalActivity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId =
                R.id.navSettings
            val deviceName = originalActivity.findViewById<EditText>(R.id.etControlDeviceName)
            deviceName.setText("Rootless gateway")
            originalActivity.findViewById<EditText>(R.id.etControlPairingCode).setText("one-time-secret")
            originalActivity.findViewById<EditText>(R.id.etCfgPass).setText("sip-password")
            deviceName.requestFocus()
            deviceName.setSelection(5, 12)

            val savedState = Bundle()
            originalController.saveInstanceState(savedState)
            assertEquals(R.id.etControlDeviceName, savedState.getInt("ui.config_focused_field_id"))
            assertEquals(5, savedState.getInt("ui.config_selection_start"))
            assertEquals(12, savedState.getInt("ui.config_selection_end"))
            val savedStrings = savedState.keySet().mapNotNull { key -> savedState.getString(key) }
            assertFalse(savedStrings.contains("one-time-secret"))
            assertFalse(savedStrings.contains("sip-password"))

            // Exercise the visible-IME restore path with the same saved-state
            // contract produced by Android when the keyboard was open.
            savedState.putBoolean("ui.config_ime_visible", true)
            originalController.pause().stop().destroy()
            originalDestroyed = true
            restoredController = Robolectric.buildActivity(MainActivity::class.java)
                .create(savedState)
                .start()
                .resume()
                .visible()

            val restoredActivity = restoredController.get()
            assertEquals(View.VISIBLE, restoredActivity.findViewById<View>(R.id.tabConfig).visibility)
            restoredController!!.windowFocusChanged(true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val restoredField = restoredActivity.findViewById<EditText>(R.id.etControlDeviceName)
            assertTrue(restoredField.hasFocus())
            assertEquals(5, restoredField.selectionStart)
            assertEquals(12, restoredField.selectionEnd)
            assertTrue(restoredActivity.findViewById<EditText>(R.id.etControlPairingCode).text.isEmpty())
            assertTrue(restoredActivity.findViewById<EditText>(R.id.etCfgPass).text.isEmpty())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val inputMethodManager =
                    restoredActivity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                assertTrue(Shadows.shadowOf(inputMethodManager).isSoftInputVisible)
            }
        } finally {
            restoredController?.pause()?.stop()?.destroy()
            if (!originalDestroyed) originalController.pause().stop().destroy()
        }
    }
}
