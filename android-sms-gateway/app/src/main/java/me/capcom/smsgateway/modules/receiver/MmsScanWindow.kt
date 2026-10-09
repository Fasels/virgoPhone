package me.capcom.smsgateway.modules.receiver

internal fun initialMmsScanSinceSeconds(
    lastUpdateTimeMillis: Long,
    currentTimeMillis: Long,
): Long = (
    lastUpdateTimeMillis.takeIf { it > 0 && it <= currentTimeMillis }
        ?: currentTimeMillis
    ) / 1000
