package me.capcom.smsgateway.modules.gateway

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MmsRetryPolicyTest {
    @get:Rule val folder = TemporaryFolder()

    private fun payload(type: String = "image/gif", data: String = "R0lGODlh") = """
        {"serverUrl":"http://original/mobile/v1","deviceId":"original-device",
        "payload":{"messageId":"42","attachments":[{"contentType":"$type","data":"$data"}]}}
    """.trimIndent()

    @Test fun onlyPreviouslyUnsupportedGifHasAutomaticRecovery() {
        assertTrue(isCompatibleGifRetry("HTTP 415", payload()))
        for (reason in listOf("HTTP 400", "HTTP 403", "HTTP 409", "HTTP 413", "HTTP 500", "HTTP 415; GIF compatibility checked")) {
            assertFalse(isCompatibleGifRetry(reason, payload()))
        }
        for (type in listOf("image/jpeg", "image/png", "image/webp", "image/heic", "application/javascript")) {
            assertFalse(isCompatibleGifRetry("HTTP 415", payload(type)))
        }
    }

    @Test fun incompleteOrMalformedPayloadsStayRetainedForInspection() {
        for (json in listOf("{}", "null", "not-json", payload(data=""), payload().replace("original-device", ""))) {
            assertFalse(isCompatibleGifRetry("HTTP 415", json))
        }
    }

    @Test fun selectedRecoveryKeepsOriginalBytesAndLeavesOtherFailuresUntouched() {
        val directory = folder.newFolder()
        val box = MmsOutbox(directory)
        val gif = box.key("server", "device", "gif")
        val rejected = box.key("server", "device", "rejected")
        val otherFormat = box.key("server", "device", "unsupported")
        for ((key, data, reason) in listOf(
            Triple(gif, payload(), "HTTP 415"),
            Triple(rejected, payload(), "HTTP 403"),
            Triple(otherFormat, payload("image/webp"), "HTTP 415"),
        )) {
            box.enqueue(key, data)
            box.fail(key, reason)
        }
        assertEquals(1, box.retryFailedMatching { data, reason -> isCompatibleGifRetry(reason, data) })
        assertEquals(listOf(gif), box.pending())
        assertEquals(payload(), box.read(gif))
        assertTrue(File(directory, "$rejected.failed").exists())
        assertTrue(File(directory, "$otherFormat.failed").exists())
        assertEquals(0, box.retryFailedMatching { data, reason -> isCompatibleGifRetry(reason, data) })
        box.complete(gif)
        box.enqueue(gif, payload())
        assertTrue(box.pending().isEmpty())
        assertTrue(box.contains(gif))
    }
}
