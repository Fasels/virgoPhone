package me.capcom.smsgateway.modules.gateway.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.capcom.smsgateway.R
import me.capcom.smsgateway.helpers.SSEManager
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.events.ExternalEvent
import me.capcom.smsgateway.modules.events.ExternalEventType
import me.capcom.smsgateway.modules.gateway.GatewayApi
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.MODULE_NAME
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.notifications.NotificationsService
import me.capcom.smsgateway.modules.orchestrator.EventsRouter
import me.capcom.smsgateway.modules.settings.SettingsService
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookDTO
import me.capcom.smsgateway.domain.EntitySource
import org.koin.android.ext.android.inject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class SSEForegroundService : Service() {
    private val settings: GatewaySettings by inject()
    private val gatewayService: GatewayService by inject()
    private val settingsService: SettingsService by inject()
    private val webHooksService: WebHooksService by inject()
    private val eventBus: EventBus by inject()

    private val eventsRouter by inject<EventsRouter>()

    private val notificationsSvc: NotificationsService by inject()
    private val logsService: LogsService by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    private val settingsMutex = Mutex()
    private val webhookMutex = Mutex()
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    @Volatile
    private var networkAvailable = false
    @Volatile
    private var stopRequested = false
    @Volatile
    private var lastSuccessfulSyncAt: Long? = null
    @Volatile
    private var lastCommandAt: Long? = null
    @Volatile
    private var lastError: String? = null
    private var sseManager: SSEManager? = null

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:VirgoGatewayService")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            lastError = null
            log(LogEntry.Priority.INFO, "NETWORK_AVAILABLE")
            updateNotification("network available")
            requestSync(applicationContext)
        }

        override fun onLost(network: Network) {
            networkAvailable = hasUsableNetwork()
            log(LogEntry.Priority.WARN, "NETWORK_LOST")
            updateNotification("network lost")
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (!running.compareAndSet(false, true)) {
            log(LogEntry.Priority.INFO, "SERVICE_ALREADY_RUNNING")
        }
        state.postValue(true)
        log(LogEntry.Priority.INFO, "SERVICE_CREATE")
        networkAvailable = hasUsableNetwork()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationsService.NOTIFICATION_ID_REALTIME_EVENTS,
            notificationsSvc.makeGatewayServiceNotification(
                this,
                "starting",
                "device ${deviceStateText()}",
                lastSuccessfulSyncText(),
            )
        )

        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            log(LogEntry.Priority.INFO, "SERVICE_STOP_REQUESTED")
            stopSelf()
            return START_NOT_STICKY
        }

        log(
            LogEntry.Priority.INFO,
            "SERVICE_START",
            mapOf(
                "action" to (intent?.action ?: "default"),
                "configured" to isConfigured(),
                "networkAvailable" to networkAvailable,
            )
        )

        if (intent?.action == ACTION_SYNC_NOW) {
            startMainLoops()
            serviceScope.launch { syncNow("explicit action") }
        } else {
            startMainLoops()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun startMainLoops() {
        if (!loopsStarted.compareAndSet(false, true)) {
            return
        }

        serviceScope.launch {
            connectSseLoop()
        }
        serviceScope.launch {
            pollMessagesLoop()
        }
        serviceScope.launch {
            periodicSettingsLoop()
        }
        serviceScope.launch {
            periodicWebhooksLoop()
        }
        serviceScope.launch {
            collectLocalGatewayEvents()
        }
    }

    private suspend fun connectSseLoop() {
        var retryDelayMs = RETRY_INITIAL_MS
        while (serviceScope.isActive && !stopRequested) {
            if (!isConfigured()) {
                updateNotification("not configured")
                delay(CONFIG_RECHECK_MS)
                continue
            }
            if (!networkAvailable) {
                updateNotification("offline")
                delay(NETWORK_WAIT_MS)
                continue
            }

            try {
                log(LogEntry.Priority.INFO, "SERVER_CONNECTING")
                ensureSseConnected()
                log(LogEntry.Priority.INFO, "SERVER_CONNECTED")
                updateNotification("connected")
                retryDelayMs = RETRY_INITIAL_MS

                while (serviceScope.isActive && !stopRequested && networkAvailable && isConfigured()) {
                    delay(SSE_HEALTH_CHECK_MS)
                }
            } catch (th: Throwable) {
                sseManager?.disconnect()
                sseManager = null
                lastError = th.message ?: th.javaClass.simpleName
                log(
                    LogEntry.Priority.WARN,
                    "SERVER_RETRY",
                    mapOf("reason" to safeError(th), "delayMs" to retryDelayMs)
                )
                updateNotification("reconnecting")
                delay(retryDelayMs)
                retryDelayMs = min(retryDelayMs * 2, RETRY_MAX_MS)
            }
        }
    }

    private suspend fun pollMessagesLoop() {
        var retryDelayMs = RETRY_INITIAL_MS
        while (serviceScope.isActive && !stopRequested) {
            if (!isConfigured()) {
                delay(CONFIG_RECHECK_MS)
                continue
            }
            if (!networkAvailable) {
                delay(NETWORK_WAIT_MS)
                continue
            }

            val ok = syncNow("poll")
            retryDelayMs = if (ok) {
                delay(POLL_INTERVAL_MS)
                RETRY_INITIAL_MS
            } else {
                delay(retryDelayMs)
                min(retryDelayMs * 2, RETRY_MAX_MS)
            }
        }
    }

    private suspend fun periodicSettingsLoop() {
        while (serviceScope.isActive && !stopRequested) {
            delay(SETTINGS_SYNC_INTERVAL_MS)
            if (isConfigured() && networkAvailable) {
                syncSettings()
            }
        }
    }

    private suspend fun periodicWebhooksLoop() {
        while (serviceScope.isActive && !stopRequested) {
            delay(WEBHOOK_SYNC_INTERVAL_MS)
            if (isConfigured() && networkAvailable) {
                syncWebhooks()
            }
        }
    }

    private suspend fun collectLocalGatewayEvents() {
        serviceScope.launch {
            eventBus.collect<me.capcom.smsgateway.modules.gateway.events.MessageEnqueuedEvent> {
                syncNow("COMMAND_RECEIVED")
            }
        }
        serviceScope.launch {
            eventBus.collect<me.capcom.smsgateway.modules.gateway.events.SettingsUpdatedEvent> {
                syncSettings()
            }
        }
        serviceScope.launch {
            eventBus.collect<me.capcom.smsgateway.modules.gateway.events.WebhooksUpdatedEvent> {
                syncWebhooks()
            }
        }
    }

    private fun ensureSseConnected() {
        if (sseManager != null) return

        val token = settings.registrationInfo?.token
            ?: throw IllegalStateException("Authentication token is required for SSE connection")

        sseManager = SSEManager("${settings.serverUrl}/events", token)
            .apply {
                onConnected = {
                    log(LogEntry.Priority.INFO, "SERVER_CONNECTED")
                    serviceScope.launch { syncNow("sse connected") }
                }
                onEvent = { event, data ->
                    Log.d("SSEForegroundService", "$event: $data")

                    try {
                        lastCommandAt = System.currentTimeMillis()
                        log(
                            LogEntry.Priority.INFO,
                            "COMMAND_RECEIVED",
                            mapOf("event" to (event ?: ExternalEventType.MessageEnqueued.name))
                        )
                        processEvent(event, data)
                        serviceScope.launch { syncNow("sse event") }
                    } catch (e: Throwable) {
                        e.printStackTrace()

                        log(
                            LogEntry.Priority.ERROR,
                            "SERVER_DISCONNECTED",
                            mapOf("event" to event, "reason" to safeError(e))
                        )
                    }
                }
            }
        sseManager?.connect()
    }

    private suspend fun syncNow(reason: String): Boolean {
        if (!isConfigured()) {
            log(LogEntry.Priority.WARN, "SERVER_DISCONNECTED", mapOf("reason" to "not configured"))
            return true
        }
        if (!networkAvailable) {
            return false
        }

        return pullMutex.withLock {
            runWithShortWakeLock {
                try {
                    log(LogEntry.Priority.INFO, "SERVER_CONNECTING", mapOf("reason" to reason))
                    gatewayService.getNewMessages(this@SSEForegroundService)
                    lastSuccessfulSyncAt = System.currentTimeMillis()
                    lastError = null
                    log(LogEntry.Priority.INFO, "SERVER_CONNECTED", mapOf("reason" to reason))
                    updateNotification("connected")
                    true
                } catch (th: Throwable) {
                    lastError = th.message ?: th.javaClass.simpleName
                    log(
                        LogEntry.Priority.ERROR,
                        "SERVER_RETRY",
                        mapOf("reason" to safeError(th), "source" to reason)
                    )
                    updateNotification("reconnecting")
                    false
                }
            }
        }
    }

    private suspend fun syncSettings() {
        if (!isConfigured() || !networkAvailable) return
        settingsMutex.withLock {
            runCatching {
                gatewayService.getSettings()?.let { settingsService.update(it) }
            }.onFailure {
                log(LogEntry.Priority.WARN, "SERVER_RETRY", mapOf("settings" to safeError(it)))
            }
        }
    }

    private suspend fun syncWebhooks() {
        if (!isConfigured() || !networkAvailable) return
        webhookMutex.withLock {
            runCatching {
                val webhooks = gatewayService.getWebHooks().map { it.toDTO() }
                webHooksService.sync(EntitySource.Cloud, webhooks)
            }.onFailure {
                log(LogEntry.Priority.WARN, "SERVER_RETRY", mapOf("webhooks" to safeError(it)))
            }
        }
    }

    private fun processEvent(event: String?, data: String) {
        val type = try {
            event?.let { ExternalEventType.valueOf(it) }
                ?: ExternalEventType.MessageEnqueued
        } catch (e: Throwable) {
            throw RuntimeException("Unknown event type: $event", e)
        }

        eventsRouter.route(
            ExternalEvent(
                type = type,
                data = data
            )
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        log(LogEntry.Priority.WARN, "SERVICE_TASK_REMOVED")
        if (!stopRequested && isConfigured()) {
            scheduleRestart(RESTART_AFTER_TASK_REMOVED_MS)
        }
    }

    override fun onDestroy() {
        log(LogEntry.Priority.WARN, "SERVICE_DESTROY", mapOf("stopRequested" to stopRequested))
        running.set(false)
        loopsStarted.set(false)
        state.postValue(false)
        sseManager?.disconnect()
        sseManager = null
        unregisterNetworkCallback()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        serviceScope.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        if (!stopRequested && isConfigured()) {
            scheduleRestart(RESTART_AFTER_DESTROY_MS)
        }

        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (th: Throwable) {
            log(LogEntry.Priority.WARN, "NETWORK_CALLBACK_FAILED", mapOf("reason" to safeError(th)))
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
    }

    private fun hasUsableNetwork(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun isConfigured(): Boolean {
        return settings.enabled && settings.registrationInfo?.token != null
    }

    private fun updateNotification(connectionState: String) {
        notificationsSvc.notifyGatewayService(
            this,
            connectionState,
            "device ${deviceStateText()}",
            lastSuccessfulSyncText(),
        )
    }

    private fun deviceStateText(): String = when {
        !settings.enabled -> "disabled"
        settings.registrationInfo?.token == null -> "not registered"
        else -> "registered"
    }

    private fun lastSuccessfulSyncText(): String {
        val last = lastSuccessfulSyncAt
            ?: return lastError?.let { "last error: ${it.take(80)}" }
            ?: "last sync: never"
        return "last sync: ${DateFormat.getDateTimeInstance().format(Date(last))}"
    }

    private suspend fun runWithShortWakeLock(block: suspend () -> Boolean): Boolean {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        }
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!settings.enabled) return
        val intent = Intent(this, SSEForegroundService::class.java)
        val pendingIntent = PendingIntent.getService(
            this,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        log(LogEntry.Priority.WARN, "SERVICE_RESTART_SCHEDULED", mapOf("delayMs" to delayMs))
    }

    private fun GatewayApi.WebHook.toDTO(): WebHookDTO {
        return WebHookDTO(
            id = id,
            deviceId = null,
            url = url,
            event = event,
            source = EntitySource.Cloud,
        )
    }

    private fun log(
        priority: LogEntry.Priority,
        message: String,
        context: Map<String, *> = emptyMap<String, Any?>()
    ) {
        logsService.insert(priority, MODULE_NAME, message, context)
    }

    private fun safeError(th: Throwable): String {
        return th.message?.take(200) ?: th.javaClass.simpleName
    }

    companion object {
        private const val ACTION_SYNC_NOW = "me.capcom.smsgateway.gateway.SYNC_NOW"
        private const val ACTION_STOP = "me.capcom.smsgateway.gateway.STOP"
        private const val RESTART_REQUEST_CODE = 7107
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 10 * 1000L
        private const val CONFIG_RECHECK_MS = 60 * 1000L
        private const val NETWORK_WAIT_MS = 30 * 1000L
        private const val RETRY_INITIAL_MS = 5 * 1000L
        private const val RETRY_MAX_MS = 60 * 1000L
        private const val SSE_HEALTH_CHECK_MS = 30 * 1000L
        private const val SETTINGS_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val WEBHOOK_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val RESTART_AFTER_TASK_REMOVED_MS = 5 * 1000L
        private const val RESTART_AFTER_DESTROY_MS = 10 * 1000L

        private val running = AtomicBoolean(false)
        private val loopsStarted = AtomicBoolean(false)
        val state = MutableLiveData(false)

        fun start(context: Context) {
            val intent = Intent(context, SSEForegroundService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun requestSync(context: Context) {
            val intent = Intent(context, SSEForegroundService::class.java)
                .setAction(ACTION_SYNC_NOW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SSEForegroundService::class.java)
                .setAction(ACTION_STOP)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
