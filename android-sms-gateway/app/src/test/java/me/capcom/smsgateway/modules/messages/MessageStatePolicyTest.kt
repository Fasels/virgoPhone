package me.capcom.smsgateway.modules.messages

import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.domain.ProcessingState.*
import org.junit.Assert.*
import org.junit.Test

class MessageStatePolicyTest {
    @Test fun `all callbacks obey forward only transitions`() {
        val accepted = mapOf(
            Pending to setOf(Processed, Sent, Delivered, Failed),
            Processed to setOf(Sent, Delivered, Failed),
            Sent to setOf(Delivered, Failed),
            Delivered to emptySet(), Failed to emptySet(),
        )
        for (current in ProcessingState.values()) for (next in ProcessingState.values()) {
            assertEquals("$current -> $next", next in accepted.getValue(current), MessageStatePolicy.accepts(current, next))
        }
    }

    @Test fun `late processed and duplicate sent cannot downgrade successful delivery`() {
        assertFalse(MessageStatePolicy.accepts(Delivered, Processed))
        assertFalse(MessageStatePolicy.accepts(Delivered, Sent))
        assertFalse(MessageStatePolicy.accepts(Sent, Sent))
        assertFalse(MessageStatePolicy.accepts(Failed, Sent))
    }

    @Test fun `multipart success waits for every distinct part`() {
        assertNull(MessageStatePolicy.partOutcome(3, listOf(Sent)))
        assertNull(MessageStatePolicy.partOutcome(3, listOf(Sent, Sent)))
        assertEquals(Sent, MessageStatePolicy.partOutcome(3, listOf(Sent, Sent, Sent)))
        assertNull(MessageStatePolicy.partOutcome(2, listOf(Delivered)))
        assertEquals(Delivered, MessageStatePolicy.partOutcome(2, listOf(Delivered, Delivered)))
    }

    @Test fun `one failed part fails the complete SMS without waiting for missing callbacks`() {
        assertEquals(Failed, MessageStatePolicy.partOutcome(4, listOf(Sent, Failed)))
        assertEquals(Failed, MessageStatePolicy.partOutcome(4, listOf(Failed)))
    }

    @Test fun `mixed recipients preserve pending work then aggregate terminal outcomes`() {
        assertEquals(Pending, MessageStatePolicy.aggregate(listOf(Sent, Pending)))
        assertEquals(Processed, MessageStatePolicy.aggregate(listOf(Sent, Processed)))
        assertEquals(Sent, MessageStatePolicy.aggregate(listOf(Delivered, Failed)))
        assertEquals(Failed, MessageStatePolicy.aggregate(listOf(Failed, Failed)))
        assertEquals(Delivered, MessageStatePolicy.aggregate(listOf(Delivered, Delivered)))
    }
}
