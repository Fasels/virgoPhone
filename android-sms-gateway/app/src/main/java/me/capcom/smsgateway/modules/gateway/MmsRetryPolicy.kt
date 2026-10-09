package me.capcom.smsgateway.modules.gateway

import com.google.gson.JsonParser

/** Only the specifically corrected GIF rejection is eligible for automatic recovery. */
internal fun isCompatibleGifRetry(reason: String, payloadJson: String): Boolean {
    if (reason.trim() != "HTTP 415") return false
    return runCatching {
        val task = JsonParser.parseString(payloadJson).asJsonObject
        if (task["deviceId"].asString.isBlank() || task["serverUrl"].asString.isBlank()) return false
        val payload = task.getAsJsonObject("payload")
        if (payload["messageId"].asString.isBlank()) return false
        val attachments = payload.getAsJsonArray("attachments")
        val supported = setOf("image/jpeg", "image/png", "image/gif", "audio/amr", "application/octet-stream")
        var containsGif = false
        for (part in attachments) {
            val attachment = part.asJsonObject
            val type = attachment["contentType"].asString.trim().lowercase()
            if (type !in supported || attachment["data"].asString.isBlank()) return false
            containsGif = containsGif || type == "image/gif"
        }
        containsGif
    }.getOrDefault(false)
}
