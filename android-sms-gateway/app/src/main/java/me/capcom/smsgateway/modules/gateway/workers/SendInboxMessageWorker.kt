package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.extensions.configure
import me.capcom.smsgateway.helpers.GatewayRunIntent
import me.capcom.smsgateway.modules.gateway.GatewayApi
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.SmsInboxOutbox
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SmsInboxUploadTask(val serverUrl: String, val deviceId: String, val payload: GatewayApi.InboxMessageRequest)
class SmsInboxRouteUnavailableException : IOException("SMS inbox waits for its original registered gateway")

/** WorkManager wakes a durable inbox; there is no retry count that discards received SMS. */
class SendInboxMessageWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val gatewayService: GatewayService by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val outbox = store(applicationContext)
        try {
            // Old Work input had no original route. Preserve it without guessing its station.
            inputData.getString(PAYLOAD)?.let {
                outbox.retainLegacy(it)
                Log.w(TAG, "Legacy SMS inbox payload retained: original gateway route unavailable")
            }
            if (!GatewayRunIntent.isRequested(applicationContext)) return@withContext Result.success()
            var transientFailure = false
            drainMutex.withLock {
                // One failed payload must not repeatedly hide later received SMS.
                for (key in outbox.pending()) {
                    if (!GatewayRunIntent.isRequested(applicationContext)) return@withLock
                    try {
                        val task = gson.fromJson(outbox.read(key), SmsInboxUploadTask::class.java)
                        require(!task.deviceId.isNullOrBlank() && !task.serverUrl.isNullOrBlank())
                        gatewayService.sendSmsInbox(task)
                        outbox.complete(key)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        val status = (error as? ClientRequestException)?.response?.status?.value
                        if (error is SmsInboxRouteUnavailableException) {
                            outbox.block(key, "original-gateway-unavailable")
                        } else if (error is JsonParseException || error is IllegalArgumentException) {
                            outbox.block(key, "invalid-retained-payload")
                        } else if (status != null && status in 400..499 && status != 408 && status != 429) {
                            outbox.block(key, "HTTP $status")
                        } else {
                            transientFailure = true
                        }
                        Log.w(TAG, "SMS inbox upload retained: task=$key, http=$status, type=${error.javaClass.simpleName}")
                    }
                }
            }
            if (!transientFailure && GatewayRunIntent.isRequested(applicationContext) && outbox.pending().isNotEmpty()) {
                requestDrain(applicationContext)
            }
            // Keep failures durable, but finish this wake so its exponential backoff cannot
            // hold new receive events for hours. Reconnect, the 60-second resident recovery
            // and the independent periodic wake each retry pending files.
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "SMS inbox recovery will retry: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SmsInboxOutbox"
        private const val NAME = "SmsInboxOutboxDrain"
        private const val PERIODIC_NAME = "SmsInboxOutboxPeriodic"
        private const val PAYLOAD = "payload"
        private val drainMutex = Mutex()
        val gson = GsonBuilder().configure().create()
        fun store(context: Context) = SmsInboxOutbox(File(context.noBackupFilesDir, "sms-inbox-outbox-v2"))
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Returns only after an immutable routed payload, or its completed receipt, is durable. */
        fun enqueue(context: Context, serverUrl: String, deviceId: String, request: GatewayApi.InboxMessageRequest): Boolean {
            require(serverUrl.isNotBlank() && deviceId.isNotBlank())
            val outbox = store(context)
            val key = outbox.key(serverUrl, deviceId, request.id)
            outbox.enqueue(key, gson.toJson(SmsInboxUploadTask(serverUrl, deviceId, request)))
            // A WorkManager DB error cannot invalidate an already durable receive.
            try { requestDrain(context) } catch (error: Exception) {
                Log.w(TAG, "SMS inbox is durable; scheduling will recover: ${error.javaClass.simpleName}")
            }
            return true
        }

        fun start(context: Context) {
            if (!GatewayRunIntent.isRequested(context)) return
            store(context).retryBlocked()
            val work = PeriodicWorkRequestBuilder<SendInboxMessageWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
            requestDrain(context, recovery = true)
        }

        fun requestDrain(context: Context, recovery: Boolean = false) {
            if (!GatewayRunIntent.isRequested(context)) return
            val work = OneTimeWorkRequestBuilder<SendInboxMessageWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,
                if (recovery) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
