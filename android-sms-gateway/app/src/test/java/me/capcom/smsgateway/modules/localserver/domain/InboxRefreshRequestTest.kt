package me.capcom.smsgateway.modules.localserver.domain

import me.capcom.smsgateway.modules.incoming.db.IncomingMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Date

class InboxRefreshRequestTest {
    @Test
    fun validateRejectsMmsRefreshRequests() {
        val request = InboxRefreshRequest(
            since = Date(1_718_000_000_000),
            until = Date(1_718_000_001_000),
            messageTypes = setOf(IncomingMessageType.MMS),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            request.validate()
        }

        assertEquals("messageTypes contains unsupported values: MMS", error.message)
    }

    @Test
    fun validateRejectsDownloadedMmsRefreshRequests() {
        val request = InboxRefreshRequest(
            since = Date(1_718_000_000_000),
            until = Date(1_718_000_001_000),
            messageTypes = setOf(IncomingMessageType.MMS_DOWNLOADED),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            request.validate()
        }

        assertEquals("messageTypes contains unsupported values: MMS_DOWNLOADED", error.message)
    }
}
