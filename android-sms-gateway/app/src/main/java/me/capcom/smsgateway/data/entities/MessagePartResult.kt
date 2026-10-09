package me.capcom.smsgateway.data.entities

import androidx.room.Entity
import me.capcom.smsgateway.domain.ProcessingState

/** Each new PendingIntent has an individual part index; legacy intents remain supported. */
@Entity(tableName = "message_part_result", primaryKeys = ["messageId", "phoneNumber", "partIndex", "eventType"])
data class MessagePartResult(
    val messageId: String,
    val phoneNumber: String,
    val partIndex: Int,
    val eventType: String,
    val state: ProcessingState,
    val error: String?,
    val updatedAt: Long = System.currentTimeMillis(),
)
