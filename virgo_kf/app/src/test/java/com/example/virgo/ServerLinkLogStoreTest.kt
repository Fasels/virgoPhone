package com.example.virgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLinkLogStoreTest {
    @Test
    fun storeKeepsOnlyLatestFiftyEntries() {
        val store = ServerLinkLogStore()

        repeat(60) { index ->
            store.record(
                method = "GET",
                path = "/agent/v1/items/$index",
                statusCode = 200,
                message = "OK",
            )
        }

        assertEquals(50, store.entries.size)
        assertEquals("/agent/v1/items/10", store.entries.first().path)
        assertEquals("/agent/v1/items/59", store.entries.last().path)
    }

    @Test
    fun entriesDescribeRequestOutcomeWithoutSensitiveValues() {
        val store = ServerLinkLogStore(nowProvider = { 1234L })

        store.record(
            method = "POST",
            path = "/agent/v1/auth/login",
            statusCode = 401,
            message = "Login failed",
        )

        val entry = store.entries.single()
        assertEquals(1234L, entry.timestampMillis)
        assertEquals("POST", entry.method)
        assertEquals("/agent/v1/auth/login", entry.path)
        assertEquals(401, entry.statusCode)
        assertEquals("Login failed", entry.message)
        assertTrue(entry.toDisplayText().contains("POST /agent/v1/auth/login"))
        assertTrue(entry.toDisplayText().contains("HTTP 401"))
    }

    @Test
    fun storeStartsFromSavedEntriesAndNotifiesWhenEntriesChange() {
        val saved = ServerLinkLogEntry(
            timestampMillis = 1000L,
            method = "GET",
            path = "/agent/v1/me",
            statusCode = 200,
            message = "OK",
        )
        var persisted: List<ServerLinkLogEntry> = emptyList()
        val store = ServerLinkLogStore(
            initialEntries = listOf(saved),
            nowProvider = { 2000L },
            onEntriesChanged = { persisted = it },
        )

        store.record("POST", "/agent/v1/auth/login", 401, "Login failed")

        assertEquals(saved, store.entries.first())
        assertEquals(2, persisted.size)
        assertEquals("/agent/v1/me", persisted.first().path)
        assertEquals("/agent/v1/auth/login", persisted.last().path)
    }

    @Test
    fun codecRoundTripsEntriesForDiskStorage() {
        val entries = listOf(
            ServerLinkLogEntry(1000L, "GET", "/agent/v1/me", 200, "OK"),
            ServerLinkLogEntry(2000L, "POST", "/agent/v1/auth/login", null, "Network\nfailed"),
        )

        val decoded = ServerLinkLogCodec.decode(ServerLinkLogCodec.encode(entries))

        assertEquals(entries, decoded)
    }
}
