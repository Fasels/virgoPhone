package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.*
import org.junit.Test

class LegacyStatusIdentityTest {
    @Test fun sealedLegacyRouteCannotMoveToAnotherDeviceOrServer() {
        assertTrue(legacyStatusIdentityMatches("dev_1\nhttp://original", "dev_1", "http://original"))
        assertFalse(legacyStatusIdentityMatches("dev_1\nhttp://original", "dev_2", "http://original"))
        assertFalse(legacyStatusIdentityMatches("dev_1\nhttp://original", "dev_1", "http://different"))
        assertFalse(legacyStatusIdentityMatches(null, "dev_1", "http://original"))
        assertFalse(legacyStatusIdentityMatches("UNBOUND", "dev_1", "http://original"))
    }
}
