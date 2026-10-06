package me.capcom.smsgateway.modules.receiver

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.workers.SendMmsInboxWorker
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MmsContentObserver : KoinComponent {
    private val context: Context by inject()
    private val storage: StateStorage by inject()
    private val gateway: GatewaySettings by inject()
    private val receiverSvc: ReceiverService by inject()
    private val logs: LogsService by inject()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    private val readiness = MmsReadiness()
    private val lastErrorAt = mutableMapOf<Long, Long>()
    private val poll = object : Runnable {
        override fun run() {
            scan()
            handler?.postDelayed(this, 5_000)
        }
    }

    fun start() {
        if (observer != null) return
        val currentThread = HandlerThread("MmsContentObserver").apply { start() }
        thread = currentThread
        handler = Handler(currentThread.looper)
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler?.post { scan() } }
        }
        context.contentResolver.registerContentObserver(Uri.parse("content://mms"), true, observer!!)
        handler?.post(poll)
        MmsScanWorker.start(context)
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    /** Fixed time boundary, NOT a moving ID cursor: late downloads remain eligible. */
    fun scan() {
        val deviceId = gateway.deviceId ?: return
        if (!gateway.enabled) return
        try {
            if (storage.mmsScanSinceSeconds == 0L) {
                storage.mmsScanSinceSeconds = System.currentTimeMillis() / 1000 - 24 * 60 * 60
            }
            val outbox = SendMmsInboxWorker.store(context)
            context.contentResolver.query(Uri.parse("content://mms"), arrayOf("_id"),
                "m_type = 132 AND msg_box = 1 AND date >= ?",
                arrayOf(storage.mmsScanSinceSeconds.toString()), "_id ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val key = outbox.key(gateway.serverUrl, deviceId, id.toString())
                    if (outbox.contains(key)) { readiness.forget(id); lastErrorAt.remove(id); continue }
                    try {
                        val message = MmsContentReader.read(context, id) ?: continue
                        // Some providers notify before all parts have been committed.
                        val fingerprint = message.hashCode()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!readiness.isReady(id, fingerprint, now)) continue
                        receiverSvc.process(context, InboxMessage.MMS(
                            id.toString(), message.body, message.subject,
                            message.attachments.map { InboxMessage.MMS.Attachment(
                                it.partId, it.contentType, it.name, it.size, it.data) },
                            message.sender, message.date, message.subscriptionId,
                            totalPartCount = message.totalPartCount, textPartCount = message.textPartCount,
                            smilPartCount = message.smilPartCount,
                            sourceAction = "content_provider_mms_observer", rawType = "MMS_DOWNLOADED",
                        ), true, "content_provider_mms_observer")
                        readiness.forget(id)
                    } catch (error: Exception) {
                        readiness.forget(id)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (lastErrorAt[id] == null || now - lastErrorAt.getValue(id) >= 60_000) {
                            lastErrorAt[id] = now
                            logs.insert(LogEntry.Priority.ERROR, MODULE_NAME, "MMS not queued; will rescan",
                                mapOf("mmsId" to id, "reason" to (error.message ?: error.javaClass.simpleName)))
                        }
                    }
                }
            }
            if (outbox.pending().isNotEmpty()) SendMmsInboxWorker.start(context)
        } catch (error: Exception) {
            logs.insert(LogEntry.Priority.ERROR, MODULE_NAME, "MMS scan failed; will retry",
                mapOf("reason" to (error.message ?: error.javaClass.simpleName)))
        }
    }
}
