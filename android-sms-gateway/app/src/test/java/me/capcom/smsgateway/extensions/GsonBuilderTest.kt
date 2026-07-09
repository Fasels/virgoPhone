package me.capcom.smsgateway.extensions

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class GsonBuilderTest {
    data class Payload(val timestamp: Date, val error: String? = null)

    @Test
    fun configureSerializesDatesAsUtcIso8601() {
        val gson = GsonBuilder().configure().create()

        val json = gson.toJson(Payload(Date(1_000)))

        assertEquals("""{"timestamp":"1970-01-01T00:00:01.000Z","error":null}""", json)
    }

    @Test
    fun configureParsesOffsetDates() {
        val gson = GsonBuilder().configure().create()

        val payload = gson.fromJson(
            """{"timestamp":"1970-01-01T08:00:01.000+08:00"}""",
            Payload::class.java,
        )

        assertEquals(Date(1_000), payload.timestamp)
    }

    @Test
    fun configureSerializesNullFieldsRequiredByVirgoStatusSchema() {
        val gson = GsonBuilder().configure().create()

        val json = gson.toJson(mapOf<String, String?>("error" to null))

        assertEquals("""{"error":null}""", json)
    }
}
