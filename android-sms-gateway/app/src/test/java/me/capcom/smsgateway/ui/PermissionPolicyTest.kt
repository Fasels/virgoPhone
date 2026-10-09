package me.capcom.smsgateway.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun `phone number permission is requested when introduced on Android 8`() {
        assertFalse(requiredRuntimePermissions(25).contains("android.permission.READ_PHONE_NUMBERS"))
        assertTrue(requiredRuntimePermissions(26).contains("android.permission.READ_PHONE_NUMBERS"))
        assertTrue(requiredRuntimePermissions(33).contains("android.permission.READ_PHONE_NUMBERS"))
    }

    @Test
    fun `notification permission is requested starting with Android 13`() {
        val android12Permissions = requiredRuntimePermissions(sdkInt = 32)
        val android13Permissions = requiredRuntimePermissions(sdkInt = 33)

        assertFalse(android12Permissions.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(android13Permissions.contains("android.permission.POST_NOTIFICATIONS"))
    }
}
