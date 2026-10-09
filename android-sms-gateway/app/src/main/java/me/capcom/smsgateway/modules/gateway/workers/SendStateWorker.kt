package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.helpers.GatewayRunIntent
import me.capcom.smsgateway.modules.gateway.GatewayService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** WorkManager wakes the durable Room outbox; network failures never discard it. */
class SendStateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params), KoinComponent {
    private val gatewayService: GatewayService by inject()
    override suspend fun doWork(): Result {
        if (!GatewayRunIntent.isRequested(applicationContext)) return Result.success()
        return try {
            withContext(Dispatchers.IO) { gatewayService.flushPendingStates(applicationContext) }
            Result.success()
        } catch (cancel: CancellationException) { throw cancel
        } catch (error: Exception) {
            // The revisioned outbox retains the failed update. Reconnect/60s recovery
            // wakes it again without placing new receipts behind exponential backoff.
            android.util.Log.w("GatewayDelivery", "Status outbox retained: ${error.javaClass.simpleName}")
            Result.success()
        }
    }
    companion object {
        private const val NAME = "GatewayStateOutboxV2"
        private const val LEGACY_NAME = "GatewayStateOutbox"
        private const val PERIODIC_NAME = "GatewayStateOutboxPeriodic"
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun start(context: Context, messageId: String) { requestDrain(context) }
        fun requestDrain(context: Context, recovery: Boolean = false) {
            if (!GatewayRunIntent.isRequested(context)) return
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_NAME)
            val work = OneTimeWorkRequestBuilder<SendStateWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(constraints()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,
                if (recovery) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
        fun startPeriodic(context: Context) {
            if (!GatewayRunIntent.isRequested(context)) return
            val work = PeriodicWorkRequestBuilder<SendStateWorker>(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(constraints()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
