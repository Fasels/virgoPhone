package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.localserver.domain.SimCard

object GatewaySimCardMapper {
    fun toDto(simCards: List<SimCard>): List<GatewayApi.SimCard> = simCards.map {
        GatewayApi.SimCard(
            slotIndex = it.slotIndex,
            simNumber = it.simNumber,
            phoneNumber = it.phoneNumber,
            carrierName = it.carrierName,
            iccid = it.iccid,
        )
    }
}
