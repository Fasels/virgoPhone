package me.capcom.smsgateway.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRunIntentTest {
    @Test fun legacyInstallRetainsAutostartChoice() {
        assertTrue(resolveGatewayRunIntent(false, false, true))
        assertFalse(resolveGatewayRunIntent(false, true, false))
    }

    @Test fun explicitStopSurvivesAutostartAndImplicitRestarts() {
        assertFalse(resolveGatewayRunIntent(true, false, true))
        assertFalse(resolveGatewayRunIntent(true, false, false))
    }

    @Test fun explicitStartWorksWithAutostartDisabled() {
        assertTrue(resolveGatewayRunIntent(true, true, false))
    }
}
