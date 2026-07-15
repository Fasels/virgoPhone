package me.capcom.smsgateway.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.services.ResidentForegroundService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class BootReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = get<SettingsHelper>()
        if (!shouldStartResidentService(intent.action, settings.autostart)) return

        try {
            ResidentForegroundService.start(context)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to restore resident service", error)
        }
    }

    companion object {
        private val events = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.ACTION_BOOT_COMPLETED",
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        private const val TAG = "BootReceiver"

        internal fun accepts(action: String?): Boolean = action in events
    }
}

internal fun shouldStartResidentService(
    action: String?,
    autostartEnabled: Boolean,
): Boolean = autostartEnabled && BootReceiver.accepts(action)
