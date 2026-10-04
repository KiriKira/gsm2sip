package com.callagent.gateway.ui

import android.Manifest
import android.content.pm.PackageManager
import com.callagent.gateway.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class GatewaySmsStartupTest {
    @Test
    fun firstLaunchAsOrdinaryAppRequestsSmsWithoutVoiceOrDialerRole() {
        val application = RuntimeEnvironment.getApplication()
        val receiverPermission = "${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val declared = application.packageManager.getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(receiverPermission in declared)
        Shadows.shadowOf(application).grantPermissions(receiverPermission)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = Shadows.shadowOf(controller.get())
            assertEquals(setOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS), activity.lastRequestedPermission.requestedPermissions.toSet())
            // Android presents runtime permissions through an activity as well.
            val permissionActivity = activity.nextStartedActivityForResult
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", permissionActivity.intent.action)
            // No role/settings activity follows the SMS permission prompt.
            assertNull(activity.nextStartedActivityForResult)
        } finally {
            controller.pause().stop().destroy()
        }
    }
    @Test
    @Config(sdk = [28])
    fun explicitVoiceDiagnosticRequestsPhoneRoleBeforeRestrictedVoicePermissions() {
        val application = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(application).grantPermissions(
            "${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            MainActivity::class.java.getDeclaredMethod("openGatewayDiagnostics").apply { isAccessible = true }.invoke(activity)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            // OnShowListener is dispatched through the Android main looper.
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            val shadow = Shadows.shadowOf(activity)
            assertNull(shadow.lastRequestedPermission)
            assertEquals(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER,
                shadow.nextStartedActivityForResult.intent.action)
        } finally {
            controller.pause().stop().destroy()
        }
    }

}
