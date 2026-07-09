package me.capcom.smsgateway.modules.receiver

import me.capcom.smsgateway.modules.receiver.data.InboxMessage

object InboxMessageClassifier {
    data class Result(
        val message: InboxMessage,
        val rawType: String,
        val finalType: String,
        val hasAttachment: Boolean,
        val hasSubject: Boolean,
        val textLength: Int,
        val reason: String,
    )

    fun classify(message: InboxMessage, action: String? = null): Result {
        return when (message) {
            is InboxMessage.Text -> Result(
                message = message,
                rawType = "SMS",
                finalType = "SMS",
                hasAttachment = false,
                hasSubject = false,
                textLength = message.text.length,
                reason = "sms_pdu_broadcast",
            )

            is InboxMessage.Data -> Result(
                message = message,
                rawType = "DATA_SMS",
                finalType = "DATA_SMS",
                hasAttachment = false,
                hasSubject = false,
                textLength = 0,
                reason = "data_sms_broadcast",
            )

            is InboxMessage.MmsHeaders -> Result(
                message = message,
                rawType = "MMS_NOTIFICATION",
                finalType = "MMS",
                hasAttachment = message.size > 0,
                hasSubject = !message.subject.isNullOrBlank(),
                textLength = 0,
                reason = "wap_push_mms_notification",
            )

            is InboxMessage.MMS -> classifyDownloadedMms(message, action)
        }
    }

    private fun classifyDownloadedMms(message: InboxMessage.MMS, action: String?): Result {
        val hasAttachment = message.attachments.isNotEmpty()
        val hasSubject = !message.subject.isNullOrBlank()
        val textLength = message.body?.length ?: 0
        val hasText = !message.body.isNullOrBlank()
        val onlyTextParts = message.totalPartCount?.let { total ->
            total > 0 && total == ((message.textPartCount ?: 0) + (message.smilPartCount ?: 0))
        } ?: true
        val hasSpecialPart = message.totalPartCount?.let { total ->
            total > ((message.textPartCount ?: 0) + (message.smilPartCount ?: 0) + message.attachments.size)
        } ?: false

        if (hasText) {
            return Result(
                message = InboxMessage.Text(
                    text = message.body.orEmpty(),
                    address = message.address,
                    date = message.date,
                    subscriptionId = message.subscriptionId,
                ),
                rawType = message.rawType ?: "MMS_DOWNLOADED",
                finalType = "SMS",
                hasAttachment = hasAttachment,
                hasSubject = hasSubject,
                textLength = textLength,
                reason = when {
                    hasAttachment -> "mms_with_text_and_attachment_uploaded_as_sms_text"
                    hasSubject -> "mms_with_text_and_subject_uploaded_as_sms_text"
                    else -> "text_only_mms_uploaded_as_sms"
                },
            )
        }

        if (hasAttachment) {
            return downloadedMmsResult(
                message = message,
                hasAttachment = true,
                hasSubject = hasSubject,
                textLength = textLength,
                reason = "mms_attachment_without_text_cannot_upload_as_sms",
            )
        }

        if (hasSubject) {
            return downloadedMmsResult(
                message = message,
                hasAttachment = false,
                hasSubject = true,
                textLength = textLength,
                reason = "mms_subject_without_text_cannot_upload_as_sms",
            )
        }

        return downloadedMmsResult(
            message = message,
            hasAttachment = false,
            hasSubject = false,
            textLength = 0,
            reason = if (hasSpecialPart || !onlyTextParts) {
                "mms_non_text_part_without_text_cannot_upload_as_sms"
            } else {
                "mms_without_text_subject_or_attachment_cannot_upload_as_sms"
            },
        )
    }

    private fun downloadedMmsResult(
        message: InboxMessage.MMS,
        hasAttachment: Boolean,
        hasSubject: Boolean,
        textLength: Int,
        reason: String,
    ): Result {
        return Result(
            message = message,
            rawType = message.rawType ?: "MMS_DOWNLOADED",
            finalType = "MMS",
            hasAttachment = hasAttachment,
            hasSubject = hasSubject,
            textLength = textLength,
            reason = reason,
        )
    }
}
