package me.capcom.smsgateway.modules.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R

class NotificationsService(
    context: Context
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as
                NotificationManager

    private val icons = mapOf(
        NOTIFICATION_ID_LOCAL_SERVICE to R.drawable.notif_server,
        NOTIFICATION_ID_SEND_WORKER to R.drawable.notif_send,
        NOTIFICATION_ID_WEBHOOK_WORKER to R.drawable.notif_webhook,
        NOTIFICATION_ID_PING_SERVICE to R.drawable.notif_ping,
        NOTIFICATION_ID_SETTINGS_CHANGED to R.drawable.notif_settings,
        NOTIFICATION_ID_SMS_RECEIVED_WEBHOOK to R.drawable.notif_webhook_registered,
        NOTIFICATION_ID_REALTIME_EVENTS to R.drawable.notif_realtime_events,
    )

    private val builders = mapOf<Int, (NotificationCompat.Builder) -> NotificationCompat.Builder>(
        NOTIFICATION_ID_SETTINGS_CHANGED to {
            it.setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        },
        NOTIFICATION_ID_SMS_RECEIVED_WEBHOOK to {
            it.setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        },
    )

    private val contentIntentFactories = mapOf(
        NOTIFICATION_ID_SMS_RECEIVED_WEBHOOK to { context: Context ->
            MainActivity.starter(context, MainActivity.TAB_INDEX_SETTINGS)
        }
    )

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.sms_gateway)
            val descriptionText = context.getString(R.string.local_sms_gateway_notifications)
            val importance = NotificationManager.IMPORTANCE_LOW
            val mChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            mChannel.description = descriptionText
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager.createNotificationChannel(mChannel)
        }
    }

    fun notify(context: Context, id: Int, contentText: String) {
        notificationManager.notify(id, makeNotification(context, id, contentText))
    }

    fun notifyGatewayService(
        context: Context,
        connectionState: String,
        deviceState: String,
        lastSync: String,
    ) {
        notificationManager.notify(
            NOTIFICATION_ID_REALTIME_EVENTS,
            makeGatewayServiceNotification(context, connectionState, deviceState, lastSync)
        )
    }

    fun makeGatewayServiceNotification(
        context: Context,
        connectionState: String,
        deviceState: String,
        lastSync: String,
    ): Notification {
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getText(R.string.gateway_service_notification_title))
            .setContentText(connectionState)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(
                        context.getString(R.string.gateway_service_status, connectionState),
                        deviceState,
                        lastSync,
                    ).joinToString("\n")
                )
            )
            .setSmallIcon(R.drawable.notif_realtime_events)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    MainActivity.starter(context, MainActivity.TAB_INDEX_HOME),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    fun makeNotification(
        context: Context,
        id: Int,
        contentText: String,
        contentIntent: PendingIntent? = null
    ): Notification {
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getText(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(icons[id] ?: R.drawable.ic_sms)
            .setContentIntent(
                contentIntent
                    ?: PendingIntent.getActivity(
                        context,
                        0,
                        contentIntentFactories[id]?.invoke(context)
                            ?: context.packageManager.getLaunchIntentForPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE
                    )
            )
            .apply { builders[id]?.invoke(this) }
            .build()
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "sms-gateway"

        const val NOTIFICATION_ID_LOCAL_SERVICE = 1
        const val NOTIFICATION_ID_SEND_WORKER = 2
        const val NOTIFICATION_ID_WEBHOOK_WORKER = 3
        const val NOTIFICATION_ID_PING_SERVICE = 4
        const val NOTIFICATION_ID_SETTINGS_CHANGED = 5
        const val NOTIFICATION_ID_SMS_RECEIVED_WEBHOOK = 6
        const val NOTIFICATION_ID_REALTIME_EVENTS = 7
    }
}
