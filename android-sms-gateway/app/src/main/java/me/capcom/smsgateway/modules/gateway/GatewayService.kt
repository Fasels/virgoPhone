package me.capcom.smsgateway.modules.gateway

import com.google.gson.GsonBuilder
import me.capcom.smsgateway.extensions.configure
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.data.entities.GatewayDelivery
import me.capcom.smsgateway.helpers.GatewayRunIntent
import me.capcom.smsgateway.helpers.GatewaySimIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import android.content.Context
import android.os.Build
import android.util.Log
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import me.capcom.smsgateway.App
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
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
import me.capcom.smsgateway.modules.gateway.workers.SendMmsInboxWorker
import me.capcom.smsgateway.modules.gateway.workers.SmsInboxUploadTask
import me.capcom.smsgateway.modules.gateway.workers.SmsInboxRouteUnavailableException
import me.capcom.smsgateway.modules.gateway.workers.SyncSimMetadataWorker
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
    private val db: AppDatabase,
) {
    private val eventsReceiver by lazy { EventsReceiver() }
    private val subscriptionsMonitor by lazy { SubscriptionsMonitor() }
    private val simMetadataState = SimMetadataSyncState()
    private val simMetadataMutex = Mutex()

    private val deliveryMutex = Mutex()
    private val statusMutex = Mutex()
    private val gson = GsonBuilder().configure().create()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recoveryJob: Job? = null
    private var _api: GatewayApi? = null
    private var apiConfiguration: Pair<String, String?>? = null

    private val api
        @Synchronized get(): GatewayApi {
            val configuration = settings.serverUrl to settings.privateToken
            if (_api == null || configuration != apiConfiguration) {
                _api = GatewayApi(configuration.first, configuration.second)
                apiConfiguration = configuration
            }
            return requireNotNull(_api)
        }

    //region Start, stop, etc...
    fun start(context: Context) {
        if (!settings.enabled || !GatewayRunIntent.isRequested(context)) return

        val generation = simMetadataState.start()
        LegacyStatusIdentity(context).seal(db.messagesDao().legacyStatusMessageIds(),
            settings.registrationInfo?.id, settings.serverUrl)
        subscriptionsMonitor.start(context.applicationContext) {
            if (simMetadataState.current() != null) {
                SyncSimMetadataWorker.requestImmediate(context.applicationContext)
            }
        }
        SyncSimMetadataWorker.start(context.applicationContext)

        PushService.register(context)
        if (settings.registrationInfo?.token != null) {
            SSEForegroundService.start(context)
        }
        PullMessagesWorker.start(context)
        WebhooksUpdateWorker.start(context)
        SettingsUpdateWorker.start(context)

        eventsReceiver.start()
        SendStateWorker.startPeriodic(context.applicationContext)
        SendStateWorker.requestDrain(context.applicationContext)
        SendInboxMessageWorker.start(context.applicationContext)
        SendMmsInboxWorker.start(context.applicationContext, recovery = true)
        if (recoveryJob?.isActive != true) {
            recoveryJob = scope.launch {
                while (sessionCurrent(context, generation)) {
                    delay(60_000)
                    if (!sessionCurrent(context, generation)) break
                    // KEEP restores an unexpectedly cancelled periodic job without replacing a GET.
                    PullMessagesWorker.requestRecovery(context.applicationContext)
                    SendStateWorker.startPeriodic(context.applicationContext)
                    SendStateWorker.requestDrain(context.applicationContext, recovery = true)
                    SendInboxMessageWorker.requestDrain(context.applicationContext, recovery = true)
                    SendMmsInboxWorker.start(context.applicationContext, recovery = true)
                    if (settings.registrationInfo != null) SSEForegroundService.start(context)
                }
            }
        }
    }

    fun stop(context: Context) {
        simMetadataState.stop()
        recoveryJob?.cancel()
        recoveryJob = null
        subscriptionsMonitor.stop()
        SyncSimMetadataWorker.stop(context)
        eventsReceiver.stop()

        SSEForegroundService.stop(context)
        SettingsUpdateWorker.stop(context)
        WebhooksUpdateWorker.stop(context)
        PullMessagesWorker.stop(context)
        SendStateWorker.stop(context)
        SendInboxMessageWorker.stop(context)
        SendMmsInboxWorker.stop(context)

        this._api = null
        apiConfiguration = null
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
            // Authentication failure must never replace an existing station identity.
            updateDevice(context, pushToken)
            return
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
            if (simMetadataState.current() != null) {
                SyncSimMetadataWorker.requestImmediate(context)
            }

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
        simMetadataMutex.withLock {
            val snapshot = SubscriptionsHelper.getActiveSimCardsSnapshot(context)
            val simCards = buildSimMetadataSyncRequest(settings.id, snapshot)?.simCards
            api.devicePatch(
                accessToken,
                GatewayApi.DevicePatchRequest(
                    settings.id,
                    pushToken,
                    simCards,
                    syncPhoneNumbers = true,
                )
            )
        }

        this.settings.fcmToken = pushToken

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                settings.login,
                settings.password,
            )
        )
    }

    /** Metadata refresh never registers an identity, starts a service or broadcasts registration. */
    internal suspend fun syncSimMetadata(context: Context) {
        val tag = "SimMetadataSync"
        val generation = simMetadataState.current() ?: run {
            Log.d(tag, "Skipped: gateway is stopped")
            return
        }
        simMetadataMutex.withLock {
            if (!simMetadataState.isCurrent(generation) || !settings.enabled) {
                Log.d(tag, "Skipped: gateway session changed or disabled")
                return
            }
            val registration = settings.registrationInfo ?: run {
                Log.d(tag, "Skipped: no existing device registration")
                return
            }
            val serverUrl = settings.serverUrl
            // Read inside the shared mutex so a queued refresh cannot submit an older snapshot.
            val snapshot = SubscriptionsHelper.getActiveSimCardsSnapshot(context)
            if (snapshot == null) {
                Log.w(tag, "Skipped: phone-state permission or subscription data unavailable; retained server SIMs")
                return
            }
            if (snapshot.isNotEmpty() && snapshot.none { !it.phoneNumber.isNullOrBlank() }) {
                Log.w(tag, "Skipped: phone numbers unavailable for all active slots; retained server numbers")
                return
            }
            val unknownSlots = snapshot.filter { it.phoneNumber.isNullOrBlank() }.map { it.slotIndex }.sorted()
            if (unknownSlots.isNotEmpty()) {
                Log.w(tag, "Phone numbers unavailable for slots=$unknownSlots; retained their server numbers")
            }
            val request = buildSimMetadataSyncRequest(
                registration.id, snapshot,
            ) ?: run {
                Log.w(tag, "Skipped: empty or invalid SIM slots; retained server SIMs")
                return
            }
            if (!simMetadataState.isCurrent(generation) || !settings.enabled ||
                settings.registrationInfo != registration || settings.serverUrl != serverUrl) {
                Log.d(tag, "Skipped: gateway session or identity changed before PATCH")
                return
            }
            try {
                api.deviceSyncSimMetadata(registration.token, request)
            } catch (error: CancellationException) {
                Log.d(tag, "Canceled: metadata worker stopped")
                throw error
            } catch (error: Exception) {
                val status = (error as? ClientRequestException)?.response?.status?.value
                Log.w(tag, "PATCH failed: type=${error.javaClass.simpleName}, httpStatus=$status")
                throw error
            }
            if (simMetadataState.isCurrent(generation)) {
                val slots = request.simCards.map { it.slotIndex }.sorted()
                val numberedSlots = request.simCards.filter { !it.phoneNumber.isNullOrBlank() }
                    .map { it.slotIndex }.sorted()
                Log.i(tag, "PATCH succeeded: device=${registration.id}, slots=$slots, numberedSlots=$numberedSlots")
            } else {
                Log.d(tag, "PATCH completed after gateway session stopped; no service was started")
            }
        }
    }

    sealed class RegistrationMode {
        object Anonymous : RegistrationMode()
        class WithCredentials(val login: String, val password: String) : RegistrationMode()
        class WithCode(val code: String) : RegistrationMode()
    }
    //endregion

    //region Messages
    private fun sessionCurrent(context: Context, generation: Long): Boolean =
        simMetadataState.isCurrent(generation) && settings.enabled && GatewayRunIntent.isRequested(context)

    internal suspend fun getNewMessages(context: Context) = deliveryMutex.withLock {
        val generation = simMetadataState.current() ?: return@withLock
        val registration = settings.registrationInfo ?: return@withLock
        val serverUrl = settings.serverUrl
        val transport = api
        fun current() = sessionCurrent(context, generation) &&
            settings.registrationInfo == registration && settings.serverUrl == serverUrl
        if (!current()) return@withLock
        var ackBudget = 100
        var enqueueBudget = 100
        val attemptedClaims = mutableSetOf<Pair<String, String>>()

        suspend fun recover(preferred: Set<String> = emptySet(), limit: Int = ackBudget) {
            val awaiting = db.deliveryLedgerDao().listAwaitingAck(registration.id, serverUrl)
                .filter { (it.messageId to it.claimToken) !in attemptedClaims }
                .sortedBy { if (it.messageId in preferred) 0 else 1 }.take(minOf(limit, ackBudget))
            for (entry in awaiting) {
                if (!current()) return
                ackBudget--
                attemptedClaims.add(entry.messageId to entry.claimToken)
                val payload = gson.fromJson(entry.payloadJson, GatewayApi.Message::class.java)
                if (entry.simFingerprint == null ||
                    GatewaySimIdentity.capture(context, payload.simNumber) != entry.simFingerprint) {
                    db.deliveryLedgerDao().markUnknown(entry.messageId, "sim-changed-before-ack")
                    continue
                }
                if (!current()) return
                try {
                    val result = transport.acknowledgeMessage(registration.token,
                        GatewayApi.DeliveryAcknowledgement(entry.messageId, entry.claimToken, entry.payloadHash))
                    check(result.ok && result.id == entry.messageId) { "Invalid delivery acknowledgement" }
                    // If stopped after ACK, retain ACKED and resume only on the next explicit start.
                    db.deliveryLedgerDao().markAcked(entry.messageId, entry.claimToken, entry.payloadHash)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ClientRequestException) {
                    val status = error.response.status.value
                    val code = deliveryErrorCode(error.response.body<String>())
                    if (status == 409 && code in setOf("DELIVERY_EXPIRED", "DELIVERY_STALE")) {
                        Log.i("GatewayDelivery", "Awaiting fresh claim: id=${entry.messageId}, code=$code")
                    } else if (status == 400 || status == 404 || status == 409 || status == 403) {
                        db.deliveryLedgerDao().markUnknown(entry.messageId, "ack-rejected-$status-$code")
                        Log.w("GatewayDelivery", "Isolated delivery: id=${entry.messageId}, http=$status, code=$code")
                    } else throw error
                }
            }
            for (entry in db.deliveryLedgerDao().listReady(registration.id, serverUrl).take(enqueueBudget)) {
                if (!current()) return
                val message = gson.fromJson(entry.payloadJson, GatewayApi.Message::class.java)
                if (entry.simFingerprint == null ||
                    GatewaySimIdentity.capture(context, message.simNumber) != entry.simFingerprint) {
                    db.deliveryLedgerDao().markUnknown(entry.messageId, "sim-changed-after-ack")
                    continue
                }
                if (!current()) return
                enqueueBudget--
                messagesService.enqueueAcknowledgedGatewayMessage(message.toSendRequest())
                Log.i("GatewayDelivery", "Durably acknowledged and enqueued: id=${entry.messageId}")
            }
        }

        // Reserve half the budget for newly issued claims, so stale stages cannot starve a fresh GET.
        recover(limit = 50)
        val order = when (messagesService.processingOrder) {
            MessagesSettings.ProcessingOrder.LIFO -> GatewayApi.ProcessingOrder.LIFO
            MessagesSettings.ProcessingOrder.FIFO -> GatewayApi.ProcessingOrder.FIFO
        }
        // Drain every page while the SSE notification is coalesced; a later notification never cancels this GET.
        for (page in 0 until 100) {
            if (!current()) return@withLock
            val messages = transport.getMessages(registration.token, order)
            if (!current()) return@withLock
            if (messages.isEmpty()) break
            val staged = mutableListOf<GatewayDelivery>()
            val checks = mutableListOf<GatewayApi.Message>()
            for (message in messages) {
                val delivery = message.delivery
                if (delivery == null && message.legacyTransport && message.id.startsWith("check_")) {
                    checks.add(message)
                    continue
                }
                if (delivery == null || !validDeliveryMetadata(delivery.token, delivery.payloadHash)) {
                    Log.e("GatewayDelivery", "Invalid protocol item retained on server: id=${message.id}")
                    continue
                }
                // All payloads are persisted in one transaction before the first server ACK.
                staged.add(GatewayDelivery(message.id, registration.id, serverUrl,
                    gson.toJson(message), delivery.payloadHash, delivery.token,
                    simFingerprint = GatewaySimIdentity.capture(context, message.simNumber)))
            }
            val conflicts = db.deliveryLedgerDao().stageBatch(staged)
            for (id in conflicts) Log.e("GatewayDelivery", "Identity/payload conflict isolated: id=$id")
            recover(staged.map { it.messageId }.toSet())
            for (message in checks) {
                if (!current()) return@withLock
                messagesService.enqueueLegacyCheckMessage(message.toSendRequest(), registration.id, serverUrl)
            }
            if (ackBudget <= 0 || enqueueBudget <= 0) {
                PullMessagesWorker.start(context)
                break
            }
            if (page == 99) PullMessagesWorker.start(context)
        }
        if (current()) {
            SendStateWorker.requestDrain(context)
            SendInboxMessageWorker.requestDrain(context, recovery = true)
            SendMmsInboxWorker.start(context, recovery = true)
        }
    }

    private fun GatewayApi.Message.toSendRequest() = SendRequest(
        EntitySource.Cloud,
        me.capcom.smsgateway.modules.messages.data.Message(
            id,
            when (val content = content) {
                is GatewayApi.MessageContent.Text -> MessageContent.Text(content.text)
                is GatewayApi.MessageContent.Data -> MessageContent.Data(content.data, content.port)
            },
            phoneNumbers, isEncrypted ?: false, createdAt ?: Date(),
        ),
        SendParams(withDeliveryReport ?: true, skipPhoneValidation = true, simNumber = simNumber,
            validUntil = validUntil, scheduleAt = scheduleAt, priority = priority)
    )

    internal suspend fun flushPendingStates(context: Context) = statusMutex.withLock {
        val generation = simMetadataState.current() ?: return@withLock
        val registration = settings.registrationInfo ?: return@withLock
        val serverUrl = settings.serverUrl
        val transport = api
        // Yield often so live SENT/DELIVERED receipts can overtake legacy reconciliation.
        for (entry in db.messagesDao().nextPendingEntries(20)) {
            if (!sessionCurrent(context, generation) || settings.registrationInfo != registration ||
                settings.serverUrl != serverUrl) return@withLock
            val ledger = db.deliveryLedgerDao().get(entry.messageId)
            if (ledger == null && !LegacyStatusIdentity(context).matches(entry.messageId, registration.id, serverUrl)) {
                db.messagesDao().markBlocked(entry.messageId, entry.revision, "legacy-original-gateway-unavailable")
                continue
            }
            if (ledger != null &&
                (ledger.deviceId != registration.id || ledger.serverUrl != serverUrl)) {
                db.messagesDao().markBlocked(entry.messageId, entry.revision, "original-gateway-identity-changed")
                continue
            }
            val message = messagesService.getMessage(entry.messageId)
            if (message == null) {
                db.messagesDao().markBlocked(entry.messageId, entry.revision, "local-message-missing")
                continue
            }
            val patch = buildMessagePatchRequest(message)
            if (!patch.shouldReportToGateway()) {
                db.messagesDao().complete(entry.messageId, entry.revision)
                continue
            }
            try {
                // Pre-upgrade status rows have no routing ledger. Prove ownership read-only first.
                if (ledger == null) transport.getMessageState(registration.token, entry.messageId)
                if (!sessionCurrent(context, generation) || settings.registrationInfo != registration ||
                    settings.serverUrl != serverUrl) return@withLock
                transport.patchMessages(registration.token, listOf(patch))
                db.messagesDao().complete(entry.messageId, entry.revision)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ClientRequestException) {
                val status = error.response.status.value
                if (status == 408 || status == 429 || status >= 500) throw error
                if (status == 400 || status == 409) {
                    val remote = try {
                        transport.getMessageState(registration.token, entry.messageId)
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (lookup: ClientRequestException) {
                        if (lookup.response.status.value >= 500 || lookup.response.status.value in setOf(408, 429)) throw lookup
                        null
                    }
                    if (remote != null && remoteConfirmsPatch(remote, patch)) {
                        db.messagesDao().complete(entry.messageId, entry.revision)
                        continue
                    }
                }
                db.messagesDao().markBlocked(entry.messageId, entry.revision, "status-rejected-$status")
                Log.w("GatewayDelivery", "Status retained for reconciliation: id=${entry.messageId}, http=$status")
            }
        }
        if (db.messagesDao().nextPendingEntries(1).isNotEmpty() && sessionCurrent(context, generation)) {
            SendStateWorker.requestDrain(context)
        }
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
    ): Boolean {
        if (!settings.enabled) return false
        val registration = settings.registrationInfo ?: return false
        return SendInboxMessageWorker.enqueue(context, settings.serverUrl, registration.id, request)
    }

    internal suspend fun sendSmsInbox(task: SmsInboxUploadTask) {
        val registration = settings.registrationInfo
        if (registration == null || registration.id != task.deviceId || settings.serverUrl != task.serverUrl) {
            throw SmsInboxRouteUnavailableException()
        }
        if (!settings.enabled || simMetadataState.current() == null || !GatewayRunIntent.isRequested(App.instance)) {
            throw IOException("SMS inbox waits for the original gateway to resume")
        }
        api.postInboxMessage(registration.token, task.payload)
    }

    internal suspend fun sendMmsInbox(task: me.capcom.smsgateway.modules.gateway.workers.MmsUploadTask) {
        val registration = settings.registrationInfo
        if (!settings.enabled || registration == null || registration.id != task.deviceId ||
            settings.serverUrl != task.serverUrl) {
            throw java.io.IOException("MMS upload waits for its original registered gateway")
        }
        if (simMetadataState.current() == null || !GatewayRunIntent.isRequested(App.instance)) {
            throw IOException("MMS inbox waits for the original gateway to resume")
        }
        api.postMmsInbox(registration.token, task.payload)
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
