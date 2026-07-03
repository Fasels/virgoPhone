package com.example.virgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageNotificationPolicyTest {
    @Test
    fun shouldNotifyOnlyWhenAppIsNotInForeground() {
        val event = inboundEvent()

        assertTrue(AgentMessageNotificationPolicy.shouldNotify(appInForeground = false, event = event))
        assertFalse(AgentMessageNotificationPolicy.shouldNotify(appInForeground = true, event = event))
    }

    @Test
    fun decisionExplainsWhyInboundNotificationIsSuppressed() {
        assertEquals(
            AgentMessageNotificationDecision.Notify,
            AgentMessageNotificationPolicy.decisionFor(appInForeground = false, event = inboundEvent()),
        )
        assertEquals(
            AgentMessageNotificationDecision.SuppressedAppInForeground,
            AgentMessageNotificationPolicy.decisionFor(appInForeground = true, event = inboundEvent()),
        )
        assertEquals(
            AgentMessageNotificationDecision.SuppressedMissingMessageId,
            AgentMessageNotificationPolicy.decisionFor(appInForeground = false, event = inboundEvent(messageId = "")),
        )
    }

    @Test
    fun contentUsesMessageTextWhenPresent() {
        val content = AgentMessageNotificationPolicy.contentFor(
            inboundEvent(text = "Hello from customer"),
        )

        assertEquals("New customer message", content.title)
        assertEquals("Hello from customer", content.body)
    }

    @Test
    fun contentFallsBackWhenMessageTextIsOnlyPlaceholder() {
        val content = AgentMessageNotificationPolicy.contentFor(
            inboundEvent(text = CustomerServiceStore.IncomingMessagePlaceholder),
        )

        assertEquals("New customer message", content.title)
        assertEquals("Open the app to view the latest message.", content.body)
    }

    private fun inboundEvent(
        text: String = "Hello",
        messageId: String = "msg_1",
    ): AgentInboundMessageEvent {
        return AgentInboundMessageEvent(
            conversationId = "conv_1",
            messageId = messageId,
            accountId = "acct_1",
            simCardId = null,
            text = text,
            state = "Received",
            time = "Now",
        )
    }
}
