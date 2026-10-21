package com.callagent.gateway.ui

import android.view.View
import android.widget.EditText
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class GatewayMaterialLayoutTest {
    @Test
    fun pagesBuildWithExpressiveThemeAndKeepSecretsOutOfSavedState() {
        val application = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val main = controller.get()
            val navigation = main.findViewById<View>(R.id.bottomNavigation)
            assertTrue(navigation is BottomNavigationView)
            assertEquals(3, (navigation as BottomNavigationView).menu.size())
            assertFalse(main.findViewById<EditText>(R.id.etControlPairingCode).isSaveEnabled)
            for (page in listOf(R.id.tabHome, R.id.tabTraffic, R.id.tabConfig, R.id.tabLogs)) {
                assertNotNull(main.findViewById<View>(page))
            }
            assertEquals(View.VISIBLE, main.findViewById<View>(R.id.tabHome).visibility)
            navigation.selectedItemId = R.id.navTraffic
            assertEquals(View.VISIBLE, main.findViewById<View>(R.id.tabTraffic).visibility)
            assertEquals(View.GONE, main.findViewById<View>(R.id.tabHome).visibility)
            navigation.selectedItemId = R.id.navSettings
            assertEquals(View.VISIBLE, main.findViewById<View>(R.id.tabConfig).visibility)
            main.findViewById<View>(R.id.btnCfgLogs).performClick()
            assertEquals(View.VISIBLE, main.findViewById<View>(R.id.tabLogs).visibility)
            assertEquals(R.id.navSettings, navigation.selectedItemId)
            main.findViewById<View>(R.id.btnLogsBack).performClick()
            assertEquals(View.VISIBLE, main.findViewById<View>(R.id.tabConfig).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
