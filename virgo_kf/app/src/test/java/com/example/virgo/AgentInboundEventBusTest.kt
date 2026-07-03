package com.example.virgo

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentInboundEventBusTest {
    @Test
    fun subscriberReceivesEventsUntilUnsubscribed() {
        val received = mutableListOf<String>()
        val unsubscribe = AgentInboundEventBus.subscribe { event ->
            received += event.messageId
        }

        AgentInboundEventBus.publish(inboundEvent("msg_1"))
        unsubscribe()
        AgentInboundEventBus.publish(inboundEvent("msg_2"))

        assertEquals(listOf("msg_1"), received)
    }

    private fun inboundEvent(messageId: String): AgentInboundMessageEvent {
        return AgentInboundMessageEvent(
            conversationId = "conv_1",
            messageId = messageId,
            accountId = "acct_1",
            simCardId = null,
        )
    }
}
