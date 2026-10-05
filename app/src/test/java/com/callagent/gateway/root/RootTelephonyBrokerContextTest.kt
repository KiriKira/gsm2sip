package com.callagent.gateway.root

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootTelephonyBrokerContextTest {
    @Test
    fun acceptsSystemPackageContextWhenSyntheticApplicationInfoUidIsZero() {
        val context = SyntheticContext(packageName = "android", applicationUid = 0)

        assertEquals(0, context.applicationInfo.uid)
        assertTrue(RootTelephonyBroker.isTrustedSystemContext(context, Process.SYSTEM_UID))
    }

    @Test
    fun rejectsNonSystemProcessAndWrongPackageContext() {
        assertFalse(
            RootTelephonyBroker.isTrustedSystemContext(
                SyntheticContext(packageName = "android", applicationUid = Process.SYSTEM_UID),
                processUid = Process.SYSTEM_UID + 1
            )
        )
        assertFalse(
            RootTelephonyBroker.isTrustedSystemContext(
                SyntheticContext(packageName = "com.callagent.gateway", applicationUid = Process.SYSTEM_UID),
                processUid = Process.SYSTEM_UID
            )
        )
    }

    private class SyntheticContext(
        private val packageName: String,
        private val applicationUid: Int
    ) : ContextWrapper(null) {
        override fun getPackageName(): String = packageName

        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
            uid = applicationUid
        }
    }
}
