package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.lifecycle.map
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.App
import java.util.concurrent.TimeUnit

class PullMessagesWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            withContext(Dispatchers.IO) {
                App.instance.gatewayService.getNewMessages(
                    applicationContext
                )
            }
            return Result.success()
        } catch (th: Throwable) {
            th.printStackTrace()
            return Result.retry()
        }
    }

    companion object {
        const val NAME = "PullMessagesWorker"
        const val IMMEDIATE_NAME = "PullMessagesWorkerImmediate"

        fun start(context: Context) {
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
                    ExistingWorkPolicy.REPLACE,
                    immediateWork
                )
        }

        fun getStateLiveData(context: Context) = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(NAME)
            WorkManager.getInstance(context)
                .cancelUniqueWork(IMMEDIATE_NAME)
        }
    }
}
