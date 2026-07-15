package me.capcom.smsgateway.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResidentServiceLifecycleTest {
    @Test
    fun `repeated service commands ensure runtime and stop it once`() {
        var starts = 0
        var stops = 0
        val lifecycle = ResidentServiceLifecycle(
            startRuntime = { starts++ },
            stopRuntime = { stops++ },
        )

        lifecycle.start()
        lifecycle.start()
        lifecycle.stop()
        lifecycle.stop()

        assertEquals(2, starts)
        assertEquals(1, stops)
    }

    @Test
    fun `failed runtime start can be retried by a later service command`() {
        var attempts = 0
        val lifecycle = ResidentServiceLifecycle(
            startRuntime = {
                attempts++
                if (attempts == 1) throw IllegalStateException("failed")
            },
            stopRuntime = {},
        )

        assertThrows(IllegalStateException::class.java) { lifecycle.start() }
        lifecycle.start()

        assertEquals(2, attempts)
    }

    @Test
    fun `failed runtime start cleans up partially started modules`() {
        var stops = 0
        val lifecycle = ResidentServiceLifecycle(
            startRuntime = { throw IllegalStateException("failed") },
            stopRuntime = { stops++ },
        )

        assertThrows(IllegalStateException::class.java) { lifecycle.start() }

        assertEquals(1, stops)
    }
}
