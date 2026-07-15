package me.capcom.smsgateway.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.notifications.NotificationsService
import me.capcom.smsgateway.modules.orchestrator.ResidentRuntime
import org.koin.android.ext.android.inject

class ResidentForegroundService : Service() {
    private val runtime: ResidentRuntime by inject()
    private val notificationsService: NotificationsService by inject()
    private val logsService: LogsService by inject()

    private val lifecycle by lazy {
        ResidentServiceLifecycle(
            startRuntime = { runtime.start(applicationContext) },
            stopRuntime = { runtime.stop(applicationContext) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationsService.makeNotification(
            this,
            NotificationsService.NOTIFICATION_ID_RESIDENT_SERVICE,
            getString(R.string.resident_service_is_active),
        )
        startForeground(NotificationsService.NOTIFICATION_ID_RESIDENT_SERVICE, notification)

        try {
            lifecycle.start()
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start resident runtime", error)
            try {
                logsService.insert(
                    LogEntry.Priority.ERROR,
                    TAG,
                    "Failed to start resident runtime",
                    mapOf("exception" to error.stackTraceToString()),
                )
            } catch (_: Throwable) {
                // Android logging above remains available if database logging is unavailable.
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            lifecycle.stop()
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to stop resident runtime", error)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ResidentForegroundService"

        fun start(context: Context) {
            val intent = Intent(context, ResidentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ResidentForegroundService::class.java))
        }
    }
}
