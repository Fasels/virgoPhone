package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.*
import com.google.gson.GsonBuilder
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.extensions.configure
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.MmsOutbox
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.webhooks.payload.MmsDownloadedPayload
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.TimeUnit

data class MmsUploadTask(val serverUrl: String, val deviceId: String, val payload: MmsDownloadedPayload)

class SendMmsInboxWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val gateway: GatewayService by inject()
    private val logs: LogsService by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val outbox = store(applicationContext)
        for (key in outbox.pending().take(20)) {
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
                    outbox.fail(key, "HTTP $status")
                }
                logs.insert(LogEntry.Priority.ERROR, "receiver", "MMS upload failed; payload retained",
                    mapOf("task" to key, "httpStatus" to status, "error" to error.javaClass.simpleName))
            }
        }
        if (outbox.pending().isEmpty()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "SendMmsInbox"
        val gson = GsonBuilder().configure().create()
        fun store(context: Context) = MmsOutbox(File(context.noBackupFilesDir, "mms-outbox"))

        fun start(context: Context) {
            val work = OneTimeWorkRequestBuilder<SendMmsInboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, work)
        }
    }
}
