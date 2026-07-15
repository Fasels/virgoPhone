package me.capcom.smsgateway.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class BootReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        if (!events.contains(intent.action)) return

        get<LogsService>().insert(
            LogEntry.Priority.INFO,
            "gateway",
            "BOOT_RECEIVER_TRIGGERED",
            mapOf("action" to intent.action)
        )

        get<OrchestratorService>().start(context, true)
        val gatewaySettings = get<GatewaySettings>()
        if (gatewaySettings.enabled && gatewaySettings.registrationInfo?.token != null) {
            SSEForegroundService.start(context)
        }
    }

    companion object {
        private val events = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.ACTION_BOOT_COMPLETED",
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
