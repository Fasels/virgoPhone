package me.capcom.smsgateway.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.app.ActivityCompat
import me.capcom.smsgateway.modules.localserver.domain.SimCard

object SubscriptionsHelper {
    @Suppress("DEPRECATION")
    fun getSubscriptionsManager(context: Context): SubscriptionManager? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1 -> null
        Build.VERSION.SDK_INT < 31 -> SubscriptionManager.from(context)
        else -> context.getSystemService(SubscriptionManager::class.java)
    }

    @SuppressLint("MissingPermission")
    fun selectAvailableSimSlots(context: Context): Set<Int>? {
        if (!hasPhoneStatePermission(context)) {
            return null
        }

        val subscriptionManager = getSubscriptionsManager(context) ?: return null
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> subscriptionManager.activeSubscriptionInfoList.map { it.simSlotIndex }
                .toSet()
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    fun getSubscriptionId(context: Context, simSlotIndex: Int): Int? {
        if (!hasPhoneStatePermission(context)) {
            return null
        }

        val subscriptionManager = getSubscriptionsManager(context) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            subscriptionManager.activeSubscriptionInfoList.find {
                it.simSlotIndex == simSlotIndex
            }?.subscriptionId
        } else {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getSimSlotIndex(context: Context, subscriptionId: Int): Int? {
        if (!hasPhoneStatePermission(context)) {
            return null
        }

        val subscriptionManager = getSubscriptionsManager(context) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            subscriptionManager.activeSubscriptionInfoList.find {
                it.subscriptionId == subscriptionId
            }?.simSlotIndex
        } else {
            null
        }
    }

    fun hasPhoneStatePermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("InlinedApi")
    fun extractSubscriptionId(context: Context, intent: Intent): Int? {
        return when {
            intent.extras?.containsKey(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX) == true -> intent.extras?.getInt(
                SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
            )

            intent.extras?.containsKey("subscription") == true -> intent.extras?.getInt("subscription")
            intent.extras?.containsKey(SubscriptionManager.EXTRA_SLOT_INDEX) == true -> intent.extras?.getInt(
                SubscriptionManager.EXTRA_SLOT_INDEX
            )?.let { getSubscriptionId(context, it) }

            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    fun getPhoneNumber(context: Context, simSlotIndex: Int): String? {
        return getActiveSimCardsSnapshot(context)?.find { it.slotIndex == simSlotIndex }?.phoneNumber
    }

    /**
     * Retrieves information about all active SIM cards in the device.
     *
     * Returns a list of SimCard objects for each active subscription.
     * Phone numbers will be null if READ_PHONE_STATE permission is not granted.
     * Returns empty list on API levels below LOLLIPOP_MR1 (22).
     *
     * @param context Android context for accessing system services
     * @return List of active SIM cards, empty if none available or API level too low
     */
    @SuppressLint("MissingPermission")
    fun getActiveSimCards(context: Context): List<SimCard> =
        getActiveSimCardsSnapshot(context).orEmpty()

    /** Null means the active subscriptions could not be read, rather than no SIMs. */
    @SuppressLint("MissingPermission")
    fun getActiveSimCardsSnapshot(context: Context): List<SimCard>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return null
        }
        if (!hasPhoneStatePermission(context)) return null
        val subscriptionManager = getSubscriptionsManager(context) ?: return null

        val activeSubscriptions = try {
            subscriptionManager.activeSubscriptionInfoList ?: return null
        } catch (_: RuntimeException) {
            return null
        }
        return activeSubscriptions.map { info ->
            @Suppress("DEPRECATION")
            val phoneNumber = readPhoneNumberFromSources(primary = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
                    PackageManager.PERMISSION_GRANTED) {
                    subscriptionManager.getPhoneNumber(info.subscriptionId)
                } else null
            }, fallback = { info.number })
            SimCard(
                slotIndex = info.simSlotIndex,
                simNumber = info.simSlotIndex + 1,
                phoneNumber = phoneNumber,
                carrierName = info.carrierName?.toString()?.takeIf { it.isNotBlank() },
                iccid = runCatching { info.iccId }.getOrNull()?.takeIf { it.isNotBlank() },
            )
        }
    }
}
