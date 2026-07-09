package me.capcom.smsgateway.modules.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony.Sms.Intents
import android.util.Log
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Date

class MessagesReceiver : BroadcastReceiver(), KoinComponent {
    private val receiverSvc: ReceiverService by inject()
    private val logsService: LogsService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intents.SMS_RECEIVED_ACTION
            && intent.action != Intents.DATA_SMS_RECEIVED_ACTION
        ) {
            return
        }

        val messages = Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val isDataMessage = intent.action == Intents.DATA_SMS_RECEIVED_ACTION
        val firstMessage = messages.first()
        val textLength = when (isDataMessage) {
            false -> messages.sumOf { it.displayMessageBody?.length ?: 0 }
            true -> 0
        }
        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "MessagesReceiver::onReceive - broadcast message",
            mapOf(
                "action" to intent.action,
                "rawType" to if (isDataMessage) "DATA_SMS" else "SMS",
                "finalType" to if (isDataMessage) "DATA_SMS" else "SMS",
                "segmentCount" to messages.size,
                "hasAttachment" to false,
                "hasSubject" to false,
                "textLength" to textLength,
                "reason" to if (isDataMessage) "data_sms_pdu_broadcast" else "sms_pdu_broadcast",
            )
        )

        val inboxMessage = when (isDataMessage) {
            false -> InboxMessage.Text(
                messages.joinToString(separator = "") { it.displayMessageBody },
                firstMessage.displayOriginatingAddress,
                Date(firstMessage.timestampMillis),
                SubscriptionsHelper.extractSubscriptionId(context, intent)
            )

            true -> InboxMessage.Data(
                firstMessage.userData,
                firstMessage.displayOriginatingAddress,
                Date(firstMessage.timestampMillis),
                SubscriptionsHelper.extractSubscriptionId(context, intent)
            )
        }

        receiverSvc.process(
            context,
            inboxMessage,
            true,
            intent.action,
        )
    }

    companion object {
        private const val TAG = "MessagesReceiver"

        private val INSTANCE: MessagesReceiver by lazy { MessagesReceiver() }

        fun register(context: Context) {
            val appContext = context.applicationContext
            unregister(appContext)

            val textFilter = IntentFilter().apply {
                addAction(Intents.SMS_RECEIVED_ACTION)
            }
            appContext.registerReceiver(
                INSTANCE,
                textFilter
            )

            val dataFilter = IntentFilter().apply {
                addAction(Intents.DATA_SMS_RECEIVED_ACTION)
                addDataScheme("sms")
                addDataAuthority("*", "53739")
            }
            appContext.registerReceiver(
                INSTANCE,
                dataFilter
            )
        }

        fun unregister(context: Context) {
            try {
                context.applicationContext.unregisterReceiver(INSTANCE)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered", e)
            }
        }
    }
}
