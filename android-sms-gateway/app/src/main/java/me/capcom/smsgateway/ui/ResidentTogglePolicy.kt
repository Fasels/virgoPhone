package me.capcom.smsgateway.ui

internal fun handleResidentToggle(
    enabled: Boolean,
    startResident: () -> Unit,
    continueStartFlow: () -> Unit,
    stopResident: () -> Unit,
) {
    if (enabled) {
        startResident()
        continueStartFlow()
    } else {
        stopResident()
    }
}
