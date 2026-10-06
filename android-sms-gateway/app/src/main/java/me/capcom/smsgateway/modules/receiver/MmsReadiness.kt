package me.capcom.smsgateway.modules.receiver

/** Tracks each provider row independently: completing a newer ID never skips an older one. */
class MmsReadiness(private val settleMillis: Long = 2_000) {
    private val observations = mutableMapOf<Long, Pair<Int, Long>>()

    fun isReady(id: Long, fingerprint: Int, now: Long): Boolean {
        val previous = observations[id]
        if (previous == null || previous.first != fingerprint) {
            observations[id] = fingerprint to now
            return false
        }
        return now - previous.second >= settleMillis
    }

    fun forget(id: Long) { observations.remove(id) }
}
