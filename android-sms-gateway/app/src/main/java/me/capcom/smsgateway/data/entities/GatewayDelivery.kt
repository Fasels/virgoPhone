package me.capcom.smsgateway.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable receipt and execution fence. Never contains the device bearer token. */
@Entity(tableName = "gateway_delivery", indices = [Index(value = ["deviceId", "serverUrl", "phase"])])
data class GatewayDelivery(
    @PrimaryKey val messageId: String,
    val deviceId: String,
    val serverUrl: String,
    val payloadJson: String,
    val payloadHash: String,
    val claimToken: String,
    val phase: String = "STAGED",
    val updatedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val simFingerprint: String? = null,
)
