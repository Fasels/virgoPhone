package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimMetadataSyncStateTest {
    @Test fun notRunningUntilExplicitStart() {
        assertNull(SimMetadataSyncState().current())
    }

    @Test fun repeatedStartDoesNotInvalidateTheCurrentSession() {
        val state = SimMetadataSyncState()
        val first = state.start()
        assertEquals(first, state.start())
        assertTrue(state.isCurrent(first))
    }

    @Test fun stopInvalidatesAlreadyStartedWorker() {
        val state = SimMetadataSyncState()
        val generation = state.start()
        state.stop()
        assertFalse(state.isCurrent(generation))
        assertNull(state.current())
    }

    @Test fun restartRejectsThePreviousSessionsLateWorker() {
        val state = SimMetadataSyncState()
        val previous = state.start()
        state.stop()
        val current = state.start()
        assertFalse(state.isCurrent(previous))
        assertTrue(state.isCurrent(current))
    }
}
