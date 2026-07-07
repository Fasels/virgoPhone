package me.capcom.smsgateway.modules.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class MmsContentObserverTest {
    @Test
    fun scanStartIdKeepsSmallMarksAtZero() {
        assertEquals(0L, MmsContentObserver.scanStartId(0L))
        assertEquals(0L, MmsContentObserver.scanStartId(1L))
        assertEquals(0L, MmsContentObserver.scanStartId(20L))
    }

    @Test
    fun scanStartIdRewindsRecentMessages() {
        assertEquals(5L, MmsContentObserver.scanStartId(25L))
        assertEquals(180L, MmsContentObserver.scanStartId(200L))
    }
}
