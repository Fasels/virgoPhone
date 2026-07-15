package me.capcom.smsgateway.services

import me.capcom.smsgateway.modules.orchestrator.IdempotentLifecycle

internal class ResidentServiceLifecycle(
    private val startRuntime: () -> Unit,
    private val stopRuntime: () -> Unit,
) {
    private val lifecycle = IdempotentLifecycle()

    fun start() {
        try {
            lifecycle.ensure(startRuntime)
        } catch (error: Throwable) {
            stopRuntime()
            throw error
        }
    }

    fun stop() = lifecycle.stop(stopRuntime)
}
