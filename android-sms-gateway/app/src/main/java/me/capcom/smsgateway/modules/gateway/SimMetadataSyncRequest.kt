package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.localserver.domain.SimCard

/** Never translate a transient unreadable/empty subscription snapshot into SIM removal. */
internal fun buildSimMetadataSyncRequest(
    deviceId: String,
    simCards: List<SimCard>?,
): GatewayApi.DeviceSimMetadataRequest? {
    if (deviceId.isBlank() || simCards.isNullOrEmpty()) return null
    if (simCards.any { it.slotIndex < 0 || it.simNumber != it.slotIndex + 1 }) return null
    if (simCards.map { it.slotIndex }.distinct().size != simCards.size) return null
    return GatewayApi.DeviceSimMetadataRequest(
        id = deviceId,
        simCards = GatewaySimCardMapper.toDto(simCards),
        syncPhoneNumbers = true,
    )
}
