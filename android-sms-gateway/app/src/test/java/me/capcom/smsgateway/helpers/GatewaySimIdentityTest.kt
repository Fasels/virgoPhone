package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GatewaySimIdentityTest {
    @Test fun formattedNumberDoesNotChangeRoutingFence() {
        assertEquals(GatewaySimIdentity.fingerprint(0, 9, "+1 (604) 555-0123", null),
            GatewaySimIdentity.fingerprint(0, 9, "6045550123", null))
    }
    @Test fun slotSubscriptionNumberAndCardChangesInvalidateFence() {
        val before = GatewaySimIdentity.fingerprint(0, 9, "+16045550123", "card-a")
        assertNotEquals(before, GatewaySimIdentity.fingerprint(1, 9, "+16045550123", "card-a"))
        assertNotEquals(before, GatewaySimIdentity.fingerprint(0, 10, "+16045550123", "card-a"))
        assertNotEquals(before, GatewaySimIdentity.fingerprint(0, 9, "+16045550124", "card-a"))
        assertNotEquals(before, GatewaySimIdentity.fingerprint(0, 9, "+16045550123", "card-b"))
    }
}
