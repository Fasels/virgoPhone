package me.capcom.smsgateway.modules.messages.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.notifications.NotificationsService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.CancellationException

class SendMessagesWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {

    private val logsService: LogsService by inject()
    private val messagesSvc: MessagesService by inject()
    private val notificationsSvc: NotificationsService by inject()

    override suspend fun doWork(): Result {
        logsService.insert(
            LogEntry.Priority.DEBUG,
            NAME,
            "SendMessagesWorker started"
        )

        return try {
            messagesSvc.sendPendingMessages()

            logsService.insert(
                LogEntry.Priority.DEBUG,
                NAME,
                "SendMessagesWorker finished successfully"
            )

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()

            logsService.insert(
                LogEntry.Priority.ERROR,
                NAME,
                "SendMessagesWorker finished with error: ${e.message ?: e.toString()}"
            )

            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(): ForegroundInfo {
        val notificationId = NotificationsService.NOTIFICATION_ID_SEND_WORKER
        val notification = notificationsSvc.makeNotification(
            applicationContext,
            notificationId,
            applicationContext.getString(R.string.send_messages_notification)
        )

        return ForegroundInfo(notificationId, notification)
    }

    companion object {
        private const val NAME = "SendMessagesWorker"
        private const val IMMEDIATE_NAME = "SendMessagesImmediateV2"
        private const val SCHEDULED_NAME = "SendMessagesScheduledV2"
        const val IMMEDIATE = 0L

        @Suppress("UNUSED_PARAMETER")
        fun start(context: Context, force: Boolean, scheduleAt: Long) {
            val initialDelay = max(0, scheduleAt - System.currentTimeMillis())

            val work = OneTimeWorkRequestBuilder<SendMessagesWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .apply {
                    if (initialDelay == 0L) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    if (initialDelay == 0L) IMMEDIATE_NAME else SCHEDULED_NAME,
                    // Queue an additional drain rather than cancelling an in-flight submit
                    // or losing the wake-up between its final SELECT and completion.
                    if (initialDelay == 0L) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                    work
                )
        }

        fun cancelLegacy(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(NAME)
                cancelUniqueWork(IMMEDIATE_NAME)
                cancelUniqueWork(SCHEDULED_NAME)
            }
        }
    }
}
