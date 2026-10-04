package com.callagent.gateway.ui

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import com.callagent.gateway.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class GatewayMaterialLayoutTest {
    @Test
    fun mainAndConfigurationLayoutsInflateWithExpressiveTheme() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SipGsmGateway)
        val inflater = LayoutInflater.from(context)
        val main = inflater.inflate(R.layout.activity_main, null)
        assertTrue(main.findViewById<View>(R.id.bottomNavigation) is BottomNavigationView)
        val configuration = inflater.inflate(R.layout.dialog_config, null)
        assertNotNull(configuration)
    }
}
