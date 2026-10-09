package me.capcom.smsgateway.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MODULE_NAME
import me.capcom.smsgateway.modules.messages.MessagesService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class EventsReceiver : BroadcastReceiver(), KoinComponent {

    private val messagesService: MessagesService by inject()
    private val logsService: LogsService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        // The receiver instance can receive another broadcast before this coroutine runs.
        // Capture its result while it still belongs to this onReceive invocation.
        val receivedResultCode = resultCode
        val receivedIntent = Intent(intent)
        val pendingResult = goAsync()
        val safeAction = receivedIntent.action.takeIf { it == ACTION_SENT || it == ACTION_DELIVERED } ?: "unrecognized"
        val safeMessageId = receivedIntent.dataString?.substringBefore('|')
            ?.takeIf { SAFE_MESSAGE_ID.matches(it) } ?: "unrecognized"
        Log.i("SmsStateCallback", "action=$safeAction code=$receivedResultCode id=$safeMessageId")
        scope.launch(Dispatchers.IO) {
            try {
                messagesService
                    .processStateIntent(receivedIntent, receivedResultCode)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                logsService.insert(
                    LogEntry.Priority.ERROR,
                    MODULE_NAME,
                    "Can't process message state intent",
                    mapOf(
                        "action" to safeAction,
                        "resultCode" to receivedResultCode,
                        "messageId" to safeMessageId,
                        "exception" to e.javaClass.simpleName,
                    )
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job)
        private val SAFE_MESSAGE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        private var INSTANCE: EventsReceiver? = null

        const val ACTION_SENT = "me.capcom.smsgateway.ACTION_SENT"
        const val ACTION_DELIVERED = "me.capcom.smsgateway.ACTION_DELIVERED"

        private fun getInstance(): EventsReceiver {
            return INSTANCE ?: EventsReceiver().also { INSTANCE = it }
        }

        fun register(context: Context) {
            context.registerReceiver(
                getInstance(),
                IntentFilter(ACTION_SENT)
                    .apply { addAction(ACTION_DELIVERED) }
            )
        }
    }
}
