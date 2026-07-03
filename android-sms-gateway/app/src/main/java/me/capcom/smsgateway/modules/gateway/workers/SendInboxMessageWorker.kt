package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.extensions.configure
import me.capcom.smsgateway.modules.gateway.GatewayApi
import me.capcom.smsgateway.modules.gateway.GatewayService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class SendInboxMessageWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val gatewayService: GatewayService by inject()

    override suspend fun doWork(): Result {
        try {
            val payload = inputData.getString(PAYLOAD) ?: return Result.failure()
            val request = gson.fromJson(payload, GatewayApi.InboxMessageRequest::class.java)
                ?: return Result.failure()

            withContext(Dispatchers.IO) {
                gatewayService.sendInboxMessage(request)
            }
            return Result.success()
        } catch (th: Throwable) {
            th.printStackTrace()
            return when {
                runAttemptCount < RETRY_COUNT -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    companion object {
        private const val RETRY_COUNT = 10
        private const val PAYLOAD = "payload"

        private val gson = GsonBuilder().configure().create()

        fun start(context: Context, request: GatewayApi.InboxMessageRequest) {
            val work = OneTimeWorkRequestBuilder<SendInboxMessageWorker>()
                .setInputData(workDataOf(PAYLOAD to gson.toJson(request)))
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueue(work)
        }
    }
}
