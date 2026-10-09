package me.capcom.smsgateway.helpers

/** A missing or placeholder modem value must never erase a server number. */
internal fun readablePhoneNumber(value: String?): String? {
    val number = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = number.replace(Regex("[\\s()-]"), "")
    if (!normalized.matches(Regex("\\+?[0-9]+"))) return null
    var digits = normalized.removePrefix("+")
    if (!normalized.startsWith("+") && digits.length == 10) digits = "1$digits"
    if (digits.startsWith("1")) {
        if (digits.length != 11) return null
        val national = digits.substring(1)
        if (national[0] !in '2'..'9' || national[3] !in '2'..'9' ||
            national.toSet().size == 1) return null
    } else if (!normalized.startsWith("+") || digits.length !in 7..15 ||
        digits.startsWith("0") || digits.toSet().size == 1) return null
    return number
}

internal fun readPhoneNumberFromSources(primary: () -> String?, fallback: () -> String?): String? {
    fun safelyRead(source: () -> String?): String? = try {
        readablePhoneNumber(source())
    } catch (_: RuntimeException) {
        null
    }
    return safelyRead(primary) ?: safelyRead(fallback)
}
