package me.capcom.smsgateway.modules.gateway

/** A worker from a previous start/stop session must not update the next session. */
internal class SimMetadataSyncState {
    private var generation = 0L
    private var running = false

    @Synchronized fun start(): Long {
        if (!running) generation++
        running = true
        return generation
    }

    @Synchronized fun stop() { running = false; generation++ }
    @Synchronized fun current(): Long? = generation.takeIf { running }
    @Synchronized fun isCurrent(value: Long): Boolean = running && value == generation
}
