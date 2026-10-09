package me.capcom.smsgateway.modules.incoming

import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class SmsInboxIdentityTest {
    private fun text(body: String = "same", time: Long = 1_000, subscription: Int = 1) =
        InboxMessage.Text(body, "+15555550100", Date(time), subscription)

    @Test fun repeatedTextAtDifferentReceiveTimesRemainsTwoMessages() {
        assertNotEquals(smsInboxIdentity(text()), smsInboxIdentity(text(time = 2_000)))
    }

    @Test fun exactBroadcastRetryKeepsTheSameIdentity() {
        assertEquals(smsInboxIdentity(text()), smsInboxIdentity(text()))
    }

    @Test fun differentSubscriptionRemainsSeparate() {
        assertNotEquals(smsInboxIdentity(text()), smsInboxIdentity(text(subscription = 2)))
    }

    @Test fun javaStringHashCollisionsDoNotMergeMessages() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(smsInboxIdentity(text("Aa")), smsInboxIdentity(text("BB")))
    }

    @Test fun identityIsBoundedForServerValidationEvenWithLongUnicodeText() {
        val id = requireNotNull(smsInboxIdentity(text("短信".repeat(20_000))))
        assertEquals(64, id.length)
        assertTrue(id.startsWith("text:v2:"))
    }

    @Test fun dataMessagesIncludeReceiveTimeAndExactData() {
        fun data(time: Long, bytes: ByteArray?) = InboxMessage.Data(bytes, "+15555550100", Date(time), 1)
        assertEquals(smsInboxIdentity(data(1, byteArrayOf(1, 2))), smsInboxIdentity(data(1, byteArrayOf(1, 2))))
        assertNotEquals(smsInboxIdentity(data(1, byteArrayOf(1, 2))), smsInboxIdentity(data(2, byteArrayOf(1, 2))))
        assertNotEquals(smsInboxIdentity(data(1, null)), smsInboxIdentity(data(1, byteArrayOf())))
    }

    @Test fun mmsRetainsItsOriginalIdentityScheme() {
        assertNull(smsInboxIdentity(InboxMessage.MmsHeaders("id", "transaction", null, 0, null,
            "+15555550100", Date(1), 1)))
    }
}
