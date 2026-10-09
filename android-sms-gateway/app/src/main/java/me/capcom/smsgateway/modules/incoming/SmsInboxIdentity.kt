package me.capcom.smsgateway.modules.incoming

import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import java.security.MessageDigest

/** New inbox identities include the original receive time; existing rows are never rewritten. */
internal fun smsInboxIdentity(message: InboxMessage): String? {
    val prefix = when (message) {
        is InboxMessage.Text -> "text:"
        is InboxMessage.Data -> "data:"
        else -> return null
    }
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: ByteArray?) {
        val bytes = value ?: byteArrayOf()
        digest.update((if (value == null) "-1:" else "${bytes.size}:").toByteArray(Charsets.UTF_8))
        digest.update(bytes)
    }
    field(prefix.toByteArray(Charsets.UTF_8))
    field(message.address.toByteArray(Charsets.UTF_8))
    field(message.subscriptionId?.toString()?.toByteArray(Charsets.UTF_8))
    field(message.date.time.toString().toByteArray(Charsets.UTF_8))
    field(when (message) {
        is InboxMessage.Text -> message.text.toByteArray(Charsets.UTF_8)
        is InboxMessage.Data -> message.data
        else -> null
    })
    // The server's receive identifier limit is 64 characters (224-bit digest plus type/version).
    return prefix + "v2:" + digest.digest().joinToString("") { "%02x".format(it) }.take(56)
}
