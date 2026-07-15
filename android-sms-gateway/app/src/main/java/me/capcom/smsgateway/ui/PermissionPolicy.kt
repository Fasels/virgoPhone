package me.capcom.smsgateway.ui

import android.Manifest
import android.os.Build

internal fun requiredRuntimePermissions(sdkInt: Int): List<String> = buildList {
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_MMS)
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
