package com.example.virgo

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimestampTest {
    private val created = Instant.parse("2026-10-08T06:07:08Z").toEpochMilli().toString()

    @Test
    fun millisecondsDisplayMonthDayAndTimeWithoutYear() {
        assertEquals("10-08 06:07:08", formatChatTimestamp(created, ZoneId.of("UTC")))
    }

    @Test
    fun phoneTimezoneCanMoveMessageToPreviousDay() {
        assertEquals("10-07 23:07:08", formatChatTimestamp(created, ZoneId.of("America/Vancouver")))
    }

    @Test
    fun isoTimestampsAndOffsetsUseTheSamePhoneTimezone() {
        assertEquals("10-07 23:07:08", formatChatTimestamp("2026-10-08T06:07:08Z", ZoneId.of("America/Vancouver")))
        assertEquals("10-08 06:07:08", formatChatTimestamp("2026-10-08T08:07:08+02:00", ZoneId.of("UTC")))
    }

    @Test
    fun daylightSavingTransitionUsesTimezoneRules() {
        assertEquals("11-01 01:30:00", formatChatTimestamp("2026-11-01T08:30:00Z", ZoneId.of("America/Vancouver")))
        assertEquals("11-01 01:30:00", formatChatTimestamp("2026-11-01T09:30:00Z", ZoneId.of("America/Vancouver")))
    }

    @Test
    fun absentOrInvalidTimeDoesNotInventADate() {
        for (raw in listOf("", "null", "Now", "bad date", "0", "-1", Long.MAX_VALUE.toString())) {
            assertEquals(raw, "", formatChatTimestamp(raw, ZoneId.of("UTC")))
        }
    }

    @Test
    fun receiveAndSendTimeTakePrecedenceOverCreationTime() {
        val actual = Instant.parse("2026-10-08T06:08:09Z").toEpochMilli().toString()
        assertEquals(actual, preferredMessageTimestamp(MessageDirection.Inbound, created, receivedAt = actual))
        assertEquals(actual, preferredMessageTimestamp(MessageDirection.Outbound, created, sentAt = actual))
    }

    @Test
    fun missingOrInvalidActualTimeFallsBackToCreationTime() {
        assertEquals(created, preferredMessageTimestamp(MessageDirection.Inbound, created, receivedAt = "0"))
        assertEquals(created, preferredMessageTimestamp(MessageDirection.Outbound, created, sentAt = "invalid"))
        assertEquals("", preferredMessageTimestamp(MessageDirection.Outbound, "invalid"))
    }
}
