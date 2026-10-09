package com.example.virgo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChatTimeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)

/** Server timestamps are UTC Unix milliseconds; display them in the phone's timezone. */
fun formatChatTimestamp(raw: String, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val instant = parseChatTimestamp(raw) ?: return ""
    return runCatching { ChatTimeFormat.format(instant.atZone(zoneId)) }.getOrDefault("")
}

fun preferredMessageTimestamp(
    direction: MessageDirection,
    createdAt: String,
    receivedAt: String = "",
    sentAt: String = "",
): String {
    val candidates = if (direction == MessageDirection.Inbound) {
        listOf(receivedAt, createdAt)
    } else {
        listOf(sentAt, createdAt)
    }
    return candidates.firstOrNull { parseChatTimestamp(it) != null }?.trim().orEmpty()
}

private fun parseChatTimestamp(raw: String): Instant? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return runCatching {
        val instant = value.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(value)
        // Do not turn absent/default or corrupt timestamps into an apparent real message time.
        instant.takeIf { it.toEpochMilli() in 1..253_402_300_799_999L }
    }.getOrNull()
}
