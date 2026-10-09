package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

class SmsInboxOutboxTest {
    @get:Rule val temp = TemporaryFolder()
    private fun folder() = File(temp.root, "outbox")

    @Test fun payloadSurvivesWorkerFailureAndProcessRecreation() {
        val store = SmsInboxOutbox(folder())
        val key = store.key("server", "device", "message")
        val payload = "large synthetic SMS ".repeat(2_000)
        store.enqueue(key, payload)
        val restarted = SmsInboxOutbox(folder())
        assertEquals(listOf(key), restarted.pending())
        assertEquals(payload, restarted.read(key))
        assertTrue(payload.toByteArray().size > 10_240)
    }

    @Test fun completedReceiptPreventsDuplicateUploadAfterCrashOrRepeatBroadcast() {
        val store = SmsInboxOutbox(folder())
        val key = store.key("server", "device", "message")
        store.enqueue(key, "original")
        store.complete(key)
        store.enqueue(key, "replacement")
        assertTrue(SmsInboxOutbox(folder()).pending().isEmpty())
        assertFalse(File(folder(), "$key.json").exists())
    }

    @Test fun differentGatewayRoutesCannotOverwriteEachOthersPayloads() {
        val store = SmsInboxOutbox(folder())
        val keys = listOf(store.key("server", "device", "message"), store.key("server", "other", "message"),
            store.key("other-server", "device", "message"))
        assertEquals(3, keys.toSet().size)
        keys.forEachIndexed { index, key -> store.enqueue(key, "route-$index") }
        assertEquals(3, store.pending().size)
        keys.forEachIndexed { index, key -> assertEquals("route-$index", store.read(key)) }
    }

    @Test fun blockedPayloadIsRetainedAndOnlyAnExplicitRecoveryUnblocksIt() {
        val store = SmsInboxOutbox(folder())
        val key = store.key("server", "device", "message")
        store.enqueue(key, "immutable")
        store.block(key, "original-gateway-unavailable")
        assertTrue(store.pending().isEmpty())
        assertEquals("immutable", store.read(key))
        store.retryBlocked()
        assertEquals(listOf(key), store.pending())
    }

    @Test fun legacyInputWithoutItsRouteIsDurableButNeverSentAutomatically() {
        val store = SmsInboxOutbox(folder())
        store.retainLegacy("original legacy payload")
        store.retainLegacy("original legacy payload")
        store.retryBlocked()
        assertTrue(store.pending().isEmpty())
        val retained = folder().listFiles()!!.single()
        assertEquals("legacy-unbound", retained.extension)
        assertEquals("original legacy payload", retained.readText())
    }

    @Test fun incompleteTemporaryWriteCannotAppearAsPending() {
        folder().mkdirs()
        File(folder(), "partial.json.tmp").writeText("partial")
        assertTrue(SmsInboxOutbox(folder()).pending().isEmpty())
    }

    @Test fun simultaneousReceiversKeepOneWholeImmutablePayload() {
        val store = SmsInboxOutbox(folder())
        val key = store.key("server", "device", "message")
        val start = CountDownLatch(1)
        val payloads = (0 until 20).map { "payload-$it-".repeat(1_000) }
        val threads = payloads.map { payload -> Thread { start.await(); store.enqueue(key, payload) }.apply { start() } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(listOf(key), store.pending())
        assertTrue(store.read(key) in payloads)
        assertFalse(folder().listFiles()!!.any { it.extension == "tmp" })
    }
}
