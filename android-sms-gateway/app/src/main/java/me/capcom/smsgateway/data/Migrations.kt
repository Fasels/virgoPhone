package me.capcom.smsgateway.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            UPDATE message
            SET validUntil = strftime('%FT%TZ', createdAt / 1000 + 86400, 'unixepoch')
            WHERE validUntil IS NULL AND state = 'Pending'
        """.trimIndent()
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create a new table with the desired schema (without the text column)
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_new (
                `id` TEXT NOT NULL,
                `withDeliveryReport` INTEGER NOT NULL DEFAULT 1,
                `simNumber` INTEGER,
                `validUntil` TEXT,
                `isEncrypted` INTEGER NOT NULL DEFAULT 0,
                `skipPhoneValidation` INTEGER NOT NULL DEFAULT 0,
                `priority` INTEGER NOT NULL DEFAULT 0,
                `type` TEXT NOT NULL DEFAULT 'Text',
                `source` TEXT NOT NULL DEFAULT 'Local',
                `content` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                `processedAt` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        // Copy data from the old table to the new table
        database.execSQL(
            """
            INSERT INTO message_new (
                id,
                withDeliveryReport,
                simNumber,
                validUntil,
                isEncrypted,
                skipPhoneValidation,
                priority,
                source,
                type,
                content,
                state,
                createdAt,
                processedAt
            ) SELECT
                id,
                withDeliveryReport,
                simNumber,
                validUntil,
                isEncrypted,
                skipPhoneValidation,
                priority,
                source,
                'Text' AS type,
                '{"text": "' || replace(replace(text, '\\', '\\\\'), '"', '\\"') || '"}' AS content,
                state,
                createdAt,
                processedAt
            FROM message
        """.trimIndent()
        )

        // Drop the old table and rename the new table to the original name
        database.execSQL("DROP TABLE IF EXISTS message")
        database.execSQL("ALTER TABLE message_new RENAME TO message")

        // Create indices
        database.execSQL("CREATE INDEX IF NOT EXISTS index_Message_state ON message (state)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_Message_createdAt ON message (createdAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_Message_processedAt ON message (processedAt)")
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            DELETE FROM incoming_messages WHERE 1
        """.trimIndent()
        )
    }
}

/** Add reliability metadata without replacing, deleting or changing any existing message. */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS gateway_delivery (
                messageId TEXT NOT NULL PRIMARY KEY,
                deviceId TEXT NOT NULL,
                serverUrl TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                payloadHash TEXT NOT NULL,
                claimToken TEXT NOT NULL,
                phase TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                error TEXT,
                simFingerprint TEXT
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS index_gateway_delivery_deviceId_serverUrl_phase ON gateway_delivery(deviceId, serverUrl, phase)")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS message_status_outbox (
                messageId TEXT NOT NULL PRIMARY KEY,
                revision INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                blockedReason TEXT,
                reportedRevision INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS message_part_result (
                messageId TEXT NOT NULL,
                phoneNumber TEXT NOT NULL,
                partIndex INTEGER NOT NULL,
                eventType TEXT NOT NULL,
                state TEXT NOT NULL,
                error TEXT,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(messageId, phoneNumber, partIndex, eventType)
            )
        """.trimIndent())
        // Old cloud Pending rows have no durable receipt/ACK or submission fence. Their
        // original state and history stay intact; do not guess whether they were submitted.
        database.execSQL("""
            INSERT OR IGNORE INTO gateway_delivery(messageId, deviceId, serverUrl, payloadJson, payloadHash, claimToken, phase, updatedAt, error)
            SELECT id, '', '', '', '', '', 'UNKNOWN', createdAt, 'legacy-unverified'
            FROM message WHERE source IN ('Cloud','Gateway') AND state = 'Pending'
        """.trimIndent())
        database.execSQL("""
            INSERT OR IGNORE INTO message_status_outbox(messageId, revision, updatedAt, blockedReason)
            SELECT id, 1, COALESCE(processedAt, createdAt), NULL
            FROM message WHERE source IN ('Cloud','Gateway') AND state <> 'Pending'
        """.trimIndent())
    }
}
