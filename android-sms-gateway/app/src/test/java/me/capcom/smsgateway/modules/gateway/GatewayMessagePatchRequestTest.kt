package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.data.entities.Message
import me.capcom.smsgateway.data.entities.MessageRecipient
import me.capcom.smsgateway.data.entities.MessageState
import me.capcom.smsgateway.data.entities.MessageWithRecipients
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.ProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class GatewayMessagePatchRequestTest {
    @Test
    fun patchRequestIncludesSentTimeForDeliveredState() {
        val message = messageWithRecipients(
            recipients = listOf(
                MessageRecipient("msg-1", "+15550000001", ProcessingState.Delivered, null),
                MessageRecipient("msg-1", "+15550000002", ProcessingState.Delivered, null),
            ),
            states = listOf(
                MessageState("msg-1", ProcessingState.Sent, 1_000),
                MessageState("msg-1", ProcessingState.Delivered, 2_000),
                MessageState("msg-1", ProcessingState.Failed, 3_000),
            )
        )

        val patch = buildMessagePatchRequest(message, Date(4_000))

        assertEquals(ProcessingState.Delivered, patch.state)
        assertEquals(setOf(ProcessingState.Sent, ProcessingState.Delivered), patch.states.keys)
        assertEquals(Date(1_000), patch.states[ProcessingState.Sent])
        assertEquals(Date(2_000), patch.states[ProcessingState.Delivered])
        assertFalse(patch.states.containsKey(ProcessingState.Failed))
    }

    @Test
    fun patchRequestSynthesizesSentTimeWhenDeliveredHistoryIsMissing() {
        val message = messageWithRecipients(
            recipients = listOf(
                MessageRecipient("msg-1", "+15550000001", ProcessingState.Delivered, null),
            ),
            states = listOf(
                MessageState("msg-1", ProcessingState.Delivered, 2_000),
            )
        )

        val patch = buildMessagePatchRequest(message, Date(4_000))

        assertEquals(ProcessingState.Delivered, patch.state)
        assertEquals(Date(2_000), patch.states[ProcessingState.Sent])
        assertEquals(Date(2_000), patch.states[ProcessingState.Delivered])
    }

    @Test
    fun patchRequestDropsRecipientErrorsUnlessRecipientFailed() {
        val message = messageWithRecipients(
            recipients = listOf(
                MessageRecipient("msg-1", "+15550000001", ProcessingState.Sent, "ignored"),
                MessageRecipient("msg-1", "+15550000002", ProcessingState.Delivered, null),
            ),
            states = listOf(MessageState("msg-1", ProcessingState.Sent, 1_000))
        )

        val patch = buildMessagePatchRequest(message, Date(4_000))

        assertEquals(ProcessingState.Sent, patch.state)
        assertTrue(patch.recipients.all { it.error == null })
    }

    @Test
    fun patchRequestKeepsFailedRecipientError() {
        val message = messageWithRecipients(
            recipients = listOf(
                MessageRecipient("msg-1", "+15550000001", ProcessingState.Failed, "network rejected"),
            ),
            states = emptyList()
        )

        val patch = buildMessagePatchRequest(message, Date(4_000))

        assertEquals(ProcessingState.Failed, patch.state)
        assertEquals(setOf(ProcessingState.Failed), patch.states.keys)
        assertEquals(Date(4_000), patch.states[ProcessingState.Failed])
        assertEquals("network rejected", patch.recipients.single().error)
    }

    @Test
    fun processedStateIsReportedToGateway() {
        val message = messageWithRecipients(
            recipients = listOf(
                MessageRecipient("msg-1", "+15550000001", ProcessingState.Processed, null),
            ),
            states = listOf(MessageState("msg-1", ProcessingState.Processed, 1_000))
        )

        val patch = buildMessagePatchRequest(message, Date(4_000))

        assertEquals(ProcessingState.Processed, patch.state)
        assertTrue(patch.shouldReportToGateway())
    }

    @Test
    fun sentAndDeliveredStatesAreReportedToGateway() {
        assertTrue(
            buildMessagePatchRequest(
                messageWithRecipients(
                    recipients = listOf(
                        MessageRecipient("msg-1", "+15550000001", ProcessingState.Sent, null),
                    ),
                    states = listOf(MessageState("msg-1", ProcessingState.Sent, 1_000))
                )
            ).shouldReportToGateway()
        )
        assertTrue(
            buildMessagePatchRequest(
                messageWithRecipients(
                    recipients = listOf(
                        MessageRecipient("msg-1", "+15550000001", ProcessingState.Delivered, null),
                    ),
                    states = listOf(MessageState("msg-1", ProcessingState.Delivered, 1_000))
                )
            ).shouldReportToGateway()
        )
    }

    @Test
    fun failedStateIsReportedToGateway() {
        assertTrue(
            buildMessagePatchRequest(
                messageWithRecipients(
                    recipients = listOf(
                        MessageRecipient("msg-1", "+15550000001", ProcessingState.Failed, "failed"),
                    ),
                    states = listOf(MessageState("msg-1", ProcessingState.Failed, 1_000))
                )
            ).shouldReportToGateway()
        )
    }

    private fun messageWithRecipients(
        recipients: List<MessageRecipient>,
        states: List<MessageState>,
    ) = MessageWithRecipients(
        message = Message(
            id = "msg-1",
            withDeliveryReport = true,
            simNumber = 1,
            validUntil = null,
            scheduleAt = null,
            isEncrypted = false,
            skipPhoneValidation = true,
            priority = Message.PRIORITY_DEFAULT,
            source = EntitySource.Cloud,
            content = "test",
            state = ProcessingState.Pending,
            createdAt = 1_000,
            processedAt = null,
        ),
        recipients = recipients,
        states = states,
    )
}
