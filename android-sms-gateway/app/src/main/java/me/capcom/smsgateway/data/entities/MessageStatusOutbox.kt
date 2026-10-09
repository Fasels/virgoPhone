package me.capcom.smsgateway.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "message_status_outbox")
data class MessageStatusOutbox(
    @PrimaryKey val messageId: String,
    val revision: Long,
    val updatedAt: Long,
    val blockedReason: String? = null,
    @ColumnInfo(defaultValue = "0") val reportedRevision: Long = 0,
)
