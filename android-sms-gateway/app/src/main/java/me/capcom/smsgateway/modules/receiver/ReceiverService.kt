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
import me.capcom.smsgateway.modules.webhooks.payload.MmsDownloadedPayload
import me.capcom.smsgateway.modules.webhooks.payload.MmsReceivedPayload
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
    private val mmsContentObserver by lazy { MmsContentObserver() }

    fun start(context: Context) {
        MessagesReceiver.register(context)
        MmsReceiver.register(context)
        eventsReceiver.start()
        mmsContentObserver.start()
    }

    fun stop(context: Context) {
        mmsContentObserver.stop()
        eventsReceiver.stop()
        MmsReceiver.unregister(context)
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
                process(context, it, triggerWebhooks, "content_provider_export")
            }

        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::export - end",
            mapOf("period" to period, "messageTypes" to messageTypes)
        )
    }

    fun process(
        context: Context,
        message: InboxMessage,
        triggerWebhooks: Boolean,
        action: String? = null,
    ) {
        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::process - message received",
            mapOf("message" to message)
        )

        val classification = InboxMessageClassifier.classify(message, action)
        val classifiedMessage = classification.message
        logsService.insert(
            LogEntry.Priority.DEBUG,
            MODULE_NAME,
            "ReceiverService::process - message classification",
            mapOf(
                "action" to action,
                "rawType" to classification.rawType,
                "finalType" to classification.finalType,
                "hasAttachment" to classification.hasAttachment,
                "hasSubject" to classification.hasSubject,
                "textLength" to classification.textLength,
                "reason" to classification.reason,
            )
        )

        // Dedup safety net: skip if this exact message was already processed
        if (incomingMessagesService.isMessageProcessed(classifiedMessage)) {
            logsService.insert(
                LogEntry.Priority.DEBUG,
                MODULE_NAME,
                "ReceiverService::process - duplicate message, skipping",
                mapOf(
                    "rawType" to classification.rawType,
                    "finalType" to classification.finalType,
                    "reason" to classification.reason,
                )
            )
            return
        }

        val simSlotIndex = classifiedMessage.subscriptionId?.let {
            SubscriptionsHelper.getSimSlotIndex(context, it)
        }
        val simNumber = simSlotIndex?.let { it + 1 }
        val recipient = simSlotIndex?.let {
            SubscriptionsHelper.getPhoneNumber(context, it)
        }

        val incoming = incomingMessagesService.save(classifiedMessage)

        if (triggerWebhooks) {
            enqueueGatewayInboxUpload(
                context,
                incoming.id,
                classifiedMessage,
                simNumber,
                recipient,
                classification
            )

            val (type, payload) = when (classifiedMessage) {
                is InboxMessage.Text -> WebHookEvent.SmsReceived to SmsEventPayload.SmsReceived(
                    messageId = classifiedMessage.hashCode().toUInt().toString(16),
                    message = classifiedMessage.text,
                    sender = incoming.sender,
                    simNumber = simNumber,
                    receivedAt = classifiedMessage.date,
                    recipient = recipient,
                )

                is InboxMessage.Data -> WebHookEvent.SmsDataReceived to SmsEventPayload.SmsDataReceived(
                    messageId = classifiedMessage.hashCode().toUInt().toString(16),
                    data = Base64.encodeToString(classifiedMessage.data, Base64.NO_WRAP),
                    simNumber = simNumber,
                    receivedAt = classifiedMessage.date,
                    sender = incoming.sender,
                    recipient = recipient,
                )

                is InboxMessage.MmsHeaders -> WebHookEvent.MmsReceived to MmsReceivedPayload(
                    messageId = classifiedMessage.messageId ?: classifiedMessage.transactionId,
                    simNumber = simNumber,
                    transactionId = classifiedMessage.transactionId,
                    subject = classifiedMessage.subject,
                    size = classifiedMessage.size,
                    contentClass = classifiedMessage.contentClass,
                    receivedAt = classifiedMessage.date,
                    sender = incoming.sender,
                    recipient = recipient,
                )

                is InboxMessage.MMS -> WebHookEvent.MmsDownloaded to MmsDownloadedPayload(
                    messageId = classifiedMessage.messageId,
                    sender = incoming.sender,
                    recipient = recipient,
                    simNumber = simNumber,
                    body = classifiedMessage.body,
                    subject = classifiedMessage.subject,
                    attachments = classifiedMessage.attachments.map {
                        MmsDownloadedPayload.Attachment(
                            partId = it.partId,
                            contentType = it.contentType,
                            name = it.name,
                            size = it.size,
                            data = it.data
                        )
                    },
                    receivedAt = classifiedMessage.date,
                )
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
        classification: InboxMessageClassifier.Result,
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
                    mapOf(
                        "messageId" to messageId,
                        "rawType" to classification.rawType,
                        "finalType" to classification.finalType,
                        "hasAttachment" to classification.hasAttachment,
                        "hasSubject" to classification.hasSubject,
                        "textLength" to classification.textLength,
                        "reason" to classification.reason,
                    )
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
            if (IncomingMessageType.MMS in messageTypes) {
                addAll(selectMms(context, period))
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

    private fun selectMms(context: Context, period: Pair<Date, Date>): List<InboxMessage> {
        val startSeconds = period.first.time / 1000
        val endSeconds = period.second.time / 1000

        val projection = arrayOf(Telephony.Mms._ID)
        // m_type 132 = retrieve-conf (fully downloaded MMS); date is in seconds
        val selection = "${Telephony.Mms.MESSAGE_TYPE} = 132 AND " +
                "${Telephony.Mms.DATE} >= ? AND ${Telephony.Mms.DATE} <= ?"
        val selectionArgs = arrayOf(startSeconds.toString(), endSeconds.toString())

        val cursor = context.contentResolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Mms.DATE} ASC, ${Telephony.Mms._ID} ASC"
        ) ?: return emptyList()

        val messages = mutableListOf<InboxMessage>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                val message = MmsContentReader.read(context, mmsId) ?: continue
                messages.add(
                    InboxMessage.MMS(
                        messageId = mmsId.toString(),
                        body = message.body,
                        subject = message.subject,
                        attachments = message.attachments.map {
                            InboxMessage.MMS.Attachment(
                                partId = it.partId,
                                contentType = it.contentType,
                                name = it.name,
                                size = it.size,
                                data = it.data
                            )
                        },
                        address = message.sender,
                        date = message.date,
                        subscriptionId = message.subscriptionId,
                        totalPartCount = message.totalPartCount,
                        textPartCount = message.textPartCount,
                        smilPartCount = message.smilPartCount,
                        sourceAction = "content_provider_export",
                        rawType = "MMS_DOWNLOADED"
                    )
                )
            }
        }

        return messages
    }
}
