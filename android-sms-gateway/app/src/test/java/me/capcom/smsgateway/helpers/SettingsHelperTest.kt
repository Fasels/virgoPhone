package me.capcom.smsgateway.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHelperTest {
    @Test
    fun `autostart defaults to enabled for a fresh installation`() {
        assertTrue(resolveAutostartPreference(hasStoredValue = false, storedValue = false))
    }

    @Test
    fun `explicitly disabled autostart remains disabled`() {
        assertFalse(resolveAutostartPreference(hasStoredValue = true, storedValue = false))
    }
}
