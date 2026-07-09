package me.capcom.smsgateway.extensions

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.GsonBuilder
import java.lang.reflect.Type
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun GsonBuilder.configure(): GsonBuilder {
    return this
        .serializeNulls()
        .registerTypeAdapter(Date::class.java, UtcDateAdapter)
}

private object UtcDateAdapter : JsonSerializer<Date>, JsonDeserializer<Date> {
    private val utc = TimeZone.getTimeZone("UTC")

    override fun serialize(
        src: Date,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        return JsonPrimitive(formatter("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(src))
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Date {
        val value = json.asString
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )

        for (pattern in patterns) {
            try {
                return requireNotNull(formatter(pattern).parse(value))
            } catch (_: ParseException) {
            }
        }

        throw JsonParseException("Invalid ISO-8601 date: $value")
    }

    private fun formatter(pattern: String): SimpleDateFormat {
        return SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = utc
        }
    }
}
