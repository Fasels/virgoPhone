package com.example.virgo

internal fun reminderButtonLabel(): String = "EZ Copy"

internal fun reminderCardTitle(menuId: String): String = menuId

internal fun reminderCardPreview(text: String, maxLength: Int = 48): String {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.length <= maxLength) return normalized
    return normalized.take((maxLength - 3).coerceAtLeast(0)).trimEnd() + "..."
}
