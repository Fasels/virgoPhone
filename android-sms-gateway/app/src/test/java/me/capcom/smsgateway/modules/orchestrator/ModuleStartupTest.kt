package me.capcom.smsgateway.modules.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleStartupTest {
    @Test
    fun `one failed module does not prevent later modules from starting`() {
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<String>()

        ModuleStartup().start(
            modules = listOf(
                ModuleStart("messages") { attempts += "messages" },
                ModuleStart("gateway") {
                    attempts += "gateway"
                    throw IllegalStateException("offline")
                },
                ModuleStart("receiver") { attempts += "receiver" },
            ),
            onFailure = { name, _ -> failures += name },
        )

        assertEquals(listOf("messages", "gateway", "receiver"), attempts)
        assertEquals(listOf("gateway"), failures)
    }

    @Test
    fun `later start retries only modules that previously failed`() {
        val attempts = mutableListOf<String>()
        var gatewayAttempts = 0
        val startup = ModuleStartup()
        val modules = listOf(
            ModuleStart("messages") { attempts += "messages" },
            ModuleStart("gateway") {
                attempts += "gateway"
                gatewayAttempts++
                if (gatewayAttempts == 1) throw IllegalStateException("offline")
            },
            ModuleStart("receiver") { attempts += "receiver" },
        )

        startup.start(modules) { _, _ -> }
        startup.start(modules) { _, _ -> }

        assertEquals(listOf("messages", "gateway", "receiver", "gateway"), attempts)
    }
}
