package me.capcom.smsgateway.modules.receiver.events

import me.capcom.smsgateway.modules.incoming.db.IncomingMessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagesExportRequestedEventTest {
    @Test
    fun withPayloadDropsUnsupportedMmsTypes() {
        val event = MessagesExportRequestedEvent.withPayload(
            """
                {
                  "since": "2026-01-01T00:00:00Z",
                  "until": "2026-01-02T00:00:00Z",
                  "messageTypes": "SMS,MMS,MMS_DOWNLOADED",
                  "triggerWebhooks": true
                }
            """.trimIndent()
        )

        assertEquals(setOf(IncomingMessageType.SMS), event.messageTypes)
    }
}
