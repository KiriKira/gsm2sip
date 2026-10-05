package com.callagent.backup

import android.text.method.PasswordTransformationMethod
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class SmsBackupActivityTest {
    class TestActivity : SmsBackupActivity()

    @Test
    fun passwordFieldMasksTypedTextAfterSingleLineConfigurationAndExcludesSavedState() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3Expressive_DayNight_NoActionBar)
        val method = SmsBackupActivity::class.java.getDeclaredMethod("passwordInput", String::class.java)
        method.isAccessible = true
        val field = method.invoke(activity, "合成测试密码提示") as TextInputEditText
        field.setText("SyntheticBackup123")
        assertTrue(field.transformationMethod is PasswordTransformationMethod)
        val rendered = field.transformationMethod.getTransformation(field.text, field).toString()
        assertEquals(field.text!!.length, rendered.length)
        assertFalse(rendered.contains("SyntheticBackup123"))
        assertFalse(field.isSaveEnabled)
    }
}
