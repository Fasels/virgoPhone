package me.capcom.smsgateway.modules.orchestrator

import android.content.Context
import android.util.Log
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.localserver.LocalServerService
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.ping.PingService
import me.capcom.smsgateway.modules.receiver.ReceiverService
import me.capcom.smsgateway.modules.webhooks.WebHooksService

class OrchestratorService(
    private val messagesSvc: MessagesService,
    private val gatewaySvc: GatewayService,
    private val localServerSvc: LocalServerService,
    private val webHooksSvc: WebHooksService,
    private val receiverService: ReceiverService,
    private val pingSvc: PingService,
    private val logsSvc: LogsService,
) : ResidentRuntime {
    private val moduleStartup = ModuleStartup()

    override fun start(context: Context) {
        moduleStartup.start(
            modules = listOf(
                ModuleStart("logs") { logsSvc.start(context) },
                ModuleStart("messages") { messagesSvc.start(context) },
                ModuleStart("webhooks") { webHooksSvc.start(context) },
                ModuleStart("gateway") { gatewaySvc.start(context) },
                ModuleStart("localServer") { localServerSvc.start(context) },
                ModuleStart("ping") { pingSvc.start(context) },
                ModuleStart("receiver") { receiverService.start(context) },
            ),
            onFailure = { name, error -> logFailure("start", name, error) },
        )
    }

    override fun stop(context: Context) {
        stopModule("receiver") { receiverService.stop(context) }
        stopModule("ping") { pingSvc.stop(context) }
        stopModule("localServer") { localServerSvc.stop(context) }
        stopModule("gateway") { gatewaySvc.stop(context) }
        stopModule("webhooks") { webHooksSvc.stop(context) }
        stopModule("messages") { messagesSvc.stop(context) }
        stopModule("logs") { logsSvc.stop(context) }
        moduleStartup.reset()
    }

    private fun stopModule(name: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            logFailure("stop", name, error)
        }
    }

    private fun logFailure(operation: String, name: String, error: Throwable) {
        Log.e(MODULE_NAME, "Failed to $operation module: $name", error)
        try {
            logsSvc.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "Failed to $operation module: $name",
                mapOf("exception" to error.stackTraceToString()),
            )
        } catch (_: Throwable) {
            // Android logging above remains available if the database logger is unavailable.
        }
    }
}
