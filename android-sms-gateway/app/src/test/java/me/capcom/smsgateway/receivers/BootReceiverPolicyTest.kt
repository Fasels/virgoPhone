package me.capcom.smsgateway.receivers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverPolicyTest {
    @Test
    fun `boot and package replacement restore an enabled resident service`() {
        assertTrue(
            shouldStartResidentService(
                action = "android.intent.action.BOOT_COMPLETED",
                autostartEnabled = true,
            )
        )
        assertTrue(
            shouldStartResidentService(
                action = "android.intent.action.MY_PACKAGE_REPLACED",
                autostartEnabled = true,
            )
        )
    }

    @Test
    fun `disabled autostart and unrelated broadcasts do not start resident service`() {
        assertFalse(
            shouldStartResidentService(
                action = "android.intent.action.BOOT_COMPLETED",
                autostartEnabled = false,
            )
        )
        assertFalse(
            shouldStartResidentService(
                action = "android.intent.action.ACTION_SHUTDOWN",
                autostartEnabled = true,
            )
        )
    }
}
