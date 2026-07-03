package com.example.virgo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

private const val RealtimeChannelId = "agent_realtime"
private const val MessageChannelId = "agent_messages_v2"
const val AgentRealtimeNotificationId = 1001

class AgentNotificationHelper(private val context: Context) {
    private val notificationIds = AtomicInteger(2000)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RealtimeChannelId,
                "Realtime service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps customer messages connected in the background."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MessageChannelId,
                "Customer messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts for new customer messages."
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build(),
                )
                enableVibration(true)
            },
        )
    }

    fun buildServiceNotification(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, RealtimeChannelId)
            .setSmallIcon(R.drawable.ic_tab_message)
            .setContentTitle("Customer service is online")
            .setContentText("Listening for new customer messages.")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showInboundMessage(event: AgentInboundMessageEvent): Boolean {
        if (!canPostNotifications()) return false
        val content = AgentMessageNotificationPolicy.contentFor(event)
        val notification = NotificationCompat.Builder(context, MessageChannelId)
            .setSmallIcon(R.drawable.ic_tab_message)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationIds.incrementAndGet(), notification)
        }.isSuccess
    }

    private fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
