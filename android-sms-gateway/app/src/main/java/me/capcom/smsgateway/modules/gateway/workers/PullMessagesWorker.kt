package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.lifecycle.map
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.capcom.smsgateway.helpers.GatewayRunIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.App
import java.util.concurrent.TimeUnit

class PullMessagesWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!GatewayRunIntent.isRequested(applicationContext)) return Result.success()
        try {
            withContext(Dispatchers.IO) {
                App.instance.gatewayService.getNewMessages(
                    applicationContext
                )
            }
            return Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (th: Exception) {
            // v2 leaves unacknowledged messages Pending and local receipts are durable.
            // Finish this wake-up so a newer SSE event is not held behind hours of backoff.
            android.util.Log.w("GatewayDelivery", "Pull retained for next recovery: ${th.javaClass.simpleName}")
            return Result.success()
        }
    }

    companion object {
        const val NAME = "PullMessagesWorker"
        const val IMMEDIATE_NAME = "PullMessagesWorkerImmediateV2"
        private const val LEGACY_IMMEDIATE_NAME = "PullMessagesWorkerImmediate"

        fun start(context: Context) {
            if (!GatewayRunIntent.isRequested(context)) return
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_IMMEDIATE_NAME)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateWork = OneTimeWorkRequestBuilder<PullMessagesWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    IMMEDIATE_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    immediateWork
                )

            val work = PeriodicWorkRequestBuilder<PullMessagesWorker>(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    work
                )
        }

        /** Recovery must not append work every minute while offline. */
        fun requestRecovery(context: Context) {
            if (!GatewayRunIntent.isRequested(context)) return
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_IMMEDIATE_NAME)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val immediate = OneTimeWorkRequestBuilder<PullMessagesWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            val periodic = PeriodicWorkRequestBuilder<PullMessagesWorker>(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.KEEP, immediate)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }

        fun getStateLiveData(context: Context) = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(NAME)
            WorkManager.getInstance(context)
                .cancelUniqueWork(IMMEDIATE_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_IMMEDIATE_NAME)
        }
    }
}
