package me.capcom.smsgateway.modules.receiver

import org.junit.Assert.*
import org.junit.Test

class MmsReadinessTest {
    @Test fun waitsUntilPartsStopChanging() {
        val gate = MmsReadiness()
        assertFalse(gate.isReady(1, 10, 0))
        assertFalse(gate.isReady(1, 10, 1_000))
        assertFalse(gate.isReady(1, 11, 2_000))
        assertFalse(gate.isReady(1, 11, 3_000))
        assertTrue(gate.isReady(1, 11, 4_000))
    }

    @Test fun lateOlderMessageIsNotSkippedAfterNewerMessage() {
        val gate = MmsReadiness()
        assertFalse(gate.isReady(2, 20, 0))
        assertTrue(gate.isReady(2, 20, 3_000))
        gate.forget(2)
        assertFalse(gate.isReady(1, 10, 4_000))
        assertTrue(gate.isReady(1, 10, 7_000))
        gate.forget(1)
        assertFalse(gate.isReady(1, 10, 8_000))
    }
}
