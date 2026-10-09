package me.capcom.smsgateway.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.capcom.smsgateway.data.entities.Message
import me.capcom.smsgateway.data.entities.GatewayDelivery
import me.capcom.smsgateway.data.entities.MessageStatusOutbox
import me.capcom.smsgateway.data.entities.MessagePartResult
import me.capcom.smsgateway.modules.messages.MessageStatePolicy
import me.capcom.smsgateway.data.entities.MessageRecipient
import me.capcom.smsgateway.data.entities.MessageState
import me.capcom.smsgateway.data.entities.MessageWithRecipients
import me.capcom.smsgateway.data.entities.MessagesStats
import me.capcom.smsgateway.data.entities.MessagesTotals
import me.capcom.smsgateway.data.entities.RecipientState
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.ProcessingState
import java.util.Date

@Dao
interface MessagesDao {
    //#region Read
    @Query("SELECT COUNT(*) as count, MAX(processedAt) as lastTimestamp FROM message WHERE state <> 'Pending' AND state <> 'Failed' AND processedAt >= :timestamp")
    fun countProcessedFrom(timestamp: Long): MessagesStats

    @Query("SELECT COUNT(*) as count, MAX(processedAt) as lastTimestamp FROM message WHERE state = 'Failed' AND processedAt >= :timestamp")
    fun countFailedFrom(timestamp: Long): MessagesStats

    @Query(
        """
        SELECT
            COUNT(*) as total,
            COALESCE(SUM(CASE WHEN state = 'Pending' THEN 1 ELSE 0 END), 0) as pending,
            COALESCE(SUM(CASE WHEN state = 'Sent' THEN 1 ELSE 0 END), 0) as sent,
            COALESCE(SUM(CASE WHEN state = 'Delivered' THEN 1 ELSE 0 END), 0) as delivered,
            COALESCE(SUM(CASE WHEN state = 'Failed' THEN 1 ELSE 0 END), 0) as failed
        FROM message
    """
    )
    fun getMessagesStats(): LiveData<MessagesTotals>

    @Query("SELECT * FROM message ORDER BY createdAt DESC LIMIT :limit")
    fun selectLast(limit: Int): LiveData<List<Message>>

    /**
     * FIFO: oldest pending first (priority DESC, createdAt ASC)
     */
    @Transaction
    @Query("SELECT *, `rowid` FROM message WHERE state = 'Pending' AND ((source = 'Local' AND NOT EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = message.id)) OR EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = message.id AND d.phase = 'ENQUEUED')) AND (scheduleAt IS NULL OR scheduleAt <= :now) ORDER BY priority DESC, createdAt ASC LIMIT 1")
    fun getPendingFifo(now: Date): MessageWithRecipients?

    /**
     * LIFO: newest pending first (priority DESC, createdAt DESC)
     */
    @Transaction
    @Query("SELECT *, `rowid` FROM message WHERE state = 'Pending' AND ((source = 'Local' AND NOT EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = message.id)) OR EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = message.id AND d.phase = 'ENQUEUED')) AND (scheduleAt IS NULL OR scheduleAt <= :now) ORDER BY priority DESC, createdAt DESC LIMIT 1")
    fun getPendingLifo(now: Date): MessageWithRecipients?

    @Query("SELECT strftime('%s', MIN(COALESCE(`scheduleAt`, '1970-01-01T00:00:00.000Z'))) * 1000 + CAST(substr(MIN(COALESCE(`scheduleAt`, '1970-01-01T00:00:00.000Z')), 21, 3) AS INTEGER) FROM Message WHERE state = 'Pending' AND ((source = 'Local' AND NOT EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = Message.id)) OR EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = Message.id AND d.phase = 'ENQUEUED'))")
    fun nextScheduledTime(): Long?

    @Transaction
    @Query("SELECT *, `rowid` FROM message WHERE id = :id")
    fun get(id: String): MessageWithRecipients?

    /**
     * Count messages based on state and date range
     */
    @Query("SELECT COUNT(*) as count FROM message WHERE source = :source AND (:state IS NULL OR state = :state) AND createdAt BETWEEN :start AND :end")
    fun count(source: EntitySource, state: ProcessingState?, start: Long, end: Long): Int

    /**
     * Get messages with pagination and filtering
     */
    @Transaction
    @Query("SELECT *, `rowid` FROM message WHERE source = :source AND (:state IS NULL OR state = :state) AND createdAt BETWEEN :start AND :end ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun select(
        source: EntitySource,
        state: ProcessingState?,
        start: Long,
        end: Long,
        limit: Int,
        offset: Int
    ): List<MessageWithRecipients>
    //#endregion

    @Insert
    fun _insert(message: Message)

    @Insert
    fun _insertRecipients(recipient: List<MessageRecipient>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun _insertMessageState(state: MessageState)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun _insertRecipientStates(state: List<RecipientState>)

    @Query(
        "INSERT INTO recipientstate(messageId, phoneNumber, state, updatedAt) " +
                "SELECT :messageId, phoneNumber, :state, strftime('%s', 'now') * 1000 " +
                "FROM messagerecipient " +
                "WHERE messageId = :messageId"
    )
    fun _insertRecipientStatesByMessage(
        messageId: String,
        state: me.capcom.smsgateway.domain.ProcessingState
    )

    @Transaction
    fun insert(message: MessageWithRecipients) {
        _insert(message.message)
        _insertMessageState(
            MessageState(
                message.message.id,
                message.message.state,
                System.currentTimeMillis()
            )
        )
        _insertRecipients(message.recipients)
        _insertRecipientStates(message.recipients.map {
            RecipientState(
                message.message.id,
                it.phoneNumber,
                it.state,
                System.currentTimeMillis()
            )
        })
    }

    @Query("UPDATE message SET state = :state, processedAt = CASE WHEN :state = 'Processed' THEN COALESCE(processedAt, :now) ELSE processedAt END WHERE id = :id AND state <> :state AND (state = 'Pending' OR (state = 'Processed' AND :state IN ('Sent','Delivered','Failed')) OR (state = 'Sent' AND :state IN ('Delivered','Failed')))")
    fun _updateMessageState(id: String, state: ProcessingState, now: Long): Int

    @Transaction
    fun updateMessageState(id: String, state: ProcessingState): Int {
        val now = System.currentTimeMillis()
        val changed = _updateMessageState(id, state, now)
        if (changed > 0) {
            _insertMessageState(MessageState(id, state, now))
            queueStatus(id, now)
        }
        return changed
    }

    @Transaction
    fun setMessageProcessed(id: String): Int = updateMessageState(id, ProcessingState.Processed)

    @Query("SELECT * FROM messagerecipient WHERE messageId = :id")
    fun recipients(id: String): List<MessageRecipient>

    @Query("UPDATE messagerecipient SET state = :state, error = :error WHERE messageId = :id AND phoneNumber = :phoneNumber AND state <> :state AND (state = 'Pending' OR (state = 'Processed' AND :state IN ('Sent','Delivered','Failed')) OR (state = 'Sent' AND :state IN ('Delivered','Failed')))")
    fun _updateRecipientState(id: String, phoneNumber: String, state: ProcessingState, error: String?): Int

    fun refreshAggregateState(id: String, now: Long) {
        val aggregate = MessageStatePolicy.aggregate(recipients(id).map { it.state })
        if (_updateMessageState(id, aggregate, now) > 0) {
            _insertMessageState(MessageState(id, aggregate, now))
        }
    }

    @Transaction
    fun updateRecipientState(id: String, phoneNumber: String, state: ProcessingState, error: String?): Int {
        val now = System.currentTimeMillis()
        val changed = _updateRecipientState(id, phoneNumber, state, error)
        if (changed > 0) {
            _insertRecipientStates(listOf(RecipientState(id, phoneNumber, state, now)))
            refreshAggregateState(id, now)
            queueStatus(id, now)
        }
        return changed
    }

    @Transaction
    fun updateRecipientsState(id: String, state: ProcessingState, error: String?): Int {
        val now = System.currentTimeMillis()
        val changedRecipients = recipients(id).filter { MessageStatePolicy.accepts(it.state, state) }
        for (recipient in changedRecipients) {
            if (_updateRecipientState(id, recipient.phoneNumber, state, error) > 0) {
                _insertRecipientStates(listOf(RecipientState(id, recipient.phoneNumber, state, now)))
            }
        }
        if (changedRecipients.isNotEmpty()) {
            refreshAggregateState(id, now)
            queueStatus(id, now)
        }
        return changedRecipients.size
    }

    @Query("INSERT OR IGNORE INTO message_status_outbox(messageId, revision, updatedAt, blockedReason) SELECT id, 0, :now, NULL FROM message WHERE id = :id AND source IN ('Cloud','Gateway')")
    fun _ensureStatus(id: String, now: Long)

    @Query("UPDATE message_status_outbox SET revision = revision + 1, updatedAt = :now, blockedReason = NULL WHERE messageId = :id")
    fun _bumpStatus(id: String, now: Long)

    @Transaction
    fun queueStatus(id: String, now: Long = System.currentTimeMillis()) {
        _ensureStatus(id, now)
        _bumpStatus(id, now)
    }

    @Query("SELECT * FROM message_status_outbox WHERE blockedReason IS NULL AND reportedRevision < revision ORDER BY CASE WHEN EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = message_status_outbox.messageId) THEN 0 ELSE 1 END, updatedAt DESC, messageId LIMIT :limit")
    fun nextPendingEntries(limit: Int = 100): List<MessageStatusOutbox>

    @Query("SELECT o.messageId FROM message_status_outbox o LEFT JOIN gateway_delivery d ON d.messageId = o.messageId WHERE d.messageId IS NULL")
    fun legacyStatusMessageIds(): List<String>

    @Query("UPDATE message_status_outbox SET reportedRevision = :revision WHERE messageId = :messageId AND revision = :revision")
    fun complete(messageId: String, revision: Long): Int

    @Query("UPDATE message_status_outbox SET blockedReason = :error WHERE messageId = :messageId")
    fun markBlocked(messageId: String, error: String): Int

    @Query("UPDATE message_status_outbox SET blockedReason = :error WHERE messageId = :messageId AND revision = :revision")
    fun markBlocked(messageId: String, revision: Long, error: String): Int

    @Query("SELECT * FROM gateway_delivery WHERE messageId = :id")
    fun delivery(id: String): GatewayDelivery?

    @Query("UPDATE gateway_delivery SET phase = :phase, updatedAt = :now WHERE messageId = :id AND phase = :expected")
    fun _changeDeliveryPhase(id: String, expected: String, phase: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun _insertDelivery(entry: GatewayDelivery): Long

    @Transaction
    fun insertLegacyCheck(message: MessageWithRecipients, deviceId: String, serverUrl: String, simFingerprint: String?): MessageWithRecipients {
        val id = message.message.id
        check(id.startsWith("check_")) { "Only explicit diagnostic messages support legacy transport" }
        check(deviceId.isNotBlank() && serverUrl.isNotBlank()) { "Diagnostic messages require an existing gateway identity" }
        val existing = get(id)
        if (existing != null) return existing
        insert(message)
        _insertDelivery(GatewayDelivery(
            messageId = id, deviceId = deviceId, serverUrl = serverUrl, payloadJson = "", payloadHash = "",
            claimToken = "", phase = if (simFingerprint == null) "UNKNOWN" else "ENQUEUED",
            error = if (simFingerprint == null) "diagnostic-sim-unavailable" else null,
            simFingerprint = simFingerprint,
        ))
        return requireNotNull(get(id))
    }

    @Transaction
    fun insertAcknowledged(message: MessageWithRecipients): MessageWithRecipients {
        val id = message.message.id
        val ledger = requireNotNull(delivery(id)) { "No durable gateway receipt for $id" }
        val existing = get(id)
        if (ledger.phase == "ENQUEUED" && existing != null) return existing
        check(ledger.phase == "ACKED") { "Gateway receipt is not acknowledged for $id" }
        if (existing != null) {
            // A pre-existing non-pending row must never be sent again after redelivery.
            check(existing.message.state != ProcessingState.Pending) { "Unverified existing pending message $id" }
            _changeDeliveryPhase(id, "ACKED", "SUBMITTED", System.currentTimeMillis())
            queueStatus(id)
            return existing
        }
        check(message.message.source in listOf(EntitySource.Cloud, EntitySource.Gateway))
        insert(message)
        check(_changeDeliveryPhase(id, "ACKED", "ENQUEUED", System.currentTimeMillis()) == 1)
        return requireNotNull(get(id))
    }

    @Query("INSERT OR IGNORE INTO gateway_delivery(messageId, deviceId, serverUrl, payloadJson, payloadHash, claimToken, phase, updatedAt, error) SELECT id, '', '', '', '', '', 'ENQUEUED', :now, NULL FROM message WHERE id = :id AND source = 'Local'")
    fun _ensureLocalFence(id: String, now: Long)

    @Transaction
    fun claimExecution(id: String): Boolean {
        val now = System.currentTimeMillis()
        _ensureLocalFence(id, now)
        return _changeDeliveryPhase(id, "ENQUEUED", "SUBMITTING", now) == 1
    }

    @Query("UPDATE gateway_delivery SET phase = 'UNKNOWN', error = 'process-ended-during-submission', updatedAt = :now WHERE phase = 'SUBMITTING'")
    fun recoverUncertainSubmissions(now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE gateway_delivery SET phase = 'UNKNOWN', error = :error, updatedAt = :now WHERE messageId = :id AND phase IN ('ACKED','ENQUEUED','SUBMITTING')")
    fun quarantineExecution(id: String, error: String, now: Long = System.currentTimeMillis()): Int

    fun finishSubmission(id: String) {
        _changeDeliveryPhase(id, "SUBMITTING", "SUBMITTED", System.currentTimeMillis())
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun _insertPartResult(result: MessagePartResult): Long

    @Query("SELECT * FROM message_part_result WHERE messageId = :id AND phoneNumber = :phone AND eventType = :eventType")
    fun partResults(id: String, phone: String, eventType: String): List<MessagePartResult>

    @Transaction
    fun recordPartResult(result: MessagePartResult): Int {
        val message = get(result.messageId) ?: return 0
        val expectedParts = message.message.partsCount ?: 1
        if (result.partIndex !in 0 until expectedParts || _insertPartResult(result) == -1L) return 0
        val parts = partResults(result.messageId, result.phoneNumber, result.eventType)
        val failure = parts.firstOrNull { it.state == ProcessingState.Failed }
        val outcome = MessageStatePolicy.partOutcome(expectedParts, parts.map { it.state }) ?: return 0
        return updateRecipientState(result.messageId, result.phoneNumber, outcome, failure?.error ?: result.error)
    }

    @Query("UPDATE message SET simNumber = :simNumber WHERE id = :id")
    fun updateSimNumber(
        id: String,
        simNumber: Int
    )

    @Query("UPDATE message SET partsCount = :partsCount WHERE id = :id")
    fun updatePartsCount(id: String, partsCount: Int)

    @Query("DELETE FROM message WHERE createdAt < :until AND state <> 'Pending' AND NOT EXISTS (SELECT 1 FROM message_status_outbox o WHERE o.messageId = message.id AND o.reportedRevision < o.revision) AND NOT EXISTS (SELECT 1 FROM gateway_delivery d WHERE d.messageId = message.id AND d.phase IN ('STAGED','ACKED','ENQUEUED','SUBMITTING','UNKNOWN'))")
    suspend fun truncateLog(until: Long)
}
