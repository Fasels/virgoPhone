package me.capcom.smsgateway.modules.gateway

import com.google.gson.JsonParser
import me.capcom.smsgateway.domain.ProcessingState

internal fun validDeliveryMetadata(token: String?, hash: String?): Boolean =
    !token.isNullOrBlank() && hash != null && Regex("[a-f0-9]{64}").matches(hash)

internal fun deliveryErrorCode(body: String): String = try {
    val json = JsonParser.parseString(body).asJsonObject
    val detail = json.get("detail")
    val code = if (detail?.isJsonObject == true) detail.asJsonObject.get("code") else json.get("code")
    code?.asString?.takeIf { Regex("[A-Z_]{1,60}").matches(it) } ?: "UNKNOWN"
} catch (_: RuntimeException) { "UNKNOWN" }

private fun confirms(remote: ProcessingState, local: ProcessingState): Boolean {
    if (remote == local) return true
    if (remote == ProcessingState.Failed || local == ProcessingState.Failed) return false
    val order = listOf(ProcessingState.Pending, ProcessingState.Processed, ProcessingState.Sent, ProcessingState.Delivered)
    return order.indexOf(remote) >= order.indexOf(local)
}

/** A 409 is successful only if this device's server record confirms every recipient. */
internal fun remoteConfirmsPatch(remote: GatewayApi.MessageStateResponse, patch: GatewayApi.MessagePatchRequest): Boolean {
    if (remote.id != patch.id || !confirms(remote.state, patch.state)) return false
    if (remote.recipients.size != patch.recipients.size) return false
    val byPhone = remote.recipients.associateBy { it.phoneNumber }
    return patch.recipients.all { local ->
        byPhone[local.phoneNumber]?.let { confirms(it.state, local.state) } == true
    }
}
