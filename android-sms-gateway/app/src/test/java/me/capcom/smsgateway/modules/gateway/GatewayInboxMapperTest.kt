package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.incoming.db.IncomingMessageType
import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class GatewayInboxMapperTest {
    @Test
    fun mapsTextSmsToInboxUploadRequest() {
        val receivedAt = Date(1_718_000_000_000)
        val request = GatewayInboxMapper.toRequest(
            messageId = "text:abc",
            message = InboxMessage.Text(
                text = "Your code is 123456",
                address = "+15551234567",
                date = receivedAt,
                subscriptionId = 7,
            ),
            simNumber = 2,
            recipient = "+15557654321",
        )

        assertEquals("text:abc", request.id)
        assertEquals(IncomingMessageType.SMS, request.type)
        assertEquals("+15551234567", request.sender)
        assertEquals("+15557654321", request.recipient)
        assertEquals(2, request.simNumber)
        assertEquals(7, request.subscriptionId)
        assertEquals(receivedAt, request.receivedAt)
        assertEquals("Your code is 123456", request.textMessage?.text)
        assertNull(request.dataMessage)
    }

    @Test
    fun mapsDataSmsToBase64InboxUploadRequest() {
        val request = GatewayInboxMapper.toRequest(
            messageId = "data:abc",
            message = InboxMessage.Data(
                data = byteArrayOf(0x01, 0x02, 0x7f),
                address = "+15551234567",
                date = Date(1_718_000_000_000),
                subscriptionId = null,
            ),
            simNumber = null,
            recipient = null,
        )

        assertEquals("data:abc", request.id)
        assertEquals(IncomingMessageType.DATA_SMS, request.type)
        assertEquals("+15551234567", request.sender)
        assertEquals("AQJ/", request.dataMessage?.data)
        assertNull(request.textMessage)
    }
}
