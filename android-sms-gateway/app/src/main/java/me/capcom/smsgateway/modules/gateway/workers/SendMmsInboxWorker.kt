package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.*
import com.google.gson.GsonBuilder
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.extensions.configure
import me.capcom.smsgateway.helpers.GatewayRunIntent
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.MmsOutbox
import me.capcom.smsgateway.modules.gateway.isCompatibleGifRetry
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.webhooks.payload.MmsDownloadedPayload
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

data class MmsUploadTask(val serverUrl: String, val deviceId: String, val payload: MmsDownloadedPayload)

class SendMmsInboxWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val gateway: GatewayService by inject()
    private val logs: LogsService by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!GatewayRunIntent.isRequested(applicationContext)) return@withContext Result.success()
        val outbox = store(applicationContext)
        // Visit the whole snapshot so an older unavailable upload cannot starve a newer image.
        for (key in outbox.pending()) {
            if (!GatewayRunIntent.isRequested(applicationContext)) return@withContext Result.success()
            try {
                val task = gson.fromJson(outbox.read(key), MmsUploadTask::class.java)
                gateway.sendMmsInbox(task)
                outbox.complete(key)
                logs.insert(LogEntry.Priority.INFO, "receiver", "MMS uploaded", mapOf("task" to key))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val status = (error as? ClientRequestException)?.response?.status?.value
                if (status != null && status in 400..499 && status != 408 && status != 429) {
                    // This version understands GIF. A remaining 415 is a genuine unsupported
                    // or invalid payload, not the old format mismatch eligible for recovery.
                    outbox.fail(key, if (status == 415) "HTTP 415; GIF compatibility checked" else "HTTP $status")
                }
                logs.insert(LogEntry.Priority.ERROR, "receiver", "MMS upload failed; payload retained",
                    mapOf("task" to key, "httpStatus" to status, "error" to error.javaClass.simpleName))
            }
        }
        // Payloads remain durable. The resident watchdog/periodic scan retries them;
        // a delayed WorkManager retry must not hold a new image behind this batch.
        Result.success()
    }

    companion object {
        private const val LEGACY_NAME = "SendMmsInbox"
        private const val NAME = "SendMmsInboxV2"
        val gson = GsonBuilder().configure().create()
        fun store(context: Context) = MmsOutbox(File(context.noBackupFilesDir, "mms-outbox"))

        fun retryCompatibleMedia(context: Context): Int = store(context).retryFailedMatching { payload, reason ->
            isCompatibleGifRetry(reason, payload)
        }

        fun start(context: Context, recovery: Boolean = false) {
            if (!GatewayRunIntent.isRequested(context)) return
            retryCompatibleMedia(context)
            if (store(context).pending().isEmpty()) return
            val work = OneTimeWorkRequestBuilder<SendMmsInboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // Append a serial wake-up when a new part arrives as the current worker finishes.
            // KEEP can discard that wake-up after the worker's final pending() check.
            val manager = WorkManager.getInstance(context)
            // Release an old exponential-backoff work item without discarding its file.
            // MMS uploads use the same server idempotency identity after cancellation.
            manager.cancelUniqueWork(LEGACY_NAME)
            manager.enqueueUniqueWork(NAME,
                if (recovery) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(LEGACY_NAME)
                cancelUniqueWork(NAME)
            }
        }
    }
}
