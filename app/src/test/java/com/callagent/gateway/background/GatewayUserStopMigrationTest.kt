package com.callagent.gateway.background

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayUserStopMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun cleanUp() {
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun upgradeOfLegacyAutoconnectDoesNotOverrideEarlierUserStop() {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("autoconnect", true).commit()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime = 100L
        info.lastUpdateTime = 300L
        Shadows.shadowOf(context.packageManager).installPackage(info)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Shadows.shadowOf(manager).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setTimestamp(200L).build()
        )
        assertFalse(GatewayBackgroundRuntime.allowedRecovery(context))
        assertTrue(prefs.getBoolean("background_task_manager_stopped", false))
        assertTrue(GatewayBackgroundRuntime.recordExplicitEnable(context))
        assertTrue(GatewayBackgroundRuntime.allowedRecovery(context))
    }
}
