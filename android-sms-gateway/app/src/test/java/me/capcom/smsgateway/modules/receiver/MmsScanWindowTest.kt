package me.capcom.smsgateway.modules.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MmsScanWindowTest {
    @Test
    fun upgradeDoesNotReplayYesterdayButIncludesMessagesBeforeServiceStarts() {
        val installed = 1_791_400_000_000L
        val firstScan = installed + 5 * 60 * 1000
        val since = initialMmsScanSinceSeconds(installed, firstScan)
        assertFalse((installed - 24 * 60 * 60 * 1000) / 1000 >= since)
        assertTrue((installed + 10_000) / 1000 >= since)
    }

    @Test
    fun missingPackageTimestampStartsAtActivationWithoutHistoricalReplay() {
        assertEquals(1_791_400_000L, initialMmsScanSinceSeconds(0, 1_791_400_000_000L))
    }

    @Test
    fun futurePackageTimestampDoesNotSkipFutureIncomingMessages() {
        assertEquals(1_791_400_000L, initialMmsScanSinceSeconds(1_791_500_000_000L, 1_791_400_000_000L))
    }
}
