package me.capcom.smsgateway.data.dao

import me.capcom.smsgateway.data.entities.GatewayDelivery
import org.junit.Assert.*
import org.junit.Test

class DeliveryLedgerDaoTest {
    private fun entry(id: String = "m", hash: String = "hash", token: String = "claim") = GatewayDelivery(
        messageId = id, deviceId = "device", serverUrl = "https://server", payloadJson = "{}",
        payloadHash = hash, claimToken = token, simFingerprint = "sim",
    )

    @Test fun `same staged delivery refreshes lease without changing saved payload or SIM identity`() {
        val dao = MemoryLedger()
        dao.stageBatch(listOf(entry()))
        assertTrue(dao.stageBatch(listOf(entry(token = "new-claim").copy(simFingerprint = "new-sim"))).isEmpty())
        assertEquals("new-claim", dao.get("m")?.claimToken)
        assertEquals("sim", dao.get("m")?.simFingerprint)
        assertEquals("STAGED", dao.get("m")?.phase)
    }

    @Test fun `redelivery cannot roll acknowledged or submitting phases back to staged`() {
        for (phase in listOf("ACKED", "ENQUEUED", "SUBMITTING", "SUBMITTED", "UNKNOWN")) {
            val dao = MemoryLedger()
            dao.entries["m"] = entry().copy(phase = phase)
            dao.stageBatch(listOf(entry(token = "new-claim")))
            assertEquals(phase, dao.get("m")?.phase)
            assertEquals("claim", dao.get("m")?.claimToken)
        }
    }

    @Test fun `conflicting payload is isolated while the rest of the batch is persisted`() {
        val dao = MemoryLedger()
        dao.stageBatch(listOf(entry()))
        assertEquals(listOf("m"), dao.stageBatch(listOf(entry(hash = "different"), entry(id = "other"))))
        assertEquals("UNKNOWN", dao.get("m")?.phase)
        assertEquals("hash", dao.get("m")?.payloadHash)
        assertEquals("STAGED", dao.get("other")?.phase)
    }

    @Test fun `different device or endpoint can never reuse a saved executable receipt`() {
        val dao = MemoryLedger()
        dao.entries["m"] = entry().copy(phase = "ENQUEUED")
        assertEquals(listOf("m"), dao.stageBatch(listOf(entry().copy(deviceId = "other-device"))))
        assertEquals("device", dao.get("m")?.deviceId)
        assertEquals("UNKNOWN", dao.get("m")?.phase)
    }

    private class MemoryLedger : DeliveryLedgerDao {
        val entries = linkedMapOf<String, GatewayDelivery>()
        override fun get(messageId: String) = entries[messageId]
        override fun insert(entry: GatewayDelivery): Long {
            if (entries.containsKey(entry.messageId)) return -1
            entries[entry.messageId] = entry
            return entries.size.toLong()
        }
        override fun refreshClaim(messageId: String, claimToken: String, now: Long) {
            val old = entries[messageId] ?: return
            if (old.phase == "STAGED") entries[messageId] = old.copy(claimToken = claimToken, updatedAt = now)
        }
        override fun quarantineConflict(messageId: String, now: Long) {
            val old = entries[messageId] ?: return
            if (old.phase in listOf("STAGED", "ACKED", "ENQUEUED")) entries[messageId] = old.copy(phase = "UNKNOWN", error = "identity-or-payload-conflict")
        }
        override fun listAwaitingAck(deviceId: String, serverUrl: String) = entries.values.filter { it.deviceId == deviceId && it.serverUrl == serverUrl && it.phase == "STAGED" }
        override fun markAcked(messageId: String, claimToken: String, payloadHash: String, now: Long): Int {
            val old = entries[messageId] ?: return 0
            if (old.phase != "STAGED" || old.claimToken != claimToken || old.payloadHash != payloadHash) return 0
            entries[messageId] = old.copy(phase = "ACKED", updatedAt = now)
            return 1
        }
        override fun listReady(deviceId: String, serverUrl: String) = entries.values.filter { it.deviceId == deviceId && it.serverUrl == serverUrl && it.phase == "ACKED" }
        override fun listUnknown() = entries.values.filter { it.phase == "UNKNOWN" }
        override fun markUnknown(messageId: String, error: String, now: Long): Int {
            val old = entries[messageId] ?: return 0
            if (old.phase in listOf("SUBMITTED", "UNKNOWN")) return 0
            entries[messageId] = old.copy(phase = "UNKNOWN", error = error, updatedAt = now)
            return 1
        }
    }
}
