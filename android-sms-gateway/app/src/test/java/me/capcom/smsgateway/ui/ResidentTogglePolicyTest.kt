package me.capcom.smsgateway.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ResidentTogglePolicyTest {
    @Test
    fun `enabling starts resident service before optional setup flow`() {
        val events = mutableListOf<String>()

        handleResidentToggle(
            enabled = true,
            startResident = { events += "resident" },
            continueStartFlow = { events += "setup" },
            stopResident = { events += "stop" },
        )

        assertEquals(listOf("resident", "setup"), events)
    }

    @Test
    fun `disabling stops resident service without starting setup`() {
        val events = mutableListOf<String>()

        handleResidentToggle(
            enabled = false,
            startResident = { events += "resident" },
            continueStartFlow = { events += "setup" },
            stopResident = { events += "stop" },
        )

        assertEquals(listOf("stop"), events)
    }
}
