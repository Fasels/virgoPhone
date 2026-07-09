package me.capcom.smsgateway.modules.receiver

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.gateway.GatewayInboxMapper
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.incoming.IncomingMessagesService
import me.capcom.smsgateway.modules.incoming.db.IncomingMessageType
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import me.capcom.smsgateway.modules.webhooks.payload.SmsEventPayload
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Date

class ReceiverService : KoinComponent {
    private val webHooksService: WebHooksService by inject()
    private val logsService: LogsService by inject()
    private val incomingMessagesService: IncomingMessagesService by inject()
    private val gatewayService: GatewayService by inject()

    private val eventsReceiver by lazy { EventsReceiver() }

    fun start(context: Context) {
        MessagesReceiver.register(context)
        eventsReceiver.start()
    }

    fun stop(context: Context) {
        eventsReceiver.stop()
        MessagesReceiver.unregister(context)
    }

    suspend fun export(
        context: Context,
        period: Pair<Date, Date>,
        messageTypes: Set<IncomingMessageType>,
        triggerWebhooks: Boolean
    ) = withContext(Dispatchers.IO) {
        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::export - start",
            mapOf("period" to period, "messageTypes" to messageTypes)
        )

        select(context, period, messageTypes)
            .forEach {
                process(context, it, triggerWebhooks)
            }

        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::export - end",
            mapOf("period" to period, "messageTypes" to messageTypes)
        )
    }

    fun process(context: Context, message: InboxMessage, triggerWebhooks: Boolean) {
        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::process - message received",
            mapOf("message" to message)
        )

        when (message) {
            is InboxMessage.MmsHeaders,
            is InboxMessage.MMS -> {
                logsService.insert(
                    LogEntry.Priority.DEBUG,
                    MODULE_NAME,
                    "ReceiverService::process - MMS support disabled, skipping",
                    mapOf("message" to message)
                )
                return
            }

            else -> Unit
        }

        // Dedup safety net: skip if this exact message was already processed
        if (incomingMessagesService.isMessageProcessed(message)) {
            logsService.insert(
                LogEntry.Priority.DEBUG,
                MODULE_NAME,
                "ReceiverService::process - duplicate message, skipping",
                mapOf("message" to message)
            )
            return
        }

        val simSlotIndex = message.subscriptionId?.let {
            SubscriptionsHelper.getSimSlotIndex(context, it)
        }
        val simNumber = simSlotIndex?.let { it + 1 }
        val recipient = simSlotIndex?.let {
            SubscriptionsHelper.getPhoneNumber(context, it)
        }

        val incoming = incomingMessagesService.save(message)

        if (triggerWebhooks) {
            enqueueGatewayInboxUpload(context, incoming.id, message, simNumber, recipient)

            val (type, payload) = when (message) {
                is InboxMessage.Text -> WebHookEvent.SmsReceived to SmsEventPayload.SmsReceived(
                    messageId = message.hashCode().toUInt().toString(16),
                    message = message.text,
                    sender = incoming.sender,
                    simNumber = simNumber,
                    receivedAt = message.date,
                    recipient = recipient,
                )

                is InboxMessage.Data -> WebHookEvent.SmsDataReceived to SmsEventPayload.SmsDataReceived(
                    messageId = message.hashCode().toUInt().toString(16),
                    data = Base64.encodeToString(message.data, Base64.NO_WRAP),
                    simNumber = simNumber,
                    receivedAt = message.date,
                    sender = incoming.sender,
                    recipient = recipient,
                )

                is InboxMessage.MmsHeaders,
                is InboxMessage.MMS -> return
            }

            webHooksService.emit(context, type, payload)
        }

        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::process - message processed",
        )
    }

    private fun enqueueGatewayInboxUpload(
        context: Context,
        messageId: String,
        message: InboxMessage,
        simNumber: Int?,
        recipient: String?,
    ) {
        when (message) {
            is InboxMessage.Text,
            is InboxMessage.Data -> {
                try {
                    gatewayService.enqueueInboxMessage(
                        context,
                        GatewayInboxMapper.toRequest(
                            messageId = messageId,
                            message = message,
                            simNumber = simNumber,
                            recipient = recipient,
                        )
                    )
                } catch (e: Exception) {
                    logsService.insert(
                        LogEntry.Priority.ERROR,
                        MODULE_NAME,
                        "Failed to enqueue gateway inbox upload",
                        mapOf(
                            "messageId" to messageId,
                            "exception" to e.stackTraceToString(),
                        )
                    )
                }
            }

            is InboxMessage.MmsHeaders,
            is InboxMessage.MMS -> {
                logsService.insert(
                    LogEntry.Priority.DEBUG,
                    MODULE_NAME,
                    "Gateway inbox upload skipped for unsupported MMS message",
                    mapOf("messageId" to messageId)
                )
            }
        }
    }


    fun select(
        context: Context,
        period: Pair<Date, Date>,
        messageTypes: Set<IncomingMessageType>
    ): List<InboxMessage> {
        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::select - start",
            mapOf("period" to period, "messageTypes" to messageTypes)
        )

        val messages = buildList {
            if (IncomingMessageType.SMS in messageTypes) {
                addAll(selectSms(context, period))
            }
        }.sortedBy { it.date.time }

        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::select - end",
            mapOf("messages" to messages.size, "messageTypes" to messageTypes)
        )

        return messages
    }

    private fun selectSms(context: Context, period: Pair<Date, Date>): List<InboxMessage> {
        val projection = mutableListOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            projection += Telephony.Sms.SUBSCRIPTION_ID
        }

        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?"
        val selectionArgs = arrayOf(
            period.first.time.toString(),
            period.second.time.toString()
        )
        val sortOrder = Telephony.Sms.DATE

        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection.toTypedArray(),
            selection,
            selectionArgs,
            sortOrder
        )

        val messages = mutableListOf<InboxMessage>()

        cursor?.use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(
                    InboxMessage.Text(
                        address = cursor.getString(1),
                        date = Date(cursor.getLong(2)),
                        text = cursor.getString(3),
                        subscriptionId = when {
                            projection.size > 4 -> cursor.getInt(4).takeIf { it >= 0 }
                            else -> null
                        }
                    )
                )
            }
        }

        return messages
    }

}
