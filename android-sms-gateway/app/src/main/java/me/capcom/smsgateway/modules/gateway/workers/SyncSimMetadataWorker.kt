package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import me.capcom.smsgateway.modules.gateway.GatewayService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

class SyncSimMetadataWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result = try {
        get<GatewayService>().syncSimMetadata(applicationContext)
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ClientRequestException) {
        // An expired identity must never fall back to registering a different device.
        if (error.response.status.value in 400..499 &&
            error.response.status.value !in listOf(408, 429)) Result.failure() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val PERIODIC_NAME = "SyncSimMetadataPeriodic"
        private const val IMMEDIATE_NAME = "SyncSimMetadataImmediate"
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun start(context: Context) {
            val work = PeriodicWorkRequestBuilder<SyncSimMetadataWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
            requestImmediate(context)
        }

        fun requestImmediate(context: Context) {
            val work = OneTimeWorkRequestBuilder<SyncSimMetadataWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_NAME)
        }
    }
}
