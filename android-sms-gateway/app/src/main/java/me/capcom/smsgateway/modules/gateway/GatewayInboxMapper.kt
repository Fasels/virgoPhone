package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.incoming.db.IncomingMessageType
import me.capcom.smsgateway.modules.receiver.data.InboxMessage

object GatewayInboxMapper {
    fun toRequest(
        messageId: String,
        message: InboxMessage,
        simNumber: Int?,
        recipient: String?,
    ): GatewayApi.InboxMessageRequest {
        return when (message) {
            is InboxMessage.Text -> GatewayApi.InboxMessageRequest(
                id = messageId,
                type = IncomingMessageType.SMS,
                sender = message.address,
                recipient = recipient,
                simNumber = simNumber,
                subscriptionId = message.subscriptionId,
                receivedAt = message.date,
                textMessage = GatewayApi.InboxTextMessage(message.text),
                dataMessage = null,
            )

            is InboxMessage.Data -> GatewayApi.InboxMessageRequest(
                id = messageId,
                type = IncomingMessageType.DATA_SMS,
                sender = message.address,
                recipient = recipient,
                simNumber = simNumber,
                subscriptionId = message.subscriptionId,
                receivedAt = message.date,
                textMessage = null,
                dataMessage = GatewayApi.InboxDataMessage(
                    (message.data ?: byteArrayOf()).toBase64()
                ),
            )

            is InboxMessage.MmsHeaders,
            is InboxMessage.MMS -> throw IllegalArgumentException(
                "MMS inbox upload is not supported yet"
            )
        }
    }

    private fun ByteArray.toBase64(): String {
        if (isEmpty()) return ""

        val result = StringBuilder(((size + 2) / 3) * 4)
        var index = 0
        while (index < size) {
            val b0 = this[index++].toInt() and 0xff
            val hasB1 = index < size
            val b1 = if (hasB1) this[index++].toInt() and 0xff else 0
            val hasB2 = index < size
            val b2 = if (hasB2) this[index++].toInt() and 0xff else 0

            result.append(BASE64_ALPHABET[b0 shr 2])
            result.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            result.append(
                if (hasB1) {
                    BASE64_ALPHABET[((b1 and 0x0f) shl 2) or (b2 shr 6)]
                } else {
                    '='
                }
            )
            result.append(if (hasB2) BASE64_ALPHABET[b2 and 0x3f] else '=')
        }

        return result.toString()
    }

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
