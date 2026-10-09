package me.capcom.smsgateway.modules.gateway

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import me.capcom.smsgateway.helpers.SubscriptionsHelper

internal class SubscriptionsMonitor {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: SubscriptionManager? = null
    private var listener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private val state = SimMetadataSyncState()

    @SuppressLint("MissingPermission")
    fun start(context: Context, onChanged: () -> Unit) {
        val generation = state.start()
        handler.post {
            if (!state.isCurrent(generation) || listener != null ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return@post
            val subscriptions = SubscriptionsHelper.getSubscriptionsManager(context) ?: return@post
            val callback = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    if (state.isCurrent(generation)) onChanged()
                }
            }
            try {
                subscriptions.addOnSubscriptionsChangedListener(callback)
                manager = subscriptions
                listener = callback
            } catch (_: RuntimeException) {
                // Startup and periodic snapshots still work when listeners are unavailable.
            }
        }
    }

    fun stop() {
        state.stop()
        handler.post {
            listener?.let { callback -> runCatching { manager?.removeOnSubscriptionsChangedListener(callback) } }
            listener = null
            manager = null
        }
    }
}
