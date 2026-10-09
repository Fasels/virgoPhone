package me.capcom.smsgateway.helpers

import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test

@OptIn(InternalCoroutinesApi::class)
private class ManualDispatcher : CoroutineDispatcher(), Delay {
    private data class Timer(val at: Long, val continuation: CancellableContinuation<Unit>)
    private val tasks = ArrayDeque<Runnable>()
    private val timers = mutableListOf<Timer>()
    private var now = 0L
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) { tasks.addLast(block) }
    }
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        synchronized(timers) { timers += Timer(now + timeMillis, continuation) }
    }
    fun drain() {
        repeat(1000) {
            val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() } ?: return
            next.run()
        }
        error("Unexpected immediate task loop")
    }
    fun advanceBy(millis: Long) {
        drain()
        now += millis
        val due = synchronized(timers) { timers.filter { it.at <= now }.also { timers.removeAll(it.toSet()) } }
        due.forEach { if (it.continuation.isActive) it.continuation.resume(Unit) }
        drain()
    }
    fun pendingTimers(): Int = synchronized(timers) { timers.count { it.continuation.isActive } }
}

private class InlineDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}

private class FakeSource(private val request: Request, val listener: EventSourceListener) : EventSource {
    var cancelCount = 0
    var emitFailureOnCancel = false
    override fun request(): Request = request
    override fun cancel() {
        cancelCount++
        if (emitFailureOnCancel) fail()
    }
    fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").build())
    fun closed() = listener.onClosed(this)
    fun fail() = listener.onFailure(this, IOException("synthetic failure"), null)
    fun event() = listener.onEvent(this, "synthetic-id", "MessageEnqueued", "synthetic-payload")
}

private class FakeFactory : EventSource.Factory {
    val sources = mutableListOf<FakeSource>()
    var beforeReturn: ((FakeSource) -> Unit)? = null
    var throwOnCreate = false
    override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
        if (throwOnCreate) throw IOException("synthetic create failure")
        return FakeSource(request, listener).also { sources += it; beforeReturn?.invoke(it) }
    }
}

private class Fixture(inline: Boolean = false) : AutoCloseable {
    val dispatcher = ManualDispatcher()
    val scope = CoroutineScope((if (inline) InlineDispatcher() else dispatcher) + Job())
    val factory = FakeFactory()
    val manager = SSEManager("http://isolated.invalid/events", "synthetic-token", scope, factory, {})
    var connected = 0
    var events = 0
    var failures = 0
    init {
        manager.onConnected = { connected++ }
        manager.onEvent = { _, _ -> events++ }
        manager.onError = { failures++ }
    }
    fun connect(): FakeSource { manager.connect(); return factory.sources.last() }
    override fun close() { manager.close(); dispatcher.drain() }
}

class SSEManagerTest {
    @Test fun repeatedConnectIsIdempotent() = repeat(50) {
        Fixture().use { f ->
            val source = f.connect()
            repeat(10) { f.manager.connect() }
            source.open()
            repeat(10) { f.manager.connect() }
            assertEquals(1, f.factory.sources.size)
            assertEquals(0, source.cancelCount)
            assertEquals(1, f.connected)
        }
    }

    @Test fun oldClosedCallbackCannotCancelTheNewConnection() = repeat(50) {
        Fixture().use { f ->
            val old = f.connect().also { it.open() }
            f.manager.connect("http://new.invalid/events", "synthetic-new-token")
            val current = f.factory.sources.last().also { it.open() }
            old.closed()
            f.dispatcher.drain()
            assertEquals(1, old.cancelCount)
            assertEquals(0, current.cancelCount)
            assertEquals(0, f.dispatcher.pendingTimers())
            assertTrue(f.manager.isCurrentConnection(f.manager.currentConnectionGeneration()!!))
        }
    }

    @Test fun oldFailureCallbackCannotCancelTheNewConnection() = repeat(50) {
        Fixture().use { f ->
            val old = f.connect()
            f.manager.disconnect()
            val current = f.connect().also { it.open() }
            old.fail()
            f.dispatcher.drain()
            assertEquals(0, current.cancelCount)
            assertEquals(0, f.failures)
            assertEquals(0, f.dispatcher.pendingTimers())
        }
    }

    @Test fun cancelFailureCallbackLeavesOnlyOneReconnectTask() = repeat(50) {
        Fixture().use { f ->
            val source = f.connect().also { it.emitFailureOnCancel = true }
            source.closed()
            source.fail()
            f.dispatcher.drain()
            assertEquals(1, source.cancelCount)
            assertEquals(1, f.dispatcher.pendingTimers())
            assertEquals(1, f.scope.coroutineContext[Job]!!.children.count())
            f.dispatcher.advanceBy(5000)
            assertEquals(2, f.factory.sources.size)
        }
    }

    @Test fun disconnectCleansUpBeforeQueuedTasksCanRun() = repeat(50) {
        Fixture().use { f ->
            val source = f.connect()
            f.manager.disconnect()
            assertEquals(1, source.cancelCount)
            assertNull(f.manager.currentConnectionGeneration())
            f.dispatcher.drain()
            assertEquals(0, f.scope.coroutineContext[Job]!!.children.count())
        }
    }

    @Test fun disconnectHasTheSameCleanupWithInlineScheduling() = repeat(50) {
        Fixture(inline = true).use { f ->
            val source = f.connect()
            f.manager.disconnect()
            assertEquals(1, source.cancelCount)
            assertNull(f.manager.currentConnectionGeneration())
            assertEquals(0, f.scope.coroutineContext[Job]!!.children.count())
        }
    }

    @Test fun repeatedStartDoesNotBypassReconnectBackoff() {
        Fixture().use { f ->
            f.connect().fail()
            f.dispatcher.drain()
            repeat(20) { f.manager.connect() }
            f.dispatcher.advanceBy(4999)
            assertEquals(1, f.factory.sources.size)
            f.dispatcher.advanceBy(1)
            assertEquals(2, f.factory.sources.size)
        }
    }

    @Test fun stoppedRetryAndLateCallbacksCannotReviveTheManager() = repeat(50) {
        Fixture().use { f ->
            val old = f.connect()
            old.fail()
            f.dispatcher.drain()
            f.manager.disconnect()
            old.open(); old.closed(); old.fail(); old.event()
            f.manager.onNetworkAvailable(forceReconnect = true)
            f.dispatcher.advanceBy(60000)
            assertEquals(1, f.factory.sources.size)
            assertEquals(0, f.connected)
            assertEquals(0, f.events)
            assertEquals(0, f.dispatcher.pendingTimers())
        }
    }

    @Test fun networkLossPausesAndRecoveryCreatesOnlyOneConnection() {
        Fixture().use { f ->
            val old = f.connect().also { it.open() }
            f.manager.onNetworkUnavailable()
            repeat(5) { f.manager.connect(); old.fail() }
            f.dispatcher.advanceBy(60000)
            assertEquals(1, f.factory.sources.size)
            assertEquals(0, f.dispatcher.pendingTimers())
            repeat(10) { f.manager.onNetworkAvailable() }
            assertEquals(2, f.factory.sources.size)
            assertEquals(1, old.cancelCount)
        }
    }

    @Test fun networkHandoverInvalidatesOnlyTheOldGeneration() {
        Fixture().use { f ->
            val old = f.connect().also { it.open() }
            val previousGeneration = f.manager.currentConnectionGeneration()!!
            f.manager.onNetworkAvailable(forceReconnect = true)
            val current = f.factory.sources.last().also { it.open() }
            old.open(); old.event(); old.closed(); old.fail()
            assertFalse(f.manager.isCurrentConnection(previousGeneration))
            assertEquals(0, current.cancelCount)
            assertEquals(2, f.connected)
            assertEquals(0, f.events)
            assertEquals(0, f.failures)
        }
    }

    @Test fun changedUrlOrTokenReplacesTheConnectionWithoutChangingIdentity() {
        Fixture().use { f ->
            val first = f.connect()
            f.manager.connect("http://isolated.invalid/events", "synthetic-changed-token")
            val second = f.factory.sources.last()
            assertEquals(1, first.cancelCount)
            assertEquals("Bearer synthetic-changed-token", second.request().header("Authorization"))
            f.manager.connect("http://new.invalid/events", "synthetic-changed-token")
            assertEquals(1, second.cancelCount)
            assertEquals("new.invalid", f.factory.sources.last().request().url.host)
            assertEquals(3, f.factory.sources.size)
        }
    }

    @Test fun synchronousOpenCanStopBeforeTheFactoryReturns() {
        Fixture().use { f ->
            f.factory.beforeReturn = { it.open() }
            f.manager.onConnected = { f.manager.disconnect() }
            val source = f.connect()
            assertEquals(1, source.cancelCount)
            assertNull(f.manager.currentConnectionGeneration())
            assertEquals(0, f.dispatcher.pendingTimers())
        }
    }

    @Test fun synchronousFactoryFailureHasOneCleanupAndOneRetry() {
        Fixture().use { f ->
            f.factory.beforeReturn = { it.fail() }
            val source = f.connect()
            f.dispatcher.drain()
            assertEquals(1, source.cancelCount)
            assertEquals(1, f.failures)
            assertEquals(1, f.dispatcher.pendingTimers())
        }
    }

    @Test fun thrownFactoryErrorCanRetryAndStop() {
        Fixture().use { f ->
            f.factory.throwOnCreate = true
            f.manager.connect()
            f.dispatcher.drain()
            assertEquals(1, f.failures)
            assertEquals(1, f.dispatcher.pendingTimers())
            f.manager.disconnect()
            f.factory.throwOnCreate = false
            f.dispatcher.advanceBy(60000)
            assertEquals(0, f.factory.sources.size)
        }
    }

    @Test fun duplicateOpenOrThrowingCallbackDoesNotDuplicateConnections() {
        Fixture().use { f ->
            val source = f.connect()
            f.manager.onConnected = { f.connected++; throw IllegalStateException("synthetic callback failure") }
            source.open(); source.open()
            assertEquals(1, f.connected)
            assertEquals(1, f.factory.sources.size)
        }
    }

    @Test fun closeCannotBeReopenedByServiceOrNetworkCallbacks() {
        Fixture().use { f ->
            val old = f.connect()
            f.manager.close()
            f.manager.connect()
            f.manager.onNetworkAvailable(forceReconnect = true)
            old.fail(); old.open()
            assertEquals(1, f.factory.sources.size)
            assertEquals(1, old.cancelCount)
            assertEquals(0, f.connected)
        }
    }

    @Test fun concurrentServiceStartCommandsCreateOneSource() {
        Fixture().use { f ->
            val barrier = CyclicBarrier(9)
            val threads = (1..8).map { thread { barrier.await(); repeat(20) { f.manager.connect() } } }
            barrier.await()
            threads.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertEquals(1, f.factory.sources.size)
        }
    }

    @Test fun failureRacingWithStopCannotLeaveARetry() = repeat(50) {
        Fixture().use { f ->
            val old = f.connect()
            val barrier = CyclicBarrier(3)
            val failure = thread { barrier.await(); old.fail() }
            val stop = thread { barrier.await(); f.manager.disconnect() }
            barrier.await()
            failure.join(5000); stop.join(5000)
            assertFalse(failure.isAlive); assertFalse(stop.isAlive)
            f.dispatcher.advanceBy(60000)
            assertEquals(1, f.factory.sources.size)
            assertEquals(0, f.dispatcher.pendingTimers())
        }
    }

    @Test fun networkRecoveryRacingWithStopCannotLeaveALiveConnection() = repeat(50) {
        Fixture().use { f ->
            f.connect()
            f.manager.onNetworkUnavailable()
            val barrier = CyclicBarrier(3)
            val available = thread { barrier.await(); f.manager.onNetworkAvailable() }
            val stop = thread { barrier.await(); f.manager.disconnect() }
            barrier.await()
            available.join(5000); stop.join(5000)
            assertFalse(available.isAlive); assertFalse(stop.isAlive)
            assertTrue(f.factory.sources.all { it.cancelCount == 1 })
            assertNull(f.manager.currentConnectionGeneration())
        }
    }

    @Test fun actualOkHttpCommentHeartbeatsKeepReadAliveThenSilenceTimesOut() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val heartbeatCount = AtomicInteger()
        val errors = mutableListOf<Throwable?>()
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val client = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
        val manager = SSEManager("http://127.0.0.1:${server.localPort}/events", "synthetic-token",
            scope, EventSources.createFactory(client), {})
        manager.onConnected = { opened.countDown() }
        manager.onError = { synchronized(errors) { errors += it }; failed.countDown() }
        val socketThread = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { /* synthetic HTTP request */ }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    output.flush()
                    repeat(7) {
                        val comment = ": heartbeat\n\n".toByteArray()
                        output.write((comment.size.toString(16) + "\r\n").toByteArray())
                        output.write(comment); output.write("\r\n".toByteArray()); output.flush()
                        heartbeatCount.incrementAndGet()
                        Thread.sleep(100)
                    }
                    failed.await(4, TimeUnit.SECONDS)
                }
            }
        }
        try {
            manager.connect()
            assertTrue("SSE headers were not opened", opened.await(5, TimeUnit.SECONDS))
            assertTrue("Socket read timeout did not fire after heartbeat bytes stopped", failed.await(5, TimeUnit.SECONDS))
            assertEquals(7, heartbeatCount.get())
            assertTrue(errors.single() is SocketTimeoutException)
            dispatcher.drain()
            assertEquals(1, dispatcher.pendingTimers())
        } finally {
            manager.close(); dispatcher.drain(); server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            socketThread.join(5000)
        }
    }
}
