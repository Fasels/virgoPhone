package me.capcom.smsgateway.modules.orchestrator

internal class IdempotentLifecycle {
    private val lock = Any()
    private var running = false

    fun start(action: () -> Unit) {
        synchronized(lock) {
            if (running) return
            running = true
        }

        try {
            action()
        } catch (error: Throwable) {
            synchronized(lock) {
                running = false
            }
            throw error
        }
    }

    fun ensure(action: () -> Unit) {
        synchronized(lock) {
            try {
                action()
                running = true
            } catch (error: Throwable) {
                running = false
                throw error
            }
        }
    }

    fun stop(action: () -> Unit) {
        synchronized(lock) {
            if (!running) return
            running = false
        }

        action()
    }
}
