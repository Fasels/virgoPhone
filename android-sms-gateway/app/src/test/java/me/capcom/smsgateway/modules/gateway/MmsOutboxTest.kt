package me.capcom.smsgateway.modules.gateway

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MmsOutboxTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun survivesRestartAndDoesNotReplaceAnImmutableSnapshot() {
        val directory = folder.newFolder()
        val box = MmsOutbox(directory)
        val key = box.key("server", "device", "123")
        box.enqueue(key, "x".repeat(20_000))
        val restored = MmsOutbox(directory)
        restored.enqueue(key, "different payload")
        assertEquals(20_000, restored.read(key).length)
        assertEquals(listOf(key), restored.pending())
        restored.complete(key)
        assertTrue(MmsOutbox(directory).contains(key))
        assertTrue(restored.pending().isEmpty())
        restored.enqueue(key, "duplicate")
        assertFalse(File(directory, "$key.json").exists())
    }

    @Test fun failedPayloadRemainsAvailableForExplicitRetry() {
        val box = MmsOutbox(folder.newFolder())
        val key = box.key("server", "device", "123")
        box.enqueue(key, "payload")
        box.fail(key, "HTTP 415")
        assertTrue(box.pending().isEmpty())
        assertEquals("payload", box.read(key))
        box.retryFailed()
        assertEquals(listOf(key), box.pending())
    }

    @Test fun serverAndDeviceIdentityPreventCrossAccountReuse() {
        val box = MmsOutbox(folder.newFolder())
        assertNotEquals(box.key("a", "device", "1"), box.key("b", "device", "1"))
        assertNotEquals(box.key("a", "device", "1"), box.key("a", "other", "1"))
    }

    @Test fun unfinishedTemporaryWriteIsNotAnUploadedMessage() {
        val directory = folder.newFolder()
        val box = MmsOutbox(directory)
        val key = box.key("server", "device", "1")
        File(directory, "$key.json.tmp").writeText("partial")
        assertFalse(box.contains(key))
        box.enqueue(key, "complete")
        assertEquals("complete", box.read(key))
    }
}
