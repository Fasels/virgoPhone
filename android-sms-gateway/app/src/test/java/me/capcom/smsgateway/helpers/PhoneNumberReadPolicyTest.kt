package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberReadPolicyTest {
    @Test fun readableNumberKeepsItsOriginalFormatting() {
        assertEquals("+1 416-555-0123", readablePhoneNumber("  +1 416-555-0123  "))
        assertEquals("4165550123", readablePhoneNumber("4165550123"))
    }

    @Test fun unknownOrMalformedNumbersAreNotSubmitted() {
        listOf(null, "", "  ", "unknown", "Unknown", "N/A", "号码未知", "416555012",
            "+14165550123ext2", "++14165550123", "1234567890123456", "416.555.0123",
            "+10000000000", "0000000000", "+1234567", "+0441234567", "442345678").forEach {
            assertNull("Unexpected readable modem value: $it", readablePhoneNumber(it))
        }
    }

    @Test fun internationalMaximumLengthIsAccepted() {
        assertEquals("+441234567890123", readablePhoneNumber("+441234567890123"))
    }

    @Test fun completeShortInternationalNumbersRequireTheCountryCode() {
        assertEquals("+4423456", readablePhoneNumber("+4423456"))
        assertNull(readablePhoneNumber("4423456"))
    }

    @Test fun modernValidNumberTakesPriorityWithoutReadingTheLegacyApi() {
        assertEquals("+14165550123", readPhoneNumberFromSources(
            primary = { "+14165550123" },
            fallback = { throw AssertionError("Legacy API must not be queried") },
        ))
    }

    @Test fun modernUnknownOrPermissionFailureFallsBackToReadableLegacyNumber() {
        assertEquals("+14165550123", readPhoneNumberFromSources({ "unknown" }, { "+14165550123" }))
        assertEquals("+14165550123", readPhoneNumberFromSources(
            { throw SecurityException("Synthetic permission denial") }, { "+14165550123" }))
        assertEquals("+14165550123", readPhoneNumberFromSources(
            { throw UnsupportedOperationException("Synthetic modem unsupported") }, { "+14165550123" }))
    }

    @Test fun bothUnavailableSourcesReturnUnknownWithoutThrowing() {
        assertNull(readPhoneNumberFromSources(
            { throw SecurityException("Synthetic permission denial") },
            { throw SecurityException("Synthetic permission denial") },
        ))
        assertNull(readPhoneNumberFromSources({ "" }, { "unknown" }))
    }
}
