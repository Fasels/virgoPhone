package me.capcom.smsgateway.modules.gateway

import android.content.Context

/** Seal the original route of pre-upgrade status records once; never store bearer credentials. */
internal class LegacyStatusIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("gateway_legacy_status_v24", Context.MODE_PRIVATE)

    @Synchronized fun seal(messageIds: List<String>, deviceId: String?, serverUrl: String) {
        if (prefs.getBoolean("sealed", false)) return
        val original = if (deviceId.isNullOrBlank()) "UNBOUND" else "$deviceId\n$serverUrl"
        val editor = prefs.edit()
        for (id in messageIds) editor.putString("message:$id", original)
        check(editor.putBoolean("sealed", true).commit()) { "Cannot preserve legacy status identity" }
    }

    fun matches(messageId: String, deviceId: String, serverUrl: String): Boolean =
        legacyStatusIdentityMatches(prefs.getString("message:$messageId", null), deviceId, serverUrl)
}

internal fun legacyStatusIdentityMatches(stored: String?, deviceId: String, serverUrl: String): Boolean =
    !deviceId.isBlank() && stored == "$deviceId\n$serverUrl"
