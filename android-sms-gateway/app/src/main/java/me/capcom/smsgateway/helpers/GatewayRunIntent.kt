package me.capcom.smsgateway.helpers

import android.content.Context
import androidx.preference.PreferenceManager

/** Explicit user intent is independent of connectivity, worker state and service restarts. */
object GatewayRunIntent {
    private const val KEY = "resident.requestedRunning"

    fun isRequested(context: Context): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return resolveGatewayRunIntent(
            preferences.contains(KEY), preferences.getBoolean(KEY, false),
            SettingsHelper(context).autostart,
        )
    }

    fun setRequested(context: Context, requested: Boolean) {
        // A stop must be durable before late callbacks or sticky starts can observe it.
        check(PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(KEY, requested).commit()) { "Unable to persist gateway run intent" }
    }
}

internal fun resolveGatewayRunIntent(hasExplicitIntent: Boolean, requested: Boolean, autostart: Boolean): Boolean =
    if (hasExplicitIntent) requested else autostart
