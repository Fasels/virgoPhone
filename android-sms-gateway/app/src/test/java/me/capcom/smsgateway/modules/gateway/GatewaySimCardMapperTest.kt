package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.localserver.domain.SimCard
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewaySimCardMapperTest {
    @Test
    fun mapsSimCardDetailsWithoutRedaction() {
        val simCard = SimCard(
            slotIndex = 0,
            simNumber = 1,
            phoneNumber = "+15551234567",
            carrierName = "Example Carrier",
            iccid = "89014103211118510720",
        )

        val result = GatewaySimCardMapper.toDto(listOf(simCard)).single()

        assertEquals(0, result.slotIndex)
        assertEquals(1, result.simNumber)
        assertEquals("+15551234567", result.phoneNumber)
        assertEquals("Example Carrier", result.carrierName)
        assertEquals("89014103211118510720", result.iccid)
    }
}
