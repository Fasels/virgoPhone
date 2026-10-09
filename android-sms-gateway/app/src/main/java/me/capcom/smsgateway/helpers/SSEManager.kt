package me.capcom.smsgateway.helpers

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** All connection transitions, including listener callbacks, share one lock. */
class SSEManager(
    url: String,
    authToken: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val eventSourceFactory: EventSource.Factory = EventSources.createFactory(
        OkHttpClient.Builder().readTimeout(75, TimeUnit.SECONDS).build()
    ),
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) {
    private class Attempt(val generation: Long) {
        var source: EventSource? = null
        var opened = false
        var canceled = false
    }

    private val lock = Any()
    private var url = url
    private var authToken = authToken
    private var desiredRunning = false
    private var networkAvailable = true
    private var disposed = false
    private var generation = 0L
    private var currentAttempt: Attempt? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    @Volatile var onEvent: ((type: String?, data: String) -> Unit)? = null
    @Volatile var onConnected: (() -> Unit)? = null
    @Volatile var onError: ((Throwable?) -> Unit)? = null
    @Volatile var onClosed: (() -> Unit)? = null

    fun connect() = synchronized(lock) { connectLocked(url, authToken) }

    /** Reuses the existing identity; a changed URL/token invalidates the previous connection. */
    fun connect(url: String, authToken: String) = synchronized(lock) {
        connectLocked(url, authToken)
    }

    private fun connectLocked(url: String, authToken: String) {
        if (disposed) return
        val changed = this.url != url || this.authToken != authToken
        if (changed) {
            this.url = url
            this.authToken = authToken
            invalidateConnectionLocked()
            reconnectAttempts = 0
        }
        desiredRunning = true
        // Repeated service commands neither create a second source nor reset a pending backoff.
        if (networkAvailable && currentAttempt == null && reconnectJob == null) {
            beginConnectionLocked()
        }
    }

    fun disconnect() = synchronized(lock) {
        desiredRunning = false
        invalidateConnectionLocked()
        reconnectAttempts = 0
        log("SSE stopped; connection and retry cleared")
    }

    fun close() = synchronized(lock) {
        disposed = true
        desiredRunning = false
        invalidateConnectionLocked()
        scope.cancel()
    }

    /** Network callbacks cannot change the user's requested running state. */
    fun onNetworkUnavailable() = synchronized(lock) {
        networkAvailable = false
        invalidateConnectionLocked()
        if (desiredRunning) log("SSE waiting for network")
    }

    fun onNetworkAvailable(forceReconnect: Boolean = false) = synchronized(lock) {
        val wasUnavailable = !networkAvailable
        networkAvailable = true
        if (!desiredRunning || disposed) return@synchronized
        if (forceReconnect || wasUnavailable) {
            invalidateConnectionLocked()
            reconnectAttempts = 0
        }
        if (currentAttempt == null && reconnectJob == null) beginConnectionLocked()
    }

    fun currentConnectionGeneration(): Long? = synchronized(lock) {
        currentAttempt?.takeIf { desiredRunning && it.opened }?.generation
    }

    fun isCurrentConnection(value: Long): Boolean = synchronized(lock) {
        desiredRunning && !disposed && currentAttempt?.let { it.opened && it.generation == value } == true
    }

    private fun beginConnectionLocked() {
        if (!desiredRunning || disposed || !networkAvailable || currentAttempt != null) return
        val attempt = Attempt(++generation)
        currentAttempt = attempt
        try {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $authToken").build()
            val source = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) = synchronized(lock) {
                    if (!acceptsLocked(attempt, eventSource) || attempt.opened) return@synchronized
                    attempt.source = eventSource
                    attempt.opened = true
                    reconnectAttempts = 0
                    log("SSE connected; generation=${attempt.generation}")
                    notifySafely { onConnected?.invoke() }
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) =
                    synchronized(lock) {
                        if (!acceptsLocked(attempt, eventSource) || !attempt.opened) return@synchronized
                        // Event bodies can contain private messages and are never diagnostic log data.
                        notifySafely { onEvent?.invoke(type, data) }
                    }

                override fun onClosed(eventSource: EventSource) = synchronized(lock) {
                    if (!acceptsLocked(attempt, eventSource)) return@synchronized
                    finishAttemptLocked(attempt, eventSource)
                    log("SSE closed; generation=${attempt.generation}")
                    notifySafely { onClosed?.invoke() }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) =
                    synchronized(lock) {
                        if (!acceptsLocked(attempt, eventSource)) return@synchronized
                        finishAttemptLocked(attempt, eventSource)
                        log("SSE failure; generation=${attempt.generation}, type=${t?.javaClass?.simpleName}, httpStatus=${response?.code}")
                        notifySafely { onError?.invoke(t) }
                    }
            })
            if (attempt.source == null) attempt.source = source
            // A synchronous callback can stop/replace this attempt before the factory returns.
            if (currentAttempt !== attempt || !desiredRunning || disposed) cancelAttemptLocked(attempt)
        } catch (error: Exception) {
            if (currentAttempt !== attempt || !desiredRunning || disposed) return
            finishAttemptLocked(attempt, attempt.source)
            log("SSE creation failed; generation=${attempt.generation}, type=${error.javaClass.simpleName}")
            notifySafely { onError?.invoke(error) }
        }
    }

    private fun acceptsLocked(attempt: Attempt, source: EventSource): Boolean =
        desiredRunning && !disposed && currentAttempt === attempt &&
            attempt.generation == generation && (attempt.source == null || attempt.source === source)

    private fun finishAttemptLocked(attempt: Attempt, source: EventSource?) {
        if (attempt.source == null) attempt.source = source
        currentAttempt = null
        generation++
        // Invalidate before cancel: cancellation may synchronously call onFailure again.
        cancelAttemptLocked(attempt)
        scheduleReconnectLocked()
    }

    private fun invalidateConnectionLocked() {
        generation++
        val old = currentAttempt
        currentAttempt = null
        val retry = reconnectJob
        reconnectJob = null
        retry?.cancel()
        old?.let { cancelAttemptLocked(it) }
    }

    private fun cancelAttemptLocked(attempt: Attempt) {
        val source = attempt.source ?: return
        if (attempt.canceled) return
        attempt.canceled = true
        try {
            source.cancel()
        } catch (error: Exception) {
            log("SSE cancel failed; type=${error.javaClass.simpleName}")
        }
    }

    private fun scheduleReconnectLocked() {
        if (!desiredRunning || disposed || !networkAvailable || reconnectJob != null) return
        reconnectAttempts++
        val waitMillis = when {
            reconnectAttempts > 10 -> 60_000L
            reconnectAttempts > 5 -> 30_000L
            else -> 5_000L
        }
        val expectedGeneration = generation
        lateinit var retry: Job
        retry = scope.launch(start = CoroutineStart.LAZY) {
            delay(waitMillis)
            synchronized(lock) {
                if (reconnectJob !== retry || generation != expectedGeneration ||
                    !desiredRunning || disposed || !networkAvailable) return@synchronized
                reconnectJob = null
                beginConnectionLocked()
            }
        }
        reconnectJob = retry
        log("SSE reconnect scheduled; waitMillis=$waitMillis, attempt=$reconnectAttempts")
        retry.start()
    }

    private inline fun notifySafely(callback: () -> Unit) {
        try {
            callback()
        } catch (error: Exception) {
            log("SSE listener failed; type=${error.javaClass.simpleName}")
        }
    }

    companion object { const val TAG = "SSEManager" }
}
