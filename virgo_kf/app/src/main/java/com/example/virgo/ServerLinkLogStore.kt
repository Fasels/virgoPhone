package com.example.virgo

import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

data class ServerLinkLogEntry(
    val timestampMillis: Long,
    val method: String,
    val path: String,
    val statusCode: Int?,
    val message: String,
) {
    fun toDisplayText(): String {
        val status = statusCode?.let { "HTTP $it" } ?: "NO STATUS"
        return "${formatTime(timestampMillis)}  $method $path  $status  $message"
    }

    private companion object {
        fun formatTime(timestampMillis: Long): String {
            return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMillis))
        }
    }
}

class ServerLinkLogStore(
    private val maxEntries: Int = 50,
    initialEntries: List<ServerLinkLogEntry> = emptyList(),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val onEntriesChanged: (List<ServerLinkLogEntry>) -> Unit = {},
) {
    private val lock = Any()
    private val mutableEntries = initialEntries.takeLast(maxEntries).toMutableList()

    val entries: List<ServerLinkLogEntry>
        get() = synchronized(lock) { mutableEntries.toList() }

    fun record(
        method: String,
        path: String,
        statusCode: Int?,
        message: String,
    ) {
        val snapshot = synchronized(lock) {
            mutableEntries += ServerLinkLogEntry(
                timestampMillis = nowProvider(),
                method = method,
                path = path,
                statusCode = statusCode,
                message = message.ifBlank { "No message" },
            )
            while (mutableEntries.size > maxEntries) {
                mutableEntries.removeAt(0)
            }
            mutableEntries.toList()
        }
        onEntriesChanged(snapshot)
    }
}

object ServerLinkLogCodec {
    fun encode(entries: List<ServerLinkLogEntry>): String {
        return entries.joinToString("\n") { entry ->
            listOf(
                entry.timestampMillis.toString(),
                encodePart(entry.method),
                encodePart(entry.path),
                entry.statusCode?.toString().orEmpty(),
                encodePart(entry.message),
            ).joinToString("\t")
        }
    }

    fun decode(raw: String): List<ServerLinkLogEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 5) return@mapNotNull null
            val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
            ServerLinkLogEntry(
                timestampMillis = timestamp,
                method = decodePart(parts[1]),
                path = decodePart(parts[2]),
                statusCode = parts[3].toIntOrNull(),
                message = decodePart(parts[4]),
            )
        }.toList()
    }

    private fun encodePart(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun decodePart(value: String): String {
        return String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }
}
