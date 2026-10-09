package me.capcom.smsgateway.helpers

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import java.security.MessageDigest

/** Local routing fence. It never invents an unavailable SIM or a phone number. */
object GatewaySimIdentity {
    fun capture(context: Context, simNumber: Int?): String? {
        return try {
        if (Build.VERSION.SDK_INT < 22) return null
        val slot = simNumber?.minus(1) ?: run {
            if (Build.VERSION.SDK_INT < 24) return null
            val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultId < 0) return null
            SubscriptionsHelper.getSimSlotIndex(context, defaultId) ?: return null
        }
        if (slot < 0) return null
        val card = SubscriptionsHelper.getActiveSimCardsSnapshot(context)
            ?.singleOrNull { it.slotIndex == slot } ?: return null
        val subscription = SubscriptionsHelper.getSubscriptionId(context, slot) ?: return null
        if (subscription < 0) return null
        fingerprint(slot, subscription, card.phoneNumber, card.iccid)
    } catch (_: RuntimeException) {
        null
    }
    }

    internal fun fingerprint(slot: Int, subscriptionId: Int, phone: String?, iccid: String?): String {
        var number = readablePhoneNumber(phone)?.replace(Regex("[\\s()+-]"), "").orEmpty()
        if (number.length == 10) number = "1$number"
        val input = "$slot|$subscriptionId|$number|${iccid?.trim().orEmpty()}"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
