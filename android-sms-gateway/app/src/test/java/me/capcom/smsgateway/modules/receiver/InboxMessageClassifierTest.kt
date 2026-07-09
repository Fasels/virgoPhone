package me.capcom.smsgateway.modules.receiver

import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class InboxMessageClassifierTest {
    private val receivedAt = Date(1_718_000_000_000)

    @Test
    fun keepsPlainSingleRecipientSmsAsSms() {
        val message = InboxMessage.Text(
            text = "hello",
            address = "+15551234567",
            date = receivedAt,
            subscriptionId = 1,
        )

        val result = InboxMessageClassifier.classify(
            message,
            "android.provider.Telephony.SMS_RECEIVED"
        )

        assertSame(message, result.message)
        assertEquals("SMS", result.rawType)
        assertEquals("SMS", result.finalType)
        assertFalse(result.hasAttachment)
        assertFalse(result.hasSubject)
        assertEquals(5, result.textLength)
        assertEquals("sms_pdu_broadcast", result.reason)
    }

    @Test
    fun keepsConcatenatedLongSmsAsSms() {
        val longText = "part-one-" + "x".repeat(180) + "-part-two"
        val message = InboxMessage.Text(
            text = longText,
            address = "+15551234567",
            date = receivedAt,
            subscriptionId = 1,
        )

        val result = InboxMessageClassifier.classify(message)

        assertSame(message, result.message)
        assertEquals("SMS", result.finalType)
        assertEquals(longText.length, result.textLength)
    }

    @Test
    fun uploadsDownloadedMmsWithTextAsSmsEvenWhenAttachmentExists() {
        val message = downloadedMms(
            body = "photo caption",
            attachments = listOf(
                InboxMessage.MMS.Attachment(
                    partId = 10,
                    contentType = "image/jpeg",
                    name = "image.jpg",
                    size = 2048,
                    data = null,
                )
            ),
            totalPartCount = 3,
            textPartCount = 1,
            smilPartCount = 1,
        )

        val result = InboxMessageClassifier.classify(message)

        assertEquals("MMS_DOWNLOADED", result.rawType)
        assertEquals("SMS", result.finalType)
        assertTrue(result.message is InboxMessage.Text)
        assertTrue(result.hasAttachment)
        assertEquals("photo caption", (result.message as InboxMessage.Text).text)
        assertEquals("mms_with_text_and_attachment_uploaded_as_sms_text", result.reason)
    }

    @Test
    fun fallsBackToSmsWhenProviderMarksPlainTextAsMm() {
        val message = downloadedMms(
            body = "verification code 123456",
            subject = null,
            attachments = emptyList(),
            rawType = "MM",
            totalPartCount = 1,
            textPartCount = 1,
            smilPartCount = 0,
        )

        val result = InboxMessageClassifier.classify(message)

        assertEquals("MM", result.rawType)
        assertEquals("SMS", result.finalType)
        assertFalse(result.hasAttachment)
        assertFalse(result.hasSubject)
        assertEquals("verification code 123456", (result.message as InboxMessage.Text).text)
        assertEquals("text_only_mms_uploaded_as_sms", result.reason)
    }

    @Test
    fun keepsSubjectOnlyMmsOutOfSmsUploadBecauseThereIsNoTextBody() {
        val message = downloadedMms(
            body = null,
            subject = "Subject only",
            attachments = emptyList(),
            totalPartCount = 0,
            textPartCount = 0,
            smilPartCount = 0,
        )

        val result = InboxMessageClassifier.classify(message)

        assertSame(message, result.message)
        assertEquals("MMS", result.finalType)
        assertTrue(result.hasSubject)
        assertEquals("mms_subject_without_text_cannot_upload_as_sms", result.reason)
    }

    @Test
    fun keepsSpecialPartWithoutTextOutOfSmsUploadWithClearReason() {
        val message = downloadedMms(
            body = null,
            subject = null,
            attachments = emptyList(),
            totalPartCount = 2,
            textPartCount = 0,
            smilPartCount = 1,
        )

        val result = InboxMessageClassifier.classify(message)

        assertSame(message, result.message)
        assertEquals("MMS", result.finalType)
        assertFalse(result.hasSubject)
        assertFalse(result.hasAttachment)
        assertEquals("mms_non_text_part_without_text_cannot_upload_as_sms", result.reason)
    }

    @Test
    fun samsungMmTypeDoesNotSkipPlainText() {
        val message = downloadedMms(
            body = "samsung delivered this through a provider MM row",
            subject = null,
            attachments = emptyList(),
            rawType = "x-samsung-MM",
            totalPartCount = 1,
            textPartCount = 1,
            smilPartCount = 0,
        )

        val result = InboxMessageClassifier.classify(message)

        assertEquals("x-samsung-MM", result.rawType)
        assertEquals("SMS", result.finalType)
        assertTrue(result.message is InboxMessage.Text)
        assertEquals(
            "samsung delivered this through a provider MM row",
            (result.message as InboxMessage.Text).text
        )
    }

    private fun downloadedMms(
        body: String?,
        subject: String? = null,
        attachments: List<InboxMessage.MMS.Attachment> = emptyList(),
        rawType: String = "MMS_DOWNLOADED",
        totalPartCount: Int? = null,
        textPartCount: Int? = null,
        smilPartCount: Int? = null,
    ) = InboxMessage.MMS(
        messageId = "42",
        body = body,
        subject = subject,
        attachments = attachments,
        address = "+15551234567",
        date = receivedAt,
        subscriptionId = 1,
        totalPartCount = totalPartCount,
        textPartCount = textPartCount,
        smilPartCount = smilPartCount,
        rawType = rawType,
    )
}
