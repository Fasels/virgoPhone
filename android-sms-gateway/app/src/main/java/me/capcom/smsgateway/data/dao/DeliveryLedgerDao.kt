package me.capcom.smsgateway.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.capcom.smsgateway.data.entities.GatewayDelivery

@Dao
interface DeliveryLedgerDao {
    @Query("SELECT * FROM gateway_delivery WHERE messageId = :messageId")
    fun get(messageId: String): GatewayDelivery?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entry: GatewayDelivery): Long

    @Query("UPDATE gateway_delivery SET claimToken = :claimToken, updatedAt = :now WHERE messageId = :messageId AND phase = 'STAGED'")
    fun refreshClaim(messageId: String, claimToken: String, now: Long)

    @Query("UPDATE gateway_delivery SET phase = 'UNKNOWN', error = 'identity-or-payload-conflict', updatedAt = :now WHERE messageId = :messageId AND phase IN ('STAGED','ACKED','ENQUEUED')")
    fun quarantineConflict(messageId: String, now: Long)

    /** A malformed delivery cannot prevent the remaining batch from being durably received. */
    @Transaction
    fun stageBatch(entries: List<GatewayDelivery>): List<String> {
        val conflicts = mutableListOf<String>()
        for (entry in entries) {
            val existing = get(entry.messageId)
            if (existing == null) {
                insert(entry.copy(phase = "STAGED", error = null))
            } else if (existing.deviceId != entry.deviceId || existing.serverUrl != entry.serverUrl ||
                existing.payloadHash != entry.payloadHash) {
                quarantineConflict(entry.messageId, System.currentTimeMillis())
                conflicts.add(entry.messageId)
            } else if (existing.phase == "STAGED") {
                refreshClaim(entry.messageId, entry.claimToken, System.currentTimeMillis())
            }
        }
        return conflicts
    }

    @Query("SELECT * FROM gateway_delivery WHERE deviceId = :deviceId AND serverUrl = :serverUrl AND phase = 'STAGED' ORDER BY updatedAt, messageId")
    fun listAwaitingAck(deviceId: String, serverUrl: String): List<GatewayDelivery>

    @Query("UPDATE gateway_delivery SET phase = 'ACKED', updatedAt = :now WHERE messageId = :messageId AND claimToken = :claimToken AND payloadHash = :payloadHash AND phase = 'STAGED'")
    fun markAcked(messageId: String, claimToken: String, payloadHash: String, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM gateway_delivery WHERE deviceId = :deviceId AND serverUrl = :serverUrl AND phase = 'ACKED' ORDER BY updatedAt, messageId")
    fun listReady(deviceId: String, serverUrl: String): List<GatewayDelivery>

    @Query("SELECT * FROM gateway_delivery WHERE phase = 'UNKNOWN' ORDER BY updatedAt, messageId")
    fun listUnknown(): List<GatewayDelivery>

    @Query("UPDATE gateway_delivery SET phase = 'UNKNOWN', error = :error, updatedAt = :now WHERE messageId = :messageId AND phase NOT IN ('SUBMITTED','UNKNOWN')")
    fun markUnknown(messageId: String, error: String, now: Long = System.currentTimeMillis()): Int
}
