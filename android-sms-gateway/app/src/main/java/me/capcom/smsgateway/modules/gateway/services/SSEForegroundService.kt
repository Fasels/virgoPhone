package me.capcom.smsgateway.modules.gateway.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import me.capcom.smsgateway.R
import me.capcom.smsgateway.helpers.GatewayRunIntent
import me.capcom.smsgateway.helpers.SSEManager
import me.capcom.smsgateway.modules.events.ExternalEvent
import me.capcom.smsgateway.modules.events.ExternalEventType
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.workers.PullMessagesWorker
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.notifications.NotificationsService
import me.capcom.smsgateway.modules.orchestrator.EventsRouter
import org.koin.android.ext.android.inject

class SSEForegroundService : Service() {
    private val settings: GatewaySettings by inject()
    private val eventsRouter by inject<EventsRouter>()
    private val notificationsSvc: NotificationsService by inject()
    private val logsService: LogsService by inject()
    private val mainHandler = Handler(Looper.getMainLooper())

    // These fields are accessed only on the main thread, including posted network/SSE callbacks.
    private var sseManager: SSEManager? = null
    private var destroyed = false
    private var currentNetwork: Network? = null
    private var networkAvailable = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, javaClass.name)
    }
    private val wifiLock: WifiManager.WifiLock by lazy {
        (getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, javaClass.name)
    }

    private fun mayRun(): Boolean = !destroyed && settings.enabled && GatewayRunIntent.isRequested(this)

    private fun createManager(url: String, token: String): SSEManager {
        val manager = SSEManager(url, token)
        manager.onConnected = {
            val connection = manager.currentConnectionGeneration()
            mainHandler.post {
                if (connection != null && mayRun() && sseManager === manager &&
                    manager.isCurrentConnection(connection)) {
                    Log.d(TAG, "Current SSE connected; requesting message reconciliation")
                    try {
                        PullMessagesWorker.start(this)
                    } catch (error: Exception) {
                        logsService.insert(LogEntry.Priority.ERROR, TAG,
                            "Failed to request message reconciliation after SSE connection")
                    }
                }
            }
        }
        manager.onEvent = { event, data ->
            val connection = manager.currentConnectionGeneration()
            mainHandler.post {
                if (connection != null && mayRun() && sseManager === manager &&
                    manager.isCurrentConnection(connection)) {
                    try {
                        processEvent(event, data)
                    } catch (error: Exception) {
                        // Do not write event bodies, tokens or exception messages to diagnostic logs.
                        logsService.insert(LogEntry.Priority.ERROR, TAG,
                            "Failed to process SSE event: ${error.javaClass.simpleName}")
                    }
                }
            }
        }
        return manager
    }

    override fun onCreate() {
        super.onCreate()
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()
        observeNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null sticky intent and a delayed explicit service command share the same persistent gate.
        if (!mayRun()) {
            sseManager?.disconnect()
            stopSelf(startId)
            Log.d(TAG, "SSE start ignored: gateway is stopped or disabled")
            return START_NOT_STICKY
        }
        val token = settings.registrationInfo?.token
        if (token.isNullOrBlank()) {
            sseManager?.disconnect()
            stopSelf(startId)
            Log.w(TAG, "SSE start ignored: existing device authentication unavailable")
            return START_NOT_STICKY
        }
        val notification = notificationsSvc.makeNotification(this,
            NotificationsService.NOTIFICATION_ID_REALTIME_EVENTS,
            getString(R.string.listening_to_the_server_events))
        startForeground(NotificationsService.NOTIFICATION_ID_REALTIME_EVENTS, notification)

        val url = "${settings.serverUrl}/events"
        val manager = sseManager ?: createManager(url, token).also { sseManager = it }
        if (!networkAvailable) manager.onNetworkUnavailable()
        manager.connect(url, token)
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun observeNetwork() {
        val connectivity = connectivityManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                currentNetwork = connectivity.activeNetwork
                networkAvailable = currentNetwork != null
            } else {
                networkAvailable = connectivity.activeNetworkInfo?.isConnected == true
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post {
                        if (destroyed) return@post
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            // Generic callbacks include non-default networks on API 21-23.
                            // A secondary mobile network must not replace a working Wi-Fi route.
                            refreshLegacyNetworkAvailability(connectivity)
                            return@post
                        }
                        val changed = currentNetwork != null && currentNetwork != network
                        currentNetwork = network
                        networkAvailable = true
                        if (mayRun()) sseManager?.onNetworkAvailable(forceReconnect = changed)
                        else sseManager?.disconnect()
                    }
                }

                override fun onLost(network: Network) {
                    mainHandler.post {
                        if (destroyed) return@post
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            refreshLegacyNetworkAvailability(connectivity)
                            return@post
                        }
                        // During a Wi-Fi/mobile handover, the old network can be lost after the new one is available.
                        if (currentNetwork != network) return@post
                        currentNetwork = null
                        networkAvailable = false
                        if (mayRun()) sseManager?.onNetworkUnavailable()
                        else sseManager?.disconnect()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivity.registerDefaultNetworkCallback(callback)
            } else {
                connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            networkCallback = callback
        } catch (error: RuntimeException) {
            // SSE socket timeout/backoff remains available if network observation is unsupported.
            networkAvailable = true
            Log.w(TAG, "Network observer unavailable: ${error.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshLegacyNetworkAvailability(connectivity: ConnectivityManager) {
        networkAvailable = connectivity.activeNetworkInfo?.isConnected == true
        if (!mayRun()) sseManager?.disconnect()
        else if (networkAvailable) sseManager?.onNetworkAvailable()
        else sseManager?.onNetworkUnavailable()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun processEvent(event: String?, data: String) {
        val type = event?.let { ExternalEventType.valueOf(it) } ?: ExternalEventType.MessageEnqueued
        eventsRouter.route(ExternalEvent(type = type, data = data))
    }

    override fun onDestroy() {
        destroyed = true
        sseManager?.close()
        sseManager = null
        networkCallback?.let { callback -> runCatching { connectivityManager?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SSEForegroundService"

        fun start(context: Context) {
            if (!GatewayRunIntent.isRequested(context)) return
            val intent = Intent(context, SSEForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SSEForegroundService::class.java))
        }
    }
}
