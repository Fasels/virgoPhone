package me.capcom.smsgateway.modules.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdempotentLifecycleTest {
    @Test
    fun `repeated start and stop execute each transition once`() {
        val lifecycle = IdempotentLifecycle()
        var starts = 0
        var stops = 0

        lifecycle.start { starts++ }
        lifecycle.start { starts++ }
        lifecycle.stop { stops++ }
        lifecycle.stop { stops++ }

        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    @Test
    fun `failed start can be retried`() {
        val lifecycle = IdempotentLifecycle()
        var attempts = 0

        assertThrows(IllegalStateException::class.java) {
            lifecycle.start {
                attempts++
                throw IllegalStateException("failed")
            }
        }
        lifecycle.start { attempts++ }

        assertEquals(2, attempts)
    }
}
