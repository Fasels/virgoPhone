package com.example.virgo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class AgentRealtimeService : Service() {
    @Volatile
    private var shouldContinue = false

    @Volatile
    private var activeToken: String? = null

    private var listenerThread: Thread? = null
    private lateinit var notificationHelper: AgentNotificationHelper
    private lateinit var logStore: ServerLinkLogStore

    override fun onCreate() {
        super.onCreate()
        notificationHelper = AgentNotificationHelper(applicationContext)
        logStore = persistentServerLinkLogStore(applicationContext)
        notificationHelper.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            stopServiceWork()
            return START_NOT_STICKY
        }

        val session = loadLoginSession(applicationContext)
        if (!session.isLoggedIn) {
            stopServiceWork()
            return START_NOT_STICKY
        }

        startAsForegroundService()
        startListening(session.token)
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForegroundService() {
        val notification = notificationHelper.buildServiceNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                AgentRealtimeNotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(AgentRealtimeNotificationId, notification)
        }
    }

    private fun startListening(token: String) {
        if (activeToken == token && listenerThread?.isAlive == true) return
        stopListening()
        activeToken = token
        shouldContinue = true
        listenerThread = Thread {
            val client = AgentApiClient(
                tokenProvider = { token },
                logStore = logStore,
            )
            runCatching {
                client.listenEventsWithReconnect(
                    onInboundMessage = ::handleInboundMessage,
                    shouldContinue = { shouldContinue },
                )
            }.onFailure { error ->
                if (error is AgentApiException && error.statusCode == 401) {
                    clearLoginSession(applicationContext)
                    stopSelf()
                }
            }
        }.apply {
            name = "AgentRealtimeService"
            isDaemon = true
            start()
        }
    }

    private fun handleInboundMessage(event: AgentInboundMessageEvent) {
        AgentInboundEventBus.publish(event)
        logStore.record(
            "SSE",
            AgentApiPaths.Events,
            null,
            "Inbound message event received: conversation=${event.conversationId}, message=${event.messageId}",
        )

        val decision = AgentMessageNotificationPolicy.decisionFor(AppVisibilityTracker.isInForeground, event)
        if (decision != AgentMessageNotificationDecision.Notify) {
            logStore.record("NOTIFY", AgentApiPaths.Events, null, decision.logMessage)
            return
        }

        val posted = notificationHelper.showInboundMessage(event)
        logStore.record(
            "NOTIFY",
            AgentApiPaths.Events,
            null,
            if (posted) {
                "Inbound message notification posted"
            } else {
                "Inbound message notification blocked by Android notification permission or settings"
            },
        )
        if (!posted) {
            runCatching {
                notificationHelper.ensureChannels()
            }
        }
    }

    private fun stopServiceWork() {
        stopListening()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopListening() {
        shouldContinue = false
        activeToken = null
        listenerThread?.interrupt()
        listenerThread = null
    }

    companion object {
        private const val ActionStart = "com.example.virgo.action.START_REALTIME"
        private const val ActionStop = "com.example.virgo.action.STOP_REALTIME"

        fun start(context: Context) {
            val intent = Intent(context, AgentRealtimeService::class.java).setAction(ActionStart)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentRealtimeService::class.java).setAction(ActionStop)
            context.startService(intent)
        }
    }
}
