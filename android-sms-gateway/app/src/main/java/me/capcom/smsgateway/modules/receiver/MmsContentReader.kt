package me.capcom.smsgateway.modules.receiver

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.IOException
import java.nio.charset.Charset
import java.util.Date

object MmsContentReader {

    data class MmsMessage(
        val id: Long,
        val sender: String,
        val subject: String?,
        val body: String?,
        val date: Date,
        val attachments: List<MmsAttachment>,
        val subscriptionId: Int?,
        val totalPartCount: Int,
        val textPartCount: Int,
        val smilPartCount: Int,
    )

    data class MmsAttachment(
        val partId: Long,
        val contentType: String,
        val name: String?,
        val size: Long?,
        val data: String?,
    )

    fun read(context: Context, mmsId: Long): MmsMessage? {
        val resolver = context.contentResolver

        // 1. Message-level fields
        val mmsUri = Uri.parse("content://mms/$mmsId")
        val projection = mutableListOf("sub", "date")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            projection += "sub_id"
        }

        val mmsCursor = resolver.query(
            mmsUri,
            projection.toTypedArray(),
            null, null, null
        ) ?: return null

        val subject: String?
        val date: Date
        val subscriptionId: Int?
        mmsCursor.use { c ->
            if (!c.moveToFirst()) return null
            subject = c.getString(0)
            // MMS dates are in seconds, not milliseconds
            date = Date(c.getLong(1) * 1000)
            subscriptionId = when {
                projection.size > 2 -> c.getInt(2).takeIf { it >= 0 }
                else -> null
            }
        }

        // 2. Sender address (type 137 = FROM)
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        val sender = resolver.query(
            addrUri,
            arrayOf("address"),
            "type = 137",
            null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        } ?: "unknown"

        // 3. Parts (body text + attachment metadata)
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val partCursor = resolver.query(
            partUri,
            arrayOf("_id", "ct", "text", "name", "fn", "cl", "_data"),
            null, null, "_id"
        ) ?: throw IOException("MMS parts not ready")

        val bodyParts = mutableListOf<String>()
        val attachments = mutableListOf<MmsAttachment>()
        var totalPartCount = 0
        var textPartCount = 0
        var smilPartCount = 0
        var totalBytes = 0L

        partCursor.use { c ->
            require(c.count <= 128) { "MMS has too many parts" }
            while (c.moveToNext()) {
                totalPartCount++
                val partId = c.getLong(0)
                val contentType = c.getString(1) ?: throw IOException("MMS part type not ready")
                val mimeType = contentType.substringBefore(";").trim()

                if (mimeType.equals("text/plain", ignoreCase = true)) {
                    textPartCount++
                    val text = c.getString(6)?.let {
                        readTextPart(resolver, partId, contentType)
                    }
                        ?: c.getString(2) ?: throw IOException("MMS text not ready")

                    if (!text.isNullOrBlank()) {
                        bodyParts.add(text)
                    }

                    continue
                }

                if (mimeType.equals("application/smil", ignoreCase = true)) {
                    smilPartCount++
                    continue
                }

                val name = c.getString(3)
                    ?: c.getString(4)
                    ?: c.getString(5)
                val bytes = readPartBytes(resolver, partId)
                val size = bytes.size.toLong()
                totalBytes += size
                require(totalBytes <= 20L * 1024 * 1024) { "MMS exceeds 20 MiB" }

                attachments.add(
                    MmsAttachment(
                        partId = partId,
                        contentType = mimeType.lowercase(),
                        name = name,
                        size = size,
                        data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    )
                )
            }
        }

        if (totalPartCount == 0 || sender == "unknown") throw IOException("MMS metadata not ready")
        return MmsMessage(
            id = mmsId,
            sender = sender,
            subject = subject,
            body = bodyParts.joinToString("\n").takeIf { it.isNotEmpty() },
            date = date,
            attachments = attachments,
            subscriptionId = subscriptionId,
            totalPartCount = totalPartCount,
            textPartCount = textPartCount,
            smilPartCount = smilPartCount,
        )
    }

    private fun readPartBytes(resolver: ContentResolver, partId: Long): ByteArray {
        return resolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 10 * 1024 * 1024) { "MMS part exceeds 10 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { if (it.isEmpty()) throw IOException("MMS part not ready") }
        } ?: throw IOException("MMS part cannot be opened")
    }

    private fun readTextPart(
        resolver: ContentResolver,
        partId: Long,
        contentType: String?
    ): String? {
        return String(readPartBytes(resolver, partId), parseCharset(contentType))
    }

    private fun parseCharset(contentType: String?): Charset {
        if (contentType == null) return Charsets.UTF_8

        val parts = contentType.split(";").map { it.trim() }
        for (part in parts) {
            if (part.startsWith("charset=", ignoreCase = true)) {
                val charsetName =
                    part.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                return try {
                    Charset.forName(charsetName)
                } catch (_: IllegalArgumentException) {
                    Charsets.UTF_8
                }
            }
        }
        return Charsets.UTF_8
    }
}
