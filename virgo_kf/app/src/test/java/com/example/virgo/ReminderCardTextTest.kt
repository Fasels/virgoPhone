package com.example.virgo

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderCardTextTest {
    @Test
    fun buttonLabelUsesEzCopyText() {
        assertEquals("EZ Copy", reminderButtonLabel())
    }

    @Test
    fun reminderTitleUsesMenuId() {
        assertEquals("menu_42", reminderCardTitle("menu_42"))
    }

    @Test
    fun previewKeepsShortReminderText() {
        assertEquals(
            "Please wait while I check.",
            reminderCardPreview("Please wait while I check.", maxLength = 30),
        )
    }

    @Test
    fun previewCollapsesWhitespaceAndTruncatesLongReminderText() {
        assertEquals(
            "Line one line two line...",
            reminderCardPreview("Line one\nline two    line three", maxLength = 25),
        )
    }
}
