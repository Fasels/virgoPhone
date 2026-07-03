package com.example.virgo

import java.util.concurrent.CopyOnWriteArraySet

object AgentInboundEventBus {
    private val listeners = CopyOnWriteArraySet<(AgentInboundMessageEvent) -> Unit>()

    fun subscribe(listener: (AgentInboundMessageEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun publish(event: AgentInboundMessageEvent) {
        listeners.forEach { listener -> listener(event) }
    }
}
