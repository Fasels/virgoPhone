package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Type

class GatewaySettingsTest {
    @Test
    fun serverUrl_defaultsToConfiguredPrivateGateway() {
        val settings = GatewaySettings(EmptyStorage())

        assertEquals(
            "https://api.syzygium.net/mobile/v1",
            settings.serverUrl
        )
    }

    private class EmptyStorage : KeyValueStorage {
        override fun <T> set(key: String, value: T) = Unit

        override fun <T> get(key: String, typeOfT: Type): T? = null

        override fun remove(key: String) = Unit
    }
}
