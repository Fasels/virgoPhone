package me.capcom.smsgateway.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ConvertersDateTest {
    @Test fun datesKeepTheirOriginalUtcFormatAndMillisecondPrecision() {
        val converter = Converters()
        assertNull(converter.dateToString(null))
        assertNull(converter.dateFromString(null))
        assertEquals("1970-01-01T00:00:00.789Z", converter.dateToString(Date(789)))
        assertEquals(789L, converter.dateFromString("1970-01-01T00:00:00.789Z")!!.time)
    }

    @Test fun concurrentConverterInstancesCannotCorruptStoredDates() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val values = listOf("2030-01-02T03:04:05.789Z", "2026-10-09T01:23:45.001Z", "2031-12-31T23:59:59.999Z")
        val expected = values.map { Converters().dateFromString(it)!!.time }
        try {
            val futures = (0 until 8).map { thread -> pool.submit(Callable {
                val converter = Converters()
                start.await()
                repeat(1_000) { iteration ->
                    val index = (thread + iteration) % values.size
                    assertEquals(expected[index], converter.dateFromString(values[index])!!.time)
                    assertEquals(values[index], converter.dateToString(Date(expected[index])))
                }
            }) }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
