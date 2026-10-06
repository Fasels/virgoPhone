package me.capcom.smsgateway.modules.receiver

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** WorkManager restores this scan after process death/reboot; network is not needed to spool. */
class MmsScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scanner = MmsContentObserver()
        scanner.scan()
        delay(2_500)
        scanner.scan()
        Result.success()
    }

    companion object {
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("MmsInboxScan",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MmsScanWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
