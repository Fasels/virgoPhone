package me.capcom.smsgateway.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun `notification permission is requested starting with Android 13`() {
        val android12Permissions = requiredRuntimePermissions(sdkInt = 32)
        val android13Permissions = requiredRuntimePermissions(sdkInt = 33)

        assertFalse(android12Permissions.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(android13Permissions.contains("android.permission.POST_NOTIFICATIONS"))
    }
}
