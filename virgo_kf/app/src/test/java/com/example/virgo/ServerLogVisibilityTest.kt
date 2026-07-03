package com.example.virgo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLogVisibilityTest {
    @Test
    fun serverLogsAreVisibleOnlyForDebugBuilds() {
        assertTrue(shouldShowServerLogs(isDebugBuild = true))
        assertFalse(shouldShowServerLogs(isDebugBuild = false))
    }
}
