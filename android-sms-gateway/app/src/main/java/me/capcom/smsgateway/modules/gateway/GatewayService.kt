package me.capcom.smsgateway.modules.gateway

import android.content.Context
import android.os.Build
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import me.capcom.smsgateway.data.entities.MessageWithRecipients
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.MessageContent
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.gateway.events.DeviceRegisteredEvent
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.gateway.workers.PullMessagesWorker
import me.capcom.smsgateway.modules.gateway.workers.SendStateWorker
import me.capcom.smsgateway.modules.gateway.workers.SendInboxMessageWorker
import me.capcom.smsgateway.modules.gateway.workers.SettingsUpdateWorker
import me.capcom.smsgateway.modules.gateway.workers.WebhooksUpdateWorker
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.MessagesSettings
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.services.PushService
import java.util.Date

class GatewayService(
    private val messagesService: MessagesService,
    private val settings: GatewaySettings,
    private val events: EventBus,
    private val logsService: LogsService,
) {
    private val eventsReceiver by lazy { EventsReceiver() }

    private var _api: GatewayApi? = null

    private val api
        get() = _api ?: GatewayApi(
            settings.serverUrl,
            settings.privateToken
        ).also { _api = it }

    //region Start, stop, etc...
    fun start(context: Context) {
        if (!settings.enabled) return

        PushService.register(context)
        if (settings.registrationInfo?.token != null) {
            SSEForegroundService.start(context)
        }
        PullMessagesWorker.start(context)
        WebhooksUpdateWorker.start(context)
        SettingsUpdateWorker.start(context)

        eventsReceiver.start()
    }

    fun stop(context: Context) {
        eventsReceiver.stop()

        SSEForegroundService.stop(context)
        SettingsUpdateWorker.stop(context)
        WebhooksUpdateWorker.stop(context)
        PullMessagesWorker.stop(context)

        this._api = null
    }

    fun isActiveLiveData(context: Context) = PullMessagesWorker.getStateLiveData(context)
    //endregion

    //region Account
    suspend fun getLoginCode(): GatewayApi.GetUserCodeResponse {
        val password = settings.password
            ?: throw IllegalStateException("Password is not set")

        return getLoginCodeWithPassword(password)
    }

    suspend fun getLoginCodeWithPassword(password: String): GatewayApi.GetUserCodeResponse {
        val username = settings.username
            ?: throw IllegalStateException("Username is not set")

        return api.getUserCode(username to password)
    }

    suspend fun changePassword(current: String, new: String) {
        val info = settings.registrationInfo
            ?: throw IllegalStateException("The device is not registered on the server")

        this.api.changeUserPassword(
            info.token,
            GatewayApi.PasswordChangeRequest(current, new)
        )

        settings.registrationInfo = info.copy(password = new)

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                info.login,
                new,
            )
        )
    }
    //endregion

    //region Device
    internal suspend fun registerDevice(
        context: Context,
        pushToken: String?,
        registerMode: RegistrationMode
    ) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo
        val accessToken = settings?.token

        if (accessToken != null) {
            // if there's an access token, try to update push token
            try {
                updateDevice(context, pushToken)
                return
            } catch (e: ClientRequestException) {
                // if token is invalid, try to register new one
                if (e.response.status != HttpStatusCode.Unauthorized) {
                    throw e
                }
            }
        }

        try {
            val deviceName = "${Build.MANUFACTURER}/${Build.PRODUCT}"
            val simCards = SubscriptionsHelper.getActiveSimCards(context)
            val request = GatewayApi.DeviceRegisterRequest(
                deviceName,
                pushToken,
                GatewaySimCardMapper.toDto(simCards),
            )
            val response = when (registerMode) {
                RegistrationMode.Anonymous -> api.deviceRegister(request, null)
                is RegistrationMode.WithCode -> api.deviceRegister(request, registerMode.code)
                is RegistrationMode.WithCredentials -> api.deviceRegister(
                    request,
                    registerMode.login to registerMode.password
                )
            }

            this.settings.fcmToken = pushToken
            this.settings.registrationInfo = response

            events.emit(
                DeviceRegisteredEvent.Success(
                    api.hostname,
                    response.login,
                    response.password,
                )
            )
        } catch (th: Throwable) {
            events.emit(
                DeviceRegisteredEvent.Failure(
                    api.hostname,
                    th.localizedMessage ?: th.message ?: th.toString()
                )
            )

            throw th
        }
    }

    internal suspend fun updateDevice(context: Context, pushToken: String?) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo ?: return
        val accessToken = settings.token
        val simCards = SubscriptionsHelper.getActiveSimCards(context)

        api.devicePatch(
            accessToken,
            GatewayApi.DevicePatchRequest(
                settings.id,
                pushToken,
                GatewaySimCardMapper.toDto(simCards),
            )
        )

        this.settings.fcmToken = pushToken

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                settings.login,
                settings.password,
            )
        )
    }

    sealed class RegistrationMode {
        object Anonymous : RegistrationMode()
        class WithCredentials(val login: String, val password: String) : RegistrationMode()
        class WithCode(val code: String) : RegistrationMode()
    }
    //endregion

    //region Messages
    internal suspend fun getNewMessages(context: Context) {
        if (!settings.enabled) return
        val settings = settings.registrationInfo ?: run {
            logsService.insert(
                LogEntry.Priority.WARN,
                MODULE_NAME,
                "Gateway message pull skipped: device is not registered"
            )
            return
        }
        val processingOrder = when (messagesService.processingOrder) {
            MessagesSettings.ProcessingOrder.LIFO -> GatewayApi.ProcessingOrder.LIFO
            MessagesSettings.ProcessingOrder.FIFO -> GatewayApi.ProcessingOrder.FIFO
        }
        val messages = api.getMessages(settings.token, processingOrder)
        for (message in messages) {
            try {
                processMessage(context, message)
            } catch (th: Throwable) {
                logsService.insert(
                    LogEntry.Priority.ERROR,
                    MODULE_NAME,
                    "Failed to process message",
                    mapOf(
                        "message" to message,
                        "exception" to th.stackTraceToString(),
                    )
                )
                th.printStackTrace()
            }
        }
    }

    private fun processMessage(context: Context, message: GatewayApi.Message) {
        val messageState = messagesService.getMessage(message.id)
        if (messageState != null) {
            SendStateWorker.start(context, message.id)
            return
        }

        val request = SendRequest(
            EntitySource.Cloud,
            me.capcom.smsgateway.modules.messages.data.Message(
                message.id,
                when (val content = message.content) {
                    is GatewayApi.MessageContent.Text -> MessageContent.Text(content.text)
                    is GatewayApi.MessageContent.Data -> MessageContent.Data(
                        content.data,
                        content.port
                    )
                },
                message.phoneNumbers,
                message.isEncrypted ?: false,
                message.createdAt ?: Date(),
            ),
            SendParams(
                message.withDeliveryReport ?: true,
                skipPhoneValidation = true,
                simNumber = message.simNumber,
                validUntil = message.validUntil,
                scheduleAt = message.scheduleAt,
                priority = message.priority,
            )
        )
        messagesService.enqueueMessage(request)
    }

    internal suspend fun sendState(
        message: MessageWithRecipients
    ) {
        val settings = settings.registrationInfo ?: return
        val patch = buildMessagePatchRequest(message)
        if (!patch.shouldReportToGateway()) return
        val request = listOf(patch)

        try {
            api.patchMessages(settings.token, request)
        } catch (e: ClientRequestException) {
            val responseBody = try {
                e.response.body<String>()
            } catch (_: Exception) {
                null
            }
            logsService.insert(
                LogEntry.Priority.ERROR,
                MODULE_NAME,
                "Gateway message status update rejected by server",
                mapOf(
                    "status" to e.response.status.value,
                    "response" to responseBody,
                    "messageId" to message.message.id,
                    "state" to patch.state,
                    "recipientCount" to message.recipients.size,
                    "stateHistory" to patch.states.keys.map { it.name },
                )
            )
            throw e
        }
    }

    internal fun enqueueInboxMessage(
        context: Context,
        request: GatewayApi.InboxMessageRequest
    ) {
        if (!settings.enabled) return
        if (settings.registrationInfo == null) return

        SendInboxMessageWorker.start(context, request)
    }

    internal suspend fun sendInboxMessage(request: GatewayApi.InboxMessageRequest) {
        if (!settings.enabled) return
        val settings = settings.registrationInfo ?: return

        try {
            api.postInboxMessage(settings.token, request)
        } catch (e: ClientRequestException) {
            val responseBody = try {
                e.response.body<String>()
            } catch (_: Exception) {
                null
            }
            logsService.insert(
                LogEntry.Priority.ERROR,
                MODULE_NAME,
                "Gateway inbox upload rejected by server",
                mapOf(
                    "status" to e.response.status.value,
                    "response" to responseBody,
                    "requestId" to request.id,
                    "type" to request.type,
                    "hasText" to !request.textMessage?.text.isNullOrBlank(),
                    "textLength" to (request.textMessage?.text?.length ?: 0),
                    "hasSender" to request.sender.isNotBlank(),
                    "hasRecipient" to !request.recipient.isNullOrBlank(),
                    "simNumber" to request.simNumber,
                    "subscriptionId" to request.subscriptionId,
                )
            )
            throw e
        }
    }
    //endregion

    //region Webhooks
    internal suspend fun getWebHooks(): List<GatewayApi.WebHook> {
        val settings = settings.registrationInfo
        return if (settings != null) {
            api.getWebHooks(settings.token)
        } else {
            emptyList()
        }
    }
    //endregion

    //region Settings
    internal suspend fun getSettings(): Map<String, *>? {
        val settings = settings.registrationInfo ?: return null

        return api.getSettings(settings.token)
    }
    //endregion

    //region Utility
    suspend fun getPublicIP(): String {
        return GatewayApi(
            settings.serverUrl,
            settings.privateToken
        )
            .getDevice(settings.registrationInfo?.token)
            .externalIp
    }
    //endregion

}

internal fun buildMessagePatchRequest(
    message: MessageWithRecipients,
    now: Date = Date(),
): GatewayApi.MessagePatchRequest {
    val currentState = message.state
    val stateTimestamps = message.states
        .groupBy { it.state }
        .mapValues { (_, states) -> Date(states.maxOf { it.updatedAt }) }
        .toMutableMap()

    val currentStateTimestamp = stateTimestamps[currentState] ?: now
    stateTimestamps[currentState] = currentStateTimestamp

    val states = buildServerCompatibleStateHistory(
        currentState,
        stateTimestamps,
        currentStateTimestamp,
    )

    return GatewayApi.MessagePatchRequest(
        id = message.message.id,
        state = currentState,
        recipients = message.recipients.map { recipient ->
            GatewayApi.RecipientState(
                recipient.phoneNumber,
                recipient.state,
                recipient.error.takeIf { recipient.state == ProcessingState.Failed }
            )
        },
        states = states
    )
}

private fun buildServerCompatibleStateHistory(
    currentState: ProcessingState,
    stateTimestamps: Map<ProcessingState, Date>,
    currentStateTimestamp: Date,
): Map<ProcessingState, Date> {
    val result = linkedMapOf<ProcessingState, Date>()

    fun putIfKnown(state: ProcessingState) {
        stateTimestamps[state]?.let { result[state] = it }
    }

    when (currentState) {
        ProcessingState.Pending -> putIfKnown(ProcessingState.Pending)
        ProcessingState.Processed -> putIfKnown(ProcessingState.Processed)
        ProcessingState.Sent -> putIfKnown(ProcessingState.Sent)
        ProcessingState.Delivered -> {
            result[ProcessingState.Sent] =
                stateTimestamps[ProcessingState.Sent] ?: currentStateTimestamp
            result[ProcessingState.Delivered] = currentStateTimestamp
        }
        ProcessingState.Failed -> putIfKnown(ProcessingState.Failed)
    }

    return result.ifEmpty { mapOf(currentState to currentStateTimestamp) }
}

internal fun GatewayApi.MessagePatchRequest.shouldReportToGateway(): Boolean {
    return state != ProcessingState.Pending
}
